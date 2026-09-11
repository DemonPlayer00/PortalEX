/*
 * 运行时传感器接入（system_server 侧）—— 让投递 100% 由我们掌握。
 *
 * 设计依据（全部来自对 libsensorservice.so 的反汇编，不是推测）：
 *
 * 1) 三个 API 都是导出符号，可 dlsym：
 *      registerRuntimeSensor(sensor_t const&, int deviceId, sp<RuntimeSensorCallback>)
 *      sendRuntimeSensorEvent(sensors_event_t const&)
 *      unregisterRuntimeSensor(int)
 *    事件由 SensorService::RuntimeSensorHandler 自己的线程投递（其 threadLoop 只调
 *    sendRuntimeSensorEvent 与 Thread::exitPending，完全不碰 HAL poll）——投递节奏
 *    与底层传感器状态无关。
 *
 * 2) 实例从 ServiceManager 取 "sensorservice"。取到后**必须校验**才敢用：
 *    对象的 vptr 必须落在 libsensorservice.so 的 .data.rel.ro 内，再用导出的
 *    isSensorActive(已知 handle) 做一次行为确认。任一不过 → 整体放弃（功能不生效）。
 *
 * 3) 回调对象不能传空：注册路径对空 sp 有判空，但 RuntimeSensor::activate 里
 *      ldr x19,[x0,#0xa0] ; ldr x8,[x19] ; br [x8]
 *    直接解引用 —— 空回调一被客户端使能就段错误。
 *
 * 4) 回调对象的布局由**我们的虚表**决定（关键洞察）：
 *    服务构造 sp 时按 ABI 调整指针：
 *      ldr x9,[obj]          ; 我们的虚表
 *      ldur x9,[x9,#-0x18]   ; vtable[-3] = offset-to-top
 *      add x0,obj,x9         ; RefBase 子对象 = obj + vtable[-3]
 *      bl  RefBase::incStrong
 *    所以 RefBase 子对象放在 obj + REFBASE_OFF，而我们把自己的虚表 [-3] 写成
 *    REFBASE_OFF 即可 —— 我们同时控制接口 vptr 与 RefBase 子对象位置，
 *    两者互不干扰：接口方法走 [obj]（vtable[0] = onConfigurationChanged），
 *    RefBase 的虚函数走子对象自己的 vptr（由 libutils 的 RefBase 构造函数写入）。
 */
#include "portal_runtime_sensor.h"

#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "PortalSensor"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* RefBase 子对象在我们对象内的偏移（vtable[-3] 与它必须一致） */
#define REFBASE_OFF 16
#define OBJ_SIZE 96

typedef struct {
    void *vptr;          /* 接口虚表（我们自己的） */
    uint64_t pad;
    /* offset 16 起：libutils 的 RefBase 子对象，由 RefBase::RefBase() 初始化 */
    uint8_t refbase[OBJ_SIZE - REFBASE_OFF];
} fake_callback_t;

/*
 * 虚表布局（Itanium ABI）：[地址点 -3] = offset-to-top、[-2] = typeinfo、[-1] 保留，
 * 地址点本身 = 第一个虚函数。这里**必须显式留出 header 三个槽**——
 * 上一版只留了一个指针，写 [-3] 就越界写到结构体之前（落在只读段）→ system_server SIGSEGV。
 */
typedef struct {
    void *header[3]; /* [-3] offset-to-top、[-2] typeinfo、[-1] 保留 */
    void *fns[6];    /* 地址点：fns[0] = onConfigurationChanged */
} cb_vtable_t;

static int g_done = 0;
static int g_ok = 0;
static char g_msg[256] = "not probed";

static uintptr_t g_base = 0;
static char g_path[256];
static uintptr_t g_relro_addr = 0, g_relro_size = 0;

static void *g_service = NULL;       /* SensorService* （已校验） */
static void *g_api_register = NULL;
static void *g_api_send = NULL;
static void *g_api_unregister = NULL;
static void *g_api_issensoractive = NULL;
static fake_callback_t *g_callback = NULL;

/* ------------------------------------------------------------------ */
/* 模块基址 / RELRO 区间（只读；用于校验实例 vptr）                      */
/* ------------------------------------------------------------------ */

