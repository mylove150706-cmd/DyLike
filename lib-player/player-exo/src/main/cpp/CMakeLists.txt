cmake_minimum_required(VERSION 3.18)
project("ncnn-sr")

# 只编译有 NCNN 库的 ABI
if(NOT ANDROID_ABI STREQUAL "arm64-v8a" AND NOT ANDROID_ABI STREQUAL "x86_64")
    add_library(ncnn-sr SHARED ncnn_sr_stub.cpp)
    return()
endif()

set(NCNN_DIR "${CMAKE_SOURCE_DIR}/../jni/ncnn/${ANDROID_ABI}")
include_directories(${NCNN_DIR}/include)

function(import_static_lib lib_name lib_path)
    add_library(${lib_name} STATIC IMPORTED)
    set_target_properties(${lib_name} PROPERTIES IMPORTED_LOCATION ${lib_path})
endfunction()

import_static_lib(libncnn ${NCNN_DIR}/lib/libncnn.a)
import_static_lib(libSPIRV ${NCNN_DIR}/lib/libSPIRV.a)
import_static_lib(libglslang ${NCNN_DIR}/lib/libglslang.a)
import_static_lib(libMachineIndependent ${NCNN_DIR}/lib/libMachineIndependent.a)
import_static_lib(libGenericCodeGen ${NCNN_DIR}/lib/libGenericCodeGen.a)
import_static_lib(libOSDependent ${NCNN_DIR}/lib/libOSDependent.a)
import_static_lib(libglslang-default-resource-limits ${NCNN_DIR}/lib/libglslang-default-resource-limits.a)

# 静态链接 OpenMP runtime（避免 libomp.so 运行时冲突/崩溃）
set(CLANG_LIB_DIR "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/lib/clang/19/lib/linux")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    import_static_lib(libomp_static ${CLANG_LIB_DIR}/aarch64/libomp.a)
elseif(ANDROID_ABI STREQUAL "x86_64")
    import_static_lib(libomp_static ${CLANG_LIB_DIR}/x86_64/libomp.a)
endif()

add_library(ncnn-sr SHARED ncnn_sr_jni.cpp)

# 用 -fopenmp 编译（生成 OpenMP 调用），但静态链接 libomp.a
target_compile_options(ncnn-sr PRIVATE -fopenmp)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_link_libraries(ncnn-sr
    libncnn
    libSPIRV
    libglslang
    libMachineIndependent
    libGenericCodeGen
    libOSDependent
    libglslang-default-resource-limits
    libomp_static
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)

# 防止链接器生成对 libomp.so 的动态依赖
target_link_options(ncnn-sr PRIVATE -Wl,-Bstatic -lomp -Wl,-Bdynamic)

set_target_properties(ncnn-sr PROPERTIES CXX_STANDARD 14)
target_compile_options(ncnn-sr PRIVATE -fexceptions -frtti)
