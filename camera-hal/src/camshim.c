/*
 * PortalEX 摄像头 HAL 包装层（Magisk 模块 portalex-camera-hal 的载荷）。
 *
 * ## 为什么是这一层
 *
 * 实测（MI6 / Android 15 / LineageOS 22.2 / msm8998）：
 *   · `cameraserver`（父进程 init）与 camera provider（父进程 init）都是 **init 起的独立进程**，
 *     不经 zygote ⇒ LSPosed 注入不到；视频流也从不经过 system_server（那里只能做控制面）；
 *   · provider 是 **32 位**，通过 `hw_get_module("camera")` 加载
 *     `/vendor/lib/hw/camera.msm8998.so`（导出 `HMI` 与
 *     `_ZN7qcamera25QCamera3HardwareInterface23process_capture_requestEPK14camera3_deviceP23camera3_capture_request`）。
 *
 * 拦截点选在 **vendor HAL 的 camera3 C API**：本 .so 覆盖同一路径，导出模块入口
 * （`HAL_MODULE_INFO_SYM` → 符号名 `HMI`），转发 `open()`，再把真实设备的
 * `camera3_device_ops` 里 `process_capture_request` 那一格换成我们的函数。
 * 应用一行注入都没有；而且因为拦在 HAL，**所有相机 API（Camera1/Camera2/CameraX/NDK）都覆盖**。
 *
 * ## 四个刻意的取舍（每条都是实测逼出来的，别改回去）
 *
 * 1. **不引 `camera3.h`**（NDK 里没有；手抄结构一旦错位就是"调错函数指针"）。
 *    改为按**导出符号地址**在 ops 表里按值定位 `process_capture_request`，与结构布局无关。
 * 2. **模块入口留 512 字节并整块照抄真实模块**：真实 `HMI` 是 `camera_module_t`
 *    （实测 **176 字节**，含 `get_number_of_cameras` 等回调），照 AOSP 教科书只声明
 *    `hw_module_t`（128 字节）会让 provider 的 `CameraModule::init()` 读到相邻内存当
 *    "相机数量"，随后 `libutils` 的 Vector 容量断言溢出 → SIGABRT → init 每 5 秒重启 provider。
 * 3. **`hw_get_module` 之后必须把 `HMI.common.dso` 指回真实句柄**：否则 vendor 实现拿
 *    `module->dso` 去 dlsym 自己的符号会失败。
 * 4. **改 ops 表后按 `/proc/self/maps` 记下的原始权限逐页还原**：QTI 的 ops 表与 HAL 的
 *    `.bss` 静态量同页且**本来就是可写**的，按"只读"还原会让 HAL 写自己的函数内静态量时
 *    SEGV_ACCERR（tombstone: `translateCapabilityToMetadata` → `__cxa_guard_acquire`）。
 *
 * ## 里程碑
 *
 *  M0（当前）：接管 + 转发 + 计数（`已接管 process_capture_request（ops[N]）`），相机行为不变。
 *
 * ## ACE5（AIDL / ColorOS 16）与 MI6 的差别（2026-09-13 实测，详见 docs/camera-mock.md §22）
 *
 *  · provider 拿到的 `camera3_device_t` 的 `ops` **恒为 NULL**（真件不填、OPPO oemlayer 也不填）；
 *  · 真件里**不存在** `camera3_device_ops` 静态表，运行时全地址空间也搜不到那几个入口地址；
 *  · CamX 的 HAL3 入口是 OPPO 的 oemlayer **直接按函数地址调用**的 ——
 *    所以 ACE5 上唯一可行的接管面是 `hook_vaddrs=` 这套**入口钩子**（默认关，见 g_opt_inline_hook）。
 *  M1：在 [shim_process_capture_request] 里把解码后的视频帧写进
 *      `request->output_buffers[i].buffer`（gralloc 句柄，格式/stride 按 stream 元数据）。
 *
 * ## 纪律
 *
 *  · 这里是 **HAL 进程**：任何崩溃都会让整台设备的相机不可用。失败一律安静降级为纯转发；
 *  · 不链接 libc++（`-DANDROID_STL=none`）；
 *  · 热路径只做计数与限流日志，不做字符串处理。
 */
#define LOG_TAG "PortalCamShim"

/* `dladdr()`：用来推导"原件就在自己旁边"的默认路径（见 default_real_hal_path）。
 * bionic 把它放在 _GNU_SOURCE 之后，所以必须在本文件任何系统头之前定义。 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <pthread.h>
#include <sched.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <dirent.h>
#include <time.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * 原件的副本路径。
 *
 * **为什么放在 vendor 库目录里而不是 /data/adb**：实测 `/data/adb/portalex/**` 的文件被标成
 * `adb_data_file`，`hal_camera_default` 读不了它 —— dlopen 直接报 "library not found"
 * （EACCES 被 linker 报成 not found）。放在同一个 vendor 库目录下、只换个文件名，
 * 由模块的 sepolicy 规则授权 execute 即可（magiskpolicy 的语句格式见 sepolicy.rule）。
 *
 * ⚠️ 这里只是**最后的兜底**。真正的默认值由 [default_real_hal_path] 从"自己所在路径"推导
 * （`<自己的路径>.orig.so`），因为部署约定本来就是"原件备份在 shim 旁边、只换文件名"。
 * 教训（ACE5 / Android 16，docs/camera-mock.md §19）：旧代码把**本行**当默认值用时，
 * 32 位路径在 64 位机上必然不存在 ⇒ dlopen 失败 ⇒ 旧代码留着全零的 `HMI` ⇒ provider 崩。
 */
#define REAL_HAL_PATH "/vendor/lib/hw/camera.msm8998.orig.so"

/**
 * 运行期开关文件（由 Magisk 模块提供，随 `system/vendor/lib/hw/**` 挂进来）。
 *
 * **为什么要有它**：HAL 里的 A/B 实验若只能靠编译期开关，每换一组条件都要重编 + 重推 + 杀进程；
 * 而本模块的 sepolicy 已经允许 `hal_camera_default` 读 `system_file`
 * （见 magisk/portalex-camera-hal/sepolicy.rule），所以模块里**新增**的文件 HAL 读得到。
 * 有了它，换条件只要 `adb push portal.cfg` + `kill provider`，秒级迭代 ——
 * 更重要的是出问题能立刻把开关拨回全 0（纯转发），不必重新部署 .so。
 *
 * 格式：`key=value`，每行一条，`#` 开头为注释。缺省全 0（= 纯 M0 转发）。
 *   dump=1         打印请求/结果的真实布局与流参数（只读，安全）
 *   usage_patch=1  在 configure_streams 里给输出流补 SW 读写用途位（让 buffer 可 CPU 映射）
 *   init_wrap=1    包一层框架回调表（注入的时序前提）
 *   inject=1       往输出 buffer 的 Y 平面写合成图案（需 usage_patch + init_wrap）
 *   freeze=1       不把请求转发给真 HAL（只有我们能自己产出结果时才用，否则应用会卡死）
 *   cam=<id>       只对指定相机 id 生效（空 = 全部）
 */
#define PORTAL_CFG_PATH "/vendor/lib/hw/portal.cfg"

/**
 * 真实模块符号大小（照抄这么多字节）。
 *
 * 实测：MI6 = 176（Android 15 / 32 位），**ACE5 = 376**（Android 16 / 64 位）——
 * 所以它是运行期开关（`real_hmi_bytes=`）而不是常量；这里的值只是老机型兜底。
 * 抄少了不会越界（我们的留白有 536 字节），但会留下**未被真实值覆盖的槽位**，
 * 那些槽位在惰性初值里指向兜底回调（见 portal_hmi_t），不会再是"全零"。
 */
#define REAL_HMI_BYTES 176u

/* ------------------------------------------------------------------ *
 * AOSP 稳定 ABI 的最小声明（`hardware/hardware.h` 的公开布局，带 reserved 兜底）
 * ------------------------------------------------------------------ */

struct hw_module_t;
struct hw_device_t;

struct hw_module_methods_t {
    int (*open)(const struct hw_module_t *module, const char *id, struct hw_device_t **device);
};

struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    struct hw_module_methods_t *methods; /* offset 20（32 位） */
    void *dso;                           /* offset 24 */
    uint32_t reserved[32 - 7];
};

struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t *module;
    uint32_t reserved[12];
    int (*close)(struct hw_device_t *device);
};

/** camera3_device 的公开布局：common + ops + priv（自 Android 5 起未变） */
struct camera3_device {
    struct hw_device_t common;
    const void *ops;
    void *priv;
};

/* ------------------------------------------------------------------ *
 * camera3 / gralloc 的稳定 ABI 声明
 *
 * 同样**不引 AOSP 头**（NDK 里没有 `hardware/camera3.h`、`hardware/gralloc.h`），
 * 只声明 M1 真正要读的字段 —— 这些结构自 Android 5 起形状稳定，且我们只读前几个字段，
 * 尾部怎么长都不影响（按偏移取字段，不做 sizeof 运算）。
 * ------------------------------------------------------------------ */

/** 捕获请求（M0 只透传，故只用不透明指针） */
typedef struct camera3_capture_request camera3_capture_request_t;

/** 输出流：注入时要看它的 format / 尺寸 */
typedef struct camera3_stream {
    int stream_type;
    uint32_t width;
    uint32_t height;
    int format;
    uint32_t usage;
    uint32_t max_buffers;
    void *priv;
} camera3_stream_t;

/** 一个输出 buffer：`buffer` 是 gralloc 句柄（我们要往它里面写像素） */
typedef struct camera3_stream_buffer {
    camera3_stream_t *stream;
    void **buffer; /* buffer_handle_t * */
    int status;
    int acquire_fence;
    int release_fence;
} camera3_stream_buffer_t;

/**
 * 一张完成帧的结果（**注入点**）：HAL 填完 buffer 后通过回调把它交给框架，
 * 我们在这个时刻改写 buffer 内容，应用拿到的就是我们的画面。
 */
typedef struct camera3_capture_result {
    uint32_t frame_number;
    const void *result; /* camera_metadata_t * */
    uint32_t num_output_buffers;
    const camera3_stream_buffer_t *output_buffers;
    const camera3_stream_buffer_t *input_buffer;
    uint32_t partial_result;
} camera3_capture_result_t;

/** 框架给 HAL 的回调表（我们包一层，只截 process_capture_result） */
typedef struct camera3_callback_ops {
    void (*process_capture_result)(const struct camera3_callback_ops *,
                                   const camera3_capture_result_t *);
    void (*notify)(const struct camera3_callback_ops *, const void *);
} camera3_callback_ops_t;

/** `android_ycbcr`：lock_ycbcr 的输出 —— Y/Cb/Cr 三个指针与 stride */
struct portal_android_ycbcr {
    void *y;
    void *cb;
    void *cr;
    size_t ystride;
    size_t cstride;
    size_t chroma_step;
    uint32_t reserved[8];
};

/** legacy gralloc 模块（C API）：只要 lock_ycbcr / unlock */
typedef struct portal_gralloc_module {
    struct hw_module_t common;
    int (*registerBuffer)(const struct portal_gralloc_module *, void *);
    int (*unregisterBuffer)(const struct portal_gralloc_module *, void *);
    int (*lock)(const struct portal_gralloc_module *, void *, int, int, int, int, int, void **);
    int (*unlock)(const struct portal_gralloc_module *, void *);
    int (*perform)(struct portal_gralloc_module *, int, ...);
    int (*lock_ycbcr)(const struct portal_gralloc_module *, void *, int, int, int, int, int,
                      struct portal_android_ycbcr *);
} portal_gralloc_module_t;

/* 像素格式（system/graphics.h） */
#define PORTAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED 0x22
#define PORTAL_PIXEL_FORMAT_YCbCr_420_888 0x23

/* gralloc 用法位：读+写（legacy gralloc.h） */
#define PORTAL_GRALLOC_USAGE_SW_READ_OFTEN 0x00000003
#define PORTAL_GRALLOC_USAGE_SW_WRITE_OFTEN 0x00000030
#define PORTAL_GRALLOC_USAGE_SW_WRITE_MASK 0x000000F0 /* SW_WRITE_NEVER/RARELY/OFTEN 的掩码 */

#define PORTAL_GRALLOC_HARDWARE_MODULE_ID "gralloc"

/* ------------------------------------------------------------------ *
 * `libui` 的 GraphicBufferMapper（Android 15 上唯一可用的锁内存路径）
 *
 * 为什么不用 legacy gralloc：实测本机 `hw_get_module("gralloc")` 拿到的是
 * "Graphics Memory Module"（mapper 时代的 stub，`lock`/`lock_ycbcr`/`perform` 全是 NULL），
 * Android 15 的 gralloc 已经全面转向 HIDL/AIDL mapper，legacy C API 没了。
 *
 * 而 provider 进程本来就加载了 `libui.so`，它的 `GraphicBufferMapper::lock` 是**静态方法**
 * （C++ ABI 上就是普通函数），所以可以直接按 mangled 符号调用 —— 不需要 C++ 头、
 * 不需要 libc++、也不需要 hidl/aidl 的生成代码。
 * 代价：这个重载**只给平面 0 的基址、不给 stride**（`getPlaneLayouts` 要 std::vector，
 * 从 C 里构造不了）。因此 M1a 只写"开头有限几行"，用 width 当 stride 上界，**绝不越界**。
 * ------------------------------------------------------------------ */

struct portal_rect {
    int32_t left, top, right, bottom;
};

/* 用 dlopen + dlsym 取这两个静态方法：libui 是平台私有库，NDK 没有它的 stub，
 * 直接按符号引用会在链接期报 undefined（实测）。运行时取地址则两边都干净。 */
/*
 * ⚠️ 这几个都是 **非静态成员函数**（mangled 名里没有 K/无 this 省略），调用时必须把
 * 单例指针当 `this` 传第一个参数。踩过的坑：当成普通函数调，`this`/参数整体错位，
 * 在函数入口第 2 个字节就段错误（tombstone: `getUsage+2`，fault addr 0x0000000c）。
 *
 * 单例指针从 libui 的导出全局 `Singleton<GraphicBufferMapper>::sInstance` 读出来 ——
 * 这是唯一不用 C++ 头就能拿到它的途径（`GraphicBufferMapper::get()` 没有导出符号）。
 */
static void *g_gbm_self = NULL;
static int (*g_gbm_lock)(void *self, void *handle, uint32_t usage,
                         const struct portal_rect *bounds, void **vaddr) = NULL;
static int (*g_gbm_unlock)(void *self, void *handle, void *fence) = NULL;
/* 安全探测：buffer 的真实 usage（只查描述符，不锁不映射） */
static int (*g_gbm_get_usage)(void *self, void *handle, uint64_t *outUsage) = NULL;
/* 正经的 YUV 锁：直接给出 Y/Cb/Cr 三个平面指针与 stride */
static int (*g_gbm_lock_ycbcr)(void *self, void *handle, uint32_t usage,
                               const struct portal_rect *bounds,
                               struct portal_android_ycbcr *out) = NULL;

static bool load_libui_mapper(void) {
    static bool tried = false;
    if (tried) return g_gbm_lock != NULL;
    tried = true;
    void *h = dlopen("libui.so", RTLD_NOW | RTLD_LOCAL);
    if (h == NULL) {
        LOGE("dlopen libui.so 失败：%s", dlerror());
        return false;
    }
    /*
     * 必须先 preloadHal()：GraphicBufferMapper 内部持有一个到 mapper 服务的连接，
     * 在**第一次使用前**建立。provider 进程自己（camera HAL）不走 libui 的 mapper，
     * 所以这个连接一直是空的 —— 直接 lock 会在 mapper 内部空指针解引用
     * （实测 tombstone: lock+66，fault addr 0x0000000c）。
     */
    void (*preload)(void) = (void (*)(void))dlsym(h, "_ZN7android19GraphicBufferMapper10preloadHalEv");
    if (preload != NULL) {
        preload();
    } else {
        LOGE("libui 里没有 preloadHal，mapper 连接可能未初始化");
    }
    g_gbm_lock = (int (*)(void *, void *, uint32_t, const struct portal_rect *, void **))dlsym(
        h, "_ZN7android19GraphicBufferMapper4lockEPK13native_handlejRKNS_4RectEPPv");
    g_gbm_unlock = (int (*)(void *, void *, void *))dlsym(
        h, "_ZN7android19GraphicBufferMapper6unlockEPK13native_handlePNS_4base14unique_fd_implINS4_13DefaultCloserEEE");
    g_gbm_get_usage = (int (*)(void *, void *, uint64_t *))dlsym(
        h, "_ZN7android19GraphicBufferMapper8getUsageEPK13native_handlePy");
    g_gbm_lock_ycbcr = (int (*)(void *, void *, uint32_t, const struct portal_rect *,
                                struct portal_android_ycbcr *))dlsym(
        h, "_ZN7android19GraphicBufferMapper9lockYCbCrEPK13native_handlejRKNS_4RectEP13android_ycbcr");

    /* 单例：libui 导出的全局 sInstance（4 字节指针） */
    void **slot = (void **)dlsym(h, "_ZN7android9SingletonINS_19GraphicBufferMapperEE9sInstanceE");
    g_gbm_self = (slot != NULL) ? *slot : NULL;
    if (g_gbm_self == NULL) {
        LOGE("GraphicBufferMapper 单例尚未创建（sInstance=NULL）：本流不注入（相机行为不变）");
        return false;
    }
    if (g_gbm_lock == NULL || g_gbm_unlock == NULL) {
        LOGE("libui 里没找到 GraphicBufferMapper::lock/unlock（lock=%p unlock=%p），本流不注入",
             (void *)g_gbm_lock, (void *)g_gbm_unlock);
        return false;
    }
    LOGI("libui mapper 就绪：self=%p lock=%p unlock=%p getUsage=%p lockYCbCr=%p", g_gbm_self,
         (void *)g_gbm_lock, (void *)g_gbm_unlock, (void *)g_gbm_get_usage,
         (void *)g_gbm_lock_ycbcr);
    return true;
}

/*
 * `HARDWARE_MODULE_TAG` = MAKE_TAG_CONSTANT('H','W','M','T') = 0x48574D54。
 * ⚠️ 写错会**当场炸掉相机**：hw_get_module 判定"这不是 HAL 模块" ⇒ provider 每 5 秒重启。
 * （实测真实模块的 tag 正是 0x48574d54。）
 */
#define HARDWARE_MODULE_TAG 0x48574D54u /* 'HWMT' */

/*
 * 惰性模块的版本号初值（真实值在原件加载成功后被整块覆盖）。
 * AOSP 的算法是 `HARDWARE_MAKE_API_VERSION(maj,min) = (maj << 8) | min`：
 *   `HARDWARE_HAL_API_VERSION`      = (1,0) = 0x0100
 *   `CAMERA_MODULE_API_VERSION_2_4` = (2,4) = 0x0204
 * 不用 AOSP 的宏名，免得以后有人 `#include <hardware/hardware.h>` 时撞名。
 */
#define PORTAL_HAL_API_VERSION 0x0100u
#define PORTAL_CAMERA_MODULE_API_VERSION 0x0204u

/* ------------------------------------------------------------------ *
 * 状态
 * ------------------------------------------------------------------ */

static void *g_real_handle = NULL;
static struct hw_module_t *g_real_module = NULL;
static int (*g_real_open)(const struct hw_module_t *, const char *, struct hw_device_t **) = NULL;
static int (*g_real_process_capture_request)(const struct camera3_device *,
                                             camera3_capture_request_t *) = NULL;
static int (*g_real_initialize)(const struct camera3_device *,
                                const camera3_callback_ops_t *) = NULL;

/*
 * 注入的默认状态：**全关**（= 纯 M0 转发）。
 *
 * 历史教训（docs/camera-mock.md §4.1）：早先把"包框架回调"与"改 configure_streams"两处接管
 * 一起打开，应用看到**黑屏**。但那两处是**打包**测的，从未单独隔离 —— 而"改动用法位"正是让
 * 应用 buffer 变成可 CPU 映射（从而能写进我们的画面）的唯一正规手段，所以本轮把它们拆成
 * 独立开关逐个验证。默认仍全关，任何一项失败都安静退回纯转发。
 */
static portal_gralloc_module_t *g_gralloc = NULL;
static const camera3_callback_ops_t *g_framework_callbacks = NULL;
static bool g_gralloc_tried = false;
static unsigned long g_injected_frames = 0;

/* ------------------------------------------------------------------ *
 * 运行期开关（文件格式见 [PORTAL_CFG_PATH]）
 * ------------------------------------------------------------------ */

static int g_opt_dump = 0;
static int g_opt_usage_patch = 0;
static int g_opt_init_wrap = 0;
static int g_opt_inject = 0;
static int g_opt_freeze = 0;
static int g_opt_pattern = 0;
static char g_opt_cam[16] = {0};
/** 外源帧文件（app 侧解码后写进来的像素通道）；空 = 用合成图案 */
static char g_opt_src_file[160] = {0};
/** 色度 U/V 是否互换（NV12 ↔ NV21）：这种错只有看图才认得出，故做运行期开关 */
static int g_opt_swap_uv = 0;
/**
 * 真实 HAL 的路径与"照抄多少字节"。
 *
 * 为什么做成开关：不同机器的 HAL 库名与 `camera_module_t` 大小都不同 ——
 * MI6 是 `/vendor/lib/hw/camera.msm8998.orig.so` + **176 字节**；
 * 一加 ACE5（pineapple / ColorOS 16）是 `/vendor/lib64/hw/camera.qcom.so` 的副本 + **376 字节**（实测）。
 * 写死一个值就等于只支持一台机器。
 */
static char g_opt_real_hal[192] = {0};
static unsigned g_opt_real_hmi_bytes = 0;
/**
 * 按 **AOSP ops 索引**取真实函数指针（而不是 dlsym）。
 *
 * 为什么需要：ACE5 的 `camera.qcom.so` **不导出** `QCamera3HardwareInterface::*`
 * （实测 dynsym 里只有 `HMI`），dlsym 必然失败；而 ops 表里的那一格就是真实地址。
 * 取之前先过一道"该地址落在可执行映射里"的校验（见 portal_is_exec_addr），
 * 免得把数据当函数指针调用 —— 在 HAL 进程里那是必崩。
 */
static int g_opt_ops_index = 0;
/**
 * 开关 ops_scan=1：**扫描并接管真件自己的 `camera3_device_ops` 表**（ACE5 需要这条路）。
 *
 * 为什么需要（ACE5 实测）：provider 拿到的 device 对象 `ops` 字段始终是 NULL ——
 * 真件 open 返回时不填、oemlayer 换上的外层对象也不填。可 27ms 后 CamX 的
 * `configure_streams` 照样被调用，说明调用方是**从别处**拿到那批函数指针的。
 * 真件里 `camxhal3.cpp` 的 HAL3 入口是：
 *   process_capture_request = 基址+0x1e49cc0、configure_streams = 基址+0x1e46f80、
 *   open = 基址+0x1e41a90（用 "HalOp: Begin CONFIG/Begin OPEN" 这两条日志字符串反查出来的）。
 * 于是最省事的接管方式变成：在**进程内存里找出这张表**（含该函数指针的可写数据段），
 * 直接改它 —— 不碰代码、不需要 trampoline、也就没有 CFI 与多线程自改代码的风险。
 */
static int g_opt_ops_scan = 0;
/** 真件里 process_capture_request 的 vaddr（按版本可配；默认按本机实测值） */
static unsigned long g_opt_pcr_vaddr = 0x1e49cc0ul;
/**
 * 开关 inline_hook=1 / inline_hook_cfg=1：**直接挂钩真件 HAL3 入口**。
 *
 * 为什么最后落到这条路（ACE5 全套实测）：
 *   · provider 拿到的 device 对象 `ops` 恒为 NULL（真件不填、oemlayer 换上的外层也不填）；
 *   · 真件里**没有** `camera3_device_ops` 静态表：三个 HAL3 入口地址（pcr 0x1e49ca0 /
 *     configure_streams 0x1e46f60 / open 0x1e41a7c）在整个 36MB 文件里没有任何 8 字节指针引用，
 *     也没有 `str xT,[xN,#0x40]` 写 ops 的代码；
 *   · 运行时全地址空间（344MB，含只读 GOT）也搜不到这些地址被存下来过。
 * 既然指针不落地、表不存在，就只能在**函数入口**上挂 —— 这也顺带回答"到底是谁在调它"。
 *
 * 实现（AArch64 最小挂钩）：入口前 16 字节替换成 `ldr x16,#8; br x16; .quad 钩子`，
 * 钩子里重放被顶掉的 4 条指令（paciasp + 3 条 stp，全是非 PC 相对的）再跳回 entry+16。
 * 不动调用目标地址 ⇒ 不触发 CFI；不改映射权限（页本来就可执行）⇒ 不碰 execmem 策略。
 */
static int g_opt_inline_hook = 0;
static int g_opt_inline_hook_cfg = 0;
/** 入口 vaddr（本机实测：pcr=0x1e49ca0、configure_streams=0x1e46f60） */
static unsigned long g_opt_hook_pcr_vaddr = 0x1e49ca0ul;
static unsigned long g_opt_hook_cfg_vaddr = 0x1e46f60ul;
/** `hook_vaddrs=0x1e41a70,0x1e46f60,...`：一次挂一片 HAL3 入口，让日志说出谁在跑 */
static char g_opt_hook_vaddrs[512] = {0};
/**
 * 开关 open_probe=1：open 之后的那些**诊断**（出参观察线程、调用者帧读表、全地址空间探针）。
 *
 * 默认**关**：实测（2026-09-13，ACE5）这套诊断开着时，相机会话会在 `open` 之后、
 * `configure_streams` 之前就崩掉 —— 对照实验很干净：
 *   原厂（无 shim）：Begin OPEN=1，Begin CONFIG=1，逐帧出流；
 *   挂了诊断的 shim：Begin OPEN=1，Begin CONFIG=0，provider 随即被 init 重启。
 * 头号嫌疑是这些诊断在热路径上疯狂调用 `dladdr()`（它要拿 linker 的全局锁）与读
 * `/proc/self/maps`（该进程有上千条映射），把 provider 自己的 dlopen/dlsym 拖死。
 * 所以：诊断必须显式打开、用完就关；**默认部署走的是"零诊断"的形态**。
 */
static int g_opt_open_probe = 0;
/** 开关 `report_period=<秒>`：周期汇报零调用钩子的命中情况。
 *  为什么要有它：钩子体里一条 I/O 都不能做（§22.9），汇报只能等 open —— 而 open 只在
 *  每次相机会话开始时发生一次，实验时"读数"要开一次关一次相机。周期线程把读数解耦。 */
static int g_opt_report_period = 0;
/** 开关 `req_vaddrs=0x…`：在**请求入口**（已实测 = 真件 process_capture_request 0x1e49ca0）
 *  装一个"零调用请求快照"外壳 —— 纯 load/store 把 camera3_capture_request_t 的
 *  前几个字段与首个输出缓冲的 gralloc handle 抄进我们自己的 .bss，
 *  供汇报线程（非热路径）读取。这是做图像注入前必须先拿到的现场数据。 */
static char g_opt_req_vaddrs[128] = {0};
/** 开关 `req_probe=1`：汇报线程在打印快照后，尝试 mmap 首缓冲的 dmabuf（只读）*/
static int g_opt_req_probe = 0;
/** `inject_fd=<fd>`：把某个 dmabuf 里持续填成 `inject_byte`（先在日志里看 dmabuf 清单再挑）
 *  `inject_byte=<0-255>` 填充字节；`inject_us=<微秒>` 填充周期。 */
static int g_opt_inject_fd = -1;
/** `inject_size=<字节>`：按**大小**选靶（首选，因为 fd 号每次重启都变）——
 *  把所有该大小的 dmabuf 全部持续填充。相机输出缓冲在 fd 表里的名字就是 MAP_UNKNOWN。 */
static unsigned long g_opt_inject_size = 0;
static int g_opt_inject_byte = 0x80;
static int g_opt_inject_us = 15000;
/** `inject_pattern=1`：不做纯色填充，改成**逐字节渐变** —— 用来证明"逐像素可控"
 *  （纯色只能证明"写进去了"，渐变才能证明每个字节都由我们决定）。 */
static int g_opt_inject_pattern = 0;
/** `mock_file=<路径>`：把文件内容当作**要注入的整帧原始数据**（尺寸 = 目标缓冲的平面大小）。
 *  优先于 inject_byte/inject_pattern。文件读一次、模板化，热循环只做 memcpy。 */
static char g_opt_mock_file[192] = {0};
/** `mock_map=<尺寸>:<路径>[,<尺寸>:<路径>…]`：**按缓冲尺寸分别给模板**。
 *  为什么需要：预览/录像是不同尺寸的流（实测预览 4,038,656、录像 3,194,880），
 *  一个模板套不了两张画面 —— 图片与视频必须各给一份。 */
static char g_opt_mock_map[512] = {0};
/** `raw_dump=<尺寸>:<路径>`：把**现场那一帧**（该尺寸的第一块 dmabuf）原样落到文件。
 *  用途：照片链路的输入是 RAW（实测：注 RAW 会改照片，注预览 YUV 不会），
 *  而"逆 ISP 合成一张自然颜色的 RAW"不是一朝一夕的事 —— 那就反过来：
 *  **先把真帧取下来，改一处，再喂回去**。人眼看到的仍是自然照片，改动处就是我们的。 */
static char g_opt_raw_dump[256] = {0};
/** `res_hook=<vaddr>` + `res_module=<模块名>` + `res_writer=1`：
 *  **在"结果投递"这一刻写缓冲**，而不是盲目死循环。
 *  为什么必须这样：录像的编码器是"ISP 写完 → 立刻读"，盲目循环的写入大多落在
 *  ISP 写完之前（被覆盖）或之后（太晚），实测结果就是撕裂的花屏。 */
static char g_opt_res_hook[64] = {0};
static char g_opt_res_module[64] = {0};
static int g_opt_res_writer = 0;
/** `hook_dev_vaddrs=`：在 `camx.device-impl.so` 上装**记账钩子**（零调用），
 *  用来回答"结果路径上到底哪个函数在跑" —— 拿到答案再决定写手挂哪儿。 */
static char g_opt_hook_dev_vaddrs[256] = {0};
/** `raw_dump=<尺寸>:<路径>` 的旧声明（保持顺序） */
/** `req_watch=1`：以 2 毫秒周期盯住请求快照，**只要"输出流集合"变了就打印**。
 *  为什么需要：快照只保留"最近一次请求"，而静止/快照请求只占几十次预览请求里的少数，
 *  按秒级周期去读基本永远读不到它。2 毫秒轮询才可能抓到。 */
static int g_opt_req_watch = 0;
/** `jpeg_probe=1`：在所有 dmabuf 里找 **JPEG 码流**（`FF D8 FF`）。
 *  思路：静止/快照不走我们挂钩的 HAL3 入口（实测），那就换个方向找它 ——
 *  不去找"哪个函数被调用"，而是找"编好的 JPEG 最后落在哪块缓冲里"。
 *  找到那块缓冲，照片模拟就退化成"把我们自己的 JPEG 写进去"。 */
static int g_opt_jpeg_probe = 0;
/** `stride_probe=1`：用"行间相关性"实测目标缓冲的**行 stride**（不猜布局） */
static int g_opt_stride_probe = 0;
/** `bufmap=1`：按段打印目标缓冲的内容分布（先知道数据在哪，再谈布局） */
static int g_opt_bufmap = 0;
/** `probe_size=<字节>`：**只看不写**的探针目标尺寸。必须与注入解耦 ——
 *  踩过：探针复用了 inject_size，结果探针读到的永远是我们自己刚填进去的均匀值。 */
static unsigned long g_opt_probe_size = 0;
/** `hook_ol_vaddrs=...`：同一套钩子挂在 **oemlayer** 上的目标（基址按 maps 里的模块路径取） */
static char g_opt_hook_ol_vaddrs[512] = {0};
/** `hook_null_vaddrs=0x1e46f60`：装**空钩子**（只重放、不调用 C），用于隔离机制本身 */
static char g_opt_hook_null_vaddrs[256] = {0};
/** `hook_touch_vaddrs=0x1e46f60`：带一次调用、但被调者只 `ret` 的钩子 */
static char g_opt_hook_touch_vaddrs[256] = {0};
/** `hook_rec_vaddrs=` / `hook_rec_ol_vaddrs=`：**零调用钩子**（正式形态，只记账不 call） */
static char g_opt_hook_rec_vaddrs[512] = {0};
/** `hook_nrec_vaddrs=`：touch 形态外壳 + 裸汇编记录器（最多 2 个） */
static char g_opt_hook_nrec_vaddrs[256] = {0};
static char g_opt_hook_rec_ol_vaddrs[512] = {0};
/**
 * 照片是否**预镜像**（水平翻转）。
 *
 * 为什么需要它（实测）：相机 app 给"真 HAL 那张 JPEG"写 EXIF Orientation=**7**（镜像+旋转），
 * 给"我们的 JPEG"写的是 **2**（只镜像）—— 差别在于我们的 JPEG **不带 EXIF**，app 走了自己的
 * 回退公式。结果是相册里那张照片被水平翻了过去（预览与源视频都是花在右，照片里花在左）。
 * 所以照片路径先镜像一次，让 app 的镜像抵消掉。
 * **只有看图才认得出这类错**，所以按项目惯例做成运行期开关（默认关：别的设备/别的 app 未必一样）。
 */
static int g_opt_photo_mirror = 0;

/**
 * 抹掉 arm64 Android 指针的**顶字节标签**。
 *
 * 实测（ACE5 / Android 16）：堆指针形如 `0xb400007cfdde4450`，而 `/proc/self/maps` 里列的是
 * **去掉标签**的地址（`7cf00b0000-...`）。不抹标签去查 maps 会一条都匹配不上 ——
 * 这个坑曾经让我们得出"对象已释放"的错误结论（探针自检才发现：连已知在映射里的对象都报"不在映射"）。
 * 硬件本身忽略顶字节（TBI），所以解引用时用原指针即可，只有"查 maps"必须抹。
 */
