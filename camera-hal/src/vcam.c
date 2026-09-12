/*
 * PortalEX 自建虚拟摄像头 HAL（camera module）。
 *
 * ## 为什么走到这一步
 *
 * 之前是在 vendor HAL 外面包一层（camshim.c）：拦它的 camera3 C API，往**它的**输出 buffer 里
 * 写画面。真机实测这条路在本 ROM 上不通 —— 那批 buffer 来自旧分配路径（无 gralloc4 描述符）：
 *   · `GraphicBufferMapper::getUsage` → ENOSYS（无法安全探测能否 CPU 写）
 *   · `eglCreateImageKHR` → EGL_BAD_PARAMETER（也不能当 EGLImage 画）
 * 于是"改别人的 buffer"这件事既写不进也画不进。本文件换掉整个思路：
 *
 *   **我们自己就是 camera HAL** —— 帧由我们产出，buffer 的用途位由我们在
 *   `configure_streams` 里提出来（这时框架还没分配 buffer，所以真的会生效），
 *   于是应用拿到的就是"我们写的帧"。
 *
 * ## 关键设计
 *
 * 1. **元数据转发真 HAL**：`get_number_of_cameras` / `get_camera_info` 直接转给 vendor HAL
 *    （dlopen 原件副本）。相机元数据（可用能力、支持的流配置、控制模式…）是应用
 *    （尤其 CameraX）做校验的重头戏，自己编一份几乎必然被拒 —— 转发是最稳的。
 *    为此保留一个"只用来取模板"的真实 device：`construct_default_request_settings` 也转发。
 * 2. **帧通路完全自持**：`process_capture_request` 里往框架给的 buffer 写图案，
 *    然后立刻回 `process_capture_result`（release_fence = -1，表示已就绪）。
 * 3. **失败一律降级**：任何一步出错都尽量"回一个空结果/直接返回错误码"，绝不 abort
 *    —— 这是 HAL 进程，崩了整机相机就没了。
 *
 * ## 里程碑
 *
 *  B2-M0（本文件）：相机能被应用打开、流能配上、每帧画上合成条纹（证明"帧由我们产出"）。
 *  B2-M1：把图案换成解码后的视频点素材（接 provider 投递的那条通道 + 选片引擎）。
 */
#define LOG_TAG "PortalVCam"

#include <android/log.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define REAL_HAL_PATH "/vendor/lib/hw/camera.msm8998.orig.so"
#define REAL_HMI_BYTES 176u

/* ---------------- AOSP 稳定 ABI 的最小声明（与 camshim.c 同源） ---------------- */

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

struct camera3_device {
    struct hw_device_t common;
    const void *ops;
    void *priv;
};

typedef struct camera3_stream {
    int stream_type;
    uint32_t width;
    uint32_t height;
    int format;
    uint32_t usage;
    uint32_t max_buffers;
    void *priv;
} camera3_stream_t;

typedef struct camera3_stream_buffer {
    camera3_stream_t *stream;
    void **buffer;
    int status;
    int acquire_fence;
    int release_fence;
} camera3_stream_buffer_t;

typedef struct camera3_stream_configuration {
    uint32_t num_streams;
    camera3_stream_t **streams;
    uint32_t operation_mode;
} camera3_stream_configuration_t;

typedef struct camera3_capture_request {
    uint32_t frame_number;
    const void *settings; /* camera_metadata_t* */
    const camera3_stream_buffer_t *input_buffer;
    const camera3_stream_buffer_t *output_buffers;
    uint32_t num_output_buffers;
} camera3_capture_request_t;

typedef struct camera3_capture_result {
    uint32_t frame_number;
    const void *result;
    uint32_t num_output_buffers;
    const camera3_stream_buffer_t *output_buffers;
    const camera3_stream_buffer_t *input_buffer;
    uint32_t partial_result;
} camera3_capture_result_t;

typedef struct camera3_notify_msg {
    int type; /* 0 = SHUTTER */
    union {
        struct {
            uint32_t frame_number;
            int64_t timestamp;
        } shutter;
        struct {
            uint32_t frame_number;
            int error_code;
        } error;
    } message;
} camera3_notify_msg_t;

typedef struct camera3_callback_ops {
    void (*process_capture_result)(const struct camera3_callback_ops *,
                                   const camera3_capture_result_t *);
    void (*notify)(const struct camera3_callback_ops *, const camera3_notify_msg_t *);
} camera3_callback_ops_t;

