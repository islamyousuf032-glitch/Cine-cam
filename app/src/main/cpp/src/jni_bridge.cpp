#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include "cine_raw_engine.h"
#include "cine_types.h"

#define LOG_TAG "CineJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace cinecam;

// ---------------------------------------------------------------------------
// JNI Helpers
// ---------------------------------------------------------------------------

static std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return s;
}

// ---------------------------------------------------------------------------
// Engine Lifecycle
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeInitialize(
        JNIEnv* env, jobject /*thiz*/,
        jint width, jint height, jint bitDepth, jint poolSize) {
    auto* engine = GetGlobalEngine();
    bool ok = engine->initialize(width, height, bitDepth, poolSize);
    LOGI("nativeInitialize(%dx%d @%d-bit, pool=%d) -> %d", width, height, bitDepth, poolSize, ok);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeRelease(
        JNIEnv* env, jobject /*thiz*/) {
    ReleaseGlobalEngine();
    LOGI("nativeRelease()");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeStartRecording(
        JNIEnv* env, jobject /*thiz*/,
        jint width, jint height, jint fps, jint bitDepth,
        jstring jOutputPath, jstring jContainer,
        jboolean enableZstd, jint zstdLevel) {

    auto* engine = GetGlobalEngine();
    if (!engine->isInitialized()) {
        LOGE("nativeStartRecording: engine not initialized");
        return JNI_FALSE;
    }

    RecordingConfig cfg;
    cfg.width = width;
    cfg.height = height;
    cfg.fps = fps;
    cfg.bitDepth = bitDepth;
    cfg.outputPath = jstringToStd(env, jOutputPath);
    cfg.container = jstringToStd(env, jContainer);
    cfg.enableZstd = enableZstd;
    cfg.zstdLevel = zstdLevel;

    std::string err;
    bool ok = engine->startRecording(cfg, err);
    if (!ok) {
        LOGE("nativeStartRecording failed: %s", err.c_str());
    } else {
        LOGI("nativeStartRecording ok: %s", cfg.outputPath.c_str());
    }
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeStopRecording(
        JNIEnv* env, jobject /*thiz*/) {
    auto* engine = GetGlobalEngine();
    bool ok = engine->stopRecording();
    LOGI("nativeStopRecording -> %d", ok);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeIsRecording(
        JNIEnv* env, jobject /*thiz*/) {
    auto* engine = GetGlobalEngine();
    return static_cast<jboolean>(engine->isRecording());
}

// ---------------------------------------------------------------------------
// Frame Ingestion
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativePushFrame(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray jData, jint size,
        jlong timestampNs, jlong exposureNs, jint iso,
        jint width, jint height, jint stride, jint bitDepth,
        jint frameNumber) {

    auto* engine = GetGlobalEngine();
    if (!engine->isInitialized()) return JNI_FALSE;

    jbyte* dataPtr = env->GetByteArrayElements(jData, nullptr);
    if (!dataPtr) {
        LOGE("nativePushFrame: GetByteArrayElements failed");
        return JNI_FALSE;
    }

    FrameMetadata meta{};
    meta.timestampNs = timestampNs;
    meta.exposureTimeNs = exposureNs;
    meta.iso = iso;
    meta.width = width;
    meta.height = height;
    meta.stride = stride;
    meta.bitDepth = bitDepth;
    meta.frameNumber = frameNumber;

    bool ok = engine->onRawFrameAvailable(reinterpret_cast<uint8_t*>(dataPtr),
                                          static_cast<size_t>(size), meta);

    env->ReleaseByteArrayElements(jData, dataPtr, JNI_ABORT);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativePushDirectFrame(
        JNIEnv* env, jobject /*thiz*/,
        jobject byteBuffer, jint size,
        jlong timestampNs, jlong exposureNs, jint iso,
        jint width, jint height, jint stride, jint bitDepth,
        jint frameNumber) {

    auto* engine = GetGlobalEngine();
    if (!engine->isInitialized()) return JNI_FALSE;

    uint8_t* directAddr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!directAddr) {
        LOGE("nativePushDirectFrame: GetDirectBufferAddress failed");
        return JNI_FALSE;
    }

    FrameMetadata meta{};
    meta.timestampNs = timestampNs;
    meta.exposureTimeNs = exposureNs;
    meta.iso = iso;
    meta.width = width;
    meta.height = height;
    meta.stride = stride;
    meta.bitDepth = bitDepth;
    meta.frameNumber = frameNumber;

    bool ok = engine->onRawFrameAvailable(directAddr, static_cast<size_t>(size), meta);
    return static_cast<jboolean>(ok);
}

// ---------------------------------------------------------------------------
// Stats & Diagnostics
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeGetStatsJson(
        JNIEnv* env, jobject /*thiz*/) {

    auto* engine = GetGlobalEngine();
    auto stats = engine->getStats();

    char json[1024];
    snprintf(json, sizeof(json),
             "{\"poolAvailable\":%d,\"poolTotal\":%d,\"ringSize\":%d,\"ringCapacity\":%d,"
             "\"ringFill\":%.3f,\"framesPushed\":%llu,\"framesDropped\":%llu,\"framesWritten\":%llu,"
             "\"isRecording\":%s}",
             stats.poolAvailable, stats.poolTotal,
             stats.ringSize, stats.ringCapacity,
             stats.ringFillRatio,
             (unsigned long long)stats.framesPushed,
             (unsigned long long)stats.framesDropped,
             (unsigned long long)stats.framesWritten,
             engine->isRecording() ? "true" : "false");

    return env->NewStringUTF(json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeGetLastError(
        JNIEnv* env, jobject /*thiz*/) {
    auto* engine = GetGlobalEngine();
    std::string err = engine->getLastError();
    return env->NewStringUTF(err.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeGetVersion(
        JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("CineCam RAW Engine v0.1.0-alpha (NDK 26, C++17)");
}

// ---------------------------------------------------------------------------
// JNI_OnLoad - register and verify
// ---------------------------------------------------------------------------

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "CineCam JNI_OnLoad: NDK engine loaded (v0.1.0-alpha)");
    return JNI_VERSION_1_6;
}
