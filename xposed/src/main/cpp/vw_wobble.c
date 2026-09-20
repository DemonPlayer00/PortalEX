/*
 * 按组波动 —— 两组外周传感器模拟各两条参数（页面可编辑的文本输入）。
 *
 * ## 组
 *   · [VW_WOB_GROUP_CADENCE]     —— 步频侧（TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR）
 *   · [VW_WOB_GROUP_ORIENTATION] —— 角度与指南针侧（加速度/重力/线性加速度/陀螺/磁场/方向角/旋转矢量）
 *
 * ## 两条参数（都是相对量，0..1 的分数；UI 上是百分比，默认 15%）
 *   · amp 「波动强度」：**慢漂**半幅。偏差含 amp × s(t)，s(t) 是一阶低通随机游走 ∈[-1,1]，
 *     时间常数 [VW_WOB_TAU_NS]（1.5s）—— 表现为读数在秒级上缓慢来回走。
 *   · rnd 「随机区间」：**逐条事件**的均匀随机半宽。偏差再含 rnd × U[-1,1]。
 *   本事件总偏差 `dev = amp·s(t) + rnd·U[-1,1]`，范围约 ±(amp+rnd)。
 *
 * ## 施加口径：`dev × 该类型的参考量`，**不是逐值百分比**
 *
 * 逐值百分比（value × (1+dev)）在两类量上会坏掉：
 *   · 基准接近 0 的量（陀螺 x/y 静止时只有零偏、线性加速度静止时为 0）永远不抖；
 *   · 角度量（方向角）会变成"朝向越大抖得越狠"（0° 几乎不抖、350° 抖 50°）—— 与朝向无关的
 *     抖动才是真机行为。
 * 所以统一按**该类型的参考量**加绝对偏差，参考量见 [vw_wobble_ref]：
 *   加速度/重力/线性加速度 = 1g = 9.80665 m/s²；陀螺 = 1 rad/s；磁场 = 50 µT；
 *   方向角 = 180°；旋转矢量 = π（加在**半角**上，四元数因此仍是单位四元数）。
 *   步频侧没有"分量"，作用在**步间隔**上：interval × (1 + dev)（等价于步频波动）。
 *
 * ## 与既有噪声层的关系
 *
 * 这里**不替代**逐轴 σ（vw_noise.c）：σ 是"每条事件的传感器本底噪声"，本模块是叠加在它之上的
 * 慢漂与逐条抖动。两者独立可调，σ 那条在 Calibration 页。
 *
 * ## 并发
 *
 * 与主世界共用同一把锁 [g_lock]：设置走 vw_set_group_wobble（自己取锁），
 * 读取与慢漂推进发生在生成期（fill_values / vw_update_state 都在 g_lock 内）。
 * `vw_wobble_dev` 会**推进状态并消耗随机数** ⇒ 必须在锁内调用，且**参数全 0 时必须在取随机数之前
 * 就返回**（否则 0 值会悄悄挪动全局随机流，让"参数为 0 时输出逐位一致"这个判据失效）。
 */
#include <math.h>
#include <stdio.h>

#include <android/log.h>
#define LOG_TAG "PortalSensor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include "vw_internal.h"

/** 慢漂时间常数（纳秒）：1.5s 量级 —— 秒级游走，肉眼能看出"活"，又不会像逐条随机那样毛刺 */
#define VW_WOB_TAU_NS 1500000000LL

/** 慢漂状态推进的时间上限：长时间没有事件（停摆）后不要一次跳完 */
#define VW_WOB_MAX_DT_NS 3000000000LL

/** 慢漂半幅（相对量）：默认 15%（用户口径） */
static float g_wob_amp[VW_WOB_GROUP_COUNT] = {0.15f, 0.15f};

/** 逐条随机半宽（相对量）：默认 15%（用户口径） */
static float g_wob_rnd[VW_WOB_GROUP_COUNT] = {0.15f, 0.15f};

/** 慢漂当前值 ∈[-1,1]（每组一份） */
static double g_wob_slow[VW_WOB_GROUP_COUNT] = {0.0, 0.0};

/** 慢漂上次推进时刻（0 = 还没推进过） */
static long long g_wob_last_ns[VW_WOB_GROUP_COUNT] = {0, 0};

static int wob_group_ok(int group) {
    return group >= 0 && group < VW_WOB_GROUP_COUNT;
}

static float wob_clamp01(float v) {
    if (!(v > 0.0f)) return 0.0f; /* NaN 与负值都归 0 */
    if (v > 1.0f) return 1.0f;    /* 100%：慢漂半幅不可能超过参考量本身 */
    return v;
}

void vw_set_group_wobble(int group, float amp, float rnd) {
    if (!wob_group_ok(group)) return;
    amp = wob_clamp01(amp);
    rnd = wob_clamp01(rnd);
    pthread_mutex_lock(&g_lock);
    float oa = g_wob_amp[group], orr = g_wob_rnd[group];
    g_wob_amp[group] = amp;
    g_wob_rnd[group] = rnd;
    pthread_mutex_unlock(&g_lock);
    if (oa != amp || orr != rnd) {
        LOGI("wobble[%s] amp %.3f -> %.3f rnd %.3f -> %.3f",
             group == VW_WOB_GROUP_CADENCE ? "cadence" : "orientation", oa, amp, orr, rnd);
    }
}

