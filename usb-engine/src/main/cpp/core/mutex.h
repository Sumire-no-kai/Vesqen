#pragma once

#include <pthread.h>

namespace vesqen::usb {

/**
 * A plain pthread mutex. std::mutex reports failures by throwing, so using it would link libc++'s
 * exception and string support into the library, more than doubling its size.
 */
class Mutex {
public:
    Mutex() { pthread_mutex_init(&mutex_, nullptr); }
    ~Mutex() { pthread_mutex_destroy(&mutex_); }
    Mutex(const Mutex&) = delete;
    Mutex& operator=(const Mutex&) = delete;

    void lock() { pthread_mutex_lock(&mutex_); }
    void unlock() { pthread_mutex_unlock(&mutex_); }

private:
    pthread_mutex_t mutex_;
};

class Lock {
public:
    explicit Lock(Mutex& mutex) : mutex_(mutex) { mutex_.lock(); }
    ~Lock() { mutex_.unlock(); }
    Lock(const Lock&) = delete;
    Lock& operator=(const Lock&) = delete;

private:
    Mutex& mutex_;
};

}  // namespace vesqen::usb
