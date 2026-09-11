package moe.fuqiuluo.portalex.service

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import moe.fuqiuluo.portalex.Portal
import moe.fuqiuluo.portalex.ext.sensorGridHz
import moe.fuqiuluo.portalex.ext.altitude
import moe.fuqiuluo.portalex.ext.binderSensorMock
import moe.fuqiuluo.portalex.ext.debug
import moe.fuqiuluo.portalex.ext.disableFusedProvider
import moe.fuqiuluo.portalex.ext.enableAGPS
import moe.fuqiuluo.portalex.ext.enableGetFromLocation
import moe.fuqiuluo.portalex.ext.enableNMEA
import moe.fuqiuluo.portalex.ext.enableRequestGeofence
import moe.fuqiuluo.portalex.ext.minSatelliteCount
import moe.fuqiuluo.portalex.ext.needDowngradeToCdma
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.ext.loopBroadcastlocation
import moe.fuqiuluo.xposed.utils.FakeLoc

object MockServiceHelper {
    const val PROVIDER_NAME = "portal"
    private lateinit var randomKey: String

    private var loopThread :Thread ?= null
    @Volatile private var isRunning = false

    // ---- 传感器轮询心跳 ----
    /**
     * 模拟会话期间保持一个**低频连续型传感器**订阅。
     *
     * 这不是为了取数，而是为了让框架的传感器轮询转起来：SensorService 的取事件循环
     * 由 HAL 的投递驱动，而 HAL 只为"当前被订阅的传感器"投递。若目标应用只订阅了
     * on-change 类型的步数传感器（没有连续型），框架就长时间阻塞在等待里 ——
     * **本模块在框架层注入的传感器事件于是只能零星到达**（实测：无连续订阅时
     * 走动几分钟只发出 2 次步事件，注入计数 emitted/dropped ≈ 1:38）。
     * 应用按到达时间估算步频时，这种"半天来一批"就会读出 277 这类离谱值。
     * 挂一个 SENSOR_DELAY_UI 的加速度计（≈16Hz）即可把轮询撑到足够密，
     * 让注入的事件平滑送达所有客户端（对目标应用同样有效）。
     */
    private var keepAliveListener: android.hardware.SensorEventListener? = null

    fun startSensorPollKeepAlive(context: Context) {
        if (keepAliveListener != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager ?: return
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {}
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        if (sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)) {
            keepAliveListener = listener
            Log.d("MockServiceHelper", "sensor poll keep-alive started")
        }
    }

