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
import moe.fuqiuluo.portalex.ext.keepAliveInBackground
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.service.MockKeepAliveService
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.service.StaminaController
import moe.fuqiuluo.portalex.ui.mock.HistoricalLocation
import moe.fuqiuluo.portalex.ui.mock.HistoricalRoute
import moe.fuqiuluo.portalex.ui.mock.Rocker
import moe.fuqiuluo.xposed.RemoteCommandHandler
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.PortalProtocol.Key
import net.sf.geographiclib.Geodesic
import kotlin.math.abs

/**
 * 模拟会话的 App 侧控制器 —— **迁移后是遥控器，不是执行器**。
 *
 * ## 这一层还剩下什么（统一架构：`docs/sensor-architecture.md`）
 *
 * | 留下 | 交出去了 |
 * | --- | --- |
 * | 路线编辑：把选中路线展开成播放路径点并**上传** | 沿路径推进（谁走、走多快） |
 * | 摇杆意图：按住/锁定 + 方向 | 每拍位移的积分与体力倍率缩放 |
 * | 显示与收尾：进度回读、播完提示音/振动、后台保活 | 位置流本身 |
 *
 * 交出去的理由只有一条：**位置流只能在生成它的地方被缩放**。交付给应用的坐标是绝对量，
 * 模块侧事后缩放只能做出永远落后的滞后积分器（路线走不到终点）；所以推进与体力一起
 * 搬进了 system_server，App 只表达意图、只读回结果。
 *
 * 副作用是好的那一种：**灭屏/被冻结时路线照样走**（位置流的连续性不再依赖 App 进程活着），
 * 而"上报间隔"仍由 App 的设置项决定（[moe.fuqiuluo.portalex.ext.reportDuration] 会下发）。
 */
class MockServiceViewModel : ViewModel() {

    lateinit var rocker: Rocker

    /** 遥控循环：每拍**只在下发内容变化时**发命令 + 低频回读状态 */
    private lateinit var directorJob: Job

    var isRockerLocked = false
    val rockerCoroutineController = CoroutineController()

    /**
     * 播放路径点：路线按段展开（平滑段 = 贝塞尔曲线采样序列，普通段 = 端点直连）。
     * [spacing] = 与前一播放点的距离；[cum] = 自路径起点的累计距离（米）。
     *
     * 这是 App 侧**唯一**与推进有关的数据 —— 它由路线编辑器产生，上传后即交给系统侧。
     */
    private class PathPoint(
        val lat: Double,
        val lon: Double,
        val spacing: Double,
        val cum: Double
    )

    /** 当前展开的播放路径（路线切换时重建） */
    private var cachedRoute: HistoricalRoute? = null
    private var pathPoints: List<PathPoint> = emptyList()
    private var routeDistance = 0.0

    /** 是否已把当前路线/播放状态下发给系统侧（只在变化时发，见 [loop]） */
    @Volatile private var routeUploaded = false
    @Volatile private var routePlayingPushed = false

    /** 摇杆意图（App 采集、系统侧执行） */
    @Volatile private var rockerBearing = 0.0
    @Volatile private var lastPushedBearing = Double.NaN
    @Volatile private var lastPushedActive = false

    /**
     * 摇杆**是否有手指按着**。
     *
     * 用来区分两种"摇杆在走"：
     *  · 手指按着（true）—— 屏幕必然亮、进程必然在收触摸事件，不需要任何强保活；
     *  · **锁定后松手继续走**（false 但暂停门开着）—— 无人值守。
     */
    private var joystickTouched = false

    /**
     * 后台保活服务的当前状态。
     *
     * 迁移后它的理由变了：**不再需要"钉住 App 以维持推进"**（推进在 system_server，
     * App 被冻结也照样走），留下它是为了"无人值守的**收尾**"—— 播完要放提示音/振动、
     * 要把悬停按钮的状态收回来，那需要一个活着的观察者。
     */
    private var keepAliveOn = false

    /** 上一次回读推进状态/卫星状态的时刻（各自节流） */
    private var lastMotionPollNanos = 0L

