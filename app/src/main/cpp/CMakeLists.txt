cmake_minimum_required(VERSION 3.22.1)

project(cameraprobe LANGUAGES C CXX)

add_library(cameraprobe SHARED
        cameraprobe.cpp
        cameraprobe_record.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(media-lib mediandk)

target_link_libraries(cameraprobe
        ${log-lib}
        ${android-lib}
        ${media-lib}
)