static uintptr_t portal_untag(const void *addr) {
    return (uintptr_t)addr & 0x00FFFFFFFFFFFFFFULL;
}

/**
 * 该地址是否落在**可执行**的已映射区间里（读 `/proc/self/maps`）。
 *
 * 用途：按 ops 索引取真实函数指针时的兜底校验 —— 拿错一格就是"把数据当函数调用"，
 * 在 HAL 进程里必崩。宁可这次不接管，也不赌。
 */
static bool portal_is_exec_addr(const void *addr, const char **out_where) {
    if (addr == NULL) return false;
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == NULL) return false;
    uintptr_t target = portal_untag(addr);
    char line[512];
    bool ok = false;
    while (fgets(line, sizeof(line), f) != NULL) {
        unsigned long start = 0, end = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) continue;
        if (target < start || target >= end) continue;
        if (perms[2] == 'x') {
            ok = true;
            if (out_where != NULL) {
                char *q = strchr(line, '/');
                if (q != NULL) {
                    size_t n = strlen(q);
                    if (n > 0 && q[n - 1] == '\n') q[n - 1] = '\0';
                    *out_where = q;
                }
            }
        }
        break;
    }
    fclose(f);
    return ok;
}

/** 当前 open 的相机 id；`cam=` 定向就靠它 */
static char g_cur_cam[16] = {0};

/** 本次 open 是否在作用范围内（cam= 为空即全部生效） */
static bool portal_cam_selected(void) {
    if (g_opt_cam[0] == '\0') return true;
    return strcmp(g_opt_cam, g_cur_cam) == 0;
}

/**
 * 读一次开关文件。**只读一次**（构造期后不再碰文件系统：HAL 热路径不做 IO）。
 * 文件缺失/读不了 ⇒ 全 0 ⇒ 纯转发，这与部署前的行为完全一致。
 */
static void load_options(void) {
    static bool tried = false;
    if (tried) return;
    tried = true;

    /*
     * 开关文件按顺序找：
     *  1) `/vendor/lib64/hw/portal.cfg`（64 位设备的正式位置，模块挂进去的）；
     *  2) `/vendor/lib/hw/portal.cfg`（32 位设备的正式位置）；
     *  3) `/data/local/tmp/portalex/portal.cfg`（**bring-up 首选**：整个目录已经被标成
     *     `portalex_frame_file`，而 sepolicy 里已有
     *     `allow hal_camera_default portalex_frame_file file { read write open getattr map }`
     *     ⇒ 不需要为它再加任何规则）；
     *  4) `/data/local/tmp/portal.cfg`（最老的 bring-up 位置，需要额外一条对 `shell_data_file`
     *     的 allow，只留给"资料目录还没建起来"的场合）。
     *
     * 0) **`/data/vendor/camera/portal.cfg`（ACE5 上的首选）**：`vendor_camera_data_file`，
     *     相机 HAL 本来就在读写这个目录 ⇒ **不需要任何 sepolicy 补丁、也不需要 overlay**。
     *     （踩过：把 cfg 放 `/data/local/tmp/portalex/` 即使 chcon 正确也读不到 ——
     *     路径中间那层 `/data/local/tmp` 是 `shell_data_file`，`hal_camera_default` 没有 dir search。
     *     而 `/vendor/lib64/hw` 那条路要用 overlay 才能写，overlay 又会打死 `media.swcodec`
     *     的 gralloc-mapper，连带把拍照弄坏 —— 所以"cfg 放相机自己的数据目录"才是对的。）
     *
     * ⚠️ 为什么值得为"多找一个地方"写这么多注释：ACE5 上模块文件根本没被挂载（KernelSU 的
     * 这台机器不挂模块树），于是 1/2 都不存在 —— 而旧代码在"找不到 cfg"时会退化到硬编码的
     * MI6 原件路径，接着就是 §19 那场 provider 崩溃。多一个可达的候选路径，就少一次那种失败。
     */
    static const char *cands[] = {"/data/vendor/camera/portal.cfg",
                                  "/vendor/lib64/hw/portal.cfg",
                                  PORTAL_CFG_PATH,
                                  "/data/local/tmp/portalex/portal.cfg",
                                  "/data/local/tmp/portal.cfg"};
    FILE *f = NULL;
    const char *used = NULL;
    for (unsigned i = 0; i < sizeof(cands) / sizeof(cands[0]) && f == NULL; i++) {
        f = fopen(cands[i], "re");
        if (f != NULL) used = cands[i];
    }
    if (f == NULL) {
        LOGI("五个开关文件都不存在（/data/vendor/camera /vendor/lib64/hw /vendor/lib/hw "
             "/data/local/tmp/portalex /data/local/tmp）：全部开关按关闭处理");
        return;
    }
    LOGI("开关文件：%s", used);
    char line[256];
    while (fgets(line, sizeof(line), f) != NULL) {
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '#' || *p == '\n' || *p == '\r' || *p == '\0') continue;
        char *eq = strchr(p, '=');
        if (eq == NULL) continue;
        *eq = '\0';
        char *key = p;
        char *val = eq + 1;
        char *end = val + strlen(val);
        while (end > val && (end[-1] == '\n' || end[-1] == '\r' || end[-1] == ' ')) *--end = '\0';
        /* key 也可能带尾空格 */
        end = key + strlen(key);
        while (end > key && end[-1] == ' ') *--end = '\0';

        if (strcmp(key, "dump") == 0) {
            g_opt_dump = atoi(val);
        } else if (strcmp(key, "usage_patch") == 0) {
            g_opt_usage_patch = atoi(val);
        } else if (strcmp(key, "init_wrap") == 0) {
            g_opt_init_wrap = atoi(val);
        } else if (strcmp(key, "inject") == 0) {
            g_opt_inject = atoi(val);
        } else if (strcmp(key, "freeze") == 0) {
            g_opt_freeze = atoi(val);
        } else if (strcmp(key, "pattern") == 0) {
            g_opt_pattern = atoi(val);
        } else if (strcmp(key, "swap_uv") == 0) {
            g_opt_swap_uv = atoi(val);
        } else if (strcmp(key, "photo_mirror") == 0) {
            g_opt_photo_mirror = atoi(val);
        } else if (strcmp(key, "real_hal") == 0) {
            snprintf(g_opt_real_hal, sizeof(g_opt_real_hal), "%s", val);
        } else if (strcmp(key, "real_hmi_bytes") == 0) {
            g_opt_real_hmi_bytes = (unsigned)atoi(val);
        } else if (strcmp(key, "ops_index") == 0) {
            g_opt_ops_index = atoi(val);
        } else if (strcmp(key, "ops_scan") == 0) {
            g_opt_ops_scan = atoi(val);
        } else if (strcmp(key, "ops_pcr_vaddr") == 0) {
            g_opt_pcr_vaddr = strtoul(val, NULL, 0);
        } else if (strcmp(key, "inline_hook") == 0) {
            g_opt_inline_hook = atoi(val);
        } else if (strcmp(key, "inline_hook_cfg") == 0) {
            g_opt_inline_hook_cfg = atoi(val);
        } else if (strcmp(key, "hook_pcr_vaddr") == 0) {
            g_opt_hook_pcr_vaddr = strtoul(val, NULL, 0);
        } else if (strcmp(key, "hook_cfg_vaddr") == 0) {
            g_opt_hook_cfg_vaddr = strtoul(val, NULL, 0);
        } else if (strcmp(key, "hook_vaddrs") == 0) {
            snprintf(g_opt_hook_vaddrs, sizeof(g_opt_hook_vaddrs), "%s", val);
        } else if (strcmp(key, "open_probe") == 0) {
            g_opt_open_probe = atoi(val);
        } else if (strcmp(key, "report_period") == 0) {
            g_opt_report_period = atoi(val);
        } else if (strcmp(key, "req_vaddrs") == 0) {
            snprintf(g_opt_req_vaddrs, sizeof(g_opt_req_vaddrs), "%s", val);
        } else if (strcmp(key, "req_probe") == 0) {
            g_opt_req_probe = atoi(val);
        } else if (strcmp(key, "inject_fd") == 0) {
            g_opt_inject_fd = atoi(val);
        } else if (strcmp(key, "inject_size") == 0) {
            g_opt_inject_size = strtoul(val, NULL, 0);
        } else if (strcmp(key, "inject_byte") == 0) {
            g_opt_inject_byte = (int)(strtoul(val, NULL, 0) & 0xFFu);
        } else if (strcmp(key, "inject_us") == 0) {
            g_opt_inject_us = atoi(val);
        } else if (strcmp(key, "inject_pattern") == 0) {
            g_opt_inject_pattern = atoi(val);
        } else if (strcmp(key, "mock_map") == 0) {
            snprintf(g_opt_mock_map, sizeof(g_opt_mock_map), "%s", val);
        } else if (strcmp(key, "mock_file") == 0) {
            snprintf(g_opt_mock_file, sizeof(g_opt_mock_file), "%s", val);
        } else if (strcmp(key, "stride_probe") == 0) {
            g_opt_stride_probe = atoi(val);
        } else if (strcmp(key, "jpeg_probe") == 0) {
            g_opt_jpeg_probe = atoi(val);
        } else if (strcmp(key, "req_watch") == 0) {
            g_opt_req_watch = atoi(val);
        } else if (strcmp(key, "raw_dump") == 0) {
            snprintf(g_opt_raw_dump, sizeof(g_opt_raw_dump), "%s", val);
        } else if (strcmp(key, "res_hook") == 0) {
            snprintf(g_opt_res_hook, sizeof(g_opt_res_hook), "%s", val);
        } else if (strcmp(key, "res_module") == 0) {
            snprintf(g_opt_res_module, sizeof(g_opt_res_module), "%s", val);
        } else if (strcmp(key, "res_writer") == 0) {
            g_opt_res_writer = atoi(val);
        } else if (strcmp(key, "hook_dev_vaddrs") == 0) {
            snprintf(g_opt_hook_dev_vaddrs, sizeof(g_opt_hook_dev_vaddrs), "%s", val);
        } else if (strcmp(key, "bufmap") == 0) {
            g_opt_bufmap = atoi(val);
        } else if (strcmp(key, "probe_size") == 0) {
            g_opt_probe_size = strtoul(val, NULL, 0);
        } else if (strcmp(key, "hook_nrec_vaddrs") == 0) {
            snprintf(g_opt_hook_nrec_vaddrs, sizeof(g_opt_hook_nrec_vaddrs), "%s", val);
        } else if (strcmp(key, "hook_rec_vaddrs") == 0) {
            snprintf(g_opt_hook_rec_vaddrs, sizeof(g_opt_hook_rec_vaddrs), "%s", val);
        } else if (strcmp(key, "hook_rec_ol_vaddrs") == 0) {
            snprintf(g_opt_hook_rec_ol_vaddrs, sizeof(g_opt_hook_rec_ol_vaddrs), "%s", val);
        } else if (strcmp(key, "hook_touch_vaddrs") == 0) {
            snprintf(g_opt_hook_touch_vaddrs, sizeof(g_opt_hook_touch_vaddrs), "%s", val);
        } else if (strcmp(key, "hook_null_vaddrs") == 0) {
            snprintf(g_opt_hook_null_vaddrs, sizeof(g_opt_hook_null_vaddrs), "%s", val);
        } else if (strcmp(key, "hook_ol_vaddrs") == 0) {
            snprintf(g_opt_hook_ol_vaddrs, sizeof(g_opt_hook_ol_vaddrs), "%s", val);
        } else if (strcmp(key, "src_file") == 0) {
            snprintf(g_opt_src_file, sizeof(g_opt_src_file), "%s", val);
        } else if (strcmp(key, "cam") == 0) {
            snprintf(g_opt_cam, sizeof(g_opt_cam), "%s", val);
        }
    }
    fclose(f);
    LOGI("开关：dump=%d ops_index=%d usage_patch=%d init_wrap=%d inject=%d freeze=%d pattern=%d photo_mirror=%d cam=%s src=%s",
         g_opt_dump, g_opt_ops_index, g_opt_usage_patch, g_opt_init_wrap, g_opt_inject, g_opt_freeze,
         g_opt_pattern, g_opt_photo_mirror, g_opt_cam[0] ? g_opt_cam : "(全部)",
         g_opt_src_file[0] ? g_opt_src_file : "(图案)");
}

/* 我们交给 HAL 的回调表（定义在这里，实现见下方 M1a 段）—— 先声明后引用 */
static void portal_process_capture_result(const camera3_callback_ops_t *,
                                          const camera3_capture_result_t *);
/* 拍照请求的早期信号（定义在外源帧段；请求回调里就要用到，故前置声明） */
static void portal_fsrc_signal_photo(uint32_t w, uint32_t h);
static void portal_notify(const camera3_callback_ops_t *, const void *);
static camera3_callback_ops_t g_our_callbacks = {
    .process_capture_result = portal_process_capture_result,
    .notify = portal_notify,
};

/* 前置声明（g_methods 要用） */
static int shim_open(const struct hw_module_t *module, const char *id,
                     struct hw_device_t **device);
/* 前置声明：钩子命中计数汇报（实现见下方"函数入口挂钩"段） */
static void portal_hook_report_counts(void);

/**
 * 惰性兜底回调：原件没打开时，任何被点名调用的 `camera_module_t` 回调都落到这里。
 *
 * 返回 `-ENODEV`：`get_number_of_cameras()` 被当整数读时是负数 ⇒ provider 的循环一次都不进
 * ⇒ "零个相机"；其余回调返回错误码也是明确的"不可用"语义。绝不返回 0/成功值 ——
 * 假装有相机比说没有更糟。
 */
static int portal_inert_stub(void) { return -ENODEV; }

static struct hw_module_methods_t g_methods = {.open = shim_open};

/*
 * ------------------------------------------------------------------ *
 * OPPO OEM 层额外索要的导出符号（**必须转出去，不能只有 HMI**）
 *
 * 血案（ACE5 / Android 16 / ColorOS 16，docs/camera-mock.md §19）：`/odm/lib64/hw/camera.oemlayer.so`
 * 的 `load()` 会按「这个文件导出 `HMI` 吗」在候选目录里挑一个相机 HAL，然后
 *   1) `dlsym(h, "SetOplusCameraInfoCallback")` —— 只把失败当一条日志；
 *   2) `dlsym(h, "HMI")` + `strcmp("camera", HMI->common.id)`；
 * 并在 `OemLayer::init()` 里**无条件调用**第 1 步拿到的指针：
 *
 *     6f250: ldr  x19, [x8]          ; = dlsym 结果
 *     6f26c: bl   __cfi_slowpath     ; 先过 CFI（x1 = 目标指针）
 *     6f278: blr  x19                ; ★ NULL 就在这里炸
 *
 * 我们替换了原件，就必须连这个符号一起提供，否则 dlsym 得到 NULL、调用时崩在
 * `__cfi_slowpath` 里（tombstone: `OemLayer::init() (.cfi)+312` → `__cfi_slowpath+28`，
 * x1 = 0）—— 而 provider 崩溃循环会连带把整机相机打死。
 *
 * 惰性时返回 `-ENODEV`（真件没加载 ⇒ 明确说"没有"），绝不假装成功。
 * ------------------------------------------------------------------ */
typedef int (*portal_oplus_info_cb_t)(void *info);
static portal_oplus_info_cb_t g_real_oplus_info_cb = NULL;

__attribute__((visibility("default"))) int SetOplusCameraInfoCallback(void *info) {
    if (g_real_oplus_info_cb == NULL) return -ENODEV;
    return g_real_oplus_info_cb(info);
}

/**
 * 我们导出的模块入口（符号名必须是 `HMI`：`HAL_MODULE_INFO_SYM` 展开后就是它）。
 *
 * 留 512 字节而不是 128：真实结构是 `camera_module_t`（见文件头"取舍 2"），
 * 声明小了会让 provider 读到相邻内存当回调/数量，直接崩在 init 里。
 *
 * **惰性合法初值**（2026-09-12 在一台一加 ACE5 上换来的，见 docs/camera-mock.md §19）：
 * 原件加载成功时这个结构被整块 memcpy 覆盖；而**加载失败时它必须仍然合法**。
 * 反例：Android 16 的 OPPO OEM 层（`/odm/lib64/hw/camera.oemlayer.so` 的 `load()`）会自己
 * 把 `camera.qcom` 再加载一遍，然后执行
 *
 *     strcmp("camera", ((camera_module_t *)dlsym(h, "HMI"))->common.id)
 *
 * 旧的 `HMI = {0}` 在这种情况下 `common.id == NULL` ⇒ provider 崩在这句 strcmp 里，
 * 而且崩在构造函数第一行日志之前（tombstone `#00 __strcmp_aarch64 ← load+276`；
 * 六份 tombstone 签名逐帧一致，连"日志一行都没有"都对得上）。
 * 现在最坏情况降级为"相机不可用、进程活着"，与应用的 fail-inert 纪律一致。
 */
struct portal_hmi_t {
    struct hw_module_t common; /* AOSP 布局：id 在 +8（tag 4 + api 2 + hal 2） */
    void *extra[48];           /* 留白：真实结构更大，绝不能少（376 字节仍在 512 内） */
};

__attribute__((visibility("default"))) struct portal_hmi_t HMI = {
    .common =
        {
            .tag = HARDWARE_MODULE_TAG,
            .module_api_version = PORTAL_CAMERA_MODULE_API_VERSION,
            .hal_api_version = PORTAL_HAL_API_VERSION,
            .id = "camera",
            .name = "PortalEX camera shim (inert until real HAL loads)",
            .author = "PortalEX",
            .methods = &g_methods,
            .dso = NULL,
        },
    /* 其余槽位先全指向惰性回调；加载成功后整块覆盖 + 尾部清零（见 load_real_hal） */
    .extra = {[0 ... 47] = (void *)&portal_inert_stub},
};

/* ------------------------------------------------------------------ *
 * 页保护读取
 * ------------------------------------------------------------------ */

/**
 * 读 `/proc/self/maps`，取出 [addr] 所在页的**当前**保护标志。
 *
 * 为什么需要：改 ops 表后必须**原样还原**该页权限。实测把 QTI ops 表所在页按"只读"还原后，
 * HAL 写自己同页的 `.bss` 静态量（`__cxa_guard_acquire`）就 SEGV_ACCERR，provider 当场死。
 *
 * @return PROT_* 位掩码；查不到返回 -1
 */
static int page_protection(const void *addr) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == NULL) return -1;
    char line[512];
    uintptr_t target = portal_untag(addr);
    int result = -1;
    while (fgets(line, sizeof(line), f) != NULL) {
        unsigned long start = 0, end = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) continue;
        if (target < start || target >= end) continue;
        result = 0;
        if (perms[0] == 'r') result |= PROT_READ;
        if (perms[1] == 'w') result |= PROT_WRITE;
        if (perms[2] == 'x') result |= PROT_EXEC;
        break;
    }
    fclose(f);
    return result;
}

/* ------------------------------------------------------------------ *
 * 请求/结果的真实布局探测
 *
 * **本机实测**：provider 传给 HAL 的 `camera3_capture_request_t` 与 AOSP 头文件字段顺序
 * **不同** —— `num_output_buffers` 在 `output_buffers` **之前**（docs/camera-mock.md §5.4：
 * 按头文件解引用 ⇒ `SIGSEGV fault addr 0x1`，因为把计数 1 当成了指针）。结果结构同理，不能赌。
 *
 * 这里按"哪一组看起来像（指针, 计数）"来选，两个候选**互斥消歧**：
 *   · 指针必须 > 0x1000 且 4 字节对齐；计数必须落在 1..16。
 * 交换序下 AOSP 候选会把计数当指针（判据不过），反之亦然 ⇒ 唯一解；两个候选都不成立就
 * 直接放弃（绝不解引用一个没把握的地址）。
 * ------------------------------------------------------------------ */

struct portal_layout {
    const camera3_stream_buffer_t *sbs;
    uint32_t num;
    const char *which;
};

/** 按给定的 (output_buffers 偏移, num_output_buffers 偏移) 试解一次，判据不过就返回 false */
static bool portal_parse_pair(const void *raw, size_t out_off, size_t num_off,
                              struct portal_layout *out) {
    const char *base = (const char *)raw;
    const void *ptr = *(const void *const *)(base + out_off);
    uint32_t n = *(const uint32_t *)(base + num_off);
    if ((uintptr_t)ptr <= 0x1000u || ((uintptr_t)ptr & 3u) != 0u || n < 1u || n > 16u) {
        return false;
    }
    out->sbs = (const camera3_stream_buffer_t *)ptr;
    out->num = n;
    return true;
}

static void portal_layout_fail(struct portal_layout *out, const char *why) {
    out->sbs = NULL;
    out->num = 0;
    out->which = why;
}

/** 请求：交换序(out@16,num@12) 优先（本机实测），再退 AOSP 序 */
static bool portal_parse_request(const void *raw, struct portal_layout *out) {
    if (portal_parse_pair(raw, 16, 12, out)) {
        out->which = "交换序(out@16,num@12)";
        return true;
    }
    if (portal_parse_pair(raw, 12, 16, out)) {
        out->which = "AOSP序(out@12,num@16)";
        return true;
    }
    portal_layout_fail(out, "认不出");
    return false;
}

/** 结果：AOSP 序(out@12,num@8) 优先，再退交换序(out@8,num@12) —— 同样靠判据消歧 */
static bool portal_parse_result(const void *raw, struct portal_layout *out) {
    if (portal_parse_pair(raw, 12, 8, out)) {
        out->which = "AOSP序(out@12,num@8)";
        return true;
    }
    if (portal_parse_pair(raw, 8, 12, out)) {
        out->which = "交换序(out@8,num@12)";
        return true;
    }
    portal_layout_fail(out, "认不出");
    return false;
}

/**
 * 打印一个 gralloc 句柄的整数段。
 *
 * **为什么要打**：写像素需要 stride，而本机 `lockYCbCr` 在这些 buffer 上不工作、
 * `GraphicBufferMapper::lock` 又只给基址不给 stride。厂商 gralloc 的私有字段里有几何信息，
 * 但布局是厂商私有的 —— 只能打出来看（纯读，不改）。头字段不像 native_handle 就立刻收手。
 */
static void portal_dump_handle(uint32_t idx, const void *handle) {
    if (handle == NULL || (uintptr_t)handle < 0x1000u) return;
    const int *h = (const int *)handle;
    int version = h[0], numFds = h[1], numInts = h[2];
    if (numFds < 0 || numFds > 8 || numInts < 0 || numInts > 64) {
        LOGI("  out[%u] handle=%p 头字段不像 native_handle（v=%d nFds=%d nInts=%d）→ 不解引用",
             idx, handle, version, numFds, numInts);
        return;
    }
    const int *ints = h + 3 + numFds;
    char buf[400];
    int off = snprintf(buf, sizeof(buf), "  out[%u] handle v=%d nFds=%d nInts=%d ints=", idx,
                       version, numFds, numInts);
    for (int k = 0; k < numInts && off < (int)sizeof(buf) - 14; k++) {
        off += snprintf(buf + off, sizeof(buf) - off, "%d:%u ", k, (unsigned)ints[k]);
    }
    LOGI("%s", buf);
}

/** 这批输出里有没有 JPEG/BLOB（0x21）—— 拍照走的就是它，需要单独打点 */
static bool portal_layout_has_blob(const struct portal_layout *L) {
    if (L == NULL || L->sbs == NULL) return false;
    for (uint32_t i = 0; i < L->num; i++) {
        const camera3_stream_t *st = L->sbs[i].stream;
        if (st != NULL && st->format == 0x21) return true;
    }
    return false;
}

/** 打印一次请求的真实字段与每个输出流/句柄的参数（只读；dump 开关打开时才走） */
static void portal_dump_request(const void *raw, unsigned long n) {
    struct portal_layout L;
    bool ok = portal_parse_request(raw, &L);
    LOGI("请求布局#%lu：frame=%u w4=%p w8=%08x w12=%08x w16=%08x → %s num=%u", n,
         *(const uint32_t *)raw, *(const void *const *)((const char *)raw + 4),
         *(const uint32_t *)((const char *)raw + 8), *(const uint32_t *)((const char *)raw + 12),
         *(const uint32_t *)((const char *)raw + 16), L.which, L.num);
    if (!ok) return;
    for (uint32_t i = 0; i < L.num; i++) {
        const camera3_stream_buffer_t *sb = &L.sbs[i];
        if (sb->stream == NULL) {
            LOGI("  out[%u] stream=NULL", i);
            continue;
        }
        LOGI("  out[%u] format=0x%x %ux%u usage=0x%x type=%d max_buffers=%u", i, sb->stream->format,
             sb->stream->width, sb->stream->height, sb->stream->usage, sb->stream->stream_type,
             sb->stream->max_buffers);
        if (sb->buffer != NULL) portal_dump_handle(i, *sb->buffer);
    }
}

/* ------------------------------------------------------------------ *
 * 速率与耗时计量（判定"谁在限速"的唯一手段：传感器帧率 vs 我们的写帧开销）
 * ------------------------------------------------------------------ */

static unsigned long g_req_count = 0;
static struct timespec g_req_prev;
static double g_write_ms_total = 0.0;
static unsigned long g_write_count = 0;

static double portal_seconds_since(struct timespec *prev) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    double dt = (double)(now.tv_sec - prev->tv_sec) + (double)(now.tv_nsec - prev->tv_nsec) / 1e9;
    *prev = now;
    return dt;
}

/** 每 120 个捕获请求报一次真实到达速率（含会话中途重配流后的抖动，故取整段平均） */
static void portal_count_request(void) {
    unsigned long n = __atomic_add_fetch(&g_req_count, 1, __ATOMIC_RELAXED);
    if (n % 120 == 0) {
        if (g_req_prev.tv_sec == 0) {
            /* 首个样本只是把基准时间取回来（否则会把"进程启动至今"当成一次采样） */
            portal_seconds_since(&g_req_prev);
            return;
        }
        double dt = portal_seconds_since(&g_req_prev);
        if (dt > 0.05) LOGI("捕获请求速率：120 个用时 %.0f ms ≈ %.1f fps", dt * 1000.0, 120.0 / dt);
    }
}

/** 每 120 次写帧报一次平均耗时（写整幅 1600×1200 的 CPU 成本） */
static void portal_count_write(double ms) {
    unsigned long n = __atomic_add_fetch(&g_write_count, 1, __ATOMIC_RELAXED);
    g_write_ms_total += ms;
    if (n % 120 == 0) {
        LOGI("写帧耗时：最近 120 帧平均 %.2f ms（累计 %lu 帧）", g_write_ms_total / 120.0, n);
        g_write_ms_total = 0.0;
    }
}

/* ------------------------------------------------------------------ *
 * 转发 + 接管
 * ------------------------------------------------------------------ */

static int shim_process_capture_request(const struct camera3_device *dev,
                                        camera3_capture_request_t *request) {
    static unsigned long calls = 0;
    unsigned long n = __atomic_add_fetch(&calls, 1, __ATOMIC_RELAXED);
    portal_count_request();

    if (n == 1 || n % 240 == 0) {
        LOGI("process_capture_request 已接管：#%lu（相机 %s）", n, g_cur_cam[0] ? g_cur_cam : "?");
    }
    /* **拍照请求的早期信号**：这里就能看到 BLOB 流，比结果早一百多毫秒 */
    if (request != NULL && g_opt_src_file[0] != '\0' && portal_cam_selected()) {
        struct portal_layout LR;
        if (portal_parse_request(request, &LR)) {
            for (uint32_t i = 0; i < LR.num; i++) {
                const camera3_stream_t *st = LR.sbs[i].stream;
                if (st != NULL && st->format == 0x21) {
                    portal_fsrc_signal_photo(st->width, st->height);
                    break;
                }
            }
        }
    }
    if (request != NULL && g_opt_dump && portal_cam_selected()) {
        bool want = (n <= 3 || n % 240 == 0);
        if (!want) {
            struct portal_layout L;
            want = portal_parse_request(request, &L) && portal_layout_has_blob(&L);
        }
        if (want) portal_dump_request(request, n);
    }
    /* freeze：真 HAL 不参与（只有我们能自己产出并投递结果时才能开，否则应用等不到帧） */
    if (g_opt_freeze) return 0;
    if (g_real_process_capture_request == NULL) return -1;
    return g_real_process_capture_request(dev, request);
}

/* ------------------------------------------------------------------ *
 * M1b：GPU 合成 —— 用 EGLImage 把相机 buffer 当**渲染目标**
 *
 * 为什么要绕到 GPU：本 ROM 的相机输出 buffer 没有 mapper 描述符
 * （`getUsage` 返回 ENOSYS），既无法安全探测能否 CPU 写，而 `lock` 对不可映射的 buffer
 * 又是 abort（打死过 provider 三次）⇒ CPU 直写不可安全实现。
 * 而 EGL/GL 这条路**每一步失败都返回错误码**（EGL_NO_IMAGE/EGL_NO_CONTEXT/GL error），
 * 不会把 HAL 进程带走 —— 这正是 HAL 里做实验的硬要求。
 *
 * 做法：`eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID, handle)` 把 buffer 变成 EGLImage
 * → `glEGLImageTargetTexture2DOES` 绑成外部纹理 → 挂到 FBO 上当颜色附件 → 直接
 * clear + scissor 画条带（不需要写 shader）。
 *
 * 两个必须守住的点：
 *  1. **保存并恢复当前 EGL 上下文**：我们跑在 HAL 的线程上，若该线程已有 current context，
 *     切走会让 HAL 之后的 GL 调用画到我们的上下文里 —— 用完必须恢复原样；
 *  2. glEGLImageTargetTexture2DOES / eglCreateImageKHR 都是扩展，必须走 eglGetProcAddress；
 *     拿不到就整条路径放弃（返回 false，纯转发）。
 * ------------------------------------------------------------------ */

#define PORTAL_EGL_NATIVE_BUFFER_ANDROID 0x3140
#define PORTAL_EGL_IMAGE_PRESERVED_KHR 0x30D2
#define PORTAL_GL_TEXTURE_EXTERNAL_OES 0x8D65
#define PORTAL_GL_FRAMEBUFFER 0x8D40
#define PORTAL_GL_COLOR_ATTACHMENT0 0x8CE0
#define PORTAL_GL_FRAMEBUFFER_COMPLETE 0x8CD5

/* EGL / GLES 函数指针 */
static void *g_egl = NULL, *g_gles = NULL;
static void *(*p_eglGetProcAddress)(const char *) = NULL;
static void *(*p_eglGetDisplay)(int) = NULL;
static int (*p_eglInitialize)(void *, int *, int *) = NULL;
static int (*p_eglChooseConfig)(void *, const int *, void **, int, int *) = NULL;
static void *(*p_eglCreateContext)(void *, void *, void *, const int *) = NULL;
static void *(*p_eglCreatePbufferSurface)(void *, void *, const int *) = NULL;
static int (*p_eglMakeCurrent)(void *, void *, void *, void *) = NULL;
static void *(*p_eglGetCurrentContext)(void) = NULL;
static void *(*p_eglGetCurrentDisplay)(void) = NULL;
static void *(*p_eglGetCurrentSurface)(int) = NULL;
static void *(*p_eglCreateImageKHR)(void *, void *, unsigned int, void *, const int *) = NULL;
static int (*p_eglDestroyImageKHR)(void *, void *) = NULL;
static int (*p_eglGetError)(void) = NULL;
static void (*p_glEGLImageTargetTexture2DOES)(unsigned int, void *) = NULL;
static void (*p_glGenTextures)(int, unsigned int *) = NULL;
static void (*p_glBindTexture)(unsigned int, unsigned int) = NULL;
static void (*p_glTexParameteri)(unsigned int, unsigned int, int) = NULL;
static void (*p_glGenFramebuffers)(int, unsigned int *) = NULL;
static void (*p_glBindFramebuffer)(unsigned int, unsigned int) = NULL;
static void (*p_glFramebufferTexture2D)(unsigned int, unsigned int, unsigned int, unsigned int,
                                        int) = NULL;
static unsigned int (*p_glCheckFramebufferStatus)(unsigned int) = NULL;
static void (*p_glViewport)(int, int, int, int) = NULL;
static void (*p_glClearColor)(float, float, float, float) = NULL;
static void (*p_glClear)(unsigned int) = NULL;
static void (*p_glScissor)(int, int, int, int) = NULL;
static void (*p_glEnable)(unsigned int) = NULL;
static void (*p_glDisable)(unsigned int) = NULL;
static void (*p_glDeleteFramebuffers)(int, const unsigned int *) = NULL;
static void (*p_glDeleteTextures)(int, const unsigned int *) = NULL;

static void *g_egl_display = NULL;
static void *g_egl_context = NULL;
static void *g_egl_surface = NULL;
static bool g_egl_tried = false;
static bool g_egl_ready = false;

static void *sym(void *lib, const char *name) {
    void *p = lib ? dlsym(lib, name) : NULL;
    if (p == NULL && p_eglGetProcAddress != NULL) p = p_eglGetProcAddress(name);
    return p;
}

