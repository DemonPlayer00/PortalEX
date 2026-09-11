package moe.fuqiuluo.xposed

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import moe.fuqiuluo.xposed.hooks.LocationServiceHook
import moe.fuqiuluo.xposed.hooks.sensor.BinderSensorMock
import moe.fuqiuluo.xposed.hooks.sensor.BinderSensorNative
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.Logger
import java.util.Collections
import kotlin.random.Random

object RemoteCommandHandler {
    private val proxyBinders by lazy { Collections.synchronizedList(arrayListOf<IBinder>()) }
    private val needProxyCmd = arrayOf("start", "stop", "set_speed_amp", "set_altitude", "set_speed", "update_location", "set_bearing", "move", "put_config")
    // 由 BaseDivineService 的 exchange_key 在 client 进程同步为系统侧 key（两进程同一把钥匙）；
    // 旧实现各进程 lazy 生成各自的随机值，系统转发来的指令永远通不过校验（配置不传播的根因）
    internal var randomKey: String = "portal_" + Random.nextDouble()

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun handleInstruction(command: String, rely: Bundle): Boolean {
        // Exchange key -> returns a random key -> is used to verify that it is the PortalManager
        if (command == "exchange_key") {
            val userId = BinderUtils.getCallerUid()
            if (BinderUtils.isLocationProviderEnabled(userId)) {
                rely.putString("key", randomKey)
                return true
            }
            // Go back and see if the instruction has been processed to prevent it from being detected by others
        } else if (command != randomKey) {
            return false
        }
        val commandId = rely.getString("command_id") ?: return false

        kotlin.runCatching {
            if (proxyBinders.isNotEmpty() && needProxyCmd.any { it == commandId }) {
                proxyBinders.removeIf {
                    if (it.isBinderAlive && it.pingBinder()) {
                        val data = Parcel.obtain()
                        data.writeBundle(rely)
                        it.transact(1, data, null, 0)
                        data.recycle()
                        false
                    } else true
                }
            }
        }.onFailure {
            Logger.error("Failed to transact with proxyBinder", it)
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("commandId=$commandId, rely=$rely")
        }

        when (commandId) {
            "set_proxy" -> {
                Logger.info("SubProxyBinder: ${rely.getBinder("proxy")} from ${BinderUtils.getUidPackageNames()}!")
                rely.getBinder("proxy")?.let {
                    proxyBinders.add(it)
                }
                return true
            }
            "start" -> {
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)

                FakeLoc.enable = true

                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy

                // 模拟会话启动：若无明确朝向（从未摇杆/移动），随机分配中心角度
                if (!FakeLoc.hasBearings) {
                    FakeLoc.bearing = kotlin.random.Random.nextDouble(0.0, 360.0)
                }

                // 实验性：Binder 外周传感器模拟随会话开始推流（开关关闭时无动作）
                BinderSensorMock.onSimulationChanged()

                // 启动拉回：设备在室内无真实 GPS 回调时，目标应用会停在原有位置，
                // 需手动摇杆/路线播放一次才到预定位置——这里 0.5s 后单次推送当前位置
                scheduleInitialPullback()
                return true
            }
            "stop" -> {
                FakeLoc.enable = false
                FakeLoc.hasBearings = false
                BinderSensorMock.onSimulationChanged()
                return true
            }
            "is_start" -> {
                rely.putBoolean("is_start", FakeLoc.enable)
                return true
            }
            "start_gnss_mock" -> {
                FakeLoc.enableMockGnss = true
                // 立即主动推送一次模拟卫星数据：雷达无需等待系统 GNSS 引擎上报
                LocationServiceHook.pushGnssStatus()
                return true
            }
            "stop_gnss_mock" -> {
                FakeLoc.enableMockGnss = false
                return true
            }
            "is_gnss_start" -> {
                rely.putBoolean("is_gnss_start", FakeLoc.enableMockGnss)
                return true
            }
            "is_wifi_mock_start" -> {
                rely.putBoolean("is_wifi_mock_start", FakeLoc.enableMockWifi)
                return true
            }
            "start_wifi_mock" -> {
                FakeLoc.enableMockWifi = true
                return true
            }
            "stop_wifi_mock" -> {
                FakeLoc.enableMockWifi = false
                return true
            }
            "set_sensor_mock" -> {
                // 实验性：Binder 外周传感器模拟开关（只下发给系统侧，不经代理转发）
                val enabled = rely.getBoolean("binder_sensor_mock", FakeLoc.enableBinderSensorMock)
                FakeLoc.enableBinderSensorMock = enabled
                if (!BinderSensorMock.onConfigChanged()) {
                    Logger.error("Binder 外周传感器模拟：原生注入层不可用（详见 logcat PortalSensor）")
                    return false
                }
                return true
            }
            "get_sensor_status" -> {
                // 诊断页数值总览：注入层/运动学/步频意图 vs 实际的原始数值
                BinderSensorMock.fillStatus(rely)
                return true
            }
            "is_sensor_mock" -> {
                rely.putBoolean("binder_sensor_mock", FakeLoc.enableBinderSensorMock)
                return true
            }
            "get_location" -> {
                rely.putDouble("lat", FakeLoc.latitude)
                rely.putDouble("lon", FakeLoc.longitude)
                return true
            }
            "get_listener_size" -> {
                rely.putInt("size", LocationServiceHook.locationListeners.size)
                return true
            }
            "get_speed" -> {
                rely.putDouble("speed", FakeLoc.speed)
                return true
            }
            "get_bearing" -> {
                rely.putDouble("bearing", FakeLoc.bearing)
                return true
            }
            "get_altitude" -> {
                rely.putDouble("altitude", FakeLoc.altitude)
                return true
            }
            "set_speed_amp" -> {
                FakeLoc.speedAmplitude = rely.numberOr("speed_amplitude", FakeLoc.speedAmplitude)
                return true
            }
            "set_altitude" -> {
                FakeLoc.altitude = rely.numberOr("altitude", FakeLoc.altitude)
                return true
            }
            "set_speed" -> {
                FakeLoc.speed = rely.numberOr("speed", FakeLoc.speed)
                return true
            }
            "set_bearing" -> {
                val bearing = rely.getDouble("bearing", 0.0)
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                // 朝向变了就立刻投一帧：摇杆只转向不位移时，若不投递，应用侧要等保活补帧
                // （1.2s）才看到新朝向——就是"转了半天不动，然后跳一下"。
                // 限流 80ms（≈12Hz）：摇杆拖动事件可达 60Hz，逐事件推流既浪费又异常。
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                if (FakeLoc.isSystemServerProcess &&
                    nowNanos - lastBearingPushNanos >= BEARING_PUSH_MIN_INTERVAL_NANOS
                ) {
                    lastBearingPushNanos = nowNanos
                    LocationServiceHook.callOnLocationChanged(force = true)
                }
                return true
            }
            "move" -> {
                val distance = rely.getDouble("n", 0.0)
                if (distance == 0.0) return true
                val bearing = rely.getDouble("bearing", 0.0)
                val newLoc = FakeLoc.moveLocation(
                    n = distance,
                    angle = bearing
                )
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("move: distance=$distance, bearing=$bearing, newLoc=$newLoc")
                }
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                return updateCoordinate(newLoc.first, newLoc.second).also {
                    // force：坐标已变化，立刻送达（推送链的被拦死注册必须拿到这一步的位移）
                    if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged(force = true)
                }
            }
            "update_location" -> {
                val mode = rely.getString("mode")
                var newLat = rely.getDouble("lat", 0.0)
                var newLon = rely.getDouble("lon", 0.0)
                // 统一落点入口：写坐标后**立即投递一帧**。
                // 旧回调架构下只有 move / broadcast_location 会投递，而**路线自动播放**走的是
                // update_location——不在此投递就会出现「坐标在推进但应用收不到」：
                // 表现为位置不动、被拉回真实坐标、GPS 信号差（长时间无帧）。
                fun applyCoordinate(lat: Double, lon: Double, explicitBearing: Double? = null): Boolean {
                    val ok = updateCoordinate(lat, lon, updateBearing = true, explicitBearing = explicitBearing)
                    if (ok && FakeLoc.isSystemServerProcess) {
                        // force：路线播放/设点每 tick 都推进坐标，必须立刻送达
                        LocationServiceHook.callOnLocationChanged(force = true)
                    }
                    return ok
                }
                when(mode) {
                    "+" -> return applyCoordinate(newLat + FakeLoc.latitude, newLon + FakeLoc.longitude)
                    "-" -> return applyCoordinate(FakeLoc.latitude - newLat, FakeLoc.longitude - newLon)
                    "*" -> return applyCoordinate(newLat * FakeLoc.latitude, newLon * FakeLoc.longitude)
                    "/" -> {
                        if (newLat == 0.0 || newLon == 0.0) {
                            return false
                        }
                        return applyCoordinate(FakeLoc.latitude / newLat, FakeLoc.longitude / newLon)
                    }
                    "=" -> {
                        // 路线播放：方位随位置显式下发（路线切线）——弧长推进每 tick 位移
                        // 仅约 0.2m，达不到位移法 1m 门控，靠位移推算会导致朝向永不更新。
                        val explicitBearing =
                            if (rely.containsKey("bearing")) rely.getDouble("bearing") else null
                        return applyCoordinate(newLat, newLon, explicitBearing)
                    }
                    "random" -> {
                        return applyCoordinate(Random.nextDouble(-90.0, 90.0), Random.nextDouble(-180.0, 180.0))
                    }
                }
                return true
            }
            "put_config" -> {
                val enable = rely.getBoolean("enable", FakeLoc.enable)
                val speed = rely.numberOr("speed", FakeLoc.speed)
                val altitude = rely.numberOr("altitude", FakeLoc.altitude)
                val accuracy = rely.numberOr("accuracy", FakeLoc.accuracy.toDouble()).toFloat()
                val enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                val disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                val needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                var minSatellites = rely.getInt("min_satellites", 12)
                if (minSatellites < 0) {
                    minSatellites = 12
                }

                val enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
                val enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
                val disableRequestGeofence = rely.getBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
                val disableGetFromLocation = rely.getBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)
                val loopBroadcastLocation = rely.getBoolean("loop_broadcast_location", FakeLoc.loopBroadcastLocation)
                // 实验性开关：读不到键时保持当前值（旧版 App 不下发该键 → 行为不变）
                val binderSensorMock = rely.getBoolean("binder_sensor_mock", FakeLoc.enableBinderSensorMock)
                // 注入栅格分辨率（Hz，0=自动）：读不到键时保持当前值（旧版 App 不下发）
                val sensorGridHz = rely.getInt("sensor_grid_hz", FakeLoc.sensorGridHz)
                // 步频倍率（微调步频↔速度）：读不到键时保持当前值
                val cadenceScale = rely.numberOr("cadence_scale", FakeLoc.cadenceScale)

