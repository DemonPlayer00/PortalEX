#include "vw_internal.h"

/*
 * ============================ 文件结构地图 ============================
 *
 * 这是"虚拟世界"的主文件：**时间轴 + 通道表 + 生成器 + 事件填充**。
 * 按主题拆出去的部分：
 *   · vw_rand.c    —— xorshift 与高斯抽样（纯机械能力）
 *   · vw_noise.c   —— 注入噪声档（逐轴 σ + 陀螺零偏，Calibration 页可编辑）
 * 内部接口与**世界锁**见 vw_internal.h；对外接口见 virtual_world.h。
 *
 * 本文件自上而下：
 *   ① 时间网格（tick/抖动）与生成时钟        —— 决定"什么时候出数据"
 *   ② 通道表 g_chan                          —— 每个类型一个通道：速率/活跃/批量
 *   ③ 世界状态与运动学（速度/方位/步态/摆动） —— "世界现在是什么样"
 *   ④ 步事件队列                             —— on-change 的两条流（计数器/检测器）
 *   ⑤ 观测与统计（诊断）                     —— 只读记账，允许竞态
 *   ⑥ fill_event / fill_values               —— 事件内容（含噪声与精度字段）
 *   ⑦ vw_generate                            —— **两个消费者共用一条时间轴**的核心
 *
 * 改这里的铁律：`vw_generate` 的"归属判定"与"队尾清扫"必须成对看（历史上两处都丢过事件）；
 * 不变量由 host 测试守着：`sh xposed/src/main/cpp/test/run.sh`。
 * =====================================================================
 */

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

/*
 * ---- 没有栅格了：每通道"下次应发时刻" ----
 *
 * 历史：这里曾有一层固定时间栅格（10ms 基准，周期写成 tick 数），事件被量子化到栅格点上，
 * 再给时间戳加抖动去掩盖 `%10ms==0` 这个指纹。那是"用一层假时间轴去藏另一层假时间轴"。
 * 现在改成**每通道各走各的到点时刻**（相位自由累加，能精确落在框架采用值上，例如 66.7ms），
 * 事件就发在它自己到点的那一刻：世界状态按变步长惰性推进到该时刻再取值。
 *
 * 两个不变量由 host 测试守着（`sh xposed/src/main/cpp/test/run.sh`）：
 *   · 时间戳全局不回退（静默→恢复时重锚相位，不补旧账）；
 *   · 记账守恒（发出去的每一条都算进 emitted，丢掉的每一条都算进 dropped）。
 */
#define MAX_EVENTS_PER_CALL 128      /* 单次生成的硬上限（安全阀；正常一次只有几条） */
#define STALE_WINDOW_NS 2000000000LL /* 过期到不可能再送达的步事件（2s）直接清掉 */

/* 前向声明：vw_is_poll_type 用得到（定义在文件下方） */
static int type_uses_accuracy(int32_t t);
#define MAX_TICKS_PER_CALL 200 /* 单次最多追 2s，再长就丢旧数据 */
#define STEP_QUEUE_CAP 64
#define MAX_CHANNELS 14

typedef struct {
    int32_t type;
    /* 默认周期（纳秒；0 = 不参与周期推送，即步数两条 on-change 流）。
     * **只是兜底**：框架 dump 给出采用值（period_ns）后一律以采用值为准。 */
    long long def_period_ns;
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
    /*
     * 批量上报（真机语义：HAL 把事件攒在 FIFO 里，到批量边界一次性上报）。
     * `batch_ns` 取框架 dump 的 `batching_period … selected`；0 = 不批量（默认、逐条上报）。
     * 只有**所有**订阅者都要求批量时框架的 selected 才非 0，所以绝大多数情况这里是 0。
     */
    long long batch_ns;
    long long batch_due_ns;
    portal_sensor_event_t pend[8];
    int pend_n;
    /* 上一拍是否"活着"（有人订/刚看到真实事件）：用于在"静默→恢复"时重锚相位，
     * 否则恢复后会把静默期间的旧到点时刻补发出来，时间戳就会回退。 */
    int was_live;
} vw_channel_t;

static vw_channel_t g_chan[MAX_CHANNELS] = {
    /* 快档 20ms（50Hz）、慢档 40ms（25Hz）：与真机 HAL 的常见默认一致。
     * 有了框架采用值之后这些数只在"还没有 hint"的极短窗口里用到。 */
    {PS_TYPE_ACCELEROMETER, 20000000LL, -1, 0, 0},
    {PS_TYPE_ACCELEROMETER_UNCALIBRATED, 20000000LL, -1, 0, 0},
    {PS_TYPE_LINEAR_ACCELERATION, 20000000LL, -1, 0, 0},
    {PS_TYPE_GYROSCOPE, 20000000LL, -1, 0, 0},
    {PS_TYPE_GYROSCOPE_UNCALIBRATED, 20000000LL, -1, 0, 0},
    {PS_TYPE_ORIENTATION, 20000000LL, -1, 0, 0},
    {PS_TYPE_ROTATION_VECTOR, 20000000LL, -1, 0, 0},
    {PS_TYPE_GAME_ROTATION_VECTOR, 20000000LL, -1, 0, 0},
    {PS_TYPE_GRAVITY, 40000000LL, -1, 0, 0},
    {PS_TYPE_MAGNETIC_FIELD, 40000000LL, -1, 0, 0},
    {PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED, 40000000LL, -1, 0, 0},
    {PS_TYPE_GEOMAGNETIC_ROTATION_VECTOR, 40000000LL, -1, 0, 0},
    /* 步数两兄弟不在周期推送里（on-change，由步事件队列驱动） */
    {PS_TYPE_STEP_COUNTER, 0, -1, 0, 0},
    {PS_TYPE_STEP_DETECTOR, 0, -1, 0, 0},
};

