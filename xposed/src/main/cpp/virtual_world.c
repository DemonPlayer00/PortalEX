#include "virtual_world.h"

#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "PortalSensor"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* ---- 时间网格 ---- */
/* 10ms 基准栅格：所有周期都是它的整数倍，事件天然按时间升序交织，
 * 应用侧按相邻事件时间戳算 dt 不会出现负值（真机 HAL 也是按时序交织的）。*/
#define TICK_NS 10000000LL
#define MAX_TICKS_PER_CALL 200 /* 单次最多追 2s，再长就丢旧数据 */
#define STEP_QUEUE_CAP 64
#define MAX_CHANNELS 14

typedef struct {
    int32_t type;
    int period_ticks;
    int32_t handle;
    uint32_t flags;
    int known;
    /*
     * 「按应用期望出数据」——由框架侧观测驱动（见 portal_sensor.c 的 setChannelHint）：
     * 真机 HAL 是按**所有请求里最快那个**（框架 dump 的 selected）出数据的，
     * 然后框架把每条事件原样广播给所有订阅者。这里就照这个模型走：
     *   · period_ns   = 框架采用值（0 = 未指定/最快档，用默认栅格）；
     *   · active_hint = 框架 dump 里该 handle 是否有活跃订阅者；
     *   · last_real_ns= 最近一次看到该类型的**真实**事件（= HAL 刚被启用，立刻恢复出力，
     *                   避免"应用刚订阅却要等下一次 dump"的空窗）；
     *   · hinted      = 还没收到过提示时保持原行为（默认栅格、恒出力）。
     */
    long long period_ns;
    long long next_due_ns;
    long long last_real_ns;
    int active_hint;
    int hinted;
} vw_channel_t;

