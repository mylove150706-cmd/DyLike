cmake_minimum_required(VERSION 3.18)
project("ncnn-sr")

# NCNN 预编译库路径（相对于 cpp/ 目录）
set(NCNN_DIR "${CMAKE_SOURCE_DIR}/../jni/ncnn/${ANDROID_ABI}")

# NCNN 头文件
include_directories(${NCNN_DIR}/include/ncnn)

# NCNN 静态库
add_library(libncnn STATIC IMPORTED)
set_target_properties(libncnn PROPERTIES
    IMPORTED_LOCATION
    ${NCNN_DIR}/lib/libncnn.a
)

# 我们的 JNI 库
add_library(ncnn-sr SHARED
    ncnn_sr_jni.cpp
)

# 链接
find_library(log-lib log)
find_library(android-lib android)

# Vulkan（NCNN Vulkan 后端需要）
find_library(vulkan-lib vulkan)

target_link_libraries(ncnn-sr
    libncnn
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)

# C++ 标准
set_target_properties(ncnn-sr PROPERTIES CXX_STANDARD 14)
