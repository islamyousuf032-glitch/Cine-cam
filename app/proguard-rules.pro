# CineCam ProGuard Rules
-keep class com.cinecam.engine.** { *; }
-keep class com.cinecam.camera.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
# Keep JNI bridge
-keep class com.cinecam.engine.NativeRawEngine { *; }
-dontwarn org.xmlpull.v1.**