static vw_channel_t g_chan[MAX_CHANNELS] = {
    {PS_TYPE_ACCELEROMETER, 2, -1, 0, 0},
    {PS_TYPE_ACCELEROMETER_UNCALIBRATED, 2, -1, 0, 0},
    {PS_TYPE_LINEAR_ACCELERATION, 2, -1, 0, 0},
    {PS_TYPE_GYROSCOPE, 2, -1, 0, 0},
    {PS_TYPE_GYROSCOPE_UNCALIBRATED, 2, -1, 0, 0},
    {PS_TYPE_ORIENTATION, 2, -1, 0, 0},
    {PS_TYPE_ROTATION_VECTOR, 2, -1, 0, 0},
    {PS_TYPE_GAME_ROTATION_VECTOR, 2, -1, 0, 0},
    {PS_TYPE_GRAVITY, 4, -1, 0, 0},
    {PS_TYPE_MAGNETIC_FIELD, 4, -1, 0, 0},
    {PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED, 4, -1, 0, 0},
    {PS_TYPE_GEOMAGNETIC_ROTATION_VECTOR, 4, -1, 0, 0},
    /* 步数两兄弟不在栅格上（on-change，由步事件队列驱动），period 0 = 不参与栅格 */
    {PS_TYPE_STEP_COUNTER, 0, -1, 0, 0},
    {PS_TYPE_STEP_DETECTOR, 0, -1, 0, 0},
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- Java 推来的状态快照 ---- */
static int g_active = 0;
static double g_speed = 3.05;static double g_target_azimuth = 0.0;
static int g_moving = 0;
static long long g_steps = 0;
static long long g_state_nanos = 0;
static int g_have_state = 0;

/* ---- 生成器状态 ---- */
static long long g_last_tick = 0;      /* 已生成到的栅格点 */
static double g_azimuth = 0.0;         /* 平滑后的注入方位（度） */
static int g_azimuth_init = 0;
static double g_gyro_z = 0.0;          /* 低通后的角速度（rad/s） */
static double g_last_az_for_gyro = 0.0;
static int g_gyro_init = 0;

/* 步态相位（走路时身体竖直方向做周期性加减速：每步两拍——脚跟着地 + 蹬地）。
 * 频率与步频严格同源（cadenceForSpeed），因此 IMU 的周期峰与步事件是对齐的。 */
static double g_gait_phase = 0.0;
/* 一个波形周期 = 多少步（校准量）。
 * 原始波形是"一个周期 = 一步"。用户按"波长 ×3"迭代了两次：3 → **9**（本轮默认 9 步/周期）。
 * 这是**编译期常量**：不再有任何运行时校准入口（曾有一个读 /data/local/tmp 的临时口子，
 * 已按要求连同那个文件一起移除）。 */
static int g_gait_steps = 9;
static int g_gait_step_index = 0;         /* 周期内的第几步，用于轮转 PLL 目标相位 */
static long long g_gait_anchor_ns = 0;   /* 最近一步的时间戳 */
static long long g_gait_interval_ns = 0; /* 平滑后的步间隔（相位推进的节拍来源） */

static double g_sway_from = 0.0, g_sway_to = 0.0, g_sway_side = 1.0;
static long long g_sway_start = 0, g_sway_half = 500000000LL;
static double g_micro_target = 0.0, g_micro_offset = 0.0;

static long long g_emitted = 0, g_dropped = 0, g_suppressed = 0;

/* 实际发出的步事件滚动窗口（1s 桶 × 8）：诊断页用它给出"有效步频"，
 * 与设定速度算出的"意图步频"对照——两者对不上就是生成/投递环节的问题。 */
#define STEP_BUCKETS 8
static unsigned g_step_bucket[STEP_BUCKETS];
static long long g_step_bucket_sec = -1;
static long long g_step_emit_total = 0;

static void step_bucket_tick(long long ts_nanos) {
    long long sec = ts_nanos / 1000000000LL;
    if (sec == g_step_bucket_sec) return;
    if (g_step_bucket_sec < 0) {
        memset(g_step_bucket, 0, sizeof(g_step_bucket));
    } else {
        for (long long s = g_step_bucket_sec + 1; s <= sec; s++) {
            g_step_bucket[s % STEP_BUCKETS] = 0;
        }
    }
    g_step_bucket_sec = sec;
}

static long long g_last_counter_value = 0; /* 最近一次发出的 STEP_COUNTER 值（诊断用） */
/* 最近一次**观测到的真实** STEP_COUNTER 值（-1 = 还没见过）。
 * 用途：模拟接管时把自家计数器**接在真实计数器后面**（真机是"开机以来累计"），
 * 否则会出现"0 → 随机 3000~12000"的跳变 —— 按 Δ步数算步频的应用会被这一步跳变
 * 长期拉高（7000 步摊到十几分钟就是几百步/分）。 */
static long long g_real_counter = -1;

static void step_emitted(long long ts_nanos) {
    step_bucket_tick(ts_nanos);
    if (g_step_bucket_sec >= 0) g_step_bucket[g_step_bucket_sec % STEP_BUCKETS]++;
    g_step_emit_total++;
}

/** 最近一次发给客户端的 TYPE_STEP_COUNTER 值（= "系统从开机到现在的总步数" 在客户端的样子） */
long long vw_step_counter_value(void) { return g_last_counter_value; }

/** 最近观测到的真实 STEP_COUNTER 值（-1 = 未知）。模拟接管时用它做起点，保证连续。 */
long long vw_real_step_counter(void) { return g_real_counter; }

/** 记一条真实事件（取真实计数器值做基线；同时标记"该类型正在被真实 HAL 出力"） */
void vw_note_real_event(int32_t type, const float *data) {
    if (type == PS_TYPE_STEP_COUNTER) {
        long long v;
        memcpy(&v, data, sizeof(v));
        if (v >= 0 && v != g_real_counter) g_real_counter = v;
    }
    /* 「按应用期望出数据」的即时恢复信号：见到真实事件 ⇒ 该类型刚被启用 */
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (g_chan[i].type == type) {
            g_chan[i].last_real_ns = g_state_nanos > 0 ? g_state_nanos : g_chan[i].last_real_ns;
            if (g_chan[i].last_real_ns == 0) {
                struct timespec ts;
                clock_gettime(CLOCK_BOOTTIME, &ts);
                g_chan[i].last_real_ns = (long long) ts.tv_sec * 1000000000LL + ts.tv_nsec;
            }
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
}

/** 近 5 秒实际发出的步事件 → 步/分 */
int vw_step_rate_per_min(long long now_nanos) {
    step_bucket_tick(now_nanos);
    unsigned sum = 0;
    for (int i = 0; i < 5; i++) {
        long long s = g_step_bucket_sec - i;
        if (s < 0) break;
        sum += g_step_bucket[s % STEP_BUCKETS];
    }
    return (int) (sum * 12); /* 5 秒的计数 × 12 = 每分钟 */
}

/* 步事件队列：每一步一个时间戳 + 该步之后的累计值 */
typedef struct {
    long long ts;
    long long count;
    int used;
} vw_step_t;
static vw_step_t g_steps_q[STEP_QUEUE_CAP];
static long long g_last_steps_seen = 0;
static long long g_last_step_push_nanos = 0;

/* 虚拟世界常量（进程内恒定，与 app 端 hook 同一口径） */
static double g_mag_h = 35.0;
static double g_mag_dip = 1.0;
static double g_mag_bias_x = 0.0, g_mag_bias_y = 0.0;
static double g_gyro_drift_x = 0.0, g_gyro_drift_y = 0.0, g_gyro_drift_z = 0.0;

/* ---- 步态（IMU 里必须有走路的周期性信号，否则一切"从加速度/陀螺推算步频"的应用
 *      只能看到一条直线，估计器会漂到一个荒谬值并锁死）----
 * 量级取真机走路的典型值：竖直 ±(0.35×v) m/s²（每步两拍），前后 30%、左右 18%；
 * 3 m/s 时竖直约 ±1.05 m/s²，跑步更快时按速度增大到 2.2 封顶。
 * 静止时三项全为 0（真机静置也只有噪声底）。 */
static const double GAIT_Z_PER_SPEED = 0.35;
static const double GAIT_Z_BASE = 0.55; /* 低速也留得住可检测幅度（1 m/s → 0.9 m/s²） */
static const double GAIT_AMP_MAX = 3.0;
static const double GAIT_AY_RATIO = 0.25;
static const double GAIT_AX_RATIO = 0.12;

/* 与 SystemSensorManagerHook 一致的常量 */
static const double BEARING_APPROACH_ALPHA = 0.18; /* 每 50ms 靠近 18% → τ≈0.25s */
static const double GYRO_SMOOTH_ALPHA = 0.25;
static const double SWAY_MAX_DEG = 3.0;
static const double SWAY_MIN_DEG = 0.5;
static const long long SWAY_HALF_MIN_NS = 350000000LL;
static const long long SWAY_HALF_MAX_NS = 750000000LL;
static const double MICRO_JITTER_AMP = 0.6;
static const double MICRO_JITTER_TAU = 0.12;

/* ---- 轻量 PRNG（不碰 libc rand 的全局状态） ---- */
static uint64_t g_rng = 0x9E3779B97F4A7C15ULL;

static uint64_t rng_next(void) {
    uint64_t x = g_rng;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    g_rng = x;
    return x;
}

static double rng_unit(void) { return (double) (rng_next() >> 11) * (1.0 / 9007199254740992.0); }

static double rng_range(double lo, double hi) { return lo + (hi - lo) * rng_unit(); }

static float rng_noise(float amp) { return (float) ((rng_unit() * 2.0 - 1.0) * amp); }

int vw_owns_type(int32_t type) {
    switch (type) {
        case PS_TYPE_ACCELEROMETER:
        case PS_TYPE_ACCELEROMETER_UNCALIBRATED:
        case PS_TYPE_LINEAR_ACCELERATION:
        case PS_TYPE_GYROSCOPE:
        case PS_TYPE_GYROSCOPE_UNCALIBRATED:
        case PS_TYPE_ORIENTATION:
        case PS_TYPE_ROTATION_VECTOR:
        case PS_TYPE_GAME_ROTATION_VECTOR:
        case PS_TYPE_GEOMAGNETIC_ROTATION_VECTOR:
        case PS_TYPE_GRAVITY:
        case PS_TYPE_MAGNETIC_FIELD:
        case PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED:
        case PS_TYPE_STEP_COUNTER:
        case PS_TYPE_STEP_DETECTOR:
            return 1;
        default:
            return 0;
    }
}

void vw_init(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    g_rng ^= (uint64_t) ts.tv_nsec * 0x2545F4914F6CDD1DULL ^ (uint64_t) getpid();
    rng_next();
    g_mag_h = rng_range(28.0, 42.0);
    g_mag_dip = rng_range(0.8, 1.2);
    g_mag_bias_x = rng_range(-3.0, 3.0);
    g_mag_bias_y = rng_range(-3.0, 3.0);
    g_gyro_drift_x = rng_range(-0.008, 0.008);
    g_gyro_drift_y = rng_range(-0.008, 0.008);
    g_gyro_drift_z = rng_range(-0.008, 0.008);
    g_azimuth = rng_range(0.0, 360.0);
    g_target_azimuth = g_azimuth;
    g_azimuth_init = 0;
    LOGI("virtual world seeded: H=%.1fuT dip=%.2f", g_mag_h, g_mag_dip);
}

/* 真实事件后多久内仍认为"HAL 正在为某人出力"（应用刚订阅时的即时恢复窗口） */
#define REAL_FRESH_NS 3000000000LL /* 3s */

/**
 * 「按应用期望出数据」：把框架侧观测到的采用速率与活跃状态灌进通道。
 *
 * @param period_ns 框架 dump 的 `selected`（毫秒转纳秒）；0 = 未指定/最快档 ⇒ 用默认栅格
 * @param active    框架 dump 里该 handle 是否有活跃订阅者（active-count ≥ 1）
 *
 * 只对**栅格通道**生效：步数两条流是 on-change（由步事件队列驱动），不受速率影响。
 */
void vw_set_channel_hint(int32_t type, long long period_ns, int active) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (g_chan[i].type != type) continue;
        if (g_chan[i].period_ticks > 0) {
            int changed = !g_chan[i].hinted || g_chan[i].period_ns != period_ns ||
                          g_chan[i].active_hint != (active ? 1 : 0);
            g_chan[i].hinted = 1;
            g_chan[i].period_ns = period_ns > 0 ? period_ns : 0;
            g_chan[i].active_hint = active ? 1 : 0;
            if (changed) {
                g_chan[i].next_due_ns = 0; /* 速率/活跃变化：重新对齐，不补旧账 */
                LOGI("rate hint: type=%d period=%.1fms active=%d", type, g_chan[i].period_ns / 1e6,
                     g_chan[i].active_hint);
            }
        }
        break;
    }
    pthread_mutex_unlock(&g_lock);
}