/* 世界锁：定义在这里，按主题拆出去的文件共用同一把（见 vw_internal.h，别发私有锁） */
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- Java 推来的状态快照 ---- */
static int g_active = 0;
double g_speed = 3.05;
double g_target_azimuth = 0.0;   /* 朝向目标（由 vw_update_state 写、步态读） */
int g_moving = 0;
static long long g_steps = 0;
static long long g_state_nanos = 0;
static int g_have_state = 0;

/* ---- 生成器状态 ---- */
static long long g_last_tick = 0;      /* 已生成到的栅格点 */


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
/*
 * ---- 步事件"同时下发"的诊断计数器（2026-09-21 加，为查"每约 20 秒一次步数尖峰"）----
 *
 * 现象：客户端（1 秒轮询）偶发看到**两条步事件几乎同时**到达 ⇒ 按事件时间戳算步频时会得到
 * 一个荒谬的瞬时值。要定责得分开看两个环节：
 *   · g_step_multi_push —— `vw_update_state` 一次推送里带了 ≥2 步。此时 `per = span/delta`
 *     把这两步**摊在前一次推送的间隔里**，间隔被压缩成 span/2（例如 25ms）；
 *   · g_step_short_gap  —— 实际发出时与上一条步事件间隔 < 100ms（压缩真的发生了）。
 * 两者都在 status 里可读，诊断页/日志一眼能看出"是不是我们排的时刻挤在一起"。
 */
static long long g_step_multi_push = 0;   /* delta ≥ 2 的推送次数 */
static long long g_step_short_gap = 0;    /* 与上一条步事件间隔 < 100ms 的次数 */
static long long g_step_rebase_skipped = 0; /* 被判为"基线搬移"而**没有**发出去的步数 */
static long long g_last_step_emit_ts = 0; /* 上一条**实际发出**的步事件时间戳（诊断用） */
static long long g_last_step_due = 0;     /* 上一条步事件的**排定**时刻（对比压缩/钳位） */

/*
 * ---- 步事件**专用**时间戳（2026-09-21 修"两条步事件几乎同时"）----
 *
 * 为什么不能借用通用的 [jitter_ts]：它有一条**全局**单调钳位 `ts = g_last_emit_ts + 1`，
 * 而步事件天生是"迟到"的 —— 它们的排定时刻落在**上一次推送的间隔里**（空闲拍长时可达 1 秒），
 * 这段时间里 IMU 通道早就以 20~66ms 的节奏把 g_last_emit_ts 推到了"现在"附近。
 * 于是每条步事件都被钳成"上一条事件 + 1ns"：实测三条步事件的时间戳只差 **1ns**
 * （排定差明明是 284ms）⇒ 客户端按事件时间戳算步频会得到无穷大，这正是"步数尖峰"。
 *
 * 新口径：步事件按**自己**的游标走 —— 排定时刻 + 抖动，且与上一条步事件至少间隔
 * [STEP_MIN_GAP_NS]；只保证"不越过 now"与"步流内部不回退"，**不再与 IMU 的游标对齐**。
 * 与真机一致：HAL 各传感器的 FIFO 是各自独立的，跨传感器的时间戳本来就不保证有序；
 * 客户端按同一传感器的 dt 计算，步流内部有序即可。
 */
#define STEP_MIN_GAP_NS 150000000LL /* 步与步之间至少 150ms（≈400 步/分，人类达不到；真实步间隔 ~300ms） */
static long long g_last_step_ts = 0;

static long long step_ts(long long base, long long now_ns) {
    long long j = (long long) ((vw_rng_unit() * 2.0 - 1.0) * 15000000.0); /* ±15ms */
    long long ts = base + j;
    long long min_ts = g_last_step_ts + STEP_MIN_GAP_NS;
    if (ts < min_ts) ts = min_ts;      /* 与上一条步事件至少 50ms */
    if (ts > now_ns) ts = now_ns;      /* 不许跑到未来（客户端会丢） */
    if (ts < g_last_step_ts) ts = g_last_step_ts; /* 挤不下时也不回退 */
    g_last_step_ts = ts;
    return ts;
}
/* jitter 的两类"被迫改动"计数（定义在下面 jitter_ts 附近使用；这里先声明以便状态串读取） */
static long long g_jitter_clamp_now = 0;
static long long g_jitter_force_next = 0;

long long vw_step_multi_push_count(void) { return g_step_multi_push; }
long long vw_step_short_gap_count(void) { return g_step_short_gap; }
long long vw_step_rebase_skipped(void) { return g_step_rebase_skipped; }
long long vw_jitter_clamp_count(void) { return g_jitter_clamp_now; }
long long vw_jitter_force_count(void) { return g_jitter_force_next; }
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

/* 与 SystemSensorManagerHook 一致的常量 */

/*
 * 时间戳去网格化
 * --------------
 * 生成器按 10ms 栅格推进，原始时间戳全是 10ms 的整数倍（实测小数部分**一模一样**：
 * ts=170.786569894 / 170.856569894 / …），而真机 HAL 的时间戳来自 SSC，抖得毫无规律
 * —— "% 10ms == 0" 本身就是个可被判定的指纹。这里给每条事件的**时间戳**加一段抖动：
 *   · 幅度取"该通道周期"的一小部分（≥200us、上限 4ms）⇒ 抹掉栅格指纹但不改变平均速率；
 *   · 保证**严格单调**（同一批里后续事件永远在前一条之后）；
 *   · 不允许跑到 now 之后（否则应用会看到"未来"的时间戳）。
 * 数值本身仍是连续波形上的采样（只有时间轴抖），不会引入物理上说不通的数据。
 */
static long long g_last_emit_ts = 0;

