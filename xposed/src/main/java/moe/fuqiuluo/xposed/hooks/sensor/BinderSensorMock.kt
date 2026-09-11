package moe.fuqiuluo.xposed.hooks.sensor

import android.os.SystemClock
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import kotlin.random.Random

/**
 * Binder 外周传感器模拟（实验性，默认关）—— system_server 侧调度。
 *
 * 与 app 端 [SystemSensorManagerHook] 的分工：
 * - [SystemSensorManagerHook]：**应用进程内**改写真实回调（旧路径，只在 LSPosed
 *   作用域内的应用生效，且需要真实回调存在才能驱动）。
 * - 本类：**只在系统框架侧**工作。它把 FakeLoc 的权威运动学量（速度 / 朝向 / 步数）
 *   周期性地喂给原生注入层，由原生层在传感器 HAL 包装器的 poll 出口自产事件并压制
 *   真实值。目标应用**一个 hook 都不装**也能拿到模拟数据，且不依赖底层传感器是否
 *   在工作。
 *
 * 生命周期：开关打开（或模拟启动）时装载原生层并开始推流；关闭时立刻停推并把注入层
 * 置为 inactive（真实事件原样放行）。开关关闭（默认）时本类**不做任何事**——不加载
 * .so、不起线程，旧行为逐位不变。
 */
object BinderSensorMock {

    /** 状态推送周期：50ms 足够跟住摇杆转向/路线转弯，JNI 开销可忽略 */
    private const val PUSH_INTERVAL_MS = 50L

    /** 速度推算窗口（ms）：与位置注入帧同口径 */
    private const val SPEED_WINDOW_MS = 1000L

    /** 失败后的重试间隔（内部 tick 数）：避免每 50ms 刷一次异常日志 */
    private const val FAIL_RETRY_TICKS = 100

    /**
     * 运行时投递泵的周期。生成器自己按 20ms/40ms 的栅格决定谁该出事件
     * （加速度 50Hz、磁场 25Hz、步数按步事件），所以泵只要比最密的栅格更密即可。
     */
    private const val PUMP_INTERVAL_MS = 10L

    @Volatile private var supervisorStarted = false
    @Volatile private var nativeReady = false
    @Volatile private var active = false
    @Volatile private var pumpThread: Thread? = null

    /** 模拟步数真值（与 app 端 hook 同量级：随机起点，像"已经走了不少"） */
    private var steps = Random.nextLong(3000, 12000)
    private var stepFraction = 0.0
    private var lastTickNanos = 0L
    private var failTicks = 0

    /** 原生注入层是否已就绪（诊断/给 UI 用） */
    val isNativeReady: Boolean get() = nativeReady

    /**
     * 开关变化（put_config 已写入 [FakeLoc.enableBinderSensorMock] 后调用）。
     * 只在 system_server 内生效；其它进程只镜像开关值。
     * @return 开关要求开启时，原生注入层是否已就绪；关闭时为 true
     */
    fun onConfigChanged(): Boolean {
        if (!FakeLoc.isSystemServerProcess) return true
        Logger.info(
            "BinderSensorMock: onConfigChanged flag=${FakeLoc.enableBinderSensorMock} " +
                    "supervisor=$supervisorStarted native=$nativeReady fail=$failTicks"
        )
        if (FakeLoc.enableBinderSensorMock) {
            ensureSupervisor()
            // 用户刚刚改的开关：不受退避影响，必须当场给出结论
            val ok = ensureNative(force = true)
            // S1 探针：只解析 + 读 mPtr，不注册任何东西（零副作用；解析可重试）
            Logger.info("BinderSensorMock: ${SystemRuntimeChannel.probe()}")
            SystemRuntimeChannel.resolveFailure?.let {
                Logger.error("BinderSensorMock: 运行时通道解析失败：$it")
            }
            return ok
        }
        deactivate()
        SystemRuntimeChannel.releaseCarrier()
        return true
    }

    /** 模拟会话启停（start/stop 命令）：开关打开时才需要动作。 */
    fun onSimulationChanged() {
        if (!FakeLoc.isSystemServerProcess) return
        if (FakeLoc.enableBinderSensorMock) {
            ensureSupervisor()
            ensureNative()
        } else {
            deactivate()
        }
    }

