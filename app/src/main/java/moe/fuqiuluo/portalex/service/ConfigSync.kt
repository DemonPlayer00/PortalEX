package moe.fuqiuluo.portalex.service

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.os.Bundle as AndroidBundle
import android.util.Log
import moe.fuqiuluo.portalex.ext.altitude
import moe.fuqiuluo.portalex.ext.binderSensorMock
import moe.fuqiuluo.portalex.ext.cadenceScale
import moe.fuqiuluo.portalex.ext.debug
import moe.fuqiuluo.portalex.ext.disableFusedProvider
import moe.fuqiuluo.portalex.ext.enableAGPS
import moe.fuqiuluo.portalex.ext.enableGetFromLocation
import moe.fuqiuluo.portalex.ext.enableNMEA
import moe.fuqiuluo.portalex.ext.enableRequestGeofence
import moe.fuqiuluo.portalex.ext.loopBroadcastlocation
import moe.fuqiuluo.portalex.ext.minSatelliteCount
import moe.fuqiuluo.portalex.ext.needDowngradeToCdma
import moe.fuqiuluo.portalex.ext.sensorGridHz
import moe.fuqiuluo.portalex.ext.sensorNoise
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.PortalProtocol.Cmd
import moe.fuqiuluo.xposed.utils.PortalProtocol.Key

/**
 * **配置下发的唯一出口**（App 侧偏好 → 系统侧）。
 *
 * 为什么收口：以前"下发"散在四处（设置页、GNSS 页、校准页、启动握手），每处各自
 * 决定字段、各自解释失败 ⇒ 三个后果：
 *  1. **同一份状态三个写者**（设置项 setter、putConfig、initRocker 各写一遍 FakeLoc 镜像），
 *     谁覆盖谁看时序，出现"改了不生效"只能靠猜；
 *  2. 失败只返回一个 `Boolean`，"没握手"和"系统侧明确拒绝"混在一起 ⇒ UI 只能说
 *     "同步配置成功"，而实际什么都没发生（假成功）；
 *  3. 新增一个设置项时容易只改一处（漏下发），且没人能一眼看出"哪些字段需要下发"。
 *
 * 现在：**要下发就调 [push] / [setSensorMock]；要写本进程镜像就调 [mirrorLocal]**，
 * 字段清单在 [push] 里集中可见，结果用 [Result] 三态表达。
 */
object ConfigSync {

    /**
     * 下发结果。刻意不用 `Boolean`：三种情况调用方要给三种不同提示，
     * 尤其是 [REJECTED] —— 那是"系统侧收到了但明确拒绝"（原生注入层不可用），
     * 说明开关/配置**没有生效**，绝不能说"成功"。
     */
    enum class Result {
        OK,
        /** 还没握手（服务没起来 / 没有 LocationManager）：什么都没发出去 */
        NO_SERVICE,
        /** 发出去了但系统侧拒绝：原生注入层不可用（详见 logcat PortalSensor） */
        REJECTED;

        val isOk: Boolean get() = this == OK

        /** 给用户看的文案（调用方不要再自己拼字符串，免得各处口径不一） */
        fun message(context: Context): String = when (this) {
            OK -> "同步配置成功"
            NO_SERVICE -> "定位服务加载异常，配置未同步"
            REJECTED -> "系统侧拒绝：原生注入层不可用（配置未生效）"
        }
    }

    /**
     * 只写**本进程镜像**（不下发）。
     *
     * 摇杆/运动循环读的是本进程这份 `FakeLoc`，所以设置项改完要立刻镜像一下；
     * 而"下发"是另一件事（[push]）。把这两件事分开写，是为了不再出现"以为下发了、
     * 其实只改了本地镜像"这种假象。
     */
    fun mirrorLocal(context: Context) {
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
        FakeLoc.enableBinderSensorMock = context.binderSensorMock
        FakeLoc.sensorGridHz = context.sensorGridHz
        FakeLoc.cadenceScale = context.cadenceScale.toDouble()
        FakeLoc.noiseProfile = context.sensorNoise
    }