/** 初始化一次：EGL display/context/pbuffer + 所有函数指针。任何一步失败都返回 false */
static bool portal_egl_init(void) {
    if (g_egl_tried) return g_egl_ready;
    g_egl_tried = true;

    g_egl = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    g_gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    if (g_egl == NULL || g_gles == NULL) {
        LOGE("dlopen libEGL/libGLESv2 失败：GPU 注入不可用（相机行为不变）");
        return false;
    }

    p_eglGetProcAddress = (void *(*)(const char *))dlsym(g_egl, "eglGetProcAddress");
    p_eglGetDisplay = (void *(*)(int))sym(g_egl, "eglGetDisplay");
    p_eglInitialize = (int (*)(void *, int *, int *))sym(g_egl, "eglInitialize");
    p_eglChooseConfig = (int (*)(void *, const int *, void **, int, int *))sym(g_egl, "eglChooseConfig");
    p_eglCreateContext = (void *(*)(void *, void *, void *, const int *))sym(g_egl, "eglCreateContext");
    p_eglCreatePbufferSurface =
        (void *(*)(void *, void *, const int *))sym(g_egl, "eglCreatePbufferSurface");
    p_eglMakeCurrent = (int (*)(void *, void *, void *, void *))sym(g_egl, "eglMakeCurrent");
    p_eglGetCurrentContext = (void *(*)(void))sym(g_egl, "eglGetCurrentContext");
    p_eglGetCurrentDisplay = (void *(*)(void))sym(g_egl, "eglGetCurrentDisplay");
    p_eglGetCurrentSurface = (void *(*)(int))sym(g_egl, "eglGetCurrentSurface");
    p_eglCreateImageKHR =
        (void *(*)(void *, void *, unsigned int, void *, const int *))sym(g_egl, "eglCreateImageKHR");
    p_eglDestroyImageKHR = (int (*)(void *, void *))sym(g_egl, "eglDestroyImageKHR");
    p_eglGetError = (int (*)(void))sym(g_egl, "eglGetError");
    p_glEGLImageTargetTexture2DOES =
        (void (*)(unsigned int, void *))sym(g_gles, "glEGLImageTargetTexture2DOES");
    p_glGenTextures = (void (*)(int, unsigned int *))sym(g_gles, "glGenTextures");
    p_glBindTexture = (void (*)(unsigned int, unsigned int))sym(g_gles, "glBindTexture");
    p_glTexParameteri = (void (*)(unsigned int, unsigned int, int))sym(g_gles, "glTexParameteri");
    p_glGenFramebuffers = (void (*)(int, unsigned int *))sym(g_gles, "glGenFramebuffers");
    p_glBindFramebuffer = (void (*)(unsigned int, unsigned int))sym(g_gles, "glBindFramebuffer");
    p_glFramebufferTexture2D =
        (void (*)(unsigned int, unsigned int, unsigned int, unsigned int, int))sym(
            g_gles, "glFramebufferTexture2D");
    p_glCheckFramebufferStatus = (unsigned int (*)(unsigned int))sym(g_gles, "glCheckFramebufferStatus");
    p_glViewport = (void (*)(int, int, int, int))sym(g_gles, "glViewport");
    p_glClearColor = (void (*)(float, float, float, float))sym(g_gles, "glClearColor");
    p_glClear = (void (*)(unsigned int))sym(g_gles, "glClear");
    p_glScissor = (void (*)(int, int, int, int))sym(g_gles, "glScissor");
    p_glEnable = (void (*)(unsigned int))sym(g_gles, "glEnable");
    p_glDisable = (void (*)(unsigned int))sym(g_gles, "glDisable");
    p_glDeleteFramebuffers = (void (*)(int, const unsigned int *))sym(g_gles, "glDeleteFramebuffers");
    p_glDeleteTextures = (void (*)(int, const unsigned int *))sym(g_gles, "glDeleteTextures");

    if (p_eglGetDisplay == NULL || p_eglInitialize == NULL || p_eglCreateImageKHR == NULL ||
        p_glEGLImageTargetTexture2DOES == NULL || p_glCheckFramebufferStatus == NULL) {
        LOGE("EGL/GL 关键函数缺失（image=%p targetTexture=%p）→ GPU 注入不可用",
             (void *)p_eglCreateImageKHR, (void *)p_glEGLImageTargetTexture2DOES);
        return false;
    }

    g_egl_display = p_eglGetDisplay(0 /* EGL_DEFAULT_DISPLAY */);
    int major = 0, minor = 0;
    if (g_egl_display == NULL || !p_eglInitialize(g_egl_display, &major, &minor)) {
        LOGE("eglInitialize 失败 → GPU 注入不可用");
        return false;
    }
    /* 1x1 pbuffer 只为满足"有 surface"的要求；真正渲染目标是每帧的 EGLImage */
    const int cfg_attr[] = {0x3024 /*RED_SIZE*/,   8,
                            0x3023 /*GREEN_SIZE*/, 8,
                            0x3022 /*BLUE_SIZE*/,  8,
                            0x3040 /*RENDERABLE_TYPE*/, 0x0004 /*ES2_BIT*/,
                            0x3033 /*SURFACE_TYPE*/, 0x0001 /*PBUFFER_BIT*/,
                            0x3038 /*NONE*/,       0};
    void *config = NULL;
    int num = 0;
    if (!p_eglChooseConfig(g_egl_display, cfg_attr, &config, 1, &num) || num == 0) {
        LOGE("eglChooseConfig 失败 → GPU 注入不可用");
        return false;
    }
    const int ctx_attr[] = {0x3098 /*CONTEXT_CLIENT_VERSION*/, 2, 0x3038, 0};
    g_egl_context = p_eglCreateContext(g_egl_display, config, NULL /*no share*/, ctx_attr);
    const int pb_attr[] = {0x3057 /*WIDTH*/, 1, 0x3056 /*HEIGHT*/, 1, 0x3038, 0};
    g_egl_surface = p_eglCreatePbufferSurface(g_egl_display, config, pb_attr);
    if (g_egl_context == NULL || g_egl_surface == NULL) {
        LOGE("创建 EGL 上下文/pbuffer 失败 → GPU 注入不可用");
        return false;
    }
    g_egl_ready = true;
    LOGI("GPU 注入就绪：EGL %d.%d display=%p context=%p", major, minor, g_egl_display,
         g_egl_context);
    return true;
}

/**
 * 把图案画进这个 buffer。**每一步失败都只是返回 false**（EGL/GL 都是错误码语义）。
 * @return true = 真的画进去了
 */
static bool inject_via_gpu(void *handle, uint32_t w, uint32_t h, uint32_t frame) {
    if (!portal_egl_init()) return false;

    /* 保存调用线程原有的 EGL 状态，结束前原样恢复（HAL 可能在同一线程用 GL） */
    void *prev_ctx = p_eglGetCurrentContext ? p_eglGetCurrentContext() : NULL;
    void *prev_dpy = p_eglGetCurrentDisplay ? p_eglGetCurrentDisplay() : NULL;
    void *prev_draw = p_eglGetCurrentSurface ? p_eglGetCurrentSurface(0x3059 /*DRAW*/) : NULL;
    void *prev_read = p_eglGetCurrentSurface ? p_eglGetCurrentSurface(0x305A /*READ*/) : NULL;

    if (!p_eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context)) {
        return false;
    }

    bool ok = false;
    /* 属性组合逐个试：EGL 的失败全是错误码，可以放心试探（并把错误码记下来） */
    const int attr_none[] = {0x3038 /*EGL_NONE*/, 0};
    const int attr_preserved[] = {PORTAL_EGL_IMAGE_PRESERVED_KHR, 1, 0x3038, 0};
    const int attr_unpreserved[] = {PORTAL_EGL_IMAGE_PRESERVED_KHR, 0, 0x3038, 0};
    const int *variants[3] = {attr_none, attr_preserved, attr_unpreserved};
    const char *variant_names[3] = {"none", "preserved=1", "preserved=0"};
    void *image = NULL;
    int last_err = 0;
    for (int v = 0; v < 3 && image == NULL; v++) {
        if (p_eglGetError) p_eglGetError(); /* 清掉历史错误 */
        image = p_eglCreateImageKHR(g_egl_display, NULL, PORTAL_EGL_NATIVE_BUFFER_ANDROID, handle,
                                    variants[v]);
        if (image == NULL && p_eglGetError) last_err = p_eglGetError();
        if (image != NULL) {
            static bool logged = false;
            if (!logged) {
                logged = true;
                LOGI("eglCreateImageKHR 成功（attrs=%s）—— 该 buffer 可作为 EGL 渲染目标",
                     variant_names[v]);
            }
        }
    }
    if (image == NULL) {
        static unsigned long n = 0;
        if (__atomic_add_fetch(&n, 1, __ATOMIC_RELAXED) == 1) {
            LOGE("eglCreateImageKHR 三种属性组合均失败（eglGetError=0x%x）→ 该流不注入", last_err);
        }
        goto restore;
    }
    {
        unsigned int tex = 0, fbo = 0;
        p_glGenTextures(1, &tex);
        p_glBindTexture(PORTAL_GL_TEXTURE_EXTERNAL_OES, tex);
        p_glTexParameteri(PORTAL_GL_TEXTURE_EXTERNAL_OES, 0x2801 /*MIN_FILTER*/, 0x2600 /*NEAREST*/);
        p_glTexParameteri(PORTAL_GL_TEXTURE_EXTERNAL_OES, 0x2800 /*MAG_FILTER*/, 0x2600);
        p_glEGLImageTargetTexture2DOES(PORTAL_GL_TEXTURE_EXTERNAL_OES, image);

        p_glGenFramebuffers(1, &fbo);
        p_glBindFramebuffer(PORTAL_GL_FRAMEBUFFER, fbo);
        p_glFramebufferTexture2D(PORTAL_GL_FRAMEBUFFER, PORTAL_GL_COLOR_ATTACHMENT0,
                                 PORTAL_GL_TEXTURE_EXTERNAL_OES, tex, 0);
        if (p_glCheckFramebufferStatus(PORTAL_GL_FRAMEBUFFER) == PORTAL_GL_FRAMEBUFFER_COMPLETE) {
            p_glViewport(0, 0, (int)w, (int)h);
            p_glDisable(0x0B71 /*GL_DEPTH_TEST*/);
            p_glDisable(0x0BE2 /*GL_BLEND*/);
            /* 底色 */
            p_glClearColor(0.06f, 0.06f, 0.06f, 1.0f);
            p_glClear(0x00004000 /*GL_COLOR_BUFFER_BIT*/);
            /* 横条：每 1/12 高度一条亮带，随时间上下移动 —— 一眼看出是我们画的 */
            int band = (int)(h / 12 > 4 ? h / 12 : 4);
            int offset = (int)((frame * 8u) % (uint32_t)(h + (uint32_t)band)) - band;
            p_glEnable(0x0C11 /*GL_SCISSOR_TEST*/);
            for (int i = -1; i < 12; i++) {
                int y = offset + i * band * 2;
                p_glScissor(0, y, (int)w, band);
                if (i % 2 == 0) {
                    p_glClearColor(0.92f, 0.92f, 0.20f, 1.0f);
                } else {
                    p_glClearColor(0.10f, 0.55f, 0.95f, 1.0f);
                }
                p_glClear(0x00004000);
            }
            p_glDisable(0x0C11);
            ok = true;
        } else {
            static unsigned long n = 0;
            if (__atomic_add_fetch(&n, 1, __ATOMIC_RELAXED) == 1) {
                LOGE("FBO 不完整（EGLImage 不能作为颜色附件）→ 该流不注入");
            }
        }
        p_glBindFramebuffer(PORTAL_GL_FRAMEBUFFER, 0);
        if (fbo) p_glDeleteFramebuffers(1, &fbo);
        if (tex) p_glDeleteTextures(1, &tex);
    }
    p_eglDestroyImageKHR(g_egl_display, image);

restore:
    /* 原样恢复调用线程的 EGL 状态 */
    p_eglMakeCurrent(prev_dpy, prev_draw, prev_read, prev_ctx);
    return ok;
}

/* ------------------------------------------------------------------ *
 * M1a：把合成图案写进输出 buffer 的 Y 平面
 *
 * 时序选在**回调方向**（HAL 填完 buffer、交给框架之前），而不是 process_capture_request
 * 里：那时 buffer 还在 HAL 手里、写入会与它的填充竞争。在回调里写，数据已就绪、
 * 应用还没看到 —— 这是唯一既安全又有效的时刻。
 * ------------------------------------------------------------------ */

/**
 * 我们的 initialize：HAL 会用我们给的这张回调表把每帧结果交回来。
 * 记下框架的原始回调表，转手把**我们的**回调表给 HAL —— 注入就发生在两者之间。
 */
static int portal_initialize(const struct camera3_device *dev,
                             const camera3_callback_ops_t *callback_ops) {
    g_framework_callbacks = callback_ops;
    LOGI("initialize 已被我们接管：框架回调已记录，注入点生效");
    if (g_real_initialize == NULL) return -1;
    return g_real_initialize(dev, &g_our_callbacks);
}

/**
 * 惰性加载 legacy gralloc 模块（C API）。失败返回 NULL，调用方退化为纯转发。
 *
 * `hw_get_module` 声明在 AOSP 的 `hardware/hardware.h` 里，**NDK 不带这个头**，
 * 所以这里自己声明签名并从 `libhardware.so` dlsym —— 不增加构建期依赖。
 * 该 .so 本来就在 provider 进程里（vendor HAL 自己就链接它），dlopen 只是取回同一个实例。
 */
typedef int (*portal_hw_get_module_fn)(const char *id, const struct hw_module_t **module);
typedef int (*portal_hw_get_module_by_class_fn)(const char *class_id, const char *inst_id,
                                                const struct hw_module_t **module);

static portal_gralloc_module_t *load_gralloc(void) {
    if (g_gralloc_tried) return g_gralloc;
    g_gralloc_tried = true;

    static portal_hw_get_module_fn get_module = NULL;
    static portal_hw_get_module_by_class_fn get_module_by_class = NULL;
    if (get_module == NULL) {
        void *hw = dlopen("libhardware.so", RTLD_NOW | RTLD_LOCAL);
        if (hw != NULL) {
            get_module = (portal_hw_get_module_fn)dlsym(hw, "hw_get_module");
            get_module_by_class =
                (portal_hw_get_module_by_class_fn)dlsym(hw, "hw_get_module_by_class");
        }
        if (get_module == NULL) {
            LOGE("libhardware.so 里没有 hw_get_module：M1a 注入不可用（相机行为不变）");
            return NULL;
        }
    }

    /* 两条路都试：默认选择的 gralloc 在 Android 15 上是 mapper 时代的 stub（没有 lock_ycbcr），
     * 而厂商模块（gralloc.msm8998.so）仍实现 legacy C API。哪个有 lock_ycbcr 用哪个。 */
    struct hw_module_t *candidates[2] = {NULL, NULL};
    const char *labels[2] = {"默认", "按类名"};
    if (get_module(PORTAL_GRALLOC_HARDWARE_MODULE_ID,
                   (const struct hw_module_t **)&candidates[0]) != 0) {
        candidates[0] = NULL;
    }
    if (get_module_by_class != NULL &&
        get_module_by_class(PORTAL_GRALLOC_HARDWARE_MODULE_ID, "msm8998",
                            (const struct hw_module_t **)&candidates[1]) != 0) {
        candidates[1] = NULL;
    }

    for (int i = 0; i < 2; i++) {
        struct hw_module_t *m = candidates[i];
        if (m == NULL) continue;
        portal_gralloc_module_t *gm = (portal_gralloc_module_t *)m;
        LOGI("gralloc[%s] name=%s lock=%p lock_ycbcr=%p perform=%p", labels[i],
             m->name ? m->name : "?", (void *)gm->lock, (void *)gm->lock_ycbcr,
             (void *)gm->perform);
        if (g_gralloc == NULL && gm->lock_ycbcr != NULL) {
            g_gralloc = gm;
            LOGI("采用 gralloc[%s] 的 lock_ycbcr（可拿到各平面指针与 stride）", labels[i]);
        }
    }
    if (g_gralloc == NULL) {
        LOGI("没有 legacy gralloc 的 lock_ycbcr，改走 libui 的 GraphicBufferMapper::lock（只写开头几行）");
    }
    return g_gralloc;
}

/**
 * usage 里有没有 SW 写位 —— **这是能不能 CPU 映射的判据，也是保命门**。
 *
 * 实测教训：对 HW-only 的相机输出流（实测 usage=0x10020900，一个 SW 位都没有）调用
 * `GraphicBufferMapper::lock`，mapper 内部会直接 abort：
 *   #00 libui.so GraphicBufferMapper::lock(native_handle const*, long long, Rect const&, unique_fd&&)
 *   #02 camera.msm8998.so inject_via_libui
 * ⇒ provider 当场死、相机整机不可用。所以这道门必须留在**调用之前**。
 */
static bool portal_cpu_writable(const camera3_stream_t *st) {
    if (st == NULL) return false;
    return (st->usage & PORTAL_GRALLOC_USAGE_SW_WRITE_MASK) != 0;
}

/* 给输出流补的 SW 用途位（0x30 = SW_WRITE_OFTEN，0x03 = SW_READ_OFTEN） */
#define PORTAL_ADD_SW_USAGE 0x00000033u

/**
 * QTI 的 `GRALLOC_USAGE_PRIVATE_0`：**UBWC 分配**（平铺 + 压缩）。
 *
 * 本机实测：应用预览流的最终 usage 是 `0x10020900` —— 其中 `0x10000000` 就是这一位，
 * 而它是**厂商 HAL** 在 configure_streams 里后加的（框架进来时只有 0x900）。
 * UBWC 的像素不是线性排布的，所以"按线性 stride 写入"会让读取方看到**平铺的小方块阵列**
 * （实测：注入后真实场景确实没了，但图案形态是规则碎块而不是我们写的渐变）。
 * `usage_patch=2` 在真 HAL 之后清掉这一位，让分配退回线性 —— 这是"能写对"的前提。
 */
#define PORTAL_GRALLOC_USAGE_PRIVATE_0 0x10000000u

/**
 * `GRALLOC_USAGE_HW_VIDEO_ENCODER`：**录像用的编码器流，必须完全不碰**。
 *
 * 实测教训：录像会话里编码器流是 `format=0x22 1920x1080 usage=0x10030022`（含 0x10000）。
 * 我们按"非 BLOB 就清 UBWC"的规则清了它 —— 结果**录到的画面是平铺噪声**：
 * 编码器（Venus）按 UBWC 布局读这块内存，清掉 UBWC 之后它读到的就是垃圾。
 * 也就是说"补位 + 注入"会把**录像内容弄坏**，比不接管更糟。所以这条流一律跳过：
 * 不补位、不清 UBWC、不注入 ⇒ 录像内容退回**真实相机**（已知边界，见 docs §15.8）。
 */
#define PORTAL_GRALLOC_USAGE_HW_VIDEO_ENCODER 0x00010000u

/**
 * 是否在 configure_streams 里改输出流的用途位（运行期开关 `usage_patch`）。
 *
 * camera3 的既定机制：HAL 可以在 `configure_streams` 里改 `camera3_stream_t::usage`，
 * 框架会把 HAL 的位并进去并**按新 usage 重新分配 buffer** —— 这正是让"硬件写的相机流"
 * 变成"我们也能 CPU 写"的正规做法（否则只能眼巴巴看着 HW-only 的 buffer）。
 *
 * 取值：0 = 不动；1 = 只补 SW 读写位；2 = 补 SW 位**并清 UBWC 位**（写整幅必需）。
 *
 * 实测依据：本机相机输出流的 usage 里一个 SW 位都没有，vcam 之所以能用
 * `GraphicBufferMapper::lock()` 写进去，正是因为**它自己是 HAL、自己申报了 SW 位**
 * （docs/camera-mock.md §5.4：lock 对无 SW 位的 buffer 是 abort，打死过 provider 三次）。
 *
 * **改位必须排在真 HAL 之后**：框架进来时是 0x900，UBWC / HW_CAMERA_WRITE 这些位是厂商 HAL
 * 加的，先改会被它覆盖掉；框架在 configure_streams 返回后回读整张表（我们的 SW 位就是这么生效的）。
 */
static int (*g_real_configure_streams)(const struct camera3_device *, void *) = NULL;

/* 映射缓存整表清空（定义在下方写帧段；新会话必须失效，见 portal_configure_streams） */
static void portal_invalidate_maps(void);

/* 等 acquire_fence（定义在下方拍照片段；写 YUV 槽位时也要用） */
static void portal_wait_fence(int fd);

/* 帧文件侧的两个前向声明（定义在下方外源帧段；configure_streams 要提前问"产帧端活着吗"） */
static bool portal_fsrc_open(void);
static bool portal_fsrc_alive(void);
static void portal_fsrc_set_photo_req(uint32_t w, uint32_t h);
static void portal_fsrc_signal_photo(uint32_t w, uint32_t h);

static void portal_scrub_streams(void *stream_list) {
    /* camera3_stream_configuration_t { uint32_t num_streams; camera3_stream_t **streams;
     *                                   uint32_t operation_mode; … } */
    struct {
        uint32_t num_streams;
        camera3_stream_t **streams;
    } *cfg = (void *)stream_list;
    if (cfg->streams == NULL || cfg->num_streams > 16u) return;
    for (uint32_t i = 0; i < cfg->num_streams; i++) {
        camera3_stream_t *st = cfg->streams[i];
        if (st == NULL || st->stream_type != 0 /* OUTPUT */) continue;
        /* JPEG/BLOB（0x21）与 RAW（0x20/0x25）不动：它们的 buffer 由硬件/编码器写，
         * 补 SW 位不但没用，还会改掉分配路径把一次拍照弄坏。 */
        int f = st->format;
        /* RAW（0x20/0x25）不动：模拟 RAW 没有意义，改它的分配只会把拍照弄坏 */
        if (f == 0x20 || f == 0x25) continue;
        /* 录像编码器流也不动：清了 UBWC 会让编码器读到平铺噪声（实测），把录像内容弄坏 */
        if ((st->usage & PORTAL_GRALLOC_USAGE_HW_VIDEO_ENCODER) != 0) {
            LOGI("视频编码器流（format=0x%x %ux%u usage=0x%x）不动：编码器按 UBWC 读",
                 st->format, st->width, st->height, st->usage);
            continue;
        }
        uint32_t before = st->usage;
        st->usage |= PORTAL_ADD_SW_USAGE;
        /* BLOB（0x21）是线性字节块，没有平铺问题 ⇒ 只补写位、不动 UBWC；
         * 补写位是"照片也要吃虚拟帧"的前提（否则 CPU 写 BLOB buffer 会被拒） */
        if (f != 0x21 && g_opt_usage_patch >= 2) {
            st->usage &= ~PORTAL_GRALLOC_USAGE_PRIVATE_0;
        }
        if (f == 0x21) portal_fsrc_set_photo_req(st->width, st->height);
        LOGI("改用途位[%u]：format=0x%x %ux%u usage 0x%x → 0x%x（UBWC %s）", i, st->format,
             st->width, st->height, before, st->usage,
             (g_opt_usage_patch >= 2) ? "已清" : "保留");
    }
}

/**
 * 产帧端现在活着吗（`enabled=1` 且心跳新鲜）。
 *
 * **为什么 configure_streams 要看它**：清 UBWC 位是"让 buffer 能被 CPU 正确写入"的前提，
 * 但它同时**破坏真实相机的落图路径** —— ISP 仍按 UBWC 平铺写进线性 buffer，读出来就是一片
 * 绿噪声（实测：app 被杀后预览变成规则噪声，而不是真实场景）。
 * 所以补位必须**按会话判定**：只有产帧端在跑时才改分配，否则保持厂商原始行为，
 * 这样"关掉模拟/产帧端挂了"留下的仍是一台**能正常拍照**的手机。
 *
 * 图案模式（没配 src_file）是研究/演示路径，一直补位。
 */
static bool portal_should_patch_usage(void) {
    if (g_opt_src_file[0] == '\0') return true; /* 图案模式：一直补位（研究/演示路径） */
    return portal_fsrc_alive();
}

static int portal_configure_streams(const struct camera3_device *dev, void *stream_list) {
    if (g_real_configure_streams == NULL) return -1;
    /* 先让真 HAL 把它的位（UBWC / HW_CAMERA_WRITE）加完，再改我们那几位 */
    int rc = g_real_configure_streams(dev, stream_list);
    /* 新会话 ⇒ 新 buffer ⇒ 映射缓存整表失效 */
    portal_invalidate_maps();
    if (rc == 0 && stream_list != NULL && g_opt_usage_patch && portal_cam_selected()) {
        if (portal_should_patch_usage()) {
            portal_scrub_streams(stream_list);
        } else {
            LOGI("产帧端未在运行 ⇒ 本次不改用途位（真实相机走厂商原始分配路径）");
        }
    }
    return rc;
}

/** 合成图案：默认「移动竖条 + 横向渐变」；pattern=1/2 是探针用的纯横向/纵向渐变 */
static inline uint8_t portal_pattern(int x, int y, int w, int h, uint32_t frame) {
    if (g_opt_pattern == 1) {
        /* 横向渐变：若布局正确，画面应是一条平滑的亮暗带（配合旋转也只是方向变） */
        return (uint8_t)((x * 255) / (w > 0 ? w : 1));
    }
    if (g_opt_pattern == 2) {
        return (uint8_t)((y * 255) / (h > 0 ? h : 1));
    }
    if (g_opt_pattern == 3) {
        return 128; /* 纯灰：看色度是否中性、有无平铺碎块 */
    }
    int bar = (int)((frame * 12u) % (uint32_t)(w > 0 ? w : 1));
    int dx = x - bar;
    if (dx < 0) dx = -dx;
    if (dx < (w / 12 > 8 ? w / 12 : 8)) return 235;      /* 竖条：亮 */
    int g = (x * 200) / (w > 0 ? w : 1);                  /* 横向渐变 */
    int shade = (y & 32) ? 8 : 0;                         /* 细横纹，便于看出帧在动 */
    int v = 16 + g / 2 + shade;
    return (uint8_t)(v > 235 ? 235 : v);
}

/**
 * 写帧阶梯（每一级失败都只是返回 false，绝不给 HAL 留下坏状态）。
 *
 * 保命门在调用方 [inject_into_buffer]：**流的 usage 必须有 SW 写位**。
 * 这是唯一被实测证明能区分"lock 可用"与"lock 直接 abort 掉 provider"的判据
 * （无 SW 位的 buffer 上调用 mapper lock ⇒ abort，打死过 provider 三次）。
 *
 *   ① `lockYCbCr`：同时拿到 Y/Cb/Cr 指针与 stride ⇒ 写整幅，还能把色度抹成中性。
 *   ② `lock()`：只有基址、没有 stride ⇒ 只写"开头有限行"，用 width 当步长上界，
 *      写入区间 [0, rows*width) 必然落在平面内，**不会越界**；代价是画面斜切。
 */
#define PORTAL_SAFE_ROWS 64

/** 限流日志：同一处只打一次（这几条只在注入试探期出现，不影响稳态） */
#define PORTAL_LOG_ONCE(level, ...) \
    do {                            \
        static bool logged_ = false; \
        if (!logged_) {             \
            logged_ = true;         \
            level(__VA_ARGS__);     \
        }                           \
    } while (0)

/**
 * 帧模板：整幅只在**内容变化时**算一次，之后逐行 `memcpy`。
 *
 * 为什么必须这样：最初逐像素调 [portal_pattern]（内含一次除法）直接写目标内存，
 * 实测 **123.77 ms/帧** —— 1600×1200 的 1.92M 像素上，除法叠加"对不可缓存的 gralloc
 * 内存逐字节写"，把相机压到 8 fps 以下（对照：同一暗场景传感器本身约 12 fps）。
 * 改成"模板 + 逐行 memcpy"后代价退化为纯内存带宽。
 *
 * 真实视频帧进来时走的就是同一条路：源帧逐行 memcpy 进相机 buffer（两侧 stride 可不同）。
 */
static uint8_t *g_tpl = NULL;
static uint32_t g_tpl_w = 0, g_tpl_h = 0;
static uint32_t g_tpl_frame = 0xFFFFFFFFu;
static uint8_t *g_grad = NULL;
static uint32_t g_grad_w = 0;
/* 动态图案的静态底图：渐变+横纹只算一次，每帧只把底图拷过来再画竖条
 * （实测：逐像素重建底图要 48 ms/帧 —— 比真正写进相机 buffer 的 3 ms 贵 16 倍） */
static uint8_t *g_base = NULL;
static uint32_t g_base_frame = 0;

/** 横向渐变查表（每像素一次加法，取代除法） */
static void portal_build_grad(uint32_t w) {
    if (g_grad != NULL && g_grad_w == w) return;
    free(g_grad);
    g_grad = (uint8_t *)malloc(w);
    g_grad_w = w;
    if (g_grad == NULL) {
        g_grad_w = 0;
        return;
    }
    for (uint32_t x = 0; x < w; x++) g_grad[x] = (uint8_t)((x * 200u) / w);
}

static const uint8_t *portal_template(uint32_t w, uint32_t h, uint32_t frame) {
    if (g_tpl == NULL || g_tpl_w != w || g_tpl_h != h) {
        free(g_tpl);
        free(g_base);
        g_tpl = (uint8_t *)malloc((size_t)w * h);
        g_base = (uint8_t *)malloc((size_t)w * h);
        g_tpl_w = w;
        g_tpl_h = h;
        g_tpl_frame = 0xFFFFFFFFu;
        if (g_tpl == NULL || g_base == NULL) {
            g_tpl_w = g_tpl_h = 0;
            return NULL;
        }
        portal_build_grad(w);
    }
    if (g_tpl_frame == frame || g_tpl_frame == 0xFFFFFFFEu) return g_tpl; /* 后者=静态图案 */
    if (g_grad == NULL && (g_opt_pattern == 0 || g_opt_pattern == 1)) return NULL;
    g_tpl_frame = frame;

    if (g_opt_pattern == 3) {
        memset(g_tpl, 128, (size_t)w * h);
        g_tpl_frame = 0xFFFFFFFEu; /* 静态：与帧号无关，不再重建 */
        return g_tpl;
    }
    if (g_opt_pattern == 2) {
        for (uint32_t r = 0; r < h; r++) {
            memset(g_tpl + (size_t)r * w, (int)((r * 255u) / h), w);
        }
        g_tpl_frame = 0xFFFFFFFEu;
        return g_tpl;
    }
    if (g_opt_pattern == 1) {
        for (uint32_t r = 0; r < h; r++) memcpy(g_tpl + (size_t)r * w, g_grad, w);
        g_tpl_frame = 0xFFFFFFFEu;
        return g_tpl;
    }
    /* 默认：横向渐变 + 细横纹 + 移动竖条 */
    if (g_base_frame != 0xFFFFFFFEu) {
        for (uint32_t r = 0; r < h; r++) {
            uint8_t *row = g_base + (size_t)r * w;
            uint8_t shade = (r & 32) ? 8 : 0;
            for (uint32_t x = 0; x < w; x++) {
                uint8_t v = (uint8_t)(16 + g_grad[x] / 2 + shade);
                row[x] = (v > 235) ? 235 : v;
            }
        }
        g_base_frame = 0xFFFFFFFEu;
    }
    uint32_t bar = (frame * 12u) % (w > 0 ? w : 1);
    uint32_t bar_half = (w / 12 > 8 ? w / 12 : 8);
    uint32_t x0 = (bar > bar_half) ? bar - bar_half : 0;
    uint32_t x1 = bar + bar_half;
    if (x1 > w) x1 = w;
    for (uint32_t r = 0; r < h; r++) {
        uint8_t *row = g_tpl + (size_t)r * w;
        memcpy(row, g_base + (size_t)r * w, w);
        if (x1 > x0) memset(row + x0, 235, x1 - x0);
    }
    return g_tpl;
}

/* ------------------------------------------------------------------ *
 * 外源帧（"帧文件"）：真实视频像素进 HAL 的通道
 *
 * 为什么用**文件**而不是 socket/SCM_RIGHTS：HAL 只读得到它有权读的路径，app 只写得进自己的目录；
 * 两边都能碰的只有一个"约定格式的文件"。让 app 把解码结果按下面的头写进文件、HAL mmap 只读，
 * 是三边（SELinux / 依赖 / 代码量）成本最低的做法，也不需要把编解码器搬进 HAL 进程。
 *
 * 头 64 字节，槽位数据从 4096 起；每槽 slot_bytes。取帧两种模式：
 *   · `flags & 1`（HAL 自行循环）：按相机帧序轮转槽位 —— 离线验证/演示用，不需要 app；
 *   · 否则读 volatile `seq`/`write_idx`：生产者"先写数据、再更新 write_idx、最后 seq++"，
 *     消费者只在 seq 变化时换槽 ⇒ 永远读已写完的槽（多缓冲无锁；没新帧就重发上一帧，
 *     **绝不能"没新帧就不写"**，否则画面会在真图与假图之间闪）。
 * 头里的 `enabled=0` 是生产者的总开关：HAL 见到就完全不接管（露出真实相机）。
 * ------------------------------------------------------------------ */

#define PORTAL_FRAME_MAGIC 0x46584550u /* 'PEXF' */
#define PORTAL_FRAME_HDR_BYTES 4096u
#define PORTAL_FRAME_FLAG_CYCLE 1u

struct portal_frame_hdr {
    uint32_t magic, version, width, height, y_stride, uv_stride, format, slot_bytes;
    uint32_t slots, enabled, seq, write_idx, flags, reserved;
    uint64_t timestamp_ns;
    /* 以下字段由**消费端（HAL）**回写：app 据此把解码器缩放到相机流真实的几何 */
    uint32_t consumer_w, consumer_h, producer_state, reserved2;
    /*
     * 照片区（拍照 = JPEG/BLOB 流，2592×1944 实测）：由 **app 编码**、HAL 只搬运。
     * 为什么不在这层编码：libjpeg 的 `jpeg_compress_struct` 是版本相关的 ABI，
     * 在 HAL 里自带头文件等于赌版本；而 app 侧 `YuvImage.compressToJpeg` 是现成的。
     * 负载位置 = HDR + slots*slot_bytes（紧接亮度槽位之后），长度 photo_bytes。
     */
    uint32_t photo_w, photo_h, photo_bytes, photo_seq;
    /* 消费端（HAL）回写：**静态流（BLOB）请求的几何** —— app 据此把照片编码到这个尺寸，
     * 而不是沿用解码帧自己的 1920×1080（实测相机请求的是 2592×1944） */
    uint32_t photo_req_w, photo_req_h;
    /* 消费端（HAL）回写的**拍照请求序号**：HAL 在 `process_capture_request` 里发现 BLOB 流
     * 就 +1，通知 app"现在就编一张"（按需编码，避免周期性 5 MP 重活拖慢热路径） */
    uint32_t photo_req_seq;
};

