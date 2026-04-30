package moe.fuqiuluo.portal.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.fuqiuluo.portal.android.coro.CoroutineController
import moe.fuqiuluo.portal.android.coro.CoroutineRouteMock
import moe.fuqiuluo.portal.ext.Loc4j
import moe.fuqiuluo.portal.ext.accuracy
import moe.fuqiuluo.portal.ext.altitude
import moe.fuqiuluo.portal.ext.reportDuration
import moe.fuqiuluo.portal.ext.speed
import moe.fuqiuluo.portal.service.MockServiceHelper
import moe.fuqiuluo.portal.ui.mock.HistoricalLocation
import moe.fuqiuluo.portal.ui.mock.HistoricalRoute
import moe.fuqiuluo.portal.ui.mock.Rocker
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

    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null

    // ---------- 速度衰减相关 ----------
    private var totalDistanceMoved = 0.0          // 累计移动距离（米）
    private val decayDistanceThreshold = 14000.0 // 衰减阈值（米），对应步频模拟的20000步（平均步幅0.7m）
    private val minSpeedFactor = 130.0 / 190.0   // 最小速度因子 ≈ 0.6842

    /**
     * 根据累计移动距离计算当前速度衰减因子（线性衰减）
     * 距离从 0 → decayDistanceThreshold，因子从 1.0 → minSpeedFactor
     */
    private fun getCurrentSpeedFactor(): Double {
        val progress = (totalDistanceMoved / decayDistanceThreshold).coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - minSpeedFactor) * progress
    }

    /**
     * 重置累计距离（例如重新开始路线模拟时调用）
     */
    private fun resetDistanceAccumulator() {
        totalDistanceMoved = 0.0
        Log.d("MockServiceViewModel", "速度衰减累计距离已重置")
    }
    // ---------------------------------

    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || rockerJob.isCancelled) {
            rockerCoroutineController.pause()
            val delayTime = activity.reportDuration.toLong()
            val applicationContext = activity.applicationContext
            rockerJob = GlobalScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

                    CrashReport.setUserSceneTag(applicationContext, 261773)
                    if(!MockServiceHelper.move(locationManager!!, FakeLoc.speed / (1000 / delayTime) / 0.85, FakeLoc.bearing)) {
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
            // 每次启动路线模拟时重置累计距离（保证衰减从头开始）
            resetDistanceAccumulator()

            routeMockJob = GlobalScope.launch {
                do {
                    routeMockCoroutine.routeMockCoroutine()
                    delay(delayTime)
                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        MockServiceHelper.setLocation(
                            locationManager!!,
                            selectedRoute!!.route[0].first,
                            selectedRoute!!.route[0].second
                        )
                        routeStage++
                    }
                    val route = selectedRoute!!.route

                    // 处理所有已到达的阶段
                    while (routeStage < route.size) {
                        val target = route[routeStage]
                        val location = MockServiceHelper.getLocation(locationManager!!)
                        val currentLat = location!!.first
                        val currentLon = location.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        // 判断距离是否小于1米（可根据需要调整阈值）
                        if (inverse.s12 < 1.0) {
                            // 精确设置位置到目标点并进入下一阶段
                            MockServiceHelper.setLocation(
                                locationManager!!,
                                target.first,
                                target.second
                            )
                            routeStage++
                        } else if (inverse.s12 < FakeLoc.speed * getCurrentSpeedFactor() / (1000 / delayTime) / 0.85) {
                            // 如果距离小于当前衰减后的单步移动距离，直接移动到目标点
                            MockServiceHelper.setLocation(
                                locationManager!!,
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
                        break // 退出循环
                    }

                    // 处理当前目标点的移动
                    val target = route[routeStage]
                    val location = MockServiceHelper.getLocation(locationManager!!)
                    val currentLat = location!!.first
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

                    // ***** 速度衰减核心：计算当前实际移动距离并累加 *****
                    val stepDistance = inverse.s12  // 本次需要移动的距离（米）
                    if (stepDistance > 0) {
                        // 累加实际移动的距离（注意：这里累加的是全部剩余距离，但因为可能一次移动不完，需要按比例累加？）
                        // 更好的做法：每次移动只移动一部分，累加那部分距离。但当前 move 内部可能一次移动整段距离？
                        // 观察 move 函数：它接收速度向量和方位角，移动固定距离（由速度 * 时间计算）。
                        // 实际移动的距离是 speed * time，而不是 stepDistance。
                        // 因此我们应该累加每次调用 move 实际移动的距离，而不是目标点剩余距离。
                        // 但由于此循环每次 delayTime 调用一次 move，我们可以使用一个固定步长： moveStep = 当前衰减速度 * (delayTime / 1000)
                        val moveStep = FakeLoc.speed * getCurrentSpeedFactor() * (delayTime / 1000.0)
                        totalDistanceMoved += moveStep
                        Log.d("MockServiceViewModel", "累计移动距离: %.2f m, 速度因子: %.3f".format(totalDistanceMoved, getCurrentSpeedFactor()))
                    }

                    Log.d("MockServiceViewModel", "从 $currentLat, $currentLon 移动到 ${target.first}, ${target.second}, 方位角: $azimuth")
                    // 使用衰减后的速度进行移动
                    val decayedSpeed = FakeLoc.speed * getCurrentSpeedFactor()
                    if (!MockServiceHelper.move(
                            locationManager!!,
                            decayedSpeed / (1000 / delayTime) / 0.85,
                            azimuth
                        )
                    ) {
                        Log.e("MockServiceViewModel", "移动失败")
                    }
                } while (isActive)
            }
        }

        return rocker
    }

    fun isServiceStart(): Boolean {
        return locationManager != null && MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(
            locationManager!!
        )
    }
}