typedef struct camera3_device_ops {
    int (*initialize)(const struct camera3_device *, const camera3_callback_ops_t *);
    int (*configure_streams)(const struct camera3_device *, camera3_stream_configuration_t *);
    int (*register_stream_buffers)(const struct camera3_device *, void *);
    const void *(*construct_default_request_settings)(const struct camera3_device *, int);
    int (*process_capture_request)(const struct camera3_device *, camera3_capture_request_t *);
    void (*get_metadata_vendor_tag_ops)(const struct camera3_device *, void *);
    void (*dump)(const struct camera3_device *, int);
    int (*flush)(const struct camera3_device *);
} camera3_device_ops_t;

#define HARDWARE_MODULE_TAG 0x48574D54u
#define HARDWARE_DEVICE_TAG 0x48574456u
#define CAMERA_DEVICE_API_VERSION_3_0 0x0300
#define CAMERA_MODULE_API_VERSION_2_4 0x0204

/* gralloc 用法位 */
#define USAGE_SW_READ_OFTEN 0x00000003u
#define USAGE_SW_WRITE_OFTEN 0x00000030u

/* libui mapper（成员函数，需单例 this） */
struct portal_rect {
    int32_t left, top, right, bottom;
};

struct portal_android_ycbcr {
    void *y;
    void *cb;
    void *cr;
    size_t ystride;
    size_t cstride;
    size_t chroma_step;
    uint32_t reserved[8];
};

static void *g_gbm_self = NULL;
static int (*g_gbm_lock_ycbcr)(void *, void *, uint32_t, const struct portal_rect *,
                               struct portal_android_ycbcr *) = NULL;
static int (*g_gbm_lock)(void *, void *, uint32_t, const struct portal_rect *, void **) = NULL;
static int (*g_gbm_unlock)(void *, void *, void *) = NULL;

/* ---------------- 真 HAL（只用来转发元数据与模板） ---------------- */

static void *g_real_handle = NULL;
static struct hw_module_t *g_real_module = NULL;
/* 真 HAL 的 camera_module_t 尾部回调（转发用），按 AOSP 布局：hw_module_t 之后依次是
 * get_number_of_cameras / get_camera_info / set_callbacks / get_vendor_tag_ops / open_legacy … */
struct real_camera_module {
    struct hw_module_t common;
    int (*get_number_of_cameras)(void);
    int (*get_camera_info)(int camera_id, void *info);
    int (*set_callbacks)(const void *callbacks);
    void (*get_vendor_tag_ops)(void *ops);
    int (*open_legacy)(const struct hw_module_t *, const char *, uint32_t, struct hw_device_t **);
};
static struct real_camera_module *g_real_cam = NULL;

static void *g_real_device = NULL; /* 只用于 construct_default_request_settings */
static const void *(*g_real_default_settings)(const struct camera3_device *, int) = NULL;

/*
 * 真 device 的"空回调"：我们只借真 HAL 的**模板构造**能力，绝不接它的帧。
 *
 * 为什么必须先给真 device 调一次 initialize：实测 —— 不调就直接转发
 * `construct_default_request_settings`，真 HAL 内部状态是空的，provider 当场
 * SIGSEGV（fault addr 0x1）。它的模板路径依赖 initialize 里建立的内部对象。
 */
static void real_dummy_result(const camera3_callback_ops_t *ops, const camera3_capture_result_t *r) {
    (void)ops;
    (void)r;
}
static void real_dummy_notify(const camera3_callback_ops_t *ops, const camera3_notify_msg_t *m) {
    (void)ops;
    (void)m;
}
static camera3_callback_ops_t g_real_dummy_cbs = {real_dummy_result, real_dummy_notify};

/* ---------------- 结果元数据（libcamera_metadata.so） ----------------
 *
 * camera3 要求每帧结果带一份**结构合法**的 camera_metadata。我们最初传 NULL，
 * 框架直接报 `Camera2-Metadata: acquire: Failed to validate metadata structure 0x0`
 * 并把整个会话作废（应用随即放弃相机）。这里从平台的 libcamera_metadata.so 申请一份
 * （先空着 —— 空但合法的结构能过校验；时间戳等键等跑通后再补）。
 */
static void *g_meta_lib = NULL;
static void *(*p_alloc_meta)(size_t, size_t) = NULL;
static void *g_result_meta = NULL;

