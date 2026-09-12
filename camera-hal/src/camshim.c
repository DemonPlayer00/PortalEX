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

/** 捕获请求一律用不透明指针：M0 只透传，M1 再按需要声明字段 */
typedef struct camera3_capture_request camera3_capture_request_t;

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
        patch_ops((const struct camera3_device *)*device);
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

    LOGI("真实 HAL 已加载并整块接管：%s（tag=0x%08x id=%s name=%s api=0x%04x）",
         REAL_HAL_PATH, HMI.common.tag, HMI.common.id, HMI.common.name,
         HMI.common.module_api_version);
}

__attribute__((constructor)) static void on_load(void) {
    load_real_hal();
}
