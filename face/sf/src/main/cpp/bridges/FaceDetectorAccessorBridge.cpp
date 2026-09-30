// FaceDetectorAccessorBridge.cpp —— FaceDetector 访问器 JNI 补齐：Java 绑定
// 声明了这些 native 方法，但上游没有任何 JNI 源码实现它们；不补这个桥，
// 任何一个调用都会抛 UnsatisfiedLinkError。这里全部基于属性 set/get API
// 实现 —— 新版 SeetaFace6 原生构建里的专用方法行为正是如此。
//
// 属性↔方法的映射对齐上游 FaceDetector.Property 的索引；升级上游时需与
// FaceBoxes/java/com/seeta/sdk/FaceDetector.java 保持同步。
#include <jni.h>

#include <android/log.h>

#include "seeta/FaceDetector.h"

namespace {

constexpr const char *kLogTag = "SeetaFaceBridge";

using NativeDetector = seeta::FaceDetector;

jlong impl_of(JNIEnv *env, jobject self) {
    jclass self_class = env->GetObjectClass(self);
    jfieldID impl_field = env->GetFieldID(self_class, "impl", "J");
    return env->GetLongField(self, impl_field);
}

NativeDetector *detector_of(JNIEnv *env, jobject self) {
    return reinterpret_cast<NativeDetector *>(impl_of(env, self));
}

void set_property(JNIEnv *env, jobject self, NativeDetector::Property property,
                  jdouble value) {
    // impl 为 0 说明 Java 对象已被 dispose，属调用方生命周期错误；
    // 静默忽略会掩盖问题，这里降级为告警日志。
    if (NativeDetector *native = detector_of(env, self)) {
        native->set(property, value);
    } else {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "Set 操作被忽略：FaceDetector 已释放（use-after-dispose）");
    }
}

jdouble get_property(JNIEnv *env, jobject self,
                     NativeDetector::Property property) {
    if (NativeDetector *native = detector_of(env, self)) {
        return native->get(property);
    }
    __android_log_print(ANDROID_LOG_WARN, kLogTag,
                        "Get 操作返回 -1：FaceDetector 已释放（use-after-dispose）");
    return -1;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_seeta_sdk_FaceDetector_SetMinFaceSize(JNIEnv *env, jobject self,
                                               jint size) {
    set_property(env, self, NativeDetector::PROPERTY_MIN_FACE_SIZE, size);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_seeta_sdk_FaceDetector_GetMinFaceSize(JNIEnv *env, jobject self) {
    return static_cast<jint>(
        get_property(env, self, NativeDetector::PROPERTY_MIN_FACE_SIZE));
}

extern "C" JNIEXPORT void JNICALL
Java_com_seeta_sdk_FaceDetector_SetThresh(JNIEnv *env, jobject self,
                                          jfloat thresh) {
    set_property(env, self, NativeDetector::PROPERTY_THRESHOLD, thresh);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_seeta_sdk_FaceDetector_GetThresh(JNIEnv *env, jobject self) {
    return static_cast<jfloat>(
        get_property(env, self, NativeDetector::PROPERTY_THRESHOLD));
}

extern "C" JNIEXPORT void JNICALL
Java_com_seeta_sdk_FaceDetector_SetMaxImageWidth(JNIEnv *env, jobject self,
                                                 jint width) {
    set_property(env, self, NativeDetector::PROPERTY_MAX_IMAGE_WIDTH, width);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_seeta_sdk_FaceDetector_GetMaxImageWidth(JNIEnv *env, jobject self) {
    return static_cast<jint>(
        get_property(env, self, NativeDetector::PROPERTY_MAX_IMAGE_WIDTH));
}

extern "C" JNIEXPORT void JNICALL
Java_com_seeta_sdk_FaceDetector_SetMaxImageHeight(JNIEnv *env, jobject self,
                                                  jint height) {
    set_property(env, self, NativeDetector::PROPERTY_MAX_IMAGE_HEIGHT, height);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_seeta_sdk_FaceDetector_GetMaxImageHeight(JNIEnv *env, jobject self) {
    return static_cast<jint>(
        get_property(env, self, NativeDetector::PROPERTY_MAX_IMAGE_HEIGHT));
}

extern "C" JNIEXPORT void JNICALL
Java_com_seeta_sdk_FaceDetector_SetComputingThreads(JNIEnv *env, jobject self,
                                                    jint num) {
    set_property(env, self, NativeDetector::PROPERTY_NUMBER_THREADS, num);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_seeta_sdk_FaceDetector_GetComputingThreads(JNIEnv *env, jobject self) {
    return static_cast<jint>(
        get_property(env, self, NativeDetector::PROPERTY_NUMBER_THREADS));
}
