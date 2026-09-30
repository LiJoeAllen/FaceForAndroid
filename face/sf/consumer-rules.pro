# SeetaFace6 bindings: classes are called from native code (JNI) and call into
# native methods themselves. R8 must not rename or remove either side.
-keep class com.seeta.sdk.** { *; }
-keep class com.seeta.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
