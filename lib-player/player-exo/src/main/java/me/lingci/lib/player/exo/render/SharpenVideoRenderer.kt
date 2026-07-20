package me.lingci.lib.player.exo.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import me.lingci.lib.base.util.Log
import android.util.Log as AndroidLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 双 pass 锐化 GL Renderer（Mali GPU 优化版）。
 *
 * Pass 1 (blit):  OES 外部纹理 → RGBA FBO（1 次采样，YUV→RGB 只做 1 次）
 * Pass 2 (SGSR1): RGBA FBO → SGSR1 shader → 屏幕（~25 次采样，读快速 sampler2D）
 *
 * 这样避免了 SGSR1 的 25 次采样都走 OES 外部纹理（每次都做 YUV→RGB 转换），
 * 对 Mali GPU（麒麟处理器）性能提升 30-50%。
 *
 * 移植自 Google ExoPlayer demo + SGSR1 (Qualcomm) + Arm Mali 优化建议。
 */
class SharpenVideoRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val useNcnn: Boolean = false
) : GLSurfaceView.Renderer {

    companion object {
        private const val SP_KEY_STRENGTH = "labSuperResolutionStrength"
        private const val SP_STRENGTH_DEFAULT = 1.0f
        private const val STRENGTH_MIN = 0.0f
        private const val STRENGTH_MAX = 3.0f
    }

    private val frameAvailable = AtomicBoolean(false)
    private val transformMatrix = FloatArray(16)

    // OES 外部纹理（ExoPlayer 写入）
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    // Pass 1: blit program (OES → RGBA FBO)
    private var blitProgram: GlProgram? = null

    // Pass 2: SGSR1 program (RGBA FBO → screen)
    private var sgsrProgram: GlProgram? = null

    // RGBA FBO（Pass 1 输出，Pass 2 输入）
    private var fboId = 0
    private var fboTextureId = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private var glSurfaceViewRef: WeakReference<GLSurfaceView>? = null
    private var videoWidth = 0
    private var videoHeight = 0

    // ===== NCNN 混合模式字段 =====
    private var ncnnSr: me.lingci.lib.player.exo.ncnn.NcnnSuperResolution? = null
    private var frameCount = 0
    private var ncnnOutputTexId = 0
    private var pixelBuffer: java.nio.ByteBuffer? = null
    private var blitProgramForNcnn: GlProgram? = null

    // 异步推理：双缓冲 + 频率限制
    private val ncnnExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var ncnnInferInProgress = false
    @Volatile private var ncnnResultReady = false
    // 双缓冲：A 用于当前显示，B 用于后台写入，完成后交换
    private val ncnnBufA = java.nio.ByteBuffer.allocateDirect(1280 * 720 * 4)
    private val ncnnBufB = java.nio.ByteBuffer.allocateDirect(1280 * 720 * 4)
    private var ncnnDisplayBuf: java.nio.ByteBuffer = ncnnBufA
    private var ncnnWorkBuf: java.nio.ByteBuffer = ncnnBufB
    @Volatile private var ncnnResultWidth = 0
    @Volatile private var ncnnResultHeight = 0
    private var ncnnHasValidResult = false
    // 频率限制：每 N 帧推理一次（避免 GPU 过载导致黑屏）
    private val NCNN_INFER_INTERVAL = 2  // 每 2 帧推理 1 次（~15fps 超分）
    // 缩放缓冲复用
    private val scaledBuf = java.nio.ByteBuffer.allocateDirect(640 * 360 * 4)
    private val fullBuf = java.nio.ByteBuffer.allocateDirect(1920 * 1080 * 4)

    private val sharpenAmount: Float = try {
        val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val raw = sp.getFloat(SP_KEY_STRENGTH, SP_STRENGTH_DEFAULT)
        raw.coerceIn(STRENGTH_MIN, STRENGTH_MAX)
    } catch (_: Throwable) {
        SP_STRENGTH_DEFAULT
    }

    fun bindGlSurfaceView(view: GLSurfaceView) {
        glSurfaceViewRef = WeakReference(view)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            // 1. 创建 OES external texture
            textureId = GlUtil.createExternalTexture()
            // 2. 创建 SurfaceTexture
            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener { _ ->
                    frameAvailable.set(true)
                    glSurfaceViewRef?.get()?.requestRender()
                }
            }
            // 3. 通知外层
            onSurfaceTextureReady(surfaceTexture!!)

            // 4. 编译两个 shader program
            // Pass 1: blit (OES → RGBA)
            blitProgram = GlProgram(
                context,
                "shaders/video_vertex_es2.glsl",
                "shaders/blit_oes_to_rgba_es2.glsl"
            ).apply {
                setBufferAttribute("aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                setBufferAttribute("aTexCoords",
                    GlUtil.getTextureCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }

            // Pass 2: SGSR1 (RGBA → screen)
            sgsrProgram = GlProgram(
                context,
                "shaders/video_vertex_es2.glsl",
                "shaders/sgsr_fragment_es2.glsl"
            ).apply {
                setBufferAttribute("aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                setBufferAttribute("aTexCoords",
                    GlUtil.getTextureCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }

            Log.d("SharpenVideoRenderer", "onSurfaceCreated OK (dual-pass), textureId=$textureId")

            // ===== NCNN 初始化 =====
            if (useNcnn) {
                ncnnSr = me.lingci.lib.player.exo.ncnn.NcnnSuperResolution().also {
                    val ok = it.init(context)
                    Log.d("SharpenVideoRenderer", "NCNN init: $ok")
                }
                // 创建 NCNN 输出纹理（2x 分辨率）
                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                ncnnOutputTexId = texIds[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ncnnOutputTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                // blit shader 用于渲染 NCNN 输出（跟 Pass 1 的 blit 类似，但读 sampler2D 而不是 OES）
                blitProgramForNcnn = GlProgram(
                    context,
                    "shaders/video_vertex_es2.glsl",
                    "shaders/blit_rgba_es2.glsl"
                ).apply {
                    setBufferAttribute("aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                    setBufferAttribute("aTexCoords",
                        GlUtil.getTextureCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                }
            }
        } catch (e: Exception) {
            AndroidLog.e("SharpenVideoRenderer", "onSurfaceCreated failed: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 更新 OES 纹理（如果有新帧）
        if (frameAvailable.compareAndSet(true, false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
            } catch (e: Exception) {
                AndroidLog.e("SharpenVideoRenderer", "updateTexImage failed: ${e.message}")
            }
        }

        // 确保 FBO 尺寸匹配视频尺寸
        ensureFbo()

        // ===== Pass 1: OES → RGBA FBO（总是执行） =====
        blitOesToFbo()

        // ===== Pass 2: 根据模式选择渲染路径 =====
        if (useNcnn && ncnnSr?.initialized == true && fboId != 0) {
            // NCNN 异步超分模式
            drawFrameWithNcnn()
        } else {
            // 纯 SGSR1 模式
            drawFrameWithSgsr1()
        }
        frameCount++
    }

    /** Pass 1: OES → RGBA FBO */
    private fun blitOesToFbo() {
        if (fboId == 0 || blitProgram == null) return
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, fboWidth, fboHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            blitProgram!!.use()
            blitProgram!!.setSamplerTexIdUniform("uVideoTex", textureId, 0)
            blitProgram!!.setFloatsUniform("uTexTransform", transformMatrix)
            blitProgram!!.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        } catch (e: GlUtil.GlException) {
            AndroidLog.e("SharpenVideoRenderer", "Pass 1 blit failed: ${e.message}")
        }
    }

    /** Pass 2 (SGSR1): RGBA FBO → SGSR1 shader → 屏幕 */
    private fun drawFrameWithSgsr1() {
        if (fboId == 0 || fboTextureId == 0) return
        sgsrProgram?.let { p ->
            try {
                val view = glSurfaceViewRef?.get()
                if (view != null) {
                    GLES20.glViewport(0, 0, view.width, view.height)
                }
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                p.use()
                p.setSamplerTexIdUniform("uVideoTex", fboTextureId, 0)
                p.setFloatsUniform("uTexTransform", GlUtil.create4x4IdentityMatrix())
                if (videoWidth > 0 && videoHeight > 0) {
                    p.setFloatsUniformIfPresent("uViewportInfo",
                        floatArrayOf(1f / videoWidth, 1f / videoHeight, videoWidth.toFloat(), videoHeight.toFloat()))
                }
                p.setFloatsUniformIfPresent("uSharpenAmount", floatArrayOf(sharpenAmount))
                p.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                AndroidLog.e("SharpenVideoRenderer", "Pass 2 SGSR1 failed: ${e.message}")
            }
        }
    }

    /**
     * 异步 NCNN 路径（无闪烁版）：
     * - 有新推理结果 → 上传 + 渲染超分帧
     * - 没有新结果但已有旧结果 → 重复渲染上一帧超分结果（不闪）
     * - 从未有过结果（前几帧）→ 用 SGSR1 过渡（只在最开始）
     * - 后台没在推理 → 提交当前帧
     */
    private fun drawFrameWithNcnn(): Boolean {
        val sr = ncnnSr
        if (sr == null || !sr.initialized || fboWidth <= 0 || fboHeight <= 0) {
            drawFrameWithSgsr1()
            return false
        }

        // 1. 如果有新推理结果，上传到纹理
        if (ncnnResultReady) {
            ncnnResultReady = false
            ncnnHasValidResult = true
            // 交换缓冲：后台写完的 ncnnWorkBuf 变成 ncnnDisplayBuf 用于显示
            synchronized(this) {
                val tmp = ncnnDisplayBuf
                ncnnDisplayBuf = ncnnWorkBuf
                ncnnWorkBuf = tmp
            }
            try {
                ncnnDisplayBuf.rewind()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ncnnOutputTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    ncnnResultWidth, ncnnResultHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    ncnnDisplayBuf)
            } catch (e: Exception) {
                AndroidLog.e("SharpenVideoRenderer", "NCNN upload failed: ${e.message}")
            }
        }

        // 2. 按频率提交推理（每 NCNN_INFER_INTERVAL 帧推理 1 次）
        if (!ncnnInferInProgress && frameCount % NCNN_INFER_INTERVAL == 0) {
            ncnnInferInProgress = true
            try {
                // 从 FBO 读原始帧到复用 buffer
                val readSize = fboWidth * fboHeight * 4
                if (fullBuf.capacity() < readSize) {
                    // 视频分辨率超出预期，fallback
                    ncnnInferInProgress = false
                    drawFrameWithSgsr1()
                    return false
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                fullBuf.rewind()
                GLES20.glReadPixels(0, 0, fboWidth, fboHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, fullBuf)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                // 复制到 ByteArray 给后台线程（不持有 direct buffer 引用）
                fullBuf.rewind()
                val fullBytes = ByteArray(readSize)
                fullBuf.get(fullBytes)

                val targetW = 640
                val targetH = 360
                val fw = fboWidth
                val fh = fboHeight
                val workBuf = ncnnWorkBuf  // 捕获当前 work buffer 引用

                ncnnExecutor.execute {
                    try {
                        val scaledBytes = scaleDownRgba(fullBytes, fw, fh, targetW, targetH)
                        val ncnnOut = sr.infer(scaledBytes, targetW, targetH)
                        if (ncnnOut != null) {
                            val pixels = ncnnOut.first
                            val ow = ncnnOut.second
                            val oh = ncnnOut.third
                            val expectedSize = ow * oh * 4
                            if (pixels.size == expectedSize && expectedSize <= workBuf.capacity()) {
                                synchronized(this@SharpenVideoRenderer) {
                                    workBuf.clear()
                                    workBuf.put(pixels)
                                    workBuf.flip()
                                }
                                ncnnResultWidth = ow
                                ncnnResultHeight = oh
                                ncnnResultReady = true
                            }
                        }
                    } catch (e: Exception) {
                        AndroidLog.e("SharpenVideoRenderer", "NCNN async infer failed: ${e.message}")
                    } finally {
                        ncnnInferInProgress = false
                    }
                }
            } catch (e: Exception) {
                AndroidLog.e("SharpenVideoRenderer", "glReadPixels for NCNN failed: ${e.message}")
                ncnnInferInProgress = false
            }
        }

        // 3. 渲染到屏幕
        if (ncnnHasValidResult) {
            val view = glSurfaceViewRef?.get()
            if (view != null) GLES20.glViewport(0, 0, view.width, view.height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            blitProgramForNcnn?.let { p ->
                p.use()
                p.setSamplerTexIdUniform("uTexSampler", ncnnOutputTexId, 0)
                p.setFloatsUniform("uTexTransform", GlUtil.create4x4IdentityMatrix())
                p.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            return true
        } else {
            drawFrameWithSgsr1()
            return false
        }
    }

    private fun ensurePixelBuffer(): java.nio.ByteBuffer {
        val size = fboWidth * fboHeight * 4
        if (pixelBuffer == null || pixelBuffer!!.capacity() < size) {
            pixelBuffer = java.nio.ByteBuffer.allocateDirect(size)
        }
        pixelBuffer!!.rewind()
        return pixelBuffer!!
    }

    /**
     * 简单的最近邻缩放 RGBA byte[]。
     * 在 CPU 后台线程调用，不占 GL 线程。
     */
    private fun scaleDownRgba(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        val dst = ByteArray(dstW * dstH * 4)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        for (dy in 0 until dstH) {
            val sy = (dy * yRatio).toInt().coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) {
                val sx = (dx * xRatio).toInt().coerceIn(0, srcW - 1)
                val srcIdx = (sy * srcW + sx) * 4
                val dstIdx = (dy * dstW + dx) * 4
                dst[dstIdx]     = src[srcIdx]
                dst[dstIdx + 1] = src[srcIdx + 1]
                dst[dstIdx + 2] = src[srcIdx + 2]
                dst[dstIdx + 3] = src[srcIdx + 3]
            }
        }
        return dst
    }

    /** 创建或调整 RGBA FBO 尺寸以匹配视频分辨率。 */
    private fun ensureFbo() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        if (fboWidth == videoWidth && fboHeight == videoHeight && fboId != 0) return

        // 删旧 FBO
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
        }

        // 创建新 RGBA 纹理
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        fboTextureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            videoWidth, videoHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 创建 FBO
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        fboWidth = videoWidth
        fboHeight = videoHeight
        Log.d("SharpenVideoRenderer", "FBO created: ${fboWidth}x${fboHeight}")
    }

    fun onVideoSizeChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
        }
    }

    fun readFramebuffer(): android.graphics.Bitmap? {
        return null  // Phase 3 加
    }

    fun release() {
        try {
            ncnnExecutor.shutdownNow()
            surfaceTexture?.release()
            blitProgram?.delete()
            sgsrProgram?.delete()
            blitProgramForNcnn?.delete()
            if (fboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            }
            if (ncnnOutputTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(ncnnOutputTexId), 0)
            }
            ncnnSr?.release()
        } catch (_: Exception) {}
    }
}
