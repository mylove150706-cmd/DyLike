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

    // Vulkan GPU - 全部优化开启
    sr_net.opt.num_threads = 4;
    sr_net.opt.use_fp16_packed     = true;
    sr_net.opt.use_fp16_storage    = true;
    sr_net.opt.use_fp16_arithmetic = true;
    sr_net.opt.use_winograd_convolution = true;   // 3x3 卷积加速（SR 模型全是 3x3）
    sr_net.opt.use_sgemm_convolution = true;      // GEMM 路径优化
    sr_net.opt.use_int8_storage     = true;       // INT8 存储
    sr_net.opt.use_int8_arithmetic  = true;       // INT8 计算（Vulkan GPU 路径）

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

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInfer(
    JNIEnv* env, jobject,
    jbyteArray inputData, jint width, jint height,
    jbyteArray outputData, jint outWidth, jint outHeight)
{
    if (!sr_loaded) return JNI_FALSE;

    // 1. 输入 RGBA → ncnn::Mat
    jbyte* in = env->GetByteArrayElements(inputData, nullptr);
    ncnn::Mat in_mat = ncnn::Mat::from_pixels(
        (const unsigned char*)in, ncnn::Mat::PIXEL_RGBA, width, height);
    env->ReleaseByteArrayElements(inputData, in, JNI_ABORT);

    LOGI("infer: input %dx%d, in_mat w=%d h=%d c=%d", width, height, in_mat.w, in_mat.h, in_mat.c);

    // 2. NCNN 推理
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

    LOGI("infer: out_mat w=%d h=%d c=%d (expected %dx%d)", out_mat.w, out_mat.h, out_mat.c, outWidth, outHeight);

    // 检查输出尺寸是否匹配预期
    if (out_mat.w != outWidth || out_mat.h != outHeight) {
        LOGE("Output size mismatch: got %dx%d, expected %dx%d", out_mat.w, out_mat.h, outWidth, outHeight);
        return JNI_FALSE;
    }

    // 3. ncnn::Mat → RGBA byte[]
    jbyte* out = env->GetByteArrayElements(outputData, nullptr);
    out_mat.to_pixels((unsigned char*)out, ncnn::Mat::PIXEL_RGBA);
    env->ReleaseByteArrayElements(outputData, out, 0);
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
