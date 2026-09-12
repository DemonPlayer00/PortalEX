package moe.fuqiuluo.portalex.ext

import android.content.Context
import androidx.core.content.edit
import com.baidu.mapapi.map.BaiduMap
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.ui.mock.HistoricalLocation
import moe.fuqiuluo.portalex.ui.mock.HistoricalRoute
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.PortalProtocol
import moe.fuqiuluo.xposed.utils.SensorNoise

val Context.sharedPrefs
    get() = getSharedPreferences(PortalProtocol.PREFS_NAME, Context.MODE_PRIVATE)!!

var Context.selectLocation: HistoricalLocation?
    get() {
        return sharedPrefs.getString("selectedLocation", null)?.let {
            try {
                HistoricalLocation.fromString(it)
            } catch (e: Exception) {
                // 历史/损坏数据容错：清掉脏值，避免下次读取再次崩溃
                sharedPrefs.edit {
                    putString("selectedLocation", "")
                }
                null
            }
        }
    }
    set(value) = sharedPrefs.edit {
        putString("selectedLocation", value?.toString())
    }

var Context.selectRoute: HistoricalRoute?
    get() {
        return sharedPrefs.getString("selectedRoute", null)?.let {
            try {
                HistoricalRoute.parse(it)
            } catch (e: Exception) {
                sharedPrefs.edit {
                    putString("selectedRoute", "")
                }
                null
            }
        }
    }
    set(value) = sharedPrefs.edit {
        putString("selectedRoute", value?.let { HistoricalRoute.toJson(it) })
    }

val Context.historicalLocations: List<HistoricalLocation>
    get() {
        return sharedPrefs.getStringSet("locations", emptySet())?.map {
            HistoricalLocation.fromString(it)
        } ?: emptyList()
    }

var Context.rawHistoricalLocations: Set<String>
    get() {
        return sharedPrefs.getStringSet("locations", emptySet()) ?: emptySet()
    }
    set(value) {
        sharedPrefs.edit {
            putStringSet("locations", value)
        }
    }

var Context.jsonHistoricalRoutes: String
    get() {
        return sharedPrefs.getString("routes", null) ?: ""
    }
    set(value) {
        sharedPrefs.edit {
            putString("routes", value)
        }
    }

var Context.reportDuration: Int
    get() = sharedPrefs.getInt("reportDuration", 100)
    set(value) = sharedPrefs.edit {
        putInt("reportDuration", value)
    }

var Context.minSatelliteCount: Int
    get() = sharedPrefs.getInt("minSatelliteCount", 12)
    set(value) = sharedPrefs.edit {
        putInt("minSatelliteCount", value)
    }

var Context.mapType: Int
    get() = sharedPrefs.getInt("mapType", BaiduMap.MAP_TYPE_NORMAL)
    set(value) = sharedPrefs.edit {
        putInt("mapType", value)
    }

var Context.rockerCoords: Pair<Int, Int>
    get() {
        val x = sharedPrefs.getInt("rocker_x", 0)
        val y = sharedPrefs.getInt("rocker_y", 0)
        return Pair(x, y)
    }
    set(value) = sharedPrefs.edit {
        putInt("rocker_x", value.first)
        putInt("rocker_y", value.second)
    }

var Context.speed: Double
    get() = sharedPrefs.getFloat("speed", FakeLoc.speed.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("speed", value.toFloat())
    }

var Context.altitude: Double
    // 默认值取配置高度本体：offset_altitude 是每帧重新掷骰的注入抖动，不该当默认值
    get() = sharedPrefs.getFloat("altitude", FakeLoc.altitude.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("altitude", value.toFloat())
    }

var Context.accuracy: Float
    get() = sharedPrefs.getFloat("accuracy", FakeLoc.accuracy)
    set(value) = sharedPrefs.edit {
        putFloat("accuracy", value)
    }

var Context.needOpenSELinux: Boolean
    get() = sharedPrefs.getBoolean("needOpenSELinux", false)
    set(value) = sharedPrefs.edit {
        putBoolean("needOpenSELinux", value)
    }

var Context.needDowngradeToCdma: Boolean
    get() = sharedPrefs.getBoolean("needDowngradeToCdma", FakeLoc.needDowngradeToCdma)
    set(value) = sharedPrefs.edit {
        putBoolean("needDowngradeToCdma", value)
    }

//var Context.updateInterval: Long
//    get() = sharedPrefs.getLong("updateInterval", FakeLoc.updateInterval)
//
//    set(value) = sharedPrefs.edit {
//        putLong("updateInterval", value)
//    }
//
//var Context.hideMock: Boolean
//    get() = sharedPrefs.getBoolean("hideMock", FakeLoc.hideMock)
//
//    set(value) = sharedPrefs.edit {
//        putBoolean("hideMock", value)
//    }

var Context.debug: Boolean
    get() = sharedPrefs.getBoolean("debug", FakeLoc.enableDebugLog)
    set(value) = sharedPrefs.edit {
        putBoolean("debug", value)
    }

var Context.disableFusedProvider: Boolean
    get() = sharedPrefs.getBoolean("disableFusedProvider", FakeLoc.disableFusedLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("disableFusedProvider", value)
        FakeLoc.disableFusedLocation = value
    }

/**
 * 是否允许地理围栏请求
 */
var Context.enableRequestGeofence: Boolean
    get() = sharedPrefs.getBoolean("enableRequestGeofence", !FakeLoc.disableRequestGeofence)
    set(value) = sharedPrefs.edit {
        putBoolean("enableRequestGeofence", value)
        FakeLoc.disableRequestGeofence = !value
    }

/**
 * 是否允许位置获取
 */
