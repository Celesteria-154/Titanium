cmake_minimum_required(VERSION 3.24)
project(titanium_native LANGUAGES CXX)

if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "Titanium native requires Windows x86-64")
endif()
set(TITANIUM_NGX_ROOT "" CACHE PATH "Extracted NVIDIA DLSS SDK root")
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
find_package(JNI REQUIRED)

add_library(titanium_native SHARED
    src/AdapterSelector.cpp
    src/D3D11Context.cpp
    src/FrameInteropSet.cpp
    src/FrameNgxEvaluator.cpp
    src/NativeBridge.cpp
    src/WglDxInterop.cpp)
target_include_directories(titanium_native PRIVATE include ${JNI_INCLUDE_DIRS})
target_link_libraries(titanium_native PRIVATE d3d11 dxgi opengl32)
target_compile_definitions(titanium_native PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_compile_options(titanium_native PRIVATE /utf-8)

# SDK未指定時は同じJNIを持つ未対応ビルドを作成し、Java側でBilinearへ退避する。
if(TITANIUM_NGX_ROOT)
    set(NGX_INCLUDE "${TITANIUM_NGX_ROOT}/include")
    set(NGX_LIB "${TITANIUM_NGX_ROOT}/lib/Windows_x86_64/x64/nvsdk_ngx_d.lib")
    foreach(required IN ITEMS "${NGX_INCLUDE}/nvsdk_ngx.h"
            "${NGX_INCLUDE}/nvsdk_ngx_helpers.h" "${NGX_LIB}")
        if(NOT EXISTS "${required}")
            message(FATAL_ERROR "Missing NVIDIA DLSS SDK input: ${required}")
        endif()
    endforeach()
    target_include_directories(titanium_native PRIVATE "${NGX_INCLUDE}")
    target_link_libraries(titanium_native PRIVATE "${NGX_LIB}")
    target_compile_definitions(titanium_native PRIVATE TITANIUM_HAS_NGX=1)
    set_property(TARGET titanium_native PROPERTY MSVC_RUNTIME_LIBRARY
        "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")
endif()
