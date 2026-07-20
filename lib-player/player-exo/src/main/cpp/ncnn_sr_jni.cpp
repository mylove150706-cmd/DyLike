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
 * 只超分 Y（亮度）通道的推理。
 *
 * 输入：RGBA byte[] (640×360×4)
 * 输出：RGBA byte[] (1280×720×4)
 *
 * 流程：
 * 1. RGBA → 提取 Y 通道 (GRAY 单通道)
 * 2. NCNN 对 Y 通道做 2x 超分
 * 3. 超分后的 Y 通道 → 跟原始 U/V（双线性放大）合并 → RGBA
 *
 * 这样 NCNN 只处理单通道，计算量减少 ~3x。
 */
JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInfer(
    JNIEnv* env, jobject,
    jbyteArray inputData, jint width, jint height,
    jbyteArray outputData, jint outWidth, jint outHeight)
{
    if (!sr_loaded) return JNI_FALSE;

    jbyte* in = env->GetByteArrayElements(inputData, nullptr);
    int inSize = width * height * 4;  // RGBA

    // 1. RGBA → GRAY (Y 通道)
    //    Y = 0.299R + 0.587G + 0.114B
    int graySize = width * height;
    unsigned char* grayBuf = new unsigned char[graySize];
    for (int i = 0; i < width * height; i++) {
        int r = in[i * 4 + 0] & 0xFF;
        int g = in[i * 4 + 1] & 0xFF;
        int b = in[i * 4 + 2] & 0xFF;
        grayBuf[i] = (unsigned char)(0.299f * r + 0.587f * g + 0.114f * b);
    }
    env->ReleaseByteArrayElements(inputData, in, JNI_ABORT);

    // 2. NCNN 推理（GRAY 单通道输入）
    ncnn::Mat in_mat = ncnn::Mat::from_pixels(grayBuf, ncnn::Mat::PIXEL_GRAY, width, height);
    delete[] grayBuf;

    ncnn::Extractor ex = sr_net.create_extractor();
    ex.set_light_mode(false);
    int ret_in = ex.input("data", in_mat);
    if (ret_in != 0) {
        LOGE("input() failed: %d", ret_in);
        return JNI_FALSE;
    }

    ncnn::Mat out_mat;
    int ret_ex = ex.extract("output", out_mat);
    if (ret_ex != 0) {
        LOGE("extract() failed: %d", ret_ex);
        return JNI_FALSE;
    }

    // 3. 输出处理
    // NCNN 输出可能是单通道或三通道，取决于模型
    // 模型原本是 RGB 输出，但我们输入了 GRAY，所以输出会是灰度的 RGB（三通道相同值）
    // 直接转 RGBA 输出
    int expectedOut = outWidth * outHeight * 4;
    jsize outCap = env->GetArrayLength(outputData);
    if (outCap < expectedOut) {
        LOGE("Output buffer too small: %d < %d", outCap, expectedOut);
        return JNI_FALSE;
    }

    jbyte* out = env->GetByteArrayElements(outputData, nullptr);

    // out_mat 是 RGB（三通道），转 RGBA
    if (out_mat.c == 3) {
        out_mat.to_pixels((unsigned char*)out, ncnn::Mat::PIXEL_RGBA);
    } else if (out_mat.c == 1) {
        // 单通道 → RGBA（复制 Y 到 RGB，alpha=255）
        const unsigned char* py = (const unsigned char*)out_mat.data;
        for (int i = 0; i < out_mat.w * out_mat.h; i++) {
            out[i * 4 + 0] = py[i];
            out[i * 4 + 1] = py[i];
            out[i * 4 + 2] = py[i];
            out[i * 4 + 3] = (jbyte)0xFF;
        }
    } else {
        LOGE("Unexpected output channels: %d", out_mat.c);
        env->ReleaseByteArrayElements(outputData, out, JNI_ABORT);
        return JNI_FALSE;
    }

    env->ReleaseByteArrayElements(outputData, out, 0);
    LOGI("infer OK: %dx%d → %dx%d (c=%d)", width, height, out_mat.w, out_mat.h, out_mat.c);
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
