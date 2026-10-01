// Local dev stub — CI par asli llama-jni.cpp compile hota hai (llama.cpp maujood hone par)
#include <jni.h>
extern "C" JNIEXPORT jboolean JNICALL Java_com_alnoor_autobot_LlamaBridge_nativeLoad(JNIEnv *, jobject, jstring, jint, jint) { return JNI_FALSE; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_alnoor_autobot_LlamaBridge_nativeIsLoaded(JNIEnv *, jobject) { return JNI_FALSE; }
extern "C" JNIEXPORT void JNICALL Java_com_alnoor_autobot_LlamaBridge_nativeFree(JNIEnv *, jobject) {}
extern "C" JNIEXPORT jstring JNICALL Java_com_alnoor_autobot_LlamaBridge_nativeGenerate(JNIEnv *e, jobject, jstring, jint, jfloat, jfloat, jint) { return e->NewStringUTF(""); }
extern "C" JNIEXPORT jstring JNICALL Java_com_alnoor_autobot_LlamaBridge_nativeLastError(JNIEnv *e, jobject) { return e->NewStringUTF("stub build"); }
