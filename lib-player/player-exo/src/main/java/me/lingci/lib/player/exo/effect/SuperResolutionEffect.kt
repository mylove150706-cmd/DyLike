package me.lingci.lib.player.exo.effect

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * 画质增强 GlEffect。当前 Phase 1 加载染色 shader 验证管线。
 *
 * 后续 Phase 会替换为 unsharp_mask / adaptive_sharpen。
 *
 * 详见 spec：docs/superpowers/specs/2026-07-20-exo-adaptive-sharpen-design.md
 */
@OptIn(UnstableApi::class)
class SuperResolutionEffect(
    @Suppress("unused") private val strength: Float = 1.0f
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useDebug: Boolean): GlShaderProgram {
        return SuperResolutionProgram(context)
    }
}

/**
 * Media3 BaseGlShaderProgram 子类。模板参考 androidx.media3.effect.ColorLutShaderProgram。
 *
 * 注意构造函数签名：BaseGlShaderProgram(useHighPrecisionColorComponents, texturePoolCapacity)
 * - useHighPrecisionColorComponents: HDR 用 true，SDR 用 false
 * - texturePoolCapacity: 输出纹理池大小，单输入单输出用 1
 */
@OptIn(UnstableApi::class)
private class SuperResolutionProgram(
    context: Context
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents */ false, /* texturePoolCapacity */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(
            context,
            /* vertexShaderFilePath */ "shaders/vertex_shader_transformation_es2.glsl",
            /* fragmentShaderFilePath */ "shaders/fragment_shader_tint_es2.glsl"
        )
    } catch (e: Exception) {
        throw VideoFrameProcessingException(e)
    }.apply {
        // 标准 Media3 模板：填满整个 NDC 空间 [-1, 1]
        setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        setFloatsUniform("uTransformationMatrix", identity)
        setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // 不改变尺寸
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex */ 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first */ 0, /* count */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (_: GlUtil.GlException) {
            // ignore on release
        }
    }
}