var Context.enableGetFromLocation: Boolean
    get() = sharedPrefs.getBoolean("enableGetFromLocation", !FakeLoc.disableGetFromLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("enableGetFromLocation", value)
        FakeLoc.disableGetFromLocation = !value
    }

/**
 * 是否允许AGPS模块
 */
var Context.enableAGPS: Boolean
    get() = sharedPrefs.getBoolean("enableAGPS", FakeLoc.enableAGPS)
    set(value) = sharedPrefs.edit {
        putBoolean("enableAGPS", value)
        FakeLoc.enableAGPS = value
    }

/**
 * 是否允许NMEA模块
 */
var Context.enableNMEA: Boolean
    get() = sharedPrefs.getBoolean("enableNMEA", FakeLoc.enableNMEA)
    set(value) = sharedPrefs.edit {
        putBoolean("enableNMEA", value)
        FakeLoc.enableNMEA = value
    }

var Context.disableWifiScan: Boolean
    // 默认 false：旧实现默认取 FakeLoc.enableNMEA（复制粘贴的错键），开关初始状态会错乱
    get() = sharedPrefs.getBoolean("disableWifiScan", false)
    set(value) = sharedPrefs.edit {
        putBoolean("disableWifiScan", value)
        FakeLoc.enableMockWifi = value
    }

var Context.loopBroadcastlocation: Boolean
    get() = sharedPrefs.getBoolean("loopBroadcastLocation", FakeLoc.loopBroadcastLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("loopBroadcastLocation", value)
        FakeLoc.loopBroadcastLocation = value
    }

/**
 * 步频倍率（微调"步频 ↔ 速度"关系）。默认 1.0；可填整数或小数。
 * 1.0 = 逐位保持原公式；>1 = 同速度下步频更高，<1 = 更低。钳在 0.2~3.0 防手滑。
 */
var Context.cadenceScale: Float
    get() = sharedPrefs.getFloat("cadenceScale", 1.0f)
    set(value) = sharedPrefs.edit(commit = true) {
        putFloat("cadenceScale", if (value <= 0f) 1.0f else value.coerceIn(0.2f, 3.0f))
    }

/**
 * 注入栅格分辨率（Hz）。**0 = 自动**（跟随框架采用值，上限 400Hz）。
 *
 * 语义：栅格只是"能表现出来的最快速率"——调细**不会**改变任何传感器自己的采用速率
 * （每个通道仍按框架仲裁后的周期发数据），只是让更快的档位能落地；调粗则会把它压慢。
 * 钳在 20~400Hz。（原生层还会再钳一次 2.5~50ms，防手滑。）
 */
var Context.sensorGridHz: Int
    get() = sharedPrefs.getInt("sensorGridHz", 0)
    set(value) = sharedPrefs.edit(commit = true) {
        putInt("sensorGridHz", if (value <= 0) 0 else value.coerceIn(20, 400))
    }

/**
 * 注入噪声档（Calibration 页）：[SensorNoise.COUNT] 个槽（逐轴 σ + 陀螺零偏），见 SensorNoise。
 *
 * 存成 "a,b,c,…" 串（而不是 putFloatArray）：读取路径要能容错——长度不符/损坏/
 * 旧版本留下的脏值都会经 [SensorNoise.sanitize] 归一，绝不把非法值送进原生层。
 * 默认 = SensorNoise.DEFAULTS（σ 与旧硬编码口径的方差一致；陀螺零偏默认 0）。
 */
var Context.sensorNoise: FloatArray
    get() = SensorNoise.decode(sharedPrefs.getString("sensorNoise", null))
    // commit=true：校准结果是"量出来的"，丢了就得重量一遍（apply 的异步落盘在
    // 进程被强杀/重启时可能还没写完）。写入量极小，同步落盘可接受。
    set(value) = sharedPrefs.edit(commit = true) {
        putString("sensorNoise", SensorNoise.encode(SensorNoise.sanitize(value)))
    }

/**
 * 最近一次一键校准的统计明细（逐轴中位数 + σ）。
 *
 * 中位数在加速度/重力/线性加速度/磁场上**不注入**（含姿态与环境直流），但它是校准的原始
 * 依据——留一份在这里，重开页面仍能看到上次到底量到了什么。
 */
var Context.sensorNoiseReport: String
    get() = sharedPrefs.getString("sensorNoiseReport", "") ?: ""
    set(value) = sharedPrefs.edit(commit = true) {
        putString("sensorNoiseReport", value)
    }

/**
 * Binder 外周传感器模拟。**默认关闭**：
 * 打开后由 system_server 侧原生 hook 在系统框架层接管外周传感器
 * （步频 / 加速度 / 角度 / 指南针），**不 hook 目标应用**；
 * 关闭时这条路径完全不装载（不加载 .so、不起线程），即旧行为逐位不变。
 *
 * 注意默认值只在**偏好里还没有这个键**时生效：用户手动关掉过，就以存下来的值为准。
 */
var Context.binderSensorMock: Boolean
    get() = sharedPrefs.getBoolean(PortalProtocol.Pref.BINDER_SENSOR_MOCK, false)
    set(value) = sharedPrefs.edit {
        putBoolean(PortalProtocol.Pref.BINDER_SENSOR_MOCK, value)
    }

/**
 * 是否允许横屏。默认关闭（锁竖屏）——横屏下部分界面尚未完全适配，
 * 需要横屏的用户可以在设置页手动打开。
 */
var Context.allowLandscape: Boolean
    get() = sharedPrefs.getBoolean("allowLandscape", false)
    set(value) = sharedPrefs.edit {
        putBoolean("allowLandscape", value)
    }



