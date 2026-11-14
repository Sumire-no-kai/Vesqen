#pragma once

#include <algorithm>
#include <cstdint>

namespace vesqen::usb {

/**
 * How many frames each service interval carries. At the nominal rate the remainder is carried
 * forward, so 44.1 kHz at 1 ms sends 44 frames nine times and 45 once, and never drifts. An
 * asynchronous endpoint's feedback replaces the nominal rate once it has sent a usable value.
 */
class PacketSchedule {
public:
    PacketSchedule(int sampleRateHz, int serviceIntervalUs)
        : increment_(static_cast<int64_t>(sampleRateHz) * serviceIntervalUs) {}

    int nextFrames() {
        if (feedbackQ16_ > 0) {
            // As Linux does: the fraction carries forward, and a packet never exceeds what it can hold.
            phase_ = (phase_ & 0xFFFF) + feedbackQ16_;
            return static_cast<int>(std::min<int64_t>(phase_ >> 16, maxFrames_));
        }
        accumulator_ += increment_;
        const int64_t frames = accumulator_ / kMicrosPerSecond;
        accumulator_ %= kMicrosPerSecond;
        return static_cast<int>(frames);
    }

    /** From now on, [framesQ16] frames per packet in 16.16 fixed point, but never more than [maxFrames]. */
    void follow(int64_t framesQ16, int maxFrames) {
        feedbackQ16_ = framesQ16;
        maxFrames_ = maxFrames;
    }

    /** The most frames [nextFrames] can return for this rate and interval at the nominal rate. */
    static int64_t maxFrames(int sampleRateHz, int serviceIntervalUs) {
        return (static_cast<int64_t>(sampleRateHz) * serviceIntervalUs + kMicrosPerSecond - 1) / kMicrosPerSecond;
    }

private:
    static constexpr int64_t kMicrosPerSecond = 1'000'000;
    const int64_t increment_;
    int64_t accumulator_ = 0;
    int64_t feedbackQ16_ = 0;
    int64_t phase_ = 0;
    int maxFrames_ = 0;
};

}  // namespace vesqen::usb
