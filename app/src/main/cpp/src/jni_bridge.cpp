#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include "cine_raw_engine.h"
#include "cine_types.h"
#include "raw16_codec.h"
#include "bp16_archive.h"
#include "export_copy.h"
#include "dng_preview.h"
#include "ccraw_preview.h"
#include <vector>
#include <array>
#include <algorithm>

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
    if (!chars) return "";
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

    if (env->ExceptionCheck()) return JNI_FALSE;
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

    if (!jData || size <= 0 || size > env->GetArrayLength(jData)) return JNI_FALSE;
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

    if (!byteBuffer || size <= 0 || static_cast<jlong>(size) > env->GetDirectBufferCapacity(byteBuffer)) return JNI_FALSE;
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

    char json[4096];
    snprintf(json, sizeof(json),
        "{\"poolAvailable\":%d,\"poolTotal\":%d,\"poolPeakUsed\":%d,\"poolPreTouchNs\":%llu,\"ringSize\":%d,\"ringCapacity\":%d,"
        "\"ringFill\":%.3f,\"framesPushed\":%llu,\"framesDropped\":%llu,\"framesWritten\":%llu,"
        "\"writerStatus\":%d,\"isRecording\":%s,"
        "\"dropReasons\":{\"poolExhausted\":%llu,\"controlBusy\":%llu,\"invalidLayout\":%llu,"
        "\"timestampRejected\":%llu,\"queueRejected\":%llu,\"notRecording\":%llu,\"writerUnavailable\":%llu},"
        "\"rowCopyNs\":%llu,\"maxRowCopyNs\":%llu,"
        "\"writerTimings\":{\"crcBackend\":\"%s\",\"hardwareCrcStatus\":\"%s\",\"payloadBytes\":%llu,\"crcNs\":%llu,"
        "\"writeSyscallNs\":%llu,\"fsyncNs\":%llu,\"appendNs\":%llu,\"maxAppendNs\":%llu,\"finalizeNs\":%llu,"
        "\"allocationNs\":%llu,\"reservedBytes\":%llu,\"allocationError\":%d,\"truncateNs\":%llu,"
        "\"closeNs\":%llu,\"renameNs\":%llu,\"writeCalls\":%llu,\"maxWriteNs\":%llu}}",
        stats.poolAvailable, stats.poolTotal, stats.poolPeakUsed, (unsigned long long)stats.poolPreTouchNs, stats.ringSize, stats.ringCapacity, stats.ringFillRatio,
        (unsigned long long)stats.framesPushed, (unsigned long long)stats.framesDropped, (unsigned long long)stats.framesWritten,
        static_cast<int>(stats.writerStatus), engine->isRecording() ? "true" : "false",
        (unsigned long long)stats.poolExhausted, (unsigned long long)stats.controlBusy, (unsigned long long)stats.invalidLayout,
        (unsigned long long)stats.timestampRejected, (unsigned long long)stats.queueRejected,
        (unsigned long long)stats.notRecording, (unsigned long long)stats.writerUnavailable,
        (unsigned long long)stats.rowCopyNs, (unsigned long long)stats.maxRowCopyNs,
        ccrawCrcBackend(), ccrawHardwareCrcStatus(),
        (unsigned long long)stats.writerTimings.payloadBytes, (unsigned long long)stats.writerTimings.crcNs,
        (unsigned long long)stats.writerTimings.writeSyscallNs, (unsigned long long)stats.writerTimings.fsyncNs,
        (unsigned long long)stats.writerTimings.appendNs, (unsigned long long)stats.writerTimings.maxAppendNs,
        (unsigned long long)stats.writerTimings.finalizeNs,
        (unsigned long long)stats.writerTimings.allocationNs, (unsigned long long)stats.writerTimings.reservedBytes,
        stats.writerTimings.allocationError, (unsigned long long)stats.writerTimings.truncateNs,
        (unsigned long long)stats.writerTimings.closeNs, (unsigned long long)stats.writerTimings.renameNs,
        (unsigned long long)stats.writerTimings.writeCalls, (unsigned long long)stats.writerTimings.maxWriteNs);

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
    return env->NewStringUTF("CineCam CCRAW001 / runtime CRC dispatch / bounded paced test (C++17)");
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

