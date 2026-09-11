/*
 * virtual_world 的**内部**接口（只有本目录的 .c 用；外部/Java 侧一律走 virtual_world.h）。
 *
 * 为什么要有它：`virtual_world.c` 现在按主题拆成多个编译单元（PRNG / 噪声档 / 主世界），
 * 但**世界锁与状态的所有权没有变** —— 拆分的是文件，不是并发模型。
 */
#ifndef PORTAL_VW_INTERNAL_H
#define PORTAL_VW_INTERNAL_H

#include <pthread.h>
#include <stdint.h>

#include "virtual_world.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * **世界锁**：所有世界状态（通道表、步事件队列、噪声档、统计量…）都在它下面读写。
 *
 * 它定义在 `virtual_world.c`，按主题拆出去的文件只是**共用**同一把锁 ——
 * 千万不要在拆分时给某个模块再发一把私有锁：那会把"整体有序"变成"两把锁的竞态"
 * （噪声档写入与生成期读取原本靠这把锁串起来）。
 */
extern pthread_mutex_t g_lock;

/** 世界的基础时间栅格（10ms）：所有周期都是它的整数倍，事件天然按时间升序交织 */
#define TICK_NS 10000000LL

/* ---- PRNG（vw_rand.c） ---- */
/** 进程级播种（常量种子 ⊕ 启动时间 ⊕ pid）：`vw_init` 调一次；不调也能跑，只是各进程同序列 */
void vw_rng_seed_process(void);
uint64_t vw_rng_next(void);
double vw_rng_unit(void);
double vw_rng_range(double lo, double hi);
/** 标准正态（Box–Muller） */
double vw_gauss(void);

/** 与 `virtual_world.c` 内调用点保持一致的短名（内联转发，不引入额外状态） */
static inline double rng_unit(void) { return vw_rng_unit(); }
static inline double rng_range(double lo, double hi) { return vw_rng_range(lo, hi); }

/* ---- 步态与朝向（vw_gait.c） ---- */
/*
 * 世界状态里被运动学读取的那部分（定义仍在 virtual_world.c，因为它由 vw_update_state 写、
 * 也被生成器读）：步态是"世界的函数"，所以这些量必须显式共享，而不是各自藏一份副本。
 */
extern int g_moving;
extern double g_speed;
extern long long g_tick_ns;
extern double g_target_azimuth;


/** 推进虚拟世界一拍（相位 PLL、摆动、微抖、方位平滑、角速度低通） */
void vw_gait_init(void);
void advance_one_tick(long long now);
/** 记一次"这一步在 IMU 上也必须正好是一个峰" */
void gait_note_step(long long ts);
/** 当前步态加速度（设备坐标；静止时全 0） */
void gait_accel(long long t, double *ax, double *ay, double *az);
/** 当前注入方位（平滑中轴 + 摆动 + 微抖，归一化到 [0,360)） */
double virtual_azimuth(long long now);
/** z 轴角速度（低通后的转弯角速度，rad/s） */
double gyro_z(long long now);

/* ---- 噪声档（vw_noise.c） ---- */
/**
 * 第 [index] 槽的 σ —— **无锁**读取，调用方必须已持有 [g_lock]（生成期在锁内）。
 * 外部设置/读取一律走 virtual_world.h 的 vw_set_noise / vw_get_noise（它们自己取锁）。
 */
float vw_noise_raw(int index);
/** 给事件的第 [index] 个通道叠加 σ 为 [sigma] 的高斯噪声（sigma ≤ 0 不动） */
void add_noise_i(portal_sensor_event_t *e, int index, float sigma);
/** 三轴逐轴叠加（[base] 为该传感器 σ 的起始槽） */
void add_noise_xyz(portal_sensor_event_t *e, int base);

#ifdef __cplusplus
}
#endif

#endif /* PORTAL_VW_INTERNAL_H */