    /**
     * 把当前状态写进 Bundle（诊断页 / `get_sensor_status` 命令用）。
     *
     * 关键对照项：`cadence_intent`（按设定速度算出的**意图步频**）与原生层
     * `status()` 里的 `step_rate`（**实际发出的步事件换算的步频**）——两者对不上
     * 就说明问题在生成/投递环节，而不是应用侧的显示。
     */
    fun fillStatus(rely: android.os.Bundle) {
        rely.putBoolean("flag", FakeLoc.enableBinderSensorMock)
        rely.putBoolean("mock_running", FakeLoc.enable)
        rely.putBoolean("native_ready", nativeReady)
        rely.putBoolean("active", active)
        rely.putString("native", runCatching { BinderSensorNative.status() }.getOrDefault("n/a"))
        // 运行时投递通道（"投递 100% 可控"那条路）的状态
        rely.putString("rt_channel", SystemRuntimeChannel.status())
        // 运动学权威值（system_server 侧）
        val (speed, moving) = FakeLoc.averageSpeedOverWindow(SPEED_WINDOW_MS)
        rely.putDouble("measured_speed", speed)
        rely.putDouble("configured_speed", FakeLoc.speed)
        rely.putBoolean("moving", moving)
        rely.putDouble("bearing_target", FakeLoc.bearing)
        rely.putDouble("bearing_frame", FakeLoc.processedBearing())
        rely.putDouble("gait_speed", speed)
        rely.putInt("cadence_intent", FakeLoc.cadenceForSpeed(speed))
        rely.putLong("steps_total", steps)
        rely.putDouble("lat", FakeLoc.latitude)
        rely.putDouble("lon", FakeLoc.longitude)
        rely.putDouble("altitude", FakeLoc.altitude)
        rely.putBoolean("gnss_mock", FakeLoc.enableMockGnss)
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
                Logger.error("BinderSensorMock: 平台符号未解析到，功能不生效（未做任何猜测）")
                return false
            }
            if (!BinderSensorNative.ensureLoaded()) {
                failTicks = FAIL_RETRY_TICKS
                Logger.error("BinderSensorMock: ${BinderSensorNative.lastLoadError()}")
                return false
            }
            val ok = BinderSensorNative.install(syms.toOffsets())
            if (ok) {
                nativeReady = true
                seedHandleMap()
                Logger.info("BinderSensorMock: ${BinderSensorNative.status()}")
                return true
            }
            failTicks = FAIL_RETRY_TICKS
            Logger.error("BinderSensorMock: native layer unavailable, feature inert")
            return false
        }
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
            Logger.warn("BinderSensorMock: 传感器表不可用，改用真实事件学习 handle")
            return
        }
        runCatching { BinderSensorNative.setHandleMap(triples) }
            .onFailure { Logger.error("BinderSensorMock: setHandleMap failed", it) }
    }

    private fun deactivate() {
        stopPump()
        if (!active) return
        active = false
        if (nativeReady) {
            runCatching { BinderSensorNative.setActive(false) }
                .onFailure { Logger.error("BinderSensorMock: setActive(false) failed", it) }
        }
        Logger.info("BinderSensorMock: deactivated")
    }

    /**
     * 运行时投递泵：每 [PUMP_INTERVAL_MS] 向原生层取一帧到期事件，逐条投递。
     *
     * 这是"投递 100% 可控"的落点：不再依赖 HAL 轮询（poll 只在被调用时才推进生成），
     * 改为我们自己的时钟推进。启动顺序必须是 **先切节拍、再起泵**（[SystemRuntimeChannel.startDelivery]），
     * 停止顺序相反，中间不留"两边都不发"的空窗。
     */
    private fun ensurePump() {
        if (pumpThread != null) return
        synchronized(this) {
            if (pumpThread != null) return
            if (!SystemRuntimeChannel.startDelivery()) return
            pumpThread = Thread({ pumpLoop() }, "PortalSensorPump").apply {
                isDaemon = true
                start()
            }
            Logger.info("BinderSensorMock: 运行时投递泵已启动")
        }
    }

    private fun stopPump() {
        val t = pumpThread ?: run {
            SystemRuntimeChannel.stopDelivery()
            return
        }
        pumpThread = null
        t.interrupt()
        SystemRuntimeChannel.stopDelivery()
    }

    private fun pumpLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(PUMP_INTERVAL_MS)
                if (!FakeLoc.enableBinderSensorMock || !FakeLoc.enable || !nativeReady) return
                SystemRuntimeChannel.pump(SystemClock.elapsedRealtimeNanos())
            }
        } catch (_: InterruptedException) {
            // 正常停止路径（stopPump 会 interrupt）
        } catch (t: Throwable) {
            Logger.error("BinderSensorMock: 投递泵异常，交回 poll 路径", t)
        } finally {
            // 任何退出路径都要清掉引用并交回节拍：否则 native 侧会停在"只压制"，
            // 而我们的泵已经不在了 —— 那是"两边都不发"的最坏状态。
            if (pumpThread === Thread.currentThread()) pumpThread = null
            SystemRuntimeChannel.stopDelivery()
        }
    }

    private fun ensureSupervisor() {
        if (supervisorStarted) return
        synchronized(this) {
            if (supervisorStarted) return
            supervisorStarted = true
        }
        Thread({ supervisorLoop() }, "PortalSensorSupervisor").apply {
            isDaemon = true
            start()
        }
        Logger.info("BinderSensorMock: supervisor started")
    }

    /**
     * 唯一状态源：每 [PUSH_INTERVAL_MS] 采一次 FakeLoc 的权威运动学量，
     * 步数按步频积分后随快照下发（原生层再按步间隔分摊成逐事件）。
     *
     * 为什么步数在这里积分：TYPE_STEP_COUNTER 是 on-change 传感器，底层不走路就没有
     * 事件；位置回调（速度/位移）才是步数真正的数据源。app 端 hook 有同样的积分，
     * 两边都用 [FakeLoc.cadenceForSpeed] 这唯一公式源，步频口径一致。
     */
    private fun supervisorLoop() {
        while (true) {
            try {
                Thread.sleep(PUSH_INTERVAL_MS)
                tick()
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                Logger.error("BinderSensorMock: tick failed", t)
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastTickNanos == 0L) 0.0 else (now - lastTickNanos) / 1e9
        lastTickNanos = now

        // 开关关闭 == 什么都不做（不装载、不注入、不注册载体）
        if (!FakeLoc.enableBinderSensorMock) {
            deactivate()
            return
        }
        // S2：运行时通道的**载体引导**只与实验开关同步，与"模拟会话是否在跑"无关
        // —— 它只启动框架的运行时投递机制（事件缓冲 + RuntimeSensorHandler 线程），
        // 不推送任何数据，载体本身对客户端不可见。投递接管在后续阶段接入。
        if (nativeReady) SystemRuntimeChannel.ensureCarrier()

        // 只有「模拟开关打开」且「模拟会话在跑」时才注入；否则真实传感器原样透传
        val want = FakeLoc.enable
        if (!want) {
            deactivate()
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

        val (speed, moving) = FakeLoc.averageSpeedOverWindow(SPEED_WINDOW_MS)
        if (moving && dt > 0.0 && dt < 5.0) {
            stepFraction += FakeLoc.cadenceForSpeed(speed) / 60.0 * dt
            val whole = stepFraction.toInt()
            if (whole > 0) {
                stepFraction -= whole
                steps += whole
            }
        }

        if (!active) {
            active = true
            // 每次激活都重播一次映射：传感器表可能因动态传感器增减而变化
            seedHandleMap()
            BinderSensorNative.setActive(true)
            Logger.info("BinderSensorMock: activated (${BinderSensorNative.status()})")
        }
        // S2：载体引导在上面的"开关打开"分支里已经做过（幂等），这里只推进状态
        BinderSensorNative.updateState(speed, FakeLoc.processedBearing(), moving, steps, now)
    }
}
