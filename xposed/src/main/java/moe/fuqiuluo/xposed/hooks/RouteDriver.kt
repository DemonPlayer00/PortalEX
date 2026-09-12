package moe.fuqiuluo.xposed.hooks

import android.os.Bundle
import android.os.SystemClock
import moe.fuqiuluo.xposed.RemoteCommandHandler
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.RouteMath

/**
 * **路线推进器（system_server 侧）**：整条折线一次交付，之后由这里按真实时间推进。
 *
 * ## 为什么必须由 system_server 推进
 * 之前是 App 每 tick 推一个坐标。App 是普通应用进程：退到后台就会被 Android 的
 * **Cached Apps Freezer** 冻结（实测 MI6/LineageOS 15：切后台那一秒起，运动循环整段停摆
 * 25.6s / 25.8s，期间 system_server 照常按保活节奏推帧，但位置一动不动 ⇒ 目标跑步
 * 应用看到"原地不动"，配速曲线上就是每 ~25s 一根尖峰）。
 * system_server 不会被冻，把推进权放这里，App 被冻/被杀都不再影响"世界在走"。
 *
 * ## 与 App 的分工（改了要同步改）
 * · App：开始时一次性 [setRoute]（展开后的折线 + tick 间隔）→ [start]；结束时 [stop]。
 *   **不再逐 tick 推送坐标**（`update_location` 只剩兼容/回退路径在用）。
 * · 模块：每 [tickMs] 推进 `speed × 真实Δt`，插值出位置与切线朝向，写坐标并投递一帧。
 *
 * ## 契约
 * · 只在 system_server 内生效（其它进程只是镜像，调用直接返回 false）；
 * · 会话没开（`FakeLoc.enable == false`）不推进 —— 会话由 `stop` 命令/开关关掉；
 * · 推进量按**真实 Δt**（同 App 侧修过的口径），单次上限 [MAX_ADVANCE_MS]：
 *   长冻结后不补出大跳变，避免被当成瞬移（那会让该帧速度归 0，正是要消灭的尖峰）；
 * · 到终点：精确落在末点、置 `finished`、自动停 —— App 通过 `route_state` 查询到
 *   之后播完成提示音（与原行为一致）。
 */
internal object RouteDriver {

    /** 默认 tick 间隔（ms）：与 App 的「上报间隔」默认值一致 */
    private const val DEFAULT_TICK_MS = 100L

    /** 单次推进的时间上限（ms）：与 App 侧同口径，见 KDoc */
    private const val MAX_ADVANCE_MS = 3_000.0

    /** 切线前视距离（米）：与 App 的 TANGENT_LOOKAHEAD_M 同值 */
    private const val TANGENT_LOOKAHEAD_M = 2.0

    private var lat = DoubleArray(0)
    private var lon = DoubleArray(0)
    private var cum = DoubleArray(0)

    @Volatile private var travelled = 0.0
    @Volatile private var running = false
    @Volatile private var finished = false
    @Volatile private var tickMs = DEFAULT_TICK_MS

    private var worker: Thread? = null
    private var lastTickNanos = 0L

    val isRunning: Boolean get() = running

    /** 路线总长（米）；未装载时为 0 */
    val total: Double get() = if (cum.isEmpty()) 0.0 else cum[cum.size - 1]

    /** 是否已经播完（App 据此播完成提示音，并复位 UI） */
    val isFinished: Boolean get() = finished

    /**
     * 装载路线（覆盖旧的并**复位进度**）。
     *
     * @param latArr 折线纬度序列（App 已展开：平滑段做过贝塞尔采样）
     * @param lonArr 折线经度序列，长度需与 [latArr] 一致
     * @return 是否装载成功（点数 < 2 / 长度不符 / 越界值 → false，不改变现有路线）
     */
    fun setRoute(latArr: DoubleArray?, lonArr: DoubleArray?, tickIntervalMs: Long): Boolean {
        if (latArr == null || lonArr == null) return false
        val n = minOf(latArr.size, lonArr.size)
        if (n < 2) {
            Logger.warn("RouteDriver: 路线点数不足（$n），拒绝装载")
            return false
        }
        for (i in 0 until n) {
            if (latArr[i] !in -90.0..90.0 || lonArr[i] !in -180.0..180.0) {
                Logger.error("RouteDriver: 第 $i 个点越界（${latArr[i]}, ${lonArr[i]}），拒绝装载")
                return false
            }
        }
        val newLat = latArr.copyOf(n)
        val newLon = lonArr.copyOf(n)
        val newCum = RouteMath.cumulative(newLat, newLon)
        synchronized(this) {
            lat = newLat
            lon = newLon
            cum = newCum
            travelled = 0.0
            finished = false
            tickMs = tickIntervalMs.coerceIn(20L, 1_000L)
        }
        Logger.info("RouteDriver: 路线已装载 $n 点 / ${"%.1f".format(total)}m，tick=${tickMs}ms")
        return true
    }

