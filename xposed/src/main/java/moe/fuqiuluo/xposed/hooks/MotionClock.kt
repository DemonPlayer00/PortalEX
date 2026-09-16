package moe.fuqiuluo.xposed.hooks

import android.os.SystemClock
import moe.fuqiuluo.xposed.RemoteCommandHandler
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.MotionEngine
import moe.fuqiuluo.xposed.utils.StaminaRuntime

/**
 * **模块时钟**：system_server 侧唯一的固定节拍，推进世界 + 推进体力 + 投递位置帧。
 *
 * ## 为什么需要它（与旧实现的分工变化）
 *
 * 迁移前：App 的运动循环每拍算位移、送 `move`/`update_location` 命令，模块只是"接指令写坐标"。
 * 于是"谁推进"在 App、"谁缩放"也在 App —— 位置流由 App 进程的调度决定，App 被冻结就停。
 *
 * 迁移后：**推进与缩放都在这一侧**（[MotionEngine] 按 `速度 × 体力倍率 × Δt` 推进，
 * 见 `docs/sensor-architecture.md`）。App 只上传路线、下发摇杆意图。
 * 副作用是好的那一种：位置流的连续性不再依赖 App 进程活着。
 *
 * ## 两个间隔，别混
 *
 * - **拍（[BEAT_MS] = 50ms）**：积分步长。小步长让曲线平滑、体力按真实 Δt 结算。
 * - **投递间隔（[moe.fuqiuluo.xposed.utils.LocConfig.reportDurationMs]）**：对外出帧节奏，
 *   沿用 App 的「上报间隔」设置 —— 客户端观测到的帧率不该因为这次迁移而改变。
 *
 * ## 只在 system_server 里跑
 *
 * 其它进程（App 自身）没有权威世界，起时钟只会把镜像坐标搅乱：`start()` 直接拒绝。
 */
object MotionClock {

    /** 积分步长（毫秒） */
    private const val BEAT_MS = 50L

    /** 投递间隔的兜底范围（毫秒）：设置页允许 1ms，那样会 1000Hz 刷帧 */
    private const val MIN_DELIVER_MS = 20L
    private const val MAX_DELIVER_MS = 2_000L

    @Volatile
    private var thread: Thread? = null

    /**
     * 停摆/唤醒的锁（与 `BinderSensorMock` 同一做法）：**没有推进意图时世界不会动**，
     * 那时 20Hz 的拍子纯属空转（实测稳态占 0.33% 单核）。
     */
    private val idleLock = Object()

    /**
     * 停摆后的兜底自检间隔（毫秒）。
     *
     * 取 5s 而不是几分钟：唤醒点虽然铺全了（每条意图命令都叫），但"漏一次唤醒"的后果是
     * **按住摇杆位置却一动不动**（本仓踩过的静默失效形态）。5s 的代价只有 0.2Hz 的唤醒，
     * 换来的是"最多 5 秒自愈"。
     */
    private const val IDLE_RECHECK_MS = 5_000L

    private var lastBeatNanos = 0L
    private var lastDeliverNanos = 0L

    val isRunning: Boolean get() = thread != null

    /** 会话启动（`start` 命令）：起拍。幂等 */
    fun start() {
        if (!FakeLoc.isSystemServerProcess) return
        if (thread != null) return
        synchronized(this) {
            if (thread != null) return
            lastBeatNanos = 0L
            lastDeliverNanos = 0L
            thread = Thread({ loop() }, "PortalMotionClock").apply {
                isDaemon = true
                start()
            }
            wake()
            Logger.info(
                "MotionClock: 推进时钟已启动（${BEAT_MS}ms/拍，投递间隔 ${deliverIntervalMs()}ms，" +
                        "体力=${StaminaRuntime.config().enabled}）"
            )
        }
    }

    /** 会话停止（`stop` 命令）：停拍并清空推进状态（路线数据保留，见 [MotionEngine.stopSession]） */
    fun stop() {
        val t = thread
        thread = null
        MotionEngine.stopSession()
        if (t != null) {
            t.interrupt()
            Logger.info("MotionClock: 推进时钟已停止（${MotionEngine.status()}）")
        }
    }

