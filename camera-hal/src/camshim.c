/*
 * PortalEX 摄像头 HAL 包装层（Magisk 模块 portalex-camera-hal 的载荷）。
 *
 * ## 为什么是这一层
 *
 * 实测（MI6 / Android 15 / msm8998）：
 *   · `cameraserver`（`1373 1 cameraserver`）与 camera provider（`1038 1 …@2.4-service`）
 *     都是 **init 起的独立进程**，不经 zygote ⇒ LSPosed 注入不到；
 *   · 视频流从不经过 system_server ⇒ 那里只能做控制面（见 hooks/camera/CameraWatch.kt）；
 *   · provider 是 **32 位**，通过 `hw_get_module("camera")` 加载
 *     `/vendor/lib/hw/camera.msm8998.so`（导出
 *     `_ZN7qcamera25QCamera3HardwareInterface23process_capture_requestEPK14camera3_deviceP23camera3_capture_request`）。
 *
 * 于是拦截点选在 **vendor HAL 的 camera3 C API**：本 .so 覆盖同一个路径，导出同名模块入口
 * （`HAL_MODULE_INFO_SYM` → 符号名 `HMI`），转发 `open()`，然后把真实设备的
 * `camera3_device_ops` 表里 `process_capture_request` 那一格换成我们的函数 ——
 * 应用一行注入都没有，画面也终于经过我们手里。
 *
 * ## 两个刻意的取舍
 *
 * 1. **不引 `camera3.h`**（NDK 里没有；整份手抄结构一旦字段顺序错位就是"调错函数指针"，
 *    后果是相机崩在 HAL 里）。改成 **按符号地址在 ops 表里按值定位**：真实
 *    `process_capture_request` 的地址可以从 .so 里 dlsym 到，扫表找它即可 ——
 *    与结构布局完全无关，版本漂移也不怕。
 * 2. **原地替换那一格**（mprotect 改可写→改回只读），而不是整表复制：其余函数指针
 *    原封不动，行为与原来逐位一致，风险最小。
 *
 * ## 里程碑
 *
 *  M0（本文件当前状态）：**只接管 + 转发 + 计数**。目标是把"我们在帧通路上"变成一条
 *      可验证的日志，同时保证相机行为不变。
 *  M1：在 [shim_process_capture_request] 里把解码后的视频帧写进
 *      `request->output_buffers[i].buffer`（gralloc 句柄，格式/stride 按 stream 元数据），
 *      实现"应用看到的是我们的素材"。
 *
 * ## 纪律
 *
 *  · 这里是 **HAL 进程**：任何崩溃都会让整台设备的相机不可用。所有失败路径一律
 *    "安静降级为纯转发"，绝不 abort；
 *  · 不链接 libc++（`-DANDROID_STL=none`），不引入额外运行时依赖；
 *  · 只在 M0 记日志（限流），不为每条请求做字符串处理。
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
 * **为什么放在 vendor 库目录里而不是 /data/adb**：实测（MI6/Android 15）`/data/adb/portalex/**`
 * 的文件被标成 `adb_data_file`，`hal_camera_default` 读不了它 —— dlopen 直接报
 * "library not found"（EACCES 被 linker 报成 not found）。放在同一个 vendor 库目录下、
 * 只换个文件名，则自动继承 `vendor_file` 标签：HAL 域本来就在这个目录里加载库，
 * **一行 sepolicy 都不用写**。文件由模块安装时的 customize.sh 从 /vendor 复制（那时还没挂载）。
 */
#define REAL_HAL_PATH "/vendor/lib/hw/camera.msm8998.orig.so"

/* ------------------------------------------------------------------ *
 * AOSP 稳定 ABI 的最小声明
 *
 * 这几个结构是 HAL 的公开 ABI（`hardware/hardware.h`），自 Android 起未变过，
 * 且都带 `reserved` 数组兜底。只声明我们真正碰得到的字段，其余用 reserved 占位。
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
    struct hw_module_methods_t *methods;
    void *dso;
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

/** 捕获请求/流等结构一律用不透明指针 —— M0 只透传，M1 再按需要声明字段 */
typedef struct camera3_capture_request camera3_capture_request_t;

/*
 * `HARDWARE_MODULE_TAG` = MAKE_TAG_CONSTANT('H','W','M','T') = 0x48574D54。
 *
 * ⚠️ 这个值写错会**当场炸掉相机**：hw_get_module 读到 tag 不符就判定"这不是 HAL 模块"，
 * provider 初始化失败 → init 每 5 秒重启一次它（实测踩过：相机整个不可用）。
 * `HARDWARE_DEVICE_TAG` 是 'HWDV'，这里只作记录，我们不改设备结构里的字段。
 */
#define HARDWARE_MODULE_TAG 0x48574D54u /* 'HWMT' */
#define HARDWARE_DEVICE_TAG 0x48574456u /* 'HWDV' */