static long long jitter_ts(long long base, long long span_ns, long long now_ns) {
    /* 幅度取周期的 ~5%（真机 SSC 时间戳的抖动就是几个百分点量级）：
     * 足够让 "% 10ms == 0" 失效，又不会抖得比真机还"随机"。 */
    long long amp = span_ns / 20;
    if (amp < 150000) amp = 150000;    /* 至少 150us */
    if (amp > 2000000) amp = 2000000;  /* 至多 2ms */
    long long j = (long long) ((rng_unit() * 2.0 - 1.0) * (double) amp);
    long long ts = base + j;
    if (ts > now_ns) { ts = now_ns; g_jitter_clamp_now++; }
    /*
     * 单调性修正**不能越过 now**：真机上"时间戳在未来"会被严格客户端直接丢弃
     * （同一个调用里事件数比时钟分辨率还密时，旧实现的 `g_last_emit_ts + 1` 会把
     *  时间戳顶到 now+1 —— host 不变量测试抓到的就是它）。
     * 挤不下时退回 now，允许与上一条相同：真机同一纳秒两条事件是常态，
     * 而"未来时间戳"不是。
     */
    if (ts <= g_last_emit_ts) {
        g_jitter_force_next++;
        ts = g_last_emit_ts + 1;
        if (ts > now_ns) ts = now_ns;
    }
    g_last_emit_ts = ts;
    return ts;
}

/*
 * ---- 外周传感器模拟的**按类开关**（2026-09-18 用户裁决：一个总开关拆成两侧）----
 *
 * 侧别划分（按传感器类别，与 App 侧两个功能页一一对应）：
 *  · 步频侧   ：TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR
 *  · 角度指南针侧：加速度（含未校准/线性加速度）、陀螺（含未校准）、
 *                磁场（含未校准）、朝向（orientation/rotation vector/重力）
 *
 * **为什么门控必须落在本函数**：它是"这个 type 归不归我们管"的唯一判定，
 * 而 portal_sensor.c 的分叉是「归我们管 ⇒ 发我们生成的事件；不归我们管 ⇒ **真实事件原样放行**」。
 * 于是关掉一侧 = 那一侧**一个事件都不会被注入**（这正是拆分的验收判据），
 * 而且无需在投递路径上再插一道判断。
 *
 * 默认两侧都开 ⇒ 与拆分前的行为逐位一致（拆分不得静默改变行为）。
 */
static int g_class_cadence = 1;
static int g_class_orientation = 1;

void vw_set_class_enable(int cadence, int orientation) {
    g_class_cadence = cadence ? 1 : 0;
    g_class_orientation = orientation ? 1 : 0;
}

int vw_class_enabled(int32_t type) {
    switch (type) {
        case PS_TYPE_STEP_COUNTER:
        case PS_TYPE_STEP_DETECTOR:
            return g_class_cadence;
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
            return g_class_orientation;
        default:
            return 0;
    }
}

int vw_owns_type(int32_t type) {
    return vw_class_enabled(type);
}

void vw_init(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    vw_rng_seed_process(); /* 每进程一份不同的序列，见 vw_rand.c */
    vw_rng_next();         /* 预热一步：保持与拆分前完全相同的抽取序列（行为等价） */
    g_mag_h = rng_range(28.0, 42.0);
    g_mag_dip = rng_range(0.8, 1.2);
    g_mag_bias_x = rng_range(-3.0, 3.0);
    g_mag_bias_y = rng_range(-3.0, 3.0);
    g_gyro_drift_x = rng_range(-0.008, 0.008);
    g_gyro_drift_y = rng_range(-0.008, 0.008);
    g_gyro_drift_z = rng_range(-0.008, 0.008);
    vw_gait_init(); /* 随机初始朝向：抽取次序与拆分前一致（行为等价） */
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
/*
 * 哪些类型走 poll 路径投递（见 portal_sensor.c 的 post_process）
 * ------------------------------------------------------------------
 * 1. 步数两条流：可切换（历史开关，默认关）；
 * 2. **3 值类型**（加速度/磁场/方向/陀螺/重力/线性加速度）：默认**走 poll** ——
 *    框架的运行时传感器 JNI 对这几个类型**硬要求恰好 3 个值**（多传一个整条丢弃），
 *    精度字段（data[3]）塞不进去；poll 路径我们自己造整个 sensors_event_t ⇒ 精度能落地。
 *    代价：节拍由 HAL 轮询驱动 —— 有连续订阅者时 HAL 本就按仲裁速率轮询，速率不变；
 *    没有订阅者时我们本来也不出数据（见 channel_live）。
 */
static int g_acc_via_poll = 1;
static int g_steps_via_poll = 0;

void vw_set_steps_via_poll(int on) { g_steps_via_poll = on ? 1 : 0; }

void vw_set_acc_via_poll(int on) { g_acc_via_poll = on ? 1 : 0; }

int vw_is_poll_type(int32_t type) {
    if (type == PS_TYPE_STEP_COUNTER || type == PS_TYPE_STEP_DETECTOR) return g_steps_via_poll;
    return g_acc_via_poll && type_uses_accuracy(type);
}

int vw_poll_types_enabled(void) {
    return vw_is_poll_type(PS_TYPE_ACCELEROMETER) || vw_is_poll_type(PS_TYPE_STEP_COUNTER);
}

void vw_set_channel_hint(int32_t type, long long period_ns, long long batch_ns, int active) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (g_chan[i].type != type) continue;
        if (g_chan[i].def_period_ns > 0) {
            long long p = period_ns > 0 ? period_ns : 0;
            long long b = batch_ns > 0 ? batch_ns : 0;
            int changed = !g_chan[i].hinted || g_chan[i].period_ns != p ||
                          g_chan[i].batch_ns != b || g_chan[i].active_hint != (active ? 1 : 0);
            g_chan[i].hinted = 1;
            g_chan[i].period_ns = p;
            g_chan[i].batch_ns = b;
            g_chan[i].active_hint = active ? 1 : 0;
            if (changed) {
                g_chan[i].next_due_ns = 0; /* 速率/批量/活跃变化：重新对齐，不补旧账 */
                g_chan[i].batch_due_ns = 0;
                g_chan[i].pend_n = 0;
                LOGI("rate hint: type=%d period=%.1fms batch=%.1fms active=%d", type, p / 1e6,
                     b / 1e6, g_chan[i].active_hint);
            }
        }
        break;
    }
    pthread_mutex_unlock(&g_lock);
}

