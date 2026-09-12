package moe.fuqiuluo.xposed.utils

/**
 * PortalEX 的**配置面**：全部开关与可调参数（谁下发、走哪条链路，见 `ConfigSync` /
 * `RemoteCommandHandler` 的 `Pref` 分支）。
 *
 * 为什么单独拆出来：这些字段原先和「虚拟世界运行时状态」（坐标/速度/朝向/游走相位）
 * 混在同一个 `object FakeLoc` 里，读代码时无法一眼分清「这是用户设置」还是「这是本帧
 * 的世界状态」——而两者生命周期完全不同：配置只在握手 / 改设置 / 命令下发时写，
 * 世界状态每帧都在变。
 *
 * 约定：
 *  · **只存状态，不做校验/夹取**（校验在下发侧与各自的 calc 函数里）；
 *  · 对外公开名字仍是 `FakeLoc.xxx`（[FakeLoc] 是门面，getter/setter 直接转发到这里）；
 *  · 语义文档随字段搬到这里（原注释逐字保留）；[FakeLoc] 侧只留门面；
 *  · `@Volatile` 标在**这里的真实字段**上 —— 门面属性没有 backing field，也不该有注解。
 */
internal object LocConfig {
    /**
     * 是否允许打印日志
     */
    var enableLog = true

    /**
     * 是否允许打印调试日志
     */
    var enableDebugLog = true

    /**
     * 模拟定位服务开关
     */
    @Volatile
    var enable = false

    /**
     * 模拟Gnss卫星数据开关
     */
    @Volatile
    var enableMockGnss = false

    /**
     * 模拟WLAN数据
     */
    @Volatile
    var enableMockWifi = false

    /**
     * Binder 外周传感器模拟（**默认开**）：
     * 由 system_server 侧原生 hook（[moe.fuqiuluo.xposed.hooks.sensor.BinderSensorMock]）
     * 在系统框架层接管外周传感器——**不向目标应用注入任何 hook**。
     *
     * ⚠️ 默认开的安全前提只有一条：**开机阶段绝不装载**。system_server 的
     * `handleLoadPackage("android")` 只做登记（[moe.fuqiuluo.xposed.hooks.sensor.BinderSensorMock.registerAtBoot]），
     * dlopen + 改写 `libsensorservice.so` 一律推迟到**模拟会话启动**时
     * （[moe.fuqiuluo.xposed.hooks.sensor.BinderSensorMock.onSimulationChanged]）。
     * 违反这条会锁死开机：system_server 永久停在 `Waiting for service 'sensorservice'`，
     * 开机动画永不结束（2026-09-12 在 MI6/LineageOS 15 上实测并 A/B 证实）。改这条链路前先读那段 KDoc。
     *
     * 关闭时该路径完全不安装（不加载 .so、不起线程），行为与旧版本逐位一致。
     */
    @Volatile
    var enableBinderSensorMock = true

    /** 注入栅格分辨率（Hz）：0 = 自动跟随框架采用值；非 0 时固定为 1e9/该值（原生层钳 2.5~50ms） */
    var sensorGridHz = 0

    /**
     * 注入噪声档（Calibration 页）：[SensorNoise.COUNT] 个半宽，索引见 [SensorNoise]。
     * 默认 = [SensorNoise.DEFAULTS]（与原生硬编码默认逐位一致）。
     */
    @Volatile
    var noiseProfile: FloatArray = SensorNoise.DEFAULTS.copyOf()

    /**
     * 把噪声档下发给原生层（system_server 内才有效果；其它进程只是镜像值）。
     * 单项失败不影响其它项 —— 逐项调用，原生层自己丢弃非法值。
     */
    fun applyNoiseProfile(native: (Int, Float) -> Unit) {
        val values = SensorNoise.sanitize(noiseProfile)
        noiseProfile = values
        for (i in 0 until SensorNoise.COUNT) native(i, values[i])
    }

    /**
     * 原生注入层是否已成功装载（由 BinderSensorMock 在 system_server 内回填，只读诊断用）：
     * 装载失败时为 false，此时不改变任何既有行为。
     */
    @Volatile
    var binderSensorNativeReady = false

    /**
     * 融合定位（fused provider）处置方式：见 [FusedMode]（0=拒绝 1=放行 2=伪装）。
     *
     * 取代原来的布尔 `disableFusedLocation`：那个开关只能表达"拒绝/不拒绝"，
     * 说不出"放行但不干预"与"让它跑、结果换成我们的"这两种完全不同的语义。
     * 默认 [FusedMode.DISGUISE]（在检测到融合定位的设备上由 App 初始化为伪装）。
     */
    @Volatile
    var fusedMode: Int = FusedMode.DEFAULT
    var disableNetworkLocation = true

    var disableRequestGeofence = false
    var disableGetFromLocation = false

    /**
     * 是否允许AGPS模块（当前没什么鸟用）
     */
    var enableAGPS = false

    /**
     * 是否允许NMEA模块
     */
    var enableNMEA = false

    /**
     * 是否隐藏模拟位置
     */
    var hideMock = true

    /**
     * may cause system to crash
     */
    var hookWifi = true

    /**
     * 将网络定位降级为Cdma
     */
    var needDowngradeToCdma = true
    var isSystemServerProcess = false

    /**
     * 模拟最小卫星数量
     */
    var minSatellites = 12

    /**
     * 反定位复原加强（启用后将导致部分应用在关闭Portal后需要重新启动才能重新获取定位）
     */
    var loopBroadcastLocation = false

    // ---- 由虚拟世界读取的量（读在 [VirtualWorld]，写在各设置入口）----

    /** 速度抖动幅度（m/s，绝对值）：注入 Location.speed 时在模拟速度上叠加 ±该值 */
    var speedAmplitude = 0.3

    /**
     * 步频倍率（设置页「步频倍率」）：默认 1.0 = 逐位保持原公式；非 1 时按倍率微调
     * "步频 ↔ 速度"的关系（例如 1.1 = 同样速度下步频快 10%）。
     * 作用在**基础值 clamp 之后**，并再钳进 30~300 的物理合理区间——
     * 这样默认值下输出与改动前完全一致（不会因为换了钳位区间而改变既有行为）。
     */
    var cadenceScale = 1.0

    /**
     * 模拟会话期间的速度保底（m/s）。
     *
     * 为什么需要：真机语义下**静止定位没有有效航向**（speed==0 ⇒ `hasBearing()` 无意义），
     * 应用会据此放弃航向——实测目标应用（步道乐跑）在 MI6 上表现为"停下即指针归 0、恒指北"，
     * 而我们注入的位置本身是对的（`vel=0.0 bear=295°` 稳定）。保底一个极小速度，
     * 让 `bearing` 保持"有效"，指针停在最后朝向而不是归零。
     * 值要小到不像在走（0.3 m/s ≈ 1.08 km/h），但足以让 hasBearing 成立。
     */
    var speedFloor = 0.3

    /**
     * 模拟定位精度（米）：既是上报的 hAcc，也是注入坐标偏移的上限（见 [VirtualWorld.jitterLocation]）。
     * 负值按绝对值处理（设置页允许手输，取绝对值比报错友好）。
     */
    var accuracy = 25.0f
        set(value) {
            field = if (value < 0) {
                -value
            } else {
                value
            }
        }
}
