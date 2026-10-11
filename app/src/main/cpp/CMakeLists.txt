cmake_minimum_required(VERSION 3.22.1)

project(vulkan_probe LANGUAGES CXX)

add_library(vulkan_probe SHARED
    vulkan_probe.cpp
)

find_library(log-lib log)
find_library(vulkan-lib vulkan)
find_library(android-lib android)

target_link_libraries(vulkan_probe PRIVATE
    ${log-lib}
    ${vulkan-lib}
    ${android-lib}
)

target_compile_features(vulkan_probe PRIVATE cxx_std_17)
