cmake_minimum_required(VERSION 3.22.1)

project("Portal")

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 抹掉构建机绝对路径：clang 的 -ffile-prefix-map 会同时重写 DWARF 的
# DW_AT_name（源文件）与 DW_AT_comp_dir（含 .cxx 构建目录），
# 使 debug 通道（保留符号、不做 strip）也不泄漏构建机的模块根目录这类
# 可被对方读取的模块特征。依赖库（dobby 等预编译产物）不受影响。
get_filename_component(PORTALEX_MODULE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${PORTALEX_MODULE_ROOT}=/build")
# MumuEmulator crash? if enable x86_64

#find_package(lsplant REQUIRED CONFIG)
find_package(dobby REQUIRED CONFIG)

add_library(portal SHARED
        main.cpp
        elf_util.cpp
        sensor_hook.cpp)

target_link_libraries(portal android log)
target_link_libraries(portal dobby::dobby)
#target_link_libraries(${CMAKE_PROJECT_NAME} lsplant::lsplant)