static int phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    const char *want = (const char *) data;
    if (info->dlpi_name == NULL || info->dlpi_name[0] == '\0') return 0;
    const char *slash = strrchr(info->dlpi_name, '/');
    const char *name = slash ? slash + 1 : info->dlpi_name;
    if (strcmp(name, want) != 0) return 0;
    g_base = (uintptr_t) info->dlpi_addr;
    snprintf(g_path, sizeof(g_path), "%s", info->dlpi_name);
    return 1;
}

static int ptr_in_relro(uintptr_t p) {
    if (g_relro_size == 0) return 0;
    uintptr_t from = g_base + g_relro_addr;
    return p >= from && p < from + g_relro_size;
}

/* ------------------------------------------------------------------ */
/* 回调对象                                                            */
/* ------------------------------------------------------------------ */

/** 客户端使能/改速率时框架会回调这里。我们不需要它做任何事：数据是我们主动推的。 */
static void cb_on_configuration_changed(void *self, int handle, int enabled,
                                        int64_t sampling_period_ns, int64_t batch_latency_ns) {
    (void) self;
    LOGI("runtime sensor cb: handle=0x%x enabled=%d period=%lldns batch=%lldns", handle, enabled,
         (long long) sampling_period_ns, (long long) batch_latency_ns);
}

static cb_vtable_t g_cb_vtable;

/**
 * 构造回调对象：接口 vptr 指向我们的虚表（[0] = onConfigurationChanged，
 * header 的 [-3] = REFBASE_OFF），RefBase 子对象由 libutils 的构造函数初始化。
 */
static int build_callback(void) {
    if (g_callback != NULL) return 1;

    void *refbase_ctor = dlsym(RTLD_DEFAULT, "_ZN7android7RefBaseC2Ev");
    if (refbase_ctor == NULL) {
        void *h = dlopen("libutils.so", RTLD_NOW | RTLD_LOCAL);
        if (h != NULL) refbase_ctor = dlsym(h, "_ZN7android7RefBaseC2Ev");
    }
    if (refbase_ctor == NULL) {
        LOGE("RefBase::RefBase() not resolvable - runtime sensor path disabled");
        return 0;
    }

    /* header：[-3] = offset-to-top（服务用它定位 RefBase 子对象）、[-2] typeinfo、[-1] 保留 */
    g_cb_vtable.fns[0] = (void *) &cb_on_configuration_changed; /* vtable[0] */
    g_cb_vtable.fns[1] = (void *) &cb_on_configuration_changed; /* 占位：接口无其它虚函数 */
    g_cb_vtable.fns[2] = NULL;
    g_cb_vtable.fns[3] = NULL;
    g_cb_vtable.fns[4] = NULL;
    g_cb_vtable.fns[5] = NULL;
    g_cb_vtable.header[0] = (void *) (uintptr_t) REFBASE_OFF; /* offset-to-top：RefBase 子对象位置 */
    g_cb_vtable.header[1] = NULL;                             /* typeinfo */
    g_cb_vtable.header[2] = NULL;

    fake_callback_t *obj = (fake_callback_t *) calloc(1, OBJ_SIZE);
    if (obj == NULL) return 0;
    obj->vptr = (void *) &g_cb_vtable.fns[0]; /* 地址点 */
    /* RefBase 子对象：位置必须与 vtable[-3] 一致 */
    ((void (*)(void *)) refbase_ctor)((uint8_t *) obj + REFBASE_OFF);
    /* RefBase 构造函数会写它自己的 vptr 到子对象开头，别覆盖它 */
    g_callback = obj;
    LOGI("runtime sensor callback object built (refbase@+%d)", REFBASE_OFF);
    return 1;
}

/* ------------------------------------------------------------------ */
/* 探测                                                                */
/* ------------------------------------------------------------------ */

static void *dlsym_any(const char *const *names, int n) {
    for (int i = 0; i < n; i++) {
        void *p = dlsym(RTLD_DEFAULT, names[i]);
        if (p != NULL) return p;
        void *h = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
        if (h != NULL) {
            p = dlsym(h, names[i]);
            if (p != NULL) return p;
        }
    }
    return NULL;
}

