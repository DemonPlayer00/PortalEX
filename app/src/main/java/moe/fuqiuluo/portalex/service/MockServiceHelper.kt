package moe.fuqiuluo.portalex.service

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import moe.fuqiuluo.portalex.Portal
import moe.fuqiuluo.portalex.ext.cadenceScale
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
import moe.fuqiuluo.portalex.ext.sensorNoise
import moe.fuqiuluo.portalex.ext.loopBroadcastlocation
import moe.fuqiuluo.xposed.utils.PortalProtocol.Cmd
import moe.fuqiuluo.xposed.utils.PortalProtocol.Key
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.PortalProtocol

object MockServiceHelper {
    const val PROVIDER_NAME = PortalProtocol.PROVIDER
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
        if(locationManager.sendExtraCommand(PortalProtocol.PROVIDER, "exchange_key", rely)) {
            rely.getString(Key.EXCHANGE_REPLY)?.let {
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
        rely.putString(Key.COMMAND_ID, Cmd.IS_START)
        if(locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)) {
            return rely.getBoolean(Key.IS_START)
        }
        return false
    }

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.IS_GNSS_START)
        if(locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)) {
            return rely.getBoolean(Key.IS_GNSS_START)
        }
        return false
    }

    fun startGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.START_GNSS_MOCK)
        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.STOP_GNSS_MOCK)
        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
    }


    fun startWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.START_WIFI_MOCK)
        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.STOP_WIFI_MOCK)
        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
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
        rely.putString(Key.COMMAND_ID, Cmd.START)
        rely.putDouble(Key.SPEED, speed)
        rely.putDouble(Key.ALTITUDE, altitude)
        rely.putFloat(Key.ACCURACY, accuracy)
        // App 侧状态同步：putConfig 不再携带 enable，避免打开设置页/GNSS 页时把模拟误关
        FakeLoc.enable = true
        startLoopBroadcastLocation(locationManager)
        // 撑住框架的传感器轮询（见 startSensorPollKeepAlive 的注释）
        runCatching { Portal.appContext }.getOrNull()?.let { startSensorPollKeepAlive(it) }
        return if(locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)) {
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
        rely.putString(Key.COMMAND_ID, Cmd.STOP)
        stopLoopBroadcastLocation()
        runCatching { Portal.appContext }.getOrNull()?.let { stopSensorPollKeepAlive(it) }
        FakeLoc.enable = false
        if (locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)) {
            return !isMockStart(locationManager)
        }
        return false
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.GET_LOCATION)
        if(locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)) {
            return Pair(rely.getDouble(Key.LAT), rely.getDouble(Key.LON))
        }
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.GET_LISTENER_SIZE)
        if(locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)) {
            return rely.getInt(Key.LISTENER_SIZE)
        }
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.BROADCAST_LOCATION)
        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.SET_BEARING)
        rely.putDouble(Key.BEARING, bearing)
        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
    }







    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.MOVE)
        rely.putDouble(Key.DISTANCE, distance)
        rely.putDouble(Key.BEARING, bearing)

        if (FakeLoc.enableDebugLog) {
            Log.d("MockServiceHelper", "move: distance=$distance, bearing=$bearing")
        }

        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
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
        rely.putString(Key.COMMAND_ID, Cmd.UPDATE_LOCATION)
        rely.putDouble(Key.LAT, lat)
        rely.putDouble(Key.LON, lon)
        rely.putString(Key.MODE, mode)
        if (bearing != null) {
            rely.putDouble(Key.BEARING, bearing)
        }
        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
    }

    /**
     * **纯传输**：把一条命令 Bundle 发给系统侧。
     *
     * 不碰偏好、不写镜像、不解释结果 —— "发什么/什么时候发/失败了意味着什么"
     * 属于 [ConfigSync]（配置）或各自的调用点（会话命令）。
     * 未握手时直接返回 false（调用方据此区分"没服务"与"被拒绝"）。
     */
    fun send(locationManager: LocationManager?, rely: Bundle): Boolean {
        if (locationManager == null) return false
        if (!::randomKey.isInitialized) return false
        if (rely.getString(Key.COMMAND_ID) == null) return false
        return locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)
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
        rely.putString(Key.COMMAND_ID, Cmd.GET_SENSOR_STATUS)
        return if (locationManager.sendExtraCommand(PortalProtocol.PROVIDER, randomKey, rely)) rely else null
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