static int g_fsrc_fd = -1;
static uint8_t *g_fsrc_map = NULL;
static size_t g_fsrc_len = 0;
static const uint8_t *g_fsrc_cur = NULL; /* 当前使用的槽位（没新帧就沿用） */
static uint32_t g_fsrc_sw = 0, g_fsrc_sh = 0, g_fsrc_sy = 0, g_fsrc_suv = 0;
static uint32_t g_fsrc_seq = 0;
static uint32_t g_fsrc_cycle = 0;
/** 共享映射是否可写（只读时就不回写 consumer 尺寸） */
static bool g_fsrc_writable = false;

static bool portal_fsrc_open(void) {
    if (g_fsrc_map != NULL) return true;
    if (g_opt_src_file[0] == '\0') return false;
    /*
     * **必须 O_RDWR**：下面要用 PROT_WRITE 映射（HAL 要回写 `consumer_w/h`、`photo_req_w/h`）。
     * 实测踩过：O_RDONLY + PROT_WRITE 的 mmap 直接 EACCES ⇒ 静默退回只读映射 ⇒
     * 几何回写全部失效（app 只能按解码帧自己的尺寸编码照片）。
     * 拿不到写权限时退回 O_RDONLY（只读模式下功能仍然可用，只是不回写几何）。
     */
    int fd = open(g_opt_src_file, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        fd = open(g_opt_src_file, O_RDONLY | O_CLOEXEC);
    }
    if (fd < 0) {
        PORTAL_LOG_ONCE(LOGE, "帧文件打不开：%s（errno=%d）→ 本流继续用图案", g_opt_src_file, errno);
        return false;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || (size_t)st.st_size < PORTAL_FRAME_HDR_BYTES) {
        PORTAL_LOG_ONCE(LOGE, "帧文件太小或取不到大小：%s", g_opt_src_file);
        close(fd);
        return false;
    }
    /* 先试读写映射（要回写 consumer 尺寸）；SELinux 不给写权限时退回只读 */
    void *m = mmap(NULL, (size_t)st.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    bool writable = true;
    if (m == MAP_FAILED) {
        writable = false;
        m = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    }
    if (m == MAP_FAILED) {
        PORTAL_LOG_ONCE(LOGE, "帧文件 mmap 失败（errno=%d）", errno);
        close(fd);
        return false;
    }
    g_fsrc_writable = writable;
    const struct portal_frame_hdr *h = (const struct portal_frame_hdr *)m;
    if (h->magic != PORTAL_FRAME_MAGIC || h->version != 1) {
        PORTAL_LOG_ONCE(LOGE, "帧文件魔数/版本不对（0x%08x v%u）→ 不接管", h->magic, h->version);
        munmap(m, (size_t)st.st_size);
        close(fd);
        return false;
    }
    g_fsrc_fd = fd;
    g_fsrc_map = (uint8_t *)m;
    g_fsrc_len = (size_t)st.st_size;
    LOGI("帧文件已挂上：%s（%ux%u stride=%u/%u 槽=%u 槽字节=%u 总长=%zu flags=%u %s）",
         g_opt_src_file, h->width, h->height, h->y_stride, h->uv_stride, h->slots, h->slot_bytes,
         g_fsrc_len, h->flags, writable ? "RW" : "RO");
    return true;
}

/**
 * 产帧端现在活着吗（`enabled=1` 且心跳新鲜）。
 *
 * **configure_streams 为什么要问它**：清 UBWC 位是"让 buffer 能被 CPU 正确写入"的前提，
 * 但它同时**破坏真实相机的落图路径** —— ISP 仍按 UBWC 平铺写进线性 buffer，读出来就是一片
 * 绿噪声（实测：app 被杀后预览变规则噪声，而不是真实场景）。所以补位必须**按会话判定**：
 * 只有产帧端在跑时才改分配；否则保持厂商原始行为，让"关掉模拟/产帧端挂了"之后
 * 留下的仍是一台能正常拍照的手机。
 */
static bool portal_fsrc_alive(void) {
    if (!portal_fsrc_open()) return false;
    const struct portal_frame_hdr *h = (const struct portal_frame_hdr *)g_fsrc_map;
    if (h->magic != PORTAL_FRAME_MAGIC || h->version != 1) return false;
    if (h->enabled == 0) return false;
    uint64_t ts = h->timestamp_ns;
    if (ts == 0) return false;
    struct timespec nw;
    clock_gettime(CLOCK_MONOTONIC, &nw);
    uint64_t now_ns = (uint64_t)nw.tv_sec * 1000000000ull + (uint64_t)nw.tv_nsec;
    return !(now_ns > ts && now_ns - ts > 5000000000ull);
}

/** 把静态流（拍照）请求的几何回写给 app：照片要按这个尺寸编码，而不是解码帧自己的尺寸 */
static void portal_fsrc_set_photo_req(uint32_t w, uint32_t h) {
    if (!g_fsrc_writable || g_fsrc_map == NULL) return;
    struct portal_frame_hdr *hdr = (struct portal_frame_hdr *)g_fsrc_map;
    /* 照片变换标志（bit0=水平镜像）：告诉 app 编码前要不要翻转 */
    uint32_t want_flags = (g_opt_photo_mirror ? 1u : 0u);
    if (hdr->reserved2 != want_flags) {
        hdr->reserved2 = want_flags;
        LOGI("照片变换标志回写：0x%x（bit0=水平镜像）", want_flags);
    }
    if (hdr->photo_req_w == w && hdr->photo_req_h == h) return;
    hdr->photo_req_w = w;
    hdr->photo_req_h = h;
    LOGI("静态流几何回写：%ux%u（app 据此编码照片）", w, h);
}

/** 拍照前的照片序号：HAL 用它判断"app 有没有为这次拍照编出新的一张" */
static uint32_t g_photo_seq_at_request = 0;

/**
 * 通知 app：**现在就编一张照片**（在 `process_capture_request` 阶段，比 BLOB 结果早
 * 一百多毫秒 —— vendor 自己编那张 JPEG 也要这么久，正好并行）。
 *
 * 为什么要按需：原来 app 每 1.5 s 无条件编一张 5 MP（缩放 + JPEG ≈ 200–300 ms 重活），
 * 实测把 HAL 的写帧从 17 ms 顶到 43–48 ms（分段日志里亮度/色度成对出现尖峰 = 抢带宽）。
 * 改成"拍照才编"之后，热路径不再被周期性重活污染。
 */
static void portal_fsrc_signal_photo(uint32_t w, uint32_t h) {
    if (!g_fsrc_writable || g_fsrc_map == NULL) return;
    struct portal_frame_hdr *hdr = (struct portal_frame_hdr *)g_fsrc_map;
    if (hdr->photo_req_w != w || hdr->photo_req_h != h) {
        hdr->photo_req_w = w;
        hdr->photo_req_h = h;
    }
    g_photo_seq_at_request = hdr->photo_seq;
    hdr->photo_req_seq++;
    LOGI("拍照请求：已通知 app 编码 %ux%u（req_seq=%u）", w, h, hdr->photo_req_seq);
}

/** 取当前该用的源槽；没有可用帧返回 NULL */
static const uint8_t *portal_fsrc_pick(void) {
    if (!portal_fsrc_open()) return NULL;
    const struct portal_frame_hdr *h = (const struct portal_frame_hdr *)g_fsrc_map;
    if (h->enabled == 0) return NULL; /* 生产者要求不接管 */
    uint32_t slots = h->slots, slot_bytes = h->slot_bytes;
    if (slots == 0 || slots > 256 || slot_bytes < 4096) return NULL;
    if (PORTAL_FRAME_HDR_BYTES + (size_t)slots * slot_bytes > g_fsrc_len) return NULL;

    /*
     * **生产者心跳**：非轮转模式下，生产者每发布一帧都会写 timestamp_ns（CLOCK_MONOTONIC）。
     * 5 秒没动静就当作"生产者没了"（app 被杀、被系统回收、用户退出）——
     * 否则 HAL 会一直重发最后一帧，应用看到一张**冻结的假画面**，比不接管更糟。
     */
    if (!(h->flags & PORTAL_FRAME_FLAG_CYCLE)) {
        uint64_t ts = h->timestamp_ns;
        if (ts == 0) return NULL;
        struct timespec nw;
        clock_gettime(CLOCK_MONOTONIC, &nw);
        uint64_t now_ns = (uint64_t)nw.tv_sec * 1000000000ull + (uint64_t)nw.tv_nsec;
        if (now_ns > ts && now_ns - ts > 5000000000ull) return NULL;
    }

    uint32_t idx;
    if (h->flags & PORTAL_FRAME_FLAG_CYCLE) {
        idx = g_fsrc_cycle++ % slots;
    } else {
        uint32_t seq = h->seq;
        if (seq != g_fsrc_seq) {
            g_fsrc_seq = seq;
            idx = h->write_idx;
            if (idx >= slots) idx = 0;
        } else if (g_fsrc_cur != NULL) {
            return g_fsrc_cur; /* 没有新帧：重发上一帧，避免真假画面交替闪 */
        } else {
            return NULL;
        }
    }
    g_fsrc_sw = h->width;
    g_fsrc_sh = h->height;
    g_fsrc_sy = h->y_stride;
    g_fsrc_suv = h->uv_stride;
    g_fsrc_cur = g_fsrc_map + PORTAL_FRAME_HDR_BYTES + (size_t)idx * slot_bytes;
    return g_fsrc_cur;
}

/* ------------------------------------------------------------------ *
 * 缩放映射（源几何 ≠ 流几何时用）
 *
 * 为什么缩放落在 HAL 而不是 app：**MediaCodec 会把 Surface 的 buffer 尺寸设回视频自身尺寸**，
 * 所以"把 ImageReader 建成相机流尺寸、让解码器顺手缩放"行不通（实测图像仍是 1920x1080）。
 * 与其在 app 侧再搭一套 GL 管线，不如在这里做逐行查表的最近邻缩放：目标是"每行一次
 * 连续访存"，而不是逐像素的分支计算。
 *
 * 取景方式：**等比缩放后居中裁剪**（cover）—— 相机会话里出现黑边比裁掉边缘更违和，
 * 与模块侧 `CameraVideoPlayer` 的 fit-center 一致。
 * ------------------------------------------------------------------ */

struct portal_scale_maps {
    uint32_t *rows; /* 目标行 → 源行 */
    uint32_t *cols; /* 目标列 → 源列 */
    uint32_t sw, sh, dw, dh;
};
/*
 * **两份缓存**（0=亮度，1=色度）。踩过的坑：一开始只做一份，而亮度用 (sw,sh→w,h)、
 * 色度用 (sw/2,sh/2→w/2,h/2) —— 每帧两次调用互相作废对方的缓存，
 * 于是**每帧都重建两张映射表**（日志里 `缩放映射已建立` 每帧刷屏就是证据）。
 */
static struct portal_scale_maps g_scale[2];

static bool portal_build_maps(int slot, uint32_t sw, uint32_t sh, uint32_t dw, uint32_t dh) {
    struct portal_scale_maps *m = &g_scale[slot & 1];
    if (m->rows != NULL && m->sw == sw && m->sh == sh && m->dw == dw && m->dh == dh) {
        return true;
    }
    free(m->rows);
    free(m->cols);
    m->rows = NULL;
    m->cols = NULL;
    if (sw == 0 || sh == 0 || dw == 0 || dh == 0) return false;

    uint32_t *rows = (uint32_t *)malloc(sizeof(uint32_t) * dh);
    uint32_t *cols = (uint32_t *)malloc(sizeof(uint32_t) * dw);
    if (rows == NULL || cols == NULL) {
        free(rows);
        free(cols);
        return false;
    }
    (void)0;
    /* cover：取较大的放大倍率（宽主导 / 高主导），另一维居中裁剪 */
    if ((uint64_t)dw * sh >= (uint64_t)dh * sw) {
        for (uint32_t dx = 0; dx < dw; dx++) {
            uint32_t sx = (uint32_t)((uint64_t)dx * sw / dw);
            cols[dx] = (sx < sw) ? sx : sw - 1;
        }
        uint32_t visible = (uint32_t)((uint64_t)dh * sw / dw);
        if (visible == 0) visible = 1;
        uint32_t y0 = (sh > visible) ? (sh - visible) / 2 : 0;
        for (uint32_t dy = 0; dy < dh; dy++) {
            uint32_t sy = y0 + (uint32_t)((uint64_t)dy * sw / dw);
            rows[dy] = (sy < sh) ? sy : sh - 1;
        }
    } else {
        for (uint32_t dy = 0; dy < dh; dy++) {
            uint32_t sy = (uint32_t)((uint64_t)dy * sh / dh);
            rows[dy] = (sy < sh) ? sy : sh - 1;
        }
        uint32_t visible = (uint32_t)((uint64_t)dw * sh / dh);
        if (visible == 0) visible = 1;
        uint32_t x0 = (sw > visible) ? (sw - visible) / 2 : 0;
        for (uint32_t dx = 0; dx < dw; dx++) {
            uint32_t sx = x0 + (uint32_t)((uint64_t)dx * sh / dh);
            cols[dx] = (sx < sw) ? sx : sw - 1;
        }
    }
    m->rows = rows;
    m->cols = cols;
    m->sw = sw;
    m->sh = sh;
    m->dw = dw;
    m->dh = dh;
    LOGI("缩放映射已建立[%s]：%ux%u → %ux%u（cover + 居中裁剪）", (slot & 1) ? "色度" : "亮度", sw,
         sh, dw, dh);
    return true;
}

/**
 * 把外源帧拷进目标平面。几何/布局不一致时**只拷重叠区**（左上对齐）并记一条，
 * 不做缩放 —— 缩放应该由 app 侧的解码器在 Surface 上完成（那是免费的 GPU 缩放）。
 */
static bool portal_copy_external(uint8_t *dy, size_t dystride, uint8_t *dcb, uint8_t *dcr,
                                 size_t dcstride, size_t dstep, uint32_t w, uint32_t h) {
    const uint8_t *src = portal_fsrc_pick();
    struct portal_frame_hdr *hdr = (struct portal_frame_hdr *)g_fsrc_map;
    if (src == NULL || hdr == NULL) return false;
    uint32_t sw = hdr->width, sh = hdr->height, sy = hdr->y_stride, suv = hdr->uv_stride;
    if (sw == 0 || sh == 0 || sy < sw) return false;
    if (sw != w || sh != h) {
        PORTAL_LOG_ONCE(LOGI, "源帧 %ux%u 与流 %ux%u 不同：在 HAL 内做 cover 缩放", sw, sh, w, h);
    }
    /* 回写消费端（HAL 自己的流）尺寸：app 侧可据此做提示/自适应（缩放由这里负责） */
    if (g_fsrc_writable && (hdr->consumer_w != w || hdr->consumer_h != h)) {
        hdr->consumer_w = w;
        hdr->consumer_h = h;
    }

    /* 色度布局先判：目标必须是 NV12/NV21 交织（cb/cr 相邻、step=2） */
    bool interleaved = (dstep == 2 && (dcr == dcb + 1 || dcb == dcr + 1));
    const uint8_t *suvp = src + (size_t)sy * sh;

    if (sw == w && sh == h) {
        /* 几何一致：最快路径，整行 memcpy */
        for (uint32_t r = 0; r < h; r++) {
            memcpy(dy + (size_t)r * dystride, src + (size_t)r * sy, w);
        }
        if (dcb == NULL || dcstride == 0 || suv == 0 || !interleaved) return true;
        for (uint32_t r = 0; r < (h + 1) / 2; r++) {
            memcpy(dcb + (size_t)r * dcstride, suvp + (size_t)r * suv, w);
        }
        return true;
    }

    struct timespec ty0;
    clock_gettime(CLOCK_MONOTONIC, &ty0);
    if (!portal_build_maps(0, sw, sh, w, h)) return false;
    const uint32_t *map_rows = g_scale[0].rows;
    const uint32_t *map_cols = g_scale[0].cols;
    /* 亮度：逐行按列映射取源字节（查表，无分支计算） */
    /*
     * **4 字节成组**取源、一次 32 位存目标：逐字节 gather 在这颗 SoC 上要 ~46 ms/帧
     * （1600×1200 目标、源 1920×1080），吃掉一半帧率。成组之后访存次数降到 1/4。
     * 列映射已预计算，且目标行 4 字节对齐（stride 为 16 对齐），所以可以直接 32 位存。
     */
    for (uint32_t r = 0; r < h; r++) {
        const uint8_t *srow = src + (size_t)map_rows[r] * sy;
        uint8_t *drow = dy + (size_t)r * dystride;
        uint32_t x = 0;
        for (; x + 4 <= w; x += 4) {
            uint32_t v = (uint32_t)srow[map_cols[x]] | ((uint32_t)srow[map_cols[x + 1]] << 8) |
                         ((uint32_t)srow[map_cols[x + 2]] << 16) |
                         ((uint32_t)srow[map_cols[x + 3]] << 24);
            memcpy(drow + x, &v, 4);
        }
        for (; x < w; x++) drow[x] = srow[map_cols[x]];
    }
    double y_ms = portal_seconds_since(&ty0) * 1000.0;
    if (dcb == NULL || dcstride == 0 || suv == 0) return true;
    if (!interleaved) {
        PORTAL_LOG_ONCE(LOGE, "目标色度不是 NV12/NV21 交织布局（step=%zu cb=%p cr=%p）→ 只写亮度",
                        dstep, dcb, dcr);
        return true;
    }
    /* 色度：半分辨率，每元素 2 字节（U,V）一起搬，省一半访存 */
    struct timespec tu0;
    clock_gettime(CLOCK_MONOTONIC, &tu0);
    uint32_t cw = w / 2, ch = h / 2;
    if (!portal_build_maps(1, sw / 2, sh / 2, cw, ch)) return true;
    const uint32_t *cmap_rows = g_scale[1].rows;
    const uint32_t *cmap_cols = g_scale[1].cols;
    for (uint32_t r = 0; r < ch; r++) {
        const uint8_t *srow = suvp + (size_t)cmap_rows[r] * suv;
        uint8_t *drow = dcb + (size_t)r * dcstride;
        uint32_t x = 0;
        if (!g_opt_swap_uv) {
            /* 两个色度元素（4 字节）一次搬 */
            for (; x + 2 <= cw; x += 2) {
                const uint8_t *p0 = srow + (size_t)cmap_cols[x] * 2;
                const uint8_t *p1 = srow + (size_t)cmap_cols[x + 1] * 2;
                uint32_t v = (uint32_t)p0[0] | ((uint32_t)p0[1] << 8) |
                             ((uint32_t)p1[0] << 16) | ((uint32_t)p1[1] << 24);
                memcpy(drow + x * 2, &v, 4);
            }
        }
        for (; x < cw; x++) {
            const uint8_t *sp = srow + (size_t)cmap_cols[x] * 2;
            if (g_opt_swap_uv) {
                drow[x * 2] = sp[1];
                drow[x * 2 + 1] = sp[0];
            } else {
                drow[x * 2] = sp[0];
                drow[x * 2 + 1] = sp[1];
            }
        }
    }
    {
        static unsigned long split_n = 0;
        unsigned long sn = __atomic_add_fetch(&split_n, 1, __ATOMIC_RELAXED);
        if (sn <= 3 || sn % 240 == 0) {
            LOGI("缩放分段耗时：亮度 %.1f ms / 色度 %.1f ms（%ux%u → %ux%u）", y_ms,
                 portal_seconds_since(&tu0) * 1000.0, sw, sh, w, h);
        }
    }
    return true;
}

/** 按 ystride 写整幅 Y；有 cb/cr 时顺带把色度抹平（否则叠在真图色彩上会花） */
static void portal_fill_frame(uint8_t *y, size_t ystride, uint8_t *cb, uint8_t *cr, size_t cstride,
                              size_t chroma_step, uint32_t w, uint32_t h, uint32_t frame) {
    if (y == NULL || ystride < w) return;
    /* 配了外源帧就**只认外源帧**：取不到（生产者没起来/已冻结/文件坏了）时**什么都不写**，
     * 让应用看到真实相机 —— 退回合成图案会让"模拟没在跑"看起来像"模拟在跑但坏了"。 */
    if (g_opt_src_file[0] != '\0') {
        if (portal_copy_external(y, ystride, cb, cr, cstride, chroma_step, w, h)) return;
        PORTAL_LOG_ONCE(LOGI, "外源帧暂不可用 → 本帧不改画面（露出真实相机）");
        return;
    }
    const uint8_t *tpl = portal_template(w, h, frame);
    if (tpl != NULL) {
        for (uint32_t r = 0; r < h; r++) {
            memcpy(y + (size_t)r * ystride, tpl + (size_t)r * w, w);
        }
    }
    if (cb == NULL || cr == NULL || cstride == 0) return;
    if (chroma_step == 0) chroma_step = 2;
    uint32_t cols = (w + 1) / 2;
    size_t max_cols = cstride / chroma_step;
    if ((size_t)cols > max_cols) cols = (uint32_t)max_cols;
    size_t cbytes = (size_t)cols * chroma_step;
    for (uint32_t r = 0; r < (h + 1) / 2; r++) {
        /* NV12/NV21：cb 与 cr 指向同一平面的相邻字节，一次 memset 覆盖两个分量 */
        memset(cb + (size_t)r * cstride, 128, cbytes);
        if (!(chroma_step == 2 && cr == cb + 1)) memset(cr + (size_t)r * cstride, 128, cbytes);
    }
}

/**
 * 映射缓存：**每个 buffer 只 lock 一次，之后常驻**。
 *
 * 为什么必须缓存：Android 15 上 `GraphicBufferMapper::lock/unlock` 走的是 AIDL mapper，
 * 每次都是 Binder 往返。实测"每帧 lock+unlock+getUsage"合计 **123 ms/帧**（改成逐行 memcpy
 * 后仍有 51 ms）—— 也就是说瓶颈根本不在内存带宽，而在每帧三次 IPC。
 * 缓存后每帧只剩纯 memcpy。
 *
 * 生命周期：只在一次 configure_streams 会话内有效（换会话就换 buffer），
 * 所以在 [portal_configure_streams] 里整体清空。**不清空时不清锁**：保持映射是 HAL 写
 * 相机 buffer 的常规做法（软件相机 HAL 就是这么干的）。
 */
struct portal_map {
    void *handle;
    uint8_t *y, *cb, *cr;
    size_t ystride, cstride, chroma_step;
    bool used;
};

#define PORTAL_MAP_SLOTS 8
static struct portal_map g_maps[PORTAL_MAP_SLOTS];

static void portal_invalidate_maps(void) {
    memset(g_maps, 0, sizeof(g_maps));
}

/** 取（必要时建立）这个 buffer 的映射；失败返回 NULL */
static struct portal_map *portal_get_map(void *handle, uint32_t w, uint32_t h) {
    struct portal_map *free_slot = NULL;
    for (int i = 0; i < PORTAL_MAP_SLOTS; i++) {
        if (g_maps[i].used && g_maps[i].handle == handle) return &g_maps[i];
        if (!g_maps[i].used && free_slot == NULL) free_slot = &g_maps[i];
    }
    if (free_slot == NULL) return NULL; /* 槽位用尽：本帧不注入（宁可少写一帧，也不动别人的映射） */

    uint32_t lock_usage = PORTAL_GRALLOC_USAGE_SW_READ_OFTEN | PORTAL_GRALLOC_USAGE_SW_WRITE_OFTEN;
    struct portal_rect bounds = {0, 0, (int32_t)w, (int32_t)h};

    if (g_gbm_lock_ycbcr != NULL) {
        struct portal_android_ycbcr yc;
        memset(&yc, 0, sizeof(yc));
        int rc = g_gbm_lock_ycbcr(g_gbm_self, handle, lock_usage, &bounds, &yc);
        if (rc == 0 && yc.y != NULL && yc.ystride >= w) {
            PORTAL_LOG_ONCE(LOGI,
                            "lockYCbCr 成功：ystride=%zu cstride=%zu step=%zu cb=%p cr=%p（%s）",
                            yc.ystride, yc.cstride, yc.chroma_step, yc.cb, yc.cr,
                            (yc.chroma_step == 2 && yc.cr == (uint8_t *)yc.cb + 1) ? "NV12(U前)"
                            : (yc.chroma_step == 2 && yc.cb == (uint8_t *)yc.cr + 1) ? "NV21(V前)"
                                                                                     : "其它");
            free_slot->handle = handle;
            free_slot->y = (uint8_t *)yc.y;
            free_slot->cb = (uint8_t *)yc.cb;
            free_slot->cr = (uint8_t *)yc.cr;
            free_slot->ystride = yc.ystride;
            free_slot->cstride = yc.cstride;
            free_slot->chroma_step = yc.chroma_step;
            free_slot->used = true;
            return free_slot;
        }
        PORTAL_LOG_ONCE(LOGE, "lockYCbCr 失败 rc=%d（y=%p ystride=%zu）→ 退 lock()", rc, yc.y,
                        yc.ystride);
    }

    /* 退路：lock() 只有基址，没有 stride ⇒ 只能安全地写开头有限行 */
    void *vaddr = NULL;
    int rc = g_gbm_lock(g_gbm_self, handle, lock_usage, &bounds, &vaddr);
    if (rc != 0 || vaddr == NULL) {
        PORTAL_LOG_ONCE(LOGE, "lock() 失败 rc=%d → 本流不注入（相机行为不变）", rc);
        return NULL;
    }
    PORTAL_LOG_ONCE(LOGI, "lock() 成功（无 stride）：只写前 %u 行（会斜切，够证明是我们写的）",
                    PORTAL_SAFE_ROWS);
    free_slot->handle = handle;
    free_slot->y = (uint8_t *)vaddr;
    free_slot->cb = free_slot->cr = NULL;
    free_slot->ystride = w; /* 用 width 当步长上界：写入区间必落在平面内，不会越界 */
    free_slot->cstride = 0;
    free_slot->chroma_step = 0;
    free_slot->used = true;
    return free_slot;
}
/** 真的把图案写进去；返回是否成功（阶梯见上方注释） */
static bool portal_write_frame(void *handle, uint32_t w, uint32_t h, uint32_t frame,
                               int acquire_fence) {
    portal_wait_fence(acquire_fence);
    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    if (!load_libui_mapper()) return false;

    /* 诊断：buffer 自己申报的 usage（只查描述符；一次就够，别放进每帧路径 —— 那是 Binder 往返） */
    if (g_gbm_get_usage != NULL) {
        static bool asked = false;
        if (!asked) {
            asked = true;
            uint64_t u = 0;
            int grc = g_gbm_get_usage(g_gbm_self, handle, &u);
            LOGI("buffer 自报：getUsage rc=%d usage=0x%llx", grc, (unsigned long long)u);
        }
    }

    struct portal_map *m = portal_get_map(handle, w, h);
    if (m == NULL) return false;
    portal_fill_frame(m->y, m->ystride, m->cb, m->cr, m->cstride, m->chroma_step, w, h, frame);
    portal_count_write(portal_seconds_since(&t0) * 1000.0);
    return true;
}

/**
 * 等 `acquire_fence` —— **写别人的 buffer 之前必须等它**。
 *
 * 实测教训：拍照的 BLOB buffer 上，我们的"替换 + 清零"被 vendor 的 JPEG 编码器**覆盖**了
 * （文件里我们的 JPEG 之后那一段是真实照片的残片，直到 ~900 KB 才归零）。
 * 原因是 **HAL 的结果回调触发时 buffer 未必写完**：QTI 的 JPEG 编码在独立线程上，
 * 完成信号走 `camera3_stream_buffer_t::acquire_fence`。不等它，我们就是在和编码器抢内存。
 *
 * 为什么用 libsync 的 `sync_wait`：它**返回错误码**、不会 abort，符合"HAL 里只调失败可返回的 API"。
 * 等不到（超时）也只是继续写 —— 宁可这一帧有瑕疵，也不能把结果线程挂死。
 */
static int (*g_sync_wait)(int fd, int timeout_ms) = NULL;
static bool g_sync_tried = false;

static void portal_wait_fence(int fd) {
    if (fd < 0) return;
    if (!g_sync_tried) {
        g_sync_tried = true;
        void *h = dlopen("libsync.so", RTLD_NOW | RTLD_LOCAL);
        if (h != NULL) g_sync_wait = (int (*)(int, int))dlsym(h, "sync_wait");
        if (g_sync_wait == NULL) LOGI("拿不到 sync_wait：不等 fence（有竞态风险）");
    }
    if (g_sync_wait == NULL) return;
    PORTAL_LOG_ONCE(LOGI, "acquire_fence 有效：fd=%d（等它写完再覆盖）", fd);
    int rc = g_sync_wait(fd, 200);
    if (rc != 0) PORTAL_LOG_ONCE(LOGI, "acquire_fence 未在 200ms 内就绪 rc=%d（继续写）", rc);
}

/* ------------------------------------------------------------------ *
 * `camera3_jpeg_blob`：JPEG 长度**真正的传达方式**
 *
 * AOSP 的约定：HAL 把 JPEG 写在 buffer 开头，并在 **buffer 末尾**放一个
 *   `camera3_jpeg_blob_t { uint16 jpeg_blob_id(=0x00FF); uint32 jpeg_size; }`（含对齐 = 8 字节），
 * 框架读这 8 个字节才知道"这张 JPEG 到底多长"。
 *
 * 这解释了本轮之前所有反常现象：保存下来的照片是 924,960 字节而我们的 JPEG 只有 126 KB ——
 * 因为那 6~8 个字节我们**从没碰过**，一直是真 HAL 那张照片的长度。
 * 修法：把自己的 JPEG 写进去的同时，**在同一个位置写我们自己的 blob**（长度=我们的字节数）。
 * ------------------------------------------------------------------ */

#define PORTAL_JPEG_BLOB_ID 0x00FFu
#define PORTAL_JPEG_BLOB_BYTES 8u

/** 在候选容量处找 camera3_jpeg_blob；找到返回容量，否则 0 */
static uint32_t portal_find_jpeg_capacity(const uint8_t *base, const void *handle, uint32_t *out_size,
                                         int verbose) {
    if (base == NULL || handle == NULL) return 0;
    const int *h = (const int *)handle;
    if (h[1] < 0 || h[1] > 8 || h[2] < 0 || h[2] > 64) return 0;
    const int *ints = h + 3 + h[1];
    for (int i = 0; i < h[2]; i++) {
        uint32_t cap = (uint32_t)ints[i];
        if (cap < 65536u || cap > 64u * 1024u * 1024u) continue;
        const uint8_t *p = base + cap - PORTAL_JPEG_BLOB_BYTES;
        uint16_t id = (uint16_t)(p[0] | (p[1] << 8));
        uint32_t sz = (uint32_t)p[4] | ((uint32_t)p[5] << 8) | ((uint32_t)p[6] << 16) |
                      ((uint32_t)p[7] << 24);
        if (verbose) LOGI("  容量候选 ints[%d]=%u → 尾部 id=0x%04x size=%u", i, cap, id, sz);
        if (id == PORTAL_JPEG_BLOB_ID && sz > 0 && sz < cap) {
            *out_size = sz;
            return cap;
        }
    }
    return 0;
}

/** 照片区大小上限（保守：实测 BLOB buffer 约 7.6 MB，4 MB 一定放得下） */
#define PORTAL_PHOTO_MAX (4u * 1024u * 1024u)

/**
 * 把共享帧文件里的 JPEG 搬进拍照输出 buffer（BLOB / format 0x21）。
 *
 * 为什么可行：BLOB buffer 是**线性**分配的，没有平铺/UBWC 问题，所以不需要防平铺那套；
 * 只需要在 configure_streams 时给它补上 SW 写位（见 portal_scrub_streams），否则 CPU 写会被拒。
 *
 * **尾部要清**：真 HAL 已经先把**真实照片**写进去了（实测 919,710 字节），我们的 JPEG 更短，
 * 所以要把自己长度之后的一段清零 —— 否则文件尾部会留着真实画面的残片（既是坏数据也是信息泄漏）。
 */
static bool portal_write_photo(const camera3_stream_buffer_t *sb) {
    if (sb == NULL || sb->stream == NULL || sb->buffer == NULL) return false;
    if (!portal_fsrc_open() || g_fsrc_map == NULL) return false;
    struct portal_frame_hdr *h = (struct portal_frame_hdr *)g_fsrc_map;
    /* 等 app 为**这次**拍照编出的新照片（按需编码）；超时就沿用上一张（旧但合法） */
    for (int i = 0; i < 40; i++) {
        if (h->photo_seq != g_photo_seq_at_request) break;
        usleep(5000);
    }
    uint32_t n = h->photo_bytes;
    if (n == 0 || n > PORTAL_PHOTO_MAX) return false;
    if (h->slots == 0 || h->slot_bytes == 0) return false;
    size_t off = PORTAL_FRAME_HDR_BYTES + (size_t)h->slots * h->slot_bytes;
    if (off + n > g_fsrc_len) {
        PORTAL_LOG_ONCE(LOGE, "照片区越界：off=%zu n=%u len=%zu", off, n, g_fsrc_len);
        return false;
    }
    if (!load_libui_mapper()) return false;

    void *handle = *sb->buffer;
    if ((uintptr_t)handle < 0x1000u) return false;
    uint32_t lock_usage =
        PORTAL_GRALLOC_USAGE_SW_READ_OFTEN | PORTAL_GRALLOC_USAGE_SW_WRITE_OFTEN;
    struct portal_rect bounds = {0, 0, (int32_t)sb->stream->width, (int32_t)sb->stream->height};
    static unsigned long photo_n = 0;
    unsigned long pn = __atomic_add_fetch(&photo_n, 1, __ATOMIC_RELAXED);
    bool dump_photo = (g_opt_dump && pn <= 6);
    if (dump_photo) LOGI("照片#%lu 搬运前（等 fence 之前）句柄：", pn);
    if (dump_photo) portal_dump_handle(90, handle);
    portal_wait_fence(sb->acquire_fence);
    if (dump_photo) LOGI("照片#%lu 搬运前（等 fence 之后）句柄：", pn);
    if (dump_photo) portal_dump_handle(91, handle);
    void *vaddr = NULL;
    int rc = g_gbm_lock(g_gbm_self, handle, lock_usage, &bounds, &vaddr);
    if (rc != 0 || vaddr == NULL) {
        PORTAL_LOG_ONCE(LOGE, "照片 buffer lock 失败 rc=%d（SW 写位没补上？）→ 保留真实照片", rc);
        return false;
    }
    memcpy(vaddr, g_fsrc_map + off, n);
    /* 先看看真 HAL 的 blob（同时把 buffer 容量问出来） */
    uint32_t real_size = 0;
    uint32_t cap = portal_find_jpeg_capacity((const uint8_t *)vaddr, handle, &real_size, dump_photo);
    size_t wipe;
    if (cap != 0) {
        /* 找到容量：只清到 blob 之前，并**写我们自己的 blob**（长度 = 我们的字节数） */
        wipe = (size_t)cap - PORTAL_JPEG_BLOB_BYTES;
        if (dump_photo) {
            LOGI("  真 HAL 的 blob：容量=%u 它那张=%u 字节（我们=%u）", cap, real_size, n);
        }
        uint8_t *blob = (uint8_t *)vaddr + wipe;
        memset(blob, 0, PORTAL_JPEG_BLOB_BYTES);
        blob[0] = (uint8_t)(PORTAL_JPEG_BLOB_ID & 0xFF);
        blob[1] = (uint8_t)(PORTAL_JPEG_BLOB_ID >> 8);
        blob[4] = (uint8_t)(n & 0xFF);
        blob[5] = (uint8_t)((n >> 8) & 0xFF);
        blob[6] = (uint8_t)((n >> 16) & 0xFF);
        blob[7] = (uint8_t)((n >> 24) & 0xFF);
    } else {
        /* 没找到（例如真 HAL 还没写 blob）：保守清 4 MiB，不动尾部 */
        wipe = PORTAL_PHOTO_MAX;
        PORTAL_LOG_ONCE(LOGI, "没找到 camera3_jpeg_blob：本次不改长度（照片尾部会是零）");
    }
    if (wipe > n) memset((uint8_t *)vaddr + n, 0, wipe - n);
    int fence = -1;
    g_gbm_unlock(g_gbm_self, handle, &fence);
    LOGI("照片已替换：%u 字节（来自共享帧文件的照片区）", n);
    if (dump_photo) {
        LOGI("照片#%lu 搬运后句柄（我们的 JPEG 长度=%u）：", pn, n);
        portal_dump_handle(92, handle);
    }
    return true;
}

/** 往一个输出 buffer 的 Y 平面写图案；成功返回 true */
static bool inject_into_buffer(const camera3_stream_buffer_t *sb, uint32_t frame) {
    if (sb == NULL || sb->stream == NULL || sb->buffer == NULL) return false;
    if (sb->status != 0) return false;
    camera3_stream_t *st = sb->stream;
    int format = st->format;
    /* 只碰 YUV 类输出：JPEG/BLOB（拍照）与 RAW 不动 —— 写坏它们会直接毁掉一次拍照 */
    if (format != PORTAL_PIXEL_FORMAT_YCbCr_420_888 &&
        format != PORTAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
        PORTAL_LOG_ONCE(LOGI, "输出流 format=0x%x 不是 YUV/私用格式 → 不注入", format);
        return false;
    }
    uint32_t w = st->width, h = st->height;
    if (w == 0 || h == 0 || w > 8192u || h > 8192u) return false;
    /* 录像编码器流不写：见 PORTAL_GRALLOC_USAGE_HW_VIDEO_ENCODER 的注释 */
    if ((st->usage & PORTAL_GRALLOC_USAGE_HW_VIDEO_ENCODER) != 0) return false;

    if (!portal_cpu_writable(st)) {
        /* 保命门：HW-only 的流绝不尝试 CPU 映射（会 abort 掉 provider，实测） */
        PORTAL_LOG_ONCE(
            LOGI, "输出流 format=0x%x usage=0x%x 无 SW 写位 → 跳过注入（需 usage_patch=1）", format,
            st->usage);
        return false;
    }
    void *handle = *sb->buffer;
    if ((uintptr_t)handle < 0x1000u) return false;
    return portal_write_frame(handle, w, h, frame, sb->acquire_fence);
}

/** 我们给 HAL 的回调表：先注入，再交给框架 */
static void portal_process_capture_result(const camera3_callback_ops_t *ops,
                                          const camera3_capture_result_t *result) {
    (void)ops;
    static bool entered = false;
    if (!entered) {
        entered = true;
        LOGI("process_capture_result 已进入我们的回调（注入时序成立）");
    }
    if (result != NULL && g_opt_dump && portal_cam_selected()) {
        static unsigned long rn = 0;
        unsigned long n = __atomic_add_fetch(&rn, 1, __ATOMIC_RELAXED);
        bool want = (n == 1 || n % 240 == 0);
        if (!want) {
            struct portal_layout LB;
            want = portal_parse_result((const void *)result, &LB) && portal_layout_has_blob(&LB);
        }
        if (want) {
            struct portal_layout L;
            bool ok = portal_parse_result((const void *)result, &L);
            const char *raw = (const char *)result;
            LOGI("结果布局#%lu：frame=%u w4=%p w8=%08x w12=%08x w16=%08x → %s num=%u", n,
                 *(const uint32_t *)raw, *(const void *const *)(raw + 4),
                 *(const uint32_t *)(raw + 8), *(const uint32_t *)(raw + 12),
                 *(const uint32_t *)(raw + 16), L.which, L.num);
            if (ok) {
                for (uint32_t i = 0; i < L.num && i < 4; i++) {
                    const camera3_stream_buffer_t *sb = &L.sbs[i];
                    if (sb->stream == NULL) continue;
                    LOGI("  res[%u] format=0x%x %ux%u usage=0x%x status=%d", i,
                         sb->stream->format, sb->stream->width, sb->stream->height,
                         sb->stream->usage, sb->status);
                    if (sb->buffer != NULL) portal_dump_handle(i, *sb->buffer);
                }
            }
        }
    }
    /* 注入：只在我们包了回调表、且开关允许时；写不进去就什么都不做 */
    if (g_opt_inject && result != NULL && portal_cam_selected()) {
        struct portal_layout L;
        if (portal_parse_result((const void *)result, &L)) {
            bool wrote = false;
            for (uint32_t i = 0; i < L.num; i++) {
                const camera3_stream_buffer_t *sb = &L.sbs[i];
                if (sb->stream != NULL && sb->stream->format == 0x21 /* BLOB/JPEG */) {
                    /* 拍照：不是"写 Y 平面"，而是整块换成我们的 JPEG */
                    if (sb->status == 0 && portal_write_photo(sb)) wrote = true;
                    continue;
                }
                if (inject_into_buffer(sb, *(const uint32_t *)result)) wrote = true;
            }
            if (wrote) {
                unsigned long n = __atomic_add_fetch(&g_injected_frames, 1, __ATOMIC_RELAXED);
                if (n == 1 || n % 120 == 0) {
                    LOGI("已注入合成图案：#%lu（frame=%u）", n, *(const uint32_t *)result);
                }
            }
        }
    }
    if (g_framework_callbacks != NULL && g_framework_callbacks->process_capture_result != NULL) {
        g_framework_callbacks->process_capture_result(g_framework_callbacks, result);
    }
}

static void portal_notify(const camera3_callback_ops_t *ops, const void *msg) {
    (void)ops;
    if (g_framework_callbacks != NULL && g_framework_callbacks->notify != NULL) {
        g_framework_callbacks->notify(g_framework_callbacks, msg);
    }
}

/**
 * 把真实 ops 表里的某一格换成我们的实现。
 *
 * 按**函数地址**扫表：不依赖结构布局、不依赖它在第几格（本机已验证"结构布局 ≠ AOSP 头文件"，
 * 所以按索引取槽是不可靠的）。找到后原地改写那一格，并按页记下的原始权限还原
 * （见 [page_protection]：还原成只读会让 HAL 写同页静态量时 SEGV_ACCERR）。
 *
 * @return 命中槽位下标；未命中返回 -1
 */
static int patch_ops_by_value(const struct camera3_device *dev, void *real_fn, void *our_fn,
                              const char *name) {
    if (dev == NULL || dev->ops == NULL || real_fn == NULL) return -1;

    void **table = (void **)dev->ops;
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;

    uintptr_t begin = (uintptr_t)table & ~(uintptr_t)(page_size - 1);
    uintptr_t end = ((uintptr_t)(table + 64) + page_size - 1) & ~(uintptr_t)(page_size - 1);
    size_t span = (size_t)(end - begin);

    int original[8];
    int pages = (int)(span / (size_t)page_size);
    if (pages > 8) pages = 8;
    for (int i = 0; i < pages; i++) {
        original[i] = page_protection((void *)(begin + (uintptr_t)i * (uintptr_t)page_size));
    }

    if (mprotect((void *)begin, span, PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect 失败：无法改写 ops 表（保持真实 HAL 行为）");
        return -1;
    }

    int hit = -1;
    for (int i = 0; i < 64; i++) {
        if (table[i] == real_fn) {
            table[i] = our_fn;
            hit = i;
            break;
        }
    }

    /* 逐页还原；读不到原权限时保守地还原成 READ|WRITE（绝不缩小到只读） */
    for (int i = 0; i < pages; i++) {
        int prot = original[i] > 0 ? original[i] : (PROT_READ | PROT_WRITE);
        mprotect((void *)(begin + (uintptr_t)i * (uintptr_t)page_size), (size_t)page_size, prot);
    }

    if (hit >= 0) {
        LOGI("已接管 %s（ops[%d]）", name, hit);
    } else {
        LOGE("ops 表里没找到真实 %s：本次不接管（相机行为不变）", name);
    }
    return hit;
}

/** M0 必做：接管 process_capture_request（只计数/转发，不改画面） */
static void patch_process_capture_request(const struct camera3_device *dev) {
    patch_ops_by_value(dev, (void *)g_real_process_capture_request,
                       (void *)shim_process_capture_request, "process_capture_request");
}

/** 开关 init_wrap=1：包一层框架回调表 —— 注入（改画面）的时序前提 */
static void patch_initialize(const struct camera3_device *dev) {
    if (g_real_initialize == NULL) {
        LOGE("拿不到真实 initialize（导出符号 + ops[0] 兜底都失败）：无法包回调，本相机不注入");
        return;
    }
    patch_ops_by_value(dev, (void *)g_real_initialize, (void *)portal_initialize, "initialize");
}

/** 开关 usage_patch=1：接管 configure_streams（给输出流补 SW 用途位） */
static void patch_configure_streams(const struct camera3_device *dev) {
    if (g_real_configure_streams == NULL) {
        LOGE("拿不到真实 configure_streams 导出符号：无法补用途位");
        return;
    }
    patch_ops_by_value(dev, (void *)g_real_configure_streams, (void *)portal_configure_streams,
                       "configure_streams");
}

/**
 * 该地址所在映射的权限串（找不到返回 false）。与 [portal_is_exec_addr] 分开写：
 * 观察别人对象时可能撞上已释放的指针，先确认"仍在某个映射里"再解引用，别把诊断变成崩溃。
 */
static bool portal_map_perm(const void *addr, char *out, size_t n) {
    if (addr == NULL) return false;
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == NULL) return false;
    uintptr_t target = portal_untag(addr);
    bool ok = false;
    char line[512];
    while (fgets(line, sizeof(line), f) != NULL) {
        unsigned long start = 0, end = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) continue;
        if (target < start || target >= end) continue;
        if (out != NULL) snprintf(out, n, "%s", perms);
        ok = true;
        break;
    }
    fclose(f);
    return ok;
}