static void ensure_result_meta(void) {
    if (g_result_meta != NULL) return;
    if (g_meta_lib == NULL) {
        g_meta_lib = dlopen("libcamera_metadata.so", RTLD_NOW | RTLD_LOCAL);
        if (g_meta_lib == NULL) {
            LOGE("dlopen libcamera_metadata.so 失败：%s", dlerror());
            return;
        }
        p_alloc_meta = (void *(*)(size_t, size_t))dlsym(g_meta_lib, "allocate_camera_metadata");
    }
    if (p_alloc_meta == NULL) return;
    g_result_meta = p_alloc_meta(4, 64);
    LOGI("结果元数据已就绪：%p", g_result_meta);
}

/* ---------------- 我们自己的 device ---------------- */

static const camera3_callback_ops_t *g_framework_cb = NULL;
static camera3_device_ops_t g_ops;
static struct camera3_device g_device;
static unsigned long g_frames = 0;

static bool load_real(void) {
    if (g_real_handle != NULL) return true;
    g_real_handle = dlopen(REAL_HAL_PATH, RTLD_NOW | RTLD_LOCAL);
    if (g_real_handle == NULL) {
        LOGE("dlopen 真 HAL 失败：%s（元数据无法转发，虚拟相机不可用）", dlerror());
        return false;
    }
    g_real_module = (struct hw_module_t *)dlsym(g_real_handle, "HMI");
    if (g_real_module == NULL) return false;
    g_real_cam = (struct real_camera_module *)g_real_module;
    return true;
}

static void load_mapper(void) {
    if (g_gbm_lock != NULL) return;
    void *h = dlopen("libui.so", RTLD_NOW | RTLD_LOCAL);
    if (h == NULL) return;
    void (*preload)(void) = (void (*)(void))dlsym(h, "_ZN7android19GraphicBufferMapper10preloadHalEv");
    void **slot = (void **)dlsym(h, "_ZN7android9SingletonINS_19GraphicBufferMapperEE9sInstanceE");
    g_gbm_self = slot ? *slot : NULL;
    g_gbm_lock_ycbcr = (int (*)(void *, void *, uint32_t, const struct portal_rect *,
                                struct portal_android_ycbcr *))dlsym(
        h, "_ZN7android19GraphicBufferMapper9lockYCbCrEPK13native_handlejRKNS_4RectEP13android_ycbcr");
    g_gbm_lock = (int (*)(void *, void *, uint32_t, const struct portal_rect *, void **))dlsym(
        h, "_ZN7android19GraphicBufferMapper4lockEPK13native_handlejRKNS_4RectEPPv");
    g_gbm_unlock = (int (*)(void *, void *, void *))dlsym(
        h, "_ZN7android19GraphicBufferMapper6unlockEPK13native_handlePNS_4base14unique_fd_implINS4_13DefaultCloserEEE");
    if (g_gbm_self == NULL) {
        /* 单例还没建：用 preloadHal 触发一次（它也需要 this，这里退化为不调用） */
        (void)preload;
        LOGE("mapper 单例未就绪（sInstance=NULL）");
    }
}

/* ---------------- camera3_device_ops 实现 ---------------- */

static int vcam_initialize(const struct camera3_device *dev, const camera3_callback_ops_t *cb) {
    (void)dev;
    g_framework_cb = cb;
    LOGI("initialize：框架回调已记录（虚拟相机开始供帧）");
    return 0;
}

static int vcam_configure_streams(const struct camera3_device *dev,
                                  camera3_stream_configuration_t *cfg) {
    (void)dev;
    if (cfg == NULL) return -1;
    for (uint32_t i = 0; i < cfg->num_streams; i++) {
        camera3_stream_t *st = cfg->streams[i];
        if (st == NULL) continue;
        /* **关键**：这时候框架还没分配 buffer —— 我们提的用法位会真正生效，
         * 于是拿到的就是"我们自己能写的"buffer（这正是外包一层做不到的事）。 */
        if (st->stream_type == 0 /* OUTPUT */) {
            /*
             * **把 IMPLEMENTATION_DEFINED 落成具体的 YUV 格式**（0x22 → 0x23）。
             *
             * 为什么必须这么做：`GraphicBufferMapper::lockYCbCr` 只认具体 YUV 格式，
             * 对 IMPLEMENTATION_DEFINED 直接返回错误（实测 `lockYCbCr 失败（1600x1200）`），
             * 于是我们拿不到平面指针、也就写不进帧。相机 HAL 本来就有权为
             * IMPLEMENTATION_DEFINED 选定实际格式，选 YUV_420_888 既让我们能 CPU 写，
             * 也仍能被 GPU 当纹理采样（HW_TEXTURE 在），预览显示不受影响。
             */
            if (st->format == 0x22 /* IMPLEMENTATION_DEFINED */) {
                st->format = 0x23; /* YCbCr_420_888 */
            }
            st->usage |= USAGE_SW_READ_OFTEN | USAGE_SW_WRITE_OFTEN;
            /*
             * **max_buffers 必须由 HAL 填**：框架传进来的是 0，不填就会在
             * `configureConsumerQueueLocked` 直接失败 ——
             * `Camera HAL requested max_buffer count: 0, requires at least 1`
             * ⇒ `Unable to configure stream 0 queue: Function not implemented (-38)`
             * ⇒ 应用收到 onConfigureFailed（实测踩过）。
             * BLOB（JPEG，format 0x21）只留 2 个：它不参与高频循环。
             */
            if (st->max_buffers == 0) {
                st->max_buffers = (st->format == 0x21 /* BLOB */) ? 2u : 4u;
            }
        }
        LOGI("configure_streams[%u]：%ux%u format=0x%x usage=0x%x max_buffers=%u stream_type=%d", i,
             st->width, st->height, st->format, st->usage, st->max_buffers, st->stream_type);
    }
    return 0;
}

