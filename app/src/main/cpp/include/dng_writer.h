#pragma once
#include "cine_types.h"
#include <string>
namespace cinecam {
class DngWriter {
public:
    static bool writeDng(const RawFrame& frame, const std::string& path, std::string& error);
};
}
