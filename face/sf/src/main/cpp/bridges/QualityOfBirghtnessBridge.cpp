// QualityOfBirghtnessBridge.cpp —— JNI 符号桥：为上游 QualityAssessor3 的
// Java 类名拼写错误（QualityOfBirghtness，少个 i）补符号转发。
//
// Java 绑定把类声明为 QualityOfBirghtness（原文如此 —— 少了一个 "i"），
// 因此 JVM 查找的符号名是 Java_com_seeta_sdk_QualityOfBirghtness_*。而
// JNIQualityOfBrightness.cpp 以拼写正确的 Java_com_seeta_sdk_QualityOfBrightness_*
// 导出同名方法，永远满足不了这次查找：每个调用都会抛 UnsatisfiedLinkError。
// 这里的转发器把各方法以 Java 侧实际使用的错误拼写名再导出一次。
// 两种拼写共存于同一个 libQualityAssessor300_java.so；升级上游时需与
// JNIQualityOfBrightness.cpp 保持同步。
#include <jni.h>

extern "C" {
void Java_com_seeta_sdk_QualityOfBrightness_construct__(JNIEnv *, jobject);
void Java_com_seeta_sdk_QualityOfBrightness_construct__FFFF(JNIEnv *, jobject,
                                                            jfloat, jfloat,
                                                            jfloat, jfloat);
void Java_com_seeta_sdk_QualityOfBrightness_dispose(JNIEnv *, jobject);
jint Java_com_seeta_sdk_QualityOfBrightness_checkCore(JNIEnv *, jobject, jobject,
                                                      jobject, jobjectArray,
                                                      jfloatArray);
}

extern "C" JNIEXPORT void JNICALL
Java_com_seeta_sdk_QualityOfBirghtness_construct__(JNIEnv *env, jobject self) {
    Java_com_seeta_sdk_QualityOfBrightness_construct__(env, self);
}

extern "C" JNIEXPORT void JNICALL
Java_com_seeta_sdk_QualityOfBirghtness_construct__FFFF(JNIEnv *env, jobject self,
                                                       jfloat v0, jfloat v1,
                                                       jfloat v2, jfloat v3) {
    Java_com_seeta_sdk_QualityOfBrightness_construct__FFFF(env, self, v0, v1,
                                                           v2, v3);
}

extern "C" JNIEXPORT void JNICALL
Java_com_seeta_sdk_QualityOfBirghtness_dispose(JNIEnv *env, jobject self) {
    Java_com_seeta_sdk_QualityOfBrightness_dispose(env, self);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_seeta_sdk_QualityOfBirghtness_checkCore(JNIEnv *env, jobject self,
                                                 jobject image, jobject face,
                                                 jobjectArray landmarks,
                                                 jfloatArray score) {
    return Java_com_seeta_sdk_QualityOfBrightness_checkCore(
        env, self, image, face, landmarks, score);
}
