package moe.fuqiuluo.xposed

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import moe.fuqiuluo.xposed.hooks.LocationServiceHook
import moe.fuqiuluo.xposed.hooks.sensor.BinderSensorMock
import moe.fuqiuluo.xposed.hooks.sensor.BinderSensorNative
import moe.fuqiuluo.xposed.utils.PortalProtocol.Cmd
import moe.fuqiuluo.xposed.utils.PortalProtocol.Key
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.FusedMode
import moe.fuqiuluo.xposed.utils.FusedStatus
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.PortalDiag
import moe.fuqiuluo.xposed.utils.SensorNoise
import java.util.Collections
import kotlin.random.Random

object RemoteCommandHandler {

    /**
     * 命令通道的两个入口。门禁不同，必须区分，不能靠默认值糊过去：
     *  - [PROVIDER]：`LocationManagerService.sendExtraCommand("portal", …)`——**任何应用都能打**，
     *    因此要求调用者 uid 通过 [BinderUtils.isLocationProviderEnabled]（只有模块自身）**且**带对钥匙。
     *  - [PROXY]：代理 Binder 的 `onTransact`——调用者只可能是转发指令的 system_server，
     *    因此要求 `uid == SYSTEM_UID`（另有钥匙校验）。
     */
    enum class Origin { PROVIDER, PROXY }

    private val proxyBinders by lazy { Collections.synchronizedList(arrayListOf<IBinder>()) }
    private val needProxyCmd = arrayOf(Cmd.START, Cmd.STOP, Cmd.SET_SPEED_AMP, Cmd.SET_ALTITUDE, Cmd.SET_SPEED, Cmd.UPDATE_LOCATION, Cmd.SET_BEARING, Cmd.MOVE, Cmd.PUT_CONFIG)

    /** 已被拒绝过的 uid：门禁告警只记一次，免得被别的应用刷日志（刷日志本身就是一种提示） */
    private val warnedDeniedUids = Collections.synchronizedSet(HashSet<Int>())

    // 由 BaseDivineService 的 exchange_key 在 client 进程同步为系统侧 key（两进程同一把钥匙）；
    // 旧实现各进程 lazy 生成各自的随机值，系统转发来的指令永远通不过校验（配置不传播的根因）
    // SecureRandom（旧实现用 java.util.Random：种子可枚举，钥匙不该赌这个）
    internal var randomKey: String = newKey()