void vw_get_group_wobble(int group, float *amp, float *rnd) {
    if (!wob_group_ok(group)) return;
    pthread_mutex_lock(&g_lock);
    if (amp) *amp = g_wob_amp[group];
    if (rnd) *rnd = g_wob_rnd[group];
    pthread_mutex_unlock(&g_lock);
}

/**
 * 慢漂推进 + 取当前值（**无锁**，调用方必须已持有 [g_lock]）。
 *
 * 一阶低通：`s += (u - s)·(1 − e^{−dt/τ})`，`u` 每次推进新抽一个 U[-1,1]。
 * `dt == 0`（同一时刻的多次调用，例如同一批里多个通道）只读不推进，也不消耗随机数。
 */
static double wob_slow(int group, long long now) {
    long long last = g_wob_last_ns[group];
    if (last == 0) {
        /* 首次：直接从均匀分布起跳，避免"从 0 慢慢爬"的头几秒死板 */
        g_wob_last_ns[group] = now;
        g_wob_slow[group] = vw_rng_unit() * 2.0 - 1.0;
        return g_wob_slow[group];
    }
    long long dt = now - last;
    if (dt <= 0) return g_wob_slow[group];
    if (dt > VW_WOB_MAX_DT_NS) dt = VW_WOB_MAX_DT_NS;
    g_wob_last_ns[group] = now;
    double alpha = 1.0 - exp(-(double) dt / (double) VW_WOB_TAU_NS);
    if (alpha > 1.0) alpha = 1.0;
    double u = vw_rng_unit() * 2.0 - 1.0;
    double s = g_wob_slow[group] + (u - g_wob_slow[group]) * alpha;
    if (s > 1.0) s = 1.0;
    if (s < -1.0) s = -1.0;
    g_wob_slow[group] = s;
    return s;
}

double vw_wobble_dev(int group, long long now) {
    if (!wob_group_ok(group)) return 0.0;
    float amp = g_wob_amp[group], rnd = g_wob_rnd[group];
    /* 0 值必须在**碰随机数之前**返回：否则 0 值也会挪动全局随机流 */
    if (amp <= 0.0f && rnd <= 0.0f) return 0.0;
    double dev = 0.0;
    if (amp > 0.0f) dev += (double) amp * wob_slow(group, now);
    if (rnd > 0.0f) dev += (double) rnd * (vw_rng_unit() * 2.0 - 1.0);
    return dev;
}

/** 该类型的参考量（见文件头"施加口径"）。返回 0 = 这个类型不吃波动。 */
double vw_wobble_ref(int32_t type) {
    switch (type) {
        case PS_TYPE_ACCELEROMETER:
        case PS_TYPE_ACCELEROMETER_UNCALIBRATED:
        case PS_TYPE_GRAVITY:
        case PS_TYPE_LINEAR_ACCELERATION:
            return 9.80665; /* 1g */
        case PS_TYPE_GYROSCOPE:
        case PS_TYPE_GYROSCOPE_UNCALIBRATED:
            return 1.0; /* rad/s */
        case PS_TYPE_MAGNETIC_FIELD:
        case PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED:
            return 50.0; /* µT */
        case PS_TYPE_ORIENTATION:
            return 180.0; /* 度 */
        case PS_TYPE_ROTATION_VECTOR:
        case PS_TYPE_GAME_ROTATION_VECTOR:
        case PS_TYPE_GEOMAGNETIC_ROTATION_VECTOR:
            return M_PI; /* 弧度（加在半角 θ 上） */
        default:
            return 0.0;
    }
}

/** 该类型的**分量个数**（不含精度字节 / 不含未校准的零偏分量） */
int vw_wobble_dims(int32_t type) {
    switch (type) {
        case PS_TYPE_ORIENTATION:
            return 1;
        case PS_TYPE_ACCELEROMETER:
        case PS_TYPE_ACCELEROMETER_UNCALIBRATED:
        case PS_TYPE_GRAVITY:
        case PS_TYPE_LINEAR_ACCELERATION:
        case PS_TYPE_GYROSCOPE:
        case PS_TYPE_GYROSCOPE_UNCALIBRATED:
        case PS_TYPE_MAGNETIC_FIELD:
        case PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED:
            return 3;
        default:
            return 0; /* 旋转矢量单独处理（加在半角上） */
    }
}

long long vw_wobble_step_interval(long long base, long long now) {
    if (base <= 0) return base;
    double dev = vw_wobble_dev(VW_WOB_GROUP_CADENCE, now);
    if (dev == 0.0) return base; /* 0 值：原样返回，逐位一致 */
    double v = (double) base * (1.0 + dev);
    /* 钳到 ±50%：慢漂 + 逐条随机叠加后仍要给出"走路"的节奏，不能出现 0 间隔或十几秒一步 */
    double lo = (double) base * 0.5, hi = (double) base * 1.5;
    if (v < lo) v = lo;
    if (v > hi) v = hi;
    long long out = (long long) (v + 0.5);
    if (out < 1) out = 1;
    return out;
}

int vw_dump_wobble(char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return 0;
    float ca, cr, oa, orr;
    vw_get_group_wobble(VW_WOB_GROUP_CADENCE, &ca, &cr);
    vw_get_group_wobble(VW_WOB_GROUP_ORIENTATION, &oa, &orr);
    int w = snprintf(out, out_size, "cad=%.0f%%/%.0f%% ori=%.0f%%/%.0f%%",
                     ca * 100.0f, cr * 100.0f, oa * 100.0f, orr * 100.0f);
    return w > 0 ? w : 0;
}
