cmake_minimum_required(VERSION 3.22.1)

project(astra_renderer LANGUAGES CXX)

find_library(log-lib log)
find_library(vulkan-lib vulkan)
find_library(android-lib android)

add_library(vulkan_probe SHARED
    vulkan_probe.cpp
)
target_compile_features(vulkan_probe PRIVATE cxx_std_17)
target_link_libraries(vulkan_probe PRIVATE
    ${log-lib}
    ${vulkan-lib}
    ${android-lib}
)

set(WEBGPU_DAWN_SO
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwebgpu_dawn.so"
)
if(NOT EXISTS "${WEBGPU_DAWN_SO}")
    message(FATAL_ERROR
        "Missing ${WEBGPU_DAWN_SO}. The Dawn Android library must be built "
        "and copied into app/src/main/jniLibs/${ANDROID_ABI} before Gradle runs."
    )
endif()

add_library(webgpu_dawn SHARED IMPORTED GLOBAL)
set_target_properties(webgpu_dawn PROPERTIES
    IMPORTED_LOCATION "${WEBGPU_DAWN_SO}"
)

add_library(dawn_probe SHARED
    dawn_probe.cpp
)
target_compile_features(dawn_probe PRIVATE cxx_std_17)
target_include_directories(dawn_probe PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_link_libraries(dawn_probe PRIVATE
    webgpu_dawn
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)

# JNI tests for native Android host services used by a future engine integration.
add_library(native_host_test SHARED native_host_test.cpp)
target_compile_features(native_host_test PRIVATE cxx_std_17)
target_link_libraries(native_host_test PRIVATE ${log-lib} ${android-lib})

# Opt-in native game-engine experiment. The normal Vulkan/Dawn probe does not
# require Wasmtime; the GitHub Actions native_engine build stages the pinned
# Android ARM64 Wasmtime shared library and matching headers before configuring CMake.
option(BUILD_NATIVE_GTA_ENGINE "Build the native Wasmtime game engine host" OFF)
if(BUILD_NATIVE_GTA_ENGINE)
    set(WASMTIME_SO "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwasmtime.so")
    set(WASMTIME_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/wasmtime-include")
    if(NOT EXISTS "${WASMTIME_SO}")
        message(FATAL_ERROR "Native engine enabled but Wasmtime library is missing: ${WASMTIME_SO}")
    endif()
    if(NOT EXISTS "${WASMTIME_INCLUDE_DIR}/wasmtime.h" OR NOT EXISTS "${WASMTIME_INCLUDE_DIR}/wasmtime/conf.h")
        message(FATAL_ERROR "Native engine enabled but matching Wasmtime C API headers are missing: ${WASMTIME_INCLUDE_DIR}")
    endif()
    add_library(wasmtime_android SHARED IMPORTED GLOBAL)
    set_target_properties(wasmtime_android PROPERTIES IMPORTED_LOCATION "${WASMTIME_SO}")
    add_library(gta_native_engine SHARED native_game_engine.cpp)
    target_compile_features(gta_native_engine PRIVATE cxx_std_17)
    target_include_directories(gta_native_engine PRIVATE "${WASMTIME_INCLUDE_DIR}")
    target_compile_options(gta_native_engine PRIVATE -O0 -g0)
    target_link_libraries(gta_native_engine PRIVATE wasmtime_android ${log-lib} ${android-lib} dl m)
endif()