    companion object {
        /** 推进状态回读间隔（毫秒）：进度显示与"播完了"的收尾用，4Hz 足够 */
        private const val MOTION_POLL_MS = 250L

        /**
         * 上传路线的点数上限。Bundle 走 binder（1MB 上限），路径点约 16 字节/点
         * ⇒ 2 万点 ≈ 320KB，安全；超过就抽稀（[buildUpload] 里的 stride），
         * 代价只是拐弯处的采样变粗 —— 总比整条路线发不出去好。
         */
        private const val MAX_UPLOAD_POINTS = 20_000
    }

    /** 自动播放中：手动摇杆不得抢占朝向（方向由路线切线接管，在系统侧算） */
    val isAutoPlaying: Boolean
        get() = ::rocker.isInitialized && rocker.autoStatus

    /** 选中路线：展开成播放路径并标记"待上传"（真正的上传在遥控循环里，失败会自动重试） */
    fun selectRouteForPlayback(route: HistoricalRoute) {
        selectedRoute = route
        cachedRoute = route
        pathPoints = buildPath(route)
        routeDistance = pathPoints.lastOrNull()?.cum ?: 0.0
        routeUploaded = false
        routePlayingPushed = false
    }

    /**
     * 清除选中路线（路线被删除等）：彻底重置并关闭自动播放，
     * 避免系统侧继续持有已不存在的路线（幽灵路线）。
     */
    fun clearSelectedRoute() {
        selectedRoute = null
        cachedRoute = null
        pathPoints = emptyList()
        routeDistance = 0.0
        routeUploaded = false
        locationManager?.let {
            // 系统侧也丢掉这条路线：否则"幽灵路线"会留在 system_server 里，
            // 下次点播放走的是已经不存在的那条（路线删了还能自动跑 = 灵异事件）
            MockServiceHelper.setRoute(it, DoubleArray(0), DoubleArray(0))
            MockServiceHelper.setRoutePlaying(it, false)
        }
        routePlayingPushed = false
        if (::rocker.isInitialized) {
            rocker.autoStatus = false
        }
    }

    /**
     * 关闭悬浮摇杆窗口时的统一收尾：停下**全部**位置推进源。
     *
     * 自动播放不看摇杆的暂停门，所以必须同时关掉 `autoStatus`；遥控循环下一拍就会
     * 把"停止"下发给系统侧（[routePlayingPushed] 随之翻转）。
     */
    fun stopMotionForFloatingHidden() {
        if (::rocker.isInitialized) {
            rocker.autoStatus = false
        }
        rockerCoroutineController.pause()
        joystickTouched = false
    }

    /**
     * 手动摇杆角度：只**记录意图**（真正推动位置的是系统侧）。
     * 自动播放中忽略——播放方向由路线切线控制，否则两者互相抢占。
     */
    fun handleRockerAngle(angle: Double) {
        if (isAutoPlaying) return
        rockerBearing = angle
        // 本进程镜像同一次赋值：App 侧的朝向显示与系统侧同一口径
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
        // 调用方对空路径一律按「不可播放」处理，绝不抛异常。
        if (points.size < 2) return emptyList()
        val path = mutableListOf<PathPoint>()
        fun append(p: Pair<Double, Double>) {
            val prev = path.lastOrNull()
            val spacing = if (prev == null) {
                0.0
            } else {
                Geodesic.WGS84.Inverse(prev.lat, prev.lon, p.first, p.second).s12
            }
            path.add(PathPoint(p.first, p.second, spacing, (prev?.cum ?: 0.0) + spacing))
        }
        for (i in 0 until points.size - 1) {
            if (route.isSmooth(i)) {
                sampleBezier(points, i).forEach { append(it) }
            } else {
                append(points[i])
            }
        }
        // 末段终点（与最后一段共享 segment 索引）
        append(points.last())
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

    /** 上传用的坐标数组（点数超限时按 stride 抽稀；抽稀后至少保留首末两点） */
    private fun buildUpload(): Pair<DoubleArray, DoubleArray> {
        val stride = maxOf(1, (pathPoints.size + MAX_UPLOAD_POINTS - 1) / MAX_UPLOAD_POINTS)
        if (stride == 1) {
            return Pair(
                DoubleArray(pathPoints.size) { pathPoints[it].lat },
                DoubleArray(pathPoints.size) { pathPoints[it].lon },
            )
        }
        val idx = (pathPoints.indices step stride).toMutableList()
        if (idx.last() != pathPoints.size - 1) idx.add(pathPoints.size - 1)
        return Pair(
            DoubleArray(idx.size) { pathPoints[idx[it]].lat },
            DoubleArray(idx.size) { pathPoints[idx[it]].lon },
        )
    }

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null) {
                MockServiceHelper.tryInitService(value)
                // 实验性开关跨重启恢复：服务握手成功后把当前偏好同步给系统侧。
                // 启动恢复：开关 + 噪声档 + 体力参数 + 上报间隔一次下发（唯一出口，见 ConfigSync）
                ConfigSync.restoreAfterHandshake(Portal.appContext, value)
                // 会话可能是在**上一个 App 进程**里启动的（迁移后状态跨 App 生命周期存活）：
                // 把 App 自己那一半职责重挂上（传感器轮询保活等），否则事件投递会退回"半天一批"
                value.let { MockServiceHelper.reattachIfRunning(Portal.appContext, it) }
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

        ensureDirectorLoop(activity)

        // 只写本进程镜像（显示用）；下发是另一件事，见 ConfigSync.push
        ConfigSync.mirrorLocal(activity)

        return rocker
    }