/**
 * 该地址落在哪个映射里（"路径+偏移"），顺带带出 dladdr 能给的符号名。
 *
 * 用途：ACE5 的 `open()` 是**谁**调的。实测调用链是
 * `camx.provider-impl.so :: CameraModule::open` → `camera.oemlayer.so+0xdb78` → 我们 → 真件，
 * 也就是说 OPPO 的 oemlayer 是"provider 之上再包一层"的角色，它会在我们的 open 返回**之后**
 * 才把 device 的 ops 表换成自己的 —— 这决定了接管时序（见 portal_binder_thread）。
 */
static bool portal_where(const void *addr, char *out, size_t n) {
    if (out == NULL || n == 0) return false;
    out[0] = '\0';
    if (addr == NULL) return false;
    uintptr_t target = portal_untag(addr);
    bool found = false;
    FILE *f = fopen("/proc/self/maps", "re");
    if (f != NULL) {
        char line[512];
        while (fgets(line, sizeof(line), f) != NULL) {
            unsigned long start = 0, end = 0;
            if (sscanf(line, "%lx-%lx", &start, &end) != 2) continue;
            if (target < start || target >= end) continue;
            char *q = strchr(line, '/');
            if (q != NULL) {
                size_t m = strlen(q);
                if (m > 0 && q[m - 1] == '\n') q[m - 1] = '\0';
                snprintf(out, n, "%s+0x%lx", q, (unsigned long)(target - start));
            } else {
                snprintf(out, n, "(anon)+0x%lx", (unsigned long)(target - start));
            }
            found = true;
            break;
        }
        fclose(f);
    }
    Dl_info di;
    if (dladdr(addr, &di) != 0 && di.dli_sname != NULL) {
        size_t m = strlen(out);
        snprintf(out + m, n - m, " [%s]", di.dli_sname);
    }
    return found;
}

/**
 * "绑定探测"线程：`open()` 返回后**极短间隔**地盯住 `device->ops`，看它什么时候被填上、
 * 填成哪个表。
 *
 * 为什么必须这么测（ACE5 实测）：真件 `open()` 返回的那一刻，device 对象前 96 字节只有
 * `tag=HWDT / version=0x305 / module`，**`ops` 是 NULL**；可 27 ms 后 CamX 就拿着同一个
 * device 指针进了 `configure_streams` —— 说明 ops 是**在 open 返回之后**由上层（OPPO oemlayer）
 * 补上的。所以"按 ops 索引接管"能不能在 ACE5 成立，取决于这张表被填的**时刻与位置**：
 *   · 若表落在 oemlayer 的静态数据段 ⇒ 可离线定点改，随时接管；
 *   · 若表是每次 open 现搭的堆副本 ⇒ 只能在填表与首次调用之间抢，时序很窄。
 * 两种结论对应两种完全不同的实现，所以先把这个事实测出来，再写代码。
 */
/**
 * 在进程内存里找出**真件自己的** `camera3_device_ops` 表，并接管 `process_capture_request`。
 *
 * 为什么不是"改 device 对象的 ops 字段"（MI6 那条路，ACE5 上走不通）：
 * ACE5 上 provider 拿到的 device 对象，`ops` 字段**始终是 NULL** —— 真件 `open` 返回时不填
 * （实测 50ms 内不变），OPPO `camera.oemlayer.so` 在返回前换上的那个外层对象也不填
 * （实测 500ms 内不变）。可 CamX 的 `configure_streams`/`process_capture_request` 照样被调用，
 * 说明调用方是从**别处**拿到那批函数指针的 —— 那张表就在本进程的可写数据段里。
 *
 * 所以这里改成：按真件 HAL3 入口的 vaddr（`ops_pcr_vaddr`，默认本机实测值）在**可写映射**里
 * 找那张表，命中就地改写。优点是不碰代码段：没有 trampoline、没有指令重定位、
 * 也不会和 CFI 校验打架（调用目标地址没变）。
 *
 * 风险控制：只扫可写、非设备、小于 64MB 的映射；总扫描量封顶 256MB；
 * 只认"表里有多数指针落在真件可执行段"的候选；找不到就什么都不做（相机行为不变）。
 *
 * @return 实际改写的表数量（0 = 没找到/没改）
 */
static int portal_scan_ops_table_ex(int min_in_hal, int skip_stack, int do_patch) {
    if (g_real_handle == NULL) return 0;
    Dl_info di;
    /* ⚠️ 必须用**真件模块**的地址去问基址：`&HMI` 是我们 shim 自己的 HMI，
     * dladdr 会给出 /odm/.../camera.qcom.so（我们）的基址 —— 之前拿它加 vaddr，
     * 算出来的是"我们基址+偏移"的垃圾地址，全地址空间当然一处都找不到。
     * 真件的基址只能从 dlopen 出来的句柄符号取。 */
    if (g_real_module == NULL || dladdr((const void *)g_real_module, &di) == 0 ||
        di.dli_fbase == NULL) {
        LOGE("ops 扫描：拿不到真件基址，放弃");
        return 0;
    }
    uintptr_t base = (uintptr_t)di.dli_fbase;
    uintptr_t pcr = base + (uintptr_t)g_opt_pcr_vaddr;
    LOGI("ops 扫描：真件基址=%p，目标 process_capture_request=%p（门槛 %d，跳栈=%d，改写=%d）",
         (void *)base, (void *)pcr, min_in_hal, skip_stack, do_patch);

    /*
     * **必须用 /proc/self/mem 读，不能直接解引用**：
     * 直接读那些 perms 写着 r 的共享段（DMA / 图形缓冲）会 SIGBUS 或卡死几十秒 ——
     * 实测直接在主机上读，provider 每次都在 ~5 秒后被 hwservicemanager 判死重启（无 tombstone）。
     * pread 走内核的 fault 处理，读不了就返回错误，不会把 HAL 进程带走。
     */
    int memfd = open("/proc/self/mem", O_RDWR);
    if (memfd < 0) {
        LOGE("ops 扫描：打不开 /proc/self/mem");
        return 0;
    }
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == NULL) {
        LOGE("ops 扫描：读不到 maps");
        close(memfd);
        return 0;
    }
    static unsigned char sbuf[256 * 1024];
    char line[512];
    unsigned long long budget = 192ull << 20, done = 0, next_report = 32ull << 20;
    long regions = 0, patched = 0;
    while (fgets(line, sizeof(line), f) != NULL) {
        unsigned long start = 0, end = 0;
        char perms[8] = {0}, path[256] = {0};
        if (sscanf(line, "%lx-%lx %7s %*s %*s %*s %255[^\n]", &start, &end, perms, path) < 3) continue;
        if (perms[0] != 'r') continue;                       /* 只扫读得到的 */
        if (perms[3] == 's') continue;                       /* 共享段=多半是 DMA/设备内存 */
        unsigned long long len = (unsigned long long)(end - start);
        if (len == 0 || len > (64ull << 20) || len > budget) continue;
        if (skip_stack && strstr(path, "[stack") != NULL) continue;
        if (strstr(path, "/dev/") != NULL || strstr(path, "kgsl") != NULL ||
            strstr(path, "dmabuf") != NULL || strstr(path, "gralloc") != NULL ||
            strstr(path, "ion") != NULL || strstr(path, "mali") != NULL ||
            strstr(path, "memfd:") != NULL) {
            continue;
        }
        regions++;
        budget -= len;
        for (uintptr_t off = start; off < end; off += sizeof(sbuf)) {
            size_t want = (size_t)(end - off);
            if (want > sizeof(sbuf)) want = sizeof(sbuf);
            ssize_t n = pread(memfd, sbuf, want, (off_t)off);
            if (n >= 8) {
                for (ssize_t i = 0; i + 8 <= n; i += 8) {
                    if (*(uint64_t *)(sbuf + i) != (uint64_t)pcr) continue;
                    uintptr_t p = off + (uintptr_t)i;
                    uint64_t slots[20] = {0};
                    ssize_t m = pread(memfd, slots, sizeof(slots), (off_t)p);
                    if (m < 8) continue;
                    int in_hal = 0, hit_idx = -1;
                    for (int k = 0; k < 20 && (ssize_t)(k * 8 + 8) <= m; k++) {
                        if (slots[k] == 0) continue;
                        if (slots[k] == (uint64_t)pcr) hit_idx = k;
                        char w[256] = {0};
                        portal_where((const void *)(uintptr_t)slots[k], w, sizeof(w));
                        if (strstr(w, "camera.qcom.so") != NULL) in_hal++;
                        if (k < 8) LOGI("  候选 %p 槽[%2d] = %p  %s", (void *)p, k,
                                        (void *)(uintptr_t)slots[k], w);
                    }
                    LOGI("  候选 %p：落在真件里的指针 %d 个，命中槽 %d（门槛 %d）", (void *)p, in_hal,
                         hit_idx, min_in_hal);
                    if (hit_idx < 0 || in_hal < min_in_hal) continue;
                    if (!do_patch) continue;
                    long page = sysconf(_SC_PAGESIZE);
                    if (page <= 0) page = 4096;
                    uintptr_t slot = p + (uintptr_t)hit_idx * 8;
                    int prot = page_protection((void *)slot);
                    if (mprotect((void *)(slot & ~(uintptr_t)(page - 1)), (size_t)page,
                                 PROT_READ | PROT_WRITE) != 0) {
                        LOGE("  槽 %p 所在页不可写，跳过", (void *)slot);
                        continue;
                    }
                    uint64_t ours = (uint64_t)(uintptr_t)&shim_process_capture_request;
                    ssize_t wr = pwrite(memfd, &ours, sizeof(ours), (off_t)slot);
                    mprotect((void *)(slot & ~(uintptr_t)(page - 1)), (size_t)page,
                             prot > 0 ? prot : (PROT_READ | PROT_WRITE));
                    if (wr == (ssize_t)sizeof(ours)) {
                        g_real_process_capture_request =
                            (int (*)(const struct camera3_device *, camera3_capture_request_t *))pcr;
                        LOGI("  已接管：把 %p（真件 ops 表里第 %d 格）换成我们的包装", (void *)slot,
                             hit_idx);
                        patched++;
                    } else {
                        LOGE("  写 %p 失败（pwrite=%zd）", (void *)slot, wr);
                    }
                }
            }
            done += (unsigned long long)want;
            if (done >= next_report) {
                LOGI("ops 扫描进度：%llu MB（段 %ld，命中改写 %ld）", done >> 20, regions, patched);
                next_report += 32ull << 20;
            }
        }
    }
    fclose(f);
    close(memfd);
    LOGI("ops 扫描结束：扫了 %ld 段 / %llu MB，改写 %ld 处", regions, done >> 20, patched);
    return (int)patched;
}

/* 前置声明：状态落盘函数定义在本段之后（见 portal_write_state） */
static void portal_write_state(const char *fmt, ...);

/**
 * 多目标探针：把若干个"真件里的 vaddr"一次性在全地址空间里搜一遍，只报告命中位置。
 *
 * 用在"这张表到底存不存在"这种是非题上：只要有一个函数指针被**任何**地方存下来过，
 * 就会在这里现形。全部落空 ⇒ 调用方不是从内存里取这个指针的（那就得改成挂函数入口）。
 */
static void portal_probe_vaddrs(const unsigned long *vaddrs, int count, unsigned long long budget_mb) {
    Dl_info di;
    if (g_real_module == NULL || dladdr((const void *)g_real_module, &di) == 0 ||
        di.dli_fbase == NULL) return;
    uintptr_t base = (uintptr_t)di.dli_fbase;
    int memfd = open("/proc/self/mem", O_RDWR);
    if (memfd < 0) return;
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == NULL) { close(memfd); return; }
    static unsigned char pbuf[512 * 1024];
    char line[512];
    unsigned long long budget = budget_mb << 20, done = 0;
    long regions = 0, hits = 0;
    LOGI("多目标探针：真件基址=%p，目标 %d 个，预算 %llu MB", (void *)base, count, budget_mb);
    while (fgets(line, sizeof(line), f) != NULL) {
        unsigned long start = 0, end = 0;
        char perms[8] = {0}, path[256] = {0};
        if (sscanf(line, "%lx-%lx %7s %*s %*s %*s %255[^\n]", &start, &end, perms, path) < 3) continue;
        if (perms[0] != 'r' || perms[3] == 's') continue;
        if (strstr(path, "[stack") != NULL || strstr(path, "[vvar") != NULL ||
            strstr(path, "[vdso") != NULL) continue;
        if (strstr(path, "/dev/") != NULL || strstr(path, "kgsl") != NULL ||
            strstr(path, "dmabuf") != NULL || strstr(path, "gralloc") != NULL) continue;
        unsigned long long len = (unsigned long long)(end - start);
        if (len == 0 || len > budget) continue;
        regions++;
        budget -= len;
        for (uintptr_t off = start; off < end; off += sizeof(pbuf)) {
            size_t want = (size_t)(end - off);
            if (want > sizeof(pbuf)) want = sizeof(pbuf);
            ssize_t n = pread(memfd, pbuf, want, (off_t)off);
            if (n >= 8) {
                for (int t = 0; t < count; t++) {
                    uint64_t want_v = (uint64_t)(base + (uintptr_t)vaddrs[t]);
                    for (ssize_t i = 0; i + 8 <= n; i += 8) {
                        if (*(uint64_t *)(pbuf + i) != want_v) continue;
                        char w[256] = {0};
                        portal_where((const void *)(off + (uintptr_t)i), w, sizeof(w));
                        LOGI("  探针命中：真件 vaddr 0x%lx 存在 %p（%s）", vaddrs[t],
                             (void *)(off + (uintptr_t)i), w);
                        hits++;
                    }
                }
            }
            done += (unsigned long long)want;
        }
    }
    fclose(f);
    close(memfd);
    LOGI("多目标探针结束：%ld 段 / %llu MB，命中 %ld 处", regions, done >> 20, hits);
}

/** 载入期扫描线程：门槛高（要像整张 ops 表）、在可写段里找 */
static void *portal_load_scan_thread(void *arg) {
    (void)arg;
    int n = portal_scan_ops_table_ex(6, 0, 1);
    portal_write_state("ops_scan(载入期) 改写 %d 处", n);
    return NULL;
}

#if defined(__aarch64__)
/* ------------------------------------------------------------------ *
 * 函数入口挂钩（inline hook）—— 见 g_opt_inline_hook 的注释
 * ------------------------------------------------------------------ */

#define PORTAL_HOOK_MAX 32
/** 每个挂钩位的"返回点"（安装时填成 entry+16） */
__attribute__((used)) static volatile unsigned long g_hook_resume[PORTAL_HOOK_MAX];
/** 每个挂钩位的命中计数（钩子体只自增；汇报在 open 时做） */
__attribute__((used)) static unsigned long g_hook_cnt[PORTAL_HOOK_MAX];

/**
 * 钩子**在内核态之外能干的全部事情**：计数 + 记下第一次调用的现场（调用者、两个参数）。
 *
 * 为什么不用 C 函数记账（ACE5 实测）：钩子是从 vendor 代码**持着自己的加载锁**的路径里
 * 进来的，而 C 函数的第一条 libc/原子调用会触发**首次 PLT 惰性绑定**（要拿 linker 锁）
 * ⇒ ABBA 死锁。对照实验：调"裸 `ret`"的钩子完全透明（CONFIG=1、229 帧），
 * 调我们那个编译出来的 C 记账函数就必死。所以钩子里**一条 call 都不许有** ——
 * 全部用内联汇编直接读写我们自己的 .bss，汇报留到 open 时（非热路径）再做。
 */
struct portal_hook_rec {
    unsigned long cnt;    /**< 命中次数 */
    unsigned long caller; /**< 第一次调用时的返回地址（干净，PAC 已在桩里避开） */
    unsigned long a0;     /**< 第一次调用时的第一个参数 */
    unsigned long a1;     /**< 第一次调用时的第二个参数 */
};
__attribute__((used)) static struct portal_hook_rec g_hook_rec[PORTAL_HOOK_MAX];
static char g_hook_name_buf[PORTAL_HOOK_MAX][64];
static const char *g_hook_name[PORTAL_HOOK_MAX];
/** 零调用类钩子（rec/req）的槽位游标：多张列表**共用**同一片槽位，
 *  否则第二次调用会从 0 重新分配、把第一张列表的钩子全顶掉（踩过）。 */
static int g_hook_rec_next = 0;

/*
 * 钩子外壳（**通用版**）：开头 4 条指令是占位 nop，安装时被替换成目标函数被顶掉的
 * 那 4 条真实指令（安装器会先验证它们与 PC 无关），然后记账，再跳回 entry+16。
 *
 * 为什么做成"可运行时填指令"：本机至少要挂两类函数，序言并不一样 ——
 *   CamX HAL3 入口：`paciasp; stp x29,x30,[sp,#-0x60]!; stp x28,x27,[sp,#0x10]; stp x26,x25,[sp,#0x20]`
 *   OPPO oemlayer：`paciasp; sub sp,sp,#0x30; stp x29,x30,[sp,#0x10]; str x19,[sp,#0x20]`
 * 写死一套就挂不上另一套；而"重放前 4 条"这个思路对两者都成立（都是非 PC 相对指令）。
 * 占位区是我们**自己 .so 的代码段**，用 /proc/self/mem（FOLL_FORCE）写 —— 同样绕开 W^X。
 *
 * 寄存器约定：x0/x1 = 参数；x9..x17 调用者可挥霍；x19..x28 由被调函数保存；x18 = 平台寄存器。
 * 顺序关键：**先**抄干净的 x30 与入口 SP，**再**重放（重放里可能就有 paciasp 与改 SP 的指令）。
 */
/* ⚠️ 跳回函数中部**不能**用 `br`：ACE5 上真件 .text 带 BTI，间接分支落到非 bti/非 paciasp
 * 的指令上会直接 SIGILL（`ILL_ILLOPC`，fault addr 就是那个返回点 —— 实测 tombstone 抓到过）。
 * `RET` 不受 BTI 约束，所以返回点写进 x30 再 `ret`；x30 的原始（已签名）值早已由重放的
 * `stp x29,x30,[sp,#-0x60]!` 存进函数自己的栈帧，函数收尾时会自己恢复，不冲突。 */
/* 除了 x0..x7，钩子还必须自己保存 **x8 与 x18**：
 * x8 是"间接结果指针"（结构体返回用），x18 在 AAPCS64 里是**平台保留**寄存器 ——
 * 带 SCS（影子调用栈）的 vendor 代码把返回地址链挂在 x18 上，而我们不开 SCS 的编译器
 * 只把 x18 当普通临时寄存器，调用 C 记账函数时会被改掉；之后 vendor 代码 `ret` 就从错的
 * 影子栈里弹返回地址，跳到野地址。实测症状：钩子只来得及打第一条日志，
 * provider 几秒后被 init 重启、**没有 tombstone**（不是段错误，是被守护逻辑收走）。 */
/* 参数先用 x9..x13 抄好、**再**执行重放：重放里的 paciasp 会改 x30，
 * 而重放本身（paciasp / stp / sub sp）不碰 x9..x13，所以这样安排是安全的。
 * 这一段曾经漏掉过 —— 结果是记账函数拿到一堆垃圾参数，provider 当场被打死。 */
#define PORTAL_HOOK_STUB(NAME, OFF, IDX)                                          \
    __attribute__((naked)) static void NAME(void) {                               \
        __asm__ volatile(                                                         \
            "mov x9, x30\n"                                                      \
            "mov x10, x0\n"                                                      \
            "mov x11, x1\n"                                                      \
            "mov x12, x29\n"                                                     \
            "mov x13, sp\n"                                                      \
            "nop\n" "nop\n" "nop\n" "nop\n"   /* ← +20 处：安装时写成被顶掉的 4 条 */ \
            "sub sp, sp, #0x60\n"                                                \
            "stp x0, x1, [sp, #0x00]\n"                                          \
            "stp x2, x3, [sp, #0x10]\n"                                          \
            "stp x4, x5, [sp, #0x20]\n"                                          \
            "stp x6, x7, [sp, #0x30]\n"                                          \
            "stp x8, x18, [sp, #0x40]\n"                                         \
            "mov x0, x9\n"                                                       \
            "mov x1, x10\n"                                                      \
            "mov x2, x11\n"                                                      \
            "mov x3, x12\n"                                                      \
            "mov x4, #" #IDX "\n"                                                \
            "mov x5, x13\n"                                                      \
            "bl portal_hook_any_c\n"                                             \
            "ldp x0, x1, [sp, #0x00]\n"                                          \
            "ldp x2, x3, [sp, #0x10]\n"                                          \
            "ldp x4, x5, [sp, #0x20]\n"                                          \
            "ldp x6, x7, [sp, #0x30]\n"                                          \
            "ldp x8, x18, [sp, #0x40]\n"                                         \
            "add sp, sp, #0x60\n"                                                \
            "adrp x16, g_hook_resume\n"                                          \
            "add x16, x16, #:lo12:g_hook_resume\n"                               \
            "ldr x16, [x16, #" #OFF "]\n"                                        \
            "mov x30, x16\n"                                                      \
            "ret\n");                                                         \
    }

PORTAL_HOOK_STUB(portal_hook_00, 0, 0)
PORTAL_HOOK_STUB(portal_hook_01, 8, 1)
PORTAL_HOOK_STUB(portal_hook_02, 16, 2)
PORTAL_HOOK_STUB(portal_hook_03, 24, 3)
PORTAL_HOOK_STUB(portal_hook_04, 32, 4)
PORTAL_HOOK_STUB(portal_hook_05, 40, 5)
PORTAL_HOOK_STUB(portal_hook_06, 48, 6)
PORTAL_HOOK_STUB(portal_hook_07, 56, 7)
PORTAL_HOOK_STUB(portal_hook_08, 64, 8)
PORTAL_HOOK_STUB(portal_hook_09, 72, 9)
PORTAL_HOOK_STUB(portal_hook_10, 80, 10)
PORTAL_HOOK_STUB(portal_hook_11, 88, 11)
PORTAL_HOOK_STUB(portal_hook_12, 96, 12)
PORTAL_HOOK_STUB(portal_hook_13, 104, 13)
PORTAL_HOOK_STUB(portal_hook_14, 112, 14)
PORTAL_HOOK_STUB(portal_hook_15, 120, 15)
PORTAL_HOOK_STUB(portal_hook_16, 128, 16)
PORTAL_HOOK_STUB(portal_hook_17, 136, 17)
PORTAL_HOOK_STUB(portal_hook_18, 144, 18)
PORTAL_HOOK_STUB(portal_hook_19, 152, 19)
PORTAL_HOOK_STUB(portal_hook_20, 160, 20)
PORTAL_HOOK_STUB(portal_hook_21, 168, 21)
PORTAL_HOOK_STUB(portal_hook_22, 176, 22)
PORTAL_HOOK_STUB(portal_hook_23, 184, 23)
PORTAL_HOOK_STUB(portal_hook_24, 192, 24)
PORTAL_HOOK_STUB(portal_hook_25, 200, 25)
PORTAL_HOOK_STUB(portal_hook_26, 208, 26)
PORTAL_HOOK_STUB(portal_hook_27, 216, 27)
PORTAL_HOOK_STUB(portal_hook_28, 224, 28)
PORTAL_HOOK_STUB(portal_hook_29, 232, 29)
PORTAL_HOOK_STUB(portal_hook_30, 240, 30)
PORTAL_HOOK_STUB(portal_hook_31, 248, 31)