static const void *vcam_default_settings(const struct camera3_device *dev, int template_id) {
    (void)dev;
    if (g_real_default_settings != NULL) {
        return g_real_default_settings((const struct camera3_device *)g_real_device, template_id);
    }
    return NULL;
}

/** 往一个输出 buffer 画合成条纹。成功返回 true */
static bool paint(void *handle, uint32_t w, uint32_t h, uint32_t frame) {
    if (handle == NULL || w == 0 || h == 0) return false;
    /* 只画我们自己在 configure_streams 里标了 SW 用途的流（否则 lock 会 abort） */
    load_mapper();
    if (g_gbm_lock_ycbcr == NULL || g_gbm_self == NULL) return false;
    struct portal_android_ycbcr yc;
    memset(&yc, 0, sizeof(yc));
    struct portal_rect r = {0, 0, (int32_t)w, (int32_t)h};
    if (g_gbm_lock_ycbcr(g_gbm_self, handle, USAGE_SW_READ_OFTEN | USAGE_SW_WRITE_OFTEN, &r, &yc) !=
        0) {
        static unsigned long n = 0;
        if (__atomic_add_fetch(&n, 1, __ATOMIC_RELAXED) == 1) LOGE("lockYCbCr 失败（%ux%u）", w, h);
        return false;
    }
    uint8_t *base = (uint8_t *)yc.y;
    if (base != NULL && yc.ystride > 0) {
        int band = (int)(h / 12 > 4 ? h / 12 : 4);
        int offset = (int)((frame * 8u) % (uint32_t)(h + (uint32_t)band)) - band;
        for (uint32_t y = 0; y < h; y++) {
            uint8_t *row = base + (size_t)y * yc.ystride;
            int idx = (int)y - offset;
            uint8_t v = (idx >= 0 && band > 0) ? (((idx / band) % 2) ? 40 : 210) : 20;
            memset(row, v, w);
        }
    }
    int fence = -1;
    g_gbm_unlock(g_gbm_self, handle, &fence);
    return base != NULL;
}

