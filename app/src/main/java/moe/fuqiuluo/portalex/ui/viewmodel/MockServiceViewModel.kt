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
import moe.fuqiuluo.portalex.android.coro.CoroutineRouteMock
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
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    // 自动播放的当前朝向（度，0=北，顺时针）——按有限角速度向目标方位角平滑转向，
    // 避免折线段切换时 App 方向计瞬间跳变；摇杆路径直接用摇杆角度，不经此值。
    private var routeBearing = 0.0
    private var routeBearingInitialized = false
    private val routeTurnRate = 120.0 // 度/秒（转向角速度上限，自然转弯）

    /**
     * 向目标方位角平滑转向一步（限角速度），返回新的朝向（归一化 0..360）。
     */
    private fun turnTowards(current: Double, target: Double, dtSeconds: Double): Double {
        val maxTurn = routeTurnRate * dtSeconds
        // 归一化差值到 (-180, 180]
        val delta = (((target - current + 180.0) % 360.0) + 360.0) % 360.0 - 180.0
        val newBearing = if (abs(delta) <= maxTurn) {
            current + delta
        } else {
            current + delta.coerceAtLeast(-maxTurn).coerceAtMost(maxTurn)
        }
        return ((newBearing % 360.0) + 360.0) % 360.0
    }

    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null

    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || rockerJob.isCancelled) {
            rockerCoroutineController.pause()
            val delayTime = activity.reportDuration.toLong()
            val applicationContext = activity.applicationContext
            rockerJob = viewModelScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

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

        if (!::routeMockJob.isInitialized || routeMockJob.isCancelled) {
            routeMockCoroutine.pause()
            val delayTime = activity.reportDuration.toLong()

            routeMockJob = viewModelScope.launch {
                do {
                    routeMockCoroutine.routeMockCoroutine()
                    delay(delayTime)
                    val lm = locationManager
                    if (lm == null) {
                        routeMockCoroutine.pause()
                        continue
                    }
                    val selected = selectedRoute
                    if (selected == null || selected.route.isEmpty()) {
                        // 未选择路线：暂停模拟，避免空指针崩溃
                        routeMockCoroutine.pause()
                        continue
                    }
                    val route = selected.route
                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        MockServiceHelper.setLocation(
                            lm,
                            route[0].first,
                            route[0].second
                        )
                        routeStage++
                    }

                    // 处理所有已到达的阶段
                    while (routeStage < route.size) {
                        val target = route[routeStage]
                        val location = MockServiceHelper.getLocation(lm) ?: break
                        val currentLat = location.first
                        val currentLon = location.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        // 判断距离是否小于1米（可根据需要调整阈值）
                        // 注：不再有「小于单步距离即跳点」分支——跳点会让路线模拟末端免费加速，
                        // 与摇杆速度不一致。统一按设定速度步进，目标点由 1m 精确阈值收敛。
                        if (inverse.s12 < 1.0) {
                            // 精确设置位置到目标点并进入下一阶段
                            MockServiceHelper.setLocation(
                                lm,
                                target.first,
                                target.second
                            )
                            routeStage++
                        } else {
                            break
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.size) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        // 重设阶段
                        routeStage = 0
                        // 播放提示音
                        playCompletionSound(activity)
                        break // 退出循环
                    }

                    // 处理当前目标点的移动
                    val target = route[routeStage]
                    val location = MockServiceHelper.getLocation(lm) ?: continue
                    val currentLat = location.first
                    val currentLon = location.second

                    val inverse = Geodesic.WGS84.Inverse(
                        currentLat,
                        currentLon,
                        target.first,
                        target.second
                    )
                    var azimuth = inverse.azi1
                    if (azimuth < 0) {
                        azimuth += 360
                    }

                    // 朝向平滑过渡：首次直接对齐目标方位角（避免从 0° 大角度转），
                    // 之后按有限角速度逐 tick 转向——折线段切换时 App 方向计平滑转动，
                    // 而非瞬间跳变。转向同步经 move 命令写入服务端 FakeLoc.bearing。
                    if (!routeBearingInitialized) {
                        routeBearing = azimuth
                        routeBearingInitialized = true
                    } else {
                        routeBearing = turnTowards(routeBearing, azimuth, delayTime / 1000.0)
                    }

                    Log.d("MockServiceViewModel", "从 $currentLat, $currentLon 移动到 ${target.first}, ${target.second}, 方位角: $azimuth, 朝向: $routeBearing")
                    // 与摇杆路径完全一致的速度公式：每 tick 移动 = speed / tick频率（±5% 对称抖动在 moveLocation）
                    if (!MockServiceHelper.move(
                            lm,
                            FakeLoc.speed / (1000 / delayTime),
                            routeBearing
                        )
                    ) {
                        Log.e("MockServiceViewModel", "移动失败")
                    }
                } while (isActive)
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