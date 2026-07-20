package me.lingci.lib.player.exo.ncnn

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/**
 * NCNN 神经网络超分封装。
 *
 * 使用 Real-ESRGANv3-anime x2 模型，通过 NCNN Vulkan GPU 推理。
 * 单帧推理 ~51ms（Adreno 840）。
 *
 * 使用方式：
 *   val sr = NcnnSuperResolution()
 *   sr.init(context)  // 加载模型
 *   val output = sr.infer(inputRgba, width, height)  // 推理
 *   sr.release()  // 释放
 */
class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSuperResolution"
        private const val PARAM_PATH = "models/realesr-animevideov3-x2.param"
        private const val BIN_PATH = "models/realesr-animevideov3-x2.bin"
    }

    var initialized = false
        private set

    fun init(context: Context): Boolean {
        if (initialized) return true
        try {
            initialized = nativeInit(context.assets, PARAM_PATH, BIN_PATH)
            Log.i(TAG, "init result: $initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "init failed: ${e.message}")
            initialized = false
        }
        return initialized
    }

    /**
     * 对一帧 RGBA 数据做 2x 超分。
     *
     * @param input RGBA byte array (width * height * 4)
     * @param width 输入宽度
     * @param height 输入高度
     * @return 超分后的 RGBA byte array (width*2 * height*2 * 4)，或 null 如果失败
     */
    fun infer(input: ByteArray, width: Int, height: Int): ByteArray? {
        if (!initialized) return null
        val outWidth = width * 2
        val outHeight = height * 2
        val output = ByteArray(outWidth * outHeight * 4)
        val ok = nativeInfer(input, width, height, output, outWidth, outHeight)
        return if (ok) output else null
    }

    fun release() {
        if (initialized) {
            nativeRelease()
            initialized = false
        }
    }

    private external fun nativeInit(
        assetManager: AssetManager,
        paramPath: String,
        binPath: String
    ): Boolean

    private external fun nativeInfer(
        input: ByteArray, width: Int, height: Int,
        output: ByteArray, outWidth: Int, outHeight: Int
    ): Boolean

    private external fun nativeRelease()
}
