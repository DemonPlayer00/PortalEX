/*
 * 虚拟传感器世界（系统框架侧）。
 *
 * 与 app 端 SystemSensorManagerHook 是**同一套运动学模型的两个消费者**：
 * 那边在应用进程改写真实回调，这边在 system_server 的 HAL 边界自产事件。
 * 数值口径必须一致（同一虚拟方位派生出旋转/地磁/重力/角速度/加速度），
 * 公式以 SystemSensorManagerHook + FakeLoc 为参照。
 *
 * 本文件不依赖 STL / libc++：注入 system_server 的库越"薄"越安全。
 */
#ifndef PORTAL_VIRTUAL_WORLD_H
#define PORTAL_VIRTUAL_WORLD_H

#include <stddef.h> /* size_t：只含 stdint.h 时在 host（glibc）上编不过，Bionic 侥幸通过 */
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 与平台 sensors_event_t 逐字节一致（ABI 常量，见 hardware/sensors.h）：
 * version(4) sensor(4) type(4) reserved0(4) timestamp(8) data(64) flags(4) reserved1(12) = 104 */
typedef struct {
    int32_t version;
    int32_t sensor;
    int32_t type;
    int32_t reserved0;
    int64_t timestamp;
    union {
        float f[16];
        uint64_t u64[8];
    } data;
    uint32_t flags;
    int32_t reserved1[3];
} portal_sensor_event_t;

/* 平台 sensor_t（只用到 handle/type/flags，其余字段保持原位以对齐 ABI） */
typedef struct {
    const char *name;
    const char *vendor;
    int32_t version;
    int32_t handle;
    int32_t type;
    float max_range;
    float resolution;
    float power;
    int32_t min_delay;
    uint32_t fifo_reserved_event_count;
    uint32_t fifo_max_event_count;
    const char *string_type;
    const char *required_permission;
    int32_t max_delay;
    uint32_t flags;
    void *reserved[2];
} portal_sensor_t;

/* ---- 传感器类型（Android Sensor.TYPE_*） ---- */
#define PS_TYPE_ACCELEROMETER 1
#define PS_TYPE_MAGNETIC_FIELD 2
#define PS_TYPE_ORIENTATION 3
#define PS_TYPE_GYROSCOPE 4
#define PS_TYPE_GRAVITY 9
#define PS_TYPE_LINEAR_ACCELERATION 10
#define PS_TYPE_ROTATION_VECTOR 11
#define PS_TYPE_MAGNETIC_FIELD_UNCALIBRATED 14
#define PS_TYPE_GAME_ROTATION_VECTOR 15
#define PS_TYPE_GYROSCOPE_UNCALIBRATED 16
#define PS_TYPE_STEP_DETECTOR 18
#define PS_TYPE_STEP_COUNTER 19
#define PS_TYPE_GEOMAGNETIC_ROTATION_VECTOR 20
#define PS_TYPE_ACCELEROMETER_UNCALIBRATED 35

/** 该类型是否属于本模块接管（自产 + 压制真实值）的集合 */
int vw_owns_type(int32_t type);

/**
 * **按类开关**：关掉的那一侧，[vw_owns_type] 直接判"不归我们管" ⇒ 真实事件原样放行
 * ⇒ 那一侧一个事件都不会被注入。默认两侧都开（与拆分前逐位一致）。
 * @param cadence     步频侧（TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR）
 * @param orientation 角度与指南针侧（加速度/陀螺/磁场/朝向）
 */
void vw_set_class_enable(int cadence, int orientation);

/** 该 type 所属侧当前是否开着（不可识别的 type 恒为 0） */
int vw_class_enabled(int32_t type);

/** 记一次"该 type 的真实事件被我们压制"（按类累计，见 vw_suppressed_counts） */
void vw_note_suppressed_class(int32_t type);

/** 读按类压制计数：关掉的一侧**停止增长**即"那一侧没被接管"的运行时证据 */
void vw_suppressed_counts(long long *cadence, long long *orientation);

/** 进程内一次性初始化（虚拟世界的固有常量：场强/磁倾角/bias/漂移） */
void vw_init(void);

/** 总开关：关闭时 vw_generate 恒返回 0（真实事件原样放行） */
void vw_set_active(int active);
int vw_is_active(void);

/** Java 侧状态快照（system_server 的 FakeLoc）：速度/朝向/移动/累计步数 */
void vw_update_state(double speed, double azimuth_deg, int moving, long long steps,
                     long long now_nanos);

/** 已知的 type → handle 映射（来自 HAL 的 getSensorsList 或真实事件观测） */
void vw_set_handle(int32_t type, int32_t handle, uint32_t sensor_flags);

