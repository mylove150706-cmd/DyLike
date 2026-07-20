#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include <string>
#include <cstring>

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
 * 推理。JNI 内部分配正确大小的 buffer。
 * 返回 ByteArray: [outW(int32)][outH(int32)][RGBA pixels...]
 */
JNIEXPORT jbyteArray JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInferAlloc(
    JNIEnv* env, jobject,
    jbyteArray inputData, jint width, jint height)
{
    if (!sr_loaded) return nullptr;

    // 1. RGBA → ncnn::Mat (RGB)
    jbyte* in = env->GetByteArrayElements(inputData, nullptr);
    ncnn::Mat in_mat = ncnn::Mat::from_pixels(
        (const unsigned char*)in, ncnn::Mat::PIXEL_RGBA2RGB, width, height);
    env->ReleaseByteArrayElements(inputData, in, JNI_ABORT);

    // 2. NCNN 推理
    ncnn::Extractor ex = sr_net.create_extractor();
    ex.set_light_mode(false);

    int ret_in = ex.input("data", in_mat);
    if (ret_in != 0) {
        LOGE("input() failed: %d", ret_in);
        return nullptr;
    }

    ncnn::Mat out_mat;
    int ret_ex = ex.extract("output", out_mat);
    if (ret_ex != 0) {
        LOGE("extract() failed: %d", ret_ex);
        return nullptr;
    }

    LOGI("infer: %dx%d → %dx%d (c=%d)", width, height, out_mat.w, out_mat.h, out_mat.c);

    // 3. 输出 RGB → RGBA
    int outW = out_mat.w;
    int outH = out_mat.h;
    int pixelSize = outW * outH * 4;

    // 返回格式: [outW 4 bytes][outH 4 bytes][RGBA data]
    int totalSize = 8 + pixelSize;
    jbyteArray result = env->NewByteArray(totalSize);

    // 写入宽高
    unsigned char header[8];
    memcpy(header, &outW, 4);
    memcpy(header + 4, &outH, 4);
    env->SetByteArrayRegion(result, 0, 8, (jbyte*)header);

    // 转换并写入像素
    unsigned char* rgbaBuf = new unsigned char[pixelSize];
    out_mat.to_pixels(rgbaBuf, ncnn::Mat::PIXEL_RGB2RGBA);
    env->SetByteArrayRegion(result, 8, pixelSize, (jbyte*)rgbaBuf);
    delete[] rgbaBuf;

    LOGI("infer OK: returning %dx%d RGBA", outW, outH);
    return result;
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
