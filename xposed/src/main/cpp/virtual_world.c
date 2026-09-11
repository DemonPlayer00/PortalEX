#include "virtual_world.h"

#include <math.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "PortalSensor"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* ---- 时间网格 ---- */
/* 10ms 基准栅格：所有周期都是它的整数倍，事件天然按时间升序交织，
 * 应用侧按相邻事件时间戳算 dt 不会出现负值（真机 HAL 也是按时序交织的）。*/
#define TICK_NS 10000000LL
#define MAX_TICKS_PER_CALL 200 /* 单次最多追 2s，再长就丢旧数据 */
#define STEP_QUEUE_CAP 64
#define MAX_CHANNELS 12

typedef struct {
    int32_t type;
    int period_ticks;
    int32_t handle;
    uint32_t flags;
    int known;
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
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- Java 推来的状态快照 ---- */
static int g_active = 0;
static double g_speed = 3.05;
static double g_target_azimuth = 0.0;
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

static double g_sway_from = 0.0, g_sway_to = 0.0, g_sway_side = 1.0;
static long long g_sway_start = 0, g_sway_half = 500000000LL;
static double g_micro_target = 0.0, g_micro_offset = 0.0;

static long long g_emitted = 0, g_dropped = 0, g_suppressed = 0;

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

void vw_set_active(int active) {
    pthread_mutex_lock(&g_lock);
    if (active && !g_active) {
        /* 重新激活：丢弃旧时间基准，避免把关闭期间的"空档"补成一堆事件 */
        g_last_tick = 0;
        g_have_state = 0;
        memset(g_steps_q, 0, sizeof(g_steps_q));
        g_last_steps_seen = g_steps;
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
    (void) now;
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
            e->data.f[0] = 0.0f;
            e->data.f[1] = 0.0f;
            e->data.f[2] = 9.81f;
            add_noise_i(e, 0, 0.01f);
            add_noise_i(e, 1, 0.01f);
            add_noise_i(e, 2, 0.01f);
            break;
        case PS_TYPE_ACCELEROMETER:
        case PS_TYPE_ACCELEROMETER_UNCALIBRATED:
            /* 平放姿态：与 GRAVITY 同源，避免真实倾角破坏 getRotationMatrix 投影 */
            e->data.f[0] = 0.0f;
            e->data.f[1] = 0.0f;
            e->data.f[2] = 9.81f;
            e->data.f[3] = 0.0f;
            e->data.f[4] = 0.0f;
            e->data.f[5] = 0.0f;
            add_noise_i(e, 0, 0.01f);
            add_noise_i(e, 1, 0.01f);
            add_noise_i(e, 2, 0.01f);
            break;
        case PS_TYPE_LINEAR_ACCELERATION:
            add_noise_i(e, 0, 0.01f);
            add_noise_i(e, 1, 0.01f);
            add_noise_i(e, 2, 0.01f);
            break;
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
            ec->data.f[0] = (float) cnt; /* 与 asm/u64 视图同一段内存，真机 HAL 也写 float */
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
        }

        advance_one_tick(t);
        long long tick_index = t / TICK_NS;
        for (int c = 0; c < MAX_CHANNELS; c++) {
            if (!g_chan[c].known) continue;
            if (tick_index % g_chan[c].period_ticks != 0) continue;
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
