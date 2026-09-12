cmake_minimum_required(VERSION 3.22.1)
project(portal_camshim C)

# 摄像头 HAL 包装层：注入到 **camera provider 进程**（32 位 vendor 进程）。
#
# 只用 C、不链接 libc++（`-DANDROID_STL=none`）：HAL 进程里多带一个 C++ 运行时
# 既没必要，也容易与 vendor 自己的 libc++ 版本打架。
add_library(camshim SHARED src/camshim.c)

target_link_libraries(camshim log dl)

# 入口符号必须导出：hw_get_module 按 "HMI" 查这张表
set_target_properties(camshim PROPERTIES
    C_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
    PREFIX "")
