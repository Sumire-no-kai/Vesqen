#include <jni.h>

#include <memory>

#include "core/stream_engine.h"
#include "transport/usbfs_transport.h"

using vesqen::usb::StreamConfig;
using vesqen::usb::StreamEngine;
using vesqen::usb::UsbfsTransport;

namespace {

struct Stream {
    Stream(int fd, unsigned char endpoint, const StreamConfig& config) : transport(fd, endpoint), engine(config, transport) {}

    UsbfsTransport transport;
    StreamEngine engine;
};

Stream* fromHandle(jlong handle) { return reinterpret_cast<Stream*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeBusSpeed(JNIEnv*, jclass, jint fd) {
    return UsbfsTransport::busSpeed(fd);
}

JNIEXPORT jlong JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeCreate(
    JNIEnv*, jclass, jint fd, jint endpoint, jint sampleRateHz, jint frameBytes, jint serviceIntervalUs,
    jint maxPacketBytes, jint packetsPerUrb, jint urbCount, jint ringFrames) {
    const StreamConfig config{sampleRateHz, frameBytes, serviceIntervalUs, maxPacketBytes, packetsPerUrb, urbCount, ringFrames};
    // Only an OUT endpoint can be streamed to.
    if (fd < 0 || endpoint <= 0 || endpoint > 0x0F || !vesqen::usb::isValid(config)) return 0;
    return reinterpret_cast<jlong>(new Stream(fd, static_cast<unsigned char>(endpoint), config));
}

JNIEXPORT jboolean JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeStart(JNIEnv*, jclass, jlong handle) {
    return fromHandle(handle)->engine.start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeWrite(
    JNIEnv* env, jclass, jlong handle, jlong epoch, jobject buffer, jint offset, jint size) {
    auto* start = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (start == nullptr || offset < 0 || size < 0 || static_cast<jlong>(offset) + size > capacity) return -1;
    return fromHandle(handle)->engine.write(epoch, start + offset, size);
}

JNIEXPORT void JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeSetRunning(
    JNIEnv*, jclass, jlong handle, jlong epoch, jboolean running) {
    fromHandle(handle)->engine.setRunning(epoch, running == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeMarkEndOfStream(JNIEnv*, jclass, jlong handle, jlong epoch) {
    fromHandle(handle)->engine.markEndOfStream(epoch);
}

JNIEXPORT jlong JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeRetireOutput(JNIEnv*, jclass, jlong handle) {
    return fromHandle(handle)->engine.retireOutput();
}

JNIEXPORT void JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeReadStats(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    if (env->GetArrayLength(out) != vesqen::usb::kStatCount) return;
    int64_t values[vesqen::usb::kStatCount];
    fromHandle(handle)->engine.readStats(values);
    env->SetLongArrayRegion(out, 0, vesqen::usb::kStatCount, reinterpret_cast<const jlong*>(values));
}

JNIEXPORT jboolean JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeStop(JNIEnv*, jclass, jlong handle, jint timeoutMs) {
    return fromHandle(handle)->engine.stop(timeoutMs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

}  // extern "C"
