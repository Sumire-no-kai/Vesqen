#pragma once

#include <pthread.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "core/packet_schedule.h"
#include "core/ring_buffer.h"

namespace vesqen::usb {

/** Arrives from Kotlin's StreamingPlan. It is checked again here before anything is allocated. */
struct StreamConfig {
    int sampleRateHz;
    int frameBytes;
    int serviceIntervalUs;
    int maxPacketBytes;
    int packetsPerUrb;
    int urbCount;
    int ringFrames;
};

bool isValid(const StreamConfig& config);

struct IsoPacket {
    uint32_t length = 0;
    uint32_t actualLength = 0;
    int32_t status = 0;
    uint32_t mediaFrames = 0;
};

struct IsoUrb {
    uint8_t* data = nullptr;
    IsoPacket* packets = nullptr;
    uint32_t packetCount = 0;
    void* transportState = nullptr;
    bool inFlight = false;
};

enum class ReapResult { kUrb, kNothing, kDeviceGone, kFailed };

struct StreamThreadReport {
    int nice = 0;
    int schedulerPolicy = 0;
};

/** usbdevfs on a device, a fake in tests. Only the stream thread calls this. */
class IsoTransport {
public:
    virtual ~IsoTransport() = default;
    /** Bytes of IsoUrb::transportState each URB needs. */
    virtual size_t stateBytes(uint32_t packetCount) const = 0;
    /** Returns 0 or an errno. */
    virtual int submit(IsoUrb& urb) = 0;
    /** The URB still has to be reaped afterwards. */
    virtual void discard(IsoUrb& urb) = 0;
    /** Waits at most [timeoutMs]. Sets [urb] on kUrb and [error] on kFailed. */
    virtual ReapResult reap(IsoUrb*& urb, int timeoutMs, int& error) = 0;
    virtual StreamThreadReport prepareStreamThread() { return {}; }
};

/** The order is shared with UsbStreamStats in Kotlin. */
enum Stat : size_t {
    kStatMediaFramesWritten,
    kStatMediaFramesSubmitted,
    kStatMediaFramesProcessed,
    kStatMediaFramesDelivered,
    kStatMediaFramesLost,
    kStatSilenceFramesInserted,
    kStatUsbFramesDelivered,
    kStatUnderruns,
    kStatUrbsCompleted,
    kStatPacketsFailed,
    kStatFailure,
    kStatFailureErrno,
    kStatThreadNice,
    kStatThreadPolicy,
    kStatCount,
};

enum StreamFailure : int64_t {
    kFailureNone = 0,
    kFailureThreadStart = 1,
    kFailureSubmit = 2,
    kFailureDeviceGone = 3,
    kFailureReap = 4,
};

/**
 * Keeps isochronous URBs in flight, filled from a ring buffer. Silence fills what the ring cannot,
 * and is counted apart from media so progress never includes it. Nothing here calls back into the
 * JVM, and no call made from outside the stream thread waits, except [stop] and only up to its limit.
 */
class StreamEngine {
public:
    StreamEngine(const StreamConfig& config, IsoTransport& transport);
    ~StreamEngine();

    bool start();
    /** Returns the bytes accepted, or -1 for a partial frame or a stream that has ended. */
    int32_t write(const uint8_t* source, int32_t bytes);
    void setRunning(bool running);
    void markEndOfStream();
    void readStats(int64_t* out) const;

    /**
     * Cancels what is in flight and waits up to [timeoutMs] for it to come back. False means URBs
     * are still in the kernel, so this object and the file descriptor have to stay alive, and a
     * later call can succeed.
     */
    bool stop(int timeoutMs);

private:
    static void* threadEntry(void* engine);
    void run();
    void fill(IsoUrb& urb);
    void account(const IsoUrb& urb, bool cancelling);
    void fail(StreamFailure failure, int error);
    void failSubmit(int error);
    void add(Stat stat, int64_t amount) { stats_[stat].fetch_add(amount, std::memory_order_relaxed); }

    const StreamConfig config_;
    IsoTransport& transport_;
    PacketSchedule schedule_;
    FrameRingBuffer ring_;
    std::vector<IsoUrb> urbs_;
    std::vector<std::unique_ptr<uint8_t[]>> payloads_;
    std::vector<std::unique_ptr<IsoPacket[]>> packets_;
    std::vector<std::unique_ptr<uint8_t[]>> transportStates_;

    pthread_t thread_{};
    bool threadStarted_ = false;
    bool threadJoined_ = false;

    std::atomic<bool> running_{false};
    std::atomic<bool> endOfStream_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> quiesced_{false};
    std::atomic<int64_t> stats_[kStatCount]{};

    // Stream thread only.
    bool mediaStarted_ = false;
    bool inUnderrun_ = false;
    int inFlight_ = 0;
};

}  // namespace vesqen::usb