    fun stopSensorPollKeepAlive(context: Context) {
        val listener = keepAliveListener ?: return
        keepAliveListener = null
        runCatching {
            (context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
                ?.unregisterListener(listener)
        }
        Log.d("MockServiceHelper", "sensor poll keep-alive stopped")
    }

    fun tryInitService(locationManager: LocationManager) {
        val rely = Bundle()
        Log.d("MockServiceHelper", "Try to init service")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, "exchange_key", rely)) {
            rely.getString("key")?.let {
                randomKey = it
                Log.d("MockServiceHelper", "Service init success, key: $randomKey")
            }
        } else {
            Log.e("MockServiceHelper", "Failed to init service")
        }
    }

    fun isMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_start")
        }
        return false
    }

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_gnss_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_gnss_start")
        }
        return false
    }

    fun startGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }


    fun startWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
    ): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start")
        rely.putDouble("speed", speed)
        rely.putDouble("altitude", altitude)
        rely.putFloat("accuracy", accuracy)
        // App 侧状态同步：putConfig 不再携带 enable，避免打开设置页/GNSS 页时把模拟误关
        FakeLoc.enable = true
        startLoopBroadcastLocation(locationManager)
        // 撑住框架的传感器轮询（见 startSensorPollKeepAlive 的注释）
        runCatching { Portal.appContext }.getOrNull()?.let { startSensorPollKeepAlive(it) }
        return if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            isMockStart(locationManager)
        } else {
            false
        }
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop")
        stopLoopBroadcastLocation()
        runCatching { Portal.appContext }.getOrNull()?.let { stopSensorPollKeepAlive(it) }
        FakeLoc.enable = false
        if (locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return !isMockStart(locationManager)
        }
        return false
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_location")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return Pair(rely.getDouble("lat"), rely.getDouble("lon"))
        }
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_listener_size")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getInt("size")
        }
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "broadcast_location")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_bearing")
        rely.putDouble("bearing", bearing)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }







    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "move")
        rely.putDouble("n", distance)
        rely.putDouble("bearing", bearing)

        if (FakeLoc.enableDebugLog) {
            Log.d("MockServiceHelper", "move: distance=$distance, bearing=$bearing")
        }

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        return updateLocation(locationManager, lat, lon, "=")
    }

    /**
     * 设置位置。[bearing] 非空时随位置显式下发朝向（自动播放的路线切线方向）——
     * 系统侧直接采用，不走位移推算（弧长推进步长小，位移法 1m 门控会挡住朝向更新）。
     */
    fun setLocation(
        locationManager: LocationManager,
        lat: Double,
        lon: Double,
        bearing: Double?
    ): Boolean {
        // 本进程镜像同一次赋值：App 侧的 FakeLoc.bearing 与系统侧保持同一口径
        // （否则自动播放结束后，App 侧仍拿着旧朝向）
        if (bearing != null) {
            FakeLoc.bearing = bearing
            FakeLoc.hasBearings = true
        }
        return updateLocation(locationManager, lat, lon, "=", bearing)
    }

    fun updateLocation(
        locationManager: LocationManager,
        lat: Double,
        lon: Double,
        mode: String,
        bearing: Double? = null
    ): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "update_location")
        rely.putDouble("lat", lat)
        rely.putDouble("lon", lon)
        rely.putString("mode", mode)
        if (bearing != null) {
            rely.putDouble("bearing", bearing)
        }
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun putConfig(locationManager: LocationManager, context: Context): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }

        FakeLoc.altitude = context.altitude
        FakeLoc.speed = context.speed
        FakeLoc.enableDebugLog = context.debug
        FakeLoc.disableFusedLocation = context.disableFusedProvider
        FakeLoc.needDowngradeToCdma = context.needDowngradeToCdma
        FakeLoc.minSatellites = context.minSatelliteCount
        FakeLoc.enableAGPS = context.enableAGPS
        FakeLoc.enableNMEA = context.enableNMEA
        FakeLoc.disableRequestGeofence = !context.enableRequestGeofence
        FakeLoc.disableGetFromLocation = !context.enableGetFromLocation
        // 实验性：Binder 外周传感器模拟（系统框架层接管，默认关）
        FakeLoc.enableBinderSensorMock = context.binderSensorMock
        FakeLoc.sensorGridHz = context.sensorGridHz

        val rely = Bundle()
        rely.putString("command_id", "put_config")
        // 不再携带 enable：模拟启停只由 start/stop 命令控制（旧实现 App 侧 enable 恒 false，
        // 打开设置页/GNSS 页触发的 put_config 会把系统侧模拟静默关闭）
        rely.putDouble("altitude", FakeLoc.altitude)
        rely.putDouble("speed", FakeLoc.speed)
        rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
        rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
        rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
        rely.putInt("min_satellites", FakeLoc.minSatellites)
        rely.putBoolean("loop_broadcast_location", context.loopBroadcastlocation)
        rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
        rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
        rely.putBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
        rely.putBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)
        rely.putBoolean("binder_sensor_mock", FakeLoc.enableBinderSensorMock)
        rely.putInt("sensor_grid_hz", FakeLoc.sensorGridHz)

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    /**
     * 诊断页数据源：向系统侧要一份数值总览（注入层状态、运动学、意图步频 vs 实际步频）。
     * 服务未握手时返回 null。
     */
    fun getSensorStatus(locationManager: LocationManager): Bundle? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_sensor_status")
        return if (locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) rely else null
    }

    fun isServiceInit(): Boolean {
        return ::randomKey.isInitialized
    }

    /**
     * 下发实验性开关「Binder 外周传感器模拟」。
     *
     * 单独一条命令（而不是塞进 put_config）的目的：系统侧在原生注入层装载失败时
     * 会让本命令返回 false，App 侧据此给出明确提示——**不做假成功**。
     * 启动时（tryInitService 之后）也调一次，让开关跨重启自动恢复。
     */
    fun setBinderSensorMock(locationManager: LocationManager, enabled: Boolean): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_sensor_mock")
        rely.putBoolean("binder_sensor_mock", enabled)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }


    private fun startLoopBroadcastLocation(locationManager: LocationManager) {
        val appContext = Portal.appContext
        val delayTime = appContext.reportDuration.toLong()
        if (isRunning) return
        if (!appContext.loopBroadcastlocation) return

        isRunning = true

        loopThread = Thread {
            Log.d("MockServiceHelper", "loopBoardcast: Start")
            while (isRunning) {
                try {
                    broadcastLocation(locationManager)
                    Thread.sleep(delayTime)
                } catch (e: InterruptedException) {
                    if (FakeLoc.enableDebugLog) {
                        Log.d("MockServiceHelper", "loopBoardcast: Stop")
                    }
                    break
                }
            }
        }
        loopThread!!.start()
    }

    private fun stopLoopBroadcastLocation(){
        isRunning =false
        loopThread?.interrupt()
        loopThread = null
    }

}