    /**
     * **遥控循环**（唯一循环，取代旧的运动推进循环）。
     *
     * 每拍只做三件事：
     *  1. 把**变化了的**意图下发给系统侧（路线数据 / 播放开关 / 摇杆方向与激活态）；
     *  2. 低频回读推进状态（进度、是否播完、当前坐标）并同步本进程镜像；
     *  3. 播完收尾（提示音/振动/收回按钮）与后台保活。
     *
     * 为什么"只发变化"：这个循环的节奏由上报间隔决定（默认 100ms），若每拍都发命令，
     * 就是每秒 10 次 binder——旧实现每拍发一次 `move` 正是这样。意图不变就没必要说话。
     */
    private fun ensureDirectorLoop(activity: Activity) {
        if (::directorJob.isInitialized && directorJob.isActive) return
        // 参数入内存：**循环启动 = 一次会话开始**（复位体力不在这里 —— 状态在系统侧，
        // 会话重启不该把"跑到一半的人"变回满体力）
        StaminaController.load(activity)
        directorJob = viewModelScope.launch {
            while (isActive) {
                val delayTime = activity.reportDuration.coerceIn(1, 1000).toLong()
                delay(delayTime)
                try {
                    step()
                } catch (t: Throwable) {
                    Log.e("MockServiceViewModel", "遥控循环异常", t)
                }
            }
        }
    }

    private fun step() {
        val lm = locationManager ?: return
        // 非阻塞地消费暂停门（`pause()`/`resume()` 只是入队，**必须有人 drain 才会生效**）：
        // 旧实现是运动循环在推进前调 `consume()`；合并到遥控循环后若不 drain，
        // "按住摇杆"就永远读成暂停 —— 位置一动不动，而且不报任何错。
        val paused = rockerCoroutineController.consume()
        val auto = isAutoPlaying

        if (auto && selectedRoute != null && pathPoints.size >= 2) {
            // 自动播放：路线数据先上传（一次），再打开播放；摇杆意图关掉（方向由切线接管）
            if (!routeUploaded) {
                val (lats, lons) = buildUpload()
                routeUploaded = MockServiceHelper.setRoute(lm, lats, lons)
                if (!routeUploaded) {
                    Log.e("MockServiceViewModel", "路线上传失败（服务未就绪？），下拍重试")
                }
            }
            if (routeUploaded && !routePlayingPushed) {
                routePlayingPushed = MockServiceHelper.setRoutePlaying(lm, true)
            }
            pushRockerIntent(lm, active = false)
        } else {
            if (routePlayingPushed) {
                MockServiceHelper.setRoutePlaying(lm, false)
                routePlayingPushed = false
            }
            // 摇杆意图：暂停门开着（手指按住或锁定后继续走）才算"在走"
            pushRockerIntent(lm, active = !paused)
        }

        // 低频回读：进度显示、播完收尾、坐标镜像
        val now = System.nanoTime()
        if (now - lastMotionPollNanos >= MOTION_POLL_MS * 1_000_000L) {
            lastMotionPollNanos = now
            pollMotion(lm)
        }

        // 保活只服务"无人值守"：自动播放（要有人听提示音、收尾）或摇杆锁定后松手继续走
        syncBackgroundKeepAlive(Portal.appContext)
    }