/**
 * 把所有栅格通道先标成"不活跃"（随后由 [vw_set_channel_hint] 按框架 dump 覆盖）。
 *
 * 用途：一次 dump 只列出**有订阅者**的传感器，所以"没被列到"就等于没人订 ⇒
 * 先清空再灌，缺席的类型自然静默，调用方不需要在 Kotlin 侧复制一份类型清单。
 */
void vw_clear_channel_hints(void) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (g_chan[i].period_ticks <= 0) continue; /* 步数不在栅格上 */
        g_chan[i].hinted = 1;
        g_chan[i].active_hint = 0;
    }
    pthread_mutex_unlock(&g_lock);
}

/** 该栅格通道此刻是否应该出数据（没人订就静默——真机 HAL 也是这样） */
static int channel_live(const vw_channel_t *ch, long long now_ns) {
    if (!ch->hinted) return 1;                 /* 还没收到提示：保持原行为 */
    if (ch->active_hint) return 1;             /* 框架说有人订 */
    return (now_ns - ch->last_real_ns) < REAL_FRESH_NS; /* 刚看到真实事件 ⇒ 立刻恢复 */
}

/** 各栅格通道的生效速率（诊断：Test 页/状态字符串） */
int vw_dump_rates(char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return 0;
    size_t used = 0;
    out[0] = '\0';
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (g_chan[i].period_ticks <= 0 || !g_chan[i].known) continue;
        long long p = g_chan[i].period_ns > 0 ? g_chan[i].period_ns
                                             : (long long) g_chan[i].period_ticks * TICK_NS;
        used += (size_t) snprintf(out + used, out_size - used, "%s%d:%.0fms%s",
                                  used ? " " : "", g_chan[i].type, p / 1e6,
                                  g_chan[i].hinted && !g_chan[i].active_hint ? "(idle)" : "");
        if (used >= out_size - 24) break;
    }
    pthread_mutex_unlock(&g_lock);
    return (int) used;
}

