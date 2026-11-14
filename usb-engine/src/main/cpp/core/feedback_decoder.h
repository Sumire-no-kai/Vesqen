#pragma once

#include <algorithm>
#include <cstdint>

namespace vesqen::usb {

/**
 * Reads an explicit feedback endpoint the way Linux's snd-usb-audio does (endpoint.c,
 * snd_usb_handle_sync_urb, checked 2026-11-14). The specification has full speed report 10.14 frames
 * per frame in three bytes and high speed 16.16 frames per microframe in four, but many devices do
 * otherwise, so the format is found from the first value by shifting it until it lies between -25 %
 * and +50 % of the nominal rate. Values are kept as 16.16 frames per data packet, which also covers a
 * data endpoint polled less often than every (micro)frame.
 */
class FeedbackDecoder {
public:
    enum class Result { kAccepted, kIgnored, kOutOfRange };

    /** [maxFrames] is the most one data packet can hold. */
    FeedbackDecoder(int sampleRateHz, int serviceIntervalUs, int maxFrames)
        : nominal_(nominalQ16(sampleRateHz, serviceIntervalUs)),
          maximum_(std::min(nominal_ + nominal_ / 2, static_cast<int64_t>(maxFrames) << 16)) {}

    /**
     * One packet as received. A packet shorter than three bytes or a zero value says nothing. A value
     * outside -12.5 % of the nominal rate and what a packet can hold is refused, and the format is
     * found again from the next one, as Linux does.
     */
    Result decode(const uint8_t* bytes, uint32_t length) {
        if (length < 3) return Result::kIgnored;
        int64_t value = bytes[0] | (bytes[1] << 8) | (bytes[2] << 16);
        // Linux keeps 28 bits of a four-byte value.
        if (length >= 4) value |= static_cast<int64_t>(bytes[3] & 0x0F) << 24;
        if (value == 0) return Result::kIgnored;
        if (shift_ == kUnknownShift) {
            int shift = 0;
            while (value < nominal_ - nominal_ / 4) {
                value <<= 1;
                ++shift;
            }
            while (value > nominal_ + nominal_ / 2) {
                value >>= 1;
                --shift;
            }
            shift_ = shift;
        } else {
            value = shift_ >= 0 ? value << shift_ : value >> -shift_;
        }
        if (value >= nominal_ - nominal_ / 8 && value <= maximum_) {
            framesQ16_ = value;
            return Result::kAccepted;
        }
        shift_ = kUnknownShift;
        return Result::kOutOfRange;
    }

    /** The last accepted value in 16.16 frames per data packet, or 0 before the first. */
    int64_t framesQ16() const { return framesQ16_; }

    static int64_t nominalQ16(int sampleRateHz, int serviceIntervalUs) {
        return (static_cast<int64_t>(sampleRateHz) * serviceIntervalUs * 65'536 + 500'000) / 1'000'000;
    }

private:
    static constexpr int kUnknownShift = -1'000;
    const int64_t nominal_;
    const int64_t maximum_;
    int shift_ = kUnknownShift;
    int64_t framesQ16_ = 0;
};

}  // namespace vesqen::usb