/**
 * 用**框架自己的传感器表**（system_server 侧 `SensorManager.getSensorList` +
 * 隐藏 `Sensor.getHandle()`）播种映射：这类映射不依赖真实事件，因此像步数计数器
 * 这种"不走路就没有事件"的 on-change 传感器也能拿到 handle。
 *
 * 语义与 [vw_set_handle] 不同：只在未知时写入，已知且不一致时**不覆盖**，
 * 只打一条告警——真实事件携带的 handle 是框架实际分发用的那个，更可信。
 */
void vw_seed_handle(int32_t type, int32_t handle, uint32_t sensor_flags);

/**
 * 生成截至 [now_nanos] 应发出的全部事件，写入 [out]（容量 [cap]）。
 * 事件按时间升序、跨类型交织（应用侧按时间戳算 dt 不会出现负值）。
 * 积压超过容量时**丢弃最旧的**，保证最新数据的时间戳仍准确。
 * @return 写入条数
 */
/**
 * 生成截至 [now_nanos] 应发的事件。
 * @param want_poll 0=只要运行时通道那批（非 poll 类型） 1=只要 poll 类型 2=全都要
 *        —— 两个消费者共用同一条时间轴，各自只取自己那批；对方的那批进延迟队列等下次取走。
 */
int vw_generate(portal_sensor_event_t *out, int cap, long long now_nanos, int want_poll);

/** 该类型是否走 poll 路径投递（3 值类型默认走 poll：运行时 JNI 塞不下精度字段 data[3]） */
int vw_is_poll_type(int32_t type);

/** 步数两条流是否改走 poll 路径（历史开关，默认关） */
void vw_set_steps_via_poll(int on);

/** 是否启用"3 值类型走 poll"（默认 1；debug.portalex.accviapoll=0 可关） */
void vw_set_acc_via_poll(int on);

/** 当前是否有任何类型走 poll 路径（决定 poll 出口要不要注入） */
int vw_poll_types_enabled(void);

/**
 * 下一个"该出事件的时刻"（纳秒；0 = 当前没有到点的源）。
 * Java 泵睡到该时刻即可 —— 事件是被推出去的，不再由固定栅格轮询出来。
 */
long long vw_next_due_ns(long long now_nanos);

/** 当前**最细的活跃周期**（纳秒；0 = 没有周期通道）与延迟队列统计（诊断） */
int vw_finest_period_dbg(void);
int vw_defer_stats(int *pending, long long *dropped);

/** 生成期间的统计（诊断用） */
void vw_stats(long long *emitted, long long *dropped, long long *suppressed);

/** 记一次"真实事件被压制"（诊断用） */
void vw_note_suppressed(long long n);

/** 近 5 秒实际发出的步事件换算成步/分（诊断页用：与"意图步频"对照） */
int vw_step_rate_per_min(long long now_nanos);

/**
 * 最近一次发给客户端的 STEP_COUNTER 值 —— 也就是应用按"间歇读系统开机总步数"算步频时
 * 读到的那个数。诊断用（Test 页"开机总步数"一行），不参与任何生成逻辑。
 */
long long vw_step_counter_value(void);

/** 最近观测到的**真实** STEP_COUNTER 值（-1 = 未知）：模拟接管时拿它做起点，避免跳变 */
long long vw_real_step_counter(void);

/**
 * 记一条真实事件（当前只用于取真实计数器值）。
 *
 * `data` 是**事件原始 16 个 float 槽的指针**，不是"第一个 float 值"：步数计数器在真机上
 * 写的是 `sensors_event_t.u64.step_counter`（int64，占满 data[0..1]），按 float 读只能拿到
 * 约 1e-41 的非规格化数（实测真机步数事件的 float 视图 = 0.000）。
 */
void vw_note_real_event(int32_t type, const float *data);

/**
 * 「按应用期望出数据」：把框架观测到的采用速率与活跃状态灌进周期通道。
 *
 * 真机 HAL 按"所有请求里最快那个"出力、框架原样广播给所有人；这里照同一个模型走。
 * `period_ns` = 框架 dump 的 `selected`（0 = 未指定 ⇒ 用默认周期）；`active` = 是否有人订阅。
 * 没人订阅（且近期也没有真实事件）时该类型**静默**——真机 HAL 没被启用时同样一条都不出。
 * 只对周期通道生效；步数两条流是 on-change，不受影响。
 */
void vw_set_channel_hint(int32_t type, long long period_ns, long long batch_ns, int active);

/** 先把所有栅格通道标成不活跃，再按 dump 灌活跃者（缺席即静默，见实现注释） */
void vw_clear_channel_hints(void);