                FakeLoc.enable = enable
                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy
                FakeLoc.enableDebugLog = enableDebugLog
                FakeLoc.disableFusedLocation = disableFusedLocation
                FakeLoc.needDowngradeToCdma = needDowngradeToCdma
                FakeLoc.minSatellites = minSatellites
                FakeLoc.enableAGPS = enableAGPS
                FakeLoc.enableNMEA = enableNMEA
                FakeLoc.disableRequestGeofence = disableRequestGeofence
                FakeLoc.disableGetFromLocation = disableGetFromLocation
                FakeLoc.loopBroadcastLocation = loopBroadcastLocation

                // Binder 外周传感器模拟：仅在 system_server 内生效（装载/卸载原生注入层）。
                // 非 system_server 进程只镜像开关值，不做任何安装。
                FakeLoc.enableBinderSensorMock = binderSensorMock
                FakeLoc.sensorGridHz = sensorGridHz
                FakeLoc.cadenceScale = if (cadenceScale <= 0.0) 1.0 else cadenceScale
                runCatching { BinderSensorNative.setGridHz(sensorGridHz) }
                    .onFailure { Logger.warn("栅格设置下发失败：${it.message}") }
                // 原生层挂不上就明确回报失败：App 侧据此提示"配置失败"，
                // 而不是让用户以为开关生效了、实际什么都没发生。
                if (!BinderSensorMock.onConfigChanged()) {
                    Logger.error("Binder 外周传感器模拟：原生注入层不可用（详见 logcat PortalSensor）")
                    return false
                }
                return true
            }
            "sync_config" -> {
                rely.putBoolean("enable", FakeLoc.enable)
                rely.putDouble("latitude", FakeLoc.latitude)
                rely.putDouble("longitude", FakeLoc.longitude)
                rely.putDouble("altitude", FakeLoc.altitude)
                rely.putDouble("speed", FakeLoc.speed)
                rely.putDouble("speed_amplitude", FakeLoc.speedAmplitude)
                rely.putBoolean("has_bearings", FakeLoc.hasBearings)
                rely.putDouble("bearing", FakeLoc.bearing)
                rely.putParcelable("last_location", FakeLoc.lastLocation)
                rely.putBoolean("enable_log", FakeLoc.enableLog)
                rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
                rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
                rely.putBoolean("hide_mock", FakeLoc.hideMock)
                rely.putBoolean("hook_wifi", FakeLoc.hookWifi)
                rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                rely.putBoolean("loop_broadcast_location", FakeLoc.loopBroadcastLocation)
                rely.putBoolean("binder_sensor_mock", FakeLoc.enableBinderSensorMock)
                return true
            }
            "broadcast_location" -> {
                // force：显式广播就是「现在推一帧」——反定位拉回线程靠反复强制推送压制真实位置
                LocationServiceHook.callOnLocationChanged(force = true)
                return true
            }
            else -> return false
        }
    }

