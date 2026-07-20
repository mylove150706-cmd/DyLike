cmake_minimum_required(VERSION 3.18)
project("ncnn-sr")

# 只编译有 NCNN 库的 ABI
if(NOT ANDROID_ABI STREQUAL "arm64-v8a" AND NOT ANDROID_ABI STREQUAL "x86_64")
    add_library(ncnn-sr SHARED ncnn_sr_stub.cpp)
    return()
endif()

set(NCNN_DIR "${CMAKE_SOURCE_DIR}/../jni/ncnn/${ANDROID_ABI}")
include_directories(${NCNN_DIR}/include)

# NCNN 核心库（自编译版：NCNN_OPENMP=OFF + NCNN_VULKAN=OFF）
# 不需要 OpenMP、glslang、SPIRV 等依赖
add_library(libncnn STATIC IMPORTED)
set_target_properties(libncnn PROPERTIES IMPORTED_LOCATION ${NCNN_DIR}/lib/libncnn.a)

add_library(ncnn-sr SHARED ncnn_sr_jni.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(ncnn-sr
    libncnn
    ${log-lib}
    ${android-lib}
)

set_target_properties(ncnn-sr PROPERTIES CXX_STANDARD 14)
target_compile_options(ncnn-sr PRIVATE -fexceptions -frtti)
