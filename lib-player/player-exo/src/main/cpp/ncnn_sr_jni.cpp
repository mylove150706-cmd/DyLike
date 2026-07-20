#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include <string>

#define TAG "NcnnSR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ncnn::Net sr_net;
static bool sr_loaded = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInit(
    JNIEnv* env, jobject, jobject assetManager,
    jstring paramPath, jstring binPath)
{
    if (sr_loaded) return JNI_TRUE;

    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin   = env->GetStringUTFChars(binPath,   nullptr);

    sr_net.opt.num_threads = 4;
    sr_net.opt.use_fp16_packed     = true;
    sr_net.opt.use_fp16_storage    = true;
    sr_net.opt.use_fp16_arithmetic = true;
    sr_net.opt.use_winograd_convolution = true;
    sr_net.opt.use_sgemm_convolution = true;
    sr_net.opt.use_int8_storage     = true;
    sr_net.opt.use_int8_arithmetic  = true;

#if NCNN_VULKAN
    if (ncnn::get_gpu_count() > 0) {
        sr_net.set_vulkan_device(0);
        sr_net.opt.use_vulkan_compute = true;
        LOGI("Vulkan GPU available, device 0");
    } else {
        LOGI("No Vulkan GPU, CPU fallback");
    }
#endif

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("Failed to get AssetManager");
        return JNI_FALSE;
    }

    int rp = sr_net.load_param(mgr, param);
    int rb = sr_net.load_model(mgr, bin);

    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath,   bin);

    if (rp != 0 || rb != 0) {
        LOGE("Load model failed: param=%d bin=%d", rp, rb);
        return JNI_FALSE;
    }

    sr_loaded = true;
    LOGI("NCNN model loaded OK");
    return JNI_TRUE;
}

/**
 * Y 通道超分 + UV 双线性放大合并。
 *
 * 输入：RGBA byte[] (srcW × srcH × 4)
 * 输出：RGBA byte[] ((srcW×2) × (srcH×2) × 4)
 *
 * 流程：
 * 1. RGBA → YUV (分离 Y / U / V)
 * 2. NCNN 对 Y 做 2x 超分（计算量大头，但只有 1 通道）
 * 3. U/V 用双线性放大到 2x（CPU 简单插值，很快）
 * 4. 超分的 Y + 放大的 U/V → YUV → RGBA 输出
 *
 * 色彩完整保留，只有亮度走神经网络。
 */
JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInfer(
    JNIEnv* env, jobject,
    jbyteArray inputData, jint width, jint height,
    jbyteArray outputData, jint outWidth, jint outHeight)
{
    if (!sr_loaded) return JNI_FALSE;

    jbyte* in = env->GetByteArrayElements(inputData, nullptr);
    int pixelCount = width * height;

    // 1. RGBA → YUV (BT.601)
    //    Y  =  0.299R + 0.587G + 0.114B
    //    U  = -0.169R - 0.331G + 0.500B + 128
    //    V  =  0.500R - 0.419G - 0.081B + 128
    unsigned char* yBuf = new unsigned char[pixelCount];
    unsigned char* uBuf = new unsigned char[pixelCount];
    unsigned char* vBuf = new unsigned char[pixelCount];

    for (int i = 0; i < pixelCount; i++) {
        int r = in[i * 4 + 0] & 0xFF;
        int g = in[i * 4 + 1] & 0xFF;
        int b = in[i * 4 + 2] & 0xFF;
        yBuf[i] = (unsigned char)( 0.299f * r + 0.587f * g + 0.114f * b);
        uBuf[i] = (unsigned char)(-0.169f * r - 0.331f * g + 0.500f * b + 128);
        vBuf[i] = (unsigned char)( 0.500f * r - 0.419f * g - 0.081f * b + 128);
    }
    env->ReleaseByteArrayElements(inputData, in, JNI_ABORT);

    // 2. NCNN 超分 Y 通道（GRAY 输入）
    ncnn::Mat in_mat = ncnn::Mat::from_pixels(yBuf, ncnn::Mat::PIXEL_GRAY, width, height);
    delete[] yBuf;

    ncnn::Extractor ex = sr_net.create_extractor();
    ex.set_light_mode(false);
    int ret_in = ex.input("data", in_mat);
    if (ret_in != 0) {
        LOGE("input() failed: %d", ret_in);
        delete[] uBuf; delete[] vBuf;
        return JNI_FALSE;
    }

    ncnn::Mat out_mat;
    int ret_ex = ex.extract("output", out_mat);
    if (ret_ex != 0) {
        LOGE("extract() failed: %d", ret_ex);
        delete[] uBuf; delete[] vBuf;
        return JNI_FALSE;
    }

    // 取超分后的 Y（out_mat 可能是 3 通道灰度或 1 通道，取 R 通道即可）
    int outW = out_mat.w;
    int outH = out_mat.h;
    const unsigned char* srY = (const unsigned char*)out_mat.data;

    // 3. 双线性放大 U/V 到 outW × outH
    int uvSize = outW * outH;
    unsigned char* upU = new unsigned char[uvSize];
    unsigned char* upV = new unsigned char[uvSize];
    {
        float xRatio = (float)(width - 1) / (outW - 1);
        float yRatio = (float)(height - 1) / (outH - 1);
        for (int dy = 0; dy < outH; dy++) {
            float sy = dy * yRatio;
            int sy0 = (int)sy;
            int sy1 = (sy0 + 1 < height) ? sy0 + 1 : sy0;
            float fy = sy - sy0;
            for (int dx = 0; dx < outW; dx++) {
                float sx = dx * xRatio;
                int sx0 = (int)sx;
                int sx1 = (sx0 + 1 < width) ? sx0 + 1 : sx0;
                float fx = sx - sx0;

                // 双线性 U
                float u00 = uBuf[sy0 * width + sx0];
                float u01 = uBuf[sy0 * width + sx1];
                float u10 = uBuf[sy1 * width + sx0];
                float u11 = uBuf[sy1 * width + sx1];
                upU[dy * outW + dx] = (unsigned char)(u00 * (1-fx)*(1-fy) + u01 * fx*(1-fy) + u10 * (1-fx)*fy + u11 * fx*fy);

                // 双线性 V
                float v00 = vBuf[sy0 * width + sx0];
                float v01 = vBuf[sy0 * width + sx1];
                float v10 = vBuf[sy1 * width + sx0];
                float v11 = vBuf[sy1 * width + sx1];
                upV[dy * outW + dx] = (unsigned char)(v00 * (1-fx)*(1-fy) + v01 * fx*(1-fy) + v10 * (1-fx)*fy + v11 * fx*fy);
            }
        }
    }
    delete[] uBuf; delete[] vBuf;

    // 4. YUV → RGBA（超分 Y + 放大 UV）
    jsize outCap = env->GetArrayLength(outputData);
    int expectedOut = outW * outH * 4;
    if (outCap < expectedOut) {
        LOGE("Output buffer too small: %d < %d", outCap, expectedOut);
        delete[] upU; delete[] upV;
        return JNI_FALSE;
    }

    jbyte* out = env->GetByteArrayElements(outputData, nullptr);
    for (int i = 0; i < outW * outH; i++) {
        // out_mat 可能是 c=1 或 c=3，取第一个通道作为 Y
        int yVal = srY[i * out_mat.c] & 0xFF;
        int uVal = upU[i] - 128;
        int vVal = upV[i] - 128;

        // BT.601 YUV → RGB
        int r = yVal + 1.402f * vVal;
        int g = yVal - 0.344f * uVal - 0.714f * vVal;
        int b = yVal + 1.772f * uVal;

        out[i * 4 + 0] = (jbyte)(r < 0 ? 0 : (r > 255 ? 255 : r));
        out[i * 4 + 1] = (jbyte)(g < 0 ? 0 : (g > 255 ? 255 : g));
        out[i * 4 + 2] = (jbyte)(b < 0 ? 0 : (b > 255 ? 255 : b));
        out[i * 4 + 3] = (jbyte)0xFF;
    }
    delete[] upU; delete[] upV;

    env->ReleaseByteArrayElements(outputData, out, 0);
    LOGI("infer OK: %dx%d → %dx%d (Y-super-res + UV-bilinear)", width, height, outW, outH);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeRelease(
    JNIEnv*, jobject)
{
    sr_net.clear();
    sr_loaded = false;
    LOGI("NCNN released");
}

} // extern "C"