//    private var hasHookSensor = false
//
//    private fun tryHookSensor(classLoader: ClassLoader = FakeLoc::class.java.classLoader!!) {
//        if (hasHookSensor || proxyBinders.isNullOrEmpty()) return
//
//
//
//        hasHookSensor = true
//    }

//    private fun generateLocation(): Location {
//        val (location, realLocation) = if (FakeLocationConfig.lastLocation != null) {
//            (FakeLocationConfig.lastLocation!! to true)
//        } else {
//            (Location(LocationManager.GPS_PROVIDER) to false)
//        }
//
//        return LocationServiceProxyHook.injectLocation(location, realLocation)
//    }

    /**
     * 启动拉回（单次版反定位拉回）：模拟启动 0.5s 后主动推送一次当前位置。
     *
     * 模拟启动只是改写系统回调：若设备室内无真实 GPS 上报，目标应用会停在
     * 原有位置，需手动摇杆/自动路线播放一次才能到预定位置。这里在启动后延迟
     * 0.5s 单次 callOnLocationChanged，把位置立即拉到预定坐标。
     * 仅在反定位拉回（loopBroadcastLocation）未开启时使用——开启时已有循环线程
     * 周期广播，避免重复推送。stop 后延迟线程若才触发，callOnLocationChanged
     * 内 injectLocation 会因 enable=false 原样返回，无害。
     */
    private fun scheduleInitialPullback() {
        if (FakeLoc.loopBroadcastLocation) return
        if (!FakeLoc.isSystemServerProcess) return
        kotlin.concurrent.thread(name = "InitialPullback", isDaemon = true, start = true) {
            try {
                Thread.sleep(500)
                LocationServiceHook.callOnLocationChanged(force = true)
            } catch (_: InterruptedException) {
                // 忽略中断
            }
        }
    }

    /**
     * 配置镜像入口：把另一进程同步来的坐标走**唯一落点入口**写入。
     * 供 BaseDivineService.syncConfig 使用——直写 FakeLoc.latitude/longitude 会绕过
     * 位移记录（速度推算/静止检测的数据源），构成同一节点的第二条入边。
     */
    fun applySyncedCoordinate(lat: Double, lon: Double) {
        updateCoordinate(lat, lon)
    }

    /** 朝向变化推流的最小间隔：摇杆拖动事件很密，限到 ~12Hz 足够平滑且不异常 */
    private const val BEARING_PUSH_MIN_INTERVAL_NANOS = 80_000_000L
    @Volatile private var lastBearingPushNanos = 0L

    /** 方向参考点（滚动）：累计位移达到 [BEARING_REF_MIN_DIST_M] 才重算方向，过滤逐点轨迹噪声 */
    @Volatile private var bearingRefLat = Double.NaN
    @Volatile private var bearingRefLon = Double.NaN
    private const val BEARING_REF_MIN_DIST_M = 3.0

    private fun updateCoordinate(
        newLat: Double,
        newLon: Double,
        updateBearing: Boolean = false,
        explicitBearing: Double? = null
    ): Boolean {
        if (newLat in -90.0..90.0 && newLon in -180.0..180.0) {
            if (updateBearing && explicitBearing != null) {
                // 显式方位（自动播放：App 侧按平滑路径算出切线方向）：直接采用，
                // 不再走位移推算——步长小于位移法门控时朝向不会更新。
                // 直接写权威目标：平滑过渡由位置端输出（processedBearing 的中轴低通）完成
                FakeLoc.bearing = (explicitBearing % 360.0 + 360.0) % 360.0
                FakeLoc.hasBearings = true
                bearingRefLat = newLat
                bearingRefLon = newLon
            } else if (updateBearing) {
                // 按实际位移方向更新朝向（权威目标）。路线播放是逐点跳点推进，直接用相邻
                // 两点方向会被密集点位 + 轨迹噪声带得乱跳，因此用**滚动参考点**（累计位移
                // ≥ 3m 才重算方向，基线更长更稳定）；输出端的中轴低通再负责平滑过渡。
                val dLat = newLat - FakeLoc.latitude
                val dLon = newLon - FakeLoc.longitude
                // 近似距离（米）：1° 纬度 ≈ 111.32km，经度按 cos(纬度) 折算
                val distM = Math.hypot(dLat, dLon * Math.cos(Math.toRadians(newLat))) * 111320.0
                // 位移过小（<1m 静止微抖）不更新方向：避免静止微扰把朝向带偏
                if (distM >= 1.0) {
                    if (bearingRefLat.isNaN()) {
                        bearingRefLat = FakeLoc.latitude
                        bearingRefLon = FakeLoc.longitude
                    }
                    val refDistM = Math.hypot(
                        newLat - bearingRefLat,
                        (newLon - bearingRefLon) * Math.cos(Math.toRadians(newLat))
                    ) * 111320.0
                    if (refDistM >= BEARING_REF_MIN_DIST_M) {
                        FakeLoc.bearing = FakeLoc.calculateBearing(
                            bearingRefLat, bearingRefLon, newLat, newLon
                        )
                        // 已有明确朝向：避免下次 start 重新随机分配（角度忽然换向）
                        FakeLoc.hasBearings = true
                        bearingRefLat = newLat
                        bearingRefLon = newLon
                    }
                }
            }
            FakeLoc.latitude = newLat
            FakeLoc.longitude = newLon
            // 记录基础坐标变化：既用于静止检测（注入 speed 在 0 与实测速度间切换），
            // 也用于按实际位移推算速度（FakeLoc.measuredSpeed）。
            // 路线播放走的是 update_location（不是 move），此处统一记录。
            FakeLoc.recordCoordinateChange(newLat, newLon)
            return true
        } else {
            Logger.error("Invalid latitude or longitude: $newLat, $newLon")
            return false
        }
    }
}

/**
 * 读取 Bundle 中的数值并兼容 Int/Long/Float/Double。
 * Bundle.getDouble 在键值是 Float 时会**静默返回默认值**（类型不符），
 * App 侧 putFloat 写、服务端 getDouble 读过一次就是恒 0——这里按 Number 统一取。
 */
private fun Bundle.numberOr(key: String, default: Double): Double =
    (get(key) as? Number)?.toDouble() ?: default
