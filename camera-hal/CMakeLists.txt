cmake_minimum_required(VERSION 3.22.1)
project(portal_camshim C)

# 摄像头 HAL 包装层：注入到 **camera provider 进程**（32 位 vendor 进程）。
#
# 只用 C、不链接 libc++（`-DANDROID_STL=none`）：HAL 进程里多带一个 C++ 运行时
# 既没必要，也容易与 vendor 自己的 libc++ 版本打架。
#
# VVideo（src/vvideo/）是**视频数据的统一层**（形状照 Linux VFS）：
# 命名空间 + 挂载表 + 操作表，后端按 file_system_type 注册。
# 它的数据面只碰 RAM（memfd / 常驻缓冲），事件面用 futex（不轮询）。
set(PORTAL_VVIDEO_SRC
    src/vvideo/vvideo_core.c
    src/vvideo/vvideo_event.c
    src/vvideo/vvfs_mem.c
    src/vvideo/vvfs_gen.c
    src/vvideo/vvfs_shm.c
    src/vvideo/vvfs_xform.c
    src/vvideo/vvfs_ctl.c
    src/vvideo/vvfs_notify.c
    src/vvideo/vvfs_builtin.c)

add_library(camshim SHARED src/camshim.c ${PORTAL_VVIDEO_SRC})
add_library(vcam SHARED src/vcam.c src/gpu_render.c)
target_include_directories(camshim PRIVATE src/vvideo)

target_link_libraries(camshim log dl)
target_link_libraries(vcam log dl EGL GLESv2)

# 入口符号必须导出：hw_get_module 按 "HMI" 查这张表
set_target_properties(camshim PROPERTIES
    C_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
    PREFIX "")
set_target_properties(vcam PROPERTIES
    C_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
    PREFIX "")

# 关闭**影子调用栈（SCS）**：本 .so 会被"套在 vendor 代码上"运行 —— 我们的钩子是从
# 真件 HAL（vendor 编译，**不维护 x18 影子栈**）的入口跳进来的，若我们自己用 SCS，
# 函数返回时会从 x18 指向的垃圾栈里弹返回地址，直接跳到野地址
# （实测 tombstone：`#00 pc 0xf8a0 <unknown>`，`#01 portal_hook_01+72`）。
# x18 在 Android arm64 上是平台寄存器，vendor 代码里它可以是任何东西。
target_compile_options(camshim PRIVATE -fno-sanitize=shadow-call-stack)
target_compile_options(vcam PRIVATE -fno-sanitize=shadow-call-stack)
