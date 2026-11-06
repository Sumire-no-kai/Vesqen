#pragma once

#include <cstdint>

namespace vesqen::usb {

/**
 * How many frames each service interval carries at the nominal rate. The remainder is carried
 * forward, so 44.1 kHz at 1 ms sends 44 frames nine times and 45 once, and never drifts.
 */
class PacketSchedule {
public:
    PacketSchedule(int sampleRateHz, int serviceIntervalUs)
        : increment_(static_cast<int64_t>(sampleRateHz) * serviceIntervalUs) {}

    int nextFrames() {
        accumulator_ += increment_;
        const int64_t frames = accumulator_ / kMicrosPerSecond;
        accumulator_ %= kMicrosPerSecond;
        return static_cast<int>(frames);
    }

    /** The most frames [nextFrames] can return for this rate and interval. */
    static int64_t maxFrames(int sampleRateHz, int serviceIntervalUs) {
        return (static_cast<int64_t>(sampleRateHz) * serviceIntervalUs + kMicrosPerSecond - 1) / kMicrosPerSecond;
    }

private:
    static constexpr int64_t kMicrosPerSecond = 1'000'000;
    const int64_t increment_;
    int64_t accumulator_ = 0;
};

}  // namespace vesqen::usb
