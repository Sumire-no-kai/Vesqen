// The stream core against a fake transport. No framework: a failed check prints its line and the
// process exits non-zero. Run it as CMakeLists.txt describes.

#include <errno.h>
#include <time.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

#include "core/packet_schedule.h"
#include "core/ring_buffer.h"
#include "core/stream_engine.h"

using namespace vesqen::usb;

namespace {

int failures = 0;

#define CHECK(condition)                                                     \
    do {                                                                     \
        if (!(condition)) {                                                  \
            std::printf("%s:%d: %s\n", __FILE__, __LINE__, #condition);      \
            ++failures;                                                      \
        }                                                                    \
    } while (false)

#define CHECK_EQ(actual, expected)                                                                     \
    do {                                                                                               \
        const auto actualValue = static_cast<long long>(actual);                                       \
        const auto expectedValue = static_cast<long long>(expected);                                   \
        if (actualValue != expectedValue) {                                                            \
            std::printf("%s:%d: %s is %lld, expected %lld\n", __FILE__, __LINE__, #actual, actualValue, expectedValue); \
            ++failures;                                                                                \
        }                                                                                              \
    } while (false)

void sleepMs(int milliseconds) {
    timespec duration{milliseconds / 1000, (milliseconds % 1000) * 1'000'000L};
    nanosleep(&duration, nullptr);
}

template <typename Condition>
bool waitUntil(Condition condition, int timeoutMs = 5000) {
    for (int waited = 0; waited < timeoutMs; ++waited) {
        if (condition()) return true;
        sleepMs(1);
    }
    return condition();
}

struct CapturedPacket {
    std::vector<uint8_t> bytes;
    uint32_t mediaFrames;
};

/** Completes URBs in order, as fast as asked. It keeps what each packet carried when submitted. */
class FakeTransport final : public IsoTransport {
public:
    size_t stateBytes(uint32_t) const override { return 1; }

    int submit(IsoUrb& urb) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (submitError != 0) return submitError;
        if (submitsLeftBeforeError == 0) {
            submitsLeftBeforeError = -1;
            return ENOSPC;
        }
        if (submitsLeftBeforeError > 0) --submitsLeftBeforeError;
        const uint8_t* data = urb.data;
        for (uint32_t i = 0; i < urb.packetCount; ++i) {
            captured.push_back({std::vector<uint8_t>(data, data + urb.packets[i].length), urb.packets[i].mediaFrames});
            data += urb.packets[i].length;
        }
        queue_.push_back(&urb);
        ++submitted;
        return 0;
    }

    void discard(IsoUrb& urb) override {
        std::lock_guard<std::mutex> lock(mutex_);
        for (IsoUrb* pending : cancelled_) {
            if (pending == &urb) ++doubleDiscards;
        }
        ++discards;
        if (!ignoreDiscard) cancelled_.push_back(&urb);
    }

    ReapResult reap(IsoUrb*& urb, int timeoutMs, int&) override {
        std::unique_lock<std::mutex> lock(mutex_);
        // The device went away while cancelled URBs were still the kernel's.
        if (deviceGoneWhileCancelled && !cancelled_.empty()) {
            deviceGoneWhileCancelled = false;
            return ReapResult::kDeviceGone;
        }
        // A held cancellation keeps the URB in the kernel, as a slow controller would.
        if (holdCancelled && !cancelled_.empty()) {
            lock.unlock();
            sleepMs(1);
            return ReapResult::kNothing;
        }
        if (!cancelled_.empty()) {
            urb = cancelled_.front();
            cancelled_.pop_front();
            for (size_t i = 0; i < queue_.size(); ++i) {
                if (queue_[i] == urb) {
                    queue_.erase(queue_.begin() + static_cast<long>(i));
                    break;
                }
            }
            for (uint32_t i = 0; i < urb->packetCount; ++i) {
                urb->packets[i].status = -ENOENT;
                urb->packets[i].actualLength = 0;
            }
            return ReapResult::kUrb;
        }
        if (deviceGone && (queue_.empty() || deviceGoneAtOnce)) return ReapResult::kDeviceGone;
        if (hold || queue_.empty()) {
            lock.unlock();
            sleepMs(hold ? 1 : timeoutMs > 1 ? 1 : timeoutMs);
            return ReapResult::kNothing;
        }
        urb = queue_.front();
        queue_.pop_front();
        for (uint32_t i = 0; i < urb->packetCount; ++i) {
            urb->packets[i].status = 0;
            urb->packets[i].actualLength = urb->packets[i].length;
            if (deviceGone) {
                urb->packets[i].status = -ESHUTDOWN;
                urb->packets[i].actualLength = 0;
            }
            if (static_cast<long>(failNextPacket) == 0) urb->packets[i].status = -EPROTO;
            if (static_cast<long>(shortNextPacket) == 0) urb->packets[i].actualLength = urb->packets[i].length / 2;
            --failNextPacket;
            --shortNextPacket;
        }
        lock.unlock();
        if (paceMs > 0) sleepMs(paceMs);
        insideReap = true;
        while (holdInsideReap) sleepMs(1);
        return ReapResult::kUrb;
    }

    std::vector<CapturedPacket> snapshot() {
        std::lock_guard<std::mutex> lock(mutex_);
        return captured;
    }

    std::vector<CapturedPacket> captured;
    std::atomic<int> submitted{0};
    std::atomic<int> discards{0};
    int submitsLeftBeforeError = -1;
    int paceMs = 0;
    std::atomic<int> submitError{0};
    std::atomic<bool> hold{false};
    std::atomic<bool> ignoreDiscard{false};
    std::atomic<bool> holdCancelled{false};
    std::atomic<bool> deviceGoneWhileCancelled{false};
    std::atomic<int> doubleDiscards{0};
    std::atomic<bool> deviceGone{false};
    std::atomic<bool> deviceGoneAtOnce{false};
    std::atomic<bool> holdInsideReap{false};
    std::atomic<bool> insideReap{false};
    long failNextPacket = -1;
    long shortNextPacket = -1;

private:
    std::mutex mutex_;
    std::deque<IsoUrb*> queue_;
    std::deque<IsoUrb*> cancelled_;
};

StreamConfig config44k() { return {44'100, 4, 1000, 192, 8, 3, 4410}; }

int64_t stat(const StreamEngine& engine, Stat which) {
    int64_t values[kStatCount];
    engine.readStats(values);
    return values[which];
}

std::vector<uint8_t> pattern(size_t frames, size_t frameBytes) {
    std::vector<uint8_t> bytes(frames * frameBytes);
    for (size_t i = 0; i < bytes.size(); ++i) bytes[i] = static_cast<uint8_t>(((i * 31) ^ (i >> 8)) + 1);
    return bytes;
}

void writeAll(StreamEngine& engine, const std::vector<uint8_t>& bytes, int64_t epoch = 0) {
    size_t offset = 0;
    int guard = 0;
    while (offset < bytes.size() && guard < 20'000) {
        const int32_t chunk = static_cast<int32_t>(bytes.size() - offset < 4096 ? bytes.size() - offset : 4096);
        const int32_t accepted = engine.write(epoch, bytes.data() + offset, chunk);
        CHECK(accepted >= 0);
        if (accepted <= 0) {
            sleepMs(1);
            ++guard;
        } else {
            offset += static_cast<size_t>(accepted);
        }
    }
    CHECK_EQ(offset, bytes.size());
}

void testRingBufferWrapsWithoutLosingFrames() {
    FrameRingBuffer ring(4, 2);
    const uint8_t input[12] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    uint8_t output[12] = {};
    CHECK_EQ(ring.read(output, 1), 0);
    CHECK_EQ(ring.write(input, 3), 3);
    CHECK_EQ(ring.read(output, 2), 2);
    CHECK(std::memcmp(output, input, 4) == 0);
    // Crosses the end of the storage, and the two that do not fit are refused.
    CHECK_EQ(ring.write(input + 6, 5), 3);
    CHECK_EQ(ring.write(input, 1), 0);
    CHECK_EQ(ring.read(output, 6), 4);
    const uint8_t expected[8] = {5, 6, 7, 8, 9, 10, 11, 12};
    CHECK(std::memcmp(output, expected, 8) == 0);
    CHECK_EQ(ring.read(output, 1), 0);
}

void testPacketScheduleKeepsTheRemainder() {
    PacketSchedule full48(48'000, 1000);
    for (int i = 0; i < 100; ++i) CHECK_EQ(full48.nextFrames(), 48);

    PacketSchedule full44(44'100, 1000);
    int total = 0;
    for (int i = 0; i < 1000; ++i) {
        const int frames = full44.nextFrames();
        CHECK(frames == 44 || frames == 45);
        total += frames;
    }
    CHECK_EQ(total, 44'100);

    PacketSchedule high48(48'000, 125);
    for (int i = 0; i < 100; ++i) CHECK_EQ(high48.nextFrames(), 6);

    PacketSchedule high44(44'100, 125);
    total = 0;
    for (int i = 0; i < 8000; ++i) total += high44.nextFrames();
    CHECK_EQ(total, 44'100);

    CHECK_EQ(PacketSchedule::maxFrames(48'000, 1000), 48);
    CHECK_EQ(PacketSchedule::maxFrames(44'100, 1000), 45);
    CHECK_EQ(PacketSchedule::maxFrames(44'100, 125), 6);
}

void testConfigurationIsCheckedAgain() {
    CHECK(isValid(config44k()));
    CHECK(isValid({48'000, 6, 1000, 288, 32, 3, 24'000}));
    // 180 bytes is the largest packet here, so the boundary is exact.
    CHECK(isValid({44'100, 4, 1000, 180, 8, 3, 4410}));
    CHECK(!isValid({44'100, 4, 1000, 179, 8, 3, 4410}));
    CHECK(!isValid({0, 4, 1000, 192, 8, 3, 4410}));
    CHECK(!isValid({768'001, 4, 125, 1024, 8, 3, 4410}));
    CHECK(!isValid({44'100, 0, 1000, 192, 8, 3, 4410}));
    CHECK(!isValid({44'100, 9, 1000, 1024, 8, 3, 4410}));
    CHECK(!isValid({44'100, 4, 0, 192, 8, 3, 4410}));
    CHECK(isValid({1'000, 4, 64'000, 1024, 8, 3, 4410}));
    CHECK(!isValid({1'000, 4, 64'001, 1024, 8, 3, 4410}));
    CHECK(!isValid({44'100, 4, 1000, 1025, 8, 3, 4410}));
    CHECK(!isValid({44'100, 4, 1000, 192, 0, 3, 4410}));
    CHECK(!isValid({44'100, 4, 1000, 192, 129, 3, 4410}));
    CHECK(isValid({44'100, 4, 1000, 192, 128, 3, 4410}));
    CHECK(!isValid({44'100, 4, 1000, 192, 8, 0, 4410}));
    CHECK(!isValid({44'100, 4, 1000, 192, 8, 33, 4410}));
    CHECK(!isValid({44'100, 4, 1000, 192, 8, 3, 0}));
    CHECK(!isValid({44'100, 4, 1000, 192, 8, 3, (1 << 20) + 1}));
}

/** The core of M7: whatever the packet boundaries, the media bytes reach USB unchanged and in order. */
void testMediaBytesArriveUnchangedAndInOrder() {
    FakeTransport transport;
    const StreamConfig config = config44k();
    StreamEngine engine(config, transport);
    const std::vector<uint8_t> input = pattern(30'000, 4);

    CHECK(engine.start());
    engine.setRunning(0, true);
    writeAll(engine, input);
    engine.markEndOfStream(0);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) == 30'000; }));
    CHECK(engine.stop(2000));

    std::vector<uint8_t> received;
    for (const CapturedPacket& packet : transport.captured) {
        CHECK(packet.bytes.size() == 176 || packet.bytes.size() == 180);
        received.insert(received.end(), packet.bytes.begin(), packet.bytes.begin() + packet.mediaFrames * 4);
        for (size_t i = packet.mediaFrames * 4; i < packet.bytes.size(); ++i) CHECK_EQ(packet.bytes[i], 0);
    }
    CHECK(received == input);
    CHECK_EQ(stat(engine, kStatMediaFramesWritten), 30'000);
    CHECK_EQ(stat(engine, kStatMediaFramesDelivered), 30'000);
    CHECK_EQ(stat(engine, kStatMediaFramesLost), 0);
    CHECK_EQ(stat(engine, kStatPacketsFailed), 0);
    CHECK_EQ(stat(engine, kStatFailure), kFailureNone);
}

void testNothingLeavesTheRingWhileNotRunning() {
    FakeTransport transport;
    transport.paceMs = 1;
    StreamEngine engine(config44k(), transport);
    const std::vector<uint8_t> input = pattern(100, 4);
    CHECK_EQ(engine.write(0, input.data(), 400), 400);
    CHECK(engine.start());
    CHECK(waitUntil([&] { return stat(engine, kStatUrbsCompleted) >= 6; }));

    CHECK_EQ(stat(engine, kStatMediaFramesSubmitted), 0);
    CHECK_EQ(stat(engine, kStatMediaFramesProcessed), 0);
    CHECK_EQ(stat(engine, kStatUnderruns), 0);
    CHECK(stat(engine, kStatSilenceFramesInserted) > 0);
    CHECK(stat(engine, kStatUsbFramesDelivered) > 0);

    // Resuming picks up exactly what was waiting.
    engine.setRunning(0, true);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) == 100; }));
    CHECK(engine.stop(2000));
}

void testUnderrunsCountOncePerGapAndNeverBeforeOrAfterTheMedia() {
    FakeTransport transport;
    transport.paceMs = 1;
    StreamEngine engine(config44k(), transport);
    engine.setRunning(0, true);
    CHECK(engine.start());
    // Running with nothing written yet is the start of a stream, not an underrun.
    CHECK(waitUntil([&] { return stat(engine, kStatUrbsCompleted) >= 6; }));
    CHECK_EQ(stat(engine, kStatUnderruns), 0);

    const std::vector<uint8_t> input = pattern(50, 4);
    CHECK_EQ(engine.write(0, input.data(), 200), 200);
    CHECK(waitUntil([&] { return stat(engine, kStatUnderruns) == 1; }));
    const int64_t completed = stat(engine, kStatUrbsCompleted);
    CHECK(waitUntil([&] { return stat(engine, kStatUrbsCompleted) >= completed + 6; }));
    CHECK_EQ(stat(engine, kStatUnderruns), 1);

    // A full packet ends the gap, so the next shortfall is a second one.
    const std::vector<uint8_t> more = pattern(200, 4);
    CHECK_EQ(engine.write(0, more.data(), 800), 800);
    CHECK(waitUntil([&] { return stat(engine, kStatUnderruns) == 2; }));

    engine.markEndOfStream(0);
    const std::vector<uint8_t> last = pattern(100, 4);
    CHECK_EQ(engine.write(0, last.data(), 400), 400);
    const int64_t before = stat(engine, kStatUrbsCompleted);
    CHECK(waitUntil([&] { return stat(engine, kStatUrbsCompleted) >= before + 6; }));
    CHECK_EQ(stat(engine, kStatUnderruns), 2);
    CHECK(engine.stop(2000));
}

void testPauseIsNotAnUnderrun() {
    FakeTransport transport;
    transport.paceMs = 1;
    StreamEngine engine(config44k(), transport);
    engine.setRunning(0, true);
    CHECK(engine.start());
    const std::vector<uint8_t> input = pattern(200, 4);
    CHECK_EQ(engine.write(0, input.data(), 800), 800);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) == 200; }));
    const int64_t underruns = stat(engine, kStatUnderruns);

    engine.setRunning(0, false);
    const int64_t completed = stat(engine, kStatUrbsCompleted);
    CHECK(waitUntil([&] { return stat(engine, kStatUrbsCompleted) >= completed + 6; }));
    CHECK_EQ(stat(engine, kStatUnderruns), underruns);
    CHECK(engine.stop(2000));
}

/** Audio is still waiting here, so a mistaken count would show even at the very first silent packet. */
void testPausingWithMediaInTheRingIsNotAnUnderrun() {
    FakeTransport transport;
    transport.paceMs = 1;
    StreamEngine engine(config44k(), transport);
    const std::vector<uint8_t> input = pattern(4000, 4);
    CHECK_EQ(engine.write(0, input.data(), 16'000), 16'000);
    engine.setRunning(0, true);
    CHECK(engine.start());
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) >= 400; }));

    engine.setRunning(0, false);
    const int64_t completed = stat(engine, kStatUrbsCompleted);
    CHECK(waitUntil([&] { return stat(engine, kStatUrbsCompleted) >= completed + 6; }));
    CHECK(stat(engine, kStatMediaFramesSubmitted) < 4000);
    CHECK_EQ(stat(engine, kStatUnderruns), 0);
    CHECK(engine.stop(2000));
}

void testFailedAndShortPacketsAreLostButStillProcessed() {
    FakeTransport transport;
    transport.failNextPacket = 2;
    transport.shortNextPacket = 5;
    StreamEngine engine(config44k(), transport);
    const std::vector<uint8_t> input = pattern(4000, 4);
    CHECK_EQ(engine.write(0, input.data(), static_cast<int32_t>(input.size())), static_cast<int32_t>(input.size()));
    engine.setRunning(0, true);
    engine.markEndOfStream(0);
    CHECK(engine.start());
    // Lost frames must not leave the end of the stream waiting.
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) == 4000; }));
    CHECK(engine.stop(2000));

    CHECK_EQ(stat(engine, kStatPacketsFailed), 2);
    const int64_t lost = stat(engine, kStatMediaFramesLost);
    CHECK(lost > 0);
    CHECK_EQ(stat(engine, kStatMediaFramesDelivered) + lost, 4000);
    // A lost packet is not a stream failure by itself.
    CHECK_EQ(stat(engine, kStatFailure), kFailureNone);
}

void testStoppingReclaimsWhatIsInFlight() {
    FakeTransport transport;
    transport.hold = true;
    StreamEngine engine(config44k(), transport);
    const std::vector<uint8_t> input = pattern(500, 4);
    CHECK_EQ(engine.write(0, input.data(), 2000), 2000);
    engine.setRunning(0, true);
    CHECK(engine.start());
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesSubmitted) > 0 && transport.submitted == 3; }));

    CHECK(engine.stop(2000));
    CHECK_EQ(transport.discards, 3);
    CHECK_EQ(transport.submitted, 3);
    // Cancelled frames are neither delivered nor lost, and nothing is reported as a failure.
    CHECK_EQ(stat(engine, kStatMediaFramesProcessed), 0);
    CHECK_EQ(stat(engine, kStatMediaFramesLost), 0);
    CHECK_EQ(stat(engine, kStatPacketsFailed), 0);
    CHECK_EQ(stat(engine, kStatFailure), kFailureNone);
    CHECK_EQ(stat(engine, kStatUrbsCompleted), 3);
    CHECK_EQ(engine.write(0, input.data(), 4), -1);
}

void testAUrbCompletingDuringAStopIsNotSentAgain() {
    FakeTransport transport;
    transport.holdInsideReap = true;
    StreamEngine engine(config44k(), transport);
    CHECK(engine.start());
    CHECK(waitUntil([&] { return transport.insideReap.load(); }));
    CHECK_EQ(transport.submitted, 3);

    // This asks for the stop and gives up waiting while the stream thread is still inside reap.
    CHECK(!engine.stop(10));
    transport.holdInsideReap = false;
    CHECK(engine.stop(2000));
    CHECK_EQ(transport.submitted, 3);
}

void testAStopThatTimesOutKeepsEverythingAliveAndCanFinishLater() {
    FakeTransport transport;
    transport.hold = true;
    transport.ignoreDiscard = true;
    StreamEngine engine(config44k(), transport);
    CHECK(engine.start());
    CHECK(waitUntil([&] { return transport.submitted == 3; }));

    CHECK(!engine.stop(30));

    transport.hold = false;
    CHECK(engine.stop(2000));
    CHECK_EQ(stat(engine, kStatUrbsCompleted), 3);
}

void testARemovedDeviceEndsTheStream() {
    FakeTransport transport;
    transport.paceMs = 1;
    StreamEngine engine(config44k(), transport);
    const std::vector<uint8_t> input = pattern(1000, 4);
    CHECK_EQ(engine.write(0, input.data(), 4000), 4000);
    engine.setRunning(0, true);
    CHECK(engine.start());
    CHECK(waitUntil([&] { return stat(engine, kStatUrbsCompleted) >= 2; }));

    transport.submitError = ENODEV;
    transport.deviceGone = true;
    CHECK(waitUntil([&] { return stat(engine, kStatFailure) == kFailureDeviceGone; }));
    CHECK(engine.stop(2000));
    CHECK_EQ(engine.write(0, input.data(), 4), -1);
}

void testADeviceGoneFromReapIsReportedAndItsUrbsStillComeBack() {
    FakeTransport transport;
    transport.hold = true;
    StreamEngine engine(config44k(), transport);
    CHECK(engine.start());
    CHECK(waitUntil([&] { return transport.submitted == 3; }));

    transport.deviceGoneAtOnce = true;
    transport.deviceGone = true;
    CHECK(waitUntil([&] { return stat(engine, kStatFailure) == kFailureDeviceGone; }));
    const uint8_t frame[4] = {};
    CHECK_EQ(engine.write(0, frame, 4), -1);
    CHECK(engine.stop(2000));
    CHECK_EQ(transport.discards, 3);
    CHECK_EQ(stat(engine, kStatUrbsCompleted), 3);
}

void testAFailedSubmitEndsTheStreamAndReclaimsTheRest() {
    FakeTransport transport;
    transport.hold = true;
    transport.submitsLeftBeforeError = 2;
    StreamEngine engine(config44k(), transport);
    CHECK(engine.start());
    CHECK(waitUntil([&] { return stat(engine, kStatFailure) == kFailureSubmit; }));
    CHECK_EQ(stat(engine, kStatFailureErrno), ENOSPC);
    CHECK(engine.stop(2000));
    CHECK_EQ(transport.discards, 2);
    CHECK_EQ(stat(engine, kStatUrbsCompleted), 2);
}

void testPartialFramesAreRefused() {
    FakeTransport transport;
    StreamEngine engine(config44k(), transport);
    const std::vector<uint8_t> input = pattern(4, 4);
    CHECK_EQ(engine.write(0, input.data(), 6), -1);
    CHECK_EQ(engine.write(0, input.data(), -4), -1);
    CHECK_EQ(stat(engine, kStatMediaFramesWritten), 0);
    CHECK_EQ(engine.write(0, input.data(), 16), 16);
    CHECK_EQ(stat(engine, kStatMediaFramesWritten), 4);
    CHECK(engine.stop(10));
}

// Each frame carries its own number, little-endian, so order, gaps and repeats show. 0 is silence.
StreamConfig config48kNumbered() { return {48'000, 4, 1000, 192, 8, 3, 24'000}; }

std::vector<uint8_t> numbered(uint32_t first, size_t frames) {
    std::vector<uint8_t> bytes(frames * 4);
    for (size_t i = 0; i < frames; ++i) {
        const uint32_t value = first + static_cast<uint32_t>(i);
        std::memcpy(bytes.data() + i * 4, &value, 4);
    }
    return bytes;
}

/** The numbers of the media frames submitted from packet [from] on, in submission order. */
std::vector<uint32_t> mediaNumbers(const std::vector<CapturedPacket>& packets, size_t from) {
    std::vector<uint32_t> numbers;
    for (size_t p = from; p < packets.size(); ++p) {
        for (size_t offset = 0; offset + 4 <= packets[p].bytes.size(); offset += 4) {
            uint32_t value = 0;
            std::memcpy(&value, packets[p].bytes.data() + offset, 4);
            if (value != 0) numbers.push_back(value);
        }
    }
    return numbers;
}

void testASecondSeekWhileTheFirstIsReclaimedStartsAtItsOwnFirstFrame() {
    FakeTransport transport;
    // Slow enough that the ring still holds old audio and URBs are in flight when the seek comes.
    transport.paceMs = 2;
    StreamEngine engine(config48kNumbered(), transport);
    CHECK(engine.start());
    writeAll(engine, numbered(1, 20'000));
    engine.setRunning(0, true);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) >= 2'000; }));

    // The first seek's URBs stay in the kernel, so its output must wait, and a second seek follows.
    transport.holdCancelled = true;
    const int64_t first = engine.retireOutput();
    const size_t afterRetire = transport.snapshot().size();
    const auto firstSeek = numbered(500'000, 400);
    CHECK_EQ(engine.write(first, firstSeek.data(), 1'600), 0);
    sleepMs(20);
    CHECK_EQ(engine.write(first, firstSeek.data(), 1'600), 0);
    CHECK_EQ(stat(engine, kStatEpoch), 0);
    CHECK_EQ(stat(engine, kStatRequestedEpoch), first);
    const int64_t second = engine.retireOutput();
    CHECK_EQ(second, first + 1);
    CHECK_EQ(engine.write(first, firstSeek.data(), 1'600), -1);
    engine.setRunning(first, true);
    CHECK_EQ(engine.write(second, firstSeek.data(), 1'600), 0);
    CHECK_EQ(transport.snapshot().size(), afterRetire);

    transport.holdCancelled = false;
    CHECK(waitUntil([&] { return stat(engine, kStatEpoch) == second; }));
    // Each output counts from zero, so nothing of the first one shows in the second's progress.
    CHECK_EQ(stat(engine, kStatMediaFramesWritten), 0);
    CHECK_EQ(stat(engine, kStatMediaFramesProcessed), 0);
    CHECK_EQ(stat(engine, kStatUnderruns), 0);
    writeAll(engine, numbered(1'000'000, 20'000), second);
    // The first output was running; the new one waits for its own start.
    sleepMs(20);
    CHECK_EQ(stat(engine, kStatMediaFramesSubmitted), 0);
    engine.markEndOfStream(second);
    engine.setRunning(second, true);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) == 20'000; }));
    CHECK(engine.stop(2000));

    const auto numbers = mediaNumbers(transport.snapshot(), afterRetire);
    CHECK_EQ(numbers.size(), 20'000);
    CHECK_EQ(numbers.empty() ? 0 : numbers.front(), 1'000'000);
    bool inOrder = true;
    for (size_t i = 0; i < numbers.size(); ++i) inOrder = inOrder && numbers[i] == 1'000'000 + i;
    CHECK(inOrder);
    CHECK_EQ(stat(engine, kStatMediaFramesLost), 0);
}

void testARetirementWhoseUrbsNeverComeBackNeverLetsTheNextOutputWrite() {
    FakeTransport transport;
    StreamEngine engine(config48kNumbered(), transport);
    CHECK(engine.start());
    writeAll(engine, numbered(1, 4'000));
    engine.setRunning(0, true);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) >= 500; }));

    transport.holdCancelled = true;
    const int64_t next = engine.retireOutput();
    const size_t afterRetire = transport.snapshot().size();
    const auto audio = numbered(700'000, 400);
    for (int i = 0; i < 20; ++i) {
        CHECK_EQ(engine.write(next, audio.data(), 1'600), 0);
        sleepMs(10);
    }
    engine.setRunning(next, true);
    CHECK_EQ(stat(engine, kStatEpoch), 0);
    CHECK_EQ(transport.snapshot().size(), afterRetire);
    // The URBs are still the kernel's, so nothing may be freed yet.
    CHECK(!engine.stop(50));
    transport.holdCancelled = false;
    CHECK(engine.stop(2000));
    CHECK(mediaNumbers(transport.snapshot(), afterRetire).empty());
}

void testFinalDeliveredPacketsSurviveEpochResetWithoutPolling() {
    FakeTransport transport;
    transport.paceMs = 2;
    transport.ignoreDiscard = true;  // The controller completes already-submitted packets successfully.
    StreamEngine engine(config48kNumbered(), transport);
    CHECK(engine.start());
    writeAll(engine, numbered(1, 20'000));
    engine.setRunning(0, true);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) >= 1'000; }));

    transport.hold = true;
    const int64_t next = engine.retireOutput();
    const auto submitted = stat(engine, kStatMediaFramesSubmitted);
    CHECK(stat(engine, kStatMediaFramesDelivered) < submitted);
    transport.hold = false;
    CHECK(waitUntil([&] { return stat(engine, kStatEpoch) == next; }));
    CHECK_EQ(stat(engine, kStatMediaFramesDelivered), 0);
    CHECK_EQ(stat(engine, kStatTotalMediaFramesDelivered), submitted);
    CHECK(engine.stop(2000));
}

void testANaturalEndPlaysTheTailBeforeTheOutputIsRetired() {
    FakeTransport transport;
    StreamEngine engine(config48kNumbered(), transport);
    CHECK(engine.start());
    writeAll(engine, numbered(1, 3'000));
    engine.markEndOfStream(0);
    engine.setRunning(0, true);
    // Media3 releases an output that reached its end only once nothing is pending.
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) == 3'000; }));
    CHECK_EQ(stat(engine, kStatMediaFramesDelivered), 3'000);
    const int64_t next = engine.retireOutput();
    CHECK(waitUntil([&] { return stat(engine, kStatEpoch) == next; }));

    CHECK_EQ(stat(engine, kStatMediaFramesDelivered), 0);
    CHECK_EQ(stat(engine, kStatTotalMediaFramesDelivered), 3'000);

    // The last output's start and end do not carry over: running before any audio is no underrun,
    // and a gap after this output's audio is one, because it has not ended.
    engine.setRunning(next, true);
    sleepMs(20);
    CHECK_EQ(stat(engine, kStatUnderruns), 0);
    writeAll(engine, numbered(5'000, 1'000), next);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) == 1'000; }));
    CHECK(waitUntil([&] { return stat(engine, kStatUnderruns) == 1; }));
    CHECK(engine.stop(2000));

    CHECK_EQ(stat(engine, kStatTotalMediaFramesDelivered), 4'000);
    CHECK_EQ(stat(engine, kStatTotalUnderruns), 1);
    const auto numbers = mediaNumbers(transport.snapshot(), 0);
    CHECK_EQ(numbers.size(), 4'000);
    bool inOrder = true;
    for (size_t i = 0; i < numbers.size(); ++i) inOrder = inOrder && numbers[i] == (i < 3'000 ? 1 + i : 5'000 + i - 3'000);
    CHECK(inOrder);
}

void testEveryRetirementCancelsWhatIsInFlight() {
    FakeTransport transport;
    transport.paceMs = 3;
    StreamEngine engine(config48kNumbered(), transport);
    CHECK(engine.start());
    for (int round = 0; round < 2; ++round) {
        const int64_t epoch = round == 0 ? 0 : engine.retireOutput();
        CHECK(waitUntil([&] { return stat(engine, kStatEpoch) == epoch; }));
        writeAll(engine, numbered(1 + round * 100'000, 8'000), epoch);
        engine.setRunning(epoch, true);
        CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) >= 1'000; }));
    }
    // Two rounds of URBs have gone out and come back, and this seek must cancel what is in flight again:
    // all three, or two when one was just reaped and waits to be submitted.
    const int before = transport.discards;
    const int64_t last = engine.retireOutput();
    CHECK(waitUntil([&] { return stat(engine, kStatEpoch) == last; }));
    CHECK(transport.discards - before >= 2);
    CHECK(transport.discards - before <= 3);
    CHECK(engine.stop(2000));
    CHECK_EQ(transport.doubleDiscards, 0);
}

void testADeviceRemovedDuringARetirementEndsTheStreamAndEveryUrbStillComesBack() {
    FakeTransport transport;
    transport.paceMs = 2;
    StreamEngine engine(config48kNumbered(), transport);
    CHECK(engine.start());
    writeAll(engine, numbered(1, 8'000));
    engine.setRunning(0, true);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) >= 500; }));

    transport.deviceGoneWhileCancelled = true;
    const int64_t next = engine.retireOutput();
    CHECK(waitUntil([&] { return stat(engine, kStatFailure) == kFailureDeviceGone; }));
    const auto audio = numbered(900'000, 400);
    CHECK_EQ(engine.write(next, audio.data(), 1'600), -1);
    CHECK(engine.stop(2000));
    CHECK_EQ(transport.doubleDiscards, 0);
}

void testStatsNeverMixTwoOutputs() {
    FakeTransport transport;
    transport.paceMs = 1;
    StreamEngine engine(config48kNumbered(), transport);
    CHECK(engine.start());
    std::atomic<bool> done{false};
    std::atomic<int> mixed{0};
    std::thread reader([&] {
        int64_t values[kStatCount];
        while (!done) {
            engine.readStats(values);
            if (values[kStatEpoch] < 0) continue;
            if (values[kStatMediaFramesSubmitted] > values[kStatMediaFramesWritten] ||
                values[kStatMediaFramesProcessed] > values[kStatMediaFramesSubmitted]) {
                ++mixed;
            }
        }
    });
    int64_t epoch = 0;
    for (int round = 0; round < 40; ++round) {
        writeAll(engine, numbered(1 + round * 10'000, 2'000), epoch);
        engine.setRunning(epoch, true);
        waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) >= 400; });
        epoch = engine.retireOutput();
        CHECK(waitUntil([&] { return stat(engine, kStatEpoch) == epoch; }));
    }
    done = true;
    reader.join();
    CHECK(engine.stop(2000));
    CHECK_EQ(mixed, 0);
}

void testARetiredOutputsCommandsChangeNothing() {
    FakeTransport transport;
    StreamEngine engine(config48kNumbered(), transport);
    CHECK(engine.start());
    const int64_t next = engine.retireOutput();
    CHECK(waitUntil([&] { return stat(engine, kStatEpoch) == next; }));
    writeAll(engine, numbered(1, 2'000), next);

    // Running or ending the retired epoch must change nothing for the new one: its audio waits for its
    // own start, and the gap after it still counts as an underrun.
    engine.setRunning(0, true);
    engine.markEndOfStream(0);
    sleepMs(30);
    CHECK_EQ(stat(engine, kStatMediaFramesSubmitted), 0);
    engine.setRunning(next, true);
    CHECK(waitUntil([&] { return stat(engine, kStatMediaFramesProcessed) == 2'000; }));
    CHECK(waitUntil([&] { return stat(engine, kStatUnderruns) == 1; }));
    CHECK(engine.stop(2000));
}

}  // namespace

int main() {
    testRingBufferWrapsWithoutLosingFrames();
    testPacketScheduleKeepsTheRemainder();
    testConfigurationIsCheckedAgain();
    testMediaBytesArriveUnchangedAndInOrder();
    testNothingLeavesTheRingWhileNotRunning();
    testUnderrunsCountOncePerGapAndNeverBeforeOrAfterTheMedia();
    testPauseIsNotAnUnderrun();
    testPausingWithMediaInTheRingIsNotAnUnderrun();
    testFailedAndShortPacketsAreLostButStillProcessed();
    testStoppingReclaimsWhatIsInFlight();
    testAUrbCompletingDuringAStopIsNotSentAgain();
    testAStopThatTimesOutKeepsEverythingAliveAndCanFinishLater();
    testARemovedDeviceEndsTheStream();
    testADeviceGoneFromReapIsReportedAndItsUrbsStillComeBack();
    testAFailedSubmitEndsTheStreamAndReclaimsTheRest();
    testPartialFramesAreRefused();
    testASecondSeekWhileTheFirstIsReclaimedStartsAtItsOwnFirstFrame();
    testARetirementWhoseUrbsNeverComeBackNeverLetsTheNextOutputWrite();
    testFinalDeliveredPacketsSurviveEpochResetWithoutPolling();
    testANaturalEndPlaysTheTailBeforeTheOutputIsRetired();
    testARetiredOutputsCommandsChangeNothing();
    testEveryRetirementCancelsWhatIsInFlight();
    testADeviceRemovedDuringARetirementEndsTheStreamAndEveryUrbStillComesBack();
    testStatsNeverMixTwoOutputs();
    if (failures == 0) std::printf("All checks passed\n");
    return failures == 0 ? 0 : 1;
}
