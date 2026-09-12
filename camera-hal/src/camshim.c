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

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
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

/** 真实模块符号大小（实测 176 字节 = camera_module_t）；照抄这么多即可，不会越界读原件 */
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

    FILE *f = fopen(PORTAL_CFG_PATH, "re");
    if (f == NULL) {
        LOGI("没有 %s：全部开关按关闭处理（纯 M0 转发）", PORTAL_CFG_PATH);
        return;
    }
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
        } else if (strcmp(key, "src_file") == 0) {
            snprintf(g_opt_src_file, sizeof(g_opt_src_file), "%s", val);
        } else if (strcmp(key, "cam") == 0) {
            snprintf(g_opt_cam, sizeof(g_opt_cam), "%s", val);
        }
    }
    fclose(f);
    LOGI("开关：dump=%d usage_patch=%d init_wrap=%d inject=%d freeze=%d pattern=%d cam=%s src=%s",
         g_opt_dump, g_opt_usage_patch, g_opt_init_wrap, g_opt_inject, g_opt_freeze, g_opt_pattern,
         g_opt_cam[0] ? g_opt_cam : "(全部)", g_opt_src_file[0] ? g_opt_src_file : "(图案)");
}

/* 我们交给 HAL 的回调表（定义在这里，实现见下方 M1a 段）—— 先声明后引用 */
static void portal_process_capture_result(const camera3_callback_ops_t *,
                                          const camera3_capture_result_t *);
static void portal_notify(const camera3_callback_ops_t *, const void *);
static camera3_callback_ops_t g_our_callbacks = {
    .process_capture_result = portal_process_capture_result,
    .notify = portal_notify,
};

/* 前置声明（g_methods 要用） */
static int shim_open(const struct hw_module_t *module, const char *id,
                     struct hw_device_t **device);

/**
 * 我们导出的模块入口（符号名必须是 `HMI`：`HAL_MODULE_INFO_SYM` 展开后就是它）。
 *
 * 留 512 字节而不是 128：真实结构是 `camera_module_t`（见文件头"取舍 2"），
 * 声明小了会让 provider 读到相邻内存当回调/数量，直接崩在 init 里。
 */
struct portal_hmi_t {
    struct hw_module_t common; /* 前 128 字节：AOSP hw_module_t 布局 */
    uint32_t extra[96];        /* 留白：真实结构更大，绝不能少 */
};

__attribute__((visibility("default"))) struct portal_hmi_t HMI = {0};

static struct hw_module_methods_t g_methods = {.open = shim_open};

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
    uintptr_t target = (uintptr_t)addr;
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
    if (hdr->photo_req_w == w && hdr->photo_req_h == h) return;
    hdr->photo_req_w = w;
    hdr->photo_req_h = h;
    LOGI("静态流几何回写：%ux%u（app 据此编码照片）", w, h);
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

    if (!portal_build_maps(0, sw, sh, w, h)) return false;
    const uint32_t *map_rows = g_scale[0].rows;
    const uint32_t *map_cols = g_scale[0].cols;
    /* 亮度：逐行按列映射取源字节（查表，无分支计算） */
    for (uint32_t r = 0; r < h; r++) {
        const uint8_t *srow = src + (size_t)map_rows[r] * sy;
        uint8_t *drow = dy + (size_t)r * dystride;
        for (uint32_t x = 0; x < w; x++) drow[x] = srow[map_cols[x]];
    }
    if (dcb == NULL || dcstride == 0 || suv == 0) return true;
    if (!interleaved) {
        PORTAL_LOG_ONCE(LOGE, "目标色度不是 NV12/NV21 交织布局（step=%zu cb=%p cr=%p）→ 只写亮度",
                        dstep, dcb, dcr);
        return true;
    }
    /* 色度：半分辨率，每元素 2 字节（U,V）一起搬，省一半访存 */
    uint32_t cw = w / 2, ch = h / 2;
    if (!portal_build_maps(1, sw / 2, sh / 2, cw, ch)) return true;
    const uint32_t *cmap_rows = g_scale[1].rows;
    const uint32_t *cmap_cols = g_scale[1].cols;
    for (uint32_t r = 0; r < ch; r++) {
        const uint8_t *srow = suvp + (size_t)cmap_rows[r] * suv;
        uint8_t *drow = dcb + (size_t)r * dcstride;
        for (uint32_t x = 0; x < cw; x++) {
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

static int shim_open(const struct hw_module_t *module, const char *id,
                     struct hw_device_t **device) {
    (void)module;
    load_options();
    if (id != NULL) snprintf(g_cur_cam, sizeof(g_cur_cam), "%s", id);
    if (g_real_open == NULL) {
        LOGE("真实 HAL 未加载，open 失败");
        return -1;
    }
    /* hw_get_module 会把我们的 HMI.dso 设成"我们自己的句柄"，而我们要以**真实模块**的身份
     * 去开设备：指回真实句柄，免得 vendor 实现拿它 dlsym 自己的符号时找不到 */
    HMI.common.dso = g_real_handle;
    int rc = g_real_open(g_real_module, id, device);
    if (rc == 0 && device != NULL && *device != NULL) {
        const struct camera3_device *cam_dev = (const struct camera3_device *)*device;
        /* initialize 的真实实现：优先用导出符号，拿不到就用 ops[0] 的当前值兜底 */
        if (g_real_initialize == NULL && cam_dev->ops != NULL) {
            g_real_initialize = (int (*)(const struct camera3_device *,
                                         const camera3_callback_ops_t *))((void **)cam_dev->ops)[0];
            LOGI("initialize 改由 ops[0] 兜底取得：%p", (void *)g_real_initialize);
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

static void load_real_hal(void) {
    if (g_real_handle != NULL) return;

    g_real_handle = dlopen(REAL_HAL_PATH, RTLD_NOW | RTLD_LOCAL);
    if (g_real_handle == NULL) {
        LOGE("dlopen 原件失败（%s）：%s", REAL_HAL_PATH, dlerror());
        return;
    }
    g_real_module = (struct hw_module_t *)dlsym(g_real_handle, "HMI");
    if (g_real_module == NULL) {
        LOGE("原件里没有 HMI 符号");
        dlclose(g_real_handle);
        g_real_handle = NULL;
        return;
    }

    /* 整块照抄：tag / 版本 / id / name / author / methods / dso，
     * 以及 camera_module_t 那一串回调（少抄一个都可能被 provider 当成垃圾指针调用） */
    memcpy(&HMI, g_real_module, REAL_HMI_BYTES);

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

    LOGI("真实 HAL 已加载并整块接管：%s（tag=0x%08x id=%s name=%s api=0x%04x）",
         REAL_HAL_PATH, HMI.common.tag, HMI.common.id, HMI.common.name,
         HMI.common.module_api_version);
}

__attribute__((constructor)) static void on_load(void) {
    load_options();
    load_real_hal();
}
