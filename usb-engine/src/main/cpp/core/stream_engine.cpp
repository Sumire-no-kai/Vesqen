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
constexpr int kMinFeedbackBytes = 3;
constexpr int kMaxFeedbackIntervalUs = 1000 << 15;  // bInterval 16 at full speed.
// Two keep the endpoint polled while the stream thread handles the other.
constexpr int kFeedbackUrbs = 2;

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
    if (maxFrames <= 0 || maxFrames * config.frameBytes > config.maxPacketBytes) return false;
    if (config.feedbackMaxPacketBytes == 0) return true;
    if (config.feedbackMaxPacketBytes < kMinFeedbackBytes || config.feedbackMaxPacketBytes > kMaxPacketBytes) return false;
    if (config.feedbackIntervalUs <= 0 || config.feedbackIntervalUs > kMaxFeedbackIntervalUs) return false;
    if (config.feedbackPacketsPerUrb <= 0 || config.feedbackPacketsPerUrb > kMaxPacketsPerUrb) return false;
    // A value must have a chance to arrive before one is missed.
    if (config.feedbackTimeoutUs < config.feedbackIntervalUs) return false;
    // Finding the format by shifting needs at least one frame per packet to compare with.
    return FeedbackDecoder::nominalQ16(config.sampleRateHz, config.serviceIntervalUs) >= 1 << 16;
}

