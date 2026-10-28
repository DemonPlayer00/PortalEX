package moe.fuqiuluo.xposed.hooks.sensor

import android.os.SystemClock
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.hooks.MotionClock
import moe.fuqiuluo.xposed.utils.MotionEngine
import moe.fuqiuluo.xposed.utils.PortalDiag
import moe.fuqiuluo.xposed.utils.StaminaRuntime

/**
 * Binder 外周传感器模拟（**默认开**）—— system_server 侧调度。
 *
 * 与 app 端 [SystemSensorManagerHook] 的分工：
 * - [SystemSensorManagerHook]：**应用进程内**改写真实回调（旧路径，只在 LSPosed
 *   作用域内的应用生效，且需要真实回调存在才能驱动）。
 * - 本类：**只在系统框架侧**工作。它把 FakeLoc 的权威运动学量（速度 / 朝向 / 步数）
 *   周期性地喂给原生注入层，由原生层在传感器 HAL 包装器的 poll 出口自产事件并压制
 *   真实值。目标应用**一个 hook 都不装**也能拿到模拟数据，且不依赖底层传感器是否
 *   在工作。
 *
 * 生命周期：**装机后默认开，但开机阶段什么都不做**（[registerAtBoot] 只登记开关）；
 * 真正的装载发生在**模拟会话启动**时（[onSimulationChanged]），装载后开始推流；
 * 开关关闭时立刻停推并把注入层置为 inactive（真实事件原样放行），下次开会话复用已装载的层。
 *
 * ⚠️ 红线：**不要在 `handleLoadPackage("android")` 阶段装载**。那段代码跑在开机途中，
 * 在 system_server 里 dlopen 并改写 `libsensorservice.so` 会让部分 ROM 永久卡在
 * `Waiting for service 'sensorservice'`（2026-09-12 在 MI6/LineageOS 15 上开机动画永不结束，
 * A/B 已证）。要动这条链路，先读 [registerAtBoot] 与 `LocConfig.enableCadenceMock/enableOrientationMock`。
 */
object BinderSensorMock {

    /** 速度推算窗口（ms）：与位置注入帧同口径 */
    private const val SPEED_WINDOW_MS = 1000L

    /** 失败后的重试间隔（内部 tick 数）：避免每 50ms 刷一次异常日志 */
    private const val FAIL_RETRY_TICKS = 100

    /**
     * 泵的睡眠上限（毫秒）。正常路径**睡到下一个事件到点**（见 [pumpLoop]），
     * 这个上限只兜"原生层暂时没有到点源"和"提示还没回来"的情况。
     */
    private const val PUMP_MAX_SLEEP_MS = 100L

    /** 泵的最小睡眠：事件已经到点时不要空转（避免 100% CPU） */
    private const val PUMP_MIN_SLEEP_MS = 1L

    /**
     * 老 .so 兜底：`nextDueNs` 是新加的 JNI，**旧的注入库没有这个符号**。
     * 模块代码与注入库的重载时机不同（前者随进程、后者随系统启动），过渡期可能一边新一边旧；
     * 一旦取不到就退回旧的 5ms 固定节拍，绝不变成"每次抛异常 + 1ms 空转"。
     */
    @Volatile private var nextDueAvailable = true
    private const val PUMP_FALLBACK_SLEEP_MS = 5L

    @Volatile private var nativeReady = false

    /**
     * 世界静止时的**朝向回灌**节奏（纳秒）。
     *
     * 静止≠没事干：朝向上的"摆动"（±3°、半周期 0.35~0.75s）一直在演化，传感器侧要跟着它
     * 才像真机磁罗盘。投递泵的每一次唤醒都会问一次 `beforePump`，这里只决定"值不值得下发"。
     */
    private const val IDLE_BEARING_PUSH_NANOS = 200_000_000L

    /** 兜底回灌上限（纳秒）：即使什么都没变也要喂一次，免得原生层里的 `now` 无限陈旧 */
    private const val STATE_HEARTBEAT_NANOS = 2_000_000_000L

    /** 静止时速率提示的刷新间隔（纳秒）：没人走路时"按应用期望出数据"不急 */
    private const val RATE_HINT_IDLE_NANOS = 10_000_000_000L

    /** 朝向变化阈值（度）：小于它不必打扰原生层 */
    private const val BEARING_EPS_DEG = 0.3

    /** 上一次真正下发过的快照（变化检测用；[Double.NaN] = 还没发过） */
    private var lastPushLat = Double.NaN
    private var lastPushLon = Double.NaN
    private var lastPushBearing = Double.NaN
    private var lastPushNanos = 0L
    @Volatile private var active = false
    @Volatile private var pumpThread: Thread? = null
    private val pumpStartGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pumpLock = Object()

