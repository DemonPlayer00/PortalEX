@file:Suppress("UNCHECKED_CAST", "PrivateApi", "DiscouragedPrivateApi")
package moe.fuqiuluo.xposed.hooks.sensor

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.onceHook
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外周传感器模拟 hook（真实回调改写方案）。
 * 注入与否完全由 LSPosed 作用域决定：本类代码只在被勾选的应用进程里加载，恒安装。
 *
 * 安装范围（见 FakeLocation.handleLoadPackage）：仅对用户应用进程安装；
 * system_server/phone 等系统框架进程不装——避免拦截系统自身传感器注册
 * （自动旋转、计步统计等）污染系统行为。SystemSensorManager 是 SDK 客户端类，
 * 运行在每个 app 进程内，因此每个被勾选的用户应用都要安装。
 *
 * 数据流（不自产事件）：应用注册真实传感器 → 系统回调 dispatchSensorEvent
 * → 按 handle→type 改写 values 为模拟值 → 投递应用。事件载体始终是真实回调数据，
 * 无主动注入/定时调度——回调在，模拟就在。
 *
 * 注入对象 = 步数 + 航向族（旋转 / 地磁 / 重力 / 角速度），被选中的用户应用永远收到虚拟数据：
 * - 步数：移动中按速度 cadence 推进；静止时停留不增长。
 * - 航向族：旋转（ORIENTATION/ROTATION_VECTOR/GAME_ROTATION_VECTOR）、地磁
 *   （MAGNETIC_FIELD[_UNCALIBRATED]）、重力（GRAVITY）共用**同一虚拟方位**——
 *   App 用旋转传感器取方向、用 getRotationMatrix(gravity, magnetic) 合成指南针，
 *   两条路径得到一致结果，且不随真实设备转动/倾斜变化。
 * - 角速度（GYROSCOPE[_UNCALIBRATED]）= 同一虚拟方位的变化率：方位平滑转弯时
 *   输出短时角速度峰值、静止≈0——与旋转/地磁路径协调一致，App 航向融合
 *   （陀螺仪积分 + 磁力计绝对方位）不会因「磁力计说在转弯、陀螺仪却说没动」
 *   的冲突在信任切换时指针间歇大角度旋转。
 * - 无真实传感器时：getDefaultSensor/getSensorList/getFullSensorsList 暴露伪造步计数器。
 *
 * 节拍（与安卓标准一致：传感器回调率由应用注册的 samplingPeriodUs 决定，与位置请求间隔无关）：
 * - 航向族：**只改写真实回调的 values，不自己造事件** → 事件率 = 应用注册的采样率；
 *   运动学推进也是**事件驱动**（每个事件一次，按 Δt 缩放步长），并给每个采样加独立
 *   小噪声——FASTEST/GAME 注册也不会拿到逐位相同的值。
 * - 步数：on-change 传感器，真机每步发一次（~1.5~3 Hz）；模拟值由位置回调推进，
 *   但**按步间隔分摊投递**（每步一个事件 + 对应时间戳），事件率与位置回调率解耦。
 *
 * 模块层加工链（角度/方向，单一数据源，不依赖 App/系统端处理）：
 *   位置回调 bearing → bearingTarget（权威目标，允许骤变）
 *     → advanceBearing()：smoothBearing 平滑趋近（低通，~0.75s —— **唯一的一级方位低通**；
 *       位置侧 FakeLoc.processedBearing 只叠加小扰动，不再做第二级低通）
 *   位置帧 bearing = 位置端生成器的**帧频段**（平滑中轴 + 漂移 + 微抖，不含摆动）
 *     → advanceBearing()：smoothBearing = 对该帧值的跟随低通（τ≈0.25s）
 *     → virtualAzimuth() = smoothBearing + 位置端生成器的**快频段**（±3° 往复摆动 + 微抖，
 *       按传感器事件率取样：FakeLoc.sampleBearingJitter）
 *     → sensorValuesFor(type)：欧拉角 / 四元数 / 地磁矢量 / 重力常量
 *   加工节拍 = 事件驱动（每事件一次，按 Δt 缩放；见 advanceMotionForEvent）。
 * - 步频 cadence(步/min) = (60 + 30*speed) * 1.15，限幅 60..220（慢走 1.2m/s→110，3.5→189，慢跑 3.0→172）。
 *
 * 数据来源：权威值在 system_server（速度 = 实际位移推算值 FakeLoc.measuredSpeed，
 * 朝向 = FakeLoc.bearing）。本进程在**应用注册的监听器回调**上取数——只读位置对象的
 * **标准字段**（location.speed/location.bearing，BaseLocationHook 写入），
 * 字段缺失时按位移推算；不依赖系统端 extras 私有键，无模块特征数据通路。
 */
object SystemSensorManagerHook {
    // ---- 传感器类型（Android Sensor.TYPE_*） ----
    private const val TYPE_ACCELEROMETER = 1
    private const val TYPE_ACCELEROMETER_UNCALIBRATED = 35
    private const val TYPE_LINEAR_ACCELERATION = 10
    private const val TYPE_GEOMAGNETIC_ROTATION_VECTOR = 20
    private const val TYPE_STEP_COUNTER = 19
    private const val TYPE_STEP_DETECTOR = 18
    private const val TYPE_ORIENTATION = 3
    private const val TYPE_ROTATION_VECTOR = 11
    private const val TYPE_GAME_ROTATION_VECTOR = 15
    private const val TYPE_MAGNETIC_FIELD = 2
    private const val TYPE_MAGNETIC_FIELD_UNCALIBRATED = 14
    private const val TYPE_GRAVITY = 9
    private const val TYPE_GYROSCOPE = 4
    private const val TYPE_GYROSCOPE_UNCALIBRATED = 16

