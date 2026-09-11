cmake_minimum_required(VERSION 3.22.1)

# 纯 C：注入 system_server 的库越"薄"越好——不引入 libc++/STL，
# 只依赖 libc / libdl / liblog（后者本来就在 system_server 里）。
project(portalsensor C)

add_library(portalsensor SHARED
        portal_sensor.c
        virtual_world.c
        vw_rand.c
        vw_noise.c)

# 兜底防泄漏：源码绝对路径不进 DWARF / .comment。AGP 的 strip 任务一旦解析不到
# strip 工具就会静默退化为原样拷贝，这条映射是那时候的最后一道闸。
# 注意必须写进 target_compile_options：add_compile_options 只对**其后**创建的
# target 生效，写在 add_library 后面等于没写（踩过一次）。
get_filename_component(PORTAL_SRC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)

target_compile_options(portalsensor PRIVATE
        "-ffile-prefix-map=${PORTAL_SRC_ROOT}=/build"
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fvisibility=hidden
        -O2
        -fno-exceptions)

find_library(log-lib log)
target_link_libraries(portalsensor PRIVATE ${log-lib} dl m)