    private fun newKey(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return "portal_" + bytes.joinToString("") { "%02x".format(it) }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun handleInstruction(command: String, rely: Bundle, origin: Origin = Origin.PROVIDER): Boolean {
        /*
         * ---- 门禁（两道，缺一不可）----
         *
         * 这里修的是**实测可利用**的越权：旧实现在 exchange_key 的门禁失败后**没有 return**，
         * 控制流继续往下走到指令分发 ⇒ 任何应用只要把 command 写成 "exchange_key"、再在
         * extras 里带 command_id，就能执行任意指令。实测（第三方包、uid≠模块）：
         *   · get_sensor_status ⇒ 返回 true 且回包带 26 个内部字段（含 portal_gate 里的模块 uid）
         *   · is_start / put_config / set_sensor_mock / set_proxy ⇒ 均可执行
         *     （能关掉注入层、也能把自己注册成指令代理 ⇒ 检测与破坏都成立）
         * 另外我们的 hook 是在 beforeHook 里直接 `result = true`，**平台自己的权限检查会被跳过**
         * ⇒ 连 ACCESS_LOCATION_EXTRA_COMMANDS 都不需要。
         *
         * 现在的规则：入口先判调用者，**不通过就立刻返回**（不落到分发）；
         * 对外表现与"这个 provider 不存在"一致 —— 剩下交给平台原实现，不制造可判定的差异。
         */
        when (origin) {
            Origin.PROVIDER -> {
                val uid = BinderUtils.getCallerUid()
                if (uid != BinderUtils.moduleOwnerUid()) {
                    PortalDiag.fail(PortalDiag.Area.COMMAND_REJECT)
                    if (warnedDeniedUids.add(uid)) {
                        Logger.warn("拒绝来自 uid=$uid 的命令通道访问（该通道只对模块自身开放）")
                    }
                    return false
                }
            }
            Origin.PROXY -> {
                // 代理指令只可能由 system_server 转发过来（proxy binder 是注册给系统的）
                if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                    Logger.warn("代理通道拒绝 uid=${Binder.getCallingUid()}（只接受系统转发）")
                    return false
                }
            }
        }

        // Exchange key -> returns a random key -> is used to verify that it is the PortalManager
        if (command == Cmd.EXCHANGE_KEY) {
            val userId = BinderUtils.getCallerUid()
            if (BinderUtils.isLocationProviderEnabled(userId)) {
                rely.putString(Key.EXCHANGE_REPLY, randomKey)
                return true
            }
            // 拿不到钥匙：既不回答，也**绝不继续分发**（旧实现少了这个 return）
            return false
        }
        if (command != randomKey) return false
        val commandId = rely.getString(Key.COMMAND_ID) ?: return false

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
            Cmd.SET_PROXY -> {
                Logger.info("SubProxyBinder: ${rely.getBinder(Key.PROXY_BINDER)} from ${BinderUtils.getUidPackageNames()}!")
                rely.getBinder(Key.PROXY_BINDER)?.let {
                    proxyBinders.add(it)
                }
                return true
            }
            Cmd.START -> {
                val speed = rely.getDouble(Key.SPEED, FakeLoc.speed)
                val altitude = rely.getDouble(Key.ALTITUDE, FakeLoc.altitude)
                val accuracy = rely.getFloat(Key.ACCURACY, FakeLoc.accuracy)

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
            Cmd.STOP -> {
                FakeLoc.enable = false
                FakeLoc.hasBearings = false
                BinderSensorMock.onSimulationChanged()
                return true
            }
            Cmd.IS_START -> {
                rely.putBoolean(Key.IS_START, FakeLoc.enable)
                return true
            }
            Cmd.START_GNSS_MOCK -> {
                FakeLoc.enableMockGnss = true
                // 立即主动推送一次模拟卫星数据：雷达无需等待系统 GNSS 引擎上报
                LocationServiceHook.pushGnssStatus()
                return true
            }
            Cmd.STOP_GNSS_MOCK -> {
                FakeLoc.enableMockGnss = false
                return true
            }
            Cmd.IS_GNSS_START -> {
                rely.putBoolean(Key.IS_GNSS_START, FakeLoc.enableMockGnss)
                return true
            }
            Cmd.IS_WIFI_MOCK_START -> {
                rely.putBoolean(Key.IS_WIFI_MOCK_START, FakeLoc.enableMockWifi)
                return true
            }
            Cmd.START_WIFI_MOCK -> {
                FakeLoc.enableMockWifi = true
                return true
            }
            Cmd.STOP_WIFI_MOCK -> {
                FakeLoc.enableMockWifi = false
                return true
            }
            Cmd.SET_SENSOR_MOCK -> {
                // 实验性：Binder 外周传感器模拟开关（只下发给系统侧，不经代理转发）
                val enabled = rely.getBoolean(Key.BINDER_SENSOR_MOCK, FakeLoc.enableBinderSensorMock)
                FakeLoc.enableBinderSensorMock = enabled
                // 启动时这条命令是"传感器侧配置"的唯一载体：噪声档与注入栅格随它一起恢复
                // （否则系统进程重启后栅格退回自动，噪声档也会退回内置默认）
                applyNoiseProfile(rely)
                val gridHz = rely.getInt(Key.SENSOR_GRID_HZ, -1)
                if (gridHz >= 0) {
                    FakeLoc.sensorGridHz = gridHz
                    if (BinderSensorMock.isNativeReady) {
                        pushToNative("栅格恢复") { BinderSensorNative.setGridHz(gridHz) }
                    }
                }
                if (!BinderSensorMock.onConfigChanged()) {
                    Logger.error("Binder 外周传感器模拟：原生注入层不可用（详见 logcat PortalSensor）")
                    return false
                }
                return true
            }
            Cmd.GET_FUSED_STATE -> {
                rely.putBoolean(Key.FUSED_AVAILABLE, FusedStatus.available)
                rely.putInt(Key.FUSED_MODE, FakeLoc.fusedMode)
                rely.putString(Key.FUSED_STATUS, FusedStatus.statusLine())
                // 设置页打开/切换调试模式时也刷一条状态日志（调试模式关着就完全安静）
                FusedStatus.logIfDebug()
                return true
            }
            Cmd.GET_SENSOR_STATUS -> {
                // 诊断页数值总览：注入层/运动学/步频意图 vs 实际的原始数值
                BinderSensorMock.fillStatus(rely)
                return true
            }
            Cmd.IS_SENSOR_MOCK -> {
                rely.putBoolean(Key.BINDER_SENSOR_MOCK, FakeLoc.enableBinderSensorMock)
                return true
            }
            Cmd.GET_LOCATION -> {
                rely.putDouble(Key.LAT, FakeLoc.latitude)
                rely.putDouble(Key.LON, FakeLoc.longitude)
                return true
            }
            Cmd.GET_LISTENER_SIZE -> {
                rely.putInt(Key.LISTENER_SIZE, LocationServiceHook.locationListeners.size)
                return true
            }
            Cmd.GET_SPEED -> {
                rely.putDouble(Key.SPEED, FakeLoc.speed)
                return true
            }
            Cmd.GET_BEARING -> {
                rely.putDouble(Key.BEARING, FakeLoc.bearing)
                return true
            }
            Cmd.GET_ALTITUDE -> {
                rely.putDouble(Key.ALTITUDE, FakeLoc.altitude)
                return true
            }
            Cmd.SET_SPEED_AMP -> {
                FakeLoc.speedAmplitude = rely.numberOr("speed_amplitude", FakeLoc.speedAmplitude)
                return true
            }
            Cmd.SET_ALTITUDE -> {
                FakeLoc.altitude = rely.numberOr("altitude", FakeLoc.altitude)
                return true
            }
            Cmd.SET_SPEED -> {
                FakeLoc.speed = rely.numberOr("speed", FakeLoc.speed)
                return true
            }
            Cmd.SET_BEARING -> {
                // 键缺失 ⇒ 保持当前朝向（默认 0.0 会让"停下时发的无 bearing 命令"把朝向清成 0：
                // 实测 MI6/LineageOS 上表现为"停止 1 秒后指南针归 0、角度计不再变化"）
                val bearing = rely.getDouble(Key.BEARING, FakeLoc.bearing)
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
            Cmd.MOVE -> {
                val distance = rely.getDouble(Key.DISTANCE, 0.0)
                if (distance == 0.0) return true
                // 键缺失 ⇒ 保持当前朝向（默认 0.0 会让"停下时发的无 bearing 命令"把朝向清成 0：
                // 实测 MI6/LineageOS 上表现为"停止 1 秒后指南针归 0、角度计不再变化"）
                val bearing = rely.getDouble(Key.BEARING, FakeLoc.bearing)
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
            Cmd.UPDATE_LOCATION -> {
                val mode = rely.getString(Key.MODE)
                var newLat = rely.getDouble(Key.LAT, 0.0)
                var newLon = rely.getDouble(Key.LON, 0.0)
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
                            if (rely.containsKey("bearing")) rely.getDouble(Key.BEARING) else null
                        return applyCoordinate(newLat, newLon, explicitBearing)
                    }
                    Cmd.RANDOM -> {
                        return applyCoordinate(Random.nextDouble(-90.0, 90.0), Random.nextDouble(-180.0, 180.0))
                    }
                }
                return true
            }
            Cmd.PUT_CONFIG -> {
                val enable = rely.getBoolean(Key.ENABLE, FakeLoc.enable)
                val speed = rely.numberOr("speed", FakeLoc.speed)
                val altitude = rely.numberOr("altitude", FakeLoc.altitude)
                val accuracy = rely.numberOr("accuracy", FakeLoc.accuracy.toDouble()).toFloat()
                val enableDebugLog = rely.getBoolean(Key.ENABLE_DEBUG_LOG, FakeLoc.enableDebugLog)
                // 融合处置三态：新键优先；旧 App 只发布尔键 ⇒ 兜底映射（true=拒绝 / false=伪装）
                val fusedMode = run {
                    val m = rely.getInt(Key.FUSED_MODE, -1)
                    if (m >= 0) FusedMode.sanitize(m)
                    else if (rely.getBoolean(Key.DISABLE_FUSED_LOCATION, FakeLoc.rejectFused)) FusedMode.REJECT
                    else FusedMode.DISGUISE
                }
                val needDowngradeToCdma = rely.getBoolean(Key.NEED_DOWNGRADE_TO_2G, FakeLoc.needDowngradeToCdma)
                var minSatellites = rely.getInt(Key.MIN_SATELLITES, 12)
                if (minSatellites < 0) {
                    minSatellites = 12
                }

                val enableAGPS = rely.getBoolean(Key.ENABLE_AGPS, FakeLoc.enableAGPS)
                val enableNMEA = rely.getBoolean(Key.ENABLE_NMEA, FakeLoc.enableNMEA)
                val disableRequestGeofence = rely.getBoolean(Key.DISABLE_REQUEST_GEOFENCE, FakeLoc.disableRequestGeofence)
                val disableGetFromLocation = rely.getBoolean(Key.DISABLE_GET_FROM_LOCATION, FakeLoc.disableGetFromLocation)
                val loopBroadcastLocation = rely.getBoolean(Key.LOOP_BROADCAST_LOCATION, FakeLoc.loopBroadcastLocation)
                // 开关：读不到键时保持当前值（= 模块默认，现为开；旧版 App 不下发该键也不改变结论）
                val binderSensorMock = rely.getBoolean(Key.BINDER_SENSOR_MOCK, FakeLoc.enableBinderSensorMock)
                // 注入栅格分辨率（Hz，0=自动）：读不到键时保持当前值（旧版 App 不下发）
                val sensorGridHz = rely.getInt(Key.SENSOR_GRID_HZ, FakeLoc.sensorGridHz)
                // 步频倍率（微调步频↔速度）：读不到键时保持当前值
                val cadenceScale = rely.numberOr("cadence_scale", FakeLoc.cadenceScale)
                // 注入噪声档（Calibration 页）：读不到键时保持当前值（旧版 App 不下发）
                val noiseProfile = rely.getFloatArray(Key.NOISE_PROFILE)?.let { SensorNoise.sanitize(it) }

                FakeLoc.enable = enable
                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy
                FakeLoc.enableDebugLog = enableDebugLog
                FakeLoc.fusedMode = fusedMode
                // 调试模式打开时把融合 hook 状态打一条（用户点名要的那条日志）
                FusedStatus.logIfDebug()
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
                if (BinderSensorMock.isNativeReady) {
                    pushToNative("栅格设置下发") { BinderSensorNative.setGridHz(sensorGridHz) }
                }
                if (noiseProfile != null) {
                    FakeLoc.noiseProfile = noiseProfile
                    if (BinderSensorMock.isNativeReady) {
                        pushToNative("噪声档下发") {
                            FakeLoc.applyNoiseProfile { index, amp ->
                                BinderSensorNative.setNoise(index, amp)
                            }
                        }
                    }
                }
                // 原生层挂不上就明确回报失败：App 侧据此提示"配置失败"，
                // 而不是让用户以为开关生效了、实际什么都没发生。
                if (!BinderSensorMock.onConfigChanged()) {
                    Logger.error("Binder 外周传感器模拟：原生注入层不可用（详见 logcat PortalSensor）")
                    return false
                }
                return true
            }
            Cmd.SYNC_CONFIG -> {
                rely.putBoolean(Key.ENABLE, FakeLoc.enable)
                rely.putDouble(Key.LATITUDE, FakeLoc.latitude)
                rely.putDouble(Key.LONGITUDE, FakeLoc.longitude)
                rely.putDouble(Key.ALTITUDE, FakeLoc.altitude)
                rely.putDouble(Key.SPEED, FakeLoc.speed)
                rely.putDouble(Key.SPEED_AMPLITUDE, FakeLoc.speedAmplitude)
                rely.putBoolean(Key.HAS_BEARINGS, FakeLoc.hasBearings)
                rely.putDouble(Key.BEARING, FakeLoc.bearing)
                rely.putParcelable(Key.LAST_LOCATION, FakeLoc.lastLocation)
                rely.putBoolean(Key.ENABLE_LOG, FakeLoc.enableLog)
                rely.putBoolean(Key.ENABLE_DEBUG_LOG, FakeLoc.enableDebugLog)
                rely.putBoolean(Key.DISABLE_FUSED_LOCATION, FakeLoc.rejectFused)   // 兼容旧口径
                rely.putInt(Key.FUSED_MODE, FakeLoc.fusedMode)
                rely.putBoolean(Key.ENABLE_AGPS, FakeLoc.enableAGPS)
                rely.putBoolean(Key.ENABLE_NMEA, FakeLoc.enableNMEA)
                rely.putBoolean(Key.HIDE_MOCK, FakeLoc.hideMock)
                rely.putBoolean(Key.HOOK_WIFI, FakeLoc.hookWifi)
                rely.putBoolean(Key.NEED_DOWNGRADE_TO_2G, FakeLoc.needDowngradeToCdma)
                rely.putBoolean(Key.LOOP_BROADCAST_LOCATION, FakeLoc.loopBroadcastLocation)
                rely.putBoolean(Key.BINDER_SENSOR_MOCK, FakeLoc.enableBinderSensorMock)
                return true
            }
            Cmd.BROADCAST_LOCATION -> {
                // force：显式广播就是「现在推一帧」——反定位拉回线程靠反复强制推送压制真实位置
                LocationServiceHook.callOnLocationChanged(force = true)
                return true
            }
            else -> {
                // 未知命令：App 比模块新（版本不匹配）或有人在探测 —— 记一笔，别静默
                PortalDiag.fail(PortalDiag.Area.COMMAND_REJECT)
                return false
            }
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

    /**
     * 下发注入噪声档（Calibration 页）。
     *
     * 读不到 `noise_profile` 键（旧版 App / 从未校准过）时**什么都不做** ——
     * 原生层保持内置默认，输出与从前逐位一致。数据先经 [SensorNoise.sanitize]
     * 规范化（补长/截断/钳位），所以长度不符也不会把原生档位写坏。
     */
    private fun applyNoiseProfile(rely: Bundle) {
        val raw = rely.getFloatArray(Key.NOISE_PROFILE) ?: return
        FakeLoc.noiseProfile = SensorNoise.sanitize(raw)
        if (BinderSensorMock.isNativeReady) {
            pushToNative("噪声档下发") {
                FakeLoc.applyNoiseProfile { index, amp -> BinderSensorNative.setNoise(index, amp) }
            }
        }
        Logger.info("Binder 外周传感器模拟：噪声档=${SensorNoise.encode(FakeLoc.noiseProfile)}")
    }

    /**
     * 把注入参数推给原生层 —— **只在原生层已装载时**。
     *
     * 装载现在推迟到模拟会话启动（见 [BinderSensorMock]），因此 App 启动时的 `put_config`
     * 通常早于装载：那一刻推只会得到 `UnsatisfiedLinkError`（既是噪声、又会误导排查"是不是库没加载"）。
     * 值已经存进 [FakeLoc]，装载时由 [BinderSensorMock] 的 `applyStoredConfig()` 一次性重放。
     */
    private inline fun pushToNative(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Logger.warn("$what 失败：${it.message}") }
    }
}

/**
 * 读取 Bundle 中的数值并兼容 Int/Long/Float/Double。
 * Bundle.getDouble 在键值是 Float 时会**静默返回默认值**（类型不符），
 * App 侧 putFloat 写、服务端 getDouble 读过一次就是恒 0——这里按 Number 统一取。
 */
private fun Bundle.numberOr(key: String, default: Double): Double =
    (get(key) as? Number)?.toDouble() ?: default