void vw_set_active(int active) {
    pthread_mutex_lock(&g_lock);
    if (active && !g_active) {
        /* 重新激活：丢弃旧时间基准，避免把关闭期间的"空档"补成一堆事件 */
        g_last_tick = 0;
        g_have_state = 0;
        memset(g_steps_q, 0, sizeof(g_steps_q));
        g_last_steps_seen = g_steps;
        for (int i = 0; i < MAX_CHANNELS; i++) g_chan[i].next_due_ns = 0; /* 重新对齐栅格 */
    }
    g_active = active ? 1 : 0;
    pthread_mutex_unlock(&g_lock);
    LOGI("virtual sensors %s", active ? "ACTIVE" : "inactive");
}

int vw_is_active(void) { return g_active; }

void vw_set_handle(int32_t type, int32_t handle, uint32_t sensor_flags) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (g_chan[i].type == type) {
            if (!g_chan[i].known || g_chan[i].handle != handle) {
                LOGI("sensor type %d -> handle 0x%x (flags 0x%x)", type, handle, sensor_flags);
            }
            g_chan[i].handle = handle;
            g_chan[i].flags = sensor_flags;
            g_chan[i].known = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
}

void vw_seed_handle(int32_t type, int32_t handle, uint32_t sensor_flags) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (g_chan[i].type != type) continue;
        if (!g_chan[i].known) {
            g_chan[i].handle = handle;
            g_chan[i].flags = sensor_flags;
            g_chan[i].known = 1;
            LOGI("sensor type %d -> handle 0x%x (flags 0x%x) [framework list]", type, handle,
                 sensor_flags);
        } else if (g_chan[i].handle != handle) {
            LOGW("sensor type %d: framework list says 0x%x but events say 0x%x - keeping the latter",
                 type, handle, g_chan[i].handle);
        }
        break;
    }
    pthread_mutex_unlock(&g_lock);
}

void vw_update_state(double speed, double azimuth_deg, int moving, long long steps,
                     long long now_nanos) {
    pthread_mutex_lock(&g_lock);
    g_speed = speed;
    g_target_azimuth = azimuth_deg;
    g_moving = moving;
    g_have_state = 1;
    if (steps > g_last_steps_seen && g_last_step_push_nanos > 0) {
        long long delta = steps - g_last_steps_seen;
        if (delta > STEP_QUEUE_CAP) delta = STEP_QUEUE_CAP; /* 异常跳变：只补满队列 */
        long long span = now_nanos - g_last_step_push_nanos;
        if (span <= 0) span = 1000000LL;
        /* 把这一步间隔**按步分摊**：每步一个时间戳，事件率回到真实步频（~1.5~3Hz） */
        long long per = span / delta;
        if (per <= 0) per = 1;
        long long base = steps - delta;
        for (long long k = 1; k <= delta; k++) {
            for (int i = 0; i < STEP_QUEUE_CAP; i++) {
                if (!g_steps_q[i].used) {
                    g_steps_q[i].used = 1;
                    g_steps_q[i].ts = g_last_step_push_nanos + per * k;
                    g_steps_q[i].count = base + k;
                    break;
                }
            }
        }
    }
    g_last_steps_seen = steps;
    g_last_step_push_nanos = now_nanos;
    g_state_nanos = now_nanos;
    pthread_mutex_unlock(&g_lock);
}

long long vw_step_events_total(void) { return g_step_emit_total; }

/** 把已学到的 type→handle 映射写成 "1:0xb 2:0x15 ..."（诊断页展示） */
/** 步态波形口径（诊断用：Test 页据此确认跑的是哪一版波形） */
const char *vw_gait_describe(void) {
    static char buf[48];
    snprintf(buf, sizeof(buf), "stride%d(pure-sin)", g_gait_steps);
    return buf;
}

int vw_dump_handles(char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return 0;
    size_t used = 0;
    out[0] = '\0';
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (!g_chan[i].known) continue;
        int w = snprintf(out + used, out_size - used, "%s%d:0x%x", used ? " " : "",
                         g_chan[i].type, g_chan[i].handle);
        if (w <= 0 || (size_t) w >= out_size - used) break;
        used += (size_t) w;
    }
    pthread_mutex_unlock(&g_lock);
    return (int) used;
}