    // 姿态输入族 + 航向族：旋转（欧拉角/四元数）、地磁（矢量）、重力（合成航向辅助输入，
    // 固定平放姿态）、角速度（同一虚拟方位的运动学表现，积分=方位）、姿态输入
    // （加速度计/线性加速度 = 同一平放虚拟姿态，供 getRotationMatrix(accel, mag) 融合）——
    // 是**同一虚拟方位/姿态的不同表现形式**，必须同 tick 注入——所有判断/调度只认这一个集合。
    // - 陀螺仪缺失时，App 航向融合会因「磁力计说在转弯、陀螺仪却说没动」冲突，信任切换时
    //   指针间歇大角度旋转；同源注入后两条路径一致。
    // - 加速度计缺失时（真实放行），getRotationMatrix(真实 accel 倾角, 注入 mag) 会把真实
    //   设备姿态注入与虚拟方位矛盾，投影突变导致指针大角度旋转；模拟平放后全输入同源。
    private val HEADING_SENSOR_TYPES = setOf(
        TYPE_ORIENTATION, TYPE_ROTATION_VECTOR, TYPE_GAME_ROTATION_VECTOR,
        TYPE_MAGNETIC_FIELD, TYPE_MAGNETIC_FIELD_UNCALIBRATED, TYPE_GRAVITY,
        TYPE_GYROSCOPE, TYPE_GYROSCOPE_UNCALIBRATED,
        TYPE_ACCELEROMETER, TYPE_ACCELEROMETER_UNCALIBRATED, TYPE_LINEAR_ACCELERATION,
        TYPE_GEOMAGNETIC_ROTATION_VECTOR
    )
    // 注入集合 = 步数 + 航向族
    // 注入集合 = 步数（计数器 + 检测器，两者必须同源：计数器在涨而检测器不响会被交叉比对）+ 航向族
    private val INJECTABLE_SENSOR_TYPES =
        HEADING_SENSOR_TYPES + TYPE_STEP_COUNTER + TYPE_STEP_DETECTOR

    // sensor handle -> sensor type（dispatchSensorEvent 只提供 handle）
    private val sensorHandleTypeMap = ConcurrentHashMap<Int, Int>()

    // 步数 listener 记录：步数传感器是 on-change 类型，设备不走路就没有真实事件，
    // 无法靠回调改写驱动——改由位置回调（步数真正的对应数据源）推进并主动投递。
    private data class StepListener(
        val listener: SensorEventListener,
        val handler: Handler?,
        val sensor: Sensor
    )

    private val stepListeners = CopyOnWriteArraySet<StepListener>()

    // 模拟步数真值（随机起点，模拟"已经走了不少"）+ 小数累加器
    private val globalSteps = AtomicInteger(kotlin.random.Random.nextInt(3000, 12000))
    private val stepLock = Any()
    private var stepFraction = 0.0
    @Volatile private var lastStepAdvanceNanos = 0L

    // 伪造的步数传感器（无真实传感器时暴露给 App）：计数器 19 + 检测器 18
    private var fakeStepSensor: Sensor? = null
    private var fakeStepDetectorSensor: Sensor? = null

    // 运动学输入缓存（m/s | 度 | 是否移动中）——由应用注册的监听器回调同步（标准字段）。
    // 初值取配置速度（FakeLoc.speed）：旧实现写死 1.5，与配置默认 3.05 不一致。
    @Volatile private var speedCache = FakeLoc.speed
    // 权威模拟朝向（目标值，允许骤变——摇杆转向/自动播放路线转弯时更新）。
    // 注入方位由 smoothBearing 趋近平滑过渡到它：方向变化平滑转弯而非瞬跳。
    // 初值 = 进程内随机（自治：无任何外部输入时也有合理虚拟朝向，不与真实设备雷同）。
    @Volatile private var bearingTarget = kotlin.random.Random.nextDouble(0.0, 360.0)
    @Volatile private var smoothBearing = bearingTarget     // 注入用平滑方位（S 曲线过渡输出）
    @Volatile private var movingCache = false
    // 移动判定辅助：无速度字段时的位移判定（前后注入位置距离 >= 1m）
    @Volatile private var lastSyncLat = Double.NaN
    @Volatile private var lastSyncLon = Double.NaN

    // 朝向的两条来源（都由**位置端**定义，本进程只取用，不产生任何随机/摆动）：
    //   ① 中轴：随位置帧到达（位置端已做 τ=0.45s 低通）→ 本进程再跟随一次低通，
    //      把离散帧插值成连续曲线（τ ≈ 0.25s：既平滑，又不至于把转向拖太久）；
    //   ② 快频段（±3° 随机往复摆动 + 微抖）：本进程按**传感器事件率**向同一生成器取样
    //      （FakeLoc.sampleBearingJitter）。帧里不含它，故不存在叠加两次。
    // 为什么摆动必须按事件率取：位置帧间隔由应用请求/保活决定（实测静止 1.5s），
    // 1.5s 的采样率承载 1~1.4Hz 摆动只会变成"跳一次、锁住、再跳"（已实测复现）。
    private const val BEARING_APPROACH_ALPHA = 0.18  // 每 50ms tick 靠近 18% → τ ≈ 0.25s

