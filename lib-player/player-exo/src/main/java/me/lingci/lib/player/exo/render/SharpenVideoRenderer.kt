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
 * 锐化 GL Renderer。移植自 Google ExoPlayer demo VideoProcessingGLSurfaceView.VideoRenderer +
 * BitmapOverlayVideoProcessor。把 BitmapOverlay 部分换成我们的 tint/sharpen shader。
 *
 * 核心流程：
 * 1. onSurfaceCreated (GL 线程): 创建 OES external texture + SurfaceTexture，
 *    通过回调通知外层把 Surface(SurfaceTexture) 给 ExoPlayer
 * 2. ExoPlayer 解码后写入 SurfaceTexture，onFrameAvailable 在 binder 线程触发，
 *    set AtomicBoolean + requestRender
 * 3. onDrawFrame (GL 线程): updateTexImage + getTransformMatrix，跑 shader，画到屏幕
 *
 * @param onSurfaceTextureReady 在 onSurfaceCreated 完成后调，参数是就绪的 SurfaceTexture。
 *   回调内部负责切回主线程调用 player.setVideoSurface(Surface(st))。
 */
class SharpenVideoRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val SP_KEY_STRENGTH = "labSuperResolutionStrength"
        private const val SP_STRENGTH_DEFAULT = 1.0f
        private const val STRENGTH_MIN = 0.0f
        private const val STRENGTH_MAX = 3.0f
    }

    private val frameAvailable = AtomicBoolean(false)
    private val transformMatrix = FloatArray(16)
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var program: GlProgram? = null
    private var glSurfaceViewRef: WeakReference<GLSurfaceView>? = null

    /** 锐化强度，构造时从 SP 读取一次（运行时切换会重建 renderer 才能改）。 */
    private val sharpenAmount: Float = try {
        val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val raw = sp.getFloat(SP_KEY_STRENGTH, SP_STRENGTH_DEFAULT)
        raw.coerceIn(STRENGTH_MIN, STRENGTH_MAX)
    } catch (_: Throwable) {
        SP_STRENGTH_DEFAULT
    }
    private var videoWidth = 0
    private var videoHeight = 0

    /** 绑定外层 GLSurfaceView，用于在 onFrameAvailable 触发 requestRender。 */
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
                    // binder 线程：只设 flag + requestRender，不能 updateTexImage
                    frameAvailable.set(true)
                    glSurfaceViewRef?.get()?.requestRender()
                }
            }
            // 3. 通知外层（外层负责切回主线程把 Surface 给 ExoPlayer）
            onSurfaceTextureReady(surfaceTexture!!)
            // 4. 编译 shader（Phase 2 用 unsharp mask 锐化）
            program = GlProgram(
                context,
                /* vertexShaderFilePath */ "shaders/video_vertex_es2.glsl",
                /* fragmentShaderFilePath */ "shaders/unsharp_fragment_es2.glsl"
            ).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
                setBufferAttribute(
                    "aTexCoords",
                    GlUtil.getTextureCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
            }
            Log.d("SharpenVideoRenderer", "onSurfaceCreated OK, textureId=$textureId")
        } catch (e: Exception) {
            AndroidLog.e("SharpenVideoRenderer", "onSurfaceCreated failed: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 必须先 updateTexImage 才能采样到最新帧
        if (frameAvailable.compareAndSet(true, false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
            } catch (e: Exception) {
                AndroidLog.e("SharpenVideoRenderer", "updateTexImage failed: ${e.message}")
            }
        }
        program?.let { p ->
            try {
                p.use()
                p.setSamplerTexIdUniform("uVideoTex", textureId, /* texUnitIndex */ 0)
                p.setFloatsUniform("uTexTransform", transformMatrix)
                // Phase 2: 设置 unsharp mask 所需的 uTexelSize + uSharpenAmount
                if (videoWidth > 0 && videoHeight > 0) {
                    p.setFloatsUniform("uTexelSize", floatArrayOf(1f / videoWidth, 1f / videoHeight))
                }
                p.setFloatUniform("uSharpenAmount", sharpenAmount)
                p.bindAttributesAndUniforms()
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first */ 0, /* count */ 4)
            } catch (e: GlUtil.GlException) {
                AndroidLog.e("SharpenVideoRenderer", "draw failed: ${e.message}")
            }
        }
    }

    /** 视频尺寸变化时调用，用于算 uTexelSize。 */
    fun onVideoSizeChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
        }
    }

    /** 截图：glReadPixels 读 framebuffer（在 GL 线程调用）。 */
    fun readFramebuffer(): android.graphics.Bitmap? {
        // Phase 1 不实现，Phase 3 加
        return null
    }

    fun release() {
        try {
            surfaceTexture?.release()
            program?.delete()
        } catch (_: Exception) {}
    }
}
