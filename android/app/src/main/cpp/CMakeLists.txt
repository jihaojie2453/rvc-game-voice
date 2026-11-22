cmake_minimum_required(VERSION 3.22)
project(vsdecoder C CXX)

# ncnn's prebuilt Android libraries with Vulkan (static), downloaded once at configure time and checked by hash
include(FetchContent)
FetchContent_Declare(ncnn_prebuilt
    URL https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android-vulkan.zip
    URL_HASH SHA256=26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804)
FetchContent_GetProperties(ncnn_prebuilt)
if(NOT ncnn_prebuilt_POPULATED)
    FetchContent_Populate(ncnn_prebuilt)
endif()
set(ncnn_DIR ${ncnn_prebuilt_SOURCE_DIR}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(vsdecoder SHARED ncnn_decoder.cpp)
target_link_libraries(vsdecoder ncnn android log)

# ---------------------------------------------------------------------------
# RNNoise denoiser.
#
# The Gradle build does NOT run this file (there is no externalNativeBuild): both .so files ship
# prebuilt in src/main/jniLibs. This target is here so the library can be produced with the NDK when
# someone wires native code into the build; day to day, rebuild it with build-rnnoise-so.ps1, which
# also downloads nothing and does not need ncnn.
#
# rnnoise_data.c is generated (78 MB of float weights) and is not checked in; get it from
# https://media.xiph.org/rnnoise/models/rnnoise_data-<model_version>.tar.gz and set RNNOISE_DATA to
# the directory holding rnnoise_data.c/.h.
# ---------------------------------------------------------------------------
option(BUILD_RNNOISE "Build librnnoise.so from src/main/cpp/rnnoise" OFF)
if(BUILD_RNNOISE)
    if(NOT DEFINED RNNOISE_DATA)
        message(FATAL_ERROR "Set RNNOISE_DATA to the directory holding rnnoise_data.c and rnnoise_data.h")
    endif()
    file(GLOB RNNOISE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/*.c)
    add_library(rnnoise SHARED ${RNNOISE_SOURCES} rnnoise_jni.c ${RNNOISE_DATA}/rnnoise_data.c)
    target_include_directories(rnnoise PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise ${RNNOISE_DATA})
    target_compile_options(rnnoise PRIVATE -O3 -fvisibility=hidden)
    target_link_options(rnnoise PRIVATE -Wl,--gc-sections -Wl,-s)
    target_link_libraries(rnnoise m)
endif()