static int vcam_process_capture_request(const struct camera3_device *dev,
                                       camera3_capture_request_t *req) {
    (void)dev;
    if (req == NULL) return -1;

    /*
     * **结构布局自适应**（实测踩过）：按 AOSP 教科书布局取 `output_buffers` 时，
     * 我在偏移 0xc 读到的是数值 1（其实就是 `num_output_buffers`），解引用即
     * `SIGSEGV fault addr 0x1`（tombstone: vcam_process_capture_request+0x4a，
     * 指令正是 `ldr r0, [r0]` 读 `sb->stream`）。也就是说本机 provider 传给 HAL 的
     * `camera3_capture_request_t` 与 AOSP 头文件的字段偏移**不一致**。
     *
     * 与其赌一个版本，这里按"哪一组看起来像 (指针, 计数)"来选，并把原始字段打一条日志
     * —— 既当场可用，又把真相留在日志里供以后收敛。
     */
    const char *raw = (const char *)req;
    /*
     * **本机实测布局**（日志 `请求布局探测` 一次就定了）：
     *   +0 frame_number=0  +4 settings(指针)  +8 0x0  +12 0x1(计数)  +16 指针
     * ⇒ provider 传进来的结构里 `num_output_buffers` **在 output_buffers 之前**，
     * 与 AOSP `camera3.h` 的字段顺序不同（按头文件解引用就是那次 SIGSEGV@0x1）。
     * 实测优先：先试这个布局，再退回教科书顺序，最后退回"少一个指针"的顺序。
     */
    const void *out_measured = *(const void *const *)(raw + 16);
    uint32_t num_measured = *(const uint32_t *)(raw + 12);
    const void *out_a = *(const void *const *)(raw + 12);
    uint32_t num_a = *(const uint32_t *)(raw + 16);
    const void *out_b = *(const void *const *)(raw + 8);
    uint32_t num_b = *(const uint32_t *)(raw + 12);
    const void *outputs = NULL;
    uint32_t num = 0;
    const char *which = "none";
    if ((uintptr_t)out_measured > 0x1000u && num_measured > 0 && num_measured <= 16u) {
        outputs = out_measured;
        num = num_measured;
        which = "实测(+16/+12)";
    } else if ((uintptr_t)out_a > 0x1000u && num_a > 0 && num_a <= 16u) {
        outputs = out_a;
        num = num_a;
        which = "AOSP(+12/+16)";
    } else if ((uintptr_t)out_b > 0x1000u && num_b > 0 && num_b <= 16u) {
        outputs = out_b;
        num = num_b;
        which = "备选(+8/+12)";
    }
    static bool logged_layout = false;
    if (!logged_layout) {
        logged_layout = true;
        LOGI("请求布局探测：frame=%u settings=%p raw[8]=%p raw[12]=%p raw[16]=%p → 采用 %s（num=%u）",
             *(const uint32_t *)raw, *(const void *const *)(raw + 4),
             *(const void *const *)(raw + 8), *(const void *const *)(raw + 12),
             *(const void *const *)(raw + 16), which, num);
    }
    if (outputs == NULL) return 0; /* 认不出来就直接放行，绝不猜着解引用 */

    const camera3_stream_buffer_t *sbs = (const camera3_stream_buffer_t *)outputs;
    bool painted = false;
    for (uint32_t i = 0; i < num; i++) {
        const camera3_stream_buffer_t *sb = &sbs[i];
        if (sb->stream == NULL || sb->buffer == NULL || (uintptr_t)(*sb->buffer) < 0x1000u) {
            continue;
        }
        if (paint(*sb->buffer, sb->stream->width, sb->stream->height, req->frame_number)) {
            painted = true;
        }
    }
    if (painted) {
        unsigned long n = __atomic_add_fetch(&g_frames, 1, __ATOMIC_RELAXED);
        if (n == 1 || n % 120 == 0) LOGI("虚拟相机已产出帧：#%lu (frame=%u)", n, req->frame_number);
    }

    if (g_framework_cb == NULL) return 0;

    /* 通知快门，然后交结果。buffer 已写好，release_fence 置 -1 表示"已就绪" */
    camera3_notify_msg_t msg;
    memset(&msg, 0, sizeof(msg));
    msg.type = 0; /* CAMERA3_MSG_SHUTTER */
    msg.message.shutter.frame_number = req->frame_number;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    msg.message.shutter.timestamp = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
    if (g_framework_cb->notify != NULL) g_framework_cb->notify(g_framework_cb, &msg);

    camera3_capture_result_t res;
    memset(&res, 0, sizeof(res));
    res.frame_number = req->frame_number;
    ensure_result_meta();
    res.result = g_result_meta; /* 必须是结构合法的元数据（NULL 会被框架校验拒掉） */
    res.num_output_buffers = num;
    res.output_buffers = sbs;
    res.partial_result = 0;
    g_framework_cb->process_capture_result(g_framework_cb, &res);
    return 0;
}

static void vcam_dump(const struct camera3_device *dev, int fd) {
    (void)dev;
    (void)fd;
}

static int vcam_flush(const struct camera3_device *dev) {
    (void)dev;
    return 0;
}

static int vcam_close(struct hw_device_t *device) {
    (void)device;
    g_framework_cb = NULL;
    LOGI("device closed");
    return 0;
}

/* ---------------- 模块入口 ---------------- */

static int vcam_open(const struct hw_module_t *module, const char *id, struct hw_device_t **device);

static struct hw_module_methods_t g_methods = {.open = vcam_open};

struct portal_hmi_t {
    struct hw_module_t common;
    /* camera_module_t 的回调（我们转发真 HAL） */
    int (*get_number_of_cameras)(void);
    int (*get_camera_info)(int camera_id, void *info);
    int (*set_callbacks)(const void *callbacks);
    void (*get_vendor_tag_ops)(void *ops);
    int (*open_legacy)(const struct hw_module_t *, const char *, uint32_t, struct hw_device_t **);
    uint32_t extra[80];
};