    // 虚拟世界环境（每进程仅生成一次——世界固有属性，不是每 tick 重新掷骰）：
    // 所有航向族传感器读到的都是**同一个虚拟世界**的同一磁场：
    // - 场强 H = 28~42µT（典型城市地磁）与磁倾角 dip 恒定；
    // - 未校准 bias 恒定小偏置（真实设备 bias 漂移极慢，可视为常量）。
    // 每 tick 唯一变化的运动学量 = 虚拟方位（virtualAzimuth()）。
    private val magneticH = kotlin.random.Random.nextDouble(28.0, 42.0)
    private val magneticDip = kotlin.random.Random.nextDouble(0.8, 1.2)
    private val magneticBiasX = kotlin.random.Random.nextDouble(-3.0, 3.0)
    private val magneticBiasY = kotlin.random.Random.nextDouble(-3.0, 3.0)

    // 陀螺仪角速度真值：虚拟方位变化率（绕设备 Z 轴，rad/s）。
    // 与磁力计/旋转族同源（同 tick 注入）：方位平滑转弯时角速度 = d(方位)/dt，
    // 静止时 ≈0——符合真实陀螺仪连续输出。
    // 虚拟方位按 20Hz 节拍跃迁推进，相邻事件角速度呈脉冲状，低通后接近真实连续曲线。
    private const val GYRO_SMOOTH_ALPHA = 0.25
    @Volatile private var lastAzimuthNanos = 0L
    @Volatile private var lastAzimuthAngle = Double.NaN
    @Volatile private var smoothGyroZ = 0.0
    // 未校准陀螺仪 bias（估计漂移通道，rad/s）：真实设备 bias 漂移极慢，视作进程恒定常量
    private val gyroDriftX = kotlin.random.Random.nextDouble(-0.008, 0.008)
    private val gyroDriftY = kotlin.random.Random.nextDouble(-0.008, 0.008)
    private val gyroDriftZ = kotlin.random.Random.nextDouble(-0.008, 0.008)

    /**
     * 方位平滑（趋近）：摇杆转向/自动播放路线转弯时 bearingTarget 骤变，
     * smoothBearing 向目标趋近——角度传感器/指南针平滑转弯而非瞬跳。
     */
    private fun advanceBearing(dtSec: Double) {
        // 方位角是环形量（0..360）：差值必须归一化到 [-180, 180] 走最短弧，
        // 否则目标跨过 0/360 边界时会朝反方向绕一大圈
        var delta = bearingTarget - smoothBearing
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        smoothBearing += delta * alphaForDelta(dtSec, BEARING_APPROACH_ALPHA)
        smoothBearing = (smoothBearing % 360.0 + 360.0) % 360.0
    }

    /** 推进运动学量（[dtSec] = 距上次推进时长）：只平滑权威方位，扰动另行叠加 */
    private fun advanceMotion(dtSec: Double) {
        advanceBearing(dtSec)
    }

    /**
     * 事件驱动推进：**每个传感器事件推进一次**，不再按固定 20Hz 节流。
     * 真机每个采样都有自己的时刻与噪声；若值只在 20Hz 变化，以 FASTEST/GAME
     * （≥50Hz）注册的应用会连续多个采样拿到逐位相同的值（可检测）。
     * 平滑/扰动步长按实际 Δt 缩放 → 与事件频率、传感器数量无关。
     */
    private fun advanceMotionForEvent() {
        val now = System.nanoTime()
        val dtSec = if (lastMotionAdvanceNanos == 0L) 0.0
        else ((now - lastMotionAdvanceNanos) / 1e9).coerceIn(0.0, 0.5)
        lastMotionAdvanceNanos = now
        advanceMotion(dtSec)
    }

    /** 「每 tick 的比例」→「时长 dtSec 的等效比例」（与推进频率无关） */
    private fun alphaForDelta(dtSec: Double, alphaPerTick: Double): Double {
        if (dtSec <= 0.0) return 0.0
        val ticks = (dtSec / TICK_SEC).coerceIn(0.0, 8.0)
        return 1.0 - Math.pow(1.0 - alphaPerTick, ticks)
    }

    /**
     * 记录**每一步**的时间间隔（秒）：摆动半周期与步频同步。
     * 传入的是本间隔 / 本段步数——交付间隔变长后若直接拿交付间隔当步间隔，
     * 摆频会被拉长数倍（角度摆动明显变慢），这就是把间隔参数带进计算的第二个修正点。
     */
    // 运动学推进：事件驱动（见 advanceMotionForEvent）；TICK_SEC = 旧实现固定节拍，
    // 只用于把「每 tick 的比例/概率」折算成任意 dt 的等效值
    private const val TICK_SEC = 0.05
    @Volatile private var lastMotionAdvanceNanos = 0L

    operator fun invoke(classLoader: ClassLoader) {
        // 注入与否完全由 LSPosed 作用域决定：本模块代码只会在被勾选的应用进程里加载，
        // 因此走到这里 = 该应用已在作用域内，恒安装传感器 hook（无开关）。
        // 真实回调改写：SystemSensorManager 是 SDK 客户端类，运行在每个 app 进程内。
        createFakeStepSensor()
        hookSensorExposure(classLoader)
        hookRegisterListener(classLoader)
        hookSystemSensorManagerQueue(classLoader)
        hookSpeedSync(classLoader)
    }