StreamEngine::StreamEngine(const StreamConfig& config, IsoTransport& transport)
    : config_(config),
      transport_(transport),
      schedule_(config.sampleRateHz, config.serviceIntervalUs),
      ring_(static_cast<size_t>(config.ringFrames), static_cast<size_t>(config.frameBytes)),
      maxFramesPerPacket_(config.maxPacketBytes / config.frameBytes),
      feedback_(config.sampleRateHz, config.serviceIntervalUs, maxFramesPerPacket_) {
    const auto allocate = [&](std::vector<IsoUrb>& urbs, int count, int packetsPerUrb, int packetBytes) {
        const auto packetCount = static_cast<uint32_t>(packetsPerUrb);
        const size_t payloadBytes = static_cast<size_t>(packetBytes) * packetCount;
        const size_t stateBytes = transport.stateBytes(packetCount);
        urbs.resize(static_cast<size_t>(count));
        for (IsoUrb& urb : urbs) {
            payloads_.push_back(std::make_unique<uint8_t[]>(payloadBytes));
            packets_.push_back(std::make_unique<IsoPacket[]>(packetCount));
            transportStates_.push_back(std::make_unique<uint8_t[]>(stateBytes));
            urb.data = payloads_.back().get();
            urb.packets = packets_.back().get();
            urb.packetCount = packetCount;
            urb.transportState = transportStates_.back().get();
        }
    };
    allocate(urbs_, config.urbCount, config.packetsPerUrb, config.maxPacketBytes);
    if (config.feedbackMaxPacketBytes > 0) {
        allocate(feedbackUrbs_, kFeedbackUrbs, config.feedbackPacketsPerUrb, config.feedbackMaxPacketBytes);
        for (IsoUrb& urb : feedbackUrbs_) urb.feedback = true;
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

int32_t StreamEngine::write(int64_t epoch, const uint8_t* source, int32_t bytes) {
    if (bytes < 0 || bytes % config_.frameBytes != 0) return -1;
    Lock lock(epochMutex_);
    if (stopRequested_.load(std::memory_order_acquire) ||
        stats_[kStatFailure].load(std::memory_order_acquire) != kFailureNone) {
        return -1;
    }
    const int64_t newest = requestedEpoch_.load(std::memory_order_relaxed);
    if (epoch != newest) return -1;
    // The last output's URBs are not all back yet, so its audio could still follow this one's.
    if (acknowledgedEpoch_.load(std::memory_order_relaxed) != newest) return 0;
    const size_t frames = ring_.write(source, static_cast<size_t>(bytes / config_.frameBytes));
    add(kStatMediaFramesWritten, static_cast<int64_t>(frames));
    return static_cast<int32_t>(frames) * config_.frameBytes;
}

void StreamEngine::setRunning(int64_t epoch, bool running) {
    Lock lock(epochMutex_);
    if (epoch == requestedEpoch_.load(std::memory_order_relaxed)) running_.store(running, std::memory_order_release);
}

void StreamEngine::markEndOfStream(int64_t epoch) {
    Lock lock(epochMutex_);
    if (epoch == requestedEpoch_.load(std::memory_order_relaxed)) endOfStream_.store(true, std::memory_order_release);
}

int64_t StreamEngine::retireOutput() {
    Lock lock(epochMutex_);
    running_.store(false, std::memory_order_release);
    endOfStream_.store(false, std::memory_order_release);
    return requestedEpoch_.fetch_add(1, std::memory_order_acq_rel) + 1;
}

void StreamEngine::readStats(int64_t* out) const {
    // The stream thread resets the counters between two stores of the acknowledged epoch, the first
    // of them -1, and resets them with release stores. A counter read with acquire that shows a reset
    // therefore makes the later read of the epoch see -1 or newer, so a read that sees the same epoch
    // before and after has one epoch's values. (No fence: GCC refuses one under ThreadSanitizer.)
    for (int attempt = 0; attempt < 16; ++attempt) {
        const int64_t before = acknowledgedEpoch_.load(std::memory_order_acquire);
        // Read completions before the upstream counters that bound them. Each published completion
        // then carries its submission and write into this snapshot even if the stream advances.
        for (size_t i = kStatMediaFramesDelivered; i < kStatCount; ++i) out[i] = stats_[i].load(std::memory_order_acquire);
        out[kStatMediaFramesProcessed] = stats_[kStatMediaFramesProcessed].load(std::memory_order_acquire);
        out[kStatMediaFramesSubmitted] = stats_[kStatMediaFramesSubmitted].load(std::memory_order_acquire);
        out[kStatMediaFramesWritten] = stats_[kStatMediaFramesWritten].load(std::memory_order_acquire);
        const int64_t after = acknowledgedEpoch_.load(std::memory_order_acquire);
        if (before == after) {
            out[kStatEpoch] = before;
            out[kStatRequestedEpoch] = requestedEpoch_.load(std::memory_order_acquire);
            return;
        }
    }
    out[kStatEpoch] = -1;
    out[kStatRequestedEpoch] = requestedEpoch_.load(std::memory_order_acquire);
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

    bool healthy = submitIdle();
    for (IsoUrb& urb : feedbackUrbs_) healthy = healthy && submitFeedback(urb);
    while (healthy && !stopRequested_.load(std::memory_order_acquire)) {
        if (retirePending()) {
            healthy = retireInFlight();
            continue;
        }
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
                if (urb->feedback) {
                    healthy = reapFeedback(*urb);
                    break;
                }
                account(*urb, false);
                urb->inFlight = false;
                --inFlight_;
                if (stopRequested_.load(std::memory_order_acquire)) break;
                healthy = submitOne(*urb);
                break;
            }
        }
    }

    discardInFlight(urbs_);
    discardInFlight(feedbackUrbs_);
    while (inFlight_ > 0 || feedbackInFlight_ > 0) {
        IsoUrb* urb = nullptr;
        int error = 0;
        switch (transport_.reap(urb, kReapTimeoutMs, error)) {
            case ReapResult::kUrb:
                urb->inFlight = false;
                if (urb->feedback) {
                    --feedbackInFlight_;
                    break;
                }
                account(*urb, true);
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

bool StreamEngine::retirePending() const {
    return requestedEpoch_.load(std::memory_order_acquire) != acknowledgedEpoch_.load(std::memory_order_relaxed);
}

/** Returns false when the stream failed. Nothing is acknowledged before every URB is back. */
bool StreamEngine::retireInFlight() {
    discardInFlight(urbs_);
    while (inFlight_ > 0) {
        IsoUrb* urb = nullptr;
        int error = 0;
        switch (transport_.reap(urb, kReapTimeoutMs, error)) {
            case ReapResult::kUrb:
                if (urb->feedback) {
                    if (!reapFeedback(*urb)) return false;
                    break;
                }
                account(*urb, true);
                urb->inFlight = false;
                --inFlight_;
                break;
            case ReapResult::kNothing:
                break;
            case ReapResult::kDeviceGone:
                fail(kFailureDeviceGone, 0);
                return false;
            case ReapResult::kFailed:
                fail(kFailureReap, error);
                return false;
        }
    }
    {
        Lock lock(epochMutex_);
        acknowledgedEpoch_.store(-1, std::memory_order_relaxed);
        ring_.discardAll();
        for (Stat counter : {kStatMediaFramesWritten, kStatMediaFramesSubmitted, kStatMediaFramesProcessed,
                             kStatMediaFramesDelivered, kStatMediaFramesLost, kStatSilenceFramesInserted,
                             kStatUsbFramesDelivered, kStatUnderruns, kStatUrbsCompleted, kStatPacketsFailed,
                             kStatLastDeliveredMediaFrame}) {
            stats_[counter].store(0, std::memory_order_release);
        }
        mediaStarted_ = false;
        inUnderrun_ = false;
        // Several retirements may have come in while reaping; only the newest output may now write.
        acknowledgedEpoch_.store(requestedEpoch_.load(std::memory_order_relaxed), std::memory_order_release);
    }
    return submitIdle();
}

bool StreamEngine::submitIdle() {
    for (IsoUrb& urb : urbs_) {
        if (!urb.inFlight && !submitOne(urb)) return false;
    }
    return true;
}

/** Returns false when the submit failed. A URB left idle by a retirement or a stop is not a failure. */
bool StreamEngine::submitOne(IsoUrb& urb) {
    Lock lock(epochMutex_);
    if (stopRequested_.load(std::memory_order_acquire) ||
        requestedEpoch_.load(std::memory_order_relaxed) != acknowledgedEpoch_.load(std::memory_order_relaxed)) {
        return true;
    }
    fill(urb);
    const int error = transport_.submit(urb);
    if (error != 0) {
        failSubmit(error);
        return false;
    }
    urb.inFlight = true;
    urb.discarded = false;
    ++inFlight_;
    return true;
}

/** Returns false when the submit failed. Nothing is submitted once a stop has been asked for. */
bool StreamEngine::submitFeedback(IsoUrb& urb) {
    if (stopRequested_.load(std::memory_order_acquire)) return true;
    for (uint32_t i = 0; i < urb.packetCount; ++i) {
        urb.packets[i] = IsoPacket{};
        urb.packets[i].length = static_cast<uint32_t>(config_.feedbackMaxPacketBytes);
    }
    const int error = transport_.submit(urb);
    if (error != 0) {
        failSubmit(error);
        return false;
    }
    urb.inFlight = true;
    urb.discarded = false;
    ++feedbackInFlight_;
    return true;
}

/**
 * Takes the newest value a completed feedback URB carries and sends the URB again. The time without
 * an accepted value counts the endpoint's polls, not the clock, so a stream thread that ran late
 * cannot mistake its own delay for silence from the device. Returns false when the stream failed.
 */
bool StreamEngine::reapFeedback(IsoUrb& urb) {
    urb.inFlight = false;
    --feedbackInFlight_;
    const auto packetBytes = static_cast<size_t>(config_.feedbackMaxPacketBytes);
    for (uint32_t i = 0; i < urb.packetCount; ++i) {
        const IsoPacket& packet = urb.packets[i];
        const bool received = packet.status == 0 && packet.actualLength <= packet.length;
        const FeedbackDecoder::Result result = received
            ? feedback_.decode(urb.data + i * packetBytes, packet.actualLength)
            : FeedbackDecoder::Result::kIgnored;
        if (result == FeedbackDecoder::Result::kAccepted) {
            feedbackQuietUs_ = 0;
            feedbackOutOfRange_ = false;
            schedule_.follow(feedback_.framesQ16(), maxFramesPerPacket_);
            continue;
        }
        if (result == FeedbackDecoder::Result::kOutOfRange) feedbackOutOfRange_ = true;
        feedbackQuietUs_ += config_.feedbackIntervalUs;
    }
    if (feedbackQuietUs_ > config_.feedbackTimeoutUs) {
        fail(feedbackOutOfRange_ ? kFailureFeedbackOutOfRange : kFailureFeedbackTimeout, 0);
        return false;
    }
    return submitFeedback(urb);
}

// A discarded URB still comes back through reap, and until it does the kernel owns its buffers.
void StreamEngine::discardInFlight(std::vector<IsoUrb>& urbs) {
    for (IsoUrb& urb : urbs) {
        if (urb.inFlight && !urb.discarded) {
            transport_.discard(urb);
            urb.discarded = true;
        }
    }
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
        const auto count = static_cast<int64_t>(media);
        packet.mediaEndFrame = stats_[kStatMediaFramesSubmitted].fetch_add(count, std::memory_order_release) + count;
        add(kStatSilenceFramesInserted, static_cast<int64_t>(frames - media));
        if (media == frames) {
            inUnderrun_ = false;
        } else if (running && mediaStarted_ && !endOfStream_.load(std::memory_order_acquire) && !inUnderrun_) {
            inUnderrun_ = true;
            add(kStatUnderruns, 1);
            add(kStatTotalUnderruns, 1);
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
            // URBs can complete out of order. A silence-only packet proves nothing about media.
            auto& last = stats_[kStatLastDeliveredMediaFrame];
            if (packet.mediaFrames > 0 && packet.mediaEndFrame > last.load(std::memory_order_relaxed)) {
                last.store(packet.mediaEndFrame, std::memory_order_release);
            }
            add(kStatTotalMediaFramesDelivered, packet.mediaFrames);
            add(kStatMediaFramesDelivered, packet.mediaFrames);
            add(kStatUsbFramesDelivered, packet.length / frameBytes);
        } else if (!cancelling) {
            add(kStatMediaFramesProcessed, packet.mediaFrames);
            add(kStatMediaFramesLost, packet.mediaFrames);
            add(kStatTotalMediaFramesLost, packet.mediaFrames);
            add(kStatPacketsFailed, 1);
            add(kStatTotalPacketsFailed, 1);
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
