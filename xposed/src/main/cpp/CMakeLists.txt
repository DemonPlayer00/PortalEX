cmake_minimum_required(VERSION 3.22.1)

# 纯 C：注入 system_server 的库越"薄"越好——不引入 libc++/STL，
# 只依赖 libc / libdl / liblog（后者本来就在 system_server 里）。
project(portalsensor C)

add_library(portalsensor SHARED
        portal_sensor.c
        virtual_world.c)

target_compile_options(portalsensor PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fvisibility=hidden
        -O2
        -fno-exceptions)

find_library(log-lib log)
target_link_libraries(portalsensor PRIVATE ${log-lib} dl m)