    /** 步频-移动速度线性模型（步/min）——**唯一公式源在 FakeLoc.cadenceForSpeed**：
     *  传感器侧摆动/位置侧 bearing 摇晃共用它，两边的摇晃频率才会一致。 */
    private fun cadenceForSpeed(speed: Double): Int = FakeLoc.cadenceForSpeed(speed)

    /**
     * 本间隔内应累计的步数 = 步频(步/分)/60 × **间隔(秒)**。
     * 间隔参数必须显式参与：交付间隔由应用请求决定（可以 900ms~1s+），
     * 速度是「本间隔平均值」（FakeLoc.frameSpeed），乘上间隔才是这段时间的真实步数。
     * 不把间隔带进公式就会随交付间隔变化产生偏差（长间隔丢步、短间隔重复计步）。
     */
    private fun stepsInInterval(speed: Double, intervalSec: Double): Double =
        cadenceForSpeed(speed) / 60.0 * intervalSec

    /**
     * **虚拟方位数据源**：跟随帧得到的中轴 + 位置端生成器按事件率取样出的 ±3° 往复摆动。
     * 唯一运动学真值——旋转、地磁、合成航向全部从此派生。
     */
    private fun virtualAzimuth(): Double {
        // 中轴来自帧（位置端），摆动向位置端生成器按事件率取样——本进程不产生任何随机量
        val az = smoothBearing + FakeLoc.sampleBearingJitter()
        // 归一化：越界值会让 App 的方位处理路径分裂（部分 App 归一、部分不归一）
        return (az % 360.0 + 360.0) % 360.0
    }

    /**
     * 虚拟方位 → 各传感器的**表现形式**（同一数据源的一次派生）：
     * - 旋转（ORIENTATION/ROTATION_VECTOR/GAME）：欧拉角 / 绕世界 Z 轴四元数
     * - 地磁（MAGNETIC_FIELD[,_UNCALIBRATED]）：水平分量指向虚拟方位
     * - 重力（GRAVITY）：固定平放姿态（合成航向辅助输入）
     * 每次 tick 只调一次；世界常量（场强/倾角/bias）在进程内恒定。
     */
    private fun sensorValuesFor(type: Int): FloatArray {
        val azimuth = virtualAzimuth()
        return when (type) {
            TYPE_ORIENTATION -> orientationAngles(azimuth)
            TYPE_MAGNETIC_FIELD -> magneticVector(azimuth, uncalibrated = false)
            TYPE_MAGNETIC_FIELD_UNCALIBRATED -> magneticVector(azimuth, uncalibrated = true)
            TYPE_GRAVITY -> floatArrayOf(0f, 0f, 9.81f)
            TYPE_ACCELEROMETER, TYPE_ACCELEROMETER_UNCALIBRATED -> {
                // 平放姿态（同 GRAVITY）：消除真实加速度计倾角注入破坏融合投影
                floatArrayOf(0f, 0f, 9.81f, 0f, 0f, 0f)
            }
            TYPE_LINEAR_ACCELERATION -> floatArrayOf(0f, 0f, 0f)
            TYPE_ROTATION_VECTOR, TYPE_GAME_ROTATION_VECTOR,
            TYPE_GEOMAGNETIC_ROTATION_VECTOR -> rotationQuaternion(azimuth)
            TYPE_GYROSCOPE -> floatArrayOf(0f, 0f, gyroscopeZ())
            TYPE_GYROSCOPE_UNCALIBRATED -> floatArrayOf(
                0f, 0f, gyroscopeZ() + gyroDriftZ.toFloat(),
                gyroDriftX.toFloat(), gyroDriftY.toFloat(), gyroDriftZ.toFloat()
            )
            // 注入集合内每种类型都必须有显式数据源；遗漏宁可空数组（不发事件）也不静默套用四元数
            else -> FloatArray(0)
        }
    }

    /**
     * 陀螺仪角速度（平放绕设备 Z 轴，rad/s）：虚拟方位变化率 d(azimuth)/dt。
     *
     * 每次陀螺仪事件采样：方位差 / 事件间隔（±180° 环绕处理），再低通趋近——
     * 20Hz 方位节拍在任意事件频率下输出平滑的角速度曲线（转弯=短时角速度峰值，
     * 静止≈0），与磁力计/旋转族的方位变化完全协调，App 航向融合无冲突。
     */
    private fun gyroscopeZ(): Float {
        val azimuth = virtualAzimuth()
        val now = System.nanoTime()
        var raw = 0.0
        if (!lastAzimuthAngle.isNaN()) {
            val dt = (now - lastAzimuthNanos) / 1_000_000_000.0
            if (dt > 0.0) {
                var d = azimuth - lastAzimuthAngle
                if (d > 180.0) d -= 360.0
                if (d < -180.0) d += 360.0
                raw = Math.toRadians(d) / dt
            }
        }
        lastAzimuthNanos = now
        lastAzimuthAngle = azimuth
        smoothGyroZ += (raw - smoothGyroZ) * GYRO_SMOOTH_ALPHA
        return smoothGyroZ.toFloat()
    }

