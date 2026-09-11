package moe.fuqiuluo.xposed.hooks.sensor

/**
 * `BinderSensorNative.install(LongArray)` 的**索引契约**（Kotlin 侧唯一定义处）。
 *
 * 为什么单独立一个对象：这套数组以前是"两边各自记顺序"——Kotlin 用 `longArrayOf(...)`
 * 平铺 7 个值、native 用 `o[0]..o[6]` 逐个读，注释里写的还是 6 项。顺序错了**不会崩**，
 * 而是"槽位改写到了别处"或者干脆什么也没装上（功能静默失效），这类漂移只能靠契约挡住。
 *
 * native 侧对应 `portal_sensor.c` 的 `VW_INSTALL_OFFSET_*`（同序同义）；
 * 任何增删项都必须同时改这两处 + [PortalProtocolTest] 式的值契约测试。
 */
object InstallOffsets {
    const val RELRO_ADDR = 0
    const val RELRO_SIZE = 1
    const val POLL_AIDL = 2
    const val POLL_FMQ_AIDL = 3
    const val POLL_HIDL = 4
    const val POLL_FMQ_HIDL = 5
    /** 采样率观测槽（enableDisable）：0 = 本 ROM 没解析到，只是拿不到频率，不影响注入 */
    const val ENABLE_DISABLE = 6

    const val COUNT = 7
}
