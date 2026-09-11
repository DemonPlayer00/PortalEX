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
int vw_generate(portal_sensor_event_t *out, int cap, long long now_nanos);

/** 生成期间的统计（诊断用） */
void vw_stats(long long *emitted, long long *dropped, long long *suppressed);

/** 记一次"真实事件被压制"（诊断用） */
void vw_note_suppressed(long long n);

#ifdef __cplusplus
}
#endif

#endif /* PORTAL_VIRTUAL_WORLD_H */