    /** 朝向（欧拉角形式）：values[0] = 方位角（度，0=北，顺时针） */
    private fun orientationAngles(azimuth: Double): FloatArray = floatArrayOf(azimuth.toFloat(), 0f, 0f)

    /** 旋转（四元数形式）：绕世界 Z 轴 [0, 0, sin(θ/2), cos(θ/2)]（API 18+ 含 w） */
    private fun rotationQuaternion(azimuth: Double): FloatArray {
        val theta = Math.toRadians(azimuth)
        val z = (-Math.sin(theta / 2.0)).toFloat()
        val w = Math.cos(theta / 2.0).toFloat()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            floatArrayOf(0f, 0f, z, w)
        } else {
            floatArrayOf(0f, 0f, z)
        }
    }

    /**
     * 指南针（地磁矢量形式）：水平分量指向虚拟方位。手机平放时
     * getRotationMatrix(gravity=[0,0,g], magnetic=[mX,mY,mZ]) 内部 H = E×A、
     * M = A×H，方位角 = atan2(Hy, My) = atan2(-mX, mY)，
     * 故取 mX = -H·sinθ, mY = +H·cosθ → 方位角 = θ（与旋转族同一虚拟方位）。
     * mZ 为负 = 北半球地磁向下（设备 Z 轴朝上）。
     * 场强 H/磁倾角 dip 为虚拟世界常量（进程内恒定）；未校准 = 校准值 + 恒定 bias（6 通道）。
     */
    private fun magneticVector(azimuth: Double, uncalibrated: Boolean): FloatArray {
        val theta = Math.toRadians(azimuth)
        val mx = (-magneticH * Math.sin(theta)).toFloat()
        val my = (magneticH * Math.cos(theta)).toFloat()
        val mz = (-magneticH * magneticDip).toFloat()
        return if (uncalibrated) {
            floatArrayOf(
                mx + magneticBiasX.toFloat(), my + magneticBiasY.toFloat(), mz,
                magneticBiasX.toFloat(), magneticBiasY.toFloat(), 0f
            )
        } else {
            floatArrayOf(mx, my, mz)
        }
    }

    // ------------------------------------------------------------------
    // 伪造步计数传感器（系统缺失时暴露给 App）
    // ------------------------------------------------------------------

    @SuppressLint("SoonBlockedPrivateApi")
    private fun createFakeStepSensor() {
        fakeStepSensor = buildFakeStepSensor("Step Counter Sensor", TYPE_STEP_COUNTER)
        fakeStepDetectorSensor = buildFakeStepSensor("Step Detector Sensor", TYPE_STEP_DETECTOR)
    }

    private fun buildFakeStepSensor(displayName: String, sensorType: Int): Sensor? {
        try {
            val sensorClass = Class.forName("android.hardware.Sensor")
            val constructor = sensorClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val sensor = constructor.newInstance() as Sensor
            sensorClass.getDeclaredField("mType").apply {
                isAccessible = true
                setInt(sensor, sensorType)
            }

            fun setField(name: String, value: Any) {
                runCatching {
                    val field = sensorClass.getDeclaredField(name)
                    field.isAccessible = true
                    when (value) {
                        is String -> field.set(sensor, value)
                        is Int -> field.setInt(sensor, value)
                        is Float -> field.setFloat(sensor, value)
                        else -> field.set(sensor, value)
                    }
                }.onFailure {
                    if (FakeLoc.enableDebugLog) Logger.debug("fake sensor field $name failed: ${it.message}")
                }
            }

            setField("mName", displayName)
            setField(
                "mStringType",
                if (sensorType == TYPE_STEP_DETECTOR) "android.sensor.step_detector"
                else "android.sensor.step_counter"
            )
            setField("mMaxRange", Float.MAX_VALUE)
            setField("mResolution", 1f)
            setField("mVendor", "Google Inc.")
            setField("mVersion", 3)
            setField("mPower", 0.5f)
            setField("mMinDelay", 0)
            setField("mMaxDelay", 0)
            setField("mFifoReservedEventCount", 0)
            setField("mFifoMaxEventCount", 0)
            setField("mRequiredPermission", "")
            setField("mFlags", 0x00000003)
            setField("mId", System.identityHashCode(sensor))
            setField("mHandle", System.identityHashCode(sensor))

            return sensor
        } catch (t: Throwable) {
            XposedBridge.log("[Portal] create fake step sensor failed: ${t.message}")
            return null
        }
    }

    // ------------------------------------------------------------------
    // 伪造传感器暴露：getDefaultSensor / getSensorList / getFullSensorsList
    // ------------------------------------------------------------------

    private fun hookSensorExposure(classLoader: ClassLoader) {
        // 具体实现类（abstract 的 SensorManager 方法无法直接 hook）
        val cSystemSensorManager = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader)

        fun exposeSensorInList(result: Any?, type: Int) {
            val list = result as? MutableList<Sensor> ?: return
            fun addIfMissing(sensor: Sensor?, sensorType: Int) {
                if (sensor == null) return
                if (type == sensorType || type == Sensor.TYPE_ALL) {
                    if (!list.any { it.type == sensorType }) {
                        list.add(sensor)
                        if (FakeLoc.enableDebugLog) Logger.debug("injected fake step sensor($sensorType) into list")
                    }
                }
            }
            addIfMissing(fakeStepSensor, TYPE_STEP_COUNTER)
            addIfMissing(fakeStepDetectorSensor, TYPE_STEP_DETECTOR)
        }

        cSystemSensorManager?.declaredMethods?.filter {
            it.name == "getDefaultSensor" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.forEach { m ->
            m.onceHook(afterHook {
                val type = args[0] as Int
                if (result == null) {
                    result = when (type) {
                        TYPE_STEP_COUNTER -> fakeStepSensor
                        TYPE_STEP_DETECTOR -> fakeStepDetectorSensor
                        else -> null
                    }
                }
            })
        }

        cSystemSensorManager?.declaredMethods?.filter {
            it.name == "getSensorList" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.forEach { m ->
            m.onceHook(afterHook {
                exposeSensorInList(result, args[0] as Int)
            })
        }

        cSystemSensorManager?.declaredMethods?.filter {
            it.name == "getFullSensorsList"
        }?.forEach { m ->
            m.onceHook(afterHook {
                exposeSensorInList(result, Sensor.TYPE_ALL)
            })
        }

        // 老版本 SensorManager 上直接声明实现时兜底（runCatching：abstract 时忽略）
        val cSensorManager = XposedHelpers.findClassIfExists("android.hardware.SensorManager", classLoader)
        cSensorManager?.declaredMethods?.filter {
            (it.name == "getDefaultSensor" || it.name == "getSensorList") && it.parameterTypes.size == 1
        }?.forEach { m ->
            runCatching {
                m.onceHook(afterHook {
                    val type = args[0] as Int
                    when (m.name) {
                        "getDefaultSensor" -> if (result == null) {
                            result = when (type) {
                                TYPE_STEP_COUNTER -> fakeStepSensor
                                TYPE_STEP_DETECTOR -> fakeStepDetectorSensor
                                else -> null
                            }
                        }
                        "getSensorList" -> exposeSensorInList(result, type)
                    }
                })
            }.onFailure { /* abstract 或不可 hook，忽略 */ }
        }
    }

    // ------------------------------------------------------------------
    // registerListener 拦截（主路径）：记录 listener，阻止真实注册
    // ------------------------------------------------------------------

    private fun hookRegisterListener(classLoader: ClassLoader) {
        val cSensorManager = XposedHelpers.findClassIfExists("android.hardware.SensorManager", classLoader)
            ?: return

        cSensorManager.declaredMethods
            .filter { m ->
                m.name == "registerListener" &&
                        m.parameterTypes.any { SensorEventListener::class.java.isAssignableFrom(it) } &&
                        m.parameterTypes.any { Sensor::class.java.isAssignableFrom(it) }
            }
            .forEach { m ->
                runCatching {
                    m.onceHook(beforeHook {
                        val sensor = args.firstOrNull { it is Sensor } as? Sensor ?: return@beforeHook
                        if (sensor.type !in INJECTABLE_SENSOR_TYPES) return@beforeHook
                        // 放行真实注册：事件由系统回调送达，本进程在 dispatchSensorEvent 中改写
                        runCatching {
                            val handle = XposedHelpers.callMethod(sensor, "getHandle") as? Int
                            if (handle != null) sensorHandleTypeMap[handle] = sensor.type
                        }
                        // 步数（计数器/检测器）：记录 listener——on-change 类型无持续回调，
                        // 改由位置回调驱动投递（两者共用同一步事件源，不会互相矛盾）
                        if (sensor.type == TYPE_STEP_COUNTER || sensor.type == TYPE_STEP_DETECTOR) {
                            val listener = args.firstOrNull { it is SensorEventListener } as? SensorEventListener
                            val handler = args.firstOrNull { it is Handler } as? Handler
                            if (listener != null) stepListeners.add(StepListener(listener, handler, sensor))
                        }
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("registerListener: type=${sensor.type}, handle→type recorded")
                        }
                    })
                }.onFailure {
                    if (FakeLoc.enableDebugLog) Logger.debug("hook registerListener(${m.parameterTypes.joinToString()}) failed: ${it.message}")
                }
            }
    }

    // ------------------------------------------------------------------
    // dispatchSensorEvent：真实事件流到达时改写数据（主路径）
    // ------------------------------------------------------------------

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        val queueClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: XposedHelpers.findClassIfExists("android.hardware.SensorManager\$SensorEventQueue", classLoader)
            ?: run {
                Logger.debug("SensorEventQueue class NOT FOUND")
                return
            }

        val dispatchMethods = queueClass.declaredMethods.filter { it.name == "dispatchSensorEvent" }
        dispatchMethods.forEach { m ->
            m.onceHook(beforeHook {
                injectClientEvent(args)
            })
        }
    }

    /**
     * 真实回调改写（主路径）：按 handle→type 分流——
     * 朝向类覆盖为模拟 bearing 对应的旋转数据（不随真实转动变化）；
     * 步数只改写为模拟真值（真值由位置回调推进，避免双重计数）。
     */
    private fun injectClientEvent(args: Array<Any?>) {
        if (args.size < 4) return
        val handle = args[0] as? Int ?: return
        val values = args[1] as? FloatArray ?: return
        if (values.isEmpty()) return

        val type = sensorHandleTypeMap[handle] ?: return
        if (type in HEADING_SENSOR_TYPES) {
            advanceMotionForEvent()
            val mock = sensorValuesFor(type)
            for (i in mock.indices) {
                if (i < values.size) values[i] = mock[i]
            }
            // 每采样独立小噪声（真机每个采样都带自己的测量噪声）
            addSampleNoise(type, values)
            return
        }
        if (type == TYPE_STEP_DETECTOR) {
            // 步检测器：每步一次，值恒为 1.0（检测语义），与步数计数器的推进严格同源
            values[0] = 1.0f
            return
        }
        if (type != TYPE_STEP_COUNTER) return

        // 步数计数器：真实事件（on-change，设备走路时才有）只改写为模拟真值
        values[0] = globalSteps.get().toFloat()
    }

    private fun addSampleNoise(type: Int, values: FloatArray) {
        /** 每采样独立小噪声：真机每个采样都带自己的测量噪声，相邻采样不会逐位相同。
         *  幅度按传感器物理量级取：角度 0.15°、磁矢量 0.3µT、加速度 0.01 m/s²、角速度 0.005 rad/s。 */
        fun noiseAt(index: Int, amplitude: Float) {
            if (index < values.size) {
                values[index] += (kotlin.random.Random.nextFloat() * 2f - 1f) * amplitude
            }
        }
        when (type) {
            TYPE_ORIENTATION -> noiseAt(0, 0.15f)
            TYPE_ROTATION_VECTOR, TYPE_GAME_ROTATION_VECTOR, TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                noiseAt(0, 0.0015f); noiseAt(1, 0.0015f); noiseAt(2, 0.0015f)
            }
            TYPE_MAGNETIC_FIELD, TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                noiseAt(0, 0.3f); noiseAt(1, 0.3f); noiseAt(2, 0.3f)
            }
            TYPE_GRAVITY, TYPE_ACCELEROMETER, TYPE_LINEAR_ACCELERATION -> {
                noiseAt(0, 0.01f); noiseAt(1, 0.01f); noiseAt(2, 0.01f)
            }
            TYPE_GYROSCOPE, TYPE_GYROSCOPE_UNCALIBRATED -> noiseAt(2, 0.005f)
        }
    }

    /**
     * 步数推进（位置回调驱动）：步数传感器是 on-change 类型，设备不走路就没有真实事件，
     * 无法靠回调改写驱动——位置回调才是步数真正的对应数据源（持续、反映实际移动），
     * 按当前步频推进模拟步数并主动投递给已注册的步数 listener。
     */
    private fun advanceStepsFromLocation() {
        val now = System.nanoTime()
        val last = lastStepAdvanceNanos
        lastStepAdvanceNanos = now
        if (last == 0L) return
        val dtSec = (now - last) / 1_000_000_000.0
        if (dtSec <= 0.0 || dtSec > 10.0) return
        if (!movingCache) return

        val whole: Int
        synchronized(stepLock) {
            stepFraction += stepsInInterval(speedCache, dtSec)
            whole = stepFraction.toInt()
            if (whole < 1) return
            stepFraction -= whole
        }
        val baseCount = globalSteps.get()
        globalSteps.addAndGet(whole)
        // 步数：真机 TYPE_STEP_COUNTER 是 on-change——每发生一步就发一次（步行时 ~1.5~3 Hz），
        // 与位置回调率无关。这里把本间隔累积的每一步**按步间隔分摊投递**：
        // 事件率回到真实步率，每步的时间戳落在各自步点（应用按事件时间算步频也准）。
        val perStepNanos = ((dtSec * 1_000_000_000.0) / whole).toLong().coerceAtLeast(1L)
        var timestamp = now - perStepNanos * whole
        for (step in 1..whole) {
            timestamp += perStepNanos
            emitStepEvent(baseCount + step, timestamp)
        }
    }

    /**
     * 向步数 listener 投递一次步事件：计数器 listener 收到累计步数，检测器 listener 收到 1.0
     * （**同一次步事件、同一时间戳**——两者不可能对不上）。
     */
    private fun emitStepEvent(count: Int, timestampNanos: Long) {
        if (stepListeners.isEmpty()) return
        for (reg in stepListeners) {
            val value = if (reg.sensor.type == TYPE_STEP_DETECTOR) 1.0f else count.toFloat()
            val event = createSensorEvent(floatArrayOf(value), reg.sensor, timestampNanos) ?: continue
            try {
                val handler = reg.handler
                if (handler != null) {
                    handler.post { runCatching { reg.listener.onSensorChanged(event) } }
                } else {
                    reg.listener.onSensorChanged(event)
                }
            } catch (t: Throwable) {
                XposedBridge.log("[Portal] step listener callback failed: ${t.message}")
            }
        }
    }

    /** 泛化 SensorEvent 构造：按值数组长度分配，sensor 字段挂注册时的真实 Sensor */
    private fun createSensorEvent(
        values: FloatArray,
        sensor: Sensor?,
        timestampNanos: Long = System.nanoTime()
    ): SensorEvent? {
        return try {
            val event: SensorEvent = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                SensorEvent::class.java.getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(values.size)
            } else {
                SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }.newInstance(values.size)
            }
            SensorEvent::class.java.getDeclaredField("values").apply {
                isAccessible = true
                set(event, values)
            }
            event.timestamp = timestampNanos
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            SensorEvent::class.java.getDeclaredField("sensor").apply {
                isAccessible = true
                if (sensor != null) set(event, sensor)
            }
            event
        } catch (t: Throwable) {
            if (FakeLoc.enableDebugLog) Logger.debug("create SensorEvent failed: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // 数据获取：在应用注册的监听器回调上取数（标准字段，不依赖系统端 extras）
    // 取数点 = 应用进程内**应用自己发起的位置获取**：
    //   requestLocationUpdates/requestSingleUpdate（LocationListener.onLocationChanged）、
    //   getCurrentLocation（SDK 30+ 走 **Consumer.accept**，不是 onLocation）、
    //   getLastKnownLocation（同步返回）。
    // 位置对象即注入位置，速度/朝向/移动标志从**标准字段**读取
    // （location.speed/location.bearing），不依赖系统端私有 extras（spd/brg/mov）。
    // 回调一律按**形态**（accept / onLocation / onLocationChanged）识别——
    // 不按方法名写死、不按参数位置写死：写死过一次（只认 onLocation），
    // 一次性取位这条链整条失效，应用若只走一次性取位，本 hook 拿不到任何运动学数据。
    // ------------------------------------------------------------------

    private fun hookSpeedSync(classLoader: ClassLoader) {
        val cLocationManager = XposedHelpers.findClassIfExists("android.location.LocationManager", classLoader)
            ?: return

        /**
         * 在应用注册的监听器回调上获取数据：只读位置对象标准字段。
         * 速度/朝向由系统注入位置的**标准字段**携带（BaseLocationHook 写入
         * location.speed/location.bearing），本进程在此取数，无私有数据通路。
         * 字段缺失时按位移推算——避免数据源冻结（方位/速度停在旧值）。
         */
        fun syncFromLocation(loc: Location) {
            val hasLast = !lastSyncLat.isNaN() && !lastSyncLon.isNaN()
            val dLat = if (hasLast) loc.latitude - lastSyncLat else 0.0
            val dLon = if (hasLast) loc.longitude - lastSyncLon else 0.0
            val distM = if (hasLast) {
                Math.hypot(dLat, dLon * Math.cos(Math.toRadians(loc.latitude))) * 111320.0
            } else 0.0

            // 速度：标准字段优先；缺失时按位移推算（步频/摆动幅度有源）
            if (loc.hasSpeed()) {
                speedCache = loc.speed.toDouble()
            } else if (distM >= 0.5) {
                speedCache = distM
            }

            // 朝向：标准字段优先；缺失时按位移方向推算（避免方位冻结）
            if (loc.hasBearing()) {
                bearingTarget = loc.bearing.toDouble()
            } else if (distM >= 1.0) {
                val east = dLon * Math.cos(Math.toRadians(loc.latitude))
                bearingTarget = (Math.toDegrees(Math.atan2(east, dLat)) + 360.0) % 360.0
            }

            // 移动判定：标准字段优先，否则位移判定
            movingCache = if (loc.hasSpeed()) {
                loc.speed > 0.05f
            } else {
                distM >= 1.0
            }

            lastSyncLat = loc.latitude
            lastSyncLon = loc.longitude

            // 步数：位置回调是步数的对应数据源（持续、反映实际移动），在此驱动步数推进
            advanceStepsFromLocation()
        }

        val hookSpeed = beforeHook {
            val loc = args[0] as? Location ?: return@beforeHook
            syncFromLocation(loc)
        }

        val hookRequestLocationUpdates = beforeHook {
            if (args.isEmpty()) return@beforeHook
            args.filterIsInstance<android.location.LocationListener>().forEach { listener ->
                listener.javaClass.onceHookAllMethod("onLocationChanged", hookSpeed)
            }
        }
        cLocationManager.declaredMethods.filter {
            it.name == "requestLocationUpdates" || it.name == "requestSingleUpdate"
        }.forEach {
            it.onceHook(hookRequestLocationUpdates)
        }

        // 一次性取位的回调体：位置对象就是同步源
        val syncOnLocationArg = beforeHook {
            val loc = args.firstOrNull { it is Location } as? Location ?: return@beforeHook
            syncFromLocation(loc)
        }

        /** 按回调形态挂同步：Consumer.accept / ILocationCallback.onLocation / LocationListener.onLocationChanged */
        fun syncCallbackShape(arg: Any?) {
            if (arg == null) return
            val methods = arg.javaClass.methods
            when {
                methods.any { it.name == "accept" } ->
                    arg.javaClass.onceHookAllMethod("accept", syncOnLocationArg)
                methods.any { it.name == "onLocation" } ->
                    arg.javaClass.onceHookAllMethod("onLocation", syncOnLocationArg)
                methods.any { it.name == "onLocationChanged" } ->
                    arg.javaClass.onceHookAllMethod("onLocationChanged", syncOnLocationArg)
            }
        }

        // getCurrentLocation（SDK 30+）：回调是 Consumer<Location>（accept），对所有重载生效
        cLocationManager.declaredMethods.filter { it.name == "getCurrentLocation" }.forEach { m ->
            m.onceHook(beforeHook { args.forEach { syncCallbackShape(it) } })
        }

        // getLastKnownLocation（同步返回的一次性取位，应用可见 API）/
        // getLastLocation（隐藏重载，部分系统内部路径）——两者都补同步链。
        cLocationManager.declaredMethods.filter {
            it.name == "getLastKnownLocation" || it.name == "getLastLocation"
        }.forEach { m ->
            m.onceHook(afterHook {
                val loc = result as? Location ?: return@afterHook
                syncFromLocation(loc)
            })
        }
    }
}