/**
 * **空钩子**：只重放被顶掉的 4 条指令，然后跳回 entry+16 —— 不调用任何 C 代码。
 * 用来把"补丁机制本身是否透明"与"C 记账函数有没有副作用"彻底分开。
 */
__attribute__((used)) static volatile unsigned long g_hook_resume_null[2];

#define PORTAL_HOOK_STUB_NULL(NAME, OFF)                                          \
    __attribute__((naked)) static void NAME(void) {                               \
        __asm__ volatile(                                                         \
            "nop\n" "nop\n" "nop\n" "nop\n"   /* +0 处：安装时写成被顶掉的 4 条 */ \
            "adrp x16, g_hook_resume_null\n"                                     \
            "add x16, x16, #:lo12:g_hook_resume_null\n"                          \
            "ldr x16, [x16, #" #OFF "]\n"                                        \
            "mov x30, x16\n"                                                      \
            "ret\n");                                                         \
    }

PORTAL_HOOK_STUB_NULL(portal_hook_null0, 0)
PORTAL_HOOK_STUB_NULL(portal_hook_null1, 8)

/**
 * **裸 ret 被调者**：没有任何序言、不碰 TLS/栈/其它寄存器。
 * 配合下面的 touch 外壳，用来分辨"从钩子里发起一次调用"与"我们的 C 函数内部做了什么"
 * 这两件事里到底是哪一件打断了会话。
 */
__attribute__((naked, used)) static void portal_hook_touch_c(void) {
    __asm__ volatile("ret\n");
}

__attribute__((used)) static volatile unsigned long g_hook_resume_touch[2];

#define PORTAL_HOOK_STUB_TOUCH(NAME, OFF)                                          \
    __attribute__((naked)) static void NAME(void) {                                \
        __asm__ volatile(                                                          \
            "mov x9, x30\n" "mov x10, x0\n" "mov x11, x1\n"                     \
            "mov x12, x29\n" "mov x13, sp\n"                                     \
            "nop\n" "nop\n" "nop\n" "nop\n"   /* +20：被顶掉的 4 条 */          \
            "sub sp, sp, #0x60\n"                                                 \
            "stp x0, x1, [sp, #0x00]\n" "stp x2, x3, [sp, #0x10]\n"              \
            "stp x4, x5, [sp, #0x20]\n" "stp x6, x7, [sp, #0x30]\n"              \
            "stp x8, x18, [sp, #0x40]\n"                                          \
            "bl portal_hook_touch_c\n"                                            \
            "ldp x0, x1, [sp, #0x00]\n" "ldp x2, x3, [sp, #0x10]\n"              \
            "ldp x4, x5, [sp, #0x20]\n" "ldp x6, x7, [sp, #0x30]\n"              \
            "ldp x8, x18, [sp, #0x40]\n"                                          \
            "add sp, sp, #0x60\n"                                                 \
            "adrp x16, g_hook_resume_touch\n"                                     \
            "add x16, x16, #:lo12:g_hook_resume_touch\n"                          \
            "ldr x16, [x16, #" #OFF "]\n"                                         \
            "mov x30, x16\n"                                                      \
            "ret\n");                                                          \
    }

PORTAL_HOOK_STUB_TOUCH(portal_hook_touch0, 0)
PORTAL_HOOK_STUB_TOUCH(portal_hook_touch1, 8)

/*
 * **nrec** = "naked record"：外壳用**已被证明透明**的 touch 形态（5 条 mov + 重放 +
 * 压栈保存 x0..x7/x8/x18 + 一次 `bl` + 恢复），只是把被调者换成**手写的裸汇编记录器**。
 * 这样既不触发 C 编译器生成的那些调用（可能引发 PLT 惰性绑定死锁），又真的把
 * 调用者/参数记下来。x9/x10/x11 由外壳在重放前就抄好（caller / a0 / a1）。
 */
__attribute__((naked, used)) static void portal_hook_nrec0_c(void) {
    __asm__ volatile(
        "adrp x16, g_hook_rec\n" "add x16, x16, #:lo12:g_hook_rec\n"
        "ldr x17, [x16]\n" "add x17, x17, #1\n" "str x17, [x16]\n"
        "cmp x17, #1\n" "b.ne 1f\n"
        "str x9, [x16, #8]\n" "str x10, [x16, #16]\n" "str x11, [x16, #24]\n"
        "1:\n" "ret\n");
}
__attribute__((naked, used)) static void portal_hook_nrec1_c(void) {
    __asm__ volatile(
        "adrp x16, (g_hook_rec + 32)\n" "add x16, x16, #:lo12:g_hook_rec\n"
        "add x16, x16, #32\n"
        "ldr x17, [x16]\n" "add x17, x17, #1\n" "str x17, [x16]\n"
        "cmp x17, #1\n" "b.ne 1f\n"
        "str x9, [x16, #8]\n" "str x10, [x16, #16]\n" "str x11, [x16, #24]\n"
        "1:\n" "ret\n");
}

__attribute__((used)) static volatile unsigned long g_hook_resume_nrec[2];

#define PORTAL_HOOK_STUB_NREC(NAME, OFF, CALLEE)                                   \
    __attribute__((naked)) static void NAME(void) {                                \
        __asm__ volatile(                                                          \
            "mov x9, x30\n" "mov x10, x0\n" "mov x11, x1\n"                     \
            "mov x12, x29\n" "mov x13, sp\n"                                     \
            "nop\n" "nop\n" "nop\n" "nop\n"                                    \
            "sub sp, sp, #0x60\n"                                                 \
            "stp x0, x1, [sp, #0x00]\n" "stp x2, x3, [sp, #0x10]\n"              \
            "stp x4, x5, [sp, #0x20]\n" "stp x6, x7, [sp, #0x30]\n"              \
            "stp x8, x18, [sp, #0x40]\n"                                          \
            "bl " #CALLEE "\n"                                                    \
            "ldp x0, x1, [sp, #0x00]\n" "ldp x2, x3, [sp, #0x10]\n"              \
            "ldp x4, x5, [sp, #0x20]\n" "ldp x6, x7, [sp, #0x30]\n"              \
            "ldp x8, x18, [sp, #0x40]\n"                                          \
            "add sp, sp, #0x60\n"                                                 \
            "adrp x16, g_hook_resume_nrec\n"                                      \
            "add x16, x16, #:lo12:g_hook_resume_nrec\n"                           \
            "ldr x16, [x16, #" #OFF "]\n"                                         \
            "mov x30, x16\n"                                                      \
            "ret\n");                                                          \
    }

PORTAL_HOOK_STUB_NREC(portal_hook_nrec0, 0, portal_hook_nrec0_c)
PORTAL_HOOK_STUB_NREC(portal_hook_nrec1, 8, portal_hook_nrec1_c)

/**
 * **零调用钩子**（正式形态）：重放被顶掉的 4 条 → 内联汇编里计数并记下首次现场 → 跳回。
 * 全程没有任何 `bl`，因此不会在 vendor 的锁里触发 PLT 惰性绑定。
 */
#define PORTAL_HOOK_STUB_REC(NAME, ROFF, DOFF)                                     \
    __attribute__((naked)) static void NAME(void) {                                \
        __asm__ volatile(                                                          \
            "mov x9, x30\n" "mov x10, x0\n" "mov x11, x1\n"                     \
            "nop\n" "nop\n" "nop\n" "nop\n"   /* +20：被顶掉的 4 条 */          \
            "adrp x16, g_hook_rec\n"                                              \
            "add x16, x16, #:lo12:g_hook_rec\n"                                   \
            "add x16, x16, #" #DOFF "\n"                                          \
            "ldr x17, [x16]\n"                                                    \
            "add x17, x17, #1\n"                                                  \
            "str x17, [x16]\n"                                                    \
            "cmp x17, #1\n"                                                       \
            "b.ne 1f\n"                                                           \
            "str x9, [x16, #8]\n"                                                 \
            "str x10, [x16, #16]\n"                                               \
            "str x11, [x16, #24]\n"                                               \
            "1:\n"                                                                \
            "adrp x16, g_hook_resume_rec\n"                                       \
            "add x16, x16, #:lo12:g_hook_resume_rec\n"                            \
            "ldr x16, [x16, #" #ROFF "]\n"                                        \
            "mov x30, x16\n"                                                      \
            "ret\n");                                                          \
    }

/**
 * 请求快照（只由裸汇编外壳写、只由汇报线程读；两边都不调用任何函数）。
 * 字段偏移与外壳里的 `str …, [x16, #N]` 一一对应 —— 改一处必须改两处。
 */
__attribute__((used)) static struct portal_req_snap {
    unsigned long cnt;    /* +0  命中次数 */
    unsigned long caller; /* +8  首次调用的返回地址 */
    unsigned long dev;    /* +16 a0 = camera3_device_t* */
    unsigned long req;    /* +24 a1 = 请求结构指针 */
    unsigned long q[24];  /* +32 req[0..23] **原样** 192 字节快照 */
} g_req_snap;
__attribute__((used)) static volatile unsigned long g_hook_resume_req;

/**
 * 请求入口外壳：**零调用、只做一级解引用**。
 *
 * ⚠️ 血的教训（本机实测，tombstone 里 `#00 portal_hook_req0+132`、fault addr 0x7700000000）：
 * 第一版在这里按"标准 camera3_capture_request_t 布局"继续往二级解引用
 * （`req[+8]` → 缓冲数组 → `[+8]` → gralloc handle），结果那个位置的指针是脏的，
 * 钩子直接在 vendor 的请求路径里把 provider 打死，连带相机 App 一起死。
 * **热路径里不许有"我以为布局是这样"的解引用** —— 外壳只抄那一级契约保证有效的内存，
 * 布局判断全部搬到我们自己的线程里，用 /proc/self/maps 逐个指针验完再读。
 */
#define PORTAL_HOOK_STUB_REQ(NAME)                                                  \
    __attribute__((naked)) static void NAME(void) {                                 \
        __asm__ volatile(                                                           \
            "mov x9, x30\n" "mov x10, x0\n" "mov x11, x1\n"                     \
            "nop\n" "nop\n" "nop\n" "nop\n"  /* +12：被顶掉的 4 条 */          \
            "adrp x16, g_req_snap\n"                                               \
            "add x16, x16, #:lo12:g_req_snap\n"                                    \
            "ldr x17, [x16]\n" "add x17, x17, #1\n" "str x17, [x16]\n"           \
            "cmp x17, #1\n" "b.ne 1f\n"                                           \
            "str x9,  [x16, #8]\n"                                                 \
            "str x10, [x16, #16]\n"                                                \
            "str x11, [x16, #24]\n"                                                \
            "1:\n"                                                                 \
            "cbz x11, 2f\n"                                                        \
            "ldr x17, [x11]\n" "str x17, [x16, #32]\n"                       \
            "ldr x17, [x11, #8]\n" "str x17, [x16, #40]\n"                       \
            "ldr x17, [x11, #16]\n" "str x17, [x16, #48]\n"                       \
            "ldr x17, [x11, #24]\n" "str x17, [x16, #56]\n"                       \
            "ldr x17, [x11, #32]\n" "str x17, [x16, #64]\n"                       \
            "ldr x17, [x11, #40]\n" "str x17, [x16, #72]\n"                       \
            "ldr x17, [x11, #48]\n" "str x17, [x16, #80]\n"                       \
            "ldr x17, [x11, #56]\n" "str x17, [x16, #88]\n"                       \
            "ldr x17, [x11, #64]\n" "str x17, [x16, #96]\n"                       \
            "ldr x17, [x11, #72]\n" "str x17, [x16, #104]\n"                       \
            "ldr x17, [x11, #80]\n" "str x17, [x16, #112]\n"                       \
            "ldr x17, [x11, #88]\n" "str x17, [x16, #120]\n"                       \
            "ldr x17, [x11, #96]\n" "str x17, [x16, #128]\n"                       \
            "ldr x17, [x11, #104]\n" "str x17, [x16, #136]\n"                       \
            "ldr x17, [x11, #112]\n" "str x17, [x16, #144]\n"                       \
            "ldr x17, [x11, #120]\n" "str x17, [x16, #152]\n"                       \
            "ldr x17, [x11, #128]\n" "str x17, [x16, #160]\n"                       \
            "ldr x17, [x11, #136]\n" "str x17, [x16, #168]\n"                       \
            "ldr x17, [x11, #144]\n" "str x17, [x16, #176]\n"                       \
            "ldr x17, [x11, #152]\n" "str x17, [x16, #184]\n"                       \
            "ldr x17, [x11, #160]\n" "str x17, [x16, #192]\n"                       \
            "ldr x17, [x11, #168]\n" "str x17, [x16, #200]\n"                       \
            "ldr x17, [x11, #176]\n" "str x17, [x16, #208]\n"                       \
            "ldr x17, [x11, #184]\n" "str x17, [x16, #216]\n"                       \
            "2:\n"                                                                 \
            "adrp x16, g_hook_resume_req\n"                                        \
            "add x16, x16, #:lo12:g_hook_resume_req\n"                             \
            "ldr x16, [x16]\n"                                                     \
            "mov x30, x16\n"                                                       \
            "ret\n");                                                              \
    }

PORTAL_HOOK_STUB_REQ(portal_hook_req0)

__attribute__((used)) static volatile unsigned long g_hook_resume_rec[PORTAL_HOOK_MAX];
PORTAL_HOOK_STUB_REC(portal_hook_rec00, 0, 0)
PORTAL_HOOK_STUB_REC(portal_hook_rec01, 8, 32)
PORTAL_HOOK_STUB_REC(portal_hook_rec02, 16, 64)
PORTAL_HOOK_STUB_REC(portal_hook_rec03, 24, 96)
PORTAL_HOOK_STUB_REC(portal_hook_rec04, 32, 128)
PORTAL_HOOK_STUB_REC(portal_hook_rec05, 40, 160)
PORTAL_HOOK_STUB_REC(portal_hook_rec06, 48, 192)
PORTAL_HOOK_STUB_REC(portal_hook_rec07, 56, 224)
PORTAL_HOOK_STUB_REC(portal_hook_rec08, 64, 256)
PORTAL_HOOK_STUB_REC(portal_hook_rec09, 72, 288)
PORTAL_HOOK_STUB_REC(portal_hook_rec10, 80, 320)
PORTAL_HOOK_STUB_REC(portal_hook_rec11, 88, 352)
PORTAL_HOOK_STUB_REC(portal_hook_rec12, 96, 384)
PORTAL_HOOK_STUB_REC(portal_hook_rec13, 104, 416)
PORTAL_HOOK_STUB_REC(portal_hook_rec14, 112, 448)
PORTAL_HOOK_STUB_REC(portal_hook_rec15, 120, 480)
PORTAL_HOOK_STUB_REC(portal_hook_rec16, 128, 512)
PORTAL_HOOK_STUB_REC(portal_hook_rec17, 136, 544)
PORTAL_HOOK_STUB_REC(portal_hook_rec18, 144, 576)
PORTAL_HOOK_STUB_REC(portal_hook_rec19, 152, 608)
PORTAL_HOOK_STUB_REC(portal_hook_rec20, 160, 640)
PORTAL_HOOK_STUB_REC(portal_hook_rec21, 168, 672)
PORTAL_HOOK_STUB_REC(portal_hook_rec22, 176, 704)
PORTAL_HOOK_STUB_REC(portal_hook_rec23, 184, 736)
PORTAL_HOOK_STUB_REC(portal_hook_rec24, 192, 768)
PORTAL_HOOK_STUB_REC(portal_hook_rec25, 200, 800)
PORTAL_HOOK_STUB_REC(portal_hook_rec26, 208, 832)
PORTAL_HOOK_STUB_REC(portal_hook_rec27, 216, 864)
PORTAL_HOOK_STUB_REC(portal_hook_rec28, 224, 896)
PORTAL_HOOK_STUB_REC(portal_hook_rec29, 232, 928)
PORTAL_HOOK_STUB_REC(portal_hook_rec30, 240, 960)
PORTAL_HOOK_STUB_REC(portal_hook_rec31, 248, 992)

static void *const g_hook_rec_fns[PORTAL_HOOK_MAX] = {
    (void *)portal_hook_rec00, (void *)portal_hook_rec01, (void *)portal_hook_rec02,
    (void *)portal_hook_rec03, (void *)portal_hook_rec04, (void *)portal_hook_rec05,
    (void *)portal_hook_rec06, (void *)portal_hook_rec07, (void *)portal_hook_rec08,
    (void *)portal_hook_rec09, (void *)portal_hook_rec10, (void *)portal_hook_rec11,
    (void *)portal_hook_rec12, (void *)portal_hook_rec13, (void *)portal_hook_rec14,
    (void *)portal_hook_rec15, (void *)portal_hook_rec16, (void *)portal_hook_rec17,
    (void *)portal_hook_rec18, (void *)portal_hook_rec19,
    (void *)portal_hook_rec20,
    (void *)portal_hook_rec21,
    (void *)portal_hook_rec22,
    (void *)portal_hook_rec23,
    (void *)portal_hook_rec24,
    (void *)portal_hook_rec25,
    (void *)portal_hook_rec26,
    (void *)portal_hook_rec27,
    (void *)portal_hook_rec28,
    (void *)portal_hook_rec29,
    (void *)portal_hook_rec30,
    (void *)portal_hook_rec31,
};

static void *const g_hook_fns[PORTAL_HOOK_MAX] = {
    (void *)portal_hook_00, (void *)portal_hook_01, (void *)portal_hook_02, (void *)portal_hook_03,
    (void *)portal_hook_04, (void *)portal_hook_05, (void *)portal_hook_06, (void *)portal_hook_07,
    (void *)portal_hook_08, (void *)portal_hook_09, (void *)portal_hook_10, (void *)portal_hook_11,
    (void *)portal_hook_12, (void *)portal_hook_13, (void *)portal_hook_14, (void *)portal_hook_15,
    (void *)portal_hook_16, (void *)portal_hook_17, (void *)portal_hook_18,
    (void *)portal_hook_19,
    (void *)portal_hook_20,
    (void *)portal_hook_21,
    (void *)portal_hook_22,
    (void *)portal_hook_23,
    (void *)portal_hook_24,
    (void *)portal_hook_25,
    (void *)portal_hook_26,
    (void *)portal_hook_27,
    (void *)portal_hook_28,
    (void *)portal_hook_29,
    (void *)portal_hook_30,
    (void *)portal_hook_31,
};

/** 把各入口的累计命中打出来 —— 只在 open 这种非热路径上调用，钩子体自身不做 I/O */
static void portal_hook_report_counts(void) {
    for (int k = 0; k < PORTAL_HOOK_MAX; k++) {
        unsigned long c = g_hook_rec[k].cnt;
        if (c != 0) {
            char w[192] = {0};
            portal_where((const void *)g_hook_rec[k].caller, w, sizeof(w));
            LOGI("零调用钩子[%d] %s 命中 %lu 次；首次：caller=%p（%s）a0=%p a1=%p", k,
                 g_hook_name[k] ? g_hook_name[k] : "?", c, (void *)g_hook_rec[k].caller, w,
                 (void *)g_hook_rec[k].a0, (void *)g_hook_rec[k].a1);
        }
        unsigned long c2 = __atomic_load_n(&g_hook_cnt[k], __ATOMIC_RELAXED);
        if (c2 != 0) {
            LOGI("钩子累计：入口[%d] %s 命中 %lu 次", k, g_hook_name[k] ? g_hook_name[k] : "?", c2);
        }
    }
}

/**
 * 汇报请求快照 —— 同样只在**我们自己的线程**里做。
 * `req_probe=1` 时顺带做一次 dmabuf 只读 mmap 试探：只为确认"拿到 fd 就能映射到帧缓冲"
 * 这条路在 ACE5 上成立（图像注入的前提）。写像素留到下一步。
 */
/**
 * **安全窥视**：一切"异步读来的指针"都用 `/proc/self/mem` 的 `pread` 去读。
 * 为什么不用直接解引用（同一轮踩过）：脏指针会当场 SIGSEGV，把 provider 与相机 App 一起带走
 * （tombstone：`#00 portal_hook_req0+132`、fault addr 0x7700000000）；
 * `pread` 遇到坏地址只返回 EFAULT/EIO，**不可能崩**。这是"零调用钩子 + 线程侧解析"
 * 这套结构里唯一正确的读法。
 */
static int portal_peek(unsigned long addr, void *buf, size_t len) {
    if (addr == 0 || buf == NULL) return -1;
    int fd = open("/proc/self/mem", O_RDONLY);
    if (fd < 0) return -1;
    ssize_t r = pread(fd, buf, len, (off_t)portal_untag((const void *)addr));
    close(fd);
    return (r == (ssize_t)len) ? 0 : -1;
}

/**
 * **实测行 stride**（不猜布局）：同一幅图像相邻两行高度相关，所以"行间平均绝对差"
 * 在真 stride 处取极小值。候选 2048..4128 步进 32，打印最好的 5 个。
 * 只读映射 + 只读内存，不可能崩。
 */
static void portal_stride_probe(void) {
    if (!g_opt_stride_probe) return;
    int target = -1;
    unsigned long tsize = 0;
    DIR *d = opendir("/proc/self/fd");
    if (d == NULL) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd < 3) continue;
        char path[64];
        char link[192] = {0};
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        ssize_t r = readlink(path, link, sizeof(link) - 1);
        if (r <= 0) continue;
        link[r] = '\0';
        if (strstr(link, "dmabuf") == NULL) continue;
        struct stat st;
        if (fstat(fd, &st) != 0) continue;
        unsigned long want = g_opt_probe_size ? g_opt_probe_size : g_opt_inject_size;
        if (want != 0 && (unsigned long)st.st_size != want) continue;
        if (want == 0 && st.st_size < 1048576) continue;
        target = fd;
        tsize = (unsigned long)st.st_size;
        break;
    }
    closedir(d);
    if (target < 0) {
        LOGI("stride 试探：没找到目标缓冲（inject_size=%lu）", g_opt_inject_size);
        return;
    }
    unsigned char *m = (unsigned char *)mmap(NULL, tsize, PROT_READ, MAP_SHARED, target, 0);
    if (m == MAP_FAILED) {
        LOGI("stride 试探：fd=%d mmap 失败（errno=%d）", target, errno);
        return;
    }
    LOGI("stride 试探：fd=%d 共 %lu 字节，行间相关性扫描（注入应当关闭，否则读到的是我们自己填的值）",
         target, tsize);
    unsigned long bases[2] = {0, 1000000};
    for (int bi = 0; bi < 2; bi++) {
    unsigned long base = bases[bi];
    if (base + 65536 > tsize) continue;
    int best[5] = {0};
    long bestc[5] = {0};
    for (int st = 2048; st <= 4128; st += 32) {
        if (base + (unsigned long)st * 8 >= tsize) break;
        long cost = 0;
        int n = 0;
        for (int row = 0; row < 8; row++) {
            const unsigned char *a = m + base + (size_t)row * st;
            const unsigned char *b = a + st;
            for (int x = 0; x < 512; x++) {
                int diff = (int)a[x] - (int)b[x];
                cost += diff < 0 ? -diff : diff;
            }
            n += 512;
        }
        long avg = n ? cost / n : 0;
        for (int k = 0; k < 5; k++) {
            if (best[k] == 0 || avg < bestc[k]) {
                for (int j = 4; j > k; j--) { best[j] = best[j - 1]; bestc[j] = bestc[j - 1]; }
                best[k] = st;
                bestc[k] = avg;
                break;
            }
        }
    }
    LOGI("  —— 基准偏移 0x%lx ——", base);
    for (int k = 0; k < 5; k++) {
        if (best[k] != 0) LOGI("  stride 候选 %d：平均行差 %ld", best[k], bestc[k]);
    }
    }
    /* 用最优 stride 看"行相关"沿纵向怎么变：Y 平面结束、UV 平面开始的位置会变形 */
    int bs = 0;
    {
        long bc = 0;
        for (int st = 2048; st <= 4128; st += 32) {
            if ((unsigned long)st * 8 >= tsize) continue;
            long cost = 0;
            for (int row = 0; row < 8; row++) {
                const unsigned char *a = m + (size_t)row * st;
                const unsigned char *b = a + st;
                for (int x = 0; x < 512; x++) {
                    int diff = (int)a[x] - (int)b[x];
                    cost += diff < 0 ? -diff : diff;
                }
            }
            if (bs == 0 || cost < bc) { bs = st; bc = cost; }
        }
    }
    if (bs > 0) {
        LOGI("  纵向相关性剖面（stride=%d）：", bs);
        for (unsigned long row = 0; row + 2 < tsize / (unsigned long)bs; row += 200) {
            const unsigned char *a = m + row * (unsigned long)bs;
            const unsigned char *b = a + bs;
            long cost = 0;
            for (int x = 0; x < 512; x++) {
                int diff = (int)a[x] - (int)b[x];
                cost += diff < 0 ? -diff : diff;
            }
            LOGI("  第 %lu 行（偏移 %lu）行差 %ld", row, row * (unsigned long)bs, cost / 512);
        }
    }
    munmap(m, tsize);
}

/**
 * `bufmap=1`：把目标缓冲**整块扫一遍**，按 256KB 一段打印"非零占比 / 最小 / 最大"。
 * 为什么需要：行 stride 试探第一轮全是"平均行差 0" —— 说明缓冲开头是**全零**，
 * 图像数据根本不在偏移 0 处。先看清数据在哪儿，再谈布局。
 */
static void portal_bufmap(void) {
    if (!g_opt_bufmap) return;
    int fds[4] = {-1, -1, -1, -1};
    unsigned long szs[4] = {0};
    int n = 0;
    DIR *d = opendir("/proc/self/fd");
    if (d == NULL) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && n < 4) {
        int fd = atoi(e->d_name);
        if (fd < 3) continue;
        char path[64];
        char link[192] = {0};
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        ssize_t r = readlink(path, link, sizeof(link) - 1);
        if (r <= 0) continue;
        link[r] = '\0';
        if (strstr(link, "dmabuf") == NULL) continue;
        struct stat st;
        if (fstat(fd, &st) != 0) continue;
        unsigned long want2 = g_opt_probe_size ? g_opt_probe_size : g_opt_inject_size;
        if (want2 != 0 && (unsigned long)st.st_size != want2) continue;
        fds[n] = fd;
        szs[n] = (unsigned long)st.st_size;
        n++;
    }
    closedir(d);
    if (n == 0) { LOGI("bufmap：没找到目标缓冲"); return; }
    for (int i = 0; i < n; i++) {
        unsigned char *m = (unsigned char *)mmap(NULL, szs[i], PROT_READ, MAP_SHARED, fds[i], 0);
        if (m == MAP_FAILED) { LOGI("bufmap：fd=%d mmap 失败", fds[i]); continue; }
        LOGI("bufmap fd=%d size=%lu：", fds[i], szs[i]);
        for (unsigned long off = 0; off + 262144 <= szs[i]; off += 262144) {
            int nz = 0, mn = 255, mx = 0;
            for (int k = 0; k < 1024; k++) {
                unsigned char v = m[off + (unsigned long)k * 256];
                if (v) nz++;
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
            LOGI("   +0x%06lx  非零 %4d/1024  min=%3d max=%3d", off, nz, mn, mx);
        }
        munmap(m, szs[i]);
    }
}

/**
 * JPEG 码流探针：扫所有 dmabuf 的前 4KB，找 `FF D8 FF`（JPEG SOI/APPn）。
 * 每 300 毫秒跑一次 —— JPEG 只在快门后短暂存在，2 秒的周期很可能整段错过。
 */
static void portal_jpeg_probe(void) {
    if (!g_opt_jpeg_probe) return;
    DIR *d = opendir("/proc/self/fd");
    if (d == NULL) return;
    struct dirent *e;
    int hits = 0;
    while ((e = readdir(d)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd < 3) continue;
        char path[64];
        char link[192] = {0};
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        ssize_t r = readlink(path, link, sizeof(link) - 1);
        if (r <= 0) continue;
        link[r] = '\0';
        if (strstr(link, "dmabuf") == NULL) continue;
        struct stat st;
        if (fstat(fd, &st) != 0 || st.st_size < 262144) continue;
        unsigned char *m = (unsigned char *)mmap(NULL, 4096, PROT_READ, MAP_SHARED, fd, 0);
        if (m == MAP_FAILED) continue;
        for (int off = 0; off < 4096 - 3; off++) {
            if (m[off] == 0xFF && m[off + 1] == 0xD8 && m[off + 2] == 0xFF) {
                LOGI("JPEG 命中：fd=%d size=%ld 偏移 %d，名前 8 字节 %02x %02x %02x %02x %02x %02x "
                     "%02x %02x（%s）", fd, (long)st.st_size, off, m[off], m[off + 1], m[off + 2],
                     m[off + 3], m[off + 4], m[off + 5], m[off + 6], m[off + 7], link);
                hits++;
                break;
            }
        }
        munmap(m, 4096);
    }
    closedir(d);
    if (hits == 0) LOGI("JPEG 探针：本轮没有 JPEG 码流");
}

/** 进程自己持有的 dmabuf 清单 —— 相机输出缓冲就在里面（先看清再动手） */
static void portal_dmabuf_report(void) {
    DIR *d = opendir("/proc/self/fd");
    if (d == NULL) return;
    struct dirent *e;
    int found = 0, shown = 0;
    unsigned long long biggest = 0;
    while ((e = readdir(d)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd < 3) continue;
        char path[64];
        char link[192] = {0};
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        ssize_t n = readlink(path, link, sizeof(link) - 1);
        if (n <= 0) continue;
        link[n] = '\0';
        if (strstr(link, "dmabuf") == NULL && strstr(link, "dma-buf") == NULL) continue;
        struct stat st;
        memset(&st, 0, sizeof(st));
        if (fstat(fd, &st) != 0) continue;
        /* 只要"像帧缓冲"的：小对象（传感器/元数据 4K 那些）全部略过，日志才有用 */
        if (st.st_size >= 524288 && shown < 40) {
            LOGI("  dmabuf fd=%d size=%lld link=%s", fd, (long long)st.st_size, link);
            shown++;
        }
        if ((unsigned long long)st.st_size > biggest) biggest = (unsigned long long)st.st_size;
        found++;
    }
    closedir(d);
    LOGI("  dmabuf 合计 %d 个，最大 %llu 字节，上面列的是 ≥512KB 的（最多 40 个）", found, biggest);
}

/**
 * 注入线程：把指定 dmabuf 持续填成同一个字节。
 *
 * 为什么放在**我们自己的线程**：帧缓冲是 mmap 出来的，写它就是普通内存写 —— 不需要 call
 * 进 vendor 的任何锁，也不需要钩子做重活。热路径只负责把"哪块缓冲、多大"记下来。
 */
/** 结果投递快照：钩子只写这几个字，解析全交给 res_writer 线程 */
__attribute__((used)) static struct portal_res_snap {
    unsigned long cnt;    /* +0  命中次数 */
    unsigned long caller; /* +8  首次调用的返回地址 */
    unsigned long dev;    /* +16 a0（callback_ops） */
    unsigned long res;    /* +24 a1（camera3_capture_result*）—— 每次都写 */
    unsigned long gen;    /* +32 代次，每次调用 +1（写线程靠它触发） */
} g_res_snap;
/** 写线程完成后置 1；钩子在这里**有限自旋**，保证"投递时缓冲里已经是我们的内容" */
__attribute__((used)) static volatile unsigned long g_res_done;

/**
 * 结果钩子外壳：**零调用**。除记账外只做三件事 —— 存结果指针、代次 +1、把 done 清零后
 * **有限自旋**等写线程完成（上限约 2~4 毫秒，超时也放行，绝不把 HAL 线程挂死）。
 */
#define PORTAL_HOOK_STUB_RES(NAME, ROFF)                                            \
    __attribute__((naked)) static void NAME(void) {                                 \
        __asm__ volatile(                                                           \
            "mov x9, x30\n" "mov x10, x0\n" "mov x11, x1\n"                     \
            "nop\n" "nop\n" "nop\n" "nop\n"  /* +12：被顶掉的 4 条 */          \
            "adrp x16, g_res_snap\n"                                               \
            "add x16, x16, #:lo12:g_res_snap\n"                                    \
            "ldr x17, [x16]\n" "add x17, x17, #1\n" "str x17, [x16]\n"           \
            "cmp x17, #1\n" "b.ne 1f\n"                                           \
            "str x9,  [x16, #8]\n"                                                 \
            "str x10, [x16, #16]\n"                                                \
            "1:\n"                                                                 \
            "str x11, [x16, #24]\n"                                                \
            "ldr x17, [x16, #32]\n" "add x17, x17, #1\n" "str x17, [x16, #32]\n" \
            "adrp x12, g_res_done\n"                                               \
            "add x12, x12, #:lo12:g_res_done\n"                                    \
            "str xzr, [x12]\n"                                                     \
            /* 2,000,000 次自旋上限：单个 mov 立即数编码不了，必须 movz+movk */      \
            "movz x17, #0x8480\n" "movk x17, #0x1e, lsl #16\n"                    \
            "2:\n" "ldr x10, [x12]\n" "cbnz x10, 3f\n"                           \
            "subs x17, x17, #1\n" "b.ne 2b\n"                                     \
            "3:\n"                                                                 \
            "adrp x16, " #ROFF "\n"                                                \
            "add x16, x16, #:lo12:" #ROFF "\n"                                     \
            "ldr x16, [x16]\n"                                                     \
            "mov x30, x16\n"                                                       \
            "ret\n");                                                              \
    }