    /**
     * 速率提示 dump 的**单飞执行器**。
     *
     * `pushHints()` 内部是 `IBinder.dump`（本机约 40KB），不允许在泵的状态推进线程上
     * 同步执行：一旦 binder 回调重入，就会与泵形成环。泵只置脏位/调度，真正的 dump
     * 在这个独立线程里、且同一时刻最多一个。
     */
    private val hintExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "PortalSensorHints").apply { isDaemon = true }
    }
    private val hintInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 重入守卫：任何回调意外回到 beforePump 时直接丢弃这一轮，不让栈继续增长 */
    private val stateAdvancing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 叫醒投递泵：会话/配置/订阅变化时调用（泵的等待会被 notify，不必等到上限） */
    fun wakePump() {
        synchronized(pumpLock) { pumpLock.notifyAll() }
    }

    /** 「按应用期望出数据」的推送节流（与 SensorRateProbe 的 dump 缓存同量级） */
    private const val RATE_HINT_INTERVAL_NANOS = 2_000_000_000L
    private var lastRateHintNanos = 0L
    @Volatile private var rateHintLogged = false

    /** 模拟步数真值（与 app 端 hook 同量级：随机起点，像"已经走了不少"） */
    /**
     * 步数计数器值（对外推送的"开机以来累计"）。
     *
     * **不再从随机值起跳**：模拟接管时接在**真实**计数器后面（见 [realStepCounter]），
     * 会话重启时取 `max(上次推送值, 真实值)`，保证单调不回退。
     * 随机起点会让第一帧出现几千步的跳变 —— 按 Δ步数算步频的应用会被长期拉高读数
     * （实测反馈：重启后 Test 页显示 0，一开始播放模拟就跳到 7000+）。
     */
    private var steps = 0L

    /** 本次会话的起点（诊断用：Test 页显示） */
    @Volatile private var stepsBase = 0L
    private var stepFraction = 0.0
    private var lastTickNanos = 0L
    private var failTicks = 0

    /** 运行时通道不可用是否已记账（每进程一次，见 [reportRuntimeChannelUnavailable]） */
    private var runtimeChannelWarned = false

    /** 原生注入层是否已就绪（诊断/给 UI 用） */
    val isNativeReady: Boolean get() = nativeReady

    /**
     * 开机登记：**只记状态，不做任何装载**。
     *
     * ⚠️ 这条路径是这条功能最危险的地方，务必保持"什么都不做"：
     * 它跑在 system_server 的 `handleLoadPackage("android")` 阶段（开机途中），而
     * `dlopen` 后改写 `libsensorservice.so` 在部分 ROM 上会让 system_server 永久卡在
     * `Waiting for service 'sensorservice'` —— 实测 MI6/LineageOS 15 上开机永远停在
     * 开机动画（2026-09-12 事故，A/B 已证）。装载统一推迟到**模拟会话真正启动**时
     * （[onSimulationChanged]）。
     */
    fun registerAtBoot() {
        if (!FakeLoc.isSystemServerProcess) return
        Logger.info(
            "BinderSensorMock: 开机登记 cadence=${FakeLoc.enableCadenceMock} orientation=${FakeLoc.enableOrientationMock}" +
                    "（装载推迟到模拟会话启动；开机阶段不碰传感器 HAL）"
        )
    }

    /**
     * 开关/配置变化（`put_config` 已写入两个按类开关后调用；**任一侧开着就该活着**）。
     * 只在 system_server 内生效；其它进程只镜像开关值。
     *
     * **只有在模拟会话已经跑着的时候才装载**：`put_config` 既可能来自开机阶段，
     * 也可能来自应用启动时——都不该成为"往 system_server 里 dlopen 传感器 HAL"的理由。
     * @return 开关要求开启时，原生注入层是否已就绪；关闭/暂不装载时为 true
     */
    fun onConfigChanged(): Boolean {
        if (!FakeLoc.isSystemServerProcess) return true
        // 配置变化可能让泵需要立刻重算状态/速率提示
        wakePump()
        Logger.info(
            "BinderSensorMock: onConfigChanged cadence=${FakeLoc.enableCadenceMock} orientation=${FakeLoc.enableOrientationMock} " +
                    "session=${FakeLoc.enable} native=$nativeReady fail=$failTicks"
        )
        // ⚠️ **配置变化这条路必须推一次按类开关**。原先 pushSensorClasses() 只挂在
        // applyStoredConfig()（开机/恢复路径），于是"改开关"只改了模块的 flag、从没到原生层 ——
        // 注入行为不变，就是个骗人的开关。这个 bug 是靠真机运行时证据抓出来的（开关关了、
        // pref 变了、模块日志的 flag 也变了，而**按类计数与注入行为没变**）。
        pushSensorClasses()
        if (!FakeLoc.anySensorMockEnabled) {
            deactivate()
            SystemRuntimeChannel.releaseCarrier()
            return true
        }
        if (!FakeLoc.enable) {
            Logger.info("BinderSensorMock: 开关已开但会话未启动 —— 不装载（等会话启动）")
            return true
        }
        return load()
    }

    /**
     * 装载原生层 + 探针：**唯一**会把注入层装进 system_server 的入口。
     * 调用点只有两个：模拟会话启动、以及"会话已在跑时配置到达"。
     */
    private fun load(): Boolean {
        // 用户刚改开关/刚开会话：不受退避影响，必须当场给出结论
        val ok = ensureNative(force = true)
        // S1 探针：只解析 + 读 mPtr，不注册任何东西（零副作用；解析可重试）
        Logger.info("BinderSensorMock: ${SystemRuntimeChannel.probe()}")
        reportRuntimeChannelUnavailable()
        // 速率提示的事件源：框架侧的"谁订了/退了/改速率"（拿不到类就退回兜底周期）
        SensorRateProbe.installSubscriptionHooks(SystemRuntimeChannel.sensorServiceClass())
        if (ok) ensurePump()
        return ok
    }

    /**
     * 运行时通道不可用**每进程只记一次账**。
     *
     * 它是设备/ROM 属性（本机看不到 `com.android.server.sensors.SensorService`），
     * 不是每次会话的新故障；按次记账会把 Test 页的"静默失败计数"变成噪声。
     */
    private fun reportRuntimeChannelUnavailable() {
        val failure = SystemRuntimeChannel.resolveFailure ?: return
        if (runtimeChannelWarned) return
        runtimeChannelWarned = true
        PortalDiag.fail(PortalDiag.Area.RT_CHANNEL)
        Logger.error("BinderSensorMock: 运行时通道解析失败：$failure（本机不支持，仅记账一次）")
    }

    /** 模拟会话启停（start/stop 命令）：**会话启动是装载原生层的正常时机**。 */
    fun onSimulationChanged() {
        if (!FakeLoc.isSystemServerProcess) return
        // 会话启停都要叫醒泵：开启 ⇒ 立刻开始推流；停止 ⇒ 醒来收尾
        wakePump()
        if (FakeLoc.anySensorMockEnabled && FakeLoc.enable) {
            load()
        } else {
            // 会话停止（或两侧开关都关）：收尾并停泵；下次会话重新装载/复用
            deactivate()
        }
        // 会话停止但开关仍开：保留已装载的注入层（投递泵自己会因 !FakeLoc.enable 退出），
        // 下次开会话直接复用，不再重复 dlopen。
    }

    /**
     * 把当前状态写进 Bundle（诊断页 / `get_sensor_status` 命令用）。
     *
     * 关键对照项：`cadence_intent`（按设定速度算出的**意图步频**）与原生层
     * `status()` 里的 `step_rate`（**实际发出的步事件换算的步频**）——两者对不上
     * 就说明问题在生成/投递环节，而不是应用侧的显示。
     */
    /**
     * 把两侧的**按类开关**灌进原生层（门控在 `vw_owns_type`）。
     *
     * 为什么不止推一次：用户可能在会话仍开着时关掉一侧 —— 那时装载已经完成，
     * "只在 load() 里推"会让这次开关改动到下次重启才生效（静默失效，最难查的那种）。
     */
    /**
     * 把两侧的**按类开关**灌进原生层（门控在 `vw_owns_type`）。
     *
     * ## 权威来源只有一条：命令（`put_config`）
     *
     * ⚠️ 这里**刻意不读 App 的偏好**。2026-09-19 真机实测（本轮判据观测）：
     * libxposed 的远程偏好是"**构造时拉一次快照、之后等框架推增量**"——
     * 而本机（LSPosed 2.2.0/api 102）**增量从未到达**：
     *   · App 在功能页里改开关（写偏好 + 发命令）⇒ 命令到了、`偏好变化推送` 一次都没有；
     *   · 用 root 直接改偏好文件（原地写、保 inode）⇒ 同样没有任何推送。
     * 于是那条通道给到 system_server 的永远是**进程第一次读时的旧值**，
     * 且由框架按 group 在进程内缓存（`getRemotePreferences` ⇒ `computeIfAbsent`），
     * 之后再读也不会刷新。
     *
     * 把这种"陈旧快照"当权威的后果是实测出来的：模块先按命令把一侧关掉，1 秒后
     * 自轮询拿旧快照把它**又打开**，界面上的开关形同虚设（日志现场：
     * `按类开关变化（自轮询）orientation=false->true` 紧跟在 `onConfigChanged orientation=false` 之后）。
     * 所以这条读路径被**整体删除**，只留命令这一条 —— 与模块其它设置（噪声档/速度…）同一条通道。
     *
     * 为什么不止推一次：用户可能在会话仍开着时关掉一侧 —— 那时装载已经完成，
     * "只在 load() 里推"会让这次开关改动到下次重启才生效（静默失效，最难查的那种）。
     */
    private fun pushSensorClasses() {
        if (!nativeReady) return
        runCatching {
            BinderSensorNative.setSensorClasses(FakeLoc.enableCadenceMock, FakeLoc.enableOrientationMock)
            // 顺带记一笔"下发时点的按类压制计数"：连读两次开关日志的**差值**即可判定
            // "关掉的那一侧是否真的不再被压制"（关掉的一侧差值必须为 0，另一侧继续涨）
            Logger.info(
                "BinderSensorMock: 按类开关 cadence=${FakeLoc.enableCadenceMock} " +
                        "orientation=${FakeLoc.enableOrientationMock} | ${BinderSensorNative.suppressedCounts()}"
            )
        }.onFailure { Logger.warn("BinderSensorMock: 按类开关下发失败：${it.message}") }
    }

    fun fillStatus(rely: android.os.Bundle) {
        rely.putBoolean("flag", FakeLoc.anySensorMockEnabled)
        rely.putBoolean("mock_cadence", FakeLoc.enableCadenceMock)
        rely.putBoolean("mock_orientation", FakeLoc.enableOrientationMock)
        rely.putBoolean("mock_running", FakeLoc.enable)
        rely.putBoolean("native_ready", nativeReady)
        rely.putBoolean("active", active)
        rely.putString("native", runCatching { BinderSensorNative.status() }.getOrDefault("n/a"))
        // 运行时投递通道（"投递 100% 可控"那条路）的状态
        rely.putString("rt_channel", SystemRuntimeChannel.status())
        // 注入噪声档的**系统侧回读**（Calibration 页用它证明下发真的落到了原生层，
        // 而不只是写进了 App 的偏好）
        rely.putString("noise_profile", runCatching { BinderSensorNative.noiseProfile() }
            .getOrDefault("n/a"))
        // 按组波动（两组各两条，%）：与上面噪声档并列，诊断页一眼看到"下发到了什么"
        rely.putString("wobble", FakeLoc.wobbleLine())
        // 静默失败记账：本次运行里"本该静默降级"的失败各发生了几次（全零 = none）
        rely.putString("diag", moe.fuqiuluo.xposed.utils.PortalDiag.dump())
        // 厂商私有传感器清单（仅展示：它们也在同一个事件出口上，但不在接管集合内）
        rely.putString("priv_sensors", SensorHandleMap.privateTypes() ?: "（读不到）")
        // 应用期望频率（框架采用值 + 客户端原始请求；见 SensorRateProbe）
        rely.putString("sensor_rates", SensorRateProbe.status())
        // 握手门禁自检：只有模块自身应显示 ALLOW，其余 uid 一律 deny
        rely.putString("portal_gate", moe.fuqiuluo.xposed.utils.BinderUtils.gateSelfTest())
        // 运动学权威值（system_server 侧）
        val (speed, moving) = FakeLoc.averageSpeedOverWindow(SPEED_WINDOW_MS)
        rely.putDouble("measured_speed", speed)
        rely.putDouble("configured_speed", FakeLoc.speed)
        rely.putBoolean("moving", moving)
        rely.putDouble("bearing_target", FakeLoc.bearing)
        rely.putDouble("bearing_frame", FakeLoc.processedBearing())
        rely.putDouble("gait_speed", speed)
        rely.putInt("cadence_intent", FakeLoc.cadenceForSpeed(speed))
        rely.putDouble("cadence_scale", FakeLoc.cadenceScale)
        rely.putLong("steps_total", steps)
        // 客户端视角的"开机总步数"（我们推送的 STEP_COUNTER 值）
        rely.putLong("steps_boot", runCatching { BinderSensorNative.stepCounterValue() }
            .getOrDefault(0L))
        rely.putLong("steps_base", stepsBase)
        rely.putDouble("lat", FakeLoc.latitude)
        rely.putDouble("lon", FakeLoc.longitude)
        rely.putDouble("altitude", FakeLoc.altitude)
        rely.putBoolean("gnss_mock", FakeLoc.enableMockGnss)
        // 体力与推进（统一架构迁移后都在系统侧，见 StaminaRuntime / MotionEngine / MotionClock）
        StaminaRuntime.writeStatus(rely)
        rely.putString("stamina", StaminaRuntime.statusLine())
        val motion = MotionEngine.status()
        // 结构化字段（Test 页/诊断用）：与 `motion` 那行同一来源
        rely.putString(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.MOTION_MODE, motion.mode.name.lowercase())
        rely.putBoolean(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.MOTION_PLAYING, motion.playing)
        rely.putBoolean(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.MOTION_COMPLETED, motion.completed)
        rely.putDouble(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.ROUTE_TRAVELLED, motion.travelledMeters)
        rely.putDouble(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.ROUTE_DISTANCE, motion.distanceMeters)
        rely.putInt(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.ROUTE_POINTS, motion.points)
        rely.putString(
            "motion",
            "推进=%s 路线=%.0f/%.0fm(%d点) 时钟=%s 上报=%dms%s".format(
                motion.mode.name.lowercase(), motion.travelledMeters, motion.distanceMeters,
                motion.points, if (MotionClock.isRunning) "在跑" else "停",
                FakeLoc.reportDurationMs,
                // 分段计时只在调试开关打开时统计；关着就是个空串（不占诊断面板）
                if (FakeLoc.enableDebugLog) {
                    "\n" + MotionClock.timingLine() +
                            "\n" + moe.fuqiuluo.xposed.hooks.LocationServiceHook.deliveryTimingLine()
                } else "",

            )
        )
    }

    /** 诊断字符串（logcat / 排查用） */
    fun status(): String =
        if (!nativeReady) "native=unloaded active=$active"
        else "active=$active ${runCatching { BinderSensorNative.status() }.getOrDefault("n/a")} | " +
                SystemRuntimeChannel.status()

    /** 同步装载原生层（幂等）。[force] = 用户显式改动开关时忽略退避，务必给出明确结果。 */
    private fun ensureNative(force: Boolean = false): Boolean {
        if (nativeReady) return true
        synchronized(this) {
            if (nativeReady) return true
            if (failTicks > 0 && !force) {
                Logger.warn("BinderSensorMock: install backoff, ${failTicks} tick(s) left")
                return false
            }
            val syms = LibSymbols.resolve()
            if (syms == null) {
                failTicks = FAIL_RETRY_TICKS
                PortalDiag.fail(PortalDiag.Area.LIB_RESOLVE)
                Logger.error("BinderSensorMock: 平台符号未解析到，功能不生效（未做任何猜测）")
                return false
            }
            if (!BinderSensorNative.ensureLoaded()) {
                failTicks = FAIL_RETRY_TICKS
                PortalDiag.fail(PortalDiag.Area.NATIVE_LOAD)
                Logger.error("BinderSensorMock: ${BinderSensorNative.lastLoadError()}")
                return false
            }
            // 走带契约校验的入口：长度/顺序不对就明确拒绝，而不是喂给 native 静默失效
            val ok = BinderSensorNative.installChecked(syms.toOffsets())
            if (ok) {
                nativeReady = true
                seedHandleMap()
                applyStoredConfig()
                Logger.info("BinderSensorMock: ${BinderSensorNative.status()}")
                return true
            }
            failTicks = FAIL_RETRY_TICKS
            PortalDiag.fail(PortalDiag.Area.NATIVE_INSTALL)
            Logger.error("BinderSensorMock: native layer unavailable, feature inert")
            return false
        }
    }

    /**
     * 把**已经存在**的注入参数重新下发给刚装载好的原生层。
     *
     * 为什么必须有这一步：装载现在推迟到会话启动（开机不碰 HAL），而 App 的 `put_config`
     * 通常在装载**之前**到达 —— 那一刻 `setNoise` 只会报
     * `UnsatisfiedLinkError` 并被丢掉。少了这次重放，用户标定的噪声档与注入栅格会在
     * "开机后第一次开会话"时静默退回内置默认（值还在 [FakeLoc]，但原生层不知道）。
     */
    private fun applyStoredConfig() {
        runCatching {
            FakeLoc.applyNoiseProfile { index, amp -> BinderSensorNative.setNoise(index, amp) }
            // 两组波动走同一条重放路径（噪声档的兄弟项）：系统进程重启后不必等 App 再下发一次
            FakeLoc.applyGroupWobble { group, amp, rnd ->
                BinderSensorNative.setGroupWobble(group, amp, rnd)
            }
            pushSensorClasses()
        }.onFailure { Logger.warn("BinderSensorMock: 噪声档重放失败：${it.message}") }
    }

    /**
     * 把框架的 `type → handle` 表播种给原生层。
     *
     * 这一步是「完全隔离」的关键补丁：只靠真实事件学习时，**on-change 传感器
     * （步数计数器 / 检测器）在手机不走路时永远学不到 handle**，"模拟走路"于是推不出步频。
     * 框架自己的传感器表与 HAL 是否出数据无关，因此无条件可靠（拿不到就退回事件学习）。
     */
    private fun seedHandleMap() {
        val triples = SensorHandleMap.collect() ?: run {
            PortalDiag.fail(PortalDiag.Area.HANDLE_MAP)
            Logger.warn("BinderSensorMock: 传感器表不可用，改用真实事件学习 handle")
            return
        }
        runCatching { BinderSensorNative.setHandleMap(triples) }
            .onFailure { PortalDiag.fail(PortalDiag.Area.HANDLE_MAP, it)
                Logger.error("BinderSensorMock: setHandleMap failed", it) }
    }

    private fun deactivate(fromPump: Boolean = false) {
        if (fromPump) {
            // 泵线程自己收尾：不能 interrupt 自己，也不该在 loop 内再进 stopPump
            pumpThread = null
            pumpStartGuard.set(false)
            SystemRuntimeChannel.stopDelivery()
        } else {
            stopPump()
        }
        if (!active) return
        active = false
        if (nativeReady) {
            runCatching { BinderSensorNative.setActive(false) }
                .onFailure { Logger.error("BinderSensorMock: setActive(false) failed", it) }
        }
        Logger.info("BinderSensorMock: deactivated")
    }

    /**
     * 运行时投递泵：**睡到下一个事件到点**，醒来把到期事件逐条投递。
     *
     * 旧实现是固定 5ms 轮询：原生层按时间栅格决定谁该出，泵每 5ms 取一帧 —— 结果是
     * 事件的**到达相位**被量化到 5ms（时间戳再精细也补不回来），而且栅格本身要跟应用
     * 采用值对齐才"像"。现在改成事件驱动：原生层给出 [BinderSensorNative.nextDueNs]，
     * 泵睡到那一刻精确唤醒，事件就发在它自己该发的时刻。
     *
     * 这是"投递 100% 可控"的落点：不再依赖 HAL 轮询（poll 只在被调用时才推进生成），
     * 改为我们自己的时钟推进。启动顺序必须是 **先切节拍、再起泵**（[SystemRuntimeChannel.startDelivery]），
     * 停止顺序相反，中间不留"两边都不发"的空窗。
     */
    private fun ensurePump() {
        if (!pumpStartGuard.compareAndSet(false, true)) return
        synchronized(this) {
            if (pumpThread != null) return
            // 先起泵：载体可能因开机时序暂时不可用，泵线程自己在状态机里重试。
            pumpThread = Thread({ pumpLoop() }, "PortalSensorPump").apply {
                isDaemon = true
                start()
            }
            Logger.info("BinderSensorMock: 运行时投递泵已启动")
        }
    }

    private fun stopPump() {
        pumpStartGuard.set(false)
        val t = pumpThread ?: run {
            SystemRuntimeChannel.stopDelivery()
            return
        }
        pumpThread = null
        t.interrupt()
        SystemRuntimeChannel.stopDelivery()
    }

    private enum class PumpState { WAIT_CARRIER, RUNNING }

    private fun pumpLoop() {
        var state = PumpState.WAIT_CARRIER
        var backoffMs = 100L
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (!FakeLoc.anySensorMockEnabled || !FakeLoc.enable || !nativeReady) {
                    deactivate(fromPump = true)
                    return
                }
                if (!SystemRuntimeChannel.ensureDelivery()) {
                    // 载体还没就绪（开机时序）：状态机退避重试，保持泵存活
                    state = PumpState.WAIT_CARRIER
                    synchronized(pumpLock) { pumpLock.wait(backoffMs) }
                    backoffMs = (backoffMs * 2).coerceAtMost(1_000L)
                    continue
                }
                if (state != PumpState.RUNNING) {
                    state = PumpState.RUNNING
                    backoffMs = 100L
                }
                val now = SystemClock.elapsedRealtimeNanos()
                // 状态必须先于取帧：泵的每次唤醒就是"世界状态推进"的事件源
                beforePump(now)
                if (!FakeLoc.enable || !FakeLoc.anySensorMockEnabled) {
                    deactivate(fromPump = true)
                    return
                }
                SystemRuntimeChannel.pump(now)
                /*
                 * 睡到"下一个到点时刻"：这是把轮询换成事件驱动的关键一步。
                 * nextDueNs 返回 0（当前没有到点的源）时用上限兜底；状态推进/提示刷新
                 * 都靠泵的唤醒，但空闲时不应 20Hz 空转，所以上限是 100ms。
                 */
                val due = if (nextDueAvailable) {
                    runCatching { BinderSensorNative.nextDueNs(now) }.getOrElse {
                        nextDueAvailable = false
                        Logger.warn("BinderSensorMock: 注入库没有 nextDueNs（旧 .so）⇒ 退回 5ms 固定节拍")
                        0L
                    }
                } else 0L
                val sleepMs = when {
                    !nextDueAvailable -> PUMP_FALLBACK_SLEEP_MS
                    // 0 = 当前没有任何到点源，不是"现在就到点"；按已到点处理会退化成 1ms 自旋
                    due == 0L -> PUMP_MAX_SLEEP_MS
                    due <= now -> PUMP_MIN_SLEEP_MS
                    else -> ((due - now) / 1_000_000L).coerceIn(PUMP_MIN_SLEEP_MS, PUMP_MAX_SLEEP_MS)
                }
                synchronized(pumpLock) { pumpLock.wait(sleepMs) }
            }
        } catch (_: InterruptedException) {
            // 正常停止路径（stopPump 会 interrupt）
        } catch (t: Throwable) {
            Logger.error("BinderSensorMock: 投递泵异常，交回 poll 路径", t)
        } finally {
            // 任何退出路径都要清掉引用并交回节拍：否则 native 侧会停在"只压制"，
            // 而我们的泵已经不在了 —— 那是"两边都不发"的最坏状态。
            pumpStartGuard.set(false)
            if (pumpThread === Thread.currentThread()) pumpThread = null
            SystemRuntimeChannel.stopDelivery()
        }
    }

    /**
     * 状态推进：**只由投递泵线程调用**（单写者），会话/配置入口只负责装载与叫醒泵。
     *
     * 刻意不加锁：旧版监督线程是唯一写者；移除线程后泵是唯一写者，生命周期线程不再
     * 直接推进状态。这样 `pushHints()`（sensor service dump，binder 调用）不会在持有
     * BinderSensorMock 监视器时执行，避免与等这把锁的 binder 线程形成跨线程等待。
     */
    /**
     * 单飞调度速率提示 dump：泵只负责触发，binder 调用在独立线程完成，
     * 同一时刻最多一个在飞。即使 dump 触发回调，也回不到泵的状态推进路径。
     */
    private fun scheduleRateHints() {
        if (!hintInFlight.compareAndSet(false, true)) return
        hintExecutor.execute {
            try {
                if (FakeLoc.enable) {
                    val ok = runCatching { SensorRateProbe.pushHints() }.getOrDefault(false)
                    if (ok && !rateHintLogged) {
                        rateHintLogged = true
                        Logger.info("BinderSensorMock: 注入速率改由框架采用值驱动（见 rates=/hints）")
                    }
                }
            } finally {
                hintInFlight.set(false)
            }
        }
    }

    private fun beforePump(now: Long) {
        // 任何意外的重入（回调绕回推进路径）都丢弃这一轮，避免栈增长
        if (!stateAdvancing.compareAndSet(false, true)) return
        try {
            beforePumpBody(now)
        } finally {
            stateAdvancing.set(false)
        }
    }

    private fun beforePumpBody(now: Long) {
        val dt = if (lastTickNanos == 0L) 0.0 else (now - lastTickNanos) / 1e9
        lastTickNanos = now

        // 开关关闭 == 什么都不做（不装载、不注入、不注册载体）
        if (!FakeLoc.anySensorMockEnabled) {
            deactivate(fromPump = true)
            return
        }
        // S2：运行时通道的**载体引导**只与实验开关同步，与"模拟会话是否在跑"无关
        // —— 它只启动框架的运行时投递机制（事件缓冲 + RuntimeSensorHandler 线程），
        // 不推送任何数据，载体本身对客户端不可见。投递接管在后续阶段接入。
        if (nativeReady) SystemRuntimeChannel.ensureCarrier()

        // 只有「模拟开关打开」且「模拟会话在跑」时才注入；否则真实传感器原样透传
        val want = FakeLoc.enable
        if (!want) {
            deactivate(fromPump = true)
            return
        }
        if (!nativeReady) {
            if (failTicks > 0) {
                failTicks--
            }
            return
        }

        // 投递路线：载体就绪 → 运行时通道（我们自己的时钟推进）；否则保持 poll 路径（现状）。
        // ensurePump 内部会先切节拍再起泵，幂等；载体未就绪时它什么都不做。
        ensurePump()

        if (!active) {
            // 会话开始：把计数器锚到"真实值 / 上次推送值"的较大者（单调），避免跳变
            val real = runCatching { BinderSensorNative.realStepCounter() }.getOrDefault(-1L)
            steps = maxOf(steps, real, 0L)
            stepsBase = steps
            stepFraction = 0.0
            active = true
            // 每次激活都重播一次映射：传感器表可能因动态传感器增减而变化
            seedHandleMap()
            BinderSensorNative.setActive(true)
            Logger.info(
                "BinderSensorMock: activated base=$stepsBase (real=$real) " +
                        "cadenceScale=${FakeLoc.cadenceScale} " +
                        "(${BinderSensorNative.status()})"
            )
        }

        /*
         * ---- 「有事才做」：这一步是这套时钟从轮询改成"事件驱动"的关键 ----
         *
         * 下面两件是每拍里**唯一贵**的事：`averageSpeedOverWindow`（扫坐标历史）与
         * `updateState`（JNI，写原生世界 + 按步分摊事件）。原先每拍无条件做，于是
         * "会话开着但人在原地"时白烧 ~1.0~1.5% 单核（实测，40 拍/2s）。
         *
         * 现在按**世界是否真的动了**分流：
         *  · 动了（有位移）⇒ 全套：量速度、积步数、下发快照（步事件的时间戳精度靠它）；
         *  · 没动 ⇒ 只按 5Hz 回灌**朝向**（摆动的半周期 0.35~0.75s，5Hz 足够带出来），
         *    外加 2s 兜底心跳；其余每拍直接返回，一行 JNI 都不打。
         *
         * 静止时不再量速度、不再积步数 —— 没位移就没有步数，`stepFraction` 也不会丢（它只在
         * moving 时累加）。落地到应用侧的表现不变：站着不动时计步器不涨、指南针仍有摆动。
         */
        val lat = FakeLoc.latitude
        val lon = FakeLoc.longitude
        val bearingNow = FakeLoc.processedBearing()
        val worldChanged = lat != lastPushLat || lon != lastPushLon
        val bearingChanged = lastPushBearing.isNaN() ||
                Math.abs(((bearingNow - lastPushBearing + 540.0) % 360.0) - 180.0) > BEARING_EPS_DEG
        val sincePush = now - lastPushNanos
        if (!worldChanged) {
            val bearingDue = bearingChanged && sincePush >= IDLE_BEARING_PUSH_NANOS
            val heartbeat = sincePush >= STATE_HEARTBEAT_NANOS
            if (!bearingDue && !heartbeat) return
        }

        // 世界动了才算运动学（静止时速度恒 0：没有位移就没有速度，不需要去查窗口平均）
        val (speed, moving) = if (worldChanged) {
            FakeLoc.averageSpeedOverWindow(SPEED_WINDOW_MS)
        } else {
            0.0 to false
        }
        if (moving && dt > 0.0 && dt < 5.0) {
            stepFraction += FakeLoc.cadenceForSpeed(speed) / 60.0 * dt
            val whole = stepFraction.toInt()
            if (whole > 0) {
                stepFraction -= whole
                steps += whole
            }
        }

        // S2：载体引导在上面的"开关打开"分支里已经做过（幂等），这里只推进状态
        /*
         * 「按应用期望出数据」：把框架观测到的**采用速率**与**活跃状态**灌给原生层。
         * 真机 HAL 按"所有请求里最快那个"出力、框架原样广播 ⇒ 我们照同一模型走；
         * 没人订阅的类型随之静默。刷新由 native 订阅脏位驱动（本机 Java 侧钩子装不上），
         * 60s 周期只作兜底 —— 没有订阅变化时一次 40KB dump 都不做。
         */
        if (nativeReady && SensorRateProbe.consumeNativeDirty()) {
            SensorRateProbe.requestRefresh()
        }
        // 速率提示：**订阅变化驱动**（native 脏位 + Java 钩子），周期只作兜底 ——
        // 一次 dump 20~30ms，靠轮询做就是白烧（实测移动时 2s 一次 ≈1~1.5% 单核）
        if (SensorRateProbe.dueForRefresh(now, moving)) {
            SensorRateProbe.markRefreshed(now)
            scheduleRateHints()
        }
        BinderSensorNative.updateState(speed, bearingNow, moving, steps, now)
        lastPushLat = lat
        lastPushLon = lon
        lastPushBearing = bearingNow
        lastPushNanos = now
    }
}
