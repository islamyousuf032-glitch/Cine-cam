#include "memory_pool.h"
#include "cine_types.h"
#include <cassert>
#include <limits>
#include <thread>
#include <vector>
#include <stdexcept>

int main() {
    using namespace cinecam;
    for (const auto size : {size_t(0), std::numeric_limits<size_t>::max()}) {
        bool rejected = false;
        try { MemoryPool pool(size, 2); }
        catch (const std::invalid_argument&) { rejected = true; }
        assert(rejected);
    }
    bool rejected = false;
    try { MemoryPool pool(1, -1); }
    catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected);
    MemoryPool pool(4097, 4);
    assert(pool.getBufferSize() == 8192);
    std::vector<int> slots;
    for (int i = 0; i < 4; ++i) {
        int slot = pool.acquire();
        assert(slot >= 0);
        assert(reinterpret_cast<uintptr_t>(pool.getBuffer(slot)) % 4096 == 0);
        slots.push_back(slot);
    }
    assert(pool.acquire() == -1);
    for (int slot : slots) pool.release(slot);
    pool.release(slots[0]); // Duplicate releases must not corrupt the free list.
    pool.release(-1);
    assert(pool.getAvailableCount() == 4);
    std::vector<std::thread> workers;
    for (int i = 0; i < 4; ++i) workers.emplace_back([&] {
        for (int j = 0; j < 10000; ++j) {
            int slot = pool.acquire();
            if (slot >= 0) { pool.getBuffer(slot)[0] = 42; pool.release(slot); }
        }
    });
    for (auto& worker : workers) worker.join();
    assert(pool.getAvailableCount() == 4);
    RawFrame frame;
    frame.data = pool.getBuffer(0);
    frame.poolSlotIndex = 0;
    frame.sizeBytes = 8;
    frame.isValid = true;
    RawFrame moved(std::move(frame));
    assert(frame.poolSlotIndex == -1 && frame.data == nullptr);
    RawFrame assigned;
    assigned = std::move(moved);
    assert(assigned.isValid && assigned.sizeBytes == 8);
    assert(moved.poolSlotIndex == -1 && !moved.isValid);
}