// Bounded diagnostic burst: RAW_SENSOR storage is 16-bit, sensor precision is separate metadata.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeStartRawClip(
        JNIEnv* env, jobject, jint width, jint height, jstring path, jlong epochNs, jboolean preallocate) {
    auto* engine = GetGlobalEngine();
    const auto directory = jstringToStd(env, path);
    if (env->ExceptionCheck() || epochNs < 0) return JNI_FALSE;
    if (!engine->initialize(width, height, 16, 3, true)) return JNI_FALSE;
    RecordingConfig config;
    config.width = width; config.height = height; config.bitDepth = 16;
    config.preallocateCcraw = preallocate == JNI_TRUE;
    config.fps = 30; config.epochNs = epochNs; config.container = "ccraw";
    config.outputPath = directory; config.enableAudio = false; config.enableZstd = false;
    std::string error;
    return engine->startRecording(config, error) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativePushRawPlane(
        JNIEnv* env, jobject, jobject buffer, jint width, jint height, jint rowStride, jint pixelStride,
        jlong timestamp, jlong exposure, jint iso, jint number,
        jfloat focus, jfloat neutralR, jfloat neutralG, jfloat neutralB) {
    if (!buffer || pixelStride != 2) return JNI_FALSE;
    const auto capacity = env->GetDirectBufferCapacity(buffer);
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data || capacity <= 0 || static_cast<uint64_t>(capacity) > SIZE_MAX) return JNI_FALSE;
    FrameMetadata metadata;
    metadata.width = width; metadata.height = height; metadata.stride = rowStride; metadata.bitDepth = 16;
    metadata.timestampNs = timestamp; metadata.exposureTimeNs = exposure; metadata.iso = iso;
    metadata.frameNumber = number; metadata.focusDistance = focus;
    metadata.neutralWhitePoint[0] = neutralR; metadata.neutralWhitePoint[1] = neutralG; metadata.neutralWhitePoint[2] = neutralB;
    return GetGlobalEngine()->onRaw16PlaneAvailable(data, static_cast<size_t>(capacity), metadata) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeRawClipCapacity(JNIEnv*, jobject) {
    return GetGlobalEngine()->ingestionCapacity();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeProbeRaw16Codec(
        JNIEnv* env, jobject, jstring path, jlong offset, jlong bytes) {
    const auto file = jstringToStd(env, path);
    if (env->ExceptionCheck()) return nullptr;
    const auto probe = probeRaw16File(file.c_str(), offset, bytes);
    char json[1024];
    snprintf(json, sizeof(json),
        "{\"codec\":\"experimental-bp16-block\",\"roundtripOk\":%s,\"inputBytes\":%llu,"
        "\"packedBytes\":%llu,\"blocks\":%llu,\"readNs\":%llu,\"packNs\":%llu,\"unpackNs\":%llu,"
        "\"compressedRecordingEnabled\":false,\"savedCompressedFile\":false,\"error\":\"%s\"}",
        probe.ok ? "true" : "false", (unsigned long long)probe.inputBytes, (unsigned long long)probe.packedBytes,
        (unsigned long long)probe.blocks, (unsigned long long)probe.readNs, (unsigned long long)probe.packNs,
        (unsigned long long)probe.unpackNs, probe.error);
    return env->NewStringUTF(json);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeCreateBp16Archive(JNIEnv* env, jobject, jstring source, jstring destination) {
    const auto input = jstringToStd(env, source);
    if (env->ExceptionCheck()) return nullptr;
    const auto output = jstringToStd(env, destination);
    if (env->ExceptionCheck()) return nullptr;
    const auto r = createBp16Archive(input.c_str(), output.c_str());
    char json[2048];
    snprintf(json, sizeof(json), "{\"format\":\"CCBP0001\",\"ok\":%s,\"sourceBytes\":%llu,\"archiveBytes\":%llu,"
        "\"encodeWriteNs\":%llu,\"verifyNs\":%llu,"
        "\"sourceReadNs\":%llu,\"packNs\":%llu,\"crcNs\":%llu,\"writeNs\":%llu,"
        "\"fsyncNs\":%llu,\"closeNs\":%llu,\"maxWriteNs\":%llu,"
        "\"readCalls\":%llu,\"writeCalls\":%llu,\"blocks\":%llu,"
        "\"blockWriteMode\":\"coalesced-record-v1\",\"timingScope\":\"encode-phase-wall-time-no-verify\",\"byteExactSourceComparison\":%s,\"compressedRecordingEnabled\":false,\"error\":\"%s\"}",
        r.ok ? "true" : "false", (unsigned long long)r.sourceBytes, (unsigned long long)r.archiveBytes,
        (unsigned long long)r.encodeWriteNs, (unsigned long long)r.verifyNs,
        (unsigned long long)r.sourceReadNs, (unsigned long long)r.packNs, (unsigned long long)r.crcNs,
        (unsigned long long)r.writeNs, (unsigned long long)r.fsyncNs, (unsigned long long)r.closeNs,
        (unsigned long long)r.maxWriteNs, (unsigned long long)r.readCalls, (unsigned long long)r.writeCalls,
        (unsigned long long)r.blocks, r.ok ? "true" : "false", r.error);
    return env->NewStringUTF(json);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeRenderDng(JNIEnv* env, jobject, jint fd, jint maxDimension) {
    const auto preview = renderDngPreview(static_cast<int>(fd), static_cast<int>(maxDimension));
    std::vector<jint> out;
    out.reserve(4 + preview.argb.size());
    out.push_back(static_cast<jint>(preview.width));
    out.push_back(static_cast<jint>(preview.height));
    out.push_back(static_cast<jint>(preview.orientation));
    out.push_back(preview.ok ? 0 : 1);
    for (uint32_t pixel : preview.argb) out.push_back(static_cast<jint>(pixel));
    jintArray result = env->NewIntArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jint*>(out.data()));
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeRenderCcrawFrame(JNIEnv* env, jobject, jint fd,
        jint frameIndex, jint maxDimension, jint orientation, jint cfa,
        jdouble neutralR, jdouble neutralG, jdouble neutralB, jdouble white,
        jdoubleArray black, jdoubleArray matrix, jint matrixMode) {
    CcrawCalibration calibration;
    calibration.cfa = static_cast<int>(cfa);
    calibration.neutral[0] = neutralR;
    calibration.neutral[1] = neutralG;
    calibration.neutral[2] = neutralB;
    calibration.white = white;
    std::array<double, 4> blackValues{};
    std::array<double, 9> matrixValues{};
    bool hasBlack = false, hasMatrix = false;
    if (black != nullptr && env->GetArrayLength(black) == 4) {
        env->GetDoubleArrayRegion(black, 0, 4, blackValues.data());
        hasBlack = env->ExceptionCheck() == JNI_FALSE;
    }
    if (matrix != nullptr && env->GetArrayLength(matrix) == 9) {
        env->GetDoubleArrayRegion(matrix, 0, 9, matrixValues.data());
        hasMatrix = env->ExceptionCheck() == JNI_FALSE;
    }
    if (hasBlack) std::copy(blackValues.begin(), blackValues.end(), calibration.black);
    calibration.valid = neutralR > 0 && neutralG > 0 && neutralB > 0 && white > 0 && hasBlack;
    calibration.matrix = hasMatrix ? matrixValues.data() : nullptr;
    calibration.matrixMode = hasMatrix ? static_cast<int>(matrixMode) : 0;
    const auto result = renderCcrawFrame(static_cast<int>(fd), static_cast<int>(frameIndex),
        static_cast<int>(maxDimension), static_cast<int>(orientation), calibration);
    const auto& preview = result.image;
    std::vector<jint> out;
    out.reserve(4 + preview.argb.size());
    out.push_back(static_cast<jint>(preview.width));
    out.push_back(static_cast<jint>(preview.height));
    out.push_back(static_cast<jint>(preview.orientation));
    out.push_back(preview.ok ? 0 : 1);
    for (uint32_t pixel : preview.argb) out.push_back(static_cast<jint>(pixel));
    jintArray pixels = env->NewIntArray(static_cast<jsize>(out.size()));
    if (pixels == nullptr) return nullptr;
    env->SetIntArrayRegion(pixels, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jint*>(out.data()));
    return pixels;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativePreviewError(JNIEnv* env, jobject) {
    return env->NewStringUTF(lastPreviewError());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cinecam_engine_NativeRawEngine_nativeCopyExport(JNIEnv* env, jobject, jstring source, jint outputFd) {
    const auto path = jstringToStd(env, source);
    if (env->ExceptionCheck()) return nullptr;
    const auto r = copyExportFile(path.c_str(), outputFd);
    char json[768];
    snprintf(json, sizeof(json), "{\"ok\":%s,\"bytes\":%llu,\"kernelBytes\":%llu,\"bufferedBytes\":%llu,"
        "\"copyNs\":%llu,\"fsyncNs\":%llu,\"error\":\"%s\"}",
        r.ok ? "true" : "false", (unsigned long long)r.bytes, (unsigned long long)r.kernelBytes,
        (unsigned long long)r.bufferedBytes, (unsigned long long)r.copyNs, (unsigned long long)r.fsyncNs, r.error);
    return env->NewStringUTF(json);
}
