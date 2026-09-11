/*
 * Binder 外周传感器模拟 —— system_server 侧原生注入层（实验性）。
 *
 * 目标：**只 hook 系统框架，不向目标应用注入任何东西**。
 *
 * 原理
 * ----
 * 应用拿到的传感器事件，最终都来自系统框架的 SensorService：它从 HAL 取事件
 * （AIDL HAL 走 FMQ，见下），再按各客户端的注册情况分发到对应的 BitTube
 * （binder 建立的事件通道）。我们不动 HAL、也不动应用，而是挂在
 * **HAL 包装器的事件出口**上：
 *
 *     SensorService::threadLoop
 *        → mSensorDevice.poll(buf, n)          [libsensorservice.so, 内联]
 *        → mHalWrapper->supportsMessageQueues() [虚调用]
 *        → mHalWrapper->pollFmq(buf, n)         [虚调用] ← 本机实际走这条
 *          （或 mHalWrapper->poll(buf, n)，老式轮询 HAL 走这条）
 *        → 我们的 hook：丢掉真实的"被接管类型"事件，追加自产事件
 *        → 框架照常做路由/过滤/批处理/唤醒锁
 *
 * 于是：
 *   1. 所有客户端（含不在 LSPosed 作用域内的应用、以及走 NDK ASensorEventQueue
 *      的原生消费者）都拿到模拟数据——**目标应用零 hook**；
 *   2. 注入的数据由我们自己生成，HAL 报什么、准不准、有没有在被别人用，都不影响
 *      推送内容——**完全隔离**；
 *   3. 真实传感器若在动（设备实际被拿起/晃动），其数据被丢弃，不会和虚拟世界
 *      互相矛盾。
 *
 * 为什么 poll 和 pollFmq 都要挂：本机 AIDL HAL 的 `poll()` 是个 `return 0` 的空实现，
 * 框架走的是 `pollFmq`（FMQ 阻塞读）。只挂 `poll` 等于挂在没人走的路上——实测如此。
 *
 * 为什么不是 ioctl / 不是直接改 HAL
 * --------------------------------
 * 本机（Android 16 + 高通/OPPO）的传感器 HAL 是**独立 vendor 进程**里的 AIDL 服务
 * （vendor.oplusSensor-aidl-1），ioctl 发生在那个进程里，LSPosed 注入不到；
 * 而框架内的这道事件出口是同一份数据的**上游唯一汇合点**，改这里等价于换掉整个
 * HAL，却不需要内核/驱动层面的改动。因此这里刻意不走 ioctl。
 *
 * 符号从哪来
 * ----------
 * 目标函数是隐藏可见性（dlsym 拿不到），地址由 Java 侧读平台库的 mini debug info
 * 解析后传进来（见 LibSymbols.kt）。**本文件不做任何地址猜测**：
 *   · 每个偏移都必须落在模块的可执行段内，否则拒绝；
 *   · 改写的唯一条件是该位置当前正好存着"那个函数的指针"（vtable 槽核对）。
 * 对不上就什么都不做——解析错、ROM 不同、库被换过，都只是功能不生效，不会写坏
 * 系统进程（这条铁律是踩过一次段错误换来的）。
 */
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include "virtual_world.h"

#define LOG_TAG "PortalSensor"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

typedef long (*ps_poll_fn)(void *self, portal_sensor_event_t *events, size_t count);

#define MAX_SEGS 8
#define MAX_TARGETS 4

/* 潜在入口：poll / pollFmq × AIDL / HIDL（本机 AIDL，但 HIDL 也一起覆盖） */
typedef struct {
    const char *name;
    uintptr_t off;        /* Java 传来的链接期偏移 */
    ps_poll_fn orig;
    ps_poll_fn hook;
} ps_target_t;

static uintptr_t g_base = 0; /* libsensorservice.so 加载基址 */
static char g_path[256];
static int g_patched = 0;
static int g_installed = 0;

/*
 * 投递节拍源：0 = poll 路径（HAL 轮询驱动，现状），1 = 运行时通道（Java 侧泵线程驱动）。
 * 两者**互斥**——同时开会让同一条事件送两份。默认 0（开关关闭时行为逐位不变）。
 */
static int g_rt_clock = 0;

/*
 * 通用出口观测器：记录**我们没有接管**的类型的到达情况（类型 / 条数 / 最近值 / 最近时间）。
 * 目的：厂商私有传感器（如 pedometer_minute 33171034、oplus_activity_recognition 33171037）
 * 也在同一个出口上，应用可能从它们读步频/活动 —— 只监控、不改写，用于判断
 * "公版在走、私版不动"这类不自洽。槽位少、无锁（诊断用途，允许竞态）。
 */
