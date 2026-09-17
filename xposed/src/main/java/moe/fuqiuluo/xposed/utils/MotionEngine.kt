package moe.fuqiuluo.xposed.utils

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * **系统侧推进引擎**（统一架构迁移：路线播放 + 摇杆步进）。
 *
 * ## 为什么推进必须在这一侧
 *
 * 交付给应用的坐标是绝对量，"模块侧事后按倍率缩放"只能做出一个永远落后的滞后积分器
 * ——App 的弧长进度一路跑向终点，交付流却永远差一截，路线在应用看来走不完
 * （见 `docs/sensor-architecture.md` 第三节的硬约束）。所以**谁推进、谁缩放必须是同一处**：
 * 本对象按 `速度 × 体力倍率 × Δt` 推进世界位置，缩放就在推进公式里发生，没有第二套口径。
 *
 * ## 分工
 *
 * | 谁 | 做什么 |
 * | --- | --- |
 * | 本对象 | 持有路线数据与世界推进状态；每拍给出新坐标/朝向/位移 |
 * | 模块时钟（MotionClock） | 调用 [beat]，把结果写进 [FakeLoc] 并投递一帧 |
 * | App | 只上传路线数据、开关播放、下发摇杆方向；进度靠回读 |
 *
 * ## 刻意不碰 Android
 *
 * 本对象只做纯数学（[WorldMath] + 等距近似小步推进），不引用 `Location`/`SystemClock`，
 * 于是**可以在 JVM 上单测**：路线插值、切线、播完判定、摇杆步进都是纯函数行为。
 */
object MotionEngine {

    enum class Mode { IDLE, ROCKER, ROUTE }

    /** 切线前视距离（米）：朝向取路径上向前该距离处的方向，短距离不会乱跳 */
    private const val TANGENT_LOOKAHEAD_M = 2.0

    private const val EARTH_RADIUS_M = 6371000.0

    data class Status(
        val mode: Mode,
        val playing: Boolean,
        /** 是否刚播完（一次性，见 [takeCompleted]） */
        val completed: Boolean,
        val travelledMeters: Double,
        val distanceMeters: Double,
        val points: Int,
    )

    /**
     * 一拍的结果。
     *
     * [meters] 是**本拍真实推进的位移**（路线末拍会被截断到剩余弧长）——
     * 体力绑定的就是它，不是名义值。
     */
    data class Step(
        val moved: Boolean,
        val meters: Double,
        val lat: Double,
        val lon: Double,
        /** 非 null = 本拍有新的朝向（路线切线 / 摇杆方向） */
        val bearing: Double?,
    )

    private val lock = Any()

    /**
     * **采样表**（可选）：native 一次展开出的 `[lat, lon, bearing] * count`，按弧长等间距。
     *
     * 注入式而不是自己去调 native：`utils` 这一层必须保持"纯数学、无 Android 依赖"
     * （整个 MotionEngine 能在 JVM 上单测就靠这条），所以"表从哪来"由调用方决定 ——
     * system_server 侧用 `BinderSensorNative.routeExpand`，测试里用 Kotlin 参考实现。
     * 表为 null 时回落到原来的"每拍二分 + 现场算朝向"路径，语义完全一致。
     */
    private var table: DoubleArray? = null
    private var tableStep = 0.0

    /** 查表结果（[sampleInto] 的输出）。只在 [lock] 内使用，避免每拍装箱。 */
    private var sLat = 0.0
    private var sLon = 0.0
    private var sBearing = 0.0

    /**
     * 注入采样表（`null` = 关闭并回落）。**表必须与当前路线同源**，
     * 所以 `setRoute` 会把它清掉，调用方要在装载路线之后立刻注入。
     */
    fun setSamplingTable(t: DoubleArray?, stepMeters: Double) = synchronized(lock) {
        table = if (t != null && t.size >= 6 && stepMeters > 0.0) t else null
        tableStep = if (table != null) stepMeters else 0.0
    }

    /** 当前是否走查表路径（诊断用） */
    fun hasSamplingTable(): Boolean = synchronized(lock) { table != null }

    /**
     * 查表取弧长 [dist] 处的位置与朝向 —— **O(1)**：一次除法定位、一次线性插值，
     * 没有二分、没有三角函数。调用方必须已持有 [lock]。
     */
    private fun sampleInto(dist: Double) {
        val t = table ?: return
        val n = t.size / 3
        var idx = dist / tableStep
        if (!idx.isFinite() || idx < 0.0) idx = 0.0
        var i = idx.toInt()
        if (i > n - 2) i = n - 2
        if (i < 0) i = 0
        val f = (idx - i).coerceIn(0.0, 1.0)
        val a = i * 3
        val b = a + 3
        sLat = t[a] + (t[b] - t[a]) * f
        sLon = t[a + 1] + (t[b + 1] - t[a + 1]) * f
        // 朝向是环形量：按**最短弧**插值，跨 0/360 才不会甩一整圈
        val b0 = t[a + 2]
        var d = (t[b + 2] - b0) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        sBearing = (b0 + d * f + 360.0) % 360.0
    }

