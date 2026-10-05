cmake_minimum_required(VERSION 3.22.1)

# set(CMAKE_ANDROID_RTTI ON CACHE)

set(VCPKG_ROOT $ENV{VCPKG_ROOT})
# 变量缺失时点名报错，否则工具链路径退化成 "/scripts/..."，配置期的报错看不懂
if ("${VCPKG_ROOT}" STREQUAL "")
    message(FATAL_ERROR "环境变量 VCPKG_ROOT 未设置：依赖与工具链均经 vcpkg 提供")
endif ()
string(REPLACE "\\" "/" VCPKG_ROOT "${VCPKG_ROOT}")
set(CMAKE_TOOLCHAIN_FILE "${VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")

set(ANDROID_NDK_HOME $ENV{ANDROID_NDK_HOME})
# 同上：缺失时先给出点名报错
if ("${ANDROID_NDK_HOME}" STREQUAL "")
    message(FATAL_ERROR "环境变量 ANDROID_NDK_HOME 未设置：vcpkg 链加载的 Android 工具链文件在其下")
endif ()
string(REPLACE "\\" "/" ANDROID_NDK_HOME "${ANDROID_NDK_HOME}")
set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")

if (ANDROID_ABI STREQUAL "x86")
    set(VCPKG_TARGET_TRIPLET "x86-android")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_TARGET_TRIPLET "x64-android")
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(VCPKG_TARGET_TRIPLET "arm-android")
elseif (ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_TARGET_TRIPLET "arm64-android")
endif ()

set(CMAKE_BUILD_TYPE RelWithDebInfo)

project("usbipdcpp_native")

set(CMAKE_CXX_STANDARD 23)

# find_package(usbipdcpp CONFIG REQUIRED COMPONENTS libusb)

# libusb 版本要求 >= 1.0.23：JNI 层用 libusb_wrap_sys_device 取 busid 后 close
# 临时 handle，依赖该版本起的 fd_keep 语义才不会连带关掉 UsbDeviceConnection 的 fd
set(USBIPDCPP_BUILD_LIBUSB_COMPONENTS ON)
set(USBIPDCPP_BUILD_SHARED_LIBS OFF)
# 本应用只做 USB 设备转发，不使用虚拟设备组件（那是给 PC 端模拟设备用的），
# 关掉免得它的 target 和源码跟着参与配置
set(USBIPDCPP_BUILD_VIRTUAL_DEVICE OFF)
add_subdirectory(usbipdcpp)

add_library(usbipdcpp_native SHARED
    usbipd_jni.cpp
    jni_log_callback.cpp
)

target_link_libraries(usbipdcpp_native
    usbipdcpp
    usbipdcpp_libusb
    android
    log
)
