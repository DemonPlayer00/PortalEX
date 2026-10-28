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
        /** **已展开的采样点数**（惰性展开：随播放推进增长，不是端点数） */
        val points: Int,
        /** 原始端点数（App 上传的那个数组的长度） */
        val endpoints: Int,
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

    private var lats = DoubleArray(0)
    private var lons = DoubleArray(0)

    /** 每个路径点的累计弧长（米），[cum].last() = 已展开部分的长度 */
    private var cum = DoubleArray(0)

    // ---- 按需展开（lazy）----
    /** 原始端点与逐段平滑标志：**不在上传时展开**，只在播放/取位走到时算下一段 */
    private var rawLat = DoubleArray(0)
    private var rawLon = DoubleArray(0)
    private var rawSmooth = BooleanArray(0)
    /** 已经展开到第几段 */
    private var expandedSeg = 0
    /** 路线全长（各段长度之和，平滑段按贝塞尔弧长估算） */
    private var totalMeters = 0.0

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
    fun setRoute(
        pointsLat: DoubleArray?,
        pointsLon: DoubleArray?,
        smooth: BooleanArray? = null,
    ): Int {
        // 校验、拷贝与**全长计算**都在锁外：平滑段要按 16 点采样估弧长，
        // 一条长路线可能耗掉毫秒级；这段时间不该挡住 50ms 的推进拍。
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
            synchronized(lock) { clearRouteLocked() }
            return 0
        }
        val newLat = la.toDoubleArray()
        val newLon = lo.toDoubleArray()
        val newSmooth = BooleanArray(maxOf(0, newLat.size - 1)) { smooth?.getOrElse(it) { false } ?: false }
        val newTotal = computeTotalMeters(newLat, newLon, newSmooth)

        synchronized(lock) {
            // 只保存端点 + 逐段平滑标志；**不在这里展开采样点**。
            // 展开是按需的：播放/取位每走一步才把「刚走过的那一段」算出来，
            // 所以一条 60 公里的路线也不会在上传瞬间生成六万个点。
            rawLat = newLat
            rawLon = newLon
            rawSmooth = newSmooth
            lats = DoubleArray(0)
            lons = DoubleArray(0)
            cum = DoubleArray(0)
            expandedSeg = 0
            totalMeters = newTotal
            ensureExpandedTo(0.0)   // 只放起点，代价与路线长度无关
            travelled = 0.0
            completed = false
            playing = false
            snapped = false
            return newLat.size
        }
    }

    /**
     * 播放/停止路线。**只有 [setRoute] 换路线时才归零进度、下一拍对齐新起点**；
     * 中途停播再播接着走，播完后停在终点（2026-10-28 语义，见 85519e57）。
     */
    fun setPlaying(play: Boolean): Boolean {
        synchronized(lock) {
            if (!play) {
                playing = false
                return true
            }
            // 判定用**原始端点**：展开数组是惰性的，播放开始时可能还是空的
            if (rawLat.size < 2) return false
            // 这里不动 snapped：播到最后一个点后停住，再点播放也仍停在终点，
            // 直到换一条路线才重新从起点开始。
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
        Status(mode(), playing, completed, travelled, distance(), lats.size, rawLat.size)
    }

    /** 路线全长：各段长度之和（与"已展开到哪"无关） */
    fun distance(): Double = totalMeters

    /**
     * 路线起点。选中路线但**尚未播放**时，系统侧要把位置摆到起点，
     * 否则界面停在上一段播放的终点，看起来像"选了没生效"。
     */
    fun routeStart(): Pair<Double, Double>? = synchronized(lock) {
        if (rawLat.size < 2) null else rawLat[0] to rawLon[0]
    }

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

    // ---- 按需展开：只算「刚走过的那一段」 ----

    /** 路线全长：各段长度之和（平滑段用 16 点采样估算弧长）。纯函数，可在锁外跑。 */
    private fun computeTotalMeters(lat: DoubleArray, lon: DoubleArray, smooth: BooleanArray): Double {
        var sum = 0.0
        for (i in 0 until lat.size - 1) {
            sum += segmentLength(lat, lon, smooth, i)
        }
        return sum
    }

    private fun segmentLength(lat: DoubleArray, lon: DoubleArray, smooth: BooleanArray, i: Int): Double {
        val sLat = lat[i]
        val sLon = lon[i]
        val eLat = lat[i + 1]
        val eLon = lon[i + 1]
        val chord = WorldMath.haversine(sLat, sLon, eLat, eLon)
        if (!smooth.getOrElse(i) { false } || chord < 2.0) return chord
        var len = 0.0
        var pLat = sLat
        var pLon = sLon
        for (k in 1..16) {
            val (a, o) = bezierPoint(lat, lon, i, k / 16.0)
            len += WorldMath.haversine(pLat, pLon, a, o)
            pLat = a
            pLon = o
        }
        return len
    }

    /** 贝塞尔上的点：控制点由相邻直线方位给出（切线 C1 连续） */
    private fun bezierPoint(lat: DoubleArray, lon: DoubleArray, i: Int, t: Double): Pair<Double, Double> {
        val sLat = lat[i]
        val sLon = lon[i]
        val eLat = lat[i + 1]
        val eLon = lon[i + 1]
        val own = WorldMath.calculateBearing(sLat, sLon, eLat, eLon)
        val aziIn = if (i > 0) WorldMath.calculateBearing(lat[i - 1], lon[i - 1], sLat, sLon) else own
        val aziOut = if (i + 1 < lat.size - 1) {
            WorldMath.calculateBearing(eLat, eLon, lat[i + 2], lon[i + 2])
        } else {
            own
        }
        val chord = WorldMath.haversine(sLat, sLon, eLat, eLon)
        val c1 = direct(sLat, sLon, chord / 3.0, aziIn)
        val c2 = direct(eLat, eLon, chord / 3.0, (aziOut + 180.0) % 360.0)
        val u = 1.0 - t
        val outLat = u * u * u * sLat + 3 * u * u * t * c1.first + 3 * u * t * t * c2.first + t * t * t * eLat
        val outLon = u * u * u * sLon + 3 * u * u * t * c1.second + 3 * u * t * t * c2.second + t * t * t * eLon
        return outLat to outLon
    }

    /** 把展开前缀补到 [target] 米：每次只算「还没走过的那一段」，不预生成整条路线 */
    private fun ensureExpandedTo(target: Double) {
        if (rawLat.size < 2) return
        if (cum.isEmpty()) appendPoint(rawLat[0], rawLon[0])
        while (expandedSeg < rawLat.size - 1 && cum.last() < target) {
            val i = expandedSeg
            val sLat = rawLat[i]
            val sLon = rawLon[i]
            val eLat = rawLat[i + 1]
            val eLon = rawLon[i + 1]
            if (rawSmooth.getOrElse(i) { false } && WorldMath.haversine(sLat, sLon, eLat, eLon) >= 2.0) {
                val len = segmentLength(rawLat, rawLon, rawSmooth, i)
                val n = maxOf(4, len.toInt())
                for (k in 1 until n) {
                    val p = bezierPoint(rawLat, rawLon, i, k.toDouble() / n)
                    appendPoint(p.first, p.second)
                }
            }
            appendPoint(eLat, eLon)
            expandedSeg = i + 1
        }
        if (expandedSeg >= rawLat.size - 1) {
            val lastLat = rawLat.last()
            val lastLon = rawLon.last()
            if (WorldMath.haversine(lats.last(), lons.last(), lastLat, lastLon) > 0.01) {
                appendPoint(lastLat, lastLon)
            }
        }
    }

    private fun appendPoint(lat: Double, lon: Double) {
        val prevLat = lats.lastOrNull() ?: lat
        val prevLon = lons.lastOrNull() ?: lon
        val d = if (lats.isEmpty()) 0.0 else WorldMath.haversine(prevLat, prevLon, lat, lon)
        lats = lats.copyOf(lats.size + 1).also { it[it.size - 1] = lat }
        lons = lons.copyOf(lons.size + 1).also { it[it.size - 1] = lon }
        val lastCum = cum.lastOrNull() ?: 0.0
        cum = cum.copyOf(cum.size + 1).also { it[it.size - 1] = lastCum + d }
    }

    /**
     * **按时间采样**：从上一拍（[dtSec] 秒前）推进到现在应该在的位置。
     * 纯函数，不改状态——供 getCurrentLocation 之类的"立即取位"用。
     */
    fun positionAhead(dtSec: Double, speed: Double, multiplier: Double): Step = synchronized(lock) {
        if (!playing || rawLat.size < 2) return@synchronized Step(false, 0.0, 0.0, 0.0, null)
        val m = if (multiplier.isFinite() && multiplier > 0.0) multiplier else 1.0
        val dt = dtSec.coerceIn(0.0, 5.0)
        val meters = (travelled + speed * m * dt).coerceIn(0.0, distance())
        // 只把「走到过的这一段」补出来；再远的部分保持未展开
        ensureExpandedTo(meters + 1.0)
        val p = interpolateAt(indexAt(meters), meters)
        Step(true, meters - travelled, p.first, p.second, bearingAt(meters))
    }

    private fun clearRouteLocked() {
        lats = DoubleArray(0)
        lons = DoubleArray(0)
        cum = DoubleArray(0)
        rawLat = DoubleArray(0)
        rawLon = DoubleArray(0)
        rawSmooth = BooleanArray(0)
        expandedSeg = 0
        totalMeters = 0.0
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
                ensureExpandedTo(1.0)
                val p = interpolateAt(indexAt(0.0), 0.0)
                return@synchronized Step(true, 0.0, p.first, p.second, bearingAt(0.0))
            }
            if (wantMeters <= 0.0) return@synchronized Step(false, 0.0, curLat, curLon, null)
            val total = distance()
            // 按需展开：只把「本拍走到的地方」算出来
            ensureExpandedTo((travelled + wantMeters).coerceAtMost(total) + 1.0)
            val before = travelled
            travelled = (travelled + wantMeters).coerceAtMost(total)
            val actual = travelled - before
            val done = travelled >= total - 1e-6
            if (done) {
                playing = false
                completed = true
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
        if (lats.size < 2) return Pair(rawLat.firstOrNull() ?: 0.0, rawLon.firstOrNull() ?: 0.0)
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
        if (rawLat.size < 2) return rockerBearing
        val total = distance()
        val ahead = (dist + TANGENT_LOOKAHEAD_M).coerceAtMost(total)
        ensureExpandedTo(ahead + 0.5)
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
