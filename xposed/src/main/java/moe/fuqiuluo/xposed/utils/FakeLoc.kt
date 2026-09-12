package moe.fuqiuluo.xposed.utils

import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object FakeLoc {
    /** 可见卫星数上限（GnssStatus 推送与 Location.extras 卫星字段**共用同一上限**：
     *  两处各自随机且上限不同时，同一时刻雷达显示与 extras 会互相矛盾，构成交叉检测面）。 */
    const val MAX_SATELLITES = 35

    // ===================== 配置门面（状态全在 [LocConfig]）=====================
    // 对外名字不变（调用点仍写 `FakeLoc.xxx`），实际状态与语义文档见 [LocConfig]。
    // 这里**只做转发**：别在门面上加校验/夹取，否则「设置下发」与「直接写字段」会走出两套语义。

    var enableLog: Boolean
        get() = LocConfig.enableLog
        set(value) { LocConfig.enableLog = value }
    var enableDebugLog: Boolean
        get() = LocConfig.enableDebugLog
        set(value) { LocConfig.enableDebugLog = value }
    var enable: Boolean
        get() = LocConfig.enable
        set(value) { LocConfig.enable = value }
    var enableMockGnss: Boolean
        get() = LocConfig.enableMockGnss
        set(value) { LocConfig.enableMockGnss = value }
    var enableMockWifi: Boolean
        get() = LocConfig.enableMockWifi
        set(value) { LocConfig.enableMockWifi = value }
    var enableBinderSensorMock: Boolean
        get() = LocConfig.enableBinderSensorMock
        set(value) { LocConfig.enableBinderSensorMock = value }
    var sensorGridHz: Int
        get() = LocConfig.sensorGridHz
        set(value) { LocConfig.sensorGridHz = value }
    var noiseProfile: FloatArray
        get() = LocConfig.noiseProfile
        set(value) { LocConfig.noiseProfile = value }
    var binderSensorNativeReady: Boolean
        get() = LocConfig.binderSensorNativeReady
        set(value) { LocConfig.binderSensorNativeReady = value }
    /** 融合定位处置三态（见 [FusedMode]） */
    /** 融合定位处置三态（见 [FusedMode]）；拒绝/放行的派生读法见下方 rejectFused / allowFusedResult */
    var fusedMode: Int
        get() = LocConfig.fusedMode
        set(value) { LocConfig.fusedMode = value }
    var disableNetworkLocation: Boolean
        get() = LocConfig.disableNetworkLocation
        set(value) { LocConfig.disableNetworkLocation = value }
    var disableRequestGeofence: Boolean
        get() = LocConfig.disableRequestGeofence
        set(value) { LocConfig.disableRequestGeofence = value }
    var disableGetFromLocation: Boolean
        get() = LocConfig.disableGetFromLocation
        set(value) { LocConfig.disableGetFromLocation = value }
    var enableAGPS: Boolean
        get() = LocConfig.enableAGPS
        set(value) { LocConfig.enableAGPS = value }
    var enableNMEA: Boolean
        get() = LocConfig.enableNMEA
        set(value) { LocConfig.enableNMEA = value }
    var hideMock: Boolean
        get() = LocConfig.hideMock
        set(value) { LocConfig.hideMock = value }
    var hookWifi: Boolean
        get() = LocConfig.hookWifi
        set(value) { LocConfig.hookWifi = value }
    var needDowngradeToCdma: Boolean
        get() = LocConfig.needDowngradeToCdma
        set(value) { LocConfig.needDowngradeToCdma = value }
    var isSystemServerProcess: Boolean
        get() = LocConfig.isSystemServerProcess
        set(value) { LocConfig.isSystemServerProcess = value }
    var minSatellites: Int
        get() = LocConfig.minSatellites
        set(value) { LocConfig.minSatellites = value }
    var loopBroadcastLocation: Boolean
        get() = LocConfig.loopBroadcastLocation
        set(value) { LocConfig.loopBroadcastLocation = value }
    /**
     * 是否**拒绝**融合定位（模式 = 拒绝）：报 fused 不可用 + 拦它的命令。
     * 保留这个派生读法，是为了让 hook 里的判断保持一句话可读。
     */
    val rejectFused: Boolean get() = LocConfig.fusedMode == FusedMode.REJECT

    /**
     * 是否**放行**融合结果（模式 = 放行）：融合算出来的位置原样交给应用，不改写。
     * ⚠️ 不推荐 —— 就是历史上的"位置被拉回"。见 [FusedMode.ALLOW]。
     */
    val allowFusedResult: Boolean get() = LocConfig.fusedMode == FusedMode.ALLOW

    /** 把噪声档下发给原生层（实现见 [LocConfig.applyNoiseProfile]）。 */
    fun applyNoiseProfile(native: (Int, Float) -> Unit) = LocConfig.applyNoiseProfile(native)

    var speedAmplitude: Double
        get() = LocConfig.speedAmplitude
        set(value) { LocConfig.speedAmplitude = value }
    var cadenceScale: Double
        get() = LocConfig.cadenceScale
        set(value) { LocConfig.cadenceScale = value }
    var speedFloor: Double
        get() = LocConfig.speedFloor
        set(value) { LocConfig.speedFloor = value }
    var accuracy: Float
        get() = LocConfig.accuracy
        set(value) { LocConfig.accuracy = value }

    // ===================== 世界状态门面（状态与生成器全在 [VirtualWorld]）=====================
    // 同样的约定：对外名字不变，这里只转发；状态、公式与完整文档都在 [VirtualWorld]。

    var lastLocation: Location?
        get() = VirtualWorld.lastLocation
        set(value) { VirtualWorld.lastLocation = value }
    var latitude: Double
        get() = VirtualWorld.latitude
        set(value) { VirtualWorld.latitude = value }
    var longitude: Double
        get() = VirtualWorld.longitude
        set(value) { VirtualWorld.longitude = value }
    var altitude: Double
        get() = VirtualWorld.altitude
        set(value) { VirtualWorld.altitude = value }
    var speed: Double
        get() = VirtualWorld.speed
        set(value) { VirtualWorld.speed = value }
    var hasBearings: Boolean
        get() = VirtualWorld.hasBearings
        set(value) { VirtualWorld.hasBearings = value }
    var bearing: Double
        get() = VirtualWorld.bearing
        set(value) { VirtualWorld.bearing = value }
    val offset_altitude: Double
        get() = VirtualWorld.offset_altitude
    /** 取一次"静止保底速度"：慢随机游走 + 一阶低通（实现见 [VirtualWorld.speedFloorSample]）。 */
    fun speedFloorSample(): Double = VirtualWorld.speedFloorSample()
    /** 步频-移动速度线性模型（实现见 [VirtualWorld.cadenceForSpeed]）。 */
    fun cadenceForSpeed(speed: Double): Int = VirtualWorld.cadenceForSpeed(speed)
    /** 注入朝向：平滑中轴 + 低频漂移（实现见 [VirtualWorld.processedBearing]）。 */
    fun processedBearing(): Double = VirtualWorld.processedBearing()
    /** 传感器端采样：快频段摆动 + 微抖（实现见 [VirtualWorld.sampleBearingJitter]）。 */
    fun sampleBearingJitter(): Double = VirtualWorld.sampleBearingJitter()
    /** 最近窗口内的平均速度与"是否移动"（实现见 [VirtualWorld.averageSpeedOverWindow]）。 */
    fun averageSpeedOverWindow(windowMs: Long): Pair<Double, Boolean> =
        VirtualWorld.averageSpeedOverWindow(windowMs)
    /** 记录一次基础坐标变化（实现见 [VirtualWorld.recordCoordinateChange]）。 */
    fun recordCoordinateChange(lat: Double, lon: Double) =
        VirtualWorld.recordCoordinateChange(lat, lon)
    /** 注入坐标 = 配置坐标 + 缓慢游走的偏移（实现见 [VirtualWorld.jitterLocation]）。 */
    fun jitterLocation(
        lat: Double = latitude,
        lon: Double = longitude,
        n: Double = accuracy.toDouble(),
        angle: Double = bearing
    ): Pair<Double, Double> = VirtualWorld.jitterLocation(lat, lon, n, angle)
    /** 沿 [angle] 方向走 [n] 米（实现见 [VirtualWorld.moveLocation]）。 */
    fun moveLocation(
        lat: Double = latitude,
        lon: Double = longitude,
        n: Double,
        angle: Double = bearing
    ): Pair<Double, Double> = VirtualWorld.moveLocation(lat, lon, n, angle)


    /** 两点球面距离（米）。实现在 [WorldMath]（纯函数，可在 JVM 上单测）。 */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        WorldMath.haversine(lat1, lon1, lat2, lon2)

    // ---- 系统 GNSS extras 的卫星字段改写 ----
    /**
     * 改写/补充系统 GNSS extras 的卫星字段（satellites / maxCn0 / meanCn0）。
     *
     * 真实 GNSS 引擎会把当前环境的星数与载噪比写进 Location.extras（ColorOS 实测
     * 室内：satellites=0 / maxCn0=0 / meanCn0=0）。位置被伪造到户外后，这些字段
     * 仍是真实环境的值——「人已在户外跑、头顶 0 颗卫星」自相矛盾。跑步类 App 直接
     * 读 extras.satellites 判信号强度，于是等待页只看 provider 状态（正常），一开跑
     * 就一路「信号差」、拒绝记录轨迹。
     *
     * 实现：**强制添加/改写**三键，不区分 provider。位置模拟到户外后，无论注入
     * 回调来自 GPS 还是 passive/network（系统 GPS 引擎休眠后只剩被动回调、无卫星
     * 键），注入位置都必须自带户外量级卫星字段——否则删除 GPS 辅助数据重置后引擎
     * 停摆，App 信号差问题重演。真实设备上被动位置也常携带最近一次 GPS fix 的卫星
     * extras，此形态不构成检测指纹。键名是系统/厂商 GPS 实现的标准字段；写入沿用
     * 原键类型（int/long/float/double），无原键时按 AOSP/ColorOS 标准写 int，避免
     * 读取方按原类型取值时类型不符。
     */
    fun sanitizeGnssExtras(src: Bundle?): Bundle? {
        if (!enable) return src

        val out = Bundle(src ?: Bundle())
        // 星数与载噪比取**当前卫星快照**：与 GnssStatus 推送同一份采样——
        // 两处各自随机（即便上限相同）也会让同一时刻的雷达与 extras 互相矛盾
        val snapshot = currentGnssSnapshot()
        val count = snapshot.svCount
        val maxCn0 = snapshot.maxCn0
        val meanCn0 = snapshot.meanCn0
        putSameType(out, "satellites", count, count.toDouble())
        putSameType(out, "maxCn0", maxCn0.toInt(), maxCn0)
        putSameType(out, "meanCn0", meanCn0.toInt(), meanCn0)
        return out
    }

    /**
     * 卫星快照：可见星数 + **每颗星的载噪比**（单位 dB-Hz）。
     * maxCn0/meanCn0 由同一份列表派生——不再是两个独立的随机数，
     * 于是「GnssStatus 里各星 C/N0」与「Location.extras 的 maxCn0/meanCn0」永远自洽。
     */
    class GnssSnapshot(val svCount: Int, val cn0s: DoubleArray) {
        val maxCn0: Double get() = cn0s.maxOrNull() ?: 0.0
        val meanCn0: Double get() = if (cn0s.isEmpty()) 0.0 else cn0s.average()
    }

    /**
     * 卫星快照的生成：**按时间桶确定**（桶 = 1 秒）。
     *
     * 为什么必须确定性：extras 的改写发生在多个进程（system_server、fused provider、
     * 各家 NLP SDK 进程），快照对象无法跨进程共享；把生成做成「时间桶 → 固定随机序列」的
     * 纯函数后，**任何进程在同一秒内都得到同一份卫星数据**，与真机 1Hz 上报的物理事实一致。
     */
    fun gnssSnapshotForBucket(bucketSec: Long): GnssSnapshot =
        WorldMath.gnssSnapshotForBucket(bucketSec, minSatellites, MAX_SATELLITES)

    /** 当前时间桶的卫星快照（桶 = 1 秒，与真机 GNSS 上报周期一致） */
    fun currentGnssSnapshot(): GnssSnapshot =
        gnssSnapshotForBucket(SystemClock.elapsedRealtimeNanos() / 1_000_000_000L)

    /** 按 [key] 原值的类型写入新值，避免读取方类型不符取到默认值。 */
    private fun putSameType(b: Bundle, key: String, intValue: Int, doubleValue: Double) {
        when (b.get(key)) {
            is Int -> b.putInt(key, intValue)
            is Long -> b.putLong(key, intValue.toLong())
            is Float -> b.putFloat(key, doubleValue.toFloat())
            is Double -> b.putDouble(key, doubleValue)
            else -> b.putInt(key, intValue)
        }
    }




    /** A → B 的初始方位角（度）。实现在 [WorldMath]。 */
    fun calculateBearing(latA: Double, lonA: Double, latB: Double, lonB: Double): Double =
        WorldMath.calculateBearing(latA, lonA, latB, lonB)
}