__attribute__((used)) static volatile unsigned long g_hook_resume_res;
PORTAL_HOOK_STUB_RES(portal_hook_res0, g_hook_resume_res)

#define PORTAL_INJECT_MAX 24
#define PORTAL_MOCK_MAX 6

/** `mock_map=<尺寸>:<路径>[,<尺寸>:<路径>…]`：**按缓冲尺寸分别给模板**。
 *  为什么需要：预览/录像是不同尺寸的流（实测预览 4,038,656、录像 3,194,880），
 *  一个模板套不了两张画面 —— 图片与视频必须各给一份。 */

typedef struct {
    unsigned long size;
    char name[48];   /* 可选：dmabuf 名字里必须含这段（`<尺寸>@<子串>:<路径>`） */
    char path[176];
    unsigned char *tpl;
    size_t tlen;
} portal_mock_entry;
static portal_mock_entry g_mock[PORTAL_MOCK_MAX];
static int g_mock_n = 0;

/** 解析 mock_map（只解析一次，在 load_options 之后） */
static void portal_mock_parse(void) {
    if (g_opt_mock_map[0] == '\0' || g_mock_n != 0) return;
    char buf[sizeof(g_opt_mock_map)];
    snprintf(buf, sizeof(buf), "%s", g_opt_mock_map);
    char *save = NULL;
    for (char *tok = strtok_r(buf, ",", &save); tok != NULL && g_mock_n < PORTAL_MOCK_MAX;
         tok = strtok_r(NULL, ",", &save)) {
        char *colon = strchr(tok, ':');
        if (colon == NULL) continue;
        *colon = '\0';
        char *at = strchr(tok, '@');
        if (at != NULL) {
            *at = '\0';
            snprintf(g_mock[g_mock_n].name, sizeof(g_mock[g_mock_n].name), "%s", at + 1);
        }
        unsigned long sz = strtoul(tok, NULL, 0);
        if (sz == 0) continue;
        g_mock[g_mock_n].size = sz;
        snprintf(g_mock[g_mock_n].path, sizeof(g_mock[g_mock_n].path), "%s", colon + 1);
        g_mock_n++;
    }
}

/** 每个模板只读一次 */
static void portal_mock_load(void) {
    for (int i = 0; i < g_mock_n; i++) {
        if (g_mock[i].tpl != NULL) continue;
        int fd = open(g_mock[i].path, O_RDONLY);
        if (fd < 0) {
            LOGE("注入：打不开模板 %s（errno=%d）", g_mock[i].path, errno);
            continue;
        }
        struct stat st;
        if (fstat(fd, &st) == 0 && st.st_size > 0) {
            unsigned char *t = (unsigned char *)malloc((size_t)st.st_size);
            if (t != NULL && read(fd, t, (size_t)st.st_size) == st.st_size) {
                g_mock[i].tpl = t;
                g_mock[i].tlen = (size_t)st.st_size;
                LOGI("注入：模板[%lu%s%s] ← %s（%zu 字节）", g_mock[i].size,
                     g_mock[i].name[0] ? "@" : "", g_mock[i].name, g_mock[i].path, g_mock[i].tlen);
            } else {
                free(t);
                LOGE("注入：读模板失败 %s", g_mock[i].path);
            }
        }
        close(fd);
    }
}

/** 预生成的纯色/渐变模板（旧路径：inject_pattern / inject_byte） */
static unsigned char *g_inject_tpl = NULL;
static size_t g_inject_tpl_size = 0;

typedef struct {
    void *p;
    size_t size;
    const unsigned char *tpl;   /* NULL ⇒ 用 inject_byte 纯色填 */
    size_t tlen;
} portal_target;

/**
 * 扫 /proc/self/fd，把"尺寸能在 mock_map 里找到模板"（或等于 inject_size）的 dmabuf 全部映射下来。
 * 只读罗列 → 按尺寸选靶，是"图片"与"视频"两条流能同时被接管的关键。
 */
static int portal_build_targets(portal_target t[], int cap) {
    int n = 0;
    DIR *d = opendir("/proc/self/fd");
    if (d == NULL) return 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && n < cap) {
        int fd = atoi(e->d_name);
        if (fd < 3) continue;
        char path[64];
        char link[192] = {0};
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        ssize_t r = readlink(path, link, sizeof(link) - 1);
        if (r <= 0) continue;
        link[r] = '\0';
        if (strstr(link, "dmabuf") == NULL) continue;
        struct stat st;
        if (fstat(fd, &st) != 0 || st.st_size <= 0) continue;
        unsigned long sz = (unsigned long)st.st_size;
        const unsigned char *tpl = NULL;
        size_t tlen = 0;
        for (int i = 0; i < g_mock_n; i++) {
            if (g_mock[i].size != sz || g_mock[i].tpl == NULL) continue;
            if (g_mock[i].name[0] != '\0' && strstr(link, g_mock[i].name) == NULL) continue;
            tpl = g_mock[i].tpl;
            tlen = g_mock[i].tlen;
            break;
        }
        if (tpl == NULL) {
            if (g_mock_n != 0) continue;                 /* 有映射表时：没模板的尺寸不动 */
            if (g_opt_inject_size == 0 || sz != g_opt_inject_size) continue;
            if (g_inject_tpl != NULL) { tpl = g_inject_tpl; tlen = g_inject_tpl_size; }
        }
        void *m = mmap(NULL, (size_t)sz, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (m == MAP_FAILED) {
            LOGI("注入：fd=%d size=%lu mmap 失败（errno=%d）", fd, sz, errno);
            continue;
        }
        t[n].p = m;
        t[n].size = (size_t)sz;
        t[n].tpl = tpl;
        t[n].tlen = tlen;
        LOGI("注入：fd=%d size=%lu → %p（模板 %s）", fd, sz, m, tpl ? "有" : "纯色");
        n++;
    }
    closedir(d);
    return n;
}

/**
 * 注入线程：把目标缓冲**持续**填成模板内容。
 *
 * 为什么按尺寸选靶：fd 号每次 provider 重启都会变，写死 fd 不是可复现的做法。
 * 为什么要"持续"填：相机以 30fps（录像 60fps）往同一批缓冲里写新帧，只填一次 33ms 后就没了。
 * 为什么必须 memcpy/memset：逐字节循环跑不赢 ISP 的 DMA（实测）。
 */
static void *portal_inject_thread(void *arg) {
    (void)arg;
    static portal_target t[PORTAL_INJECT_MAX];
    int n = 0;
    int announced = 0;
    /* ⚠️ 重扫必须用**真实时钟**：早先按"每轮 8 微秒"估算，而每轮其实要 memcpy 40MB（≈10ms），
     * 于是 15 秒的重扫窗口要 5 小时才到 —— 模式一切换（预览→录像换缓冲尺寸）就再也跟不上。
     * 实测症状：切到录像后注入线程仍抱着旧的一批缓冲，预览恢复成实时画面。 */
    time_t last_scan = time(NULL);

    if (g_opt_inject_size != 0 && g_mock_n == 0 && g_opt_inject_pattern != 0) {
        g_inject_tpl = (unsigned char *)malloc((size_t)g_opt_inject_size);
        if (g_inject_tpl != NULL) {
            for (size_t o = 0; o < (size_t)g_opt_inject_size; o++)
                g_inject_tpl[o] = (unsigned char)((o >> 6) & 0xFFu);
            g_inject_tpl_size = (size_t)g_opt_inject_size;
            LOGI("注入：渐变模板已生成（%zu 字节）", g_inject_tpl_size);
        }
    }
    portal_mock_parse();
    portal_mock_load();

    for (;;) {
        if (n == 0) {
            n = portal_build_targets(t, PORTAL_INJECT_MAX);
            if (n == 0) {
                sleep(2);
                continue;
            }
            size_t total = 0;
            for (int i = 0; i < n; i++) total += t[i].size;
            LOGI("注入线程：%d 块缓冲，共 %zu 字节（%d 个模板）", n, total, g_mock_n);
            announced = 1;
        }
        for (int i = 0; i < n; i++) {
            if (t[i].tpl != NULL) {
                memcpy(t[i].p, t[i].tpl, t[i].tlen < t[i].size ? t[i].tlen : t[i].size);
            } else {
                memset(t[i].p, g_opt_inject_byte, t[i].size);
            }
        }
        if (g_opt_inject_us > 0) usleep((useconds_t)g_opt_inject_us);
        time_t now = time(NULL);
        if (announced && now - last_scan >= 5) {   /* 每 5 秒重扫：换模式/重建会话都会换一批缓冲 */
            last_scan = now;
            for (int i = 0; i < n; i++) munmap(t[i].p, t[i].size);
            n = 0;
        }
    }
    return NULL;
}

static void portal_req_report(void) {
    unsigned long c = __atomic_load_n(&g_req_snap.cnt, __ATOMIC_RELAXED);
    if (c != 0 && g_req_snap.req != 0) {
        LOGI("请求快照：命中 %lu 次 device=%p req=%p caller=%p", c, (void *)g_req_snap.dev,
             (void *)g_req_snap.req, (void *)g_req_snap.caller);
        LOGI("  帧号=%u settings=%p 输出缓冲数=%lu 数组=%p priv_settings=%p priv_result=%p",
             (unsigned)g_req_snap.q[0], (void *)g_req_snap.q[1], g_req_snap.q[3] & 0xFFFFFFFFul,
             (void *)g_req_snap.q[4], (void *)g_req_snap.q[9], (void *)g_req_snap.q[11]);
        unsigned long outb = g_req_snap.q[4];
        unsigned long nout = g_req_snap.q[3] & 0xFFFFFFFFul;
        unsigned long arr[15] = {0};
        if (nout > 0 && nout <= 3 && portal_untag((const void *)outb) >= 0x1000 &&
            portal_peek(outb, arr, sizeof(arr)) == 0) {
            for (unsigned i = 0; i < nout; i++) {
                unsigned long *e = arr + i * 5;   /* 条目 0x28 字节 */
                LOGI("  out[%u]: stream=%p buffer=%p status=%u acq=%d rel=%d priv=%p", i,
                     (void *)e[0], (void *)e[1], (unsigned)e[2], (int)(e[2] >> 32), (int)e[3],
                     (void *)e[4]);
                /* camera3_stream_t：type/width/height/format/usage/max_buffers 一眼看出这是哪路流 */
                unsigned int st[12] = {0};
                if (portal_peek(e[0], st, sizeof(st)) == 0) {
                    LOGI("      stream: type=%d %ux%u format=%d(0x%x) usage=0x%x max=%u", (int)st[0],
                         st[1], st[2], (int)st[3], st[3], st[4], st[5]);
                }
            }
        }
    }
    if (g_opt_req_probe) portal_dmabuf_report();
    portal_stride_probe();
    portal_bufmap();
}

/**
 * 请求观察线程：2 毫秒一轮，只在"输出流集合"变化时打印，并顺手把每路流的几何解出来。
 * 目的是回答一个是非题：**静止/快照请求到底会不会出现在 HAL3 请求路径上**，
 * 以及它带的是哪几路流（BLOB？另一路 YUV？）。
 */
static void *portal_req_watch_thread(void *arg) {
    (void)arg;
    unsigned long last_frame = 0, last_nout = 0, last_stream = 0;
    int logged = 0;
    for (;;) {
        usleep(2000);
        unsigned long f = __atomic_load_n(&g_req_snap.q[0], __ATOMIC_RELAXED);
        unsigned long nout = __atomic_load_n(&g_req_snap.q[3], __ATOMIC_RELAXED) & 0xFFFFFFFFul;
        unsigned long ob = __atomic_load_n(&g_req_snap.q[4], __ATOMIC_RELAXED);
        if (f == last_frame) continue;
        unsigned long s0 = 0;
        if (portal_untag((const void *)ob) >= 0x1000) portal_peek(ob, &s0, sizeof(s0));
        if (nout != last_nout || s0 != last_stream) {
            last_nout = nout;
            last_stream = s0;
            if (logged < 40) {
                LOGI("请求流集合变化：帧号=%lu 输出路数=%lu 数组=%p 首流=%p", f, nout, (void *)ob,
                     (void *)s0);
                unsigned long arr[16] = {0};
                if (nout > 0 && nout <= 3 && portal_untag((const void *)ob) >= 0x1000 &&
                    portal_peek(ob, arr, sizeof(arr)) == 0) {
                    for (unsigned i = 0; i < nout; i++) {
                        unsigned int st[12] = {0};
                        if (portal_peek(arr[i * 5], st, sizeof(st)) == 0) {
                            LOGI("   流[%u] %ux%u format=%d usage=0x%x", i, st[1], st[2], (int)st[3],
                                 st[4]);
                        }
                    }
                }
                logged++;
            }
        }
        last_frame = f;
    }
    return NULL;
}

/** 现场帧落盘：睡一会儿等预览跑起来，然后把该尺寸的第一块缓冲整块写到文件 */
static void *portal_raw_dump_thread(void *arg) {
    (void)arg;
    char spec[sizeof(g_opt_raw_dump)];
    snprintf(spec, sizeof(spec), "%s", g_opt_raw_dump);
    char *colon = strchr(spec, ':');
    if (colon == NULL) return NULL;
    *colon = '\0';
    unsigned long want = strtoul(spec, NULL, 0);
    const char *path = colon + 1;
    sleep(8);   /* 等预览把 RAW 帧写起来 */
    for (int attempt = 0; attempt < 20; attempt++) {
        DIR *d = opendir("/proc/self/fd");
        if (d == NULL) break;
        struct dirent *e;
        int done = 0;
        while ((e = readdir(d)) != NULL && !done) {
            int fd = atoi(e->d_name);
            if (fd < 3) continue;
            char p2[64];
            char link[192] = {0};
            snprintf(p2, sizeof(p2), "/proc/self/fd/%d", fd);
            ssize_t r = readlink(p2, link, sizeof(link) - 1);
            if (r <= 0) continue;
            link[r] = '\0';
            if (strstr(link, "dmabuf") == NULL) continue;
            struct stat st;
            if (fstat(fd, &st) != 0 || (unsigned long)st.st_size != want) continue;
            unsigned char *m = (unsigned char *)mmap(NULL, (size_t)st.st_size, PROT_READ,
                                                     MAP_SHARED, fd, 0);
            if (m == MAP_FAILED) continue;
            int of = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
            if (of >= 0) {
                ssize_t wr = write(of, m, (size_t)st.st_size);
                close(of);
                LOGI("现场帧落盘：fd=%d size=%ld → %s（写入 %zd 字节）", fd, (long)st.st_size, path,
                     wr);
                done = 1;
            } else {
                LOGE("现场帧落盘：打不开 %s（errno=%d）", path, errno);
                done = 1;
            }
            munmap(m, (size_t)st.st_size);
        }
        closedir(d);
        if (done) break;
        sleep(2);
    }
    return NULL;
}

/**
 * **结果投递写手**：被结果钩子的自旋"点名"后，立刻把匹配尺寸的模板写进这一帧的输出缓冲。
 *
 * 为什么必须挂在投递这一刻（而不是盲目死循环）：
 * 录像链路上编码器是"ISP 写完 → 立刻读"，盲目循环的写入要么落在 ISP 写完之前（随后被覆盖），
 * 要么太晚（编码器已经读完）——实测表现就是撕裂花屏。
 * 这里在**结果回调入口**拿到 buffer handle，写完再放行（钩子侧有限自旋等待），
 * 于是"消费者看到的一定是我们的内容"。
 */
static int portal_res_memfd(void) {
    static int fd = -1;
    if (fd < 0) fd = open("/proc/self/mem", O_RDONLY);
    return fd;
}

static int portal_res_peek(unsigned long addr, void *buf, size_t len) {
    int fd = portal_res_memfd();
    if (fd < 0 || addr == 0) return -1;
    ssize_t r = pread(fd, buf, len, (off_t)portal_untag((const void *)addr));
    return (r == (ssize_t)len) ? 0 : -1;
}

static void *portal_res_writer_thread(void *arg) {
    (void)arg;
    unsigned long last_gen = 0;
    int logged = 0;
    struct { int fd; void *p; size_t size; } map[8];
    int nmap = 0;
    LOGI("结果写手：已启动，等 %s 的结果投递", g_opt_res_module[0] ? g_opt_res_module : "?");
    for (;;) {
        unsigned long gen = __atomic_load_n(&g_res_snap.gen, __ATOMIC_RELAXED);
        if (gen == last_gen) { usleep(200); continue; }
        last_gen = gen;
        unsigned long res = __atomic_load_n(&g_res_snap.res, __ATOMIC_RELAXED);
        unsigned int head[4] = {0};
        if (portal_res_peek(res, head, sizeof(head)) != 0) {
            __atomic_store_n(&g_res_done, 1ul, __ATOMIC_RELAXED);
            continue;
        }
        unsigned long nout = head[1];
        unsigned long outb = 0;
        if (portal_res_peek(res + 8, &outb, sizeof(outb)) != 0) {
            __atomic_store_n(&g_res_done, 1ul, __ATOMIC_RELAXED);
            continue;
        }
        if (logged < 6) {
            LOGI("结果投递：帧号=%u 输出路数=%lu 数组=%p", head[0], nout, (void *)outb);
            logged++;
        }
        if (nout > 0 && nout <= 8 && portal_untag((const void *)outb) >= 0x1000) {
            unsigned long arr[40] = {0};
            if (portal_res_peek(outb, arr, sizeof(arr)) == 0) {
                for (unsigned i = 0; i < nout && i < 8; i++) {
                    unsigned long *e = arr + i * 5;   /* 条目按 0x28 试（CamX 扩展版） */
                    unsigned long h = e[1];
                    if (logged <= 6 && i == 0) {
                        LOGI("  条目原始：%016lx %016lx %016lx %016lx %016lx", e[0], e[1], e[2], e[3],
                             e[4]);
                    }
                    if (portal_untag((const void *)h) < 0x1000) continue;
                    /* buffer 字段按 HAL3 契约是 buffer_handle_t*（再解一级才是 handle） */
                    unsigned long real = 0;
                    if (portal_res_peek(h, &real, sizeof(real)) != 0) continue;
                    for (int pass = 0; pass < 2; pass++) {
                        unsigned long cand = pass == 0 ? real : h;
                        if (portal_untag((const void *)cand) < 0x1000) continue;
                        unsigned int nh[8] = {0};
                        if (portal_res_peek(cand, nh, sizeof(nh)) != 0) continue;
                        int numfds = (int)nh[1];
                        if (numfds < 1 || numfds > 4 || nh[0] > 12 || nh[2] > 64) continue;
                        int fd = (int)nh[3];
                        if (fd <= 2) continue;
                        struct stat st;
                        if (fstat(fd, &st) != 0 || st.st_size <= 0) continue;
                        const unsigned char *tpl = NULL;
                        size_t tlen = 0;
                        for (int k = 0; k < g_mock_n; k++) {
                            if (g_mock[k].size == (unsigned long)st.st_size && g_mock[k].tpl) {
                                tpl = g_mock[k].tpl;
                                tlen = g_mock[k].tlen;
                                break;
                            }
                        }
                        if (tpl == NULL) continue;
                        void *m = NULL;
                        for (int k = 0; k < nmap; k++)
                            if (map[k].fd == fd) m = map[k].p;
                        if (m == NULL && nmap < 8) {
                            m = mmap(NULL, (size_t)st.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                                     0);
                            if (m != MAP_FAILED && m != NULL) {
                                map[nmap].fd = fd;
                                map[nmap].p = m;
                                map[nmap].size = (size_t)st.st_size;
                                nmap++;
                                LOGI("结果写手：接管 fd=%d size=%ld → %p（模板 %zu 字节）", fd,
                                     (long)st.st_size, m, tlen);
                            } else {
                                m = NULL;
                            }
                        }
                        if (m != NULL) {
                            memcpy(m, tpl, tlen < (size_t)st.st_size ? tlen : (size_t)st.st_size);
                            if (logged <= 6)
                                LOGI("结果写手：已写入 fd=%d size=%ld（帧 %u 第 %u 路）", fd,
                                     (long)st.st_size, head[0], i);
                        }
                    }
                }
            }
        }
        __atomic_store_n(&g_res_done, 1ul, __ATOMIC_RELAXED);
    }
    return NULL;
}

/** JPEG 探针线程：300ms 一轮（快门后的码流只存在很短时间） */
static void *portal_jpeg_thread(void *arg) {
    (void)arg;
    for (;;) {
        portal_jpeg_probe();
        usleep(300000);
    }
    return NULL;
}

/** 周期汇报线程：不碰 vendor 的任何锁，只读我们自己的 .bss 并写日志 */
static void *portal_report_thread(void *arg) {
    (void)arg;
    unsigned period = (unsigned)g_opt_report_period;
    if (period == 0) period = 5;
    for (;;) {
        sleep(period);
        LOGI("零调用钩子周期汇报（每 %u 秒）", period);
        portal_hook_report_counts();
        portal_req_report();
    }
    return NULL;
}

/** 路径里带 `sub` 的第一个映射的起始地址（= 该 ELF 的 vaddr 0）。找不到返回 0。 */
static uintptr_t portal_module_base(const char *sub) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == NULL) return 0;
    char line[512];
    uintptr_t best = 0;
    while (fgets(line, sizeof(line), f) != NULL) {
        if (strstr(line, sub) == NULL) continue;
        unsigned long start = 0;
        if (sscanf(line, "%lx-", &start) != 1) continue;
        if (best == 0 || (uintptr_t)start < best) best = (uintptr_t)start;
    }
    fclose(f);
    return best;
}

/**
 * 所有挂钩共用的记账函数。
 *
 * 参数顺序**必须**与钩子外壳里的 `mov x0..x5` 一致（x0=caller、x1=a0、x2=a1、x3=fp、
 * x4=kind、x5=entry_sp）—— 这一处曾经写反过，结果是 kind 收到一个代码地址、
 * 记账函数第一行就 `>= PORTAL_HOOK_MAX` 直接返回，钩子静默失效。
 *
 * @param caller   调用者的**干净**返回地址（PAC 位已在钩子里避开）
 * @param a0/a1    被挂函数头两个参数（CamX HAL3 一律是 device + 结构体指针）
 * @param fp       调用者的 x29
 * @param kind     挂钩位下标（名字在 g_hook_name 里）
 * @param entry_sp 钩子入口时的 SP —— 用来判断 fp 是不是**真的**调用者栈帧
 *
 * ⚠️ 教训（本机实测踩过）：**不能**假定 x29 一定是栈帧指针。踩中的那次，x29 里是
 * oemlayer 的 .rodata 地址，照着它去读"调用者栈里的表指针"读出了字符串字节，
 * 再按表解引用直接把 provider 打死（SIGSEGV 0xf8a0，tombstone_03）。
 * 所以这里用"必须落在入口 SP 之上、且所在映射可写、且不是文件映射"三重门槛卡死。
 */
/**
 * 钩子体（**默认路径：零副作用**）。
 *
 * 只用一次原子自增记账，然后立刻返回 —— **不打印、不取锁、不做任何 I/O**。
 *
 * 为什么必须这么干净（ACE5 实测，见 docs/camera-mock.md §22）：
 * 我们是被 vendor 代码在**它自己的调用路径里**调进来的（OPPO oemlayer 在会话建立期
 * 正持着自己的加载锁做 dlopen/dlsym）。此刻只要我们去碰别的共享资源 ——
 * `__android_log_print`（logd 的 socket/锁）、`dladdr`（linker 全局锁）、
 * `/proc/self/maps` —— 就可能与该锁构成 ABBA 死锁：症状是**没有 tombstone**、
 * 会话恰好死在 `configure_streams` 里、provider 被 init 判死后重启。
 * 实测对照：同一个入口，"只重放不调用 C"的空钩子完全透明（CONFIG=1、262 帧稳定），
 * 而带一条 LOGI 的极简钩子就会打断会话 —— 所以记账与汇报必须分开：
 * **钩子里只自增，汇报交给 portal_hook_report_thread**。
 */
__attribute__((used)) static void portal_hook_any_c(unsigned long caller, unsigned long a0,
                               unsigned long a1, unsigned long fp, unsigned long kind,
                               unsigned long entry_sp) {
    if (kind >= PORTAL_HOOK_MAX) return;
    __atomic_add_fetch(&g_hook_cnt[kind], 1, __ATOMIC_RELAXED);
    if (!g_opt_open_probe) return;   /* ← 默认在这里就回去了，一行 I/O 都没有 */

    static unsigned long logged[PORTAL_HOOK_MAX];
    unsigned long c = __atomic_add_fetch(&logged[kind], 1, __ATOMIC_RELAXED);
    if (c > 4 && c % 240 != 0) return;
    char w[256] = {0};
    portal_where((const void *)caller, w, sizeof(w));
    LOGI("入口挂钩[%lu] %s 第 %lu 次：调用者=%p（%s）a0=%p a1=%p", kind,
         g_hook_name[kind] ? g_hook_name[kind] : "?", c, (void *)caller, w, (void *)a0,
         (void *)a1);
    if (c != 1) return;
    /* 只有"确实像调用者栈帧"时才去读它 —— 见上面那段教训 */
    if (fp < entry_sp || fp > entry_sp + (1u << 20)) return;
    char perms[8] = {0};
    if (!portal_map_perm((const void *)fp, perms, sizeof(perms))) return;
    if (perms[0] != 'r' || perms[1] != 'w') return;
    char where_fp[256] = {0};
    portal_where((const void *)fp, where_fp, sizeof(where_fp));
    if (where_fp[0] == '/') return;   /* 文件映射 ⇒ 不是栈 */

    void **tbl = *(void ***)(fp - 8);
    if (tbl == NULL) return;
    char wt0[256] = {0};
    portal_where((const void *)tbl, wt0, sizeof(wt0));
    LOGI("  调用者 fp=%p（%s）里的指针 = %p（%s）", (void *)fp, where_fp, (void *)tbl, wt0);
    char perms2[8] = {0};
    if (!portal_map_perm((const void *)tbl, perms2, sizeof(perms2)) || perms2[0] != 'r') return;
    for (int i = 0; i < 12; i++) {
        if (tbl[i] == NULL) continue;
        char wt[256] = {0};
        portal_where(tbl[i], wt, sizeof(wt));
        LOGI("  表[%2d]（偏移 +%2d）= %p  %s", i, i * 8, tbl[i], wt);
    }
}

/** 这 4 条指令能不能"搬到别处执行"（不能有 PC 相对指令） */
static int portal_replayable(const uint32_t *w) {
    for (int i = 0; i < 4; i++) {
        uint32_t x = w[i];
        /* x9..x13 是我们要带给记账函数的参数，重放不许把它们当目标寄存器写掉。
         * 保守判断：取 Rd/Rt 字段（bit4:0），落在 9..13 就放弃这次挂钩。 */
        uint32_t dest = x & 0x1Fu;
        if (dest >= 9u && dest <= 13u) return 0;
        if ((x & 0x9F000000u) == 0x90000000u) return 0;  /* adrp */
        if ((x & 0x9F000000u) == 0x10000000u) return 0;  /* adr */
        if ((x & 0x7C000000u) == 0x14000000u) return 0;  /* b / bl */
        if ((x & 0xFF000010u) == 0x54000000u) return 0;  /* b.cond */
        if ((x & 0x7E000000u) == 0x36000000u) return 0;  /* tbz / tbnz */
        if ((x & 0xFF000000u) == 0x58000000u) return 0;  /* ldr (literal) */
        if ((x & 0x7E000000u) == 0x34000000u) return 0;  /* cbz / cbnz */
        if ((x & 0x3B000000u) == 0x18000000u) return 0;  /* ldrsw/prfm literal 等 */
        if (x == 0xD65F03C0u || x == 0xD65F0BFFu) return 0; /* ret */
    }
    return 1;
}

/**
 * 在 `base + vaddr` 处装一个入口挂钩（前 16 字节换绝对跳转桩 + 把被顶掉的 4 条指令
 * 写进我们自己的钩子外壳开头）。
 *
 * 全程不 mprotect：写目标代码段与写**我们自己的**代码段都走 `/proc/self/mem` + `pwrite`
 * （内核 FOLL_FORCE，只对私有映射生效）—— ACE5 上 `mprotect(PROT_WRITE|PROT_EXEC)` 会被
 * SELinux `execmem` 直接拒（实测 AVC），这条路是唯一不碰策略又能改代码的写法。
 */
static int portal_inline_hook_ex(int slot, uintptr_t base, unsigned long target_vaddr, const char *name,
                                 void *hook_fn, volatile unsigned long *resume_slot,
                                 unsigned replay_off) {
    if (slot < 0 || slot >= PORTAL_HOOK_MAX || base == 0) return 0;
    uint32_t *code = (uint32_t *)(base + (uintptr_t)target_vaddr);
    uint32_t orig[4];
    memcpy(orig, code, sizeof(orig));
    if (!portal_replayable(orig)) {
        LOGE("挂钩 %s（base=%p vaddr=0x%lx）：前 4 条指令里有 PC 相对指令，不能重放，跳过",
             name, (void *)base, target_vaddr);
        return 0;
    }
    int memfd = open("/proc/self/mem", O_RDWR);
    if (memfd < 0) {
        LOGE("挂钩 %s：打不开 /proc/self/mem（errno=%d）", name, errno);
        return 0;
    }
    /* 1) 把被顶掉的 4 条指令写进钩子外壳开头（我们自己的代码段），写完**读回验证**：
     *    这一步失败而继续挂，等于把目标函数的前 4 条指令直接抹掉 —— 那是必崩的写法。 */
    /* 重放槽在钩子外壳 **+20 字节**处：前 5 条 mov 负责先把 x9..x13 抄好
     * （必须在重放之前 —— 重放里的 paciasp 会改 x30；SP 也会被重放改掉）。 */
    uint32_t *slot_code = (uint32_t *)((char *)hook_fn + replay_off);
    ssize_t w1 = pwrite(memfd, orig, sizeof(orig), (off_t)(uintptr_t)slot_code);
    uint32_t back[4] = {0};
    ssize_t r1 = pread(memfd, back, sizeof(back), (off_t)(uintptr_t)slot_code);
    if (w1 != (ssize_t)sizeof(orig) || r1 != (ssize_t)sizeof(back) ||
        memcmp(back, orig, sizeof(orig)) != 0) {
        LOGE("挂钩 %s：钩子外壳写入/回读失败（w=%zd r=%zd errno=%d，读到 %08x %08x %08x %08x）",
             name, w1, r1, errno, back[0], back[1], back[2], back[3]);
        close(memfd);
        return 0;
    }
    /* 2) 目标入口换成绝对跳转桩 */
    uint32_t stub[4] = {0x58000050u /*ldr x16,#8*/, 0xD61F0200u /*br x16*/,
                        (uint32_t)((uintptr_t)hook_fn & 0xFFFFFFFFu),
                        (uint32_t)((uintptr_t)hook_fn >> 32)};
    if (slot >= 0) g_hook_name[slot] = name;
    *resume_slot = (unsigned long)(uintptr_t)code + 16;
    if (pwrite(memfd, stub, sizeof(stub), (off_t)(uintptr_t)code) != (ssize_t)sizeof(stub)) {
        LOGE("挂钩 %s：写目标入口失败（errno=%d）", name, errno);
        close(memfd);
        *resume_slot = 0;
        return 0;
    }
    close(memfd);
    __builtin___clear_cache((char *)slot_code, (char *)slot_code + 16);
    __builtin___clear_cache((char *)hook_fn, (char *)hook_fn + 36);
    __builtin___clear_cache((char *)code, (char *)code + 16);
    LOGI("已挂钩[%d] %s：入口 %p → 钩子 %p（重放 %08x %08x %08x %08x，返回点 %p）", slot, name,
         (void *)code, hook_fn, orig[0], orig[1], orig[2], orig[3], (void *)*resume_slot);
    return 1;
}

/** 常规入口钩子（带 C 记账） */
static int portal_inline_hook(int slot, uintptr_t base, unsigned long target_vaddr, const char *name) {
    return portal_inline_hook_ex(slot, base, target_vaddr, name, g_hook_fns[slot],
                                 &g_hook_resume[slot], 20);
}

/** 空钩子：只重放、只跳回，用来单独验证"补丁+重放+返回"这条路径是否透明 */
static int portal_inline_hook_null(int idx, uintptr_t base, unsigned long target_vaddr,
                                   const char *name) {
    if (idx < 0 || idx > 1) return 0;
    void *fn = idx == 0 ? (void *)portal_hook_null0 : (void *)portal_hook_null1;
    return portal_inline_hook_ex(-1, base, target_vaddr, name, fn, &g_hook_resume_null[idx], 0);
}

/** nrec 钩子：touch 形态的外壳 + 裸汇编记录器（最多 2 个） */
static void portal_hook_nrec_list(const char *list) {
    uintptr_t base = 0;
    Dl_info di;
    if (g_real_module != NULL && dladdr((const void *)g_real_module, &di) != 0)
        base = (uintptr_t)di.dli_fbase;
    if (base == 0) { LOGE("nrec 钩子：拿不到基址"); return; }
    void *fns[2] = {(void *)portal_hook_nrec0, (void *)portal_hook_nrec1};
    char buf[256];
    snprintf(buf, sizeof(buf), "%s", list);
    char *save = NULL;
    int idx = 0;
    for (char *tok = strtok_r(buf, ",", &save); tok != NULL && idx < 2;
         tok = strtok_r(NULL, ",", &save)) {
        while (*tok == ' ') tok++;
        unsigned long v = strtoul(tok, NULL, 0);
        if (v == 0) continue;
        snprintf(g_hook_name_buf[idx], sizeof(g_hook_name_buf[idx]), "real+0x%lx", v);
        portal_inline_hook_ex(idx, base, v, g_hook_name_buf[idx], fns[idx],
                              &g_hook_resume_nrec[idx], 20);
        idx++;
    }
}

