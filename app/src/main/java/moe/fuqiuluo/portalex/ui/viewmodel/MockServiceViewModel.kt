package moe.fuqiuluo.portalex.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.fuqiuluo.portalex.android.coro.CoroutineController
import moe.fuqiuluo.portalex.ext.accuracy
import moe.fuqiuluo.portalex.ext.altitude
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.ui.mock.HistoricalLocation
import moe.fuqiuluo.portalex.ui.mock.HistoricalRoute
import moe.fuqiuluo.portalex.ui.mock.Rocker
import moe.fuqiuluo.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic
import kotlin.math.abs

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    var isRockerLocked = false
    val rockerCoroutineController = CoroutineController()

    /**
     * 播放路径点：路线按段展开（平滑段 = 贝塞尔曲线采样序列，普通段 = 端点直连）。
     * [segment] = 所属段索引 i（段 = 端点 i → i+1）。
     * [spacing] = 与前一播放点的距离；[cum] = 自路径起点的累计距离（米）。
     */
    private class PathPoint(
        val lat: Double,
        val lon: Double,
        val segment: Int,
        val spacing: Double,
        val cum: Double
    )

    /** 当前展开的播放路径（路线切换时重建） */
    private var cachedRoute: HistoricalRoute? = null
    private var pathPoints: List<PathPoint> = emptyList()

    /** 弧长推进状态：已走距离、总长度、插值游标 */
    private var routeTravelled = 0.0
    private var routeDistance = 0.0
    private var pathCursor = 1
    private var pathInitialized = false

    /** 切线前视距离（米）：朝向取路径上向前该距离处的方向，短距离不会乱跳 */
    private val TANGENT_LOOKAHEAD_M = 2.0

    /** 自动播放中：手动摇杆不得写位置/朝向（避免与路线播放器双写互相打断） */
    val isAutoPlaying: Boolean
        get() = ::rocker.isInitialized && rocker.autoStatus

    /**
     * 重置自动播放器全部状态（路线切换/重选、播完、异常后统一入口）：
     * [route] 非空时同时重建播放路径；为 null 则清空（下次播放从零开始）。
     */
    private fun resetPlayback(route: HistoricalRoute?) {
        cachedRoute = route
        pathPoints = if (route == null) emptyList() else buildPath(route)
        routeDistance = pathPoints.lastOrNull()?.cum ?: 0.0
        routeTravelled = 0.0
        pathCursor = 1
        pathInitialized = false
    }

    /** 选中路线：立即完整重置自动播放器（重选同一条也从头播放，不残留旧进度） */
    fun selectRouteForPlayback(route: HistoricalRoute) {
        selectedRoute = route
        resetPlayback(route)
    }

    /**
     * 清除选中路线（路线被删除等）：彻底重置播放器并关闭自动播放，
     * 避免播放器继续持有已不存在的路线（幽灵路线）。
     */
    fun clearSelectedRoute() {
        selectedRoute = null
        resetPlayback(null)
        if (::rocker.isInitialized) {
            rocker.autoStatus = false
        }
    }

    /**
     * 手动摇杆角度：自动播放中忽略——播放中方向由路线切线控制，
     * 否则两者互相抢占（表现出来就是「碰一下摇杆播放就乱了」）。
     */
    fun handleRockerAngle(angle: Double) {
        if (isAutoPlaying) return
        val lm = locationManager
        if (lm != null) {
            MockServiceHelper.setBearing(lm, angle)
        }
        FakeLoc.bearing = angle
        FakeLoc.hasBearings = true
    }

    /**
     * 展开路线为播放路径点序列：
     * - 普通段：端点直连（段起点加入，相邻段共享端点）
     * - 平滑段：整段替换为三次贝塞尔曲线采样（起点切线 = 前段直线方位，终点切线 = 后段
     *   直线方位；首/末段缺失的一侧用自身方位），采样间距 ~1 米——方向变化平缓，
     *   角度计指针自然转动，无突发曲率变化。
     */
    private fun buildPath(route: HistoricalRoute): List<PathPoint> {
        val points = route.route
        // 端点不足 2 个构不成任何线段（脏数据/未绘制路线）→ 返回空路径；
        // 调用方 resetPlayback 对空路径一律按「不可播放」处理，绝不抛异常。
        if (points.size < 2) return emptyList()
        val path = mutableListOf<PathPoint>()
        fun append(p: Pair<Double, Double>, segment: Int) {
            val prev = path.lastOrNull()
            val spacing = if (prev == null) {
                0.0
            } else {
                Geodesic.WGS84.Inverse(prev.lat, prev.lon, p.first, p.second).s12
            }
            path.add(PathPoint(p.first, p.second, segment, spacing, (prev?.cum ?: 0.0) + spacing))
        }
        for (i in 0 until points.size - 1) {
            if (route.isSmooth(i)) {
                sampleBezier(points, i).forEach { append(it, i) }
            } else {
                append(points[i], i)
            }
        }
        // 末段终点（与最后一段共享 segment 索引）
        append(points.last(), points.size - 2)
        return path
    }

    /**
     * 三次贝塞尔采样（**包含起点、不含终点**：起点 = 端点本身，保证曲线穿过所有端点；
     * 终点由下一段（或末段）加入，相邻段共享端点）。
     * 控制点距离 = 段长 / 3（Hermite 张力：C1 连续、曲率平缓无突变）；
     * 参照角 = 相邻原始直线的无平滑几何方位（平滑段之间不互相耦合）。
     */
    private fun sampleBezier(points: List<Pair<Double, Double>>, i: Int): List<Pair<Double, Double>> {
        val s = points[i]
        val e = points[i + 1]
        val ownAzi = azimuthOf(s, e)
        // 起点切线 = 前段直线方位（首段无前段 → 自身方位）
        val aziIn = if (i > 0) azimuthOf(points[i - 1], s) else ownAzi
        // 终点切线 = 后段直线方位（末段无后段 → 自身方位）
        val aziOut = if (i + 1 < points.size - 1) azimuthOf(e, points[i + 2]) else ownAzi
        val len = Geodesic.WGS84.Inverse(s.first, s.second, e.first, e.second).s12
        val p1 = Geodesic.WGS84.Direct(s.first, s.second, aziIn, len / 3.0)
        // 终点控制点位于终点之前（沿后段方位的反方向）
        val p2 = Geodesic.WGS84.Direct(e.first, e.second, (aziOut + 180.0) % 360.0, len / 3.0)
        // ~1 米/采样点，至少 4 段（保证曲线形状与方向渐变）
        val n = maxOf(4, (len / 1.0).toInt())
        return (0 until n).map { k ->
            val t = k.toDouble() / n
            Pair(
                bezier(s.first, p1.lat2, p2.lat2, e.first, t),
                bezier(s.second, p1.lon2, p2.lon2, e.second, t)
            )
        }
    }

    /** 三次贝塞尔分量（u = 1 - t） */
    private fun bezier(a: Double, b: Double, c: Double, d: Double, t: Double): Double {
        val u = 1.0 - t
        return u * u * u * a + 3.0 * u * u * t * b + 3.0 * u * t * t * c + t * t * t * d
    }

    /** 无平滑直线方位（0..360） */
    private fun azimuthOf(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val az = Geodesic.WGS84.Inverse(a.first, a.second, b.first, b.second).azi1
        return if (az < 0) az + 360.0 else az
    }

    /**
     * 在路径上按累计距离线性插值出坐标（采样点 ~1m 间隔，误差可忽略）；
     * 游标单调递增（已走距离只增不减）。
     */
    private fun pointAt(dist: Double): Pair<Double, Double> {
        if (dist <= 0.0) return Pair(pathPoints[0].lat, pathPoints[0].lon)
        pathCursor = pathIndexAt(dist, pathCursor)
        return interpolateAt(pathCursor, dist)
    }

    /** 切线前视点（不推进主游标）：不改变播放位置游标，供朝向计算使用 */
    private fun lookaheadPoint(dist: Double): Pair<Double, Double> {
        if (dist >= routeDistance) {
            return Pair(pathPoints.last().lat, pathPoints.last().lon)
        }
        val index = pathIndexAt(dist, pathCursor)
        return interpolateAt(index, dist)
    }

    /** 找到包含累计距离 [dist] 的区间右端点索引（从 [from] 起向后游标推进） */
    private fun pathIndexAt(dist: Double, from: Int): Int {
        var i = from.coerceAtLeast(1)
        while (i < pathPoints.size - 1 && pathPoints[i].cum < dist) {
            i++
        }
        return i
    }

    /** 在区间 [index-1, index] 内按累计距离插值 */
    private fun interpolateAt(index: Int, dist: Double): Pair<Double, Double> {
        val b = pathPoints[index]
        val a = pathPoints[index - 1]
        val seg = b.cum - a.cum
        val t = if (seg <= 1e-9) 0.0 else ((dist - a.cum) / seg).coerceIn(0.0, 1.0)
        return Pair(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }


    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null

    fun initRocker(activity: Activity): Rocker {
        // Activity 被重建（深浅色切换、进程恢复等，ViewModel 会存活）后，旧 Rocker 仍
        // 持有已销毁的 Activity 与它的 WindowManager——继续用它 addView 会抛
        // BadTokenException。检测到换绑立即重建，并尽力恢复显示/自动播放状态。
        if (::rocker.isInitialized && rocker.hostActivity !== activity) {
            val wasStarted = rocker.isStart
            val wasAutoPlaying = rocker.autoStatus
            runCatching { rocker.hide() }
                .onFailure { Log.e("MockServiceViewModel", "解绑旧悬浮摇杆失败", it) }
            rocker = Rocker(activity)
            if (wasStarted) {
                runCatching { rocker.show() }
                rocker.autoStatus = wasAutoPlaying
            }
        }
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || !rockerJob.isActive) {
            rockerCoroutineController.pause()
            val delayTime = activity.reportDuration.toLong()
            val applicationContext = activity.applicationContext
            rockerJob = viewModelScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

                    // 自动播放中：位置/朝向由路线播放器独占推进，手动摇杆不插手
                    if (isAutoPlaying) continue

                    CrashReport.setUserSceneTag(applicationContext, 261773)
                    val lm = locationManager
                    if (lm == null) {
                        // 定位服务尚未就绪（权限未授予等），暂停循环等待下次恢复
                        rockerCoroutineController.pause()
                        continue
                    }
                    if(!MockServiceHelper.move(lm, FakeLoc.speed / (1000 / delayTime), FakeLoc.bearing)) {
                        Log.e("MockServiceViewModel", "Failed to move")
                    }
                } while (isActive)
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (!::routeMockJob.isInitialized || !routeMockJob.isActive) {
            val delayTime = activity.reportDuration.toLong()

            routeMockJob = viewModelScope.launch {
                while (isActive) {
                    delay(delayTime)

                    // 播放开关 = 悬浮摇杆自动播放状态（唯一事实源，不另维护暂停状态机）；
                    // 关闭时只空转等待，不做定位/重建（重新开启后又从重置态开始）。
                    if (!isAutoPlaying) continue
                    val lm = locationManager ?: continue
                    val selected = selectedRoute
                    if (selected == null || selected.route.size < 2) continue

                    // 路线变化 → 重建路径并完整重置（选中时已重置，此处兼容外部改选）
                    if (cachedRoute !== selected) resetPlayback(selected)

                    // 起点定位（每次重置后一次）
                    if (!pathInitialized) {
                        MockServiceHelper.setLocation(lm, pathPoints[0].lat, pathPoints[0].lon)
                        pathInitialized = true
                        continue
                    }

                    // 按速度推进弧长，直接在路线上插值出本 tick 的目标点并设置位置：
                    // 不再盲推 + 距离检测（盲推在曲线密集采样点上会失准、批量跳点）。
                    routeTravelled += FakeLoc.speed * (delayTime / 1000.0)

                    if (routeTravelled >= routeDistance) {
                        // 完成：精确落在终点，停播并彻底重置（下次播放从头开始）
                        val end = pathPoints.last()
                        MockServiceHelper.setLocation(lm, end.lat, end.lon)
                        resetPlayback(null)
                        rocker.autoStatus = false
                        playCompletionSound(activity)
                        continue
                    }

                    val target = pointAt(routeTravelled)
                    // 朝向 = 路线切线（向前 2m 处的方向）——平滑段切线连续变化，
                    // 普通段即段方向；随位置显式下发，系统侧不再依赖位移推算。
                    val ahead = lookaheadPoint(
                        minOf(routeTravelled + TANGENT_LOOKAHEAD_M, routeDistance)
                    )
                    val bearing = if (ahead == target) null else azimuthOf(target, ahead)
                    if (FakeLoc.enableDebugLog) {
                        Log.d("MockServiceViewModel", "弧长 $routeTravelled/$routeDistance → ${target.first}, ${target.second}, 朝向: $bearing")
                    }
                    if (!MockServiceHelper.setLocation(lm, target.first, target.second, bearing)) {
                        Log.e("MockServiceViewModel", "设置位置失败")
                    }
                }
            }
        }

        return rocker
    }

    private fun playCompletionSound(activity: Activity) {
        try {
            // 1. 播放声音
            val ringtoneUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            val mediaPlayer = android.media.MediaPlayer.create(activity.applicationContext, ringtoneUri)
            mediaPlayer.setOnCompletionListener { it.release() }
            mediaPlayer.start()

            // 2. 振动 0.5 秒，间隔 0.3 秒，共 2 次
            val vibrator = activity.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 500, 300, 500) // 等待0ms，振动500ms，暂停300ms，再振动500ms
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.e("MockServiceViewModel", "播放提示音或振动失败", e)
        }
    }

    fun isServiceStart(): Boolean {
        return locationManager != null && MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(
            locationManager!!
        )
    }
}