/**
 * 把所有周期通道先标成"不活跃"（随后由 [vw_set_channel_hint] 按框架 dump 覆盖）。
 *
 * 用途：一次 dump 只列出**有订阅者**的传感器，所以"没被列到"就等于没人订 ⇒
 * 先清空再灌，缺席的类型自然静默，调用方不需要在 Kotlin 侧复制一份类型清单。
 */
void vw_clear_channel_hints(void) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        if (g_chan[i].def_period_ns <= 0) continue; /* 步数不是周期通道 */
        g_chan[i].hinted = 1;
        g_chan[i].active_hint = 0;
    }
    pthread_mutex_unlock(&g_lock);
}

/**
 * 该通道的**生效周期**（纳秒；0 = 不是周期通道）。
 * 框架采用值优先；没 hint 或采用值为 0（FASTEST/未指定）时退回默认周期。
 */
static long long channel_period_ns(const vw_channel_t *ch) {
    if (ch->def_period_ns <= 0) return 0;
    return ch->period_ns > 0 ? ch->period_ns : ch->def_period_ns;
}

/** 该通道此刻是否应该出数据（没人订就静默——真机 HAL 也是这样） */
static int channel_live(const vw_channel_t *ch, long long now_ns) {
    if (!ch->hinted) return 1;                 /* 还没收到提示：保持原行为 */
    if (ch->active_hint) return 1;             /* 框架说有人订 */
    return (now_ns - ch->last_real_ns) < REAL_FRESH_NS; /* 刚看到真实事件 ⇒ 立刻恢复 */
}

/** 诊断：当前**最细的活跃周期**（纳秒；0 = 没有周期通道在跑）。取代了旧的"栅格"读数 */
int vw_finest_period_dbg(void) {
    long long finest = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        long long p = channel_period_ns(&g_chan[i]);
        if (p <= 0 || !channel_live(&g_chan[i], g_state_nanos)) continue;
        if (finest == 0 || p < finest) finest = p;
    }
    pthread_mutex_unlock(&g_lock);
    return (int) finest;
}