void vw_stats(long long *emitted, long long *dropped, long long *suppressed) {
    if (emitted) *emitted = g_emitted;
    if (dropped) *dropped = g_dropped;
    if (suppressed) *suppressed = g_suppressed;
}

void vw_note_suppressed(long long n) { g_suppressed += n; }

/* ------------------------------------------------------------------ */
/* 朝向快频段（与 FakeLoc.advanceSway / microOffset 同构）              */
/* ------------------------------------------------------------------ */

static double shortest_delta(double target, double current) {
    double d = fmod(target - current, 360.0);
    if (d > 180.0) d -= 360.0;
    if (d <= -180.0) d += 360.0;
    return d;
}

static double advance_sway(long long now) {
    if (g_sway_start == 0) {
        g_sway_start = now;
        g_sway_side = 1.0;
        g_sway_from = 0.0;
        g_sway_to = rng_range(SWAY_MIN_DEG, SWAY_MAX_DEG);
        g_sway_half = SWAY_HALF_MIN_NS +
                      (long long) (rng_unit() * (double) (SWAY_HALF_MAX_NS - SWAY_HALF_MIN_NS));
    }
    long long elapsed = now - g_sway_start;
    if (elapsed >= g_sway_half) {
        g_sway_from = g_sway_to;
        g_sway_side = -g_sway_side;
        g_sway_to = g_sway_side * rng_range(SWAY_MIN_DEG, SWAY_MAX_DEG);
        g_sway_start = now;
        g_sway_half = SWAY_HALF_MIN_NS +
                      (long long) (rng_unit() * (double) (SWAY_HALF_MAX_NS - SWAY_HALF_MIN_NS));
        elapsed = 0;
    }
    double t = (double) elapsed / (double) g_sway_half;
    if (t < 0.0) t = 0.0;
    if (t > 1.0) t = 1.0;
    double ease = (1.0 - cos(M_PI * t)) / 2.0;
    return g_sway_from + (g_sway_to - g_sway_from) * ease;
}

/* 推进一个栅格：dt = TICK_NS */
static void advance_one_tick(long long now) {
    double dt = (double) TICK_NS / 1e9;
    if (!g_azimuth_init) {
        g_azimuth = g_target_azimuth;
        g_azimuth_init = 1;
    }
    double delta = shortest_delta(g_target_azimuth, g_azimuth);
    /* 与 app 端一致：alphaForDelta(dt, 0.18) */
    double ticks = dt / 0.05;
    if (ticks > 8.0) ticks = 8.0;
    double alpha = 1.0 - pow(1.0 - BEARING_APPROACH_ALPHA, ticks);
    g_azimuth += delta * alpha;
    g_azimuth = fmod(fmod(g_azimuth, 360.0) + 360.0, 360.0);

    /* 微抖：按概率重选目标 + 平滑趋近 */
    if (rng_unit() < 1.0 - exp(-dt / MICRO_JITTER_TAU)) {
        g_micro_target = rng_range(-MICRO_JITTER_AMP, MICRO_JITTER_AMP);
    }
    g_micro_offset += (g_micro_target - g_micro_offset) * (1.0 - exp(-dt / MICRO_JITTER_TAU));

    /* 步态相位推进：优先用**实测步间隔**（与步事件同一节拍），没测到再退回步频公式。
     * 相位是**步幅相位**：一个完整周期 = **两步**（真实步态的"步幅周期"），
     * 所以每步只推进半个周期（M_PI）——见 gait_accel 的说明。 */
    if (g_moving && g_speed > 0.05) {
        double step_hz;
        if (g_gait_interval_ns > 0) {
            step_hz = 1e9 / (double) g_gait_interval_ns;
        } else {
            double cadence = 60.0 + 30.0 * g_speed;
            cadence *= 1.15;
            if (cadence < 60.0) cadence = 60.0;
            if (cadence > 220.0) cadence = 220.0;
            step_hz = cadence / 60.0;
        }
        /* 每步推进 2π/步数 ⇒ 一个波形周期正好是 g_gait_steps 步 */
        g_gait_phase += (2.0 * M_PI / (double) g_gait_steps) * step_hz * dt;
        if (g_gait_phase >= 2.0 * M_PI) g_gait_phase -= 2.0 * M_PI;
    }
    (void) now;
}

/**
 * 步态相位改为**由步时间戳连续插值**（而不是自由积分 + 每步跳相）。
 *
 * 为什么：自由积分的相位与 Java 侧真正发步的时刻会缓慢漂移（实测 IMU 基频 184~193/分
 * vs 步事件 180/分）——"IMU 峰"和"计步事件"本是同一个人的同一步，错开后，同时消费
 * 两条通道的应用（IMU 检测 + 计步事件去重）会把同一步算成两步。
 * 而直接用"上一步时间 + 平滑步间隔"算相位：峰值**精确落在步时间戳上**、波形连续
 * （跳相会在波形里留下一道台阶，检测器照样多计——实测踩到过），频率恒等于步频。
 */