    /** 摇杆意图下发（变化才发）：方向变化 >0.5° 或激活态翻转 */
    private fun pushRockerIntent(lm: LocationManager, active: Boolean) {
        val bearingChanged = lastPushedBearing.isNaN() ||
                abs(rockerBearing - lastPushedBearing) > 0.5 ||
                abs(rockerBearing - lastPushedBearing) > 359.5
        if (active == lastPushedActive && !bearingChanged) return
        if (MockServiceHelper.setRocker(lm, active, rockerBearing)) {
            lastPushedBearing = rockerBearing
            lastPushedActive = active
        }
    }

    /** 回读推进状态：进度、播完（一次性）、坐标镜像。读不到就保持上次的值，不猜 */
    private fun pollMotion(lm: LocationManager) {
        val rely = MockServiceHelper.getMotion(lm) ?: return
        val playing = rely.getBoolean(Key.MOTION_PLAYING, false)
        val completed = rely.getBoolean(Key.MOTION_COMPLETED, false)
        // 坐标镜像：App 侧显示（地图/坐标栏）读的是本进程的 FakeLoc
        RemoteCommandHandler.applySyncedCoordinate(
            rely.getDouble(Key.LAT, FakeLoc.latitude),
            rely.getDouble(Key.LON, FakeLoc.longitude),
        )
        FakeLoc.bearing = rely.getDouble(Key.BEARING, FakeLoc.bearing)
        FakeLoc.hasBearings = true

        if (isAutoPlaying && (completed || (!playing && rely.getDouble(Key.ROUTE_DISTANCE, 0.0) > 0.0 &&
                    rely.getDouble(Key.ROUTE_TRAVELLED, 0.0) >= rely.getDouble(Key.ROUTE_DISTANCE, 0.0) - 1e-6))
        ) {
            onRouteCompleted()
        }
    }

    /** 播完收尾：停自动播放（下一拍会把停止下发给系统侧）、提示音 + 振动 */
    private fun onRouteCompleted() {
        Log.i("MockServiceViewModel", "路线播放完成（系统侧进度已到终点）")
        if (::rocker.isInitialized) {
            rocker.autoStatus = false
        }
        routePlayingPushed = false
        lastPushedActive = false
        playCompletionSound(Portal.appContext)
    }

    /**
     * 手动模式：摇杆按下/松开的**意图**（推进在系统侧，这里只改状态）。
     *
     * 暂停门是**非阻塞**的（[CoroutineController.consume]）：暂停只表示"这一拍不动"，
     * 循环继续转——否则「松开摇杆（暂停）→ 启动自动播放」会卡死。
     */
    fun onRockerStarted() {
        joystickTouched = true
        rockerCoroutineController.resume()
    }

    /**
     * 摇杆松开：[locked] = 锁定开关开着 ⇒ 继续走（无人值守，需要保活）；
     * 未锁定 ⇒ 关门停下（空闲，保活随即撤掉）。
     */
    fun onRockerFinished(locked: Boolean) {
        joystickTouched = false
        if (!locked) rockerCoroutineController.pause()
    }

    /**
     * 后台保活同步（每拍调一次，内部只在状态变化时动作）。
     *
     * 需要它的只剩**无人值守仍在推进**的情况：自动播放、或摇杆锁定后松手继续走
     * —— 不是为了"维持推进"（那在 system_server 里），而是为了让 App 活着完成收尾。
     */
    private fun syncBackgroundKeepAlive(ctx: android.content.Context) {
        val unattendedMoving = isAutoPlaying ||
                (!rockerCoroutineController.isPaused && !joystickTouched)
        val want = unattendedMoving && ctx.keepAliveInBackground
        if (want == keepAliveOn) return
        keepAliveOn = want
        if (want) MockKeepAliveService.start(ctx) else MockKeepAliveService.stop(ctx)
    }

    private fun playCompletionSound(context: android.content.Context) {
        try {
            val ringtoneUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            val mediaPlayer = android.media.MediaPlayer.create(context, ringtoneUri)
            mediaPlayer.setOnCompletionListener { it.release() }
            mediaPlayer.start()

            val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
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
