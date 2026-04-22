# CMakeLists.txt for UVCCamera library
# Converted from Android.mk

cmake_minimum_required(VERSION 3.4.1)

# Set project name
project(UVCCamera)

# Set C++ standard if needed (not specified in original, but recommended)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Set compiler flags
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Werror -DANDROID_NDK -DLOG_NDEBUG -DACCESS_RAW_DESCRIPTORS")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Werror -DANDROID_NDK -DLOG_NDEBUG -DACCESS_RAW_DESCRIPTORS")

# Include directories
include_directories(
        UVCCamera/
        UVCCamera/../
        rapidjson/include
        libusb
        libuvc
        libuvc/libuvc
)

# Create shared library
add_library(UVCCamera SHARED
        UVCCamera/_onload.cpp
        UVCCamera/utilbase.cpp
        UVCCamera/UVCCamera.cpp
        UVCCamera/UVCPreview.cpp
        UVCCamera/UVCButtonCallback.cpp
        UVCCamera/UVCStatusCallback.cpp
        UVCCamera/Parameters.cpp
        UVCCamera/serenegiant_usb_UVCCamera.cpp
)

get_filename_component(PARENT_DIR ${CMAKE_SOURCE_DIR} DIRECTORY)
set(JNILIBS_DIR "${PARENT_DIR}/jniLibs")

add_library(jpeg-turbo1500 SHARED IMPORTED)
set_target_properties(jpeg-turbo1500
        PROPERTIES IMPORTED_LOCATION
        ${JNILIBS_DIR}/${ANDROID_ABI}/libjpeg-turbo1500_static.a)

add_library(usb100 SHARED IMPORTED)
set_target_properties(usb100
        PROPERTIES IMPORTED_LOCATION
        ${JNILIBS_DIR}/${ANDROID_ABI}/libusb100_static.a)

add_library(uvc SHARED IMPORTED)
set_target_properties(uvc
        PROPERTIES IMPORTED_LOCATION
        ${JNILIBS_DIR}/${ANDROID_ABI}/libuvc_static.a)

# Link libraries
target_link_libraries(UVCCamera
    android
    log
    jpeg-turbo1500
    usb100
    uvc
    dl
)