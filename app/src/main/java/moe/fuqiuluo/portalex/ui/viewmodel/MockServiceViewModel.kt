package moe.fuqiuluo.portalex.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.fuqiuluo.portalex.android.coro.CoroutineController
import moe.fuqiuluo.portalex.Portal
import moe.fuqiuluo.portalex.ext.accuracy
import moe.fuqiuluo.portalex.ext.altitude
import moe.fuqiuluo.portalex.ext.binderSensorMock
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.ui.mock.HistoricalLocation
import moe.fuqiuluo.portalex.ui.mock.HistoricalRoute
import moe.fuqiuluo.portalex.ui.mock.Rocker
import moe.fuqiuluo.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic
import kotlin.math.abs

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    /** 唯一运动推进器（摇杆步进 / 路线播放共用一条循环） */
    private lateinit var motionJob: Job
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
     * 关闭悬浮摇杆窗口时的统一收尾：停下**全部**位置推进源。
     *
     * 运动循环有两条写位置的路径：摇杆步进（走暂停门 [CoroutineController.consume]）
     * 与路线自动播放（`isAutoPlaying` 分支，**不经过暂停门**）。只 `pause()` 只能拦住
     * 前者，关窗后路线仍会一路播到终点。因此必须同时关掉 `autoStatus`——它还会顺带
     * 把摇杆自身的自走 `auto(false)` 停掉（即“摇杆移动”）。
     */
    fun stopMotionForFloatingHidden() {
        if (::rocker.isInitialized) {
            rocker.autoStatus = false
        }
        rockerCoroutineController.pause()
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
            if (value != null) {
                MockServiceHelper.tryInitService(value)
                // 实验性开关跨重启恢复：服务握手成功后把当前偏好同步给系统侧。
                // 关闭时同样要下发——系统侧进程重启后不该残留"开着"的状态。
                // 启动恢复：开关 + 栅格 + 噪声档一次下发（唯一出口，见 ConfigSync）
                ConfigSync.restoreAfterHandshake(Portal.appContext, value)
            }
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

        ensureMotionLoop(activity)

        // 只写本进程镜像（摇杆/运动循环读的是这份）；下发是另一件事，见 ConfigSync.push
        ConfigSync.mirrorLocal(activity)

        return rocker
    }

    /**
     * **唯一运动推进器**：摇杆步进与路线播放共用一条循环。
     *
     * 两种模式是「同一位置的两种来源」——此前是两个独立协程各自写位置，只靠 `isAutoPlaying`
     * 标志互斥；合并后每条 tick **只有一个分支**会写位置，互斥从「共享标志」变成「结构互斥」。
     */
    private fun ensureMotionLoop(activity: Activity) {
        if (::motionJob.isInitialized && motionJob.isActive) return
        // 循环启动即处于「暂停」：摇杆未按住时不推进位置。
        // 上游 `initRocker` 在启动循环前显式调用 `pause()`，重构时该行遗失——那时
        // 运动循环一启动就每 tick 位移，用户看到「第一次启动模拟，位置就朝一个方向
        // 漂移，手动移动一次才恢复」。此处恢复该语义；自动播放走 [advanceRoutePlayback]
        // 分支，不经过暂停门，故不受影响。
        rockerCoroutineController.pause()
        motionJob = viewModelScope.launch {
            while (isActive) {
                // 间隔每次重新读：设置页改完立即生效，且钳制下限 1ms（避免 delay(0) 空转与除零）
                val delayTime = activity.reportDuration.coerceIn(1, 1000).toLong()
                delay(delayTime)
                if (isAutoPlaying) {
                    advanceRoutePlayback(activity, delayTime)
                } else {
                    advanceRockerMove(delayTime)
                }
            }
        }
    }

    /**
     * 手动模式：摇杆步进一帧。
     *
     * 暂停门是**非阻塞**的（[CoroutineController.consume]）：暂停只表示「这一 tick 不动」，
     * 循环必须继续转——否则「松开摇杆（暂停）→ 启动自动播放」会卡死在等待 Resume 上，
     * 路线播放永远推进不了（就停在原地）。旧实现是两个独立协程，路线播放不受摇杆暂停影响，
     * 合并后必须保住这一点。
     */
    private fun advanceRockerMove(delayTime: Long) {
        if (rockerCoroutineController.consume()) return
        val lm = locationManager ?: return   // 定位服务未就绪：本 tick 跳过，下次自动重试
        // 每 tick 位移 = 速度 × 本 tick 时长（浮点）。
        // 旧实现 FakeLoc.speed / (1000 / delayTime) 是**整数除法**：
        // 150ms → 除数被截断为 6（实际速度 +11%）、700ms → 除数 1（+43%）、0 → 除零崩溃。
        if (!MockServiceHelper.move(lm, FakeLoc.speed * delayTime / 1000.0, FakeLoc.bearing)) {
            Log.e("MockServiceViewModel", "Failed to move")
        }
    }

    /** 自动模式：按速度推进弧长，在路线上插值出位置并下发切线朝向 */
    private fun advanceRoutePlayback(activity: Activity, delayTime: Long) {
        val lm = locationManager ?: return
        val selected = selectedRoute
        if (selected == null || selected.route.size < 2) return

        // 路线变化 → 重建路径并完整重置（选中时已重置，此处兼容外部改选）
        if (cachedRoute !== selected) resetPlayback(selected)

        // 起点定位（每次重置后一次）：下发成功才算初始化，失败下个 tick 重试（不推进弧长）
        if (!pathInitialized) {
            if (MockServiceHelper.setLocation(lm, pathPoints[0].lat, pathPoints[0].lon)) {
                pathInitialized = true
            } else {
                Log.e("MockServiceViewModel", "路线起点下发失败，下个 tick 重试")
            }
            return
        }

        // 按速度推进弧长，直接在路线上插值出本 tick 的目标点并设置位置：
        // 不再盲推 + 距离检测（盲推在曲线密集采样点上会失准、批量跳点）。
        val advanceMeters = FakeLoc.speed * (delayTime / 1000.0)
        routeTravelled += advanceMeters

        if (routeTravelled >= routeDistance) {
            // 完成：精确落在终点，停播并彻底重置（下次播放从头开始）
            val end = pathPoints.last()
            MockServiceHelper.setLocation(lm, end.lat, end.lon)
            resetPlayback(null)
            rocker.autoStatus = false
            playCompletionSound(activity)
            return
        }

        val target = pointAt(routeTravelled)
        // 朝向 = 路线切线（向前 2m 处的方向）——平滑段切线连续变化，
        // 普通段即段方向；随位置显式下发，系统侧不再依赖位移推算。
        val ahead = lookaheadPoint(
            minOf(routeTravelled + TANGENT_LOOKAHEAD_M, routeDistance)
        )
        val bearing = if (ahead == target) null else azimuthOf(target, ahead)
        if (FakeLoc.enableDebugLog) {
            Log.d(
                "MockServiceViewModel",
                "弧长 $routeTravelled/$routeDistance → ${target.first}, ${target.second}, 朝向: $bearing"
            )
        }
        if (!MockServiceHelper.setLocation(lm, target.first, target.second, bearing)) {
            // 下发失败（服务未就绪 / 进程重建）：回退本 tick 推进量，下个 tick 重试同一位置——
            // 否则会出现「进度在走、位置原地不动」并一路跑到终点（看起来就是卡在原位）
            routeTravelled -= advanceMeters
            Log.e("MockServiceViewModel", "设置位置失败，回退本 tick 推进量待重试")
        }
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