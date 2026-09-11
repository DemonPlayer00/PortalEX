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

    @Volatile private var supervisorStarted = false
    @Volatile private var nativeReady = false
    @Volatile private var active = false

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
            return ensureNative(force = true)
        }
        deactivate()
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

    /** 诊断字符串（logcat / 排查用） */
    fun status(): String =
        if (!nativeReady) "native=unloaded active=$active"
        else "active=$active ${runCatching { BinderSensorNative.status() }.getOrDefault("n/a")}"

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
                Logger.info("BinderSensorMock: ${BinderSensorNative.status()}")
                return true
            }
            failTicks = FAIL_RETRY_TICKS
            Logger.error("BinderSensorMock: native layer unavailable, feature inert")
            return false
        }
    }

    private fun deactivate() {
        if (!active) return
        active = false
        if (nativeReady) {
            runCatching { BinderSensorNative.setActive(false) }
                .onFailure { Logger.error("BinderSensorMock: setActive(false) failed", it) }
        }
        Logger.info("BinderSensorMock: deactivated")
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

        // 只有「实验开关打开」且「模拟会话在跑」时才注入；否则真实传感器原样透传
        val want = FakeLoc.enableBinderSensorMock && FakeLoc.enable
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
            BinderSensorNative.setActive(true)
            Logger.info("BinderSensorMock: activated (${BinderSensorNative.status()})")
        }
        BinderSensorNative.updateState(speed, FakeLoc.processedBearing(), moving, steps, now)
    }
}
