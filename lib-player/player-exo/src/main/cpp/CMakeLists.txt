cmake_minimum_required(VERSION 3.18)
project("ncnn-sr")

# 只编译有 NCNN 库的 ABI
if(NOT ANDROID_ABI STREQUAL "arm64-v8a" AND NOT ANDROID_ABI STREQUAL "x86_64")
    # 对没有 NCNN 库的 ABI（如 armeabi-v7a），创建空的占位 .so
    # 这样不会阻止其他 ABI 的 APK 构建
    add_library(ncnn-sr SHARED ncnn_sr_stub.cpp)
    return()
endif()

set(NCNN_DIR "${CMAKE_SOURCE_DIR}/../jni/ncnn/${ANDROID_ABI}")

# NCNN 头文件
include_directories(${NCNN_DIR}/include)

# 辅助函数：导入静态库
function(import_static_lib lib_name lib_path)
    add_library(${lib_name} STATIC IMPORTED)
    set_target_properties(${lib_name} PROPERTIES IMPORTED_LOCATION ${lib_path})
endfunction()

# NCNN 核心库
import_static_lib(libncnn ${NCNN_DIR}/lib/libncnn.a)

# glslang 系列（NCNN Vulkan 后端依赖）
import_static_lib(libSPIRV ${NCNN_DIR}/lib/libSPIRV.a)
import_static_lib(libglslang ${NCNN_DIR}/lib/libglslang.a)
import_static_lib(libMachineIndependent ${NCNN_DIR}/lib/libMachineIndependent.a)
import_static_lib(libGenericCodeGen ${NCNN_DIR}/lib/libGenericCodeGen.a)
import_static_lib(libOSDependent ${NCNN_DIR}/lib/libOSDependent.a)
import_static_lib(libglslang-default-resource-limits ${NCNN_DIR}/lib/libglslang-default-resource-limits.a)

# 我们的 JNI 库
add_library(ncnn-sr SHARED ncnn_sr_jni.cpp)

# 系统库
find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

# 不用系统 OpenMP（libomp 在荣耀设备上 SIGSEGV）
# 提供 stub 实现替代 libomp
add_library(omp_stub STATIC omp_stub.cpp)

# 链接顺序：ncnn 依赖 glslang 系列 + omp_stub 替代 libomp
target_link_libraries(ncnn-sr
    libncnn
    libSPIRV
    libglslang
    libMachineIndependent
    libGenericCodeGen
    libOSDependent
    libglslang-default-resource-limits
    omp_stub
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)

set_target_properties(ncnn-sr PROPERTIES CXX_STANDARD 14)
target_compile_options(ncnn-sr PRIVATE -fexceptions -frtti)