    private fun deliverIntervalMs(): Long =
        FakeLoc.reportDurationMs.coerceIn(MIN_DELIVER_MS, MAX_DELIVER_MS)

    /**
     * **叫醒停摆的时钟**（幂等）：任何可能让世界动起来的意图都要叫一次 ——
     * 摇杆激活、路线开始播放、改速度、开会话（调用点在 `RemoteCommandHandler`）。
     */
    fun wake() {
        synchronized(idleLock) { idleLock.notifyAll() }
    }

    /**
     * 停摆等待。@return false = 被中断（`stop()` 要求退出）——**不能用 runCatching 吞掉中断**，
     * 否则 stop 之后线程仍会一直停在 wait 里（中断标志被异常清掉，再等下一个超时）。
     */
    private fun park(): Boolean = try {
        synchronized(idleLock) { idleLock.wait(IDLE_RECHECK_MS) }
        true
    } catch (_: InterruptedException) {
        false
    }

    /**
     * 主循环：**有意图才走拍子，没意图就停摆**。
     *
     * 稳态的三种形状：
     *  · 会话没开 → 停摆（等 `start` 唤醒）；
     *  · 会话开着但没推进意图（摇杆没按、路线没播）→ 世界不会动：先把这段空闲结算给体力，
     *    再停摆（等意图命令唤醒）。**这一条就是"不再轮询浪费性能"的落点** ——
     *    空闲时的稳态开销从 0.33% 单核降到 ~0；
     *  · 有意图（摇杆按住 / 路线在播）→ 50ms 一拍推进世界并投递。
     *
     * 停摆期间**体力照旧要回**：恢复是连续过程，所以醒来时用
     * [StaminaRuntime.reconcileNow] 把停摆这段一次性补上（读取路径也会补，见 writeStatus）。
     */
    private fun loop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (!FakeLoc.enable) {
                    lastBeatNanos = 0L
                    if (!park()) return
                    // 醒来（多半是 start 命令）：先把停摆这段的空闲恢复补上，再决定下一步
                    StaminaRuntime.reconcileNow()
                    continue
                }
                if (MotionEngine.mode() == MotionEngine.Mode.IDLE) {
                    StaminaRuntime.reconcileNow()
                    lastBeatNanos = 0L
                    if (!park()) return
                    StaminaRuntime.reconcileNow()
                    continue
                }
                Thread.sleep(BEAT_MS)
                beat()
            }
        } catch (_: InterruptedException) {
            // 正常停止路径（stop 会 interrupt）
        } catch (t: Throwable) {
            Logger.error("MotionClock: 推进时钟异常退出（位置将停住）", t)
        } finally {
            if (thread === Thread.currentThread()) thread = null
        }
    }

    private fun beat() {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastBeatNanos == 0L) BEAT_MS / 1000.0 else (now - lastBeatNanos) / 1e9
        lastBeatNanos = now

        // 1) 推进：位移 = 速度 × 体力倍率 × Δt（缩放就在这一处发生）
        val step = MotionEngine.beat(
            dtSec = dt,
            speed = FakeLoc.speed,
            multiplier = StaminaRuntime.multiplier(),
            curLat = FakeLoc.latitude,
            curLon = FakeLoc.longitude,
        )

        // 2) 体力：用**本拍真实推进的位移**结算（不是名义值）——旧实现同一口径
        StaminaRuntime.tick(FakeLoc.speed, step.meters)

        if (!step.moved) return

        // 3) 落点：走唯一入口（记录位移历史，供速度推算/静止检测/步频使用）
        RemoteCommandHandler.applyMotionCoordinate(step.lat, step.lon, step.bearing)

        // 4) 投递：按「上报间隔」出帧（客户端观测到的帧率与迁移前一致）
        if (lastDeliverNanos == 0L || now - lastDeliverNanos >= deliverIntervalMs() * 1_000_000L) {
            lastDeliverNanos = now
            LocationServiceHook.callOnLocationChanged(force = true)
        }
    }
}