/**
 * 请求快照钩子：只挂**请求入口**（真件 process_capture_request）。零调用。
 * 复用 portal_inline_hook_ex（slot 只用来存名字），重放槽同样是 +12。
 */
static void portal_hook_req_list(const char *list) {
    uintptr_t base = 0;
    if (g_real_module != NULL) {
        Dl_info di;
        if (dladdr((const void *)g_real_module, &di) != 0) base = (uintptr_t)di.dli_fbase;
    }
    if (base == 0) { LOGE("请求快照：拿不到真件基址"); return; }
    char buf[128];
    snprintf(buf, sizeof(buf), "%s", list);
    char *save = NULL;
    for (char *tok = strtok_r(buf, ",", &save); tok != NULL; tok = strtok_r(NULL, ",", &save)) {
        while (*tok == ' ') tok++;
        unsigned long v = strtoul(tok, NULL, 0);
        if (v == 0) continue;
        if (g_hook_rec_next >= PORTAL_HOOK_MAX) { LOGE("请求快照：槽位用完了"); return; }
        int slot = g_hook_rec_next++;
        snprintf(g_hook_name_buf[slot], sizeof(g_hook_name_buf[slot]), "req+0x%lx", v);
        portal_inline_hook_ex(slot, base, v, g_hook_name_buf[slot], (void *)portal_hook_req0,
                              &g_hook_resume_req, 12);
    }
}

/** 在"结果投递"入口装钩子（安装代码必须在 arm64 区里，符号才可见） */
static void portal_hook_res_install(void) {
    uintptr_t base = 0;
    if (g_opt_res_module[0] != '\0') base = portal_module_base(g_opt_res_module);
    if (base == 0 && g_real_module != NULL) {
        Dl_info di;
        if (dladdr((const void *)g_real_module, &di) != 0) base = (uintptr_t)di.dli_fbase;
    }
    unsigned long rv = strtoul(g_opt_res_hook, NULL, 0);
    if (base == 0 || rv == 0) {
        LOGE("结果钩子：拿不到模块基址（res_module=%s res_hook=%s）", g_opt_res_module,
             g_opt_res_hook);
        return;
    }
    if (g_hook_rec_next >= PORTAL_HOOK_MAX) {
        LOGE("结果钩子：槽位用完了");
        return;
    }
    int slot = g_hook_rec_next++;
    snprintf(g_hook_name_buf[slot], sizeof(g_hook_name_buf[slot]), "res+0x%lx", rv);
    portal_inline_hook_ex(slot, base, rv, g_hook_name_buf[slot], (void *)portal_hook_res0,
                          &g_hook_resume_res, 12);
}

/** 零调用钩子：真件与 oemlayer 都可挂 */
static void portal_hook_rec_list(const char *list, const char *module_sub) {
    uintptr_t base = 0;
    if (module_sub != NULL) {
        base = portal_module_base(module_sub);
    } else if (g_real_module != NULL) {
        Dl_info di;
        if (dladdr((const void *)g_real_module, &di) != 0) base = (uintptr_t)di.dli_fbase;
    }
    if (base == 0) { LOGE("零调用钩子：拿不到基址"); return; }
    char buf[512];
    snprintf(buf, sizeof(buf), "%s", list);
    char *save = NULL;
    int slot = g_hook_rec_next;
    for (char *tok = strtok_r(buf, ",", &save); tok != NULL && slot < PORTAL_HOOK_MAX;
         tok = strtok_r(NULL, ",", &save)) {
        while (*tok == ' ') tok++;
        unsigned long v = strtoul(tok, NULL, 0);
        if (v == 0) continue;
        snprintf(g_hook_name_buf[slot], sizeof(g_hook_name_buf[slot]), "%s+0x%lx",
                 module_sub ? module_sub : "real", v);
        /* ⚠️ 重放槽必须在**这个外壳自己的**占位 nop 处：rec 外壳的序言只有 3 条 mov
         * （12 字节），写 20 会把它后面的计数指令整段覆盖掉 —— 实测就是"挂上就崩"。 */
        portal_inline_hook_ex(slot, base, v, g_hook_name_buf[slot], g_hook_rec_fns[slot],
                              &g_hook_resume_rec[slot], 12);
        slot++;
    }
    g_hook_rec_next = slot;
}

/** touch 钩子：带一次调用，但被调者只 `ret`（隔离"C 函数内部"与"发起调用"） */
static int portal_inline_hook_touch(int idx, uintptr_t base, unsigned long target_vaddr,
                                    const char *name) {
    if (idx < 0 || idx > 1) return 0;
    void *fn = idx == 0 ? (void *)portal_hook_touch0 : (void *)portal_hook_touch1;
    return portal_inline_hook_ex(-1, base, target_vaddr, name, fn, &g_hook_resume_touch[idx], 20);
}

static void portal_hook_touch_list(const char *list) {
    uintptr_t base = 0;
    Dl_info di;
    if (g_real_module != NULL && dladdr((const void *)g_real_module, &di) != 0)
        base = (uintptr_t)di.dli_fbase;
    if (base == 0) { LOGE("touch 钩子：拿不到真件基址"); return; }
    char buf[256];
    snprintf(buf, sizeof(buf), "%s", list);
    char *save = NULL;
    int idx = 0;
    for (char *tok = strtok_r(buf, ",", &save); tok != NULL && idx < 2;
         tok = strtok_r(NULL, ",", &save)) {
        while (*tok == ' ') tok++;
        unsigned long v = strtoul(tok, NULL, 0);
        if (v == 0) continue;
        char nm[48];
        snprintf(nm, sizeof(nm), "real+0x%lx", v);
        portal_inline_hook_touch(idx++, base, v, nm);
    }
}

/** 空钩子列表（最多 2 个） */
static void portal_hook_null_list(const char *list) {
    uintptr_t base = 0;
    Dl_info di;
    if (g_real_module != NULL && dladdr((const void *)g_real_module, &di) != 0)
        base = (uintptr_t)di.dli_fbase;
    if (base == 0) { LOGE("空钩子：拿不到真件基址"); return; }
    char buf[256];
    snprintf(buf, sizeof(buf), "%s", list);
    char *save = NULL;
    int idx = 0;
    for (char *tok = strtok_r(buf, ",", &save); tok != NULL && idx < 2;
         tok = strtok_r(NULL, ",", &save)) {
        while (*tok == ' ') tok++;
        unsigned long v = strtoul(tok, NULL, 0);
        if (v == 0) continue;
        char nm[48];
        snprintf(nm, sizeof(nm), "real+0x%lx", v);
        portal_inline_hook_null(idx++, base, v, nm);
    }
}

/** 解析 `a,b,c` 形式的 vaddr 列表并逐个挂钩 */
static void portal_hook_list(const char *list, const char *module_sub) {
    uintptr_t base = 0;
    if (module_sub != NULL) {
        base = portal_module_base(module_sub);
        if (base == 0) {
            LOGE("挂钩：找不到模块映射 %s", module_sub);
            return;
        }
        LOGI("挂钩：模块 %s 基址 = %p", module_sub, (void *)base);
    } else if (g_real_module != NULL) {
        Dl_info di;
        if (dladdr((const void *)g_real_module, &di) != 0) base = (uintptr_t)di.dli_fbase;
    }
    if (base == 0) {
        LOGE("挂钩：拿不到基址");
        return;
    }
    char buf[512];
    snprintf(buf, sizeof(buf), "%s", list);
    char *save = NULL;
    for (char *tok = strtok_r(buf, ",", &save); tok != NULL; tok = strtok_r(NULL, ",", &save)) {
        while (*tok == ' ') tok++;
        unsigned long v = strtoul(tok, NULL, 0);
        if (v == 0) continue;
        int slot = -1;
        for (int i = 0; i < PORTAL_HOOK_MAX; i++) {
            if (g_hook_resume[i] == 0 && g_hook_name[i] == NULL) { slot = i; break; }
        }
        if (slot < 0) {
            LOGE("挂钩：位置用完了");
            return;
        }
        /* ⚠️ 名字要存进**静态**缓冲：之前把栈上 nm 的地址存进 g_hook_name，
         * 出函数就悬空，日志里打出来是乱码。 */
        snprintf(g_hook_name_buf[slot], sizeof(g_hook_name_buf[slot]), "%s+0x%lx",
                 module_sub ? module_sub : "real", v);
        portal_inline_hook(slot, base, v, g_hook_name_buf[slot]);
    }
}
#else
static void portal_hook_list(const char *list, const char *module_sub) {
    (void)list; (void)module_sub;
    LOGE("入口挂钩只有 arm64 实现：本机是 32 位，跳过");
}
static void portal_hook_null_list(const char *list) {
    (void)list;
    LOGE("空钩子只有 arm64 实现：本机是 32 位，跳过");
}
static void portal_hook_touch_list(const char *list) {
    (void)list;
    LOGE("touch 钩子只有 arm64 实现：本机是 32 位，跳过");
}
static void portal_hook_rec_list(const char *list, const char *module_sub) {
    (void)list; (void)module_sub;
    LOGE("零调用钩子只有 arm64 实现：本机是 32 位，跳过");
}
static void portal_hook_nrec_list(const char *list) {
    (void)list;
    LOGE("nrec 钩子只有 arm64 实现：本机是 32 位，跳过");
}
static void portal_hook_report_counts(void) {}
static void *portal_report_thread(void *arg) { (void)arg; return NULL; }
static void portal_hook_req_list(const char *list) {
    (void)list;
    LOGE("请求快照钩子只有 arm64 实现：本机是 32 位，跳过");
}
static void portal_req_report(void) {}
static void portal_dmabuf_report(void) {}
static void portal_stride_probe(void) {}
static void portal_bufmap(void) {}
static void *portal_jpeg_thread(void *arg) { (void)arg; return NULL; }
static void *portal_req_watch_thread(void *arg) { (void)arg; return NULL; }
static void *portal_raw_dump_thread(void *arg) { (void)arg; return NULL; }
static void *portal_res_writer_thread(void *arg) { (void)arg; return NULL; }
static void portal_hook_res_install(void) { LOGE("结果钩子只有 arm64 实现"); }
static void *portal_inject_thread(void *arg) { (void)arg; return NULL; }
#endif

struct portal_bind_arg {
    const struct camera3_device *inner; /**< 真件 open 返回的对象 */
    void **out;                         /**< 出参地址（oemlayer 会在返回前改写它） */
};

static void *portal_binder_thread(void *arg) {
    struct portal_bind_arg *ba = (struct portal_bind_arg *)arg;
    const struct camera3_device *dev = ba->inner;
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    {
        /* 探针自检：已知在映射里的对象必须被认出来，否则后面所有"不在映射里"的结论都不算数 */
        char p[8] = {0};
        bool m = portal_map_perm((const void *)dev, p, sizeof(p));
        LOGI("探针自检：真件对象 %p 读到映射=%d perms=%s", (const void *)dev, (int)m, p);
    }

    /*
     * 第一段：等真件填 ops（ACE5 实测：**永远不会填** —— 真件返回的那一刻 ops 是 NULL，
     * 之后也没人再动它。这一格在 ACE5 上不是接管点，但先测出来，免得以后误判）。
     */
    void *ops = NULL;
    long waited_us = -1;
    for (int i = 0; i < 200; i++) {
        ops = (void *)dev->ops;
        if (ops != NULL) {
            clock_gettime(CLOCK_MONOTONIC, &t1);
            waited_us = (t1.tv_sec - t0.tv_sec) * 1000000L + (t1.tv_nsec - t0.tv_nsec) / 1000L;
            break;
        }
        sched_yield();
    }
    if (ops == NULL) {
        LOGI("绑定探测：真件 device=%p 的 ops 在 50ms 内仍是 NULL（真件自己不填这一格）",
             (const void *)dev);
        /* 载入期扫描时 provider 的会话对象还没建，pcr 指针自然找不到；这里 open 之后 50ms
         * 再扫一遍**全地址空间**（含只读 GOT），看调用方到底把那个函数指针存在哪儿。 */
        if (g_opt_ops_scan) {
            static const unsigned long probe[] = {0x1e46f80ul, 0x1e49cc0ul, 0x1e41a90ul};
            portal_probe_vaddrs(probe, 3, 2048);
        }
    } else {
        char where[256] = {0};
        portal_where(ops, where, sizeof(where));
        LOGI("绑定探测：open 返回后 %ldus，真件 device->ops = %p（%s）", waited_us, ops, where);
    }

    /*
     * 第二段：盯 **出参**。oemlayer 的 open 在返回之前会把 `*device` 换成它自己的包装对象
     * （反汇编 /odm/lib64/hw/camera.oemlayer.so+0x6bc7c 处 `str x0, [x8]`，x8 就是出参）。
     * provider 最终拿到、并调用 configure_streams 的，是**它**。所以真正的接管面在这里。
     */
    void **out = ba->out;
    void *prev = (void *)dev;
    void *first_other = NULL;
    long first_other_us = -1;
    unsigned changes = 0;
    for (int i = 0; i < 20000; i++) {
        void *cur = *out;
        if (cur != prev) {
            clock_gettime(CLOCK_MONOTONIC, &t1);
            long us = (t1.tv_sec - t0.tv_sec) * 1000000L + (t1.tv_nsec - t0.tv_nsec) / 1000L;
            char w[256] = {0};
            portal_where(cur, w, sizeof(w));
            /* 新对象：先把 ops 读出来（先确认它还在映射里，别把诊断变成崩溃） */
            char oinfo[256] = {0};
            char perms[8] = {0};
            if (cur != NULL && portal_map_perm(cur, perms, sizeof(perms))) {
                const uint64_t *q = (const uint64_t *)cur;
                void *oops = (void *)q[8];
                char w2[256] = {0};
                portal_where(oops, w2, sizeof(w2));
                snprintf(oinfo, sizeof(oinfo),
                         " 前16字节=%016llx %016llx perms=%s ops(+64)=%p %s",
                         (unsigned long long)q[0], (unsigned long long)q[1], perms, oops, w2);
            } else {
                snprintf(oinfo, sizeof(oinfo), "（不在映射里，未解引用）");
            }
            LOGI("出参第 %u 次变化 +%ldus：%p -> %p（%s）%s", changes + 1, us, prev, cur, w, oinfo);
            changes++;
            if (first_other == NULL && cur != (void *)dev) {
                first_other = cur;
                first_other_us = us;
            }
            prev = cur;
        }
        if (i < 200) {
            sched_yield();
        } else {
            struct timespec ts = {.tv_sec = 0, .tv_nsec = 100000};
            nanosleep(&ts, NULL);
        }
    }
    LOGI("出参观察结束：共 %u 次变化，首个非真件对象=%p（+%ldus），末值=%p",
         changes, first_other, first_other_us, prev);

    if (first_other == NULL) return NULL;

    /*
     * 收尾：把那个对象盯满 500ms，看它的 ops 会不会被补上；补上了就顺带把整张表打出来 ——
     * provider 真正调用的是它，接管点也只能在它身上。
     */
    void *outer = first_other;
    for (int i = 0; i < 2500; i++) {
        char perms[8] = {0};
        if (!portal_map_perm(outer, perms, sizeof(perms))) {
            LOGI("外层对象 %p 已释放，停止观察", outer);
            return NULL;
        }
        void *oops = (void *)((const uint64_t *)outer)[8];
        if (oops != NULL) {
            char w2[256] = {0};
            portal_where(oops, w2, sizeof(w2));
            LOGI("外层对象 %p 的 ops 出现了：%p（%s）", outer, oops, w2);
            const uint64_t *tbl = (const uint64_t *)oops;
            for (int k = 0; k < 24; k++) {
                if (tbl[k] == 0) continue;
                char w[256] = {0};
                portal_where((const void *)(uintptr_t)tbl[k], w, sizeof(w));
                LOGI("  outer ops[%2d] = %p  %s", k, (void *)(uintptr_t)tbl[k], w);
            }
            return NULL;
        }
        struct timespec ts = {.tv_sec = 0, .tv_nsec = 200000};
        nanosleep(&ts, NULL);
    }
    LOGI("外层对象 %p 的 ops 在 500ms 内始终为 NULL", outer);
    return NULL;
}

static int shim_open(const struct hw_module_t *module, const char *id,
                     struct hw_device_t **device) {
    (void)module;
    load_options();
    if (id != NULL) snprintf(g_cur_cam, sizeof(g_cur_cam), "%s", id);
    /* 钩子累计命中：在 open 这条**非 vendor 热路径**上汇报，钩子体自己只自增、不做 I/O */
    portal_hook_report_counts();
    if (g_real_open == NULL) {
        LOGE("真实 HAL 未加载，open 失败");
        return -1;
    }
    /* 判定日志：provider 是否真的**经我们的 methods->open** 开设备。
     * 这条线与"我们的 HMI 被 dlopen 到了"（banner）是两件事 —— ACE5 上必须分别证实，
     * 因为若 provider 绕过 open 自行 dlopen 真件，整个 ops 接管面就根本不会被走到。 */
    LOGI("shim_open 被调用：id=%s device=%p —— provider 走的是我们的 methods->open",
         id ? id : "(null)", (void *)device);
    {
        char w0[256] = {0}, w1[256] = {0};
        const void *r0 = __builtin_return_address(0);
        const void *r1 = __builtin_return_address(1);
        portal_where(r0, w0, sizeof(w0));
        portal_where(r1, w1, sizeof(w1));
        LOGI("open 的调用者：ra0=%p %s | ra1=%p %s", r0, w0, r1, w1);
    }
    /* hw_get_module 会把我们的 HMI.dso 设成"我们自己的句柄"，而我们要以**真实模块**的身份
     * 去开设备：指回真实句柄，免得 vendor 实现拿它 dlsym 自己的符号时找不到 */
    HMI.common.dso = g_real_handle;
    int rc = g_real_open(g_real_module, id, device);
    /* 诊断（bring-up 期）：open 返回时 device/ops 长什么样。ACE5 实测 ops 在这里是 NULL，
     * 由 oemlayer 在返回之后补上 —— 所以下面挂一个高频盯表线程（见 portal_binder_thread）。 */
    {
        const struct camera3_device *d0 =
            (rc == 0 && device != NULL) ? (const struct camera3_device *)*device : NULL;
        void **tbl = (d0 != NULL && d0->ops != NULL) ? (void **)d0->ops : NULL;
        const char *w = NULL;
        int e0 = tbl ? (int)portal_is_exec_addr(tbl[0], &w) : 0;
        int e4 = tbl ? (int)portal_is_exec_addr(tbl[4], NULL) : 0;
        LOGI("真实 open：rc=%d device=%p ops=%p 槽位 [0]=%p(exec=%d) [4]=%p(exec=%d) 首槽所在=%s",
             rc, (void *)d0, (void *)(d0 ? d0->ops : NULL), tbl ? tbl[0] : NULL, e0,
             tbl ? tbl[4] : NULL, e4, w ? w : "?");
        if (d0 != NULL) {
            const uint64_t *q = (const uint64_t *)d0;
            LOGI("device 前 96 字节：%016llx %016llx %016llx | %016llx %016llx %016llx | %016llx %016llx %016llx",
                 (unsigned long long)q[0], (unsigned long long)q[1], (unsigned long long)q[2],
                 (unsigned long long)q[3], (unsigned long long)q[4], (unsigned long long)q[5],
                 (unsigned long long)q[6], (unsigned long long)q[7], (unsigned long long)q[8]);
            LOGI("device.common: tag=0x%08x version=0x%08x module=%p priv=%p",
                 d0->common.tag, d0->common.version, (void *)d0->common.module, d0->priv);
            if (!g_opt_open_probe) return rc;
            pthread_t th;
            struct portal_bind_arg *ba = malloc(sizeof(*ba));
            if (ba != NULL) {
                ba->inner = d0;
                ba->out = (void **)device;
                if (pthread_create(&th, NULL, portal_binder_thread, ba) == 0) {
                    pthread_detach(th);
                } else {
                    LOGE("绑定探测线程创建失败");
                    free(ba);
                }
            }
        }
    }
    if (rc == 0 && device != NULL && *device != NULL) {
        const struct camera3_device *cam_dev = (const struct camera3_device *)*device;
        /* initialize 的真实实现：优先用导出符号，拿不到就用 ops[0] 的当前值兜底 */
        if (g_real_initialize == NULL && cam_dev->ops != NULL) {
            g_real_initialize = (int (*)(const struct camera3_device *,
                                         const camera3_callback_ops_t *))((void **)cam_dev->ops)[0];
            LOGI("initialize 改由 ops[0] 兜底取得：%p", (void *)g_real_initialize);
        }
        /*
         * 导出符号拿不到时（ACE5 的 camera.qcom.so 就不导出 QCamera3HardwareInterface::*），
         * 按 **AOSP ops 索引**从表里取真实指针：0=initialize、1=configure_streams、4=process_capture_request。
         * 取之前逐个过"落在可执行映射里"的校验；拿不准就放弃接管（相机行为不变）。
         */
        if (g_opt_ops_index && cam_dev->ops != NULL) {
            void **table = (void **)cam_dev->ops;
            const char *where = NULL;
            if (g_real_process_capture_request == NULL &&
                portal_is_exec_addr(table[4], &where)) {
                g_real_process_capture_request =
                    (int (*)(const struct camera3_device *, camera3_capture_request_t *))table[4];
                LOGI("真实 process_capture_request 取自 ops[4]=%p（%s）", table[4],
                     where ? where : "?");
            }
            if (g_real_initialize == NULL && portal_is_exec_addr(table[0], &where)) {
                g_real_initialize = (int (*)(const struct camera3_device *,
                                             const camera3_callback_ops_t *))table[0];
                LOGI("真实 initialize 取自 ops[0]=%p", table[0]);
            }
            if (g_real_configure_streams == NULL && portal_is_exec_addr(table[1], &where)) {
                g_real_configure_streams =
                    (int (*)(const struct camera3_device *, void *))table[1];
                LOGI("真实 configure_streams 取自 ops[1]=%p", table[1]);
            }
        }
        /* M0 基线：只换 process_capture_request 做计数 —— 这一处已被实测证明不破坏画面 */
        patch_process_capture_request(cam_dev);
        /*
         * 下面两处是**注入前提**，各自独立开关、必须单独验证（历史教训见 docs/camera-mock.md §4.1：
         * 当初把两处一起打开看到黑屏，但从未隔离过，无法归因）。
         */
        if (g_opt_init_wrap && portal_cam_selected()) {
            patch_initialize(cam_dev);
        }
        if (g_opt_usage_patch && portal_cam_selected()) {
            patch_configure_streams(cam_dev);
        }
    }
    return rc;
}

/* ------------------------------------------------------------------ *
 * 真实 HAL 加载
 * ------------------------------------------------------------------ */

/**
 * 默认原件路径：**原件就在自己旁边**（`<本 .so 的完整路径>.orig.so`）。
 *
 * 为什么不再硬编码机型路径：模块的部署约定本来就是"把原件备份到 shim 旁边、只换文件名"
 * （见 build-camera-hal-module.sh 与 customize.sh），所以自行推导对任何机型都成立；
 * 而硬编码一定在别的机型上错 —— "原件路径错"的后果曾经是最坏的那一种（见 §19）。
 * 推导不出来（dli_fname 不是绝对路径）时才退回历史常量。
 */
static const char *default_real_hal_path(void) {
    static char buf[512];
    if (buf[0] != '\0') return buf;
    Dl_info info;
    if (dladdr((const void *)&HMI, &info) != 0 && info.dli_fname != NULL &&
        info.dli_fname[0] == '/') {
        snprintf(buf, sizeof(buf), "%s.orig.so", info.dli_fname);
    } else {
        snprintf(buf, sizeof(buf), "%s", REAL_HAL_PATH);
    }
    return buf;
}

/**
 * 把一条状态落盘（best-effort，失败即放弃，绝不影响相机）。
 *
 * 为什么需要它：logcat 会滚、tombstone 只记崩溃，而"原件没加载成功"这类**非崩溃**故障
 * 恰恰是最难事后定位的一种 —— §19 那场排查里，最缺的就是"shim 到底走到哪一步"。
 * 文件由模块的 post-fs-data.sh 预创建并标成 `portalex_frame_file`（HAL 域有 write 权限，
 * 但没有在该目录里 create 的权限），路径与帧通道同目录。
 */
static void portal_write_state(const char *fmt, ...) {
    FILE *f = fopen("/data/local/tmp/portalex/camshim.state", "we");
    if (f == NULL) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

static void load_real_hal(void) {
    if (g_real_handle != NULL) return;

    const char *path = g_opt_real_hal[0] ? g_opt_real_hal : default_real_hal_path();
    g_real_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (g_real_handle == NULL) {
        /* ⚠️ 这里**不能**就这么走人：`HMI` 必须保持惰性合法（见 portal_hmi_t 的注释）。
         * 旧代码 return 之后 `HMI` 是全零，ACE5 的 OPPO OEM 层会因此在 strcmp 里空指针崩溃，
         * 把"相机不可用"放大成"provider 崩溃循环"。 */
        LOGE("原件 dlopen 失败（%s）：%s —— 本次按惰性模块运行（相机不可用，进程安全）", path,
             dlerror());
        portal_write_state("state=inert reason=dlopen-failed path=%s", path);
        return;
    }
    g_real_module = (struct hw_module_t *)dlsym(g_real_handle, "HMI");
    if (g_real_module == NULL) {
        LOGE("原件里没有 HMI 符号（%s）—— 本次按惰性模块运行", path);
        portal_write_state("state=inert reason=no-HMI-symbol path=%s", path);
        dlclose(g_real_handle);
        g_real_handle = NULL;
        return;
    }

    /* 整块照抄：tag / 版本 / id / name / author / methods / dso，
     * 以及 camera_module_t 那一串回调（少抄一个都可能被 provider 当成垃圾指针调用） */
    unsigned copy_bytes = g_opt_real_hmi_bytes ? g_opt_real_hmi_bytes : REAL_HMI_BYTES;
    if (copy_bytes > sizeof(HMI)) copy_bytes = sizeof(HMI);
    memcpy(&HMI, g_real_module, copy_bytes);

    /* 抄完把尾部清零：惰性初值往留白里填的是兜底回调指针，真实结构之外的槽位必须恢复成"无"，
     * 免得以后有人照着留下来的指针去猜字段（真实结构 MI6 176 / ACE5 376，我们留了 536）。 */
    if (copy_bytes < sizeof(HMI)) {
        memset((char *)&HMI + copy_bytes, 0, sizeof(HMI) - copy_bytes);
    }

    /* 只改这一处：open 走我们的（接管 camera3_device_ops 的入口） */
    HMI.common.methods = &g_methods;

    g_real_module->dso = g_real_handle;
    HMI.common.dso = g_real_handle;

    if (g_real_module->methods != NULL) {
        g_real_open = g_real_module->methods->open;
    }
    g_real_process_capture_request =
        (int (*)(const struct camera3_device *, camera3_capture_request_t *))dlsym(
            g_real_handle,
            "_ZN7qcamera25QCamera3HardwareInterface23process_capture_requestEPK14camera3_deviceP23camera3_capture_request");
    if (g_real_process_capture_request == NULL) {
        LOGE("没找到 process_capture_request 导出符号：本次不接管（相机行为不变）");
    }
    /* initialize 的导出符号名较长且各版本可能不同（实测本机是 `PK20camera3_callback_ops`，
     * **不是**记忆里的 PK21）；拿不到就退化为 open 之后用 ops[0] 的当前值兜底，两条路都能走通 */
    g_real_initialize = (int (*)(const struct camera3_device *,
                                 const camera3_callback_ops_t *))dlsym(
        g_real_handle,
        "_ZN7qcamera25QCamera3HardwareInterface10initializeEPK14camera3_devicePK20camera3_callback_ops");
    if (g_real_initialize == NULL) {
        LOGI("initialize 没有导出符号：改用 ops[0] 兜底");
    }
    /* configure_streams：补用途位那条路要用它转发（实测符号是 P28camera3_stream_configuration） */
    g_real_configure_streams =
        (int (*)(const struct camera3_device *, void *))dlsym(
            g_real_handle,
            "_ZN7qcamera25QCamera3HardwareInterface17configure_streamsEPK14camera3_deviceP28camera3_stream_configuration");
    if (g_real_configure_streams == NULL) {
        LOGI("configure_streams 没有导出符号：usage_patch 那条路不可用");
    }
    /* OPPO OEM 层索要的那个符号（见文件上方 SetOplusCameraInfoCallback 的注释）：
     * 拿到就转发，拿不到也**不致命**（我们的桩会返回 -ENODEV，OEM 层不会因此崩）。 */
    g_real_oplus_info_cb =
        (portal_oplus_info_cb_t)dlsym(g_real_handle, "SetOplusCameraInfoCallback");
    if (g_real_oplus_info_cb == NULL) {
        LOGI("原件没有 SetOplusCameraInfoCallback（OEM 层会收到 -ENODEV）");
    }

    LOGI("真实 HAL 已加载并整块接管：%s（tag=0x%08x id=%s name=%s api=0x%04x，照抄 %u 字节）",
         path, HMI.common.tag, HMI.common.id, HMI.common.name, HMI.common.module_api_version,
         copy_bytes);
    /* 一起把"三处接管点各拿到了没有"落盘：这三条决定了后面能做到哪个里程碑 */
    portal_write_state("state=ok path=%s id=%s bytes=%u open=%p pcr=%p init=%p cfg=%p oplus_cb=%p",
                       path, HMI.common.id ? HMI.common.id : "(null)", copy_bytes,
                       (void *)g_real_open, (void *)g_real_process_capture_request,
                       (void *)g_real_initialize, (void *)g_real_configure_streams,
                       (void *)g_real_oplus_info_cb);
    /* 可选：直接在真件 HAL3 入口上挂钩（ACE5 唯一可行的接管面，理由见 g_opt_inline_hook） */
    if (g_opt_hook_vaddrs[0] != '\0') {
        portal_hook_list(g_opt_hook_vaddrs, NULL);
    } else if (g_opt_inline_hook || g_opt_inline_hook_cfg) {
        char tmp[128];
        if (g_opt_inline_hook && g_opt_inline_hook_cfg) {
            snprintf(tmp, sizeof(tmp), "0x%lx,0x%lx", g_opt_hook_pcr_vaddr, g_opt_hook_cfg_vaddr);
        } else if (g_opt_inline_hook) {
            snprintf(tmp, sizeof(tmp), "0x%lx", g_opt_hook_pcr_vaddr);
        } else {
            snprintf(tmp, sizeof(tmp), "0x%lx", g_opt_hook_cfg_vaddr);
        }
        portal_hook_list(tmp, NULL);
    }
    if (g_opt_hook_ol_vaddrs[0] != '\0') {
        portal_hook_list(g_opt_hook_ol_vaddrs, "camera.oemlayer.so");
    }
    if (g_opt_hook_null_vaddrs[0] != '\0') {
        portal_hook_null_list(g_opt_hook_null_vaddrs);
    }
    if (g_opt_hook_touch_vaddrs[0] != '\0') {
        portal_hook_touch_list(g_opt_hook_touch_vaddrs);
    }
    if (g_opt_hook_nrec_vaddrs[0] != '\0') {
        portal_hook_nrec_list(g_opt_hook_nrec_vaddrs);
    }
    if (g_opt_hook_rec_vaddrs[0] != '\0') {
        portal_hook_rec_list(g_opt_hook_rec_vaddrs, NULL);
    }
    if (g_opt_hook_rec_ol_vaddrs[0] != '\0') {
        portal_hook_rec_list(g_opt_hook_rec_ol_vaddrs, "camera.oemlayer.so");
    }
    /* 请求快照钩子：做图像注入前必须先看到请求与缓冲的真身 */
    if (g_opt_req_vaddrs[0] != '\0') {
        portal_hook_req_list(g_opt_req_vaddrs);
    }
    /* 可选：扫描并接管真件自己的 ops 表（ACE5 的 device->ops 是 NULL，只能走这条） */
    if (g_opt_ops_scan) {
        /* 关键：**放到独立线程**。构造函数是在 provider 主线程里跑的，扫描一旦占住它，
         * hwservicemanager 5 秒就会把这个服务判死 —— 实测直接读共享段时正是如此（无 tombstone）。 */
        pthread_t th;
        if (pthread_create(&th, NULL, portal_load_scan_thread, NULL) == 0) {
            pthread_detach(th);
        } else {
            LOGE("载入期扫描线程创建失败");
        }
    }
}

__attribute__((constructor)) static void on_load(void) {
    load_options();
    load_real_hal();
    if (g_opt_inject_fd >= 0 || g_opt_inject_size != 0 || g_opt_mock_map[0] != '\0') {
        pthread_t th;
        if (pthread_create(&th, NULL, portal_inject_thread, NULL) == 0) {
            pthread_detach(th);
        } else {
            LOGE("注入线程创建失败");
        }
    }
    if (g_opt_hook_dev_vaddrs[0] != '\0') {
        portal_hook_rec_list(g_opt_hook_dev_vaddrs, "camx.device-impl.so");
    }
    if (g_opt_res_hook[0] != '\0') {
        portal_hook_res_install();
    }
    if (g_opt_res_writer) {
        pthread_t th;
        if (pthread_create(&th, NULL, portal_res_writer_thread, NULL) == 0) pthread_detach(th);
    }
    if (g_opt_raw_dump[0] != '\0') {
        pthread_t th;
        if (pthread_create(&th, NULL, portal_raw_dump_thread, NULL) == 0) pthread_detach(th);
    }
    if (g_opt_req_watch) {
        pthread_t th;
        if (pthread_create(&th, NULL, portal_req_watch_thread, NULL) == 0) pthread_detach(th);
    }
    if (g_opt_jpeg_probe) {
        pthread_t th;
        if (pthread_create(&th, NULL, portal_jpeg_thread, NULL) == 0) pthread_detach(th);
    }
    if (g_opt_report_period > 0 || g_opt_req_vaddrs[0] != '\0') {
        pthread_t th;
        if (pthread_create(&th, NULL, portal_report_thread, NULL) == 0) {
            pthread_detach(th);
        } else {
            LOGE("汇报线程创建失败");
        }
    }
}
