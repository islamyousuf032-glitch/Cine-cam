#pragma once

#ifdef __ANDROID__
#include <android/log.h>
#define CINE_LOGI(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)
#define CINE_LOGW(tag, ...) __android_log_print(ANDROID_LOG_WARN, tag, __VA_ARGS__)
#define CINE_LOGE(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)
#else
// Host regression tests use the real pipeline; only Android logging is substituted.
inline void cineLogDiscard(const char*, const char*, ...) {}
#define CINE_LOGI(tag, ...) cineLogDiscard(tag, __VA_ARGS__)
#define CINE_LOGW(tag, ...) cineLogDiscard(tag, __VA_ARGS__)
#define CINE_LOGE(tag, ...) cineLogDiscard(tag, __VA_ARGS__)
#endif
