#include <jni.h>

// 占位实现，Task 3 会完整实现
extern "C" {

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInit(
    JNIEnv* env, jobject, jobject, jstring, jstring)
{
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInfer(
    JNIEnv* env, jobject,
    jbyteArray, jint, jint,
    jbyteArray, jint, jint)
{
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeRelease(
    JNIEnv* env, jobject)
{
}

} // extern "C"
