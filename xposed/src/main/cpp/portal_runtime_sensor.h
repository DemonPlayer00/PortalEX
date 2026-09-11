/*
 * 运行时传感器（Runtime Sensor）接入层 —— system_server 内的第二条注入路径。
 *
 * 为什么要它
 * ----------
 * 现有的注入挂在 HAL 包装器的事件出口（poll / pollFmq）上：数据由我们自己生成，
 * 但**投递节拍受框架的 HAL 轮询支配** —— HAL 只为"当前被订阅的传感器"投递，
 * 若目标应用只订阅 on-change 类型的步数传感器，框架就长时间阻塞，我们的事件只能
 * 零星到达（实测 emitted:dropped = 2108:82039）。
 *
 * 框架内为"没有 HAL 背书的传感器"准备的正规机制是运行时传感器：
 *   SensorService::registerRuntimeSensor(sensor_t const&, int deviceId, sp<RuntimeSensorCallback>)
 *   SensorService::sendRuntimeSensorEvent(sensors_event_t const&)
 *   SensorService::unregisterRuntimeSensor(int)
 * 事件由 SensorService::RuntimeSensorHandler 自己的线程投递（反汇编确认其循环只调
 * sendRuntimeSensorEvent 与 Thread::exitPending，完全不碰 HAL poll）——**投递节奏
 * 100% 由我们掌握**，与底层传感器状态无关。
 *
 * 本文件的实现约束（都是踩过的/查过的硬事实）
 * ------------------------------------------
 * 1. 三个 API 是**导出符号**（.dynsym），但都不是静态的：需要 `SensorService*` 实例。
 *    实例从 ServiceManager 的 "sensorservice" 取（AIDL 服务的 Bn 对象就是 SensorService
 *    本身），**取到后必须先校验**：对象 vptr 必须落在 libsensorservice.so 的 .data.rel.ro
 *    区间内，再用一个导出的无害查询方法（isSensorActive）确认行为合理。校验不过就整个放弃。
 * 2. 回调对象不能传空：注册路径对空 sp 有 `cbz` 判空（`registerRuntimeSensor` 里确认），
 *    但 **RuntimeSensor::activate 会直接 `ldr x19,[x0,#0xa0]; ldr x8,[x19]` 解引用** ——
 *    空回调一被客户端使能就段错误。因此必须给一个合法对象。
 * 3. 回调对象的构造方式（从二进制读出，不是猜的）：
 *      · 服务对该指针的用法是 `[ptr] = vptr`（虚调用）且对它调 RefBase::incStrong ——
 *        两件事都作用在**偏移 0**，所以对象就是 `{ vptr; RefBase 载荷 }`；
 *      · 虚表里 **onConfigurationChanged 在 vptr[0]**，析构在 [1]/[2]（框架自己的
 *        匿名 RuntimeSensorCallbackProxy 虚表就是这个布局，已在 .data.rel.ro 中定位）；
 *      · 于是：反射一份 libutils 的 RefBase 构造函数初始化载荷，再把 vptr 指向
 *        **框架那份虚表的副本**（只把 [0] 换成我们的空实现）——RefBase 的其余虚函数
 *        保持框架实现，语义正确。
 * 4. 全程只改我们自己的内存 + 调框架的公开导出 API；不做代码段改写。
 */
#ifndef PORTAL_RUNTIME_SENSOR_H
#define PORTAL_RUNTIME_SENSOR_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 解析并校验 SensorService 实例与运行时传感器 API（**只读**：不注册、不推送）。
 * @param relro_addr/.data.rel.ro 的链接期地址与长度（来自 Java 侧的符号解析，用于校验实例 vptr）
 */
void prs_probe(uintptr_t relro_addr, uintptr_t relro_size, uintptr_t rt_register_off,
               uintptr_t rt_send_off, uintptr_t rt_unregister_off, uintptr_t rt_isactive_off);

/** 探测结果摘要（写进 status() 字符串） */
void prs_describe(char *out, size_t out_size);

/** 实例是否已通过校验 */
int prs_ready(void);

#ifdef __cplusplus
}
#endif

#endif /* PORTAL_RUNTIME_SENSOR_H */
