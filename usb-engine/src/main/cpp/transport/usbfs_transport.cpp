#include "transport/usbfs_transport.h"

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>

#include <cstring>

namespace vesqen::usb {

namespace {

// Android's THREAD_PRIORITY_URGENT_AUDIO. Ordinary apps cannot get SCHED_FIFO, and the plan asks
// M7-2 to report what this actually achieves.
constexpr int kUrgentAudioNice = -19;

int ioctlRetrying(int fd, unsigned long request, void* argument) {
    int result;
    do {
        result = ioctl(fd, request, argument);
    } while (result != 0 && errno == EINTR);
    return result;
}

}  // namespace

size_t UsbfsTransport::stateBytes(uint32_t packetCount) const {
    return sizeof(usbdevfs_urb) + packetCount * sizeof(usbdevfs_iso_packet_desc);
}

int UsbfsTransport::submit(IsoUrb& urb) {
    auto* raw = static_cast<usbdevfs_urb*>(urb.transportState);
    std::memset(raw, 0, stateBytes(urb.packetCount));
    raw->type = USBDEVFS_URB_TYPE_ISO;
    raw->endpoint = urb.feedback ? feedbackEndpoint_ : endpoint_;
    raw->flags = USBDEVFS_URB_ISO_ASAP;
    raw->buffer = urb.data;
    raw->number_of_packets = static_cast<int>(urb.packetCount);
    raw->usercontext = &urb;
    int total = 0;
    for (uint32_t i = 0; i < urb.packetCount; ++i) {
        raw->iso_frame_desc[i].length = urb.packets[i].length;
        total += static_cast<int>(urb.packets[i].length);
    }
    raw->buffer_length = total;
    return ioctlRetrying(fd_, USBDEVFS_SUBMITURB, raw) == 0 ? 0 : errno;
}

void UsbfsTransport::discard(IsoUrb& urb) {
    // EINVAL only means it has already completed, and reap will return it.
    ioctlRetrying(fd_, USBDEVFS_DISCARDURB, urb.transportState);
}

ReapResult UsbfsTransport::reap(IsoUrb*& urb, int timeoutMs, int& error) {
    usbdevfs_urb* raw = nullptr;
    int result = ioctlRetrying(fd_, USBDEVFS_REAPURBNDELAY, &raw);
    if (result != 0 && errno == EAGAIN) {
        // POLLOUT signals a completed URB. POLLHUP arrives on its own when the device goes away.
        pollfd waiting{fd_, POLLOUT, 0};
        const int ready = poll(&waiting, 1, timeoutMs);
        if (ready < 0 && errno != EINTR) {
            error = errno;
            return ReapResult::kFailed;
        }
        if (ready <= 0) return ReapResult::kNothing;
        result = ioctlRetrying(fd_, USBDEVFS_REAPURBNDELAY, &raw);
    }
    if (result != 0) {
        if (errno == EAGAIN) return ReapResult::kNothing;
        if (errno == ENODEV) return ReapResult::kDeviceGone;
        error = errno;
        return ReapResult::kFailed;
    }
    urb = static_cast<IsoUrb*>(raw->usercontext);
    for (uint32_t i = 0; i < urb->packetCount; ++i) {
        urb->packets[i].actualLength = raw->iso_frame_desc[i].actual_length;
        urb->packets[i].status = static_cast<int32_t>(raw->iso_frame_desc[i].status);
    }
    return ReapResult::kUrb;
}

StreamThreadReport UsbfsTransport::prepareStreamThread() {
    pthread_setname_np(pthread_self(), "usb-stream");
    setpriority(PRIO_PROCESS, 0, kUrgentAudioNice);
    return {getpriority(PRIO_PROCESS, 0), sched_getscheduler(0)};
}

int UsbfsTransport::busSpeed(int fd) {
    const int speed = ioctl(fd, USBDEVFS_GET_SPEED);
    return speed < 0 ? -1 : speed;
}

}  // namespace vesqen::usb
