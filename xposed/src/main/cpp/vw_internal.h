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