    private var lats = DoubleArray(0)
    private var lons = DoubleArray(0)

    /** 每个路径点的累计弧长（米），[cum].last() = 路线全长 */
    private var cum = DoubleArray(0)

    private var travelled = 0.0
    private var playing = false
    private var completed = false

    /** 路线播放开始后是否已把位置对齐到起点（对齐那一拍只投坐标、不计位移） */
    private var snapped = false

    @Volatile private var rockerActive = false
    @Volatile private var rockerBearing = 0.0

    // ---- 路线数据（App 上传） ----

    /**
     * 上传/替换路线（**展开后的播放路径点**，~1m 间距，平滑段已由 App 展开成采样点）。
     *
     * 为什么上传展开后的点而不是端点：路线平滑（贝塞尔采样）属于**路线编辑**的产物，
     * 编辑器在哪一侧，展开就在哪一侧；模块只负责"沿这条折线按弧长推进"。
     * 少于 2 个有效点视为"清空路线"。
     *
     * @return 实际接受的点数（0 = 已清空）
     */
    fun setRoute(pointsLat: DoubleArray?, pointsLon: DoubleArray?): Int {
        synchronized(lock) {
            val n = minOf(pointsLat?.size ?: 0, pointsLon?.size ?: 0)
            val la = ArrayList<Double>(n)
            val lo = ArrayList<Double>(n)
            for (i in 0 until n) {
                val a = pointsLat!![i]
                val o = pointsLon!![i]
                if (!a.isFinite() || !o.isFinite()) continue
                if (a < -90.0 || a > 90.0 || o < -180.0 || o > 180.0) continue
                la.add(a)
                lo.add(o)
            }
            if (la.size < 2) {
                clearRouteLocked()
                return 0
            }
            lats = la.toDoubleArray()
            lons = lo.toDoubleArray()
            cum = DoubleArray(lats.size)
            for (i in 1 until lats.size) {
                cum[i] = cum[i - 1] + WorldMath.haversine(lats[i - 1], lons[i - 1], lats[i], lons[i])
            }
            travelled = 0.0
            completed = false
            playing = false
            snapped = false
            // 表必须与路线同源：换了路线就作废，等调用方注入新的
            table = null
            tableStep = 0.0
            return lats.size
        }
    }

    /**
     * 播放/停止路线。开始播放时：**已经开始过就从头播**（播完后再点同样是重播），
     * 与旧实现 `resetPlayback` 的语义一致（重选/重播都从头开始，不残留旧进度）。
     */
    fun setPlaying(play: Boolean): Boolean {
        synchronized(lock) {
            if (!play) {
                playing = false
                return true
            }
            if (lats.size < 2) return false
            if (completed || travelled >= cum.last() - 1e-6) {
                travelled = 0.0
                completed = false
            }
            snapped = false
            playing = true
            return true
        }
    }

    /** 摇杆意图：是否按住（或锁定后继续走）+ 方向。自动播放中由路线切线接管，见 [beat] */
    fun setRocker(active: Boolean, bearing: Double) {
        rockerActive = active
        rockerBearing = normalize(bearing)
    }

    fun mode(): Mode = when {
        playing -> Mode.ROUTE
        rockerActive -> Mode.ROCKER
        else -> Mode.IDLE
    }

    fun status(): Status = synchronized(lock) {
        Status(mode(), playing, completed, travelled, distance(), lats.size)
    }

    fun distance(): Double = if (cum.isEmpty()) 0.0 else cum.last()

    fun travelled(): Double = synchronized(lock) { travelled }

    /** 一次性读取"刚播完"（App 轮询到之后要放提示音/收尾，读一次即清） */
    fun takeCompleted(): Boolean = synchronized(lock) {
        val c = completed
        completed = false
        c
    }

    /**
     * **会话停止**：停推进、清摇杆意图与播放进度，但**保留路线数据**。
     *
     * 路线是"数据"（像配置），不是会话状态：下一次会话直接接着用，App 也不必重新上传
     * —— 否则"停止模拟 → 再启动 → 点播放"会因为 App 侧以为路线还在（`routeUploaded`）
     * 而系统侧已经清空，变成**点了播放却不动**这种最难查的静默失败。
     */
    fun stopSession() = synchronized(lock) {
        rockerActive = false
        playing = false
        completed = false
        snapped = false
        travelled = 0.0
    }

    /** 清空一切（路线也丢）：路线被删除、或测试复位时用 */
    fun reset() = synchronized(lock) {
        clearRouteLocked()
        rockerActive = false
    }

    private fun clearRouteLocked() {
        lats = DoubleArray(0)
        lons = DoubleArray(0)
        cum = DoubleArray(0)
        travelled = 0.0
        playing = false
        completed = false
        snapped = false
    }

