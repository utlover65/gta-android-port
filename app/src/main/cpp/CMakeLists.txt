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
