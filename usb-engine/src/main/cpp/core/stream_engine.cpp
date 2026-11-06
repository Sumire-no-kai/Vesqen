#include "core/stream_engine.h"

#include <errno.h>
#include <time.h>

#include <cstring>

namespace vesqen::usb {

namespace {

constexpr int kReapTimeoutMs = 50;
constexpr int kMaxPacketsPerUrb = 128;  // The kernel's limit for one isochronous URB.
constexpr int kMaxUrbs = 32;
constexpr int kMaxPacketBytes = 1024;  // Extra high-speed transactions are refused before this point.
constexpr int kMaxRingFrames = 1 << 20;

void sleepMs(int milliseconds) {
    timespec duration{milliseconds / 1000, (milliseconds % 1000) * 1'000'000L};
    nanosleep(&duration, nullptr);
}

}  // namespace

bool isValid(const StreamConfig& config) {
    if (config.sampleRateHz <= 0 || config.sampleRateHz > 768'000) return false;
    if (config.frameBytes <= 0 || config.frameBytes > 8) return false;
    if (config.serviceIntervalUs <= 0 || config.serviceIntervalUs > 64'000) return false;
    if (config.maxPacketBytes <= 0 || config.maxPacketBytes > kMaxPacketBytes) return false;
    if (config.packetsPerUrb <= 0 || config.packetsPerUrb > kMaxPacketsPerUrb) return false;
    if (config.urbCount <= 0 || config.urbCount > kMaxUrbs) return false;
    if (config.ringFrames <= 0 || config.ringFrames > kMaxRingFrames) return false;
    const int64_t maxFrames = PacketSchedule::maxFrames(config.sampleRateHz, config.serviceIntervalUs);
    return maxFrames > 0 && maxFrames * config.frameBytes <= config.maxPacketBytes;
}

StreamEngine::StreamEngine(const StreamConfig& config, IsoTransport& transport)
    : config_(config),
      transport_(transport),
      schedule_(config.sampleRateHz, config.serviceIntervalUs),
      ring_(static_cast<size_t>(config.ringFrames), static_cast<size_t>(config.frameBytes)) {
    const auto packetCount = static_cast<uint32_t>(config.packetsPerUrb);
    const size_t payloadBytes = static_cast<size_t>(config.maxPacketBytes) * packetCount;
    const size_t stateBytes = transport.stateBytes(packetCount);
    urbs_.resize(static_cast<size_t>(config.urbCount));
    for (IsoUrb& urb : urbs_) {
        payloads_.push_back(std::make_unique<uint8_t[]>(payloadBytes));
        packets_.push_back(std::make_unique<IsoPacket[]>(packetCount));
        transportStates_.push_back(std::make_unique<uint8_t[]>(stateBytes));
        urb.data = payloads_.back().get();
        urb.packets = packets_.back().get();
        urb.packetCount = packetCount;
        urb.transportState = transportStates_.back().get();
    }
}

StreamEngine::~StreamEngine() {
    // The owner has to see stop() succeed first: the kernel may still be using these URBs.
    if (threadStarted_ && !threadJoined_) pthread_join(thread_, nullptr);
}

bool StreamEngine::start() {
    if (threadStarted_) return false;
    if (pthread_create(&thread_, nullptr, &StreamEngine::threadEntry, this) != 0) {
        fail(kFailureThreadStart, 0);
        return false;
    }
    threadStarted_ = true;
    return true;
}

int32_t StreamEngine::write(const uint8_t* source, int32_t bytes) {
    if (bytes < 0 || bytes % config_.frameBytes != 0) return -1;
    if (stopRequested_.load(std::memory_order_acquire) ||
        stats_[kStatFailure].load(std::memory_order_acquire) != kFailureNone) {
        return -1;
    }
    const size_t frames = ring_.write(source, static_cast<size_t>(bytes / config_.frameBytes));
    add(kStatMediaFramesWritten, static_cast<int64_t>(frames));
    return static_cast<int32_t>(frames) * config_.frameBytes;
}

void StreamEngine::setRunning(bool running) { running_.store(running, std::memory_order_release); }

void StreamEngine::markEndOfStream() { endOfStream_.store(true, std::memory_order_release); }

void StreamEngine::readStats(int64_t* out) const {
    for (size_t i = 0; i < kStatCount; ++i) out[i] = stats_[i].load(std::memory_order_relaxed);
}

bool StreamEngine::stop(int timeoutMs) {
    stopRequested_.store(true, std::memory_order_release);
    if (!threadStarted_) return true;
    for (int waited = 0; !quiesced_.load(std::memory_order_acquire); waited += 5) {
        if (waited >= timeoutMs) return false;
        sleepMs(5);
    }
    if (!threadJoined_) {
        pthread_join(thread_, nullptr);
        threadJoined_ = true;
    }
    return true;
}

void* StreamEngine::threadEntry(void* engine) {
    static_cast<StreamEngine*>(engine)->run();
    return nullptr;
}

void StreamEngine::run() {
    const StreamThreadReport report = transport_.prepareStreamThread();
    stats_[kStatThreadNice].store(report.nice, std::memory_order_relaxed);
    stats_[kStatThreadPolicy].store(report.schedulerPolicy, std::memory_order_relaxed);

    bool healthy = true;
    for (IsoUrb& urb : urbs_) {
        fill(urb);
        const int error = transport_.submit(urb);
        if (error != 0) {
            failSubmit(error);
            healthy = false;
            break;
        }
        urb.inFlight = true;
        ++inFlight_;
    }

    while (healthy && !stopRequested_.load(std::memory_order_acquire)) {
        IsoUrb* urb = nullptr;
        int error = 0;
        switch (transport_.reap(urb, kReapTimeoutMs, error)) {
            case ReapResult::kNothing:
                break;
            case ReapResult::kDeviceGone:
                fail(kFailureDeviceGone, 0);
                healthy = false;
                break;
            case ReapResult::kFailed:
                fail(kFailureReap, error);
                healthy = false;
                break;
            case ReapResult::kUrb: {
                account(*urb, false);
                urb->inFlight = false;
                --inFlight_;
                if (stopRequested_.load(std::memory_order_acquire)) break;
                fill(*urb);
                const int submitError = transport_.submit(*urb);
                if (submitError != 0) {
                    failSubmit(submitError);
                    healthy = false;
                    break;
                }
                urb->inFlight = true;
                ++inFlight_;
                break;
            }
        }
    }

    // A discarded URB still comes back through reap, and until it does the kernel owns its buffers.
    for (IsoUrb& urb : urbs_) {
        if (urb.inFlight) transport_.discard(urb);
    }
    while (inFlight_ > 0) {
        IsoUrb* urb = nullptr;
        int error = 0;
        switch (transport_.reap(urb, kReapTimeoutMs, error)) {
            case ReapResult::kUrb:
                account(*urb, true);
                urb->inFlight = false;
                --inFlight_;
                break;
            case ReapResult::kNothing:
                break;
            case ReapResult::kDeviceGone:
            case ReapResult::kFailed:
                sleepMs(kReapTimeoutMs);
                break;
        }
    }
    quiesced_.store(true, std::memory_order_release);
}

void StreamEngine::fill(IsoUrb& urb) {
    const auto frameBytes = static_cast<size_t>(config_.frameBytes);
    const bool running = running_.load(std::memory_order_acquire);
    uint8_t* out = urb.data;
    for (uint32_t i = 0; i < urb.packetCount; ++i) {
        const auto frames = static_cast<size_t>(schedule_.nextFrames());
        const size_t media = running ? ring_.read(out, frames) : 0;
        if (media < frames) std::memset(out + media * frameBytes, 0, (frames - media) * frameBytes);

        IsoPacket& packet = urb.packets[i];
        packet.length = static_cast<uint32_t>(frames * frameBytes);
        packet.actualLength = 0;
        packet.status = 0;
        packet.mediaFrames = static_cast<uint32_t>(media);
        out += packet.length;

        if (media > 0) mediaStarted_ = true;
        add(kStatMediaFramesSubmitted, static_cast<int64_t>(media));
        add(kStatSilenceFramesInserted, static_cast<int64_t>(frames - media));
        if (media == frames) {
            inUnderrun_ = false;
        } else if (running && mediaStarted_ && !endOfStream_.load(std::memory_order_acquire) && !inUnderrun_) {
            inUnderrun_ = true;
            add(kStatUnderruns, 1);
        }
    }
}

void StreamEngine::account(const IsoUrb& urb, bool cancelling) {
    add(kStatUrbsCompleted, 1);
    const auto frameBytes = static_cast<uint32_t>(config_.frameBytes);
    for (uint32_t i = 0; i < urb.packetCount; ++i) {
        const IsoPacket& packet = urb.packets[i];
        const bool delivered = packet.status == 0 && packet.actualLength == packet.length;
        if (delivered) {
            add(kStatMediaFramesProcessed, packet.mediaFrames);
            add(kStatMediaFramesDelivered, packet.mediaFrames);
            add(kStatUsbFramesDelivered, packet.length / frameBytes);
        } else if (!cancelling) {
            add(kStatMediaFramesProcessed, packet.mediaFrames);
            add(kStatMediaFramesLost, packet.mediaFrames);
            add(kStatPacketsFailed, 1);
        }
    }
}

void StreamEngine::failSubmit(int error) {
    // A device removed between two URBs shows up here before reap reports it.
    if (error == ENODEV) {
        fail(kFailureDeviceGone, 0);
    } else {
        fail(kFailureSubmit, error);
    }
}

void StreamEngine::fail(StreamFailure failure, int error) {
    int64_t expected = kFailureNone;
    if (stats_[kStatFailure].compare_exchange_strong(expected, failure, std::memory_order_acq_rel)) {
        stats_[kStatFailureErrno].store(error, std::memory_order_relaxed);
    }
}

}  // namespace vesqen::usb