/** 各周期通道的生效速率（诊断字符串："1:20ms 2:40ms(idle) …"），返回写入长度 */
int vw_dump_rates(char *out, size_t out_size);

/*
 * ---- 噪声档（Calibration 页可编辑） ----
 *
 * 逐轴建模：每个轴一个**标准差 σ**，逐事件按 **高斯分布** 生成动态值
 * （σ 是"每样本"的量，采集时的采样率必须与注入栅格同量级）。
 * 另外陀螺有**逐轴零偏 μ**：真机陀螺的零参考物理上就是 0，所以静止窗口的实测中位数
 * 就是它的零偏（该量必然存在、逐机不同），由 Calibration 页写入。
 *
 * 加速度/重力/线性加速度/磁场的中位数**不注入**：那些中位数里混着手机姿态与环境地磁的
 * 直流分量（平放时 accel z 的中位数就是 9.81），叠加会把模型打坏 —— 它们只作为统计参照
 * 由 App 侧显示。索引与 Kotlin 侧 [SensorNoise] 的 profile 布局**逐项一致**。
 */
#define VW_NOISE_COUNT 20
#define VW_NOISE_GYRO 0     /* 0..2  陀螺 σ (x,y,z)，rad/s */
#define VW_NOISE_GYRO_BIAS 3 /* 3..5  陀螺零偏 μ (x,y,z)，rad/s —— **唯一被注入的中位数**，可为负 */
#define VW_NOISE_ACCEL 6    /* 6..8  加速度计 σ (x,y,z)，m/s² */
#define VW_NOISE_GRAVITY 9  /* 9..11 重力 σ (x,y,z)，m/s² */
#define VW_NOISE_LINEAR 12  /* 12..14 线性加速度 σ (x,y,z)，m/s² */
#define VW_NOISE_MAG 15     /* 15..17 磁场 σ (x,y,z)，µT */
#define VW_NOISE_ORIENT 18  /* 方向角 σ，° */
#define VW_NOISE_ROTVEC 19  /* 旋转矢量 σ */
#define VW_NOISE_BIAS_BASE 3
#define VW_NOISE_BIAS_END 5

/** 设置噪声档第 [index] 项（σ 项钳到 ≥0；零偏项允许负值；|值| > 50 丢弃）。 */
void vw_set_noise(int index, float amp);

/** 读回噪声档（最多 [count] 个） */
void vw_get_noise(float *out, int count);

/** 噪声档短字符串（诊断/回显用），返回写入长度 */
int vw_dump_noise(char *out, size_t out_size);

/*
 * ---- 按组波动（两个功能页各两条参数） ----
 *
 * 与噪声档是**两层**：噪声档（上面）是"每条事件的传感器本底噪声"，这里是叠加在它之上的
 * **慢漂**（秒级来回走）与**逐条随机**。两条参数都是相对量（0..1 的分数，UI 上是百分比）：
 *   · amp 「波动强度」：慢漂半幅（时间常数 1.5s 的一阶低通随机游走 × amp）
 *   · rnd 「随机区间」：逐条事件均匀随机半宽（±rnd）
 * 施加口径 = `dev × 该类型的参考量`（不是逐值百分比），理由见 vw_wobble.c 文件头；
 * 步频侧没有分量，作用在**步间隔**上（interval × (1 + dev)）。
 *
 * 0 值是**逐位兼容**的：两条都为 0 时不碰随机数、不做任何算术，输出与未引入本功能时完全一致。
 */
#define VW_WOB_GROUP_CADENCE 0     /* 步频侧：TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR */
#define VW_WOB_GROUP_ORIENTATION 1 /* 角度与指南针侧：加速度/重力/线性/陀螺/磁场/方向角/旋转矢量 */
#define VW_WOB_GROUP_COUNT 2

/** 设置某一组的波动参数（[amp]/[rnd] 为 0..1 的分数；超出钳位，NaN 归 0）。 */
void vw_set_group_wobble(int group, float amp, float rnd);

/** 读回某一组的波动参数（0..1 的分数） */
void vw_get_group_wobble(int group, float *amp, float *rnd);

/** 波动参数短字符串（诊断/回显用），返回写入长度 */
int vw_dump_wobble(char *out, size_t out_size);

/** 累计发出的步事件数（一步计一次，counter/detector 两条事件算一步） */
long long vw_step_events_total(void);

/** 步态波形口径的短描述（诊断用，常量字符串） */
const char *vw_gait_describe(void);

/** 把已学到的 type→handle 映射写成 "1:0xb 2:0x15 ..."，返回写入长度 */
int vw_dump_handles(char *out, size_t out_size);

#ifdef __cplusplus
}
#endif

#endif /* PORTAL_VIRTUAL_WORLD_H */
