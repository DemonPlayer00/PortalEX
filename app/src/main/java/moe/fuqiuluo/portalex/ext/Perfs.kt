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

/** root shell 路径；部分设备把 su 移出 PATH，需要可配置。 */
var Context.suPath: String
    get() = sharedPrefs.getString("suPath", "su")?.takeIf { it.isNotBlank() } ?: "su"
    set(value) = sharedPrefs.edit {
        putString("suPath", value.trim().ifBlank { "su" })
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


/**
 * **应用地图更新间隔**（毫秒）：本进程地图页向系统订阅位置的频率。
 *
 * - 0 表示「跟随最大位置上报间隔」（reportDuration）；
 * - 若设置得比最大上报间隔更小，系统侧会把实际出帧钳到最大上报间隔 —— 设置页会给出警告。
 */
var Context.mapUpdateInterval: Int
    get() = sharedPrefs.getInt("mapUpdateInterval", 0)
    set(value) = sharedPrefs.edit {
        putInt("mapUpdateInterval", value)
    }

/**
 * **多条目**：基站 "mcc/mnc|cid/tac"、Wi-Fi "ssid|bssid"，每行一条。
 * 空 = 只用单条字段（[reportDuration] 那一套旧输入框）；非空时列表优先，首条为"当前连接"。
 */
var Context.cellEntries: List<String>
    get() = sharedPrefs.getString("sim_cell_list", "").orEmpty()
        .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    set(value) = sharedPrefs.edit {
        putString("sim_cell_list", value.joinToString("\n"))
    }

var Context.wifiEntries: List<String>
    get() = sharedPrefs.getString("sim_wifi_list", "").orEmpty()
        .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    set(value) = sharedPrefs.edit {
        putString("sim_wifi_list", value.joinToString("\n"))
    }

var Context.minSatelliteCount: Int
    get() = sharedPrefs.getInt("minSatelliteCount", 12)
    set(value) = sharedPrefs.edit {
        putInt("minSatelliteCount", value)
    }

/** 卫星数波动（标准差，颗）。Calibration 采集后写入；0 = 固定为 minSatelliteCount。 */
var Context.satelliteJitter: Float
    get() = sharedPrefs.getFloat("satelliteJitter", 0f)
    set(value) = sharedPrefs.edit {
        putFloat("satelliteJitter", value)
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

/** 最近一次 GPS 精度采集结果（米）；0 = 未采集。 */
var Context.gpsAccuracyMeasured: Float
    get() = sharedPrefs.getFloat("gpsAccuracyMeasured", 0f)
    set(value) = sharedPrefs.edit { putFloat("gpsAccuracyMeasured", value) }

/** 最近一次 GPS-fused 水平差（米）；0 = 未采集。 */
var Context.fusedAccuracyMeasured: Float
    get() = sharedPrefs.getFloat("fusedAccuracyMeasured", 0f)
    set(value) = sharedPrefs.edit { putFloat("fusedAccuracyMeasured", value) }

var Context.fusedDelayMeasured: Float
    get() = sharedPrefs.getFloat("fusedDelayMeasured", 0f)
    set(value) = sharedPrefs.edit { putFloat("fusedDelayMeasured", value) }

var Context.gpsIntervalMeasured: Float
    get() = sharedPrefs.getFloat("gpsIntervalMeasured", 0f)
    set(value) = sharedPrefs.edit { putFloat("gpsIntervalMeasured", value) }

var Context.satellitesMeasured: Float
    get() = sharedPrefs.getFloat("satellitesMeasured", 0f)
    set(value) = sharedPrefs.edit { putFloat("satellitesMeasured", value) }

var Context.maxCn0Measured: Float
    get() = sharedPrefs.getFloat("maxCn0Measured", 0f)
    set(value) = sharedPrefs.edit { putFloat("maxCn0Measured", value) }

var Context.meanCn0Measured: Float
    get() = sharedPrefs.getFloat("meanCn0Measured", 0f)
    set(value) = sharedPrefs.edit { putFloat("meanCn0Measured", value) }

/** 海拔波动幅度（米）：同时作为垂直精度来源。 */
var Context.altitudeJitter: Double
    get() = sharedPrefs.getFloat("altitudeJitter", FakeLoc.altitudeJitter.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("altitudeJitter", value.toFloat())
    }

/** 速度随机波动幅度（m/s）。 */
var Context.speedAmplitude: Double
    get() = sharedPrefs.getFloat("speedAmplitude", FakeLoc.speedAmplitude.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("speedAmplitude", value.toFloat())
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

/**
 * 「逐帧记录」（[moe.fuqiuluo.portalex.service.FrameRecorder]）开关：把客户端收到的每一帧
 * 落盘，供离线分析配速曲线。
 *
 * **刻意与 [debug] 分开**：`debug` 会同时打开模块侧的逐帧日志，那是 ~800 行/秒的
 * system_server 日志洪水 —— 做交付/时延类测量时它就是**扰动源**，不能和"只想安静录帧"
 * 共用一个开关。默认关闭，不改变任何既有行为。
 */
var Context.frameRecord: Boolean
    get() = sharedPrefs.getBoolean("frame_record", false)
    set(value) = sharedPrefs.edit {
        putBoolean("frame_record", value)
    }

/**
 * 融合定位处置（三态互斥，见 [moe.fuqiuluo.xposed.utils.FusedMode]）：
 * **默认伪装** —— 在检测到融合定位的设备上，让融合照常跑、但把它交给应用的结果改写成
 * 模拟位置；"拒绝"是把系统能力报成不可用，"放行"则不做任何干预（不推荐）。
 */
var Context.fusedMode: Int
    get() = sharedPrefs.getInt("fusedMode", moe.fuqiuluo.xposed.utils.FusedMode.DEFAULT)
    set(value) = sharedPrefs.edit {
        val mode = moe.fuqiuluo.xposed.utils.FusedMode.sanitize(value)
        putInt("fusedMode", mode)
        FakeLoc.fusedMode = mode
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


var Context.cadenceScale: Float
    get() = sharedPrefs.getFloat("cadenceScale", 1.0f)
    set(value) = sharedPrefs.edit(commit = true) {
        putFloat("cadenceScale", if (value <= 0f) 1.0f else value.coerceIn(0.2f, 3.0f))
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
 * 按组波动（两个功能页各两条，单位 **%**，默认 15）。
 *
 * 与噪声档是两层：噪声档（Calibration 页）是"每条事件的传感器本底噪声"，
 * 这里是叠加在它之上的慢漂（波动强度）与逐条随机（随机区间）。施加口径见
 * `xposed/src/main/cpp/vw_wobble.c` 文件头：按**该类型的参考量**加绝对偏差，
 * 步频侧作用在**步间隔**上。
 *
 * 0 = 逐位兼容（原生层不碰随机数、不做算术 ⇒ 输出与没有这个功能时完全一致）。
 */
private fun Context.wobblePref(key: String, value: Float) =
    sharedPrefs.edit(commit = true) { putFloat(key, value.coerceIn(0f, 100f)) }

/** 步频侧·波动强度（%，默认 15）：慢漂半幅 */
var Context.cadenceWobbleAmp: Float
    get() = sharedPrefs.getFloat("cadenceWobAmp", 15f).coerceIn(0f, 100f)
    set(value) = wobblePref("cadenceWobAmp", value)

/** 步频侧·随机区间（%，默认 15）：逐条事件均匀随机半宽 */
var Context.cadenceWobbleRnd: Float
    get() = sharedPrefs.getFloat("cadenceWobRnd", 15f).coerceIn(0f, 100f)
    set(value) = wobblePref("cadenceWobRnd", value)

/** 角度与指南针侧·波动强度（%，默认 15） */
var Context.orientationWobbleAmp: Float
    get() = sharedPrefs.getFloat("orientationWobAmp", 15f).coerceIn(0f, 100f)
    set(value) = wobblePref("orientationWobAmp", value)

/** 角度与指南针侧·随机区间（%，默认 15） */
var Context.orientationWobbleRnd: Float
    get() = sharedPrefs.getFloat("orientationWobRnd", 15f).coerceIn(0f, 100f)
    set(value) = wobblePref("orientationWobRnd", value)

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
 * **无人值守推进时**保持后台活跃（默认开）：
 * 起一个自己的前台服务 + partial wake lock，把 `:app` 进程钉在 FOREGROUND_SERVICE 档。
 *
 * 触发条件**不是"会话开着"**，而是"没有人操作但位置还在走"：自动播放、摇杆锁定后继续走。
 * 空闲（会话开着但没动）、手指按着摇杆、关掉本项时都不占前台、不持锁 —— 遵循系统省电策略。
 *
 * 为什么"无人值守"仍需要它（迁移后理由已经变了）：**推进不再需要 App 活着**
 * （世界在 system_server，App 被冻也照样走），但"播完要放提示音/振动、把悬停按钮状态收回来"
 * 需要一个活着的观察者 —— 而 App 退后台就是 cached 进程，会被 Cached Apps Freezer 冻结。
 * 顺带它也让 App 的**位置订阅**（普通客户端视角，地图蓝点）继续收帧。
 */
var Context.keepAliveInBackground: Boolean
    get() = sharedPrefs.getBoolean("keepAliveInBackground", true)
    set(value) = sharedPrefs.edit {
        putBoolean("keepAliveInBackground", value)
    }

/**
 * Binder 外周传感器模拟。**默认开启**：
 * 打开后由 system_server 侧原生 hook 在系统框架层接管外周传感器
 * （步频 / 加速度 / 角度 / 指南针），**不 hook 目标应用**；
 * 关闭时这条路径完全不装载（不加载 .so、不起线程），即旧行为逐位不变。
 *
 * 注意默认值只在**偏好里还没有这个键**时生效：用户手动关掉过，就以存下来的值为准。
 */
var Context.cadenceMock: Boolean
    get() = sharedPrefs.getBoolean(PortalProtocol.Pref.CADENCE_MOCK, true)
    set(value) = sharedPrefs.edit {
        putBoolean(PortalProtocol.Pref.CADENCE_MOCK, value)
    }

/**
 * GNSS 模拟的**用户意图**（二类功能允许伪启动）。
 *
 * 开关状态以这里为准：模块此刻能不能真接管（路线模拟没开、注入层未就绪）由系统侧
 * FeatureGate 决定，不该反过来把用户刚打开的开关弹回去。
 */
var Context.gnssMockIntent: Boolean
    get() = sharedPrefs.getBoolean("gnss_mock_intent", false)
    set(value) = sharedPrefs.edit { putBoolean("gnss_mock_intent", value) }

var Context.orientationMock: Boolean
    get() = sharedPrefs.getBoolean(PortalProtocol.Pref.ORIENTATION_MOCK, true)
    set(value) = sharedPrefs.edit {
        putBoolean(PortalProtocol.Pref.ORIENTATION_MOCK, value)
    }

/** 屏幕方向模式：auto / portrait / landscape。 */
var Context.orientationMode: String
    get() = sharedPrefs.getString(
        "orientationMode",
        if (sharedPrefs.getBoolean("allowLandscape", false)) ORIENTATION_AUTO else ORIENTATION_PORTRAIT
    ) ?: ORIENTATION_PORTRAIT
    set(value) = sharedPrefs.edit {
        putString("orientationMode", value)
        putBoolean("allowLandscape", value != ORIENTATION_PORTRAIT)
    }

const val ORIENTATION_AUTO = "auto"
const val ORIENTATION_PORTRAIT = "portrait"
const val ORIENTATION_LANDSCAPE = "landscape"

/** 兼容旧调用：false = 竖屏，true = 自动。 */
var Context.allowLandscape: Boolean
    get() = orientationMode != ORIENTATION_PORTRAIT
    set(value) {
        orientationMode = if (value) ORIENTATION_AUTO else ORIENTATION_PORTRAIT
    }





