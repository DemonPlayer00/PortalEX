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
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
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

/* 注入相关状态 */
static portal_gralloc_module_t *g_gralloc = NULL;
static const camera3_callback_ops_t *g_framework_callbacks = NULL;
static bool g_gralloc_tried = false;
static unsigned long g_injected_frames = 0;

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
 * 转发 + 接管
 * ------------------------------------------------------------------ */

static int shim_process_capture_request(const struct camera3_device *dev,
                                        camera3_capture_request_t *request) {
    /* M0：只证明我们在通路上（限流：每 120 帧一条，避免刷爆日志） */
    static unsigned long calls = 0;
    unsigned long n = __atomic_add_fetch(&calls, 1, __ATOMIC_RELAXED);
    if (n == 1 || n % 120 == 0) {
        LOGI("process_capture_request 已接管：#%lu", n);
    }
    if (g_real_process_capture_request == NULL) return -1;
    return g_real_process_capture_request(dev, request);
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
 * 是否在 configure_streams 里给输出流补 SW 用途位。
 *
 * camera3 的既定机制：HAL 可以在 `configure_streams` 里改 `camera3_stream_t::usage`，
 * 框架会把 HAL 的位并进去并**按新 usage 重新分配 buffer** —— 这正是让"硬件写的相机流"
 * 变成"我们也能 CPU 写"的正规做法（否则只能眼巴巴看着 HW-only 的 buffer）。
 *
 * 默认关闭：改动会作用于**所有**相机流、影响每一次预览/录像的分配路径，
 * 必须在真机上单独验证（打开 → 看相机是否正常、日志里是否出现 M1a 注入计数）后再启用。
 * 打开方式：把下面的 0 改成 1，重新部署并观察 provider 是否稳定。
 */
#define PORTAL_ENABLE_SW_USAGE_PATCH 0

static int (*g_real_configure_streams)(const struct camera3_device *, void *) = NULL;

static int portal_configure_streams(const struct camera3_device *dev, void *stream_list) {
    if (stream_list != NULL && PORTAL_ENABLE_SW_USAGE_PATCH) {
        /* camera3_stream_configuration_t { uint32_t num_streams; camera3_stream_t **streams;
         *                                   uint32_t operation_mode; … } */
        struct {
            uint32_t num_streams;
            camera3_stream_t **streams;
        } *cfg = (void *)stream_list;
        if (cfg->streams != NULL) {
            for (uint32_t i = 0; i < cfg->num_streams; i++) {
                camera3_stream_t *st = cfg->streams[i];
                /* 只给"输出流"补位：INPUT 流（reprocess 的输入）不动 */
                if (st != NULL && st->stream_type == 0 /* OUTPUT */) {
                    st->usage |= PORTAL_ADD_SW_USAGE;
                }
            }
        }
    }
    if (g_real_configure_streams == NULL) return -1;
    return g_real_configure_streams(dev, stream_list);
}

/** 合成图案：移动竖条 + 横向渐变 —— 一眼就能看出"画面不是真实世界" */
static inline uint8_t portal_pattern(int x, int y, int w, int h, uint32_t frame) {
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
 * 兜底路径：没有 legacy gralloc 时用 libui 的 mapper 锁平面 0（Y）。
 *
 * **只写开头 [PORTAL_SAFE_ROWS] 行**，并且按 `row * width`（而不是真实 stride）定位：
 * 真实 stride ≥ width，所以写入范围 [0, rows*width) 一定落在平面起点之后、不会越界；
 * stride 更大时画面会有轻微斜切，但我们只要"看得见的证明"，安全性优先。
 */
#define PORTAL_SAFE_ROWS 32
/** 单次注入最多写多少字节：4KB 远小于任何相机平面的头部区域，绝不会越界 */
#define PORTAL_SAFE_BYTES (4u * 1024u)

static bool inject_via_libui(void *handle, uint32_t w, uint32_t h, uint32_t frame) {
    if (!load_libui_mapper()) return false;

    /*
     * **安全探测**：先问 buffer 自己的 usage，再决定要不要上锁。
     *
     * 这一步是本轮的关键：`GraphicBufferMapper::lock/lockYCbCr` 对"不可 CPU 映射"的 buffer
     * 是 **abort** 而不是返回错误（实测把 provider 打死两次），所以绝不能用"试着锁一下"来探测。
     * 而 `getUsage` 只是查描述符，失败也只是返回错误码 —— 拿它当门，才能安全地
     * "等框架按我们补的 SW 用途位重新分配 buffer，等到了再写"。
     */
    if (g_gbm_get_usage == NULL) return false;
    uint64_t usage = 0;
    int grc = g_gbm_get_usage(g_gbm_self, handle, &usage);
    if (grc != 0) {
        /* 描述符查询失败的常见原因：legacy 分配路径的 buffer 没有 mapper 描述符 */
        static unsigned long n = 0;
        if (__atomic_add_fetch(&n, 1, __ATOMIC_RELAXED) == 1) {
            LOGE("getUsage 失败 rc=%d（该 buffer 可能没有 mapper 描述符）→ 不注入", grc);
        }
        return false;
    }
    if ((usage & (uint64_t)PORTAL_GRALLOC_USAGE_SW_WRITE_MASK) == 0) {
        static unsigned long n = 0;
        if (__atomic_add_fetch(&n, 1, __ATOMIC_RELAXED) == 1) {
            LOGI("buffer 实际 usage=0x%llx 仍无 SW 写位：等框架按新 usage 重分配（本帧不注入）",
                 (unsigned long long)usage);
        }
        return false;
    }

    uint32_t lock_usage =
        PORTAL_GRALLOC_USAGE_SW_READ_OFTEN | PORTAL_GRALLOC_USAGE_SW_WRITE_OFTEN;
    struct portal_rect bounds = {0, 0, (int32_t)w, (int32_t)h};

    /* 首选 lockYCbCr：能拿到 Y 平面指针**与 stride**，可以写整幅 */
    if (g_gbm_lock_ycbcr != NULL) {
        struct portal_android_ycbcr yc;
        memset(&yc, 0, sizeof(yc));
        if (g_gbm_lock_ycbcr(g_gbm_self, handle, lock_usage, &bounds, &yc) != 0) {
            static unsigned long n = 0;
            if (__atomic_add_fetch(&n, 1, __ATOMIC_RELAXED) == 1) LOGE("lockYCbCr 失败");
            return false;
        }
        uint8_t *base = (uint8_t *)yc.y;
        if (base != NULL && yc.ystride > 0) {
            for (uint32_t y = 0; y < h; y++) {
                uint8_t *row = base + (size_t)y * yc.ystride;
                for (uint32_t x = 0; x < w; x++) {
                    row[x] = portal_pattern((int)x, (int)y, (int)w, (int)h, frame);
                }
            }
        }
        int fence = -1; /* unique_fd 在 ABI 上就是一个 int */
        g_gbm_unlock(g_gbm_self, handle, &fence);
        return base != NULL;
    }

    /* 退路：只有 lock（无 stride）时，只写开头一小块条纹，边界锁死 */
    void *vaddr = NULL;
    if (g_gbm_lock(g_gbm_self, handle, lock_usage, &bounds, &vaddr) != 0 || vaddr == NULL) {
        return false;
    }
    uint8_t *base = (uint8_t *)vaddr;
    size_t bytes = (size_t)w * (size_t)(h < PORTAL_SAFE_ROWS ? h : PORTAL_SAFE_ROWS);
    if (bytes > PORTAL_SAFE_BYTES) bytes = PORTAL_SAFE_BYTES;
    for (size_t i = 0; i < bytes; i++) base[i] = ((i / 64) % 2) ? 235 : 16;
    int fence = -1;
    g_gbm_unlock(g_gbm_self, handle, &fence);
    return true;
}

/** 往一个输出 buffer 的 Y 平面写图案；成功返回 true */
static bool inject_into_buffer(const camera3_stream_buffer_t *sb, uint32_t frame) {
    if (sb == NULL || sb->stream == NULL || sb->buffer == NULL) return false;
    camera3_stream_t *st = sb->stream;
    int format = st->format;
    /* 只碰 YUV 类输出：JPEG/BLOB（拍照）与 RAW 不动 —— 写坏它们会直接毁掉一次拍照 */
    if (format != PORTAL_PIXEL_FORMAT_YCbCr_420_888 &&
        format != PORTAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
        return false;
    }
    uint32_t w = st->width, h = st->height;
    if (w == 0 || h == 0) return false;

    if (!portal_cpu_writable(st)) {
        /* 保命门：HW-only 的流绝不尝试 CPU 映射（会 abort 掉 provider，实测） */
        static unsigned long n = 0;
        if (__atomic_add_fetch(&n, 1, __ATOMIC_RELAXED) == 1) {
            LOGI("输出流 format=0x%x usage=0x%x 没有 SW 写位：跳过注入（需 configure_streams 补位）",
                 format, st->usage);
        }
        return false;
    }

    /* 首选：legacy gralloc 的 lock_ycbcr（能拿到全部平面与 stride）。本机没有，故兜底。 */
    if (g_gralloc == NULL || g_gralloc->lock_ycbcr == NULL) {
        return inject_via_libui(*sb->buffer, w, h, frame);
    }

    struct portal_android_ycbcr ycbcr;
    memset(&ycbcr, 0, sizeof(ycbcr));
    int usage = PORTAL_GRALLOC_USAGE_SW_READ_OFTEN | PORTAL_GRALLOC_USAGE_SW_WRITE_OFTEN;
    if (g_gralloc->lock_ycbcr(g_gralloc, *sb->buffer, usage, 0, 0, (int)w, (int)h, &ycbcr) != 0) {
        /* 锁不上就纯转发：绝不能因此影响相机。限流记一条，便于判断是"偶发"还是"根本不支持" */
        static unsigned long lock_fail = 0;
        if (__atomic_add_fetch(&lock_fail, 1, __ATOMIC_RELAXED) == 1) {
            LOGE("lock_ycbcr 失败（format=0x%x %ux%u）：本流不注入", format, w, h);
        }
        return false;
    }
    uint8_t *base = (uint8_t *)ycbcr.y;
    if (base != NULL && ycbcr.ystride > 0) {
        for (uint32_t y = 0; y < h; y++) {
            uint8_t *row = base + (size_t)y * ycbcr.ystride;
            for (uint32_t x = 0; x < w; x++) {
                row[x] = portal_pattern((int)x, (int)y, (int)w, (int)h, frame);
            }
        }
    }
    g_gralloc->unlock(g_gralloc, *sb->buffer);
    return base != NULL;
}

/** 我们给 HAL 的回调表：先注入，再交给框架 */
static void portal_process_capture_result(const camera3_callback_ops_t *ops,
                                          const camera3_capture_result_t *result) {
    (void)ops;
    /* 诊断：确认回调真的进来了，并记录每个流一次的格式/尺寸（格式决定能不能锁、怎么锁） */
    static bool entered = false;
    if (!entered) {
        entered = true;
        LOGI("process_capture_result 已进入我们的回调（注入时序成立）");
    }
    if (result != NULL && result->output_buffers != NULL && result->num_output_buffers > 0) {
        for (uint32_t i = 0; i < result->num_output_buffers && i < 4; i++) {
            const camera3_stream_buffer_t *sb = &result->output_buffers[i];
            if (sb->stream == NULL) continue;
            static void *seen[8] = {0};
            bool known = false;
            for (int k = 0; k < 8; k++) {
                if (seen[k] == (void *)sb->stream) { known = true; break; }
                if (seen[k] == NULL) { seen[k] = (void *)sb->stream; break; }
            }
            if (!known) {
                LOGI("输出流：format=0x%x %ux%u usage=0x%x stream_type=%d",
                     sb->stream->format, sb->stream->width, sb->stream->height,
                     sb->stream->usage, sb->stream->stream_type);
            }
        }
        /* 不在这里判 gralloc：inject_into_buffer 自己会选路径（legacy lock_ycbcr 优先，
         * 否则退到 libui 的 mapper）。之前这里多一道 load_gralloc() 门槛，导致兜底路径永不执行。 */
        bool wrote = false;
        for (uint32_t i = 0; i < result->num_output_buffers; i++) {
            if (inject_into_buffer(&result->output_buffers[i], result->frame_number)) {
                wrote = true;
            }
        }
        if (wrote) {
            unsigned long n = __atomic_add_fetch(&g_injected_frames, 1, __ATOMIC_RELAXED);
            if (n == 1 || n % 120 == 0) {
                LOGI("M1a 已注入合成图案：#%lu（frame=%u）", n, result->frame_number);
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

/** 接管 ops 表里的 initialize（camera3_device_ops 的第 0 项，自 Android 5 起未变） */
static void patch_initialize(const struct camera3_device *dev) {
    if (dev == NULL || dev->ops == NULL || g_real_initialize == NULL) return;
    void **table = (void **)dev->ops;
    if (table[0] != (void *)g_real_initialize) {
        LOGE("ops[0] 不是预期的 initialize：本次不接管回调（相机行为不变）");
        return;
    }
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;
    int prot = page_protection(table);
    if (mprotect((void *)((uintptr_t)table & ~(uintptr_t)(page_size - 1)), (size_t)page_size,
                 PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect 失败：无法接管 initialize");
        return;
    }
    table[0] = (void *)portal_initialize;
    mprotect((void *)((uintptr_t)table & ~(uintptr_t)(page_size - 1)), (size_t)page_size,
             prot > 0 ? prot : (PROT_READ | PROT_WRITE));
    LOGI("已接管 initialize（ops[0]）：M1a 注入点就位");
}

/**
 * 把真实 ops 表里的 process_capture_request 换成我们的实现。
 *
 * 按**函数地址**扫表：不依赖结构布局、不依赖它在第几格。找到后原地改写那一格，
 * 并按页记下的原始权限还原（见 [page_protection]）。
 */
static void patch_ops(const struct camera3_device *dev) {
    if (dev == NULL || dev->ops == NULL || g_real_process_capture_request == NULL) return;

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
        return;
    }

    int hit = -1;
    for (int i = 0; i < 64; i++) {
        if (table[i] == (void *)g_real_process_capture_request) {
            table[i] = (void *)shim_process_capture_request;
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
        LOGI("已接管 process_capture_request（ops[%d]）", hit);
    } else {
        LOGE("ops 表里没找到真实 process_capture_request：本次不接管（相机行为不变）");
    }
}

static int shim_open(const struct hw_module_t *module, const char *id,
                     struct hw_device_t **device) {
    (void)module;
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
        }
        patch_ops(cam_dev);
        patch_initialize(cam_dev);
        /* ops[1] = configure_streams（camera3_device_ops 的既定顺序：
         * initialize, configure_streams, register_stream_buffers,
         * construct_default_request_settings, process_capture_request, …） */
        if (cam_dev->ops != NULL) {
            void **table = (void **)cam_dev->ops;
            g_real_configure_streams =
                (int (*)(const struct camera3_device *, void *))table[1];
            if (g_real_configure_streams != NULL) {
                long ps = sysconf(_SC_PAGESIZE);
                if (ps <= 0) ps = 4096;
                int prot = page_protection(table);
                if (mprotect((void *)((uintptr_t)table & ~(uintptr_t)(ps - 1)), (size_t)ps,
                             PROT_READ | PROT_WRITE) == 0) {
                    table[1] = (void *)portal_configure_streams;
                    mprotect((void *)((uintptr_t)table & ~(uintptr_t)(ps - 1)), (size_t)ps,
                             prot > 0 ? prot : (PROT_READ | PROT_WRITE));
                    LOGI("已接管 configure_streams（ops[1]，补 SW 用途位=%s）",
                         PORTAL_ENABLE_SW_USAGE_PATCH ? "开" : "关");
                }
            }
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
    /* initialize 的导出符号名较长且各版本可能不同：拿不到就退化为下面 patch_ops 里
     * "直接用 ops[0] 的当前值当真实实现"，两条路都能走通 */
    g_real_initialize = (int (*)(const struct camera3_device *,
                                 const camera3_callback_ops_t *))dlsym(
        g_real_handle,
        "_ZN7qcamera25QCamera3HardwareInterface10initializeEPK14camera3_devicePK21camera3_callback_ops");

    LOGI("真实 HAL 已加载并整块接管：%s（tag=0x%08x id=%s name=%s api=0x%04x）",
         REAL_HAL_PATH, HMI.common.tag, HMI.common.id, HMI.common.name,
         HMI.common.module_api_version);
}

__attribute__((constructor)) static void on_load(void) {
    load_real_hal();
}
