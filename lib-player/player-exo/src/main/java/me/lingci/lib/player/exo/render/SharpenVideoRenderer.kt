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
    private var ncnnOutputTexId = 0  // NCNN 超分结果的 2x 分辨率纹理
    private var pixelBuffer: java.nio.ByteBuffer? = null  // glReadPixels 的缓冲
    private var blitProgramForNcnn: GlProgram? = null  // 用于渲染 NCNN 输出纹理的 blit shader

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
            // NCNN 全帧超分模式：每帧都走神经网络推理
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
     * Pass 2 (NCNN): RGBA FBO → glReadPixels → NCNN 推理 → glTexImage2D → 屏幕
     *
     * 比 SGSR1 慢（~71ms/帧），但画质明显更好（神经网络超分）。
     */
    private fun drawFrameWithNcnn() {
        val sr = ncnnSr ?: return
        if (!sr.initialized || fboWidth <= 0 || fboHeight <= 0) return

        try {
            // 1. 从 FBO 读像素到 CPU
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glReadPixels(0, 0, fboWidth, fboHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ensurePixelBuffer())
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            // 2. ByteBuffer → ByteArray → NCNN 推理
            pixelBuffer!!.rewind()
            val inputBytes = ByteArray(fboWidth * fboHeight * 4)
            pixelBuffer!!.get(inputBytes)
            val output = sr.infer(inputBytes, fboWidth, fboHeight) ?: return

            // 3. 上传超分结果到纹理
            val outWidth = fboWidth * 2
            val outHeight = fboHeight * 2
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ncnnOutputTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                outWidth, outHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                java.nio.ByteBuffer.wrap(output))

            // 4. 渲染超分纹理到屏幕
            val view = glSurfaceViewRef?.get()
            if (view != null) {
                GLES20.glViewport(0, 0, view.width, view.height)
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            blitProgramForNcnn?.let { p ->
                p.use()
                p.setSamplerTexIdUniform("uTexSampler", ncnnOutputTexId, 0)
                p.setFloatsUniform("uTexTransform", GlUtil.create4x4IdentityMatrix())
                p.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        } catch (e: Exception) {
            AndroidLog.e("SharpenVideoRenderer", "NCNN path failed: ${e.message}")
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