void prs_probe(uintptr_t relro_addr, uintptr_t relro_size, uintptr_t rt_register_off,
               uintptr_t rt_send_off, uintptr_t rt_unregister_off, uintptr_t rt_isactive_off) {
    if (g_done) return;
    g_done = 1;

    if (!dl_iterate_phdr(phdr_cb, (void *) "libsensorservice.so")) {
        snprintf(g_msg, sizeof(g_msg), "libsensorservice.so not loaded");
        LOGE("%s", g_msg);
        return;
    }
    g_relro_addr = relro_addr;
    g_relro_size = relro_size;

    /* 这四个 API 不在 mini debug info 里，但在 .dynsym 里；由 Java 侧（已有 ELF 解析器）
     * 解析后传进来 —— 比 dlsym 可靠：libsensorservice.so 是 RTLD_LOCAL 加载的，
     * RTLD_DEFAULT 搜不到它的导出符号（实测连续两次 reg=0x0）。 */
    g_api_register = (void *) (g_base + rt_register_off);
    g_api_send = (void *) (g_base + rt_send_off);
    g_api_unregister = (void *) (g_base + rt_unregister_off);
    g_api_issensoractive = (void *) (g_base + rt_isactive_off);
    if (rt_register_off == 0 || rt_send_off == 0) {
        snprintf(g_msg, sizeof(g_msg), "runtime sensor API offsets missing (reg=0x%lx send=0x%lx)",
                 (unsigned long) rt_register_off, (unsigned long) rt_send_off);
        LOGE("%s", g_msg);
        return;
    }

    /* --- 取 SensorService 实例 --- */
    static const char *get_service_names[] = {
        "_Z25AServiceManager_getServicePKc",
        "AServiceManager_getService",
    };
    static const char *to_binder_names[] = {
        "_Z25AIBinder_toPlatformBinderP8AIBinder",
        "AIBinder_toPlatformBinder",
    };
    typedef void *(*get_service_fn)(const char *);
    typedef void *(*to_binder_fn)(void *);
    get_service_fn get_service = (get_service_fn) dlsym_any(get_service_names, 2);
    to_binder_fn to_binder = (to_binder_fn) dlsym_any(to_binder_names, 2);
    if (get_service == NULL || to_binder == NULL) {
        snprintf(g_msg, sizeof(g_msg), "binder_ndk symbols missing (getService=%p toBinder=%p)",
                 (void *) get_service, (void *) to_binder);
        LOGE("%s", g_msg);
        return;
    }
    void *ai = get_service("sensorservice");
    if (ai == NULL) {
        snprintf(g_msg, sizeof(g_msg), "no \"sensorservice\" in ServiceManager");
        LOGE("%s", g_msg);
        return;
    }
    void *ib = to_binder(ai);
    if (ib == NULL) {
        snprintf(g_msg, sizeof(g_msg), "AIBinder_toPlatformBinder returned null");
        LOGE("%s", g_msg);
        return;
    }

    /* --- 校验 1：vptr 必须落在模块的 .data.rel.ro（虚表所在节） --- */
    uintptr_t vptr = *(uintptr_t *) ib;
    if (!ptr_in_relro(vptr)) {
        snprintf(g_msg, sizeof(g_msg), "instance vptr 0x%lx not in libsensorservice relro",
                 (unsigned long) vptr);
        LOGE("%s", g_msg);
        return;
    }

    /* --- 校验 2：导出的无害查询方法行为合理（未知 handle 应返回 0/1） --- */
    if (g_api_issensoractive != NULL) {
        int (*is_active)(void *, int) = (int (*)(void *, int)) g_api_issensoractive;
        int r = is_active(ib, 1);
        if (r != 0 && r != 1) {
            snprintf(g_msg, sizeof(g_msg), "isSensorActive returned %d - instance rejected", r);
            LOGE("%s", g_msg);
            return;
        }
    }

    g_service = ib;
    g_ok = build_callback();
    snprintf(g_msg, sizeof(g_msg), "instance=%p vptr=0x%lx ok callback=%s", ib,
             (unsigned long) vptr, g_callback ? "ready" : "FAILED");
    LOGI("runtime sensor probe: %s", g_msg);
}

int prs_ready(void) { return g_ok; }

void prs_describe(char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return;
    snprintf(out, out_size,
             "rt=%s svc=%p reg=%p send=%p unreg=%p cb=%p | %s", g_ok ? "ok" : "off", g_service,
             g_api_register, g_api_send, g_api_unregister, (void *) g_callback, g_msg);
}