static void gait_note_step(long long ts) {
    if (g_gait_anchor_ns > 0) {
        long long iv = ts - g_gait_anchor_ns;
        /* 只接受合理步间隔（0.2~3 步/秒），异常值不参与平滑 */
        if (iv > 330000000LL && iv < 5000000000LL) {
            g_gait_interval_ns = g_gait_interval_ns > 0
                                         ? (g_gait_interval_ns * 3 + iv) / 4
                                         : iv;
        }
    }
    g_gait_anchor_ns = ts;

    /* PLL 纠相：增益必须**极小**（0.02 rad ≈ 波形上 0.02 m/s²，低于噪声）。
     * 0.35 那种量级会在每个步点留下一个肉眼可见的台阶，而台阶本身就是检测器的
     * "额外一步"——实测 180 步/分被读成 202 步/分。小增益下几十步才纠完漂移，
     * 波形始终光滑，频率仍由实测步间隔推进（= 步频）。
     *
     * 目标相位**逐步交替**：一个步幅周期里两个峰分别在 π/2 与 3π/2，
     * 于是每一步都落在自己的那个峰上（而不是把相位硬拉回同一个点 —— 那会与
     * "每步推进半个周期"打架，等于给频率加一个恒定偏置）。 */
    double target = (double) ((g_gait_step_index % g_gait_steps) + 1) *
                    (2.0 * M_PI / (double) g_gait_steps);
    double err = target - g_gait_phase;
    while (err > M_PI) err -= 2.0 * M_PI;
    while (err < -M_PI) err += 2.0 * M_PI;
    g_gait_phase += 0.02 * err;
    while (g_gait_phase >= 2.0 * M_PI) g_gait_phase -= 2.0 * M_PI;
    while (g_gait_phase < 0.0) g_gait_phase += 2.0 * M_PI;
    g_gait_step_index++;
}

/**
 * 当前步态加速度（设备坐标，m/s²）。静止时三项全 0。
 *
 * **纯正弦，一个波形周期 = 两步**（步幅相位，见 advance_one_tick）：`az = amp·sin φ`。
 * 即"只把原来的波长拉长一倍"，不做任何谐波加工。
 *
 * 已知后果（刻意保留，便于手动对比）：
 *   · 基频落在**步幅频率**（步频 / 2）。按"两步一周期"换算步频的估计器（FFT 取基频 ×2
 *     那类）读数会回落到真步频 —— 这正是要修的现象（实测读数 286 ≈ 2×143）。
 *   · 一个周期内只有**一个正峰**，所以纯峰值/阈值计数类估计器会读到**步频的一半**
 *     （每两步一个峰）。要两全就得补二次谐波（1 周期 = 1 步），本版按用户要求不做。
 *
 * 三分量仍**几乎同相**（±0.05rad）：加速度**模长** sqrt(ax²+ay²+az²) 的形状才与 az 一致。
 * 踩过的坑：对称的 sin(2φ)（每步两个等高峰）→ 基频被读成 2× 步频；
 * 三分量相位差过大（0.2~0.25rad）→ 模长出现小双峰，阈值类检测器多计一步。
 */
static void gait_accel(long long t, double *ax, double *ay, double *az) {
    (void) t;
    if (!g_moving || g_speed <= 0.05) {
        *ax = *ay = *az = 0.0;
        return;
    }
    double amp = GAIT_Z_PER_SPEED * g_speed + GAIT_Z_BASE;
    if (amp > GAIT_AMP_MAX) amp = GAIT_AMP_MAX;
    double ph = g_gait_phase;
    *az = amp * sin(ph);
    *ay = GAIT_AY_RATIO * amp * sin(ph + 0.05);
    *ax = GAIT_AX_RATIO * amp * sin(ph - 0.05);
}

/** 当前注入方位：平滑中轴 + 摆动 + 微抖（归一化到 [0,360)） */
static double virtual_azimuth(long long now) {
    double az = g_azimuth + advance_sway(now) + g_micro_offset;
    az = fmod(fmod(az, 360.0) + 360.0, 360.0);
    return az;
}

static double gyro_z(long long now) {
    double az = virtual_azimuth(now);
    double raw = 0.0;
    if (g_gyro_init) {
        double dt = (double) TICK_NS / 1e9;
        double d = shortest_delta(az, g_last_az_for_gyro);
        raw = (d * M_PI / 180.0) / dt;
    }
    g_gyro_init = 1;
    g_last_az_for_gyro = az;
    g_gyro_z += (raw - g_gyro_z) * GYRO_SMOOTH_ALPHA;
    (void) now;
    return g_gyro_z;
}

/* ------------------------------------------------------------------ */
/* 事件填充                                                            */
/* ------------------------------------------------------------------ */

static void fill_event(portal_sensor_event_t *e, const vw_channel_t *ch, long long ts) {
    memset(e, 0, sizeof(*e));
    e->version = (int32_t) sizeof(portal_sensor_event_t); /* 真机 = sizeof(sensors_event_t) */
    e->sensor = ch->handle;
    e->type = ch->type;
    e->timestamp = ts;
    e->flags = ch->flags;
}

static void add_noise_i(portal_sensor_event_t *e, int index, float amp) {
    if (index >= 0 && index < 16) e->data.f[index] += rng_noise(amp);
}

