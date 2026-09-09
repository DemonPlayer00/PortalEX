package moe.fuqiuluo.xposed.utils

import android.location.Location
import moe.fuqiuluo.xposed.BuildConfig
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object FakeLoc {
    /**
     * 是否允许打印日志
     */
    var enableLog = true

    private var debugLogEnabledByConfig = true

    /**
     * 是否允许打印调试日志。
     * release 构建恒为 false（BuildConfig.DEBUG 钳制，远程指令也翻不开）；
     * debug 构建默认开启，可经配置/远程指令关闭。
     */
    var enableDebugLog: Boolean
        get() = debugLogEnabledByConfig && BuildConfig.DEBUG
        set(value) {
            debugLogEnabledByConfig = value
        }

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
     * 传感器模拟开关（默认开启；进程启动时生效，修改后需重启目标进程）：
     * 开启 = 客户端主动注入（A 方案）：每个 app 进程内注入步数/朝向传感器数据
     * （伪造 Sensor + 调度器生成事件），兼容无步计数传感器的设备。
     * 关闭 = 禁用传感器模拟（不安装任何传感器 hook）。
     * 注：服务端（SensorService 源级改写）方案已弃用，只保留客户端注入。
     */
    @Volatile
    var sensorMockEnabled = true

    /**
     * 模拟WLAN数据
     */
    @Volatile
    var enableMockWifi = false

    /**
     * 是否禁用GetCurrentLocation方法（在部分系统不禁用可能导致hook失效）
     */
    var disableGetCurrentLocation = true

    /**
     * 是否禁用RegisterLocationListener方法
     */
    var disableRegisterLocationListener = false

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
     * 当前朝向（度，0=北，顺时针）：
     * - 应用启动时随机分配中心角度
     * - 移动中由 move/摇杆更新为移动方向
     * - 静止（未操作摇杆、未自动播放）时保持稳定（永远虚拟注入，不随真实设备转动）
     */
    @Volatile var bearing = Random.nextDouble(0.0, 360.0)

    /** 最近一次移动（move 命令）的时间，用于静止检测 */
    @Volatile var lastMoveTimeNanos = 0L

    /** 是否正在移动（摇杆操作中/自动播放中）：最近 2s 内有 move 命令 */
    val isMoving: Boolean
        get() = System.nanoTime() - lastMoveTimeNanos < 2_000_000_000L

    /**
     * 速度推算窗口（纳秒）：取窗口内基础坐标的位移 / 时间差得到速度。
     * 与 [isMoving] 的 2s 静止判定保持一致。
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

            // 停止移动后按采样间隔自适应衰减：超过 2 倍采样间隔开始线性下降
            val avgInterval = spanNanos.toDouble() / (moveSamples.size - 1)
            val fullSpeedUntil = avgInterval * 2
            val decay = if (age <= fullSpeedUntil) {
                1.0
            } else {
                ((SPEED_WINDOW_NANOS - age).toDouble() /
                    (SPEED_WINDOW_NANOS - fullSpeedUntil)).coerceIn(0.0, 1.0)
            }
            (raw * decay).coerceIn(0.0, MAX_MEASURED_SPEED)
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
        lastMoveTimeNanos = now
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