    /**
     * 推进一拍。
     *
     * @param dtSec 本拍时长（秒），由模块时钟给（≤0 视为无效）
     * @param speed 名义速度（m/s，配置速度）
     * @param multiplier 体力倍率（[StaminaRuntime.multiplier]）
     * @param curLat 当前世界纬度（摇杆模式从当前位置推进）
     * @param curLon 当前世界经度
     */
    fun beat(
        dtSec: Double,
        speed: Double,
        multiplier: Double,
        curLat: Double,
        curLon: Double,
    ): Step = synchronized(lock) {
        val dt = dtSec.coerceIn(0.0, 5.0)
        val m = if (multiplier.isFinite() && multiplier > 0.0) multiplier else 1.0
        val wantMeters = speed * m * dt

        if (playing) {
            // 对齐那一拍：把位置摆到路线起点（只给坐标，不计位移 —— 否则会凭空消耗体力）
            if (!snapped) {
                snapped = true
                if (table != null) {
                    sampleInto(0.0)
                    return@synchronized Step(true, 0.0, sLat, sLon, sBearing)
                }
                val p = interpolateAt(indexAt(0.0), 0.0)
                return@synchronized Step(true, 0.0, p.first, p.second, bearingAt(0.0))
            }
            if (wantMeters <= 0.0) return@synchronized Step(false, 0.0, curLat, curLon, null)
            val total = distance()
            val before = travelled
            travelled = (travelled + wantMeters).coerceAtMost(total)
            val actual = travelled - before
            val done = travelled >= total - 1e-6
            if (done) {
                playing = false
                completed = true
            }
            // 查表路径：每拍 O(1)（无二分、无三角函数）；表缺失时回落现场计算，语义一致
            if (table != null) {
                sampleInto(travelled)
                return@synchronized Step(true, actual, sLat, sLon, sBearing)
            }
            val p = interpolateAt(indexAt(travelled), travelled)
            return@synchronized Step(true, actual, p.first, p.second, bearingAt(travelled))
        }

        if (rockerActive) {
            if (wantMeters <= 0.0) return@synchronized Step(false, 0.0, curLat, curLon, null)
            val p = direct(curLat, curLon, wantMeters, rockerBearing)
            return@synchronized Step(true, wantMeters, p.first, p.second, rockerBearing)
        }

        Step(false, 0.0, curLat, curLon, null)
    }

    // ---- 纯几何 ----

    /** 沿 [angle] 走 [n] 米（等距近似小步推进；单拍 ~0.15m，误差可忽略） */
    private fun direct(lat: Double, lon: Double, n: Double, angle: Double): Pair<Double, Double> {
        val radiusInDegrees = n / EARTH_RADIUS_M * (180.0 / PI)
        val newLat = lat + radiusInDegrees * cos(Math.toRadians(angle))
        val cosLat = cos(Math.toRadians(lat))
        val newLon = if (kotlin.math.abs(cosLat) < 1e-12) {
            lon
        } else {
            lon + radiusInDegrees * sin(Math.toRadians(angle)) / cosLat
        }
        return Pair(newLat, newLon)
    }

    /** 找到包含累计距离 [dist] 的区间右端点索引（从 1 起；不做状态修改） */
    private fun indexAt(dist: Double): Int {
        if (cum.size < 2) return 0
        var lo = 1
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] < dist) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 在区间 [index-1, index] 内按累计弧长插值 */
    private fun interpolateAt(index: Int, dist: Double): Pair<Double, Double> {
        if (lats.size < 2) return Pair(lats.firstOrNull() ?: 0.0, lons.firstOrNull() ?: 0.0)
        val i = index.coerceIn(1, lats.size - 1)
        val segLen = cum[i] - cum[i - 1]
        val t = if (segLen <= 1e-9) 0.0 else ((dist - cum[i - 1]) / segLen).coerceIn(0.0, 1.0)
        return Pair(
            lats[i - 1] + (lats[i] - lats[i - 1]) * t,
            lons[i - 1] + (lons[i] - lons[i - 1]) * t,
        )
    }

    /**
     * 朝向 = 路线切线：取 [dist] 处与向前 [TANGENT_LOOKAHEAD_M] 米处的连线方位
     * （与旧 App 实现同一口径 —— 朝向由位置显式下发，不走位移推算）。
     */
    private fun bearingAt(dist: Double): Double {
        if (lats.size < 2) return rockerBearing
        val total = distance()
        val ahead = (dist + TANGENT_LOOKAHEAD_M).coerceAtMost(total)
        val a = interpolateAt(indexAt(dist), dist)
        val b = interpolateAt(indexAt(ahead), ahead)
        if (WorldMath.haversine(a.first, a.second, b.first, b.second) < 1e-6) {
            // 前视点与当前点重合（已在终点）⇒ 用最后一段的方位
            val i = lats.size - 1
            return WorldMath.calculateBearing(lats[i - 1], lons[i - 1], lats[i], lons[i])
        }
        return WorldMath.calculateBearing(a.first, a.second, b.first, b.second)
    }

    private fun normalize(bearing: Double): Double {
        if (!bearing.isFinite()) return 0.0
        val b = bearing % 360.0
        return if (b < 0) b + 360.0 else b
    }
}