/* 按类型填充 16 通道数据（与 SystemSensorManagerHook.sensorValuesFor 同口径） */
static void fill_values(portal_sensor_event_t *e, long long now) {
    double az = virtual_azimuth(now);
    double theta = az * M_PI / 180.0;
    switch (e->type) {
        case PS_TYPE_ORIENTATION:
            e->data.f[0] = (float) az;
            add_noise_i(e, 0, 0.15f);
            break;
        case PS_TYPE_MAGNETIC_FIELD:
            e->data.f[0] = (float) (-g_mag_h * sin(theta));
            e->data.f[1] = (float) (g_mag_h * cos(theta));
            e->data.f[2] = (float) (-g_mag_h * g_mag_dip);
            add_noise_i(e, 0, 0.3f);
            add_noise_i(e, 1, 0.3f);
            add_noise_i(e, 2, 0.3f);
            break;
        case PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED:
            e->data.f[0] = (float) (-g_mag_h * sin(theta) + g_mag_bias_x);
            e->data.f[1] = (float) (g_mag_h * cos(theta) + g_mag_bias_y);
            e->data.f[2] = (float) (-g_mag_h * g_mag_dip);
            e->data.f[3] = (float) g_mag_bias_x;
            e->data.f[4] = (float) g_mag_bias_y;
            e->data.f[5] = 0.0f;
            add_noise_i(e, 0, 0.3f);
            add_noise_i(e, 1, 0.3f);
            add_noise_i(e, 2, 0.3f);
            break;
        case PS_TYPE_GRAVITY:
            /* 重力只含恒定分量：走路的周期分量在 LINEAR_ACCELERATION 里，
             * 两者相加正好等于 ACCELEROMETER（真机的物理关系） */
            e->data.f[0] = 0.0f;
            e->data.f[1] = 0.0f;
            e->data.f[2] = 9.81f;
            add_noise_i(e, 0, 0.01f);
            add_noise_i(e, 1, 0.01f);
            add_noise_i(e, 2, 0.01f);
            break;
        case PS_TYPE_ACCELEROMETER:
        case PS_TYPE_ACCELEROMETER_UNCALIBRATED: {
            /* 平放姿态 + 步态：平放避免真实倾角破坏 getRotationMatrix 投影，
             * 步态则让"在走路"这件事在 IMU 上真的看得见 */
            double gx, gy, gz;
            gait_accel(now, &gx, &gy, &gz);
            e->data.f[0] = (float) gx;
            e->data.f[1] = (float) gy;
            e->data.f[2] = (float) (9.81 + gz);
            e->data.f[3] = 0.0f;
            e->data.f[4] = 0.0f;
            e->data.f[5] = 0.0f;
            add_noise_i(e, 0, 0.01f);
            add_noise_i(e, 1, 0.01f);
            add_noise_i(e, 2, 0.01f);
            break;
        }
        case PS_TYPE_LINEAR_ACCELERATION: {
            double gx, gy, gz;
            gait_accel(now, &gx, &gy, &gz);
            e->data.f[0] = (float) gx;
            e->data.f[1] = (float) gy;
            e->data.f[2] = (float) gz;
            add_noise_i(e, 0, 0.01f);
            add_noise_i(e, 1, 0.01f);
            add_noise_i(e, 2, 0.01f);
            break;
        }
        case PS_TYPE_ROTATION_VECTOR:
        case PS_TYPE_GAME_ROTATION_VECTOR:
        case PS_TYPE_GEOMAGNETIC_ROTATION_VECTOR: {
            double half = theta / 2.0;
            e->data.f[0] = 0.0f;
            e->data.f[1] = 0.0f;
            e->data.f[2] = (float) (-sin(half));
            e->data.f[3] = (float) cos(half);
            add_noise_i(e, 0, 0.0015f);
            add_noise_i(e, 1, 0.0015f);
            add_noise_i(e, 2, 0.0015f);
            break;
        }
        case PS_TYPE_GYROSCOPE:
            e->data.f[2] = (float) gyro_z(now);
            add_noise_i(e, 2, 0.005f);
            break;
        case PS_TYPE_GYROSCOPE_UNCALIBRATED:
            e->data.f[2] = (float) (gyro_z(now) + g_gyro_drift_z);
            e->data.f[3] = (float) g_gyro_drift_x;
            e->data.f[4] = (float) g_gyro_drift_y;
            e->data.f[5] = (float) g_gyro_drift_z;
            add_noise_i(e, 2, 0.005f);
            break;
        default:
            break;
    }
}