#define OBS_SLOTS 24
typedef struct {
    int32_t type;
    long long count;
    float last;
    long long last_ts;
    long long first_ts;
} obs_slot_t;
static obs_slot_t g_obs[OBS_SLOTS];

static void obs_note(int32_t type, float v0, long long ts) {
    if (type <= 0) return;
    int free_slot = -1;
    for (int i = 0; i < OBS_SLOTS; i++) {
        if (g_obs[i].type == type) {
            g_obs[i].count++;
            g_obs[i].last = v0;
            g_obs[i].last_ts = ts;
            return;
        }
        if (free_slot < 0 && g_obs[i].type == 0) free_slot = i;
    }
    if (free_slot >= 0) {
        g_obs[free_slot].type = type;
        g_obs[free_slot].count = 1;
        g_obs[free_slot].last = v0;
        g_obs[free_slot].first_ts = ts;
        g_obs[free_slot].last_ts = ts;
    }
}

/** 把观测到的**私有类型**（type >= 0x10000）写成 "33171034:120/min:v=37 ..." */
static void obs_dump(char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return;
    size_t used = 0;
    out[0] = '\0';
    for (int i = 0; i < OBS_SLOTS; i++) {
        if (g_obs[i].type < 0x10000 || g_obs[i].count == 0) continue;
        long long span = g_obs[i].last_ts - g_obs[i].first_ts;
        long long per_min = 0;
        if (span > 0) per_min = (long long) ((double) g_obs[i].count * 6e10 / (double) span);
        int w = snprintf(out + used, out_size - used, "%s%d:%lld/min:v=%.3f",
                         used ? " " : "", g_obs[i].type, per_min, (double) g_obs[i].last);
        if (w > 0) used += (size_t) w;
        if (used + 48 >= out_size) break;
    }
    if (used == 0) snprintf(out, out_size, "none");
}
static ps_target_t g_targets[MAX_TARGETS];
static int g_target_count = 0;
static int g_seen_handle[256]; /* 观测到的 type→handle（无锁快查，只做首见登记） */

static struct {
    uintptr_t start, end;
} g_segs[MAX_SEGS];
static int g_seg_count = 0;
static uintptr_t g_exec_start[MAX_SEGS], g_exec_end[MAX_SEGS];
static int g_exec_count = 0;

/* ------------------------------------------------------------------ */
/* 模块定位与地址合法性                                                */
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
    g_seg_count = 0;
    g_exec_count = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        uintptr_t s = g_base + ph->p_vaddr;
        uintptr_t e = s + ph->p_memsz;
        if (g_seg_count < MAX_SEGS) {
            g_segs[g_seg_count].start = s;
            g_segs[g_seg_count].end = e;
            g_seg_count++;
        }
        if ((ph->p_flags & PF_X) && g_exec_count < MAX_SEGS) {
            g_exec_start[g_exec_count] = s;
            g_exec_end[g_exec_count] = e;
            g_exec_count++;
        }
    }
    return 1; /* 停止遍历 */
}

static int addr_is_executable(uintptr_t addr) {
    for (int i = 0; i < g_exec_count; i++) {
        if (addr >= g_exec_start[i] && addr < g_exec_end[i]) return 1;
    }
    return 0;
}

/** [from, to) 是否完整落在一个已映射的 PT_LOAD 段内——扫描/改写前必须先过这一关，
 *  否则一个算错的地址就是 system_server 的段错误（已踩过一次）。 */
