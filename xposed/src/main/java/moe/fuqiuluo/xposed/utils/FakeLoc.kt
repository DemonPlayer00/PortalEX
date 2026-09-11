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
     * Binder 外周传感器模拟（实验性，默认关）：
     * 由 system_server 侧原生 hook（[moe.fuqiuluo.xposed.hooks.sensor.BinderSensorMock]）
     * 在系统框架层接管外周传感器——**不向目标应用注入任何 hook**。
     * 关闭（默认）时该路径完全不安装，行为与旧版本逐位一致。
     */
    @Volatile
    var enableBinderSensorMock = false

    /** 注入栅格分辨率（Hz）：0 = 自动跟随框架采用值；非 0 时固定为 1e9/该值（原生层钳 2.5~50ms） */
    var sensorGridHz = 0

    /**
     * 原生注入层是否已成功装载（由 BinderSensorMock 在 system_server 内回填，只读诊断用）：
     * 装载失败时为 false，此时不改变任何既有行为。
     */
    @Volatile
    var binderSensorNativeReady = false

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
     * 本字段是**权威目标**（允许骤变）；对外输出由 [processedBearing] 生成——
     * 中轴低通 + 随机往复摆动都在那里，传感器侧只跟随帧值，不再加工。
     */
    @Volatile var bearing = Random.nextDouble(0.0, 360.0)

    /**
     * 步频-移动速度线性模型（步/分）：cadence = (60 + 30*speed) * 1.15，限幅 60..220。
     * **唯一公式源**：传感器侧（步数推进/摆动）与位置侧（bearing 摇晃频率）都读它，
     * 两边频率才会严格一致。
     */
    /**
     * 步频倍率（设置页「步频倍率」）：默认 1.0 = 逐位保持原公式；非 1 时按倍率微调
     * "步频 ↔ 速度"的关系（例如 1.1 = 同样速度下步频快 10%）。
     * 作用在**基础值 clamp 之后**，并再钳进 30~300 的物理合理区间——
     * 这样默认值下输出与改动前完全一致（不会因为换了钳位区间而改变既有行为）。
     */
    var cadenceScale = 1.0

    fun cadenceForSpeed(speed: Double): Int {
        val base = ((60.0 + 30.0 * speed) * 1.15).toInt().coerceIn(60, 220)
        if (cadenceScale == 1.0) return base
        return Math.round(base * cadenceScale).toInt().coerceIn(30, 300)
    }

    // ---- 朝向生成器（**唯一所有者 = 位置端**；app 端只跟随，不做任何加工）----
    //
    // 输出 = 平滑中轴 + 随机往复摆动 + 低频漂移 + 高频微抖，全部在这里生成：
    //  · 中轴：权威朝向的低通（τ = CENTER_TAU）——中轴变化（摇杆转向 / 路线转弯）时
    //    **平滑过渡到目标**，不跳变；
    //  · 摆动：±3° 内的**随机往复**——中轴不变（摆动关于中轴对称），每次换边时在两侧
    //    各取一个随机幅值，半周期 0.35~0.75s 随机；移动与静止用**同一个**摆动；
    //  · 漂移/微抖：慢速磁环境漂移 + 手部微抖（真机磁罗盘从不是干净的正弦）。
    //
    // 位置帧直接携带这里的结果（Location.bearing / NMEA trackAngle）；传感器侧只把帧值
    // 做一次**跟随低通**（插值，使其在每个事件上都连续），不再自产或叠加任何分量。
    private const val CENTER_TAU = 0.45          // 中轴低通时间常数（s）：转向平滑过渡
    private const val SWAY_MAX_DEG = 3.0         // 摆动单侧最大幅值（±3°）
    private const val SWAY_MIN_DEG = 0.5         // 摆动单侧最小幅值（避免"这轮不摆"的巧合）
    private const val SWAY_HALF_PERIOD_MIN_NANOS = 350_000_000L   // 半周期下限（s）
    private const val SWAY_HALF_PERIOD_MAX_NANOS = 750_000_000L   // 半周期上限（s）
    // 基础扰动（指南针的"针底噪"）——按需求调大：
    //  · 漂移：真机磁罗盘受磁环境/姿态影响会缓慢游走数度，故主项 ±2.0°(0.05Hz) + 次项 ±1.0°(0.017Hz)；
    //  · 微抖：手部持机的抖动约 ±0.3~1°，取 ±0.6°、τ=0.12s（平滑动而不是白噪声）。
    private const val DRIFT_AMP_PRIMARY = 2.0    // 低频漂移主项（±度）
    private const val DRIFT_AMP_SECONDARY = 1.0  // 低频漂移次项（±度）
    private const val DRIFT_HZ_PRIMARY = 0.05    // 漂移主项频率（Hz）
    private const val DRIFT_HZ_SECONDARY = 0.017 // 漂移次项频率（Hz）
    private const val MICRO_JITTER_AMP = 0.6     // 高频微抖（±度）
    private const val MICRO_JITTER_TAU = 0.12    // 微抖时间常数（s）

    /** 平滑后的中轴（跟随 [bearing]） */
    @Volatile private var centerBearing = bearing
    @Volatile private var lastBearingSampleNanos = 0L

    // 随机往复摆动状态：swayFrom → swayTo 为**半个周期**，换边时起点 = 上一次终点
    // （值与斜率都连续），并在新的一侧随机取幅值——中轴不因此移动。
    @Volatile private var swayPhaseStartNanos = 0L
    @Volatile private var swayHalfPeriodNanos = 500_000_000L
    @Volatile private var swayFrom = 0.0
    @Volatile private var swayTo = 0.0
    @Volatile private var swaySide = 1.0

    @Volatile private var microTarget = 0.0
    @Volatile private var microOffset = 0.0
    private val driftPhase1 = Random.nextDouble(0.0, Math.PI * 2)
    private val driftPhase2 = Random.nextDouble(0.0, Math.PI * 2)

    private fun randomSwayHalfPeriod(): Long =
        Random.nextLong(SWAY_HALF_PERIOD_MIN_NANOS, SWAY_HALF_PERIOD_MAX_NANOS)

    /**
     * 随机往复摆动（±[SWAY_MAX_DEG] 内，中轴不变）：
     * 半周期内从 [swayFrom] 平滑走到 [swayTo]（两端导数为 0 的余弦缓动），
     * 到点后**换到另一侧**重新随机取幅值，起点承接上一次终点——值连续、斜率连续，
     * 因此看起来是"来回游走"而不是"跳一下再跳回来"。
     */
    private fun advanceSway(now: Long): Double {
        if (swayPhaseStartNanos == 0L) {
            swayPhaseStartNanos = now
            swaySide = 1.0
            swayFrom = 0.0
            swayTo = Random.nextDouble(SWAY_MIN_DEG, SWAY_MAX_DEG)
            swayHalfPeriodNanos = randomSwayHalfPeriod()
        }
        var elapsed = now - swayPhaseStartNanos
        if (elapsed >= swayHalfPeriodNanos) {
            swayFrom = swayTo
            swaySide = -swaySide
            swayTo = swaySide * Random.nextDouble(SWAY_MIN_DEG, SWAY_MAX_DEG)
            swayPhaseStartNanos = now
            swayHalfPeriodNanos = randomSwayHalfPeriod()
            elapsed = 0
        }
        val t = (elapsed.toDouble() / swayHalfPeriodNanos).coerceIn(0.0, 1.0)
        val ease = (1.0 - cos(Math.PI * t)) / 2.0
        return swayFrom + (swayTo - swayFrom) * ease
    }

    /**
     * 注入朝向（位置帧 / NMEA trackAngle 用）：平滑中轴 + 低频漂移（慢频段）。
     *
     * **不含 ±3° 随机往复摆动**：位置帧的到达间隔由应用请求/保活决定（实测静止时 1.5s），
     * 在这个采样率上携带 1~1.4Hz 的摆动只会变成"每隔一秒多跳一次并锁定"；
     * 真机 GPS course 也不含这种步态分量。摆动由传感器端按**事件率采样同一生成器**
     * （见 [sampleBearingJitter]）——生成器与状态仍在位置端，传感器端只是取采样点。
     */
    fun processedBearing(): Double {
        val now = System.nanoTime()
        val dt = if (lastBearingSampleNanos == 0L) 0.0
        else ((now - lastBearingSampleNanos) / 1e9).coerceIn(0.0, 1.0)
        lastBearingSampleNanos = now

        if (dt > 0.0) {
            // 中轴平滑趋近权威目标（最短角差，跨 0/360 不绕远）——转向时平滑过渡
            centerBearing += shortestAngleDelta(bearing, centerBearing) *
                (1.0 - exp(-dt / CENTER_TAU))
            centerBearing = (centerBearing % 360.0 + 360.0) % 360.0
            // 微抖：目标概率重选 + 平滑趋近
            if (Random.nextDouble() < 1.0 - exp(-dt / MICRO_JITTER_TAU)) {
                microTarget = Random.nextDouble(-MICRO_JITTER_AMP, MICRO_JITTER_AMP)
            }
            microOffset += (microTarget - microOffset) * (1.0 - exp(-dt / MICRO_JITTER_TAU))
        }

        // 低频漂移：两条慢正弦（时间的纯函数，连续无跳变）
        val tSec = now / 1e9
        val drift = DRIFT_AMP_PRIMARY * sin(2.0 * Math.PI * DRIFT_HZ_PRIMARY * tSec + driftPhase1) +
            DRIFT_AMP_SECONDARY * sin(2.0 * Math.PI * DRIFT_HZ_SECONDARY * tSec + driftPhase2)

        val value = centerBearing + drift
        return (value % 360.0 + 360.0) % 360.0
    }

    /**
     * 传感器端采样：本拍**快频段** = ±3° 随机往复摆动（[advanceSway]）+ 微抖（[microOffset]）。
     *
     * 为什么快频段必须在这里取：位置帧间隔由应用请求/保活决定（静止实测 1.5s），
     * 且传感器侧对帧值还有一次跟随低通（τ≈0.25s）——把 1Hz 以上的分量放进帧里，
     * 要么混叠成"跳一次锁一秒"，要么被低通滤掉。生成器与状态仍在位置端，
     * 传感器侧只是按事件率取采样点。
     */
    fun sampleBearingJitter(): Double = advanceSway(System.nanoTime()) + microOffset

    /** 最短角差（-180, 180]：方位角是环形量，跨 0/360 必须走最短弧） */
    private fun shortestAngleDelta(target: Double, current: Double): Double {
        var d = (target - current) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /**
     * 速度推算采样窗口（纳秒）：只在坐标变化时记录采样，用于按位移推算速度。
     * 窗口只用于保留历史采样，不决定衰减速度（衰减由 [averageSpeedOverWindow] 的窗口决定）。
     */
    private const val SPEED_WINDOW_NANOS = 2_000_000_000L

    /** 单次坐标跳变超过该距离（米）视为瞬移（手动设点/路线跳点），不计入速度推算 */
    private const val TELEPORT_THRESHOLD_METERS = 50.0

    /** 注入速度上限（m/s，288km/h）：防止异常位移产生荒谬速度 */
    private const val MAX_MEASURED_SPEED = 80.0

    /** 基础坐标（未抖动）变化采样，用于按实际位移推算速度 */
    private data class MoveSample(val timeNanos: Long, val lat: Double, val lon: Double)

    private val moveSamples = ArrayDeque<MoveSample>()

    /**
     * 最近 [windowMs] 内的平均速度（m/s）与「该窗口内是否移动」。
     *
     * 调用方传入的窗口 = 一帧所代表的时长（注入帧默认 1s = 真机 GPS 的自然出帧率）。
     * 为何不能用「相邻两帧的瞬时位移」：服务端坐标是按点跳变推进的（自动播放逐点、摇杆步进），
     * 只有恰好包含那次跳变的帧才算得出速度，其余帧全为 0 → 应用侧会把这些 0 帧当成静止，
     * 整段不计步（典型偏差：移动中投出的帧 vel=0，步频掉到下限）。
     * 按窗口取平均后，任何一帧都代表“这段时间走了多少”，与采样相位无关。
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
     * 记录一次基础坐标变化（move / update_location / 配置镜像），
     * 供 [averageSpeedOverWindow] 按实际位移推算速度。
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
    fun gnssSnapshotForBucket(bucketSec: Long): GnssSnapshot {
        val rng = Random(bucketSec * 1_000_003L + minSatellites * 7919L + 0x5DEECE66DL)
        val svCount = rng.nextInt(minSatellites, MAX_SATELLITES + 1)
        val cn0s = DoubleArray(svCount) { 24.0 + rng.nextDouble() * 21.0 }   // 24~45 dB-Hz（真机量级）
        return GnssSnapshot(svCount, cn0s)
    }

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

    // ---- 位置偏移（注入坐标相对配置点的抖动）----
    // 旧实现：**每次调用**独立掷一次「0~accuracy 米 + 随机方向」→ 相邻两帧的位置可差 2×accuracy
    // （默认 25m 时就是 50m/0.1s ≈ 500m/s），与同一帧的上报速度、连续轨迹自相矛盾，可被交叉检测，
    // 也让轨迹像布朗运动。现在改为二维慢游走（Ornstein-Uhlenbeck，τ=3s）——
    // 秒级尺度上连续（与速度自洽），长时间尺度上仍在 accuracy 范围内游走（真机 GPS 误差形态）。
    // 参数标定：注入口径下相邻帧的偏移变化会被读成"额外速度"（Δ位置/Δ时间）。
    // 推送节拍可到 100ms（reportDuration），故 σ 取 0.18 m/√s —— 100ms 帧的偏移变化约 0.06m
    // （等效 ~0.6 m/s，仅占上报速度的一小部分），1s 帧约 0.18m，静止长时间游走 std ≈ 0.28m。
    private const val POSITION_JITTER_TAU = 5.0
    private const val POSITION_JITTER_SIGMA = 0.18  // 每 √s 的扩散步长（m）
    private val positionJitterLock = Any()
    private val jitterGauss = java.util.Random()
    @Volatile private var jitterEastM = 0.0
    @Volatile private var jitterNorthM = 0.0
    @Volatile private var lastJitterNanos = 0L

    private fun advancePositionJitter() {
        val now = System.nanoTime()
        val dt = if (lastJitterNanos == 0L) 0.0
        else ((now - lastJitterNanos) / 1e9).coerceIn(0.0, 5.0)
        lastJitterNanos = now
        if (dt <= 0.0) return
        val decay = exp(-dt / POSITION_JITTER_TAU)
        val kick = POSITION_JITTER_SIGMA * sqrt(dt)
        synchronized(positionJitterLock) {
            jitterEastM = jitterEastM * decay + jitterGauss.nextGaussian() * kick
            jitterNorthM = jitterNorthM * decay + jitterGauss.nextGaussian() * kick
        }
    }

    /**
     * 注入坐标 = 配置坐标 + 缓慢游走的偏移（夹在 ±[n] 米内）。
     * @param n 偏移上限（米），默认取配置的水平精度 accuracy——与上报的 hAcc 自洽。
     * @param angle 已不再参与运算（保留形参以兼容调用点）：偏移方向由游走过程决定，
     *   不再按 bearing 强行选边。
     */
    fun jitterLocation(
        lat: Double = latitude,
        lon: Double = longitude,
        n: Double = accuracy.toDouble(),
        angle: Double = bearing
    ): Pair<Double, Double> {
        advancePositionJitter()
        val bound = n.coerceAtLeast(0.0)
        val east = jitterEastM.coerceIn(-bound, bound)
        val north = jitterNorthM.coerceIn(-bound, bound)
        val dLat = north / 111320.0
        val dLon = east / (111320.0 * cos(Math.toRadians(lat)))
        return Pair(lat + dLat, lon + dLon)
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