    /**
     * 唯一出口：把当前偏好整份下发（`put_config`，幂等）。
     *
     * 刻意**不携带 enable**：模拟启停只由 start/stop 命令控制 —— 旧实现 App 侧 enable 恒 false，
     * 打开设置页/GNSS 页触发的 put_config 会把系统侧正在跑的模拟静默关掉。
     */
    fun push(context: Context, locationManager: LocationManager?): Result {
        if (locationManager == null) return Result.NO_SERVICE
        mirrorLocal(context)

        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.PUT_CONFIG)
        rely.putDouble(Key.ALTITUDE, FakeLoc.altitude)
        rely.putDouble(Key.SPEED, FakeLoc.speed)
        rely.putBoolean(Key.ENABLE_DEBUG_LOG, FakeLoc.enableDebugLog)
        rely.putBoolean(Key.DISABLE_FUSED_LOCATION, FakeLoc.disableFusedLocation)
        rely.putBoolean(Key.NEED_DOWNGRADE_TO_2G, FakeLoc.needDowngradeToCdma)
        rely.putInt(Key.MIN_SATELLITES, FakeLoc.minSatellites)
        rely.putBoolean(Key.LOOP_BROADCAST_LOCATION, context.loopBroadcastlocation)
        rely.putBoolean(Key.ENABLE_AGPS, FakeLoc.enableAGPS)
        rely.putBoolean(Key.ENABLE_NMEA, FakeLoc.enableNMEA)
        rely.putBoolean(Key.DISABLE_REQUEST_GEOFENCE, FakeLoc.disableRequestGeofence)
        rely.putBoolean(Key.DISABLE_GET_FROM_LOCATION, FakeLoc.disableGetFromLocation)
        rely.putBoolean(Key.BINDER_SENSOR_MOCK, FakeLoc.enableBinderSensorMock)
        rely.putInt(Key.SENSOR_GRID_HZ, FakeLoc.sensorGridHz)
        rely.putFloat(Key.CADENCE_SCALE, FakeLoc.cadenceScale.toFloat())
        // 注入噪声档：读不到键（旧版 App）时系统侧保持当前值，行为逐位不变
        rely.putFloatArray(Key.NOISE_PROFILE, FakeLoc.noiseProfile)

        return resultOf(MockServiceHelper.send(locationManager, rely))
    }

    /**
     * 传感器模拟开关（`set_sensor_mock`）：系统侧据此装载/卸载原生注入层。
     *
     * 这条命令同时是**启动时唯一会走到的传感器侧配置载体** ⇒ 噪声档与注入栅格随它一起恢复，
     * 否则系统进程重启后校准结果就丢了（退回内置默认）。
     */
    fun setSensorMock(context: Context, locationManager: LocationManager?, enabled: Boolean): Result {
        if (locationManager == null) return Result.NO_SERVICE
        val rely = Bundle()
        rely.putString(Key.COMMAND_ID, Cmd.SET_SENSOR_MOCK)
        rely.putBoolean(Key.BINDER_SENSOR_MOCK, enabled)
        runCatching {
            FakeLoc.noiseProfile = context.sensorNoise
            FakeLoc.sensorGridHz = context.sensorGridHz
            rely.putFloatArray(Key.NOISE_PROFILE, FakeLoc.noiseProfile)
            rely.putInt(Key.SENSOR_GRID_HZ, FakeLoc.sensorGridHz)
        }.onFailure { Log.w(TAG, "传感器侧配置恢复失败：${it.message}") }
        return resultOf(MockServiceHelper.send(locationManager, rely))
    }

    /**
     * 握手完成后的恢复（App 启动路径）：把当前偏好整份下发一次，并同步传感器开关。
     * 关着开关时也要发 —— 系统侧进程重启后不该残留"开着"的状态。
     */
    fun restoreAfterHandshake(context: Context, locationManager: LocationManager?): Result {
        val sensor = setSensorMock(context, locationManager, context.binderSensorMock)
        val config = push(context, locationManager)
        // 两者任一失败都要如实上报；传感器开关的失败更严重（决定注入层装不装）
        return if (sensor != Result.OK) sensor else config
    }

    private fun resultOf(sent: Boolean): Result = when {
        sent -> Result.OK
        !MockServiceHelper.isServiceInit() -> Result.NO_SERVICE
        else -> Result.REJECTED
    }

    private const val TAG = "ConfigSync"
}
