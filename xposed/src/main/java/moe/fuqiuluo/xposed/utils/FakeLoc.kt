package moe.fuqiuluo.xposed.utils

import android.location.Location
import android.os.Bundle
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

    /**
     * 是否允许打印日志
     */
    var enableLog = true

    /**
     * 是否允许打印调试日志
     */
    var enableDebugLog = true

    /**
     * 模拟定位服务开关
     */
    @Volatile
    var enable = false

    /**
     * 模拟Gnss卫星数据开关
     */
    @Volatile
    var enableMockGnss = false

    /**
     * 模拟WLAN数据
     */
    @Volatile
    var enableMockWifi = false

    /**
     * 如果TelephonyHook失效，可能需要打开此开关
     */
    var disableFusedLocation = true
    var disableNetworkLocation = true

    var disableRequestGeofence = false
    var disableGetFromLocation = false

    /**
     * 是否允许AGPS模块（当前没什么鸟用）
     */
    var enableAGPS = false

    /**
     * 是否允许NMEA模块
     */
    var enableNMEA = false

    /**
     * 是否隐藏模拟位置
     */
    var hideMock = true

    /**
     * may cause system to crash
     */
    var hookWifi = true

    /**
     * 将网络定位降级为Cdma
     */
    var needDowngradeToCdma = true
    var isSystemServerProcess = false

    /**
     * 模拟最小卫星数量
     */
    var minSatellites = 12

    /**
     * 反定位复原加强（启用后将导致部分应用在关闭Portal后需要重新启动才能重新获取定位）
     */
    var loopBroadcastLocation = false

    /**
     * 上一次的位置
     */
    @Volatile var lastLocation: Location? = null
    @Volatile var latitude = 0.0
    @Volatile var longitude = 0.0
    @Volatile var altitude = 80.0

    val offset_altitude : Double
        get() = altitude + Random.nextDouble(-0.25, 0.25)

    @Volatile var speed = 3.05

    /** 速度抖动幅度（m/s，绝对值）：注入 Location.speed 时在模拟速度上叠加 ±该值 */
    var speedAmplitude = 0.3

    @Volatile var hasBearings = false

    /**
     * 权威朝向目标（度，0=北，顺时针）：
     * - 应用启动时随机分配中心角度
     * - 移动中由 move/摇杆/自动播放路线更新为移动方向（允许骤变）
     * - 静止（未操作摇杆、未自动播放）时保持稳定（永远虚拟注入，不随真实设备转动）
     * 注入时经 [processedBearing] 加工（平滑趋近 + 微小扰动），本字段本身不做平滑。
     */
    @Volatile var bearing = Random.nextDouble(0.0, 360.0)

    // ---- 注入角度加工（模块层，数据源侧）----
    // 权威目标 bearing 是路线段方向（允许骤变、直线段恒定）；直接写入会让
    // 位置/指南针锁死在前进方向。这里在**权威值（已由 setBearingSlew 限速）**上
    // 叠加一个低频小扰动——**不再做第二级低通**：传感器侧还会对同一方位做一次平滑，
    // 两级低通串联会把转向滞后拉到 ~1.5s（同一朝向两套平滑 = 口径分叉）。
    private const val BEARING_OUTPUT_RETARGET_TAU = 0.4 // 扰动目标重选时间常数（s）
    private const val BEARING_OUTPUT_APPROACH_TAU = 0.15// 扰动趋近时间常数（s）
    private const val BEARING_OUTPUT_JITTER_AMP = 1.5   // 扰动幅度（±度；与 bearingAccuracy 量级一致）

    @Volatile private var bearingOutputJitter = 0.0
    @Volatile private var bearingOutputJitterTarget = 0.0
    @Volatile private var lastBearingOutputNanos = 0L

    /**
     * 步频-移动速度线性模型（步/分）：cadence = (60 + 30*speed) * 1.15，限幅 60..220。
     * **唯一公式源**：传感器侧（步数推进/摆动）与位置侧（bearing 摇晃频率）都读它，
     * 两边频率才会严格一致。
     */
    fun cadenceForSpeed(speed: Double): Int {
        return ((60.0 + 30.0 * speed) * 1.15).toInt().coerceIn(60, 220)
    }

    /**
     * 加工后的注入角度（度，0~360）：平滑趋近 [bearing] + 微小扰动。
     * 供位置注入（Location.bearing / NMEA trackAngle）使用。
     * **注意**：跑步摇晃（常驻摆动）不在这里做——位置值的更新率由应用请求的间隔决定
     * （0.9s~1s），在这个采样率上塞 3Hz 摆动只会混叠成噪声；摆动一律由传感器 hook
     * 在**每个传感器事件**上生成（见 SystemSensorManagerHook.swingOffset）。
     */
    fun processedBearing(): Double {
        val now = System.nanoTime()
        val dt = if (lastBearingOutputNanos == 0L) 0.0 else (now - lastBearingOutputNanos) / 1e9
        lastBearingOutputNanos = now
        if (dt > 0.0) {
            val dtc = dt.coerceAtMost(1.0)
            // 扰动：目标按时间概率重选，再平滑趋近（唯一的一级加工）
            if (Random.nextDouble() < 1.0 - exp(-dtc / BEARING_OUTPUT_RETARGET_TAU)) {
                bearingOutputJitterTarget =
                    Random.nextDouble(-BEARING_OUTPUT_JITTER_AMP, BEARING_OUTPUT_JITTER_AMP)
            }
            bearingOutputJitter += (bearingOutputJitterTarget - bearingOutputJitter) *
                (1.0 - exp(-dtc / BEARING_OUTPUT_APPROACH_TAU))
        }
        return (bearing + bearingOutputJitter + 360.0) % 360.0
    }

    /** 最短角差（-180, 180] */
    private fun shortestAngleDelta(target: Double, current: Double): Double {
        var d = (target - current) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    // ---- 权威朝向的转向速率限制 ----
    // 路线播放是逐点跳点推进，若直接用相邻两点方向重算 bearing，密集点位 +
    // 轨迹噪声会让朝向忽然随机转动（表现为指南针乱转）。这里限制每秒最大转角。
    @Volatile private var lastBearingSlewNanos = 0L

    /**
     * 更新权威朝向（带转向速率限制）：朝 [target] 转，但每秒最多
     * [maxTurnDegPerSec] 度——噪声方向不会让角度瞬间乱跳，真实急转弯仍能在
     * 1 秒左右转过去（120°/s 时）。
     */
    fun setBearingSlew(target: Double, maxTurnDegPerSec: Double = 120.0) {
        val now = System.nanoTime()
        val dt = if (lastBearingSlewNanos == 0L) 1.0
        else ((now - lastBearingSlewNanos) / 1e9).coerceIn(0.05, 2.0)
        lastBearingSlewNanos = now
        val maxTurn = maxTurnDegPerSec * dt
        val delta = shortestAngleDelta(target, bearing).coerceIn(-maxTurn, maxTurn)
        bearing = (bearing + delta + 360.0) % 360.0
    }

    /**
     * 速度衰减归零时间（纳秒）：停止移动后 [SPEED_DECAY_NANOS] 内速度线性降到 0——
     * 步频（= 60 + 30*speed）随速度同步过渡，过渡区 < 0.5s。
     */
    private const val SPEED_DECAY_NANOS = 400_000_000L

    /**
     * 速度推算采样窗口（纳秒）：取窗口内基础坐标的位移 / 时间差得到速度。
     * 窗口只用于保留历史采样（下次移动时算速度），不决定衰减速度。
     */
    private const val SPEED_WINDOW_NANOS = 2_000_000_000L

    /** 单次坐标跳变超过该距离（米）视为瞬移（手动设点/路线跳点），不计入速度推算 */
    private const val TELEPORT_THRESHOLD_METERS = 50.0

    /** 注入速度上限（m/s，288km/h）：防止异常位移产生荒谬速度 */
    private const val MAX_MEASURED_SPEED = 80.0

    private data class MoveSample(val timeNanos: Long, val lat: Double, val lon: Double)

    /** 基础坐标（未抖动）变化采样，用于按实际位移推算速度 */
    private val moveSamples = ArrayDeque<MoveSample>()

    /**
     * 由实际模拟位移推算的速度（m/s）。
     *
     * 取最近窗口内基础坐标的位移 / 时间差。用**基础坐标**而非注入时抖动后的坐标，
     * 与真实 GPS 用多普勒测速同理：静止时位置抖动不应体现为速度。
     * - 窗口内采样不足 2 个（静止/单次瞬移）→ 0
     * - 停止移动后：超过 2 倍采样间隔开始线性衰减，窗口末尾归零
     * - 位置跳变（> [TELEPORT_THRESHOLD_METERS]）→ 重置窗口，不产生虚高速度
     */
    val measuredSpeed: Double
        get() = synchronized(moveSamples) {
            if (moveSamples.size < 2) return 0.0
            val now = System.nanoTime()
            val newest = moveSamples.last()
            val oldest = moveSamples.first()
            val age = now - newest.timeNanos
            if (age > SPEED_WINDOW_NANOS) return 0.0
            val spanNanos = newest.timeNanos - oldest.timeNanos
            if (spanNanos <= 0L) return 0.0

            val raw = haversine(oldest.lat, oldest.lon, newest.lat, newest.lon) /
                (spanNanos / 1_000_000_000.0)

            // 停止移动后线性衰减：最多 1 个采样间隔内保持全速（避免采样稀疏时立即掉速），
            // 然后到 SPEED_DECAY_NANOS 归零——步频过渡区硬上限 < 0.5s，与采样间隔无关
            val avgInterval = spanNanos.toDouble() / (moveSamples.size - 1)
            val fullSpeedUntil = minOf(avgInterval, SPEED_DECAY_NANOS * 0.5)
            val decay = if (age <= fullSpeedUntil) {
                1.0
            } else {
                ((SPEED_DECAY_NANOS - age) / (SPEED_DECAY_NANOS - fullSpeedUntil))
                    .coerceIn(0.0, 1.0)
            }
            (raw * decay).coerceIn(0.0, MAX_MEASURED_SPEED)
        }

    /**
     * 最近 [windowMs] 内的平均速度（m/s）与「该窗口内是否移动」。
     *
     * **窗口 = 应用请求的交付间隔**——间隔参数在此进入数据链。
     * 为何不能用「相邻两帧的瞬时位移」：服务端坐标是按点跳变推进的（自动播放逐点、摇杆步进），
     * 只有恰好包含那次跳变的帧才算得出速度，其余帧全为 0；而交付间隔由应用决定（可 900ms~1s+），
     * 应用侧会把这些 0 帧当成静止 → 整段间隔不计步（典型偏差：移动中投出的帧 vel=0，步频掉到下限）。
     * 按间隔窗口取平均后，任何一帧都代表“这段时间走了多少”，与采样相位无关。
     *
     * 位置历史取自 [moveSamples]（仅在坐标变化时记录，位置分段常数，无交付抖动）。
     */
    fun averageSpeedOverWindow(windowMs: Long): Pair<Double, Boolean> {
        val winNanos = windowMs.coerceIn(1L, 10_000L) * 1_000_000L
        val now = System.nanoTime()
        val curLat = latitude
        val curLon = longitude
        synchronized(moveSamples) {
            if (moveSamples.isEmpty()) return 0.0 to false
            val fromNanos = now - winNanos
            // 窗口起点处的坐标 = 起点之前最近的一次采样（位置分段常数）
            var startLat = moveSamples.first().lat
            var startLon = moveSamples.first().lon
            var startNanos = moveSamples.first().timeNanos
            for (s in moveSamples) {
                if (s.timeNanos <= fromNanos) {
                    startLat = s.lat
                    startLon = s.lon
                    startNanos = s.timeNanos
                } else break
            }
            val distM = haversine(startLat, startLon, curLat, curLon)
            // 瞬移（手动设点/路线跳点）不算速度
            if (distM > TELEPORT_THRESHOLD_METERS) return 0.0 to false
            // 实际覆盖时长：采样历史比窗口短时用真实时长，否则按窗口计
            val spanSec = minOf(now - startNanos, winNanos).coerceAtLeast(1_000_000L) / 1e9
            val speed = (distM / spanSec).coerceIn(0.0, MAX_MEASURED_SPEED)
            return speed to (speed > 0.05)
        }
    }

    /**
     * 记录一次基础坐标变化（move / update_location），用于按实际位移推算速度，
     * 同时更新移动时间戳（静止检测）。
     */
    fun recordCoordinateChange(lat: Double, lon: Double) {
        val now = System.nanoTime()
        synchronized(moveSamples) {
            val last = moveSamples.lastOrNull()
            if (last != null &&
                haversine(last.lat, last.lon, lat, lon) > TELEPORT_THRESHOLD_METERS
            ) {
                // 瞬移（手动设点/路线跳点）：重置窗口，避免算出虚高速度
                moveSamples.clear()
            }
            moveSamples.addLast(MoveSample(now, lat, lon))
            val cutoff = now - SPEED_WINDOW_NANOS
            while (moveSamples.isNotEmpty() && moveSamples.first().timeNanos < cutoff) {
                moveSamples.removeFirst()
            }
        }
    }

    var accuracy = 25.0f
        set(value) {
            field = if (value < 0) {
                -value
            } else {
                value
            }
        }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }

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
        // 星数：与 GnssStatus 推送的 svCount 用**同一上限常量**（MAX_SATELLITES），
        // 否则同一时刻「雷达显示的星数」与「extras 里的星数」范围不同，可交叉比对出不一致
        val count = Random.nextInt(minSatellites, MAX_SATELLITES + 1)
        val maxCn0 = Random.nextDouble(30.0, 45.0)
        val meanCn0 = maxCn0 - Random.nextDouble(6.0, 14.0)
        putSameType(out, "satellites", count, count.toDouble())
        putSameType(out, "maxCn0", maxCn0.toInt(), maxCn0)
        putSameType(out, "meanCn0", meanCn0.toInt(), meanCn0)
        return out
    }

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

    fun jitterLocation(lat: Double = latitude, lon: Double = longitude, n: Double = Random.nextDouble(0.0, accuracy.toDouble()), angle: Double = bearing): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val radiusInDegrees = n / 15 / earthRadius * (180 / PI)

        val jitterAngle = if (Random.nextBoolean()) angle + 45 else angle - 45

        val newLat = lat + radiusInDegrees * cos(Math.toRadians(jitterAngle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(jitterAngle)) / cos(Math.toRadians(lat))

        return Pair(newLat, newLon)
    }

    fun moveLocation(lat: Double = latitude, lon: Double = longitude, n: Double, angle: Double = bearing): Pair<Double, Double> {
        val earthRadius = 6371000.0
        // 对称小抖动（±5%，均值 = 请求距离）：旧实现 uniform(n, n+1.2) 每步系统性多走 0~1.2m，
        // 叠加 tick 频率后实际速度远高于设定值（默认 100ms 上报 ≈ 3 倍速），这里修正。
        val radiusInDegrees = Random.nextDouble(n * 0.95, n * 1.05) / earthRadius * (180 / PI)
        val newLat = lat + radiusInDegrees * cos(Math.toRadians(angle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(angle)) / cos(Math.toRadians(lat))
        return Pair(newLat, newLon)
    }



    fun calculateBearing(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val lat1 = Math.toRadians(latA)
        val lon1 = Math.toRadians(lonA)
        val lat2 = Math.toRadians(latB)
        val lon2 = Math.toRadians(lonB)

        val deltaLon = lon2 - lon1

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360  // 标准化到0-360度

        return bearing
    }
}