__attribute__((visibility("default"))) struct portal_hmi_t HMI = {0};

static int vcam_number_of_cameras(void) {
    if (!load_real() || g_real_cam == NULL || g_real_cam->get_number_of_cameras == NULL) return 0;
    return g_real_cam->get_number_of_cameras();
}

static int vcam_get_camera_info(int camera_id, void *info) {
    if (!load_real() || g_real_cam == NULL || g_real_cam->get_camera_info == NULL) return -1;
    /* 元数据整份转发真 HAL：应用（尤其 CameraX）对相机能力校验很严，自己编必被拒 */
    return g_real_cam->get_camera_info(camera_id, info);
}

static int vcam_open(const struct hw_module_t *module, const char *id, struct hw_device_t **device) {
    (void)module;
    if (device == NULL) return -1;
    if (!load_real()) return -1;

    /* 只为取默认请求模板而开一个真 device；它的帧通路我们一概不用 */
    if (g_real_device == NULL && g_real_cam != NULL && g_real_cam->common.methods != NULL &&
        g_real_cam->common.methods->open != NULL) {
        struct hw_device_t *rd = NULL;
        if (g_real_cam->common.methods->open(&g_real_cam->common, id, &rd) == 0 && rd != NULL) {
            g_real_device = rd;
            /* 真 device 的版本号就是"框架认可的版本" —— 照它设，别猜 */
            LOGI("真 HAL device 版本=0x%x（我们的模块 API=0x%x）", rd->version,
                 HMI.common.module_api_version);
            const struct camera3_device *rcd = (const struct camera3_device *)rd;
            if (rcd->ops != NULL) {
                const void **table = (const void **)rcd->ops;
                g_real_default_settings =
                    (const void *(*)(const struct camera3_device *, int))table[3];
                /* ops[0] = initialize：先让真 device 把内部状态建起来，否则模板会崩 */
                int (*real_init)(const struct camera3_device *,
                                 const camera3_callback_ops_t *) =
                    (int (*)(const struct camera3_device *, const camera3_callback_ops_t *))table[0];
                if (real_init != NULL) {
                    int rc = real_init(rcd, &g_real_dummy_cbs);
                    LOGI("真 device initialize 完成（rc=%d，仅为取模板，不接它的帧）", rc);
                }
            }
        }
    }

    g_ops.initialize = vcam_initialize;
    g_ops.configure_streams = vcam_configure_streams;
    g_ops.register_stream_buffers = NULL;
    g_ops.construct_default_request_settings = vcam_default_settings;
    g_ops.process_capture_request = vcam_process_capture_request;
    g_ops.get_metadata_vendor_tag_ops = NULL;
    g_ops.dump = vcam_dump;
    g_ops.flush = vcam_flush;

    memset(&g_device, 0, sizeof(g_device));
    g_device.common.tag = HARDWARE_DEVICE_TAG;
    /* 与真 device 对齐：框架对 3.x 版本号有校验，猜错会在 open 后立刻被关掉 */
    uint32_t dev_version = CAMERA_DEVICE_API_VERSION_3_0;
    if (g_real_device != NULL) {
        uint32_t rv = ((struct hw_device_t *)g_real_device)->version;
        if (rv >= CAMERA_DEVICE_API_VERSION_3_0) dev_version = rv;
    }
    g_device.common.version = dev_version;
    g_device.common.module = (struct hw_module_t *)&HMI;
    g_device.common.close = vcam_close;
    g_device.ops = &g_ops;
    g_device.priv = NULL;

    *device = &g_device.common;
    LOGI("虚拟相机已 open（id=%s）", id ? id : "?");
    return 0;
}

__attribute__((constructor)) static void on_load(void) {
    /* 身份照抄真实模块（tag/id/name/版本），但回调与 open 都是我们的 */
    if (!load_real() || g_real_module == NULL) return;
    memcpy(&HMI, g_real_module, REAL_HMI_BYTES);
    HMI.common.methods = &g_methods;
    HMI.get_number_of_cameras = vcam_number_of_cameras;
    HMI.get_camera_info = vcam_get_camera_info;
    HMI.common.dso = g_real_handle;
    LOGI("虚拟相机模块已加载：id=%s name=%s 相机数=%d", HMI.common.id, HMI.common.name,
         vcam_number_of_cameras());
}
