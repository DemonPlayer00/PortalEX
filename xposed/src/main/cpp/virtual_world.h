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

/** 固定注入栅格（纳秒；0 = 自动跟随采用值）。钳在 2.5ms~50ms */
void vw_set_tick_override(long long ns);

/** 当前栅格（纳秒）与延迟队列统计（诊断） */
int vw_tick_ns_dbg(void);
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
 * 「按应用期望出数据」：把框架观测到的采用速率与活跃状态灌进栅格通道。
 *
 * 真机 HAL 按"所有请求里最快那个"出力、框架原样广播给所有人；这里照同一个模型走。
 * `period_ns` = 框架 dump 的 `selected`（0 = 未指定 ⇒ 用默认栅格）；`active` = 是否有人订阅。
 * 没人订阅（且近期也没有真实事件）时该类型**静默**——真机 HAL 没被启用时同样一条都不出。
 * 只对栅格通道生效；步数两条流是 on-change，不受影响。
 */
void vw_set_channel_hint(int32_t type, long long period_ns, long long batch_ns, int active);

/** 先把所有栅格通道标成不活跃，再按 dump 灌活跃者（缺席即静默，见实现注释） */
void vw_clear_channel_hints(void);

/** 各栅格通道的生效速率（诊断字符串："1:20ms 2:40ms(idle) …"），返回写入长度 */
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