    /**
     * 开始推进（幂等）。会话没开、路线没装载、已在跑 —— 都返回 false 且不改状态。
     * 重跑一条已播完的路线：调用方应先 [setRoute]（它会复位进度）。
     */
    fun start(): Boolean {
        if (!FakeLoc.isSystemServerProcess) return false
        if (!FakeLoc.enable) {
            Logger.warn("RouteDriver: 会话未启动，拒绝起播")
            return false
        }
        if (cum.size < 2) {
            Logger.warn("RouteDriver: 未装载路线，拒绝起播")
            return false
        }
        synchronized(this) {
            if (running) return true
            if (finished) {
                // 播完后再点播放：从头来（与 App 的 resetPlayback 语义一致）
                travelled = 0.0
                finished = false
            }
            running = true
            lastTickNanos = 0L
        }
        worker = Thread({ loop() }, "PortalRouteDriver").apply {
            isDaemon = true
            start()
        }
        Logger.info("RouteDriver: 起播（${"%.1f".format(total)}m，速度 ${FakeLoc.speed}m/s）")
        return true
    }

    /** 停止推进（幂等）：不动坐标、保留进度与路线，供再次 [start] */
    fun stop() {
        val wasRunning = running
        running = false
        worker?.interrupt()
        worker = null
        if (wasRunning) Logger.info("RouteDriver: 已停止（进度 ${"%.1f".format(travelled)}/${"%.1f".format(total)}m）")
    }

    /** 清空路线与进度（换路线 / 会话关闭） */
    fun clear() {
        stop()
        synchronized(this) {
            lat = DoubleArray(0)
            lon = DoubleArray(0)
            cum = DoubleArray(0)
            travelled = 0.0
            finished = false
        }
    }

    /** 进度/状态写进回包（`route_state` 用） */
    fun fillState(bundle: Bundle) {
        bundle.putBoolean(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.ROUTE_RUNNING, running)
        bundle.putBoolean(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.ROUTE_FINISHED, finished)
        bundle.putDouble(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.ROUTE_TRAVELLED, travelled)
        bundle.putDouble(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.ROUTE_TOTAL, total)
        bundle.putDouble(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.LAT, FakeLoc.latitude)
        bundle.putDouble(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.LON, FakeLoc.longitude)
    }

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(tickMs)
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            if (!FakeLoc.enable) {
                // 会话被关（App 的"停止模拟"/停用开关）⇒ 推进必须一起停，
                // 否则会出现"没有会话但位置自己在走"的幽灵推进
                Logger.info("RouteDriver: 会话已关闭，自动停止推进")
                running = false
                break
            }
            val now = System.nanoTime()
            val dtMs = if (lastTickNanos == 0L) tickMs.toDouble()
            else (now - lastTickNanos) / 1_000_000.0
            lastTickNanos = now
            if (!advance(dtMs)) break
        }
        worker = null
    }

    /**
     * 推进一次（**纯逻辑 + 写坐标 + 投递一帧**，可单测）：返回是否仍在推进。
     *
     * 距离按**真实 Δt** 算（[dtMs] 由调用方给出），上限 [MAX_ADVANCE_MS]。
     */
    internal fun advance(dtMs: Double): Boolean {
        synchronized(this) {
            if (cum.size < 2) return false
            // 会话没开就别推进：直接调用（非 loop 路径）也要拦住，否则会出现
            // "没有会话但位置自己在走"的幽灵推进
            if (!FakeLoc.enable) return false
            val step = FakeLoc.speed.coerceAtLeast(0.0) * (dtMs.coerceIn(1.0, MAX_ADVANCE_MS) / 1000.0)
            travelled = (travelled + step).coerceAtMost(total)
            val (la, lo) = RouteMath.pointAt(lat, lon, cum, travelled)
            val brg = RouteMath.bearingAt(lat, lon, cum, travelled, TANGENT_LOOKAHEAD_M)
            RemoteCommandHandler.applyRouteCoordinate(la, lo, brg)
            if (travelled >= total) {
                finished = true
                running = false
                Logger.info("RouteDriver: 已到达终点（${"%.1f".format(total)}m）")
                return false
            }
            return true
        }
    }

    /** 诊断串（Test 页/日志用） */
    fun status(): String =
        "route=${cum.size}pt total=${"%.0f".format(total)}m travelled=${"%.1f".format(travelled)}m " +
                "running=$running finished=$finished tick=${tickMs}ms"
}
