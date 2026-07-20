cmake_minimum_required(VERSION 3.18)
project("ncnn-sr")

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

# OpenMP（NCNN CPU 后端依赖）
find_package(OpenMP REQUIRED)

# 链接顺序很重要：ncnn 依赖 glslang 系列
target_link_libraries(ncnn-sr
    libncnn
    libSPIRV
    libglslang
    libMachineIndependent
    libGenericCodeGen
    libOSDependent
    libglslang-default-resource-limits
    OpenMP::OpenMP_CXX
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)

set_target_properties(ncnn-sr PROPERTIES CXX_STANDARD 14)
