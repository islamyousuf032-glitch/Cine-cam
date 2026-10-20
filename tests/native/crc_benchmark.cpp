#include "ccraw_writer.h"
#include "pipeline_timing.h"
#include <array>
#include <cstdio>
#include <vector>

// Host-only comparison with the previous byte-at-a-time algorithm. Not a phone/storage benchmark.
uint32_t bytewise(const uint8_t* p, size_t size) {
    static const auto table = [] {
        std::array<uint32_t, 256> t{};
        for (uint32_t i = 0; i < 256; ++i) {
            auto n = i;
            for (int b = 0; b < 8; ++b) n = (n >> 1) ^ ((n & 1) ? 0xedb88320U : 0);
            t[i] = n;
        }
        return t;
    }();
    uint32_t crc = 0xffffffffU;
    for (size_t i = 0; i < size; ++i) crc = table[(crc ^ p[i]) & 255] ^ (crc >> 8);
    return crc ^ 0xffffffffU;
}
int main() {
    std::vector<uint8_t> data(4080 * 3060 * 2);
    uint32_t seed = 31;
    for (auto& byte : data) { seed = seed * 1664525U + 1013904223U; byte = seed >> 24; }
    if (bytewise(data.data(), data.size()) != cinecam::ccrawCrc32(data.data(), data.size())) return 1;
    volatile uint32_t sink = 0;
    const auto start = cinecam::pipelineNowNs();
    for (int n = 0; n < 5; ++n) sink = sink ^ bytewise(data.data(), data.size());
    const auto middle = cinecam::pipelineNowNs();
    for (int n = 0; n < 5; ++n) sink = sink ^ cinecam::ccrawCrc32(data.data(), data.size());
    const auto end = cinecam::pipelineNowNs();
    std::printf("{\"scope\":\"host CRC only, not Galaxy F23 or disk performance\","
        "\"bytesPerIteration\":%zu,\"iterations\":5,\"bytewiseNs\":%llu,\"slicingBy8Ns\":%llu,\"sink\":%u}\n",
        data.size(), (unsigned long long)(middle - start), (unsigned long long)(end - middle), sink);
}