/* ------------------------------------------------------------------ *
 * 状态
 * ------------------------------------------------------------------ */

static void *g_real_handle = NULL;
static struct hw_module_t *g_real_module = NULL;
static int (*g_real_open)(const struct hw_module_t *, const char *, struct hw_device_t **) = NULL;
static int (*g_real_process_capture_request)(const struct camera3_device *,
                                             camera3_capture_request_t *) = NULL;

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
 * 按**函数地址**扫表：不依赖结构布局、不依赖它在第几格。找到后原地改写那一格
 * （表通常在 `.data.rel.ro`，先 mprotect 成可写，改完立刻还原只读）。
 */
static void patch_ops(const struct camera3_device *dev) {
    if (dev == NULL || dev->ops == NULL || g_real_process_capture_request == NULL) return;

    void **table = (void **)dev->ops;
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;

    /* 表可能跨页：把覆盖整张表的页都改成可写（上限 64 格） */
    uintptr_t begin = (uintptr_t)table & ~(uintptr_t)(page_size - 1);
    uintptr_t end = ((uintptr_t)(table + 64) + page_size - 1) & ~(uintptr_t)(page_size - 1);
    size_t span = (size_t)(end - begin);
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
    /* 还原为只读（即使没找到也要还原） */
    mprotect((void *)begin, span, PROT_READ);

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
    int rc = g_real_open(g_real_module, id, device);
    if (rc == 0 && device != NULL && *device != NULL) {
        patch_ops((const struct camera3_device *)*device);
    }
    return rc;
}

/* ------------------------------------------------------------------ *
 * 模块入口
 * ------------------------------------------------------------------ */

static struct hw_module_methods_t g_methods = {.open = shim_open};

/* 符号名必须是 HMI（`HAL_MODULE_INFO_SYM` 展开后就是它，hw_get_module 按 "HMI" 查） */
struct hw_module_t HMI = {
    .tag = HARDWARE_MODULE_TAG,
    .module_api_version = 0x0204, /* CAMERA_MODULE_API_VERSION_2_4，运行时会用真值覆盖 */
    .hal_api_version = 0,         /* HARDWARE_HAL_API_VERSION */
    .id = "camera",               /* hw_get_module("camera") 会核对这个字段 */
    .name = "PortalCamShim",
    .author = "PortalEX",
    .methods = &g_methods,
    .dso = NULL,
    .reserved = {0},
};

/** dlopen 真实 HAL 并把它的元信息搬到我们的入口结构上（在 hw_get_module 读表之前完成） */
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
    if (g_real_module->methods != NULL) {
        g_real_open = g_real_module->methods->open;
    }
    /* 真实 process_capture_request 的地址：QTI 把它作为导出符号留着，direct dlsym 即可 */
    g_real_process_capture_request =
        (int (*)(const struct camera3_device *, camera3_capture_request_t *))dlsym(
            g_real_handle,
            "_ZN7qcamera25QCamera3HardwareInterface23process_capture_requestEPK14camera3_deviceP23camera3_capture_request");
    if (g_real_process_capture_request == NULL) {
        /* 换名字也不致命：只是没法按地址定位 ops 表里那一格，退化为纯转发 */
        LOGE("没找到 process_capture_request 导出符号：本次不接管（相机行为不变）");
    }

    /*
     * **身份照抄原件**：id / name / author / 版本号全部以真实模块为准。
     *
     * ⚠️ 这里踩过最大的一个坑：`HMI.id` 一开始被我硬写成 "camera"，看似"就是 HAL 类名"，
     * 实际 `hw_get_module_by_class` 组装出的名字是 **"camera.msm8998"**（类名 + ro.hardware 实例），
     * 并用它核对 `hmi->id` —— 对不上就判定"这不是我要的模块"。后果不是"不生效"，而是
     * provider 在 `CameraModule::init()` 里拿到一个身份不对的模块，随后
     * `libutils` 的 Vector 容量断言溢出 → SIGABRT → init 每 5 秒重启一次 provider，
     * **整机相机不可用**（实测：启用模块 35 次 abort / 停用后 0 次、PID 恒定）。
     * 透明包装层的正确做法只有一个：除了 `methods`（那是我们要接管的东西），其余字段全抄。
     */
    HMI.module_api_version = g_real_module->module_api_version;
    HMI.hal_api_version = g_real_module->hal_api_version;
    if (g_real_module->id != NULL) HMI.id = g_real_module->id;
    if (g_real_module->name != NULL) HMI.name = g_real_module->name;
    if (g_real_module->author != NULL) HMI.author = g_real_module->author;

    LOGI("真实 HAL 已加载：%s（id=%s name=%s api=0x%04x）",
         REAL_HAL_PATH, HMI.id, HMI.name, HMI.module_api_version);
}

__attribute__((constructor)) static void on_load(void) {
    load_real_hal();
}
