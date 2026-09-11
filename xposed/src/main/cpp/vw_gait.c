/*
 * 步态与朝向模型（虚拟世界的"运动学"）。
 *
 * 为什么单独一个单元：它是**纯数学 + 状态机**（相位 PLL、摆动/微抖、角速度低通），
 * 与投递/栅格/消费者归属毫无关系；混在 1100 行的主文件里既难读也难改。
 *
 * 状态与并发：本文件的状态（相位/摆动/微抖/低通）**只在这里读写**，所以保持 static；
 * 生成期调用（fill_values / vw_generate 的 tick 循环）发生在**世界锁内**（见 vw_internal.h），
 * 因此这些函数不加锁 —— 它们与其他世界状态共享同一个临界区，别在这里补锁（pthread
 * 互斥量不可重入，会自死锁）。
 *
 * ⚠️ 步态波形是**反复实测校准过**的：`g_gait_steps = 9`（一个波形周期 = 9 步，
 * 由"波长 ×3"两次迭代而来）。改任何系数或相位推进都会直接改变应用侧步频读数。
 */
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "vw_internal.h"


/* ---- 朝向/角速度状态（原在 virtual_world.c 的"生成器状态"里，只被本单元读写） ---- */
static int g_azimuth_init = 0;
static double g_azimuth = 0.0;         /* 平滑后的注入方位（度） */
static double g_gyro_z = 0.0;          /* 低通后的角速度（rad/s） */
static double g_last_az_for_gyro = 0.0;
static int g_gyro_init = 0;

static const long long SWAY_HALF_MIN_NS = 350000000LL;
static const long long SWAY_HALF_MAX_NS = 750000000LL;
/* ---- 运动学系数（原在 virtual_world.c，随本单元一起搬；都是"校准过的值"，别随手改） ---- */
static const double BEARING_APPROACH_ALPHA = 0.18; /* 每 50ms 靠近 18% → τ≈0.25s */
static const double GYRO_SMOOTH_ALPHA = 0.25;
static const double MICRO_JITTER_AMP = 0.6;
static const double MICRO_JITTER_TAU = 0.12;
static const double SWAY_MAX_DEG = 3.0;
static const double SWAY_MIN_DEG = 0.5;

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

static double g_sway_from = 0.0;   /* 本次摆动的起止角（度） */
static double g_sway_to = 0.0;
static double g_sway_side = 1.0;
static long long g_sway_start = 0;
static long long g_sway_half = 500000000LL;
static double g_micro_target = 0.0;
static double g_micro_offset = 0.0;

static const double GAIT_Z_PER_SPEED = 0.35;
static const double GAIT_Z_BASE = 0.55; /* 低速也留得住可检测幅度（1 m/s → 0.9 m/s²） */
static const double GAIT_AMP_MAX = 3.0;
static const double GAIT_AY_RATIO = 0.25;
static const double GAIT_AX_RATIO = 0.12;

const char *vw_gait_describe(void) {
    static char buf[48];
    snprintf(buf, sizeof(buf), "stride%d(pure-sin)", g_gait_steps);
    return buf;
}

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
/**
 * 初始化朝向状态（`vw_init` 调用）：随机初值、目标一致、标记"尚未平滑"。
 * 注意它**必须消耗与拆分前相同次数**的随机数（一次 rng_range），否则后续所有随机量都会错位。
 */
void vw_gait_init(void) {
    g_azimuth = rng_range(0.0, 360.0);
    g_target_azimuth = g_azimuth;
    g_azimuth_init = 0;
}

void advance_one_tick(long long now) {
    double dt = (double) g_tick_ns / 1e9;
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
void gait_note_step(long long ts) {
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
void gait_accel(long long t, double *ax, double *ay, double *az) {
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
double virtual_azimuth(long long now) {
    double az = g_azimuth + advance_sway(now) + g_micro_offset;
    az = fmod(fmod(az, 360.0) + 360.0, 360.0);
    return az;
}

double gyro_z(long long now) {
    double az = virtual_azimuth(now);
    double raw = 0.0;
    if (g_gyro_init) {
        double dt = (double) g_tick_ns / 1e9;
        double d = shortest_delta(az, g_last_az_for_gyro);
        raw = (d * M_PI / 180.0) / dt;
    }
    g_gyro_init = 1;
    g_last_az_for_gyro = az;
    g_gyro_z += (raw - g_gyro_z) * GYRO_SMOOTH_ALPHA;
    (void) now;
    return g_gyro_z;
}
