#pragma once

#include "core/stream_engine.h"

namespace vesqen::usb {

/**
 * Isochronous transfers over usbdevfs, which Android's Java USB API does not offer. The file
 * descriptor belongs to UsbDeviceConnection, so the engine has to be gone before it closes.
 */
class UsbfsTransport final : public IsoTransport {
public:
    /** [feedbackEndpoint] is 0 for an endpoint paced by the nominal rate. */
    UsbfsTransport(int fd, unsigned char endpoint, unsigned char feedbackEndpoint)
        : fd_(fd), endpoint_(endpoint), feedbackEndpoint_(feedbackEndpoint) {}

    size_t stateBytes(uint32_t packetCount) const override;
    int submit(IsoUrb& urb) override;
    void discard(IsoUrb& urb) override;
    ReapResult reap(IsoUrb*& urb, int timeoutMs, int& error) override;
    StreamThreadReport prepareStreamThread() override;

    /** The kernel's USB_SPEED_ value, or -1. It needs no claimed interface. */
    static int busSpeed(int fd);

private:
    const int fd_;
    const unsigned char endpoint_;
    const unsigned char feedbackEndpoint_;
};

}  // namespace vesqen::usb