/** 各栅格通道的生效速率（诊断：Test 页/状态字符串） */
int vw_dump_rates(char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return 0;
    size_t used = 0;
    out[0] = '\0';
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_CHANNELS; i++) {
        long long p = channel_period_ns(&g_chan[i]);
        if (p <= 0 || !g_chan[i].known) continue;
        used += (size_t) snprintf(out + used, out_size - used, "%s%d:%.0fms%s%s",
                                  used ? " " : "", g_chan[i].type, p / 1e6,
                                  g_chan[i].batch_ns > 0 ? "/batch" : "",
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
        g_last_emit_ts = 0;
        for (int i = 0; i < MAX_CHANNELS; i++) {
            g_chan[i].next_due_ns = 0; /* 重新对齐栅格 */
            g_chan[i].batch_due_ns = 0;
            g_chan[i].pend_n = 0;
        }
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
        /*
         * **计数器重基**（2026-09-21）：短时间内涨的步数超过人类可达步频时，那不是"走了这么多步"，
         * 而是**基线被搬移**（会话重启接在真实计数器后面、宿主重放状态等）。
         * 旧行为会把这种跳变也按 `per = span/delta` 排成一串步事件 —— span 很短时 per 退化成 1ns，
         * 客户端于是看到"几十条步事件同时到达"，那正是步数尖峰；而它其实没有任何物理含义。
         * 人类上限约 4 步/秒，这里给 3 倍余量（12 步/秒）当阈值：超过就只搬基线、不发事件。
         */
        double implied = (double) delta / ((double) span / 1e9);
        if (delta >= 4 && implied > 12.0) {
            g_step_rebase_skipped += delta;
            LOGI("step rebase: delta=%lld span=%lldms（%.0f 步/秒，超过人类上限）⇒ 只搬基线，不发事件",
                 delta, span / 1000000, implied);
            g_last_steps_seen = steps;
            g_last_step_push_nanos = now_nanos;
            g_state_nanos = now_nanos;
            pthread_mutex_unlock(&g_lock);
            return;
        }
        if (delta >= 2) {
            g_step_multi_push++;
            LOGI("step push delta=%lld span=%lldms per=%lldms —— 一次推送多步，间隔被压到 span/delta",
                 delta, span / 1000000, per / 1000000);
        }
        /*
         * 步频侧波动：作用在**步间隔**上（间隔抖 = 步频抖，等价且实现最直接）。
         * 每个间隔单独取一次偏差 ⇒ 慢漂让它一段快一段慢、逐条随机让它一步一个样；
         * 参数为 0 时 vw_wobble_step_interval 原样返回（逐位一致，且不消耗随机数）。
         */
        long long t = g_last_step_push_nanos;
        for (long long k = 1; k <= delta; k++) {
            t += vw_wobble_step_interval(per, now_nanos);
            for (int i = 0; i < STEP_QUEUE_CAP; i++) {
                if (!g_steps_q[i].used) {
                    g_steps_q[i].used = 1;
                    g_steps_q[i].ts = t;
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

/*
 * **按类**统计"被我们压制掉的真实事件数"（拆分的运行时判据）。
 *
 * 为什么这个量就是判据：portal_sensor.c 的语义是「归我们管 ⇒ 压制真实事件、改发我们生成的」。
 * 所以关掉一侧 ⇒ 那一侧的真实事件不再被压制 ⇒ **该类计数停止增长**（另一侧照常增长）。
 * 这比"看传感器读数"可靠得多：它不依赖"手机是否在动"，只依赖"有没有订阅者"。
 */
static long long g_sup_cadence = 0;
static long long g_sup_orientation = 0;

void vw_note_suppressed_class(int32_t type) {
    switch (type) {
        case PS_TYPE_STEP_COUNTER:
        case PS_TYPE_STEP_DETECTOR:
            g_sup_cadence++;
            break;
        default:
            g_sup_orientation++;   /* 能走到这里的只剩被接管的朝向那一族 */
            break;
    }
}

void vw_suppressed_counts(long long *cadence, long long *orientation) {
    if (cadence) *cadence = g_sup_cadence;
    if (orientation) *orientation = g_sup_orientation;
}

/* ------------------------------------------------------------------ */
/* 朝向快频段（与 FakeLoc.advanceSway / microOffset 同构）              */
/* ------------------------------------------------------------------ */


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

/* 按类型填充 16 通道数据（与 SystemSensorManagerHook.sensorValuesFor 同口径） */
/*
 * AOSP 客户端对"3 值类型"的**精度**是从 `data[3]` 的**最低字节**读的
 * （反汇编 libandroid_runtime 的 dispatchSensorEvent：`ldrb w23, [event+0x24]` = data[3] 首字节，
 *  再 sxtb 作为 Java 的 accuracy 传入）。真机 HAL 也写这里；我们之前一直留 0
 * ⇒ GPSTest 这类应用显示 "Magnetic Accuracy: Unreliable"。
 */
#define SENSOR_STATUS_ACCURACY_HIGH 3
static int type_uses_accuracy(int32_t t) {
    return t == PS_TYPE_ACCELEROMETER || t == PS_TYPE_MAGNETIC_FIELD ||
           t == PS_TYPE_ORIENTATION || t == PS_TYPE_GYROSCOPE ||
           t == PS_TYPE_GRAVITY || t == PS_TYPE_LINEAR_ACCELERATION;
}

static void fill_values(portal_sensor_event_t *e, long long now) {
    double az = virtual_azimuth(now);
    double theta = az * M_PI / 180.0;
    /*
     * 按组波动（角度与指南针侧）：**同一事件只取一次偏差**，角度类与向量类共用它 ——
     * vw_wobble_dev 会推进慢漂状态并消耗随机数，取两次会让两组量的抖动互不相同、
     * 也让"一次事件一个偏差"的语义散掉。参数全 0 时它不碰随机数（0 值逐位兼容）。
     */
    /*
     * 参考量：磁场**用本机实际场强**（g_mag_h ∈ 28~42µT），不用表里的固定 50µT ——
     * 否则 15% 的慢漂会给出 ±7.5µT（相对实际场强是 ±21%），磁场幅度抖得比真机明显。
     * 其余类型仍取 vw_wobble_ref 的固定参考量。
     */
    int is_mag = (e->type == PS_TYPE_MAGNETIC_FIELD ||
                  e->type == PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED);
    double wref = is_mag ? g_mag_h : vw_wobble_ref(e->type);
    /*
     * 向量类偏差：**绝对单位**（慢漂全额 ×ref + 逐条随机 ≤ref/180），且 100ms 采样保持。
     * 只在"吃波动"的类型上取 —— 不吃的不该推进慢漂状态、也不该消耗随机数。
     * ⚠️ 这一行曾被两次"没命中的替换"漏掉（旧口径 vw_wobble_dev 一直生效），
     * 真机数据两轮不变才暴露出来 —— 改这里务必 grep 核验。
     */
    double wdev = (wref > 0.0 && vw_wobble_dims(e->type) > 0)
            ? vw_wobble_vec_dev(VW_WOB_GROUP_ORIENTATION, wref, now) : 0.0;
    /*
     * 角度类（朝向 / 磁场方向 / 旋转矢量）用**同一个**角度偏差：它只依赖 (组, now)，
     * 所以三者天然一致 —— 罗盘指的方向与报出的朝向不会再互相打脸；
     * 且它的逐条随机最多 1°，不再让指针跳（见 vw_wobble_angle_dev 的说明）。
     */
    double wdeg = vw_wobble_angle_dev(VW_WOB_GROUP_ORIENTATION, now);   /* 度 */
    double theta_w = (az + wdeg) * M_PI / 180.0;
    switch (e->type) {
        case PS_TYPE_ORIENTATION:
            e->data.f[0] = (float) (az + wdeg);   /* 平滑中轴 + 摆动 + 微抖 + 角度偏差 */
            add_noise_i(e, 0, vw_noise_raw(VW_NOISE_ORIENT));
            break;
        case PS_TYPE_MAGNETIC_FIELD:
            e->data.f[0] = (float) (-g_mag_h * sin(theta_w));
            e->data.f[1] = (float) (g_mag_h * cos(theta_w));
            e->data.f[2] = (float) (-g_mag_h * g_mag_dip);
            /* 磁场逐轴定标：真机静止实测 σ ≈ 0.21 / 0.12 / 0.32 µT（同一机型 19s 探针窗口），
             * 默认 σ 即取该值（见 vw_noise.c）；Calibration 页会按本机实测覆盖。 */
            add_noise_xyz(e, VW_NOISE_MAG);
            break;
        case PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED:
            e->data.f[0] = (float) (-g_mag_h * sin(theta_w) + g_mag_bias_x);
            e->data.f[1] = (float) (g_mag_h * cos(theta_w) + g_mag_bias_y);
            e->data.f[2] = (float) (-g_mag_h * g_mag_dip);
            e->data.f[3] = (float) g_mag_bias_x;
            e->data.f[4] = (float) g_mag_bias_y;
            e->data.f[5] = 0.0f;
            /* 磁场逐轴定标：真机静止实测 σ ≈ 0.21 / 0.12 / 0.32 µT（同一机型 19s 探针窗口），
             * 默认 σ 即取该值（见 vw_noise.c）；Calibration 页会按本机实测覆盖。 */
            add_noise_xyz(e, VW_NOISE_MAG);
            break;
        case PS_TYPE_GRAVITY:
            /* 重力只含恒定分量：走路的周期分量在 LINEAR_ACCELERATION 里，
             * 两者相加正好等于 ACCELEROMETER（真机的物理关系） */
            e->data.f[0] = 0.0f;
            e->data.f[1] = 0.0f;
            e->data.f[2] = 9.81f;
            add_noise_xyz(e, VW_NOISE_GRAVITY);
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
            add_noise_xyz(e, VW_NOISE_ACCEL);
            break;
        }
        case PS_TYPE_LINEAR_ACCELERATION: {
            double gx, gy, gz;
            gait_accel(now, &gx, &gy, &gz);
            e->data.f[0] = (float) gx;
            e->data.f[1] = (float) gy;
            e->data.f[2] = (float) gz;
            add_noise_xyz(e, VW_NOISE_LINEAR);
            break;
        }
        case PS_TYPE_ROTATION_VECTOR:
        case PS_TYPE_GAME_ROTATION_VECTOR:
        case PS_TYPE_GEOMAGNETIC_ROTATION_VECTOR: {
            /*
             * 旋转矢量吃波动的方式**和向量类不同**：加在**半角**上（参考量 π）。
             * 直接按分量缩放会把四元数变成非单位长度 —— 客户端 `getRotationMatrixFromVector` 会
             * 拿到一个不是旋转的"旋转矢量"，姿态整体跑偏，比不抖更糟。
             */
            double half = theta_w / 2.0;   /* 同一个角度偏差，四元数仍是单位四元数 */
            e->data.f[0] = 0.0f;
            e->data.f[1] = 0.0f;
            e->data.f[2] = (float) (-sin(half));
            e->data.f[3] = (float) cos(half);
            add_noise_xyz(e, VW_NOISE_ROTVEC);
            break;
        }
        case PS_TYPE_GYROSCOPE:
            e->data.f[2] = (float) gyro_z(now);
            break;
        case PS_TYPE_GYROSCOPE_UNCALIBRATED:
            e->data.f[2] = (float) (gyro_z(now) + g_gyro_drift_z);
            e->data.f[3] = (float) g_gyro_drift_x;
            e->data.f[4] = (float) g_gyro_drift_y;
            e->data.f[5] = (float) g_gyro_drift_z;
            break;
        default:
            break;
    }
    /*
     * 按组波动（角度与指南针侧）：向量类各分量叠加同一个绝对偏差 `dev × 参考量`。
     * 用参考量而不是逐值百分比的理由见 vw_wobble.c 文件头（零基准量会永远不抖、
     * 角度量会变成"朝向越大抖得越狠"）。旋转矢量在它自己的分支里处理，这里 dims = 0。
     */
    {
        int wdims = vw_wobble_dims(e->type);
        if (wdims > 0 && wdev != 0.0) {
            if (is_mag) {
                /*
                 * 磁场**只抖幅度**：按 (1 + dev/|H|) 缩放整条矢量。
                 * 逐分量加绝对偏差会让**方向**被噪声/漂移主导 —— 实测那样做时罗盘角 |Δ| 中位 61°
                 * （最坏 83°），而把原始序列打出来看真实只有 ±0.2°。方向交给方位角（见 theta_w）。
                 */
                float k = (g_mag_h > 0.0) ? (float) (1.0 + wdev / g_mag_h) : 1.0f;
                for (int i = 0; i < 3; i++) e->data.f[i] *= k;
            } else {
                /* wdev 已是**绝对单位**（慢漂全额 ×ref + 逐条 ≤ref/180）⇒ 直接加，别再乘 wref */
                float off = (float) wdev;
                for (int i = 0; i < wdims; i++) e->data.f[i] += off;
            }
        }
    }
    /*
     * 陀螺（实测驱动）：真机三轴都有噪声，静止实测 σ≈0.001 rad/s；x/y 只体现零偏与噪声，
     * z 是转弯角速度 + 零偏 + 噪声。
     *
     * **零偏**：真机陀螺的零参考物理上就是 0，所以静止窗口的实测中位数就是它的零偏；
     * 该量必然存在且逐机不同，由 Calibration 页按实测中位数写入（默认 0）。
     */
    if (e->type == PS_TYPE_GYROSCOPE || e->type == PS_TYPE_GYROSCOPE_UNCALIBRATED) {
        e->data.f[0] = vw_noise_raw(VW_NOISE_GYRO_BIAS);
        e->data.f[1] = vw_noise_raw(VW_NOISE_GYRO_BIAS + 1);
        e->data.f[2] += vw_noise_raw(VW_NOISE_GYRO_BIAS + 2);
        add_noise_xyz(e, VW_NOISE_GYRO);
    }

    if (type_uses_accuracy(e->type)) {
        *((uint8_t *) &e->data.f[3]) = SENSOR_STATUS_ACCURACY_HIGH;
    }
}

/*
 * 两个消费者（poll 出口 / 运行时泵）共用同一个生成器与同一条时间轴，而各自只该拿到自己那批类型
 * —— 直接"生成后过滤"会把对方的丢掉（旧实现就是这么漏事件的：实测"步数改走 poll"只到 ~48 步/分）。
 * 这里用一个**延迟队列**：不属于本次请求的事件先存起来，等对方来取时优先放出去（旧的在前，顺序天然正确）。
 */
#define DEFER_CAP 96
static portal_sensor_event_t g_defer[DEFER_CAP];
static int g_defer_n = 0;
static long long g_defer_dropped = 0;

int vw_defer_stats(int *pending, long long *dropped) {
    pthread_mutex_lock(&g_lock);
    if (pending) *pending = g_defer_n;
    if (dropped) *dropped = g_defer_dropped;
    pthread_mutex_unlock(&g_lock);
    return g_defer_n;
}

/** @param want_poll 0=只要运行时通道那批（非 poll 类型） 1=只要 poll 类型 2=全都要 */
/**
 * 下一个"该出事件的时刻"（纳秒；0 = 当前没有任何到点的源）。
 *
 * 给 Java 泵用：**睡到下一个到点时刻**，事件因此是被推出去的，而不是被固定节拍轮询出来的。
 * 只读扫描，不改状态（真正的相位推进在 [vw_generate] 里）。
 */
long long vw_next_due_ns(long long now_nanos) {
    long long best = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < STEP_QUEUE_CAP; i++) {
        if (!g_steps_q[i].used) continue;
        long long ts = g_steps_q[i].ts;
        if (ts <= now_nanos) ts = now_nanos;            /* 已经到点：立刻 */
        if (best == 0 || ts < best) best = ts;
    }
    for (int c = 0; c < MAX_CHANNELS; c++) {
        vw_channel_t *ch = &g_chan[c];
        if (!ch->known || ch->def_period_ns <= 0) continue;
        if (!channel_live(ch, now_nanos)) continue;
        long long due = ch->next_due_ns == 0 ? now_nanos : ch->next_due_ns;
        if (due < now_nanos) due = now_nanos;
        if (best == 0 || due < best) best = due;
    }
    pthread_mutex_unlock(&g_lock);
    return best;
}

int vw_generate(portal_sensor_event_t *out, int cap, long long now_nanos, int want_poll) {
    if (cap <= 0) return 0;
    int n = 0;
    pthread_mutex_lock(&g_lock);

    /* ① 先把上一轮留给本消费者的延迟事件交出（两个消费者各有自己的调用时机） */
    for (int i = 0; i < g_defer_n; ) {
        int is_poll = vw_is_poll_type(g_defer[i].type);
        if (want_poll == 2 || ((want_poll == 1) == (is_poll != 0))) {
            if (n >= cap) break;
            out[n++] = g_defer[i];
            memmove(&g_defer[i], &g_defer[i + 1], sizeof(g_defer[0]) * (size_t) (g_defer_n - i - 1));
            g_defer_n--;
        } else {
            i++;
        }
    }

    /* ② 未激活/无状态：**不能把已经从延迟队列取进 out 的事件发出去**（总开关关着就是凭空多
     *    一份数据），但也不能直接 return 0 —— 那样事件既不发也不计 dropped，账实不符。 */
    if (!g_active || !g_have_state) {
        if (n > 0) {
            g_dropped += n;
            n = 0;
        }
        pthread_mutex_unlock(&g_lock);
        return 0;
    }

    /*
     * ③ **多路归并**（取代旧的固定栅格循环）：
     *    每轮挑"最早到点"的那个源（周期通道的 next_due_ns / 步事件队列头），把世界状态
     *    **惰性推进到它的到点时刻**，再按该时刻取值发事件。
     *
     *    为什么这样更自然：事件的时间戳就是它自己到点的那一刻，相位在每个通道里自由累加
     *    （框架采用值常不是任何基准的整数倍，实测 66.7ms），不存在"先量子化到栅格点、再给
     *    时间戳加抖动去掩盖指纹"这层伪装；合并顺序天然保证时间戳全局不回退。
     */
    int budget = MAX_EVENTS_PER_CALL;
    while (budget-- > 0) {
        int step_idx = -1;
        long long step_due = 0;
        long long best_due = 0;
        int best_ch = -1;

        /* 步事件：只挑属于本次消费者的；不属于的**留在队列里**等对方来取
         * （旧实现先清 used 后判归属，两处丢事件，host 回归测试抓过）。 */
        for (int i = 0; i < STEP_QUEUE_CAP; i++) {
            if (!g_steps_q[i].used || g_steps_q[i].ts > now_nanos) continue;
            if (want_poll != 2 &&
                ((want_poll == 1) != (vw_is_poll_type(PS_TYPE_STEP_COUNTER) != 0))) {
                continue;
            }
            if (step_idx < 0 || g_steps_q[i].ts < step_due) {
                step_idx = i;
                step_due = g_steps_q[i].ts;
            }
        }

        /* 周期通道：静默的相位作废（恢复时重锚），追不上的重锚并记账 */
        for (int c = 0; c < MAX_CHANNELS; c++) {
            vw_channel_t *ch = &g_chan[c];
            if (!ch->known || ch->def_period_ns <= 0) continue;
            if (!channel_live(ch, now_nanos)) {
                ch->was_live = 0;
                ch->next_due_ns = 0;
                continue;
            }
            long long period = channel_period_ns(ch);
            if (ch->next_due_ns == 0) ch->next_due_ns = now_nanos;   /* 首次/刚恢复：立即出，不补旧账 */
            if (ch->next_due_ns < now_nanos - period * 2) {
                long long skipped = (now_nanos - ch->next_due_ns) / period;
                g_dropped += skipped;
                ch->next_due_ns = now_nanos;
            }
            if (ch->next_due_ns > now_nanos) continue;
            if (best_ch < 0 || ch->next_due_ns < best_due) {
                best_ch = c;
                best_due = ch->next_due_ns;
            }
        }

        if (step_idx < 0 && best_ch < 0) break;      /* 没有到点的源 */

        long long t;
        if (step_idx >= 0 && (best_ch < 0 || step_due <= best_due)) {
            t = step_due;
        } else {
            t = best_due;
            step_idx = -1;
        }
        vw_advance_to(t);   /* 世界状态（方位平滑/微抖/步态相位）按变步长推进到该时刻 */

        if (step_idx >= 0) {
            if (n + 2 > cap) {
                g_steps_q[step_idx].used = 0;
                g_dropped += 2;
                continue;
            }
            long long ts = step_ts(step_due, now_nanos);
            if (g_last_step_emit_ts != 0 && ts - g_last_step_emit_ts < 100000000LL) {
                g_step_short_gap++;
                LOGI("step emitted gap=%lldms 排定差=%lldms（发出 %lld vs %lld）"
                     "—— 客户端会看到两条步事件几乎同时",
                     (ts - g_last_step_emit_ts) / 1000000,
                     (step_due - g_last_step_due) / 1000000,
                     g_last_step_emit_ts, ts);
            }
            g_last_step_emit_ts = ts;
            g_last_step_due = step_due;
            long long cnt = g_steps_q[step_idx].count;
            g_steps_q[step_idx].used = 0;
            /* 计数器与检测器 = **同一次步事件、同一时间戳**：一个在涨而另一个不响，
             * 会被交叉比对看出来。 */
            portal_sensor_event_t *ec = &out[n++];
            memset(ec, 0, sizeof(*ec));
            ec->version = (int32_t) sizeof(portal_sensor_event_t);
            ec->type = PS_TYPE_STEP_COUNTER;
            ec->timestamp = ts;
            /* **int64 视图，不是 float**：真机 HAL 把步数写在
             * `sensors_event_t.u64.step_counter`（占满 data[0..1]），框架与客户端 Java 侧都按
             * int64 读；按 float 写会让客户端把 float 的位模式当成步数。 */
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
            continue;
        }

        {
            vw_channel_t *ch = &g_chan[best_ch];
            long long period = channel_period_ns(ch);
            ch->next_due_ns += period;
            ch->was_live = 1;
            portal_sensor_event_t ev;
            fill_event(&ev, ch, 0);
            ev.timestamp = jitter_ts(best_due, period, now_nanos);
            fill_values(&ev, best_due);   /* 数值采样在"到点时刻"，时间戳在它附近抖动 */

            if (want_poll != 2 && ((want_poll == 1) != (vw_is_poll_type(ev.type) != 0))) {
                /* 不属于本次请求的那批：留给另一个消费者（满了丢最旧，与 FIFO 溢出一致） */
                if (g_defer_n >= DEFER_CAP) {
                    memmove(&g_defer[0], &g_defer[1], sizeof(g_defer[0]) * (DEFER_CAP - 1));
                    g_defer_n = DEFER_CAP - 1;
                    g_defer_dropped++;
                    g_dropped++;
                }
                g_defer[g_defer_n++] = ev;
                continue;
            }
            if (ch->batch_ns > 0) {
                /* 批量上报：攒在通道自己的小队列里，到批量边界一次性放出去
                 * （真机是 HAL 在 FIFO 里攒够了再上报）。满了丢**最旧**的并记账。 */
                int pcap = (int) (sizeof(ch->pend) / sizeof(ch->pend[0]));
                if (ch->pend_n >= pcap) {
                    memmove(&ch->pend[0], &ch->pend[1], sizeof(ch->pend[0]) * (size_t) (pcap - 1));
                    ch->pend_n = pcap - 1;
                    g_dropped++;
                }
                ch->pend[ch->pend_n++] = ev;
                if (ch->batch_due_ns == 0) ch->batch_due_ns = now_nanos + ch->batch_ns;
                continue;
            }
            if (n >= cap) {
                g_dropped++;
                continue;
            }
            out[n++] = ev;
        }
    }

    /* ④ 批量边界到了：把攒下的一次性放出（各自保留自己的时间戳 = 一次上报多帧） */
    for (int c = 0; c < MAX_CHANNELS; c++) {
        if (g_chan[c].batch_ns <= 0 || g_chan[c].pend_n == 0) continue;
        if (g_chan[c].batch_due_ns > 0 && now_nanos < g_chan[c].batch_due_ns) continue;
        for (int k = 0; k < g_chan[c].pend_n; k++) {
            if (n >= cap) {
                g_dropped++;
                continue;
            }
            out[n++] = g_chan[c].pend[k];
        }
        g_chan[c].pend_n = 0;
        g_chan[c].batch_due_ns = now_nanos + g_chan[c].batch_ns;
    }

    /*
     * ⑤ 队尾清扫**不能**按"ts <= now 就清"：上面刻意把**属于另一个消费者**的步事件留在
     *    队列里等对方来取，按 due 一律清掉等于把那批事件又丢一次、而且一条都不计数
     *    （两个消费者谁先跑到，谁就把对方那份吃掉 —— host 回归测试抓过这个 bug）。
     *    这里只清"早就过期到不可能再送达"的：保留 2s 窗口。
     */
    long long stale_before = now_nanos - STALE_WINDOW_NS;
    for (int i = 0; i < STEP_QUEUE_CAP; i++) {
        if (g_steps_q[i].used && g_steps_q[i].ts < stale_before) {
            g_steps_q[i].used = 0;
            g_dropped += 2; /* 计数器 + 检测器 */
        }
    }

    g_emitted += n;
    pthread_mutex_unlock(&g_lock);
    return n;
}