static int range_is_mapped(uintptr_t from, uintptr_t to) {
    for (int i = 0; i < g_seg_count; i++) {
        if (from >= g_segs[i].start && to <= g_segs[i].end) return 1;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* 指针改写（vtable 槽 = 数据改写，不改代码段）                          */
/* ------------------------------------------------------------------ */

static int patch_slot(uintptr_t where, uintptr_t expected, uintptr_t replacement) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t start = where & ~(uintptr_t) (page - 1);
    uintptr_t end = (where + sizeof(void *) + page - 1) & ~(uintptr_t) (page - 1);
    if (mprotect((void *) start, end - start, PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect(RW) failed at 0x%lx", (unsigned long) start);
        return -1;
    }
    int ok = 0;
    uintptr_t *slot = (uintptr_t *) where;
    if (*slot == expected) {
        *slot = replacement;
        ok = 1;
    }
    mprotect((void *) start, end - start, PROT_READ);
    __builtin___clear_cache((char *) start, (char *) end);
    return ok ? 0 : -1;
}

/*
 * 在 .data.rel.ro 里找"正好指向 [target] 的函数指针"并换成 [replacement]。
 * 虚函数表就在这个节里：按值核对比按结构体偏移猜测稳，也不依赖 vtable 符号
 * （平台的 vtable 符号不在 mini debug info 里）。找不到就一个都不改。
 */
static int hook_vtable_slot(uintptr_t relro_addr, uintptr_t relro_size, uintptr_t target,
                            uintptr_t replacement, const char *name) {
    if (g_base == 0 || relro_size == 0 || target == 0) return 0;
    /* relro_addr 是**链接期**地址（.data.rel.ro 的 sh_addr），必须加上模块基址 */
    uintptr_t from = g_base + relro_addr;
    uintptr_t to = from + relro_size;
    if (!range_is_mapped(from, to)) {
        LOGE("refusing to scan 0x%lx..0x%lx: not inside any mapped PT_LOAD of %s",
             (unsigned long) from, (unsigned long) to, g_path);
        return 0;
    }
    int hits = 0;
    for (uintptr_t p = from; p + sizeof(void *) <= to; p += sizeof(void *)) {
        if (*(uintptr_t *) p != target) continue;
        if (patch_slot(p, target, replacement) == 0) {
            hits++;
            LOGI("hook %s: vtable slot @ libsensorservice.so+0x%lx -> %p", name,
                 (unsigned long) (p - g_base), (void *) replacement);
        }
    }
    if (hits == 0) {
        LOGW("%s: no vtable slot holds 0x%lx (target moved?)", name,
             (unsigned long) (target - g_base));
    }
    return hits;
}

/* ------------------------------------------------------------------ */
/* 事件出口：压制真实事件 + 追加自产事件                                */
/* ------------------------------------------------------------------ */

/*
 * type → handle 映射**只从真实事件里学**（首个事件到达即登记，随后该类型完全由本模块
 * 接管）。刻意不用 HAL 的 getSensorsList()：它返回的是平台内部 `std::vector<Sensor>`
 * （元素大小随版本变化，实测本机是 96+8=104 字节，而 sensor_t 是 96），按 sensor_t
 * 结构去遍历会错位，读出垃圾 handle 并**覆盖掉正确的映射**——实测踩到过
 * （garbage 0xb4000076 覆盖 0xb）。猜错的后果是把 A 传感器的数据写进 B 传感器，
 * 比不注入更糟，所以宁可不猜：HAL 完全静默的传感器不会被凭空注入。
 */

static long post_process(portal_sensor_event_t *buf, long n, size_t cap) {
    if (!vw_is_active()) return n;

    long kept = 0;
    long long suppressed = 0;
    for (long i = 0; i < n; i++) {
        portal_sensor_event_t *e = &buf[i];
        int type = e->type;
        if (type > 0 && type < 256 && g_seen_handle[type] == 0 && e->sensor != 0) {
            /* 首见登记（无锁快查；同一类型只进来一次） */
            g_seen_handle[type] = e->sensor;
            vw_set_handle(type, e->sensor, e->flags);
        }
        /* 真实事件先喂给虚拟世界看一眼（取真实计数器值做接管基线） */
        vw_note_real_event(type, e->data.f[0]);
        /* 未接管的类型只做观测（厂商私有传感器在同一个出口上） */
        if (!vw_owns_type(type)) obs_note(type, e->data.f[0], e->timestamp);
        if (vw_owns_type(type)) {
            suppressed++;
            continue;
        }
        if (kept != i) buf[kept] = *e;
        kept++;
    }
    if (suppressed > 0) vw_note_suppressed(suppressed);

    /*
     * 投递互斥：运行时通道接管投递时，这里的角色只剩"压制真实事件"——
     * 绝不能再注入一份（同一条事件送两份 = 应用侧翻倍/抖动）。
     * 生成与栅格推进改由 Java 侧泵线程按时钟调用 vw_generate（见 runtimeFrame）。
     */
    if (g_rt_clock) return kept;

    struct timespec ts;
    clock_gettime(CLOCK_BOOTTIME, &ts); /* 传感器事件时间基（= elapsedRealtimeNanos） */
    long long now = (long long) ts.tv_sec * 1000000000LL + ts.tv_nsec;

    long room = (long) cap - kept;
    if (room > 0) {
        kept += vw_generate(buf + kept, (int) room, now);
    }
    return kept;
}

static long run_target(int idx, void *self, portal_sensor_event_t *events, size_t count) {
    ps_target_t *t = &g_targets[idx];
    long n = t->orig(self, events, count);
    return post_process(events, n, count);
}

static long hook_target0(void *s, portal_sensor_event_t *e, size_t c) { return run_target(0, s, e, c); }
static long hook_target1(void *s, portal_sensor_event_t *e, size_t c) { return run_target(1, s, e, c); }
static long hook_target2(void *s, portal_sensor_event_t *e, size_t c) { return run_target(2, s, e, c); }
static long hook_target3(void *s, portal_sensor_event_t *e, size_t c) { return run_target(3, s, e, c); }
static ps_poll_fn HOOKS[MAX_TARGETS] = {&hook_target0, &hook_target1, &hook_target2, &hook_target3};

/* ------------------------------------------------------------------ */
/* 运行时投递通道的取值个数（必须与框架 JNI 的 switch 一致）             */
/* ------------------------------------------------------------------ */

static int value_count_for_type(int type) {
    switch (type) {
        case PS_TYPE_ACCELEROMETER:
        case PS_TYPE_MAGNETIC_FIELD:
        case PS_TYPE_ORIENTATION:
        case PS_TYPE_GYROSCOPE:
        case PS_TYPE_GRAVITY:
        case PS_TYPE_LINEAR_ACCELERATION:
            return 3;   /* 框架 JNI 明确要求恰好 3 个，否则丢弃 */
        case PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED:
        case PS_TYPE_GYROSCOPE_UNCALIBRATED:
        case PS_TYPE_ACCELEROMETER_UNCALIBRATED:
            return 6;
        case PS_TYPE_ROTATION_VECTOR:
        case PS_TYPE_GAME_ROTATION_VECTOR:
        case PS_TYPE_GEOMAGNETIC_ROTATION_VECTOR:
            return 4;
        case PS_TYPE_STEP_COUNTER:
        case PS_TYPE_STEP_DETECTOR:
            return 1;
        default:
            return 3;
    }
}

/* ------------------------------------------------------------------ */
/* 安装                                                                */
/* ------------------------------------------------------------------ */

/** offsets = [relroAddr, relroSize, pollAidl, fmqAidl, pollHidl, fmqHidl] */
static int do_install(const jlong *o) {
    if (g_installed) return 1;
    if (g_base == 0) {
        if (!dl_iterate_phdr(phdr_cb, (void *) "libsensorservice.so")) {
            LOGE("libsensorservice.so not loaded in this process");
            return 0;
        }
    }
    LOGI("libsensorservice.so base=0x%lx path=%s", (unsigned long) g_base, g_path);

    uintptr_t relro_addr = (uintptr_t) o[0];
    uintptr_t relro_size = (uintptr_t) o[1];
    if (relro_addr == 0 || relro_size == 0) {
        LOGE("no .data.rel.ro range given - feature stays inert");
        return 0;
    }

    struct {
        const char *name;
        jlong off;
    } spec[MAX_TARGETS] = {
        {"poll(AIDL)", o[2]},
        {"pollFmq(AIDL)", o[3]},
        {"poll(HIDL)", o[4]},
        {"pollFmq(HIDL)", o[5]},
    };

    g_target_count = 0;
    for (int i = 0; i < MAX_TARGETS; i++) {
        if (spec[i].off == 0) continue;
        uintptr_t addr = g_base + (uintptr_t) spec[i].off;
        if (!addr_is_executable(addr)) {
            LOGE("%s offset 0x%lx not executable - rejected", spec[i].name,
                 (unsigned long) spec[i].off);
            continue;
        }
        ps_target_t *t = &g_targets[g_target_count];
        t->name = spec[i].name;
        t->off = (uintptr_t) spec[i].off;
        t->orig = (ps_poll_fn) addr;
        t->hook = HOOKS[g_target_count];
        g_target_count++;
    }

    for (int i = 0; i < g_target_count; i++) {
        g_patched += hook_vtable_slot(relro_addr, relro_size, (uintptr_t) g_targets[i].orig,
                                      (uintptr_t) g_targets[i].hook, g_targets[i].name);
    }
    if (g_patched == 0) {
        LOGE("no vtable slot patched - feature stays inert");
        return 0;
    }
    g_installed = 1;
    LOGI("installed: %d vtable slot(s) patched", g_patched);
    /*
     * 说明：这里**只**做 HAL 事件出口（poll/pollFmq）的接管。
     * "投递 100% 可控"的那条路（框架的运行时传感器）改由 **Java 层**实现——
     * 框架本身就把 `registerRuntimeSensorNative` / `sendRuntimeSensorEventNative`
     * 暴露在 system_server 的 Java 侧，走那条路不需要任何原生代码，也就没有
     * 自建对象/虚表/ABI 的风险（上一版原生探针崩过两次 system_server，
     * 原因与教训见 docs/binder-sensor-mock.md「架构翻新」）。
     */
    return 1;
}

/* ------------------------------------------------------------------ */
/* JNI                                                                 */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    vw_init();
    LOGI("native layer loaded (pid=%d)", getpid());
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_install(JNIEnv *env, jobject thiz,
                                                                jlongArray offsets) {
    (void) thiz;
    if (offsets == NULL) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, offsets);
    if (len < MAX_TARGETS + 2) {
        LOGE("install: offsets array too short (%d)", (int) len);
        return JNI_FALSE;
    }
    jlong vals[MAX_TARGETS + 2];
    (*env)->GetLongArrayRegion(env, offsets, 0, MAX_TARGETS + 2, vals);
    return do_install(vals) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_setActive(JNIEnv *env, jobject thiz,
                                                                  jboolean active) {
    (void) env;
    (void) thiz;
    vw_set_active(active ? 1 : 0);
}

/**
 * 用框架自己的传感器表播种 type → handle（三元组 [type, handle, flags, ...]）。
 * 不依赖真实事件，所以"不走路就没有事件的"步数传感器也能拿到 handle。
 */
JNIEXPORT void JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_setHandleMap(JNIEnv *env, jobject thiz,
                                                                     jlongArray triples) {
    (void) thiz;
    if (triples == NULL) return;
    jsize len = (*env)->GetArrayLength(env, triples);
    if (len < 3) return;
    jlong *vals = (*env)->GetLongArrayElements(env, triples, NULL);
    if (vals == NULL) return;
    int mapped = 0;
    for (jsize i = 0; i + 2 < len; i += 3) {
        vw_seed_handle((int32_t) vals[i], (int32_t) vals[i + 1], (uint32_t) vals[i + 2]);
        mapped++;
    }
    (*env)->ReleaseLongArrayElements(env, triples, vals, JNI_ABORT);
    LOGI("seeded %d sensor handle(s) from framework list", mapped);
}

/**
 * 投递节拍源开关（与 Java 侧的运行时通道泵线程一一对应）。
 * 打开后 poll 出口只压制真实事件、不再注入；生成改由 [runtimeFrame] 驱动。
 */
JNIEXPORT void JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_setRuntimeClock(JNIEnv *env, jobject thiz,
                                                                        jboolean active) {
    (void) env;
    (void) thiz;
    int v = active ? 1 : 0;
    if (v == g_rt_clock) return;
    g_rt_clock = v;
    LOGI("delivery clock -> %s (poll path %s)", v ? "runtime" : "poll",
         v ? "suppress-only" : "inject+suppress");
}

/**
 * 取一帧"截至 now_nanos 应发出的事件"（运行时通道专用）。
 *
 * 每事件写 4 个 long（handle / type / timestamp / values 个数），值写进 [values]，
 * 每事件占 16 个 float 槽（与 `sensors_event_t.data` 同宽）。**值个数必须与框架 JNI
 * 的 switch 一致**：加速度/磁场/方向/陀螺仪/重力/线性加速度 恰好 3 个；
 * 未校准三兄弟 6 个；旋转矢量三家 4 个；步数两兄弟 1 个。
 * @return 写入的事件条数
 */
JNIEXPORT jint JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_runtimeFrame(JNIEnv *env, jobject thiz,
                                                                    jlong now_nanos,
                                                                    jlongArray meta,
                                                                    jfloatArray values) {
    (void) thiz;
    if (!g_rt_clock || !vw_is_active() || meta == NULL || values == NULL) return 0;
    enum { MAX_FRAME = 32, META_STRIDE = 4, VALUE_STRIDE = 16 };
    portal_sensor_event_t buf[MAX_FRAME];
    int n = vw_generate(buf, MAX_FRAME, (long long) now_nanos);
    if (n <= 0) return 0;

    jsize mlen = (*env)->GetArrayLength(env, meta);
    jsize vlen = (*env)->GetArrayLength(env, values);
    int cap = (int) (mlen / META_STRIDE);
    if (cap > n) cap = (int) n;
    if (cap > (int) (vlen / VALUE_STRIDE)) cap = (int) (vlen / VALUE_STRIDE);
    if (cap <= 0) return 0;

    jlong *m = (*env)->GetLongArrayElements(env, meta, NULL);
    jfloat *v = (*env)->GetFloatArrayElements(env, values, NULL);
    if (m == NULL || v == NULL) {
        if (m != NULL) (*env)->ReleaseLongArrayElements(env, meta, m, JNI_ABORT);
        if (v != NULL) (*env)->ReleaseFloatArrayElements(env, values, v, JNI_ABORT);
        return 0;
    }
    for (int i = 0; i < cap; i++) {
        m[i * META_STRIDE + 0] = buf[i].sensor;
        m[i * META_STRIDE + 1] = buf[i].type;
        m[i * META_STRIDE + 2] = buf[i].timestamp;
        m[i * META_STRIDE + 3] = value_count_for_type(buf[i].type);
        for (int k = 0; k < VALUE_STRIDE; k++) v[i * VALUE_STRIDE + k] = buf[i].data.f[k];
    }
    (*env)->ReleaseLongArrayElements(env, meta, m, 0);
    (*env)->ReleaseFloatArrayElements(env, values, v, 0);
    return cap;
}

/**
 * 客户端视角的"系统开机总步数"：最近一次发出的 STEP_COUNTER 值。
 * 应用若用"间歇读总步数求差"的方式算步频，读到的就是它。
 */
/**
 * 真实（HAL）STEP_COUNTER 的最近值 —— 模拟接管时的起点，用于保持"开机以来累计"连续。
 * @return ≥0 = 已知；-1 = 还没见过真实计数器（无步数传感器 / 会话期间没收到过）
 */
JNIEXPORT jlong JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_realStepCounter(JNIEnv *env,
                                                                        jobject thiz) {
    (void) env;
    (void) thiz;
    return (jlong) vw_real_step_counter();
}

JNIEXPORT jlong JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_stepCounterValue(JNIEnv *env,
                                                                        jobject thiz) {
    (void) env;
    (void) thiz;
    return (jlong) vw_step_counter_value();
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_updateState(    JNIEnv *env, jobject thiz, jdouble speed, jdouble azimuth, jboolean moving, jlong steps,
    jlong now_nanos) {
    (void) env;
    (void) thiz;
    vw_update_state(speed, azimuth, moving ? 1 : 0, (long long) steps, (long long) now_nanos);
}

JNIEXPORT jstring JNICALL
Java_moe_fuqiuluo_xposed_hooks_sensor_BinderSensorNative_status(JNIEnv *env, jobject thiz) {
    (void) thiz;
    long long emitted = 0, dropped = 0, suppressed = 0;
    vw_stats(&emitted, &dropped, &suppressed);
    char buf[768];
    size_t used = 0;
#define APPEND(...)                                                                     \
    do {                                                                                \
        if (used < sizeof(buf)) {                                                       \
            int w = snprintf(buf + used, sizeof(buf) - used, __VA_ARGS__);               \
            if (w > 0) used += (size_t) w;                                               \
        }                                                                                \
    } while (0)
    APPEND("installed=%d patched=%d targets=%d active=%d base=0x%lx", g_installed, g_patched,
           g_target_count, vw_is_active(), (unsigned long) g_base);
    for (int i = 0; i < g_target_count; i++) {
        APPEND(" [%s=0x%lx]", g_targets[i].name, (unsigned long) g_targets[i].off);
    }
    struct timespec now_ts;
    clock_gettime(CLOCK_BOOTTIME, &now_ts);
    long long now_ns = (long long) now_ts.tv_sec * 1000000000LL + now_ts.tv_nsec;
    APPEND(" emitted=%lld dropped=%lld suppressed=%lld", emitted, dropped, suppressed);
    APPEND(" steps=%lld step_rate=%d/min", vw_step_events_total(),
           vw_step_rate_per_min(now_ns));
    APPEND(" steps_boot=%lld", vw_step_counter_value());
    APPEND(" steps_base=%lld", vw_real_step_counter());
    char priv[320];
    obs_dump(priv, sizeof(priv));
    APPEND(" priv=[%s]", priv);
    APPEND(" gait=%s", vw_gait_describe());
    char handles[256];
    vw_dump_handles(handles, sizeof(handles));
    APPEND(" handles=[%s]", handles);
#undef APPEND
    return (*env)->NewStringUTF(env, buf);
}