int vw_generate(portal_sensor_event_t *out, int cap, long long now_nanos) {
    if (cap <= 0) return 0;
    int n = 0;
    pthread_mutex_lock(&g_lock);
    if (!g_active || !g_have_state || g_last_tick == 0) {
        g_last_tick = now_nanos;
        pthread_mutex_unlock(&g_lock);
        return 0;
    }

    /* 积压过多（HAL 静默一段）→ 直接跳到窗口起点，只保留最近 2s */
    long long earliest = now_nanos - (long long) MAX_TICKS_PER_CALL * TICK_NS;
    if (g_last_tick < earliest) {
        long long skipped = (earliest - g_last_tick) / TICK_NS;
        g_dropped += skipped;
        g_last_tick += skipped * TICK_NS;
        /* 丢弃过期的步事件 */
        for (int i = 0; i < STEP_QUEUE_CAP; i++) {
            if (g_steps_q[i].used && g_steps_q[i].ts < g_last_tick) g_steps_q[i].used = 0;
        }
    }

    long long t = g_last_tick;
    while (t + TICK_NS <= now_nanos) {
        t += TICK_NS;
        /* 步事件优先按时序插入（on-change，与栅格无关） */
        for (int i = 0; i < STEP_QUEUE_CAP; i++) {
            if (!g_steps_q[i].used || g_steps_q[i].ts > t) continue;
            g_steps_q[i].used = 0;
            if (n + 2 > cap) {
                g_dropped += 2;
                continue;
            }
            long long ts = g_steps_q[i].ts;
            long long cnt = g_steps_q[i].count;
            /* 计数器与检测器 = **同一次步事件、同一时间戳**：
             * 一个在涨而另一个不响，会被交叉比对看出来。 */
            portal_sensor_event_t *ec = &out[n++];
            memset(ec, 0, sizeof(*ec));
            ec->version = (int32_t) sizeof(portal_sensor_event_t);
            ec->type = PS_TYPE_STEP_COUNTER;
            ec->timestamp = ts;
            /*
             * **int64 视图，不是 float**：真机 HAL 把步数写在
             * `sensors_event_t.u64.step_counter`（占满 data[0..1]），框架与客户端 Java 侧
             * 都按 int64 读。按 float 写会让客户端把 float 的**位模式**当成步数
             * —— 实测把计数器顶到 700000 时，客户端读到 1227548160 = bits(700000.0f)。
             * （真机步数事件的 float 视图实测为 0.000，正是"int64 小整数被当 float 读"的样子。）
             */
            ec->data.u64[0] = (uint64_t) cnt;
            g_last_counter_value = cnt; /* 诊断：客户端看到的"开机总步数" */
            portal_sensor_event_t *ed = &out[n++];
            memset(ed, 0, sizeof(*ed));
            ed->version = (int32_t) sizeof(portal_sensor_event_t);
            ed->type = PS_TYPE_STEP_DETECTOR;
            ed->timestamp = ts;
            ed->data.f[0] = 1.0f;
            for (int c = 0; c < MAX_CHANNELS; c++) {
                if (!g_chan[c].known) continue;
                if (g_chan[c].type == PS_TYPE_STEP_COUNTER) {
                    ec->sensor = g_chan[c].handle;
                    ec->flags = g_chan[c].flags;
                } else if (g_chan[c].type == PS_TYPE_STEP_DETECTOR) {
                    ed->sensor = g_chan[c].handle;
                    ed->flags = g_chan[c].flags;
                }
            }
            /* 这一步在 IMU 上也必须正好是一个峰（见 gait_note_step） */
            gait_note_step(ts);
            step_emitted(ts);
        }

        advance_one_tick(t);
        for (int c = 0; c < MAX_CHANNELS; c++) {
            if (!g_chan[c].known) continue;
            if (g_chan[c].period_ticks <= 0) continue; /* 步数传感器不参与栅格 */
            /*
             * 没人订阅 ⇒ 静默（真机 HAL 不会被启用，自然一条事件都没有）。
             * "有人订阅" 有两个来源：框架 dump 报活跃，或刚刚看到该类型的真实事件
             * （应用刚 registerListener 时 HAL 立刻开始出数据，这条让我们**即时**恢复，
             *   不必等下一次 dump）。
             */
            if (!channel_live(&g_chan[c], t)) continue;
            long long period = g_chan[c].period_ns > 0
                               ? g_chan[c].period_ns
                               : (long long) g_chan[c].period_ticks * TICK_NS;
            /*
             * **按"下次应发时刻"累积，而不是 tick_index % period_ticks**：
             * 应用的采用值常常不是 10ms 栅格的整数倍（实测 66.7ms、20ms…），
             * 取模只能把它凑成 60/70ms 的整数倍；这里让相位自己累积，
             * 平均速率就精确落在采用值上（66.7ms ⇒ 6/7 tick 交替）。
             */
            if (g_chan[c].next_due_ns == 0) {
                g_chan[c].next_due_ns = t; /* 首次：当拍立即出，不补旧账 */
            } else if (g_chan[c].next_due_ns < t - period * 4) {
                g_chan[c].next_due_ns = t; /* 长时间没出（刚恢复）：对齐，别补一串 */
            }
            if (t < g_chan[c].next_due_ns) continue;
            g_chan[c].next_due_ns += period;
            if (n >= cap) {
                g_dropped++;
                continue;
            }
            portal_sensor_event_t *e = &out[n++];
            fill_event(e, &g_chan[c], t);
            fill_values(e, t);
        }
    }
    g_last_tick = t;
    /* 清掉已过期但没被捞出的步事件（容量不足时的兜底） */
    for (int i = 0; i < STEP_QUEUE_CAP; i++) {
        if (g_steps_q[i].used && g_steps_q[i].ts <= t) g_steps_q[i].used = 0;
    }
    g_emitted += n;
    pthread_mutex_unlock(&g_lock);
    return n;
}
