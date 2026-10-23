cmake_minimum_required(VERSION 3.22)
project(portal_camera_provider C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB_RECURSE AIDL_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/aidl/gen/*.cpp)
add_executable(portal_camera_provider
    src/main.cpp
    src/provider.cpp
    src/frame_reader.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../camera-hal/src/jpeg_encode.c
    ${AIDL_SRCS})
target_include_directories(portal_camera_provider PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/aidl/head
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../camera-hal/src)
target_compile_options(portal_camera_provider PRIVATE -fno-sanitize=shadow-call-stack)
target_link_libraries(portal_camera_provider log dl binder_ndk)
