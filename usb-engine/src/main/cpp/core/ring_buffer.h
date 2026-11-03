#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>

namespace vesqen::usb {

/**
 * One producer and one consumer, counted in whole frames. Neither side ever waits, so the
 * stream thread cannot be held up by the thread that fills this.
 */
class FrameRingBuffer {
public:
    FrameRingBuffer(size_t capacityFrames, size_t frameBytes)
        : capacity_(capacityFrames),
          frameBytes_(frameBytes),
          storage_(std::make_unique<uint8_t[]>(capacityFrames * frameBytes)) {}

    /** Producer only. Returns how many frames fitted. */
    size_t write(const uint8_t* source, size_t frames) {
        const uint64_t written = written_.load(std::memory_order_relaxed);
        const uint64_t read = read_.load(std::memory_order_acquire);
        const size_t count = std::min(frames, capacity_ - static_cast<size_t>(written - read));
        const size_t start = static_cast<size_t>(written % capacity_);
        const size_t first = std::min(count, capacity_ - start);
        std::memcpy(storage_.get() + start * frameBytes_, source, first * frameBytes_);
        std::memcpy(storage_.get(), source + first * frameBytes_, (count - first) * frameBytes_);
        written_.store(written + count, std::memory_order_release);
        return count;
    }

    /** Consumer only. Returns how many frames were available. */
    size_t read(uint8_t* destination, size_t frames) {
        const uint64_t read = read_.load(std::memory_order_relaxed);
        const uint64_t written = written_.load(std::memory_order_acquire);
        const size_t count = std::min(frames, static_cast<size_t>(written - read));
        const size_t start = static_cast<size_t>(read % capacity_);
        const size_t first = std::min(count, capacity_ - start);
        std::memcpy(destination, storage_.get() + start * frameBytes_, first * frameBytes_);
        std::memcpy(destination + first * frameBytes_, storage_.get(), (count - first) * frameBytes_);
        read_.store(read + count, std::memory_order_release);
        return count;
    }

private:
    const size_t capacity_;
    const size_t frameBytes_;
    std::unique_ptr<uint8_t[]> storage_;
    alignas(64) std::atomic<uint64_t> written_{0};
    alignas(64) std::atomic<uint64_t> read_{0};
};

}  // namespace vesqen::usb
