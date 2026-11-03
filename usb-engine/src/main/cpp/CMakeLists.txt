cmake_minimum_required(VERSION 3.22)
project(vesqen_usb LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_compile_options(-Wall -Wextra -Werror -fvisibility=hidden -fno-exceptions -fno-rtti)

if(NOT ANDROID)
    # For example -DVESQEN_SANITIZE=address,undefined or -DVESQEN_SANITIZE=thread.
    set(VESQEN_SANITIZE "" CACHE STRING "Value for -fsanitize")
    if(VESQEN_SANITIZE)
        add_compile_options(-fsanitize=${VESQEN_SANITIZE} -fno-omit-frame-pointer)
        add_link_options(-fsanitize=${VESQEN_SANITIZE})
    endif()
endif()

# Nothing in core knows about Android, so it also runs on a development machine or in CI.
add_library(vesqen_usb_core STATIC core/stream_engine.cpp)
target_include_directories(vesqen_usb_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

if(ANDROID)
    add_library(vesqen_usb SHARED jni/usb_stream_jni.cpp transport/usbfs_transport.cpp)
    target_link_libraries(vesqen_usb PRIVATE vesqen_usb_core log)
else()
    find_package(Threads REQUIRED)
    target_link_libraries(vesqen_usb_core PUBLIC Threads::Threads)
    enable_testing()
    add_executable(usb_core_test test/core_test.cpp)
    target_link_libraries(usb_core_test PRIVATE vesqen_usb_core)
    add_test(NAME usb_core_test COMMAND usb_core_test)
endif()
