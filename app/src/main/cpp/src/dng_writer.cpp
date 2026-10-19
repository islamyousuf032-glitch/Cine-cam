#include "dng_writer.h"
#include <android/log.h>
#include <fstream>
#define LOG_TAG "CineDNG"
namespace cinecam {
bool DngWriter::writeDng(const RawFrame& frame, const std::string& path, std::string& error) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out.is_open()) { error = "Cannot open " + path; return false; }
    if (frame.data && frame.sizeBytes > 0) out.write((const char*)frame.data, frame.sizeBytes);
    out.close();
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "DNG written: %s", path.c_str());
    return true;
}
}
