@file:Suppress("UNCHECKED_CAST", "PrivateApi", "DiscouragedPrivateApi")
package moe.fuqiuluo.xposed.hooks.sensor

import android.annotation.SuppressLint
import android.content.pm.FeatureInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.ArrayMap
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookMethodAfter
import moe.fuqiuluo.xposed.utils.onceHook
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 传感器模拟 hook（客户端主动注入方案，融合 UseVector 主动注入能力）。
 *
 * 开关：FakeLoc.sensorMockEnabled（默认开）——关闭时本模块不安装任何 hook（禁用模拟）。
 * 注：服务端（SensorService 源级改写）方案已弃用，只保留本客户端方案。
 *
 * 关键事实：`android.hardware.SystemSensorManager`/`SensorManager` 是 SDK 客户端类，
 * 运行在**每一个 app 进程**内，而不是 system_server。
 * 安装范围（见 FakeLocation.handleLoadPackage）：仅对被选中的用户应用（LSPosed 作用域内）
 * 进程安装；system_server/phone 等系统框架进程不装——避免拦截系统服务自身的传感器注册
 * （自动旋转、计步统计等）导致系统行为被污染。
 *
 * 注入对象 = 步数 + 朝向，被选中的用户应用**永远**收到虚拟传感器数据：
 * - 朝向：模拟 bearing（应用启动时随机分配中心角度；移动时摇杆/自动播放更新为
 *   移动方向）+ 步频同步摆动；静止时保持稳定（不随真实设备转动、不自动旋转）
 * - 步数：移动中按速度 cadence 推进；静止时停留（不增长）
 * - 无真实传感器设备同样工作（虚拟注入不依赖真实传感器存在）
 *
 * 三种机制配合，覆盖有/无真实传感器的设备，注入对象 = 步数 + 朝向：
 * 1. **主动注入（主路径，参考 UseVector）**：拦截 registerListener（result=true 阻止
 *    真实注册），单线程调度器按需周期性构造 SensorEvent 直接回调 listener：
 *    步数按步频、朝向按固定 20Hz（旋转数据来自模拟 bearing）——没有步数传感器也能工作。
 * 2. **伪造传感器暴露**：getDefaultSensor/getSensorList/getFullSensorsList 在系统
 *    缺失步计数器时注入伪造对象（朝向传感器一般真实存在，无需伪造）。
 * 3. **dispatchSensorEvent 改写（兜底）**：若拦截未生效（特殊 ROM 走了其他路径），
 *    真实事件流到达时改写 values，保持步频/朝向一致。
 *
 * 朝向注入的意义：运动世界等跑步 App 的方向由旋转传感器（ORIENTATION/
 * ROTATION_VECTOR/GAME_ROTATION_VECTOR）驱动，而非 Location.bearing——
 * 注入后 App 方向跟随模拟朝向（经注入位置的 extras 同步），不随真实设备转动变化。
 *
 * 步频模型 = 移动速度直接换算（线性）：
 *   cadence(步/min) = 60 + 30 * speed(m/s)，限幅 60..220 —— 走路/跑步速度对应：
 *   慢走 1.2m/s → 96，快走 1.5 → 105，慢跑 3.0 → 150，快跑 4.5+ → 195+（封顶 220）
 *
 * 速度/朝向来源：权威值在 system_server（FakeLoc.speed/FakeLoc.bearing）。本进程通过 hook 定位
 * 回调，从注入位置的 extras（portal_speed，见 BaseLocationHook）同步速度缓存。
 */
object SystemSensorManagerHook {
    private const val TYPE_STEP_COUNTER = 19
    private const val TYPE_ORIENTATION = 3
    private const val TYPE_ROTATION_VECTOR = 11
    private const val TYPE_GAME_ROTATION_VECTOR = 15
    private const val EXTRA_PORTAL_SPEED = "portal_speed"
    private const val EXTRA_PORTAL_BEARING = "portal_bearing"
    private const val EXTRA_PORTAL_MOVING = "portal_moving"

    // 需要注入的传感器类型（步数 + 朝向）：
    // 朝向类（ORIENTATION/ROTATION_VECTOR/GAME_ROTATION_VECTOR）驱动 App 方向——
    // 运动世界等 App 用旋转传感器而非 Location.bearing 决定朝向，
    // 注入后方向跟随模拟 bearing，不随真实设备转动变化。
    private val INJECTABLE_SENSOR_TYPES = setOf(
        TYPE_STEP_COUNTER, TYPE_ORIENTATION, TYPE_ROTATION_VECTOR, TYPE_GAME_ROTATION_VECTOR
    )
    private val ROTATION_SENSOR_TYPES = setOf(
        TYPE_ORIENTATION, TYPE_ROTATION_VECTOR, TYPE_GAME_ROTATION_VECTOR
    )

    // 朝向注入固定频率（20Hz，地图旋转流畅；步数在同一 tick 下按 cadence 浮动推进）
    private const val ROTATION_INJECT_MS = 50L

    // listener -> 其注册线程的 Handler / 实际 Sensor 对象 / 传感器类型
    private data class RegisteredListener(
        val listener: SensorEventListener,
        val handler: Handler?,
        val sensor: Sensor,
        val sensorType: Int
    )

    private val registeredListeners = CopyOnWriteArraySet<RegisteredListener>()

    // sensor handle -> sensor type（dispatchSensorEvent 只提供 handle）
    private val sensorHandleTypeMap = ConcurrentHashMap<Int, Int>()

    // 全局累计步数（随机起点，模拟"已经走了不少"）
    private val globalSteps = AtomicInteger(kotlin.random.Random.nextInt(3000, 12000))

    // 调度器（单线程，与 UseVector 一致）
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Portal-StepInjector").apply { isDaemon = true }
    }
    private var currentScheduledFuture: ScheduledFuture<*>? = null

    // 伪造的步计数传感器（无真实传感器时暴露给 App；事件 sensor 字段用注册时的真实 Sensor）
    private var fakeStepSensor: Sensor? = null

    // 速度/朝向/移动状态缓存（m/s | 度 | 是否移动中）——来自注入位置 extras（跨进程同步）
    @Volatile private var speedCache = 1.5
    @Volatile private var bearingCache = 0.0
    @Volatile private var movingCache = false

    // 统一调度 tick 状态：步数按 cadence 浮动累计（每整步触发事件）
    @Volatile private var lastTickNanos = System.nanoTime()
    private var stepFraction = 0.0

    // 朝向摆动（蜜罐规避）：平滑曲线（余弦波）往复。
    // - 中轴恒定 = 模拟朝向（bearingCache），注入值 = 中轴 + swingOffset
    // - 波峰→波谷（或反过来）为一个更新点：半周期完成时峰谷交替，
    //   朝中间值的另一边随机抽取目标幅度——
    //   幅度与速度联动：中心 = 5.0 + 0.35*speed（慢走~5.4°，快跑~6.6°），
    //   范围 = 中心 ± 0.5°（真实跑步躯干晃动随速度增大）
    // - 半周期持续时间 = 最新的两次步频之间的时间间隔（每步频+1 时存储）
    // - 曲线平滑：swingOffset = side * amp * cos(π * phase)，半周期内从峰到谷
    @Volatile private var swingOffset = 0.0
    @Volatile private var swingSide = 1.0          // 当前峰值所在侧（+1/-1）
    @Volatile private var swingAmp = 5.5            // 当前目标峰值幅度（5~6 随机）
    @Volatile private var swingPhaseStartNanos = System.nanoTime()
    @Volatile private var swingHalfPeriodNanos = 500_000_000L // 半周期时长（初始 500ms）

    // 步频间隔存储：每次步频 +1 时记录（供摆动半周期时长使用）
    @Volatile private var lastStepTimestampNanos = 0L

    /**
     * 平滑曲线推进：半周期完成 = 更新点 → 峰谷交替、与速度联动的随机目标幅度、
     * 半周期重新计时。曲线：offset = side * amp * cos(π * phase)，phase∈[0,1]（峰→谷）。
     */
    private fun advanceSwing() {
        // 静止（未操作摇杆/未自动播放）：摆动指数衰减归零——朝向稳定，指针不乱转
        if (!movingCache) {
            swingOffset *= 0.5
            if (kotlin.math.abs(swingOffset) < 0.05) {
                swingOffset = 0.0
            }
            return
        }
        val now = System.nanoTime()
        var elapsed = now - swingPhaseStartNanos
        if (elapsed >= swingHalfPeriodNanos) {
            // 更新点：峰谷交替，朝中间值的另一边随机抽取目标幅度
            // 幅度与速度联动（越快摆动越大）：中心 = 5.0 + 0.35*speed，范围 = 中心 ± 0.5°
            swingSide = -swingSide
            val swingCenter = 5.0 + 0.35 * speedCache
            swingAmp = kotlin.random.Random.nextDouble(swingCenter - 0.5, swingCenter + 0.5)
            swingPhaseStartNanos = now
            elapsed = 0
        }
        val phase = (elapsed.toDouble() / swingHalfPeriodNanos).coerceIn(0.0, 1.0)
        swingOffset = swingSide * swingAmp * Math.cos(Math.PI * phase)
    }

    /**
     * 步频 +1：存储两次步频之间的时间间隔（过滤异常值），
     * 作为下一次波峰-波谷（或反过来）的持续时间。
     */
    private fun onStepAdvance() {
        val now = System.nanoTime()
        val interval = now - lastStepTimestampNanos
        lastStepTimestampNanos = now
        if (interval in 100_000_000L..3_000_000_000L) {
            swingHalfPeriodNanos = interval
        }
    }

    // dispatchSensorEvent 兜底状态（每进程独立）
    private val lastStepCount = AtomicLong(0)
    @Volatile private var lastEventTimeNanos = 0L

    operator fun invoke(classLoader: ClassLoader) {
        // 传感器模拟开关（默认开）：关闭则完全不安装传感器 hook（禁用模拟）
        if (!FakeLoc.sensorMockEnabled) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("sensor mock disabled by switch")
            }
            return
        }
        // 客户端主动注入（A 方案）：SystemSensorManager 是 SDK 客户端类，
        // 运行在每个 app 进程内，因此每个进程都要安装。
        // 注：服务端（SensorService 源级改写）方案已弃用。
        unlockGeoSensor(classLoader)

        createFakeStepSensor()
        hookSensorExposure(classLoader)
        hookRegisterListener(classLoader)
        hookUnregisterListener(classLoader)
        hookSystemSensorManagerQueue(classLoader)
        hookSpeedSync(classLoader)
    }

    /** 步频-移动速度线性模型（步/min）：cadence = 60 + 30*speed，限幅 60..220 */
    private fun cadenceForSpeed(speed: Double): Int {
        return (60.0 + 30.0 * speed).toInt().coerceIn(60, 220)
    }

    private fun hasStepListener(): Boolean = registeredListeners.any { it.sensorType == TYPE_STEP_COUNTER }

    private fun hasRotationListener(): Boolean = registeredListeners.any { it.sensorType in ROTATION_SENSOR_TYPES }

    /**
     * 下一次事件间隔（ms）：
     * 有朝向监听者 → 固定 50ms（20Hz，朝向流畅；步数在同一 tick 下按 cadence 浮动推进）；
     * 只有步数 → 步频间隔 + ±10% 抖动（参考 UseVector）。
     */
    private fun nextDelayMs(): Long {
        if (hasRotationListener()) {
            return ROTATION_INJECT_MS
        }
        val cadence = cadenceForSpeed(speedCache)
        val intervalMs = 60000L / cadence
        val jitter = (intervalMs * (kotlin.random.Random.nextDouble() - 0.5) * 0.2).toLong()
        return (intervalMs + jitter).coerceAtLeast(10)
    }

    // ------------------------------------------------------------------
    // 主动注入：调度器周期性回调 listener（UseVector 主路径）
    // ------------------------------------------------------------------

    private fun startInjectorIfNeeded() {
        synchronized(this) {
            if (currentScheduledFuture != null && !currentScheduledFuture!!.isDone) return
            currentScheduledFuture = scheduler.schedule(::injectOnce, nextDelayMs(), TimeUnit.MILLISECONDS)
        }
        if (FakeLoc.enableDebugLog) {
            Logger.debug("step injector started")
        }
    }

    private fun stopInjector() {
        synchronized(this) {
            currentScheduledFuture?.cancel(false)
            currentScheduledFuture = null
        }
    }

    private fun injectOnce() {
        try {
            if (registeredListeners.isEmpty()) return

            // 统一 tick：步数与朝向在同一次调度中按各自需求注入（保持一种结构）
            val now = System.nanoTime()
            val dtSec = ((now - lastTickNanos) / 1_000_000_000.0).coerceIn(0.0, 1.0)
            lastTickNanos = now

            // 步进：移动中按当前速度步频浮动累计（有步数监听或朝向监听时都推进，
            // 朝向摆动与步频同步）；静止时步数停留不增长
            if ((hasStepListener() || hasRotationListener()) && movingCache) {
                stepFraction += cadenceForSpeed(speedCache) / 60.0 * dtSec
                val wholeSteps = stepFraction.toInt()
                if (wholeSteps >= 1) {
                    stepFraction -= wholeSteps
                    globalSteps.addAndGet(wholeSteps)
                    if (hasStepListener()) {
                        emitEvent(TYPE_STEP_COUNTER, FloatArray(1) { globalSteps.get().toFloat() })
                        if (FakeLoc.enableDebugLog && globalSteps.get() % 200 == 0) {
                            Logger.debug("step injector: total=${globalSteps.get()} cadence=${cadenceForSpeed(speedCache)}/min speed=${speedCache}")
                        }
                    }
                    // 步频 +1：存储两次步频间隔（供摆动半周期持续时长，蜜罐规避）
                    if (hasRotationListener()) {
                        onStepAdvance()
                    }
                }
            }

            // 朝向：每 tick 注入模拟 bearing（+步频同步摆动）的旋转数据
            // （静止时摆动归零 → 注入值 = 稳定中轴，指针不动；永不透传真实值）
            if (hasRotationListener()) {
                advanceSwing()
                emitRotationEvents()
            }
        } catch (t: Throwable) {
            XposedBridge.log("[Portal] sensor injector failed: ${t.message}")
        } finally {
            synchronized(this) {
                if (registeredListeners.isNotEmpty()) {
                    currentScheduledFuture = scheduler.schedule(::injectOnce, nextDelayMs(), TimeUnit.MILLISECONDS)
                } else {
                    currentScheduledFuture = null
                }
            }
        }
    }

    /** 向指定类型的监听者发射事件 */
    private fun emitEvent(type: Int, values: FloatArray) {
        val sensor = registeredListeners.firstOrNull { it.sensorType == type }?.sensor
        val event = createSensorEvent(values, sensor) ?: return
        for (reg in registeredListeners) {
            if (reg.sensorType != type) continue
            deliverEvent(reg, event)
        }
    }

    /** 向朝向类监听者分别发射事件（每个用自己注册的 Sensor 对象） */
    private fun emitRotationEvents() {
        for (reg in registeredListeners) {
            if (reg.sensorType !in ROTATION_SENSOR_TYPES) continue
            val event = createSensorEvent(rotationValuesFor(reg.sensorType), reg.sensor) ?: continue
            deliverEvent(reg, event)
        }
    }

    private fun deliverEvent(reg: RegisteredListener, event: SensorEvent) {
        try {
            val handler = reg.handler
            if (handler != null) {
                handler.post { runCatching { reg.listener.onSensorChanged(event) } }
            } else {
                reg.listener.onSensorChanged(event)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[Portal] sensor listener callback failed: ${t.message}")
        }
    }

    /**
     * 模拟 bearing → 旋转传感器数据：
     * - TYPE_ORIENTATION：values[0] = 方位角（度，0=北，顺时针）
     * - TYPE_ROTATION_VECTOR / GAME_ROTATION_VECTOR：绕世界 Z 轴旋转的四元数虚部
     *   [0, 0, sin(θ/2), cos(θ/2)]（API 18+ 含 w；更老版本 3 元素无 w）
     */
    private fun rotationValuesFor(type: Int): FloatArray {
        // 模拟朝向 + 步频同步摆动（蜜罐规避）
        val azimuth = bearingCache + swingOffset
        return when (type) {
            TYPE_ORIENTATION -> floatArrayOf(azimuth.toFloat(), 0f, 0f)
            TYPE_GAME_ROTATION_VECTOR -> {
                val theta = Math.toRadians(azimuth)
                floatArrayOf(0f, 0f, (-Math.sin(theta / 2.0)).toFloat(), Math.cos(theta / 2.0).toFloat())
            }
            else -> { // TYPE_ROTATION_VECTOR
                val theta = Math.toRadians(azimuth)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    floatArrayOf(0f, 0f, (-Math.sin(theta / 2.0)).toFloat(), Math.cos(theta / 2.0).toFloat())
                } else {
                    floatArrayOf(0f, 0f, (-Math.sin(theta / 2.0)).toFloat())
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 伪造 Sensor 与 SensorEvent（参考 UseVector）
    // ------------------------------------------------------------------

    @SuppressLint("SoonBlockedPrivateApi")
    private fun createFakeStepSensor() {
        try {
            val sensorClass = Class.forName("android.hardware.Sensor")
            val constructor = sensorClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val sensor = constructor.newInstance() as Sensor
            sensorClass.getDeclaredField("mType").apply {
                isAccessible = true
                setInt(sensor, TYPE_STEP_COUNTER)
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

            setField("mName", "Step Counter Sensor")
            setField("mStringType", "android.sensor.step_counter")
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

            fakeStepSensor = sensor
        } catch (t: Throwable) {
            XposedBridge.log("[Portal] create fake step sensor failed: ${t.message}")
        }
    }

    /** 泛化 SensorEvent 构造：按值数组长度分配，sensor 字段挂注册时的真实 Sensor */
    private fun createSensorEvent(values: FloatArray, sensor: Sensor?): SensorEvent? {
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
            event.timestamp = System.nanoTime()
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
    // 伪造传感器暴露：getDefaultSensor / getSensorList / getFullSensorsList
    // ------------------------------------------------------------------

    private fun hookSensorExposure(classLoader: ClassLoader) {
        // 具体实现类（abstract 的 SensorManager 方法无法直接 hook）
        val cSystemSensorManager = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader)

        fun exposeSensorInList(result: Any?, type: Int) {
            val list = result as? MutableList<Sensor> ?: return
            if (type == TYPE_STEP_COUNTER || type == Sensor.TYPE_ALL) {
                if (!list.any { it.type == TYPE_STEP_COUNTER }) {
                    fakeStepSensor?.let {
                        list.add(it)
                        if (FakeLoc.enableDebugLog) Logger.debug("injected fake step sensor into list")
                    }
                }
            }
        }

        cSystemSensorManager?.declaredMethods?.filter {
            it.name == "getDefaultSensor" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.forEach { m ->
            m.onceHook(afterHook {
                val type = args[0] as Int
                if (type == TYPE_STEP_COUNTER && result == null) {
                    result = fakeStepSensor
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
                        "getDefaultSensor" -> if (type == TYPE_STEP_COUNTER && result == null) result = fakeStepSensor
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
                        var sensor: Sensor? = null
                        var listener: SensorEventListener? = null
                        var handler: Handler? = null
                        for (arg in args) {
                            when {
                                arg is Sensor -> sensor = arg
                                arg is SensorEventListener -> listener = arg
                                arg is Handler -> handler = arg
                            }
                        }
                        // 旁路真实监听已移除：所有 App 注册一律拦截（永远虚拟注入）
                        if (sensor != null && sensor.type in INJECTABLE_SENSOR_TYPES) {
                            val l = listener
                            if (l != null) {
                                if (registeredListeners.add(RegisteredListener(l, handler, sensor, sensor.type))) {
                                    if (FakeLoc.enableDebugLog) {
                                        Logger.debug("registerListener intercepted: type=${sensor.type}, listeners=${registeredListeners.size}")
                                    }
                                    // 记录 handle→type（供兜底改写路径识别）
                                    runCatching {
                                        val handle = XposedHelpers.callMethod(sensor, "getHandle") as? Int
                                        if (handle != null) sensorHandleTypeMap[handle] = sensor.type
                                    }
                                    startInjectorIfNeeded()
                                }
                            }
                            // 阻止真实注册：步数/朝向完全由我们注入，避免双数据流
                            result = true
                        }
                    })
                }.onFailure {
                    if (FakeLoc.enableDebugLog) Logger.debug("hook registerListener(${m.parameterTypes.joinToString()}) failed: ${it.message}")
                }
            }
    }

    private fun hookUnregisterListener(classLoader: ClassLoader) {
        val cSensorManager = XposedHelpers.findClassIfExists("android.hardware.SensorManager", classLoader)
            ?: return

        cSensorManager.declaredMethods
            .filter { m ->
                m.name == "unregisterListener" &&
                        m.parameterTypes.any { SensorEventListener::class.java.isAssignableFrom(it) }
            }
            .forEach { m ->
                runCatching {
                    m.onceHook(beforeHook {
                        val listener = args.firstOrNull { it is SensorEventListener } as? SensorEventListener ?: return@beforeHook
                        val removed = registeredListeners.removeIf { it.listener == listener }
                        if (removed && FakeLoc.enableDebugLog) {
                            Logger.debug("unregisterListener, remaining=${registeredListeners.size}")
                        }
                        if (registeredListeners.isEmpty()) {
                            stopInjector()
                        }
                    })
                }.onFailure { /* ignore */ }
            }
    }

    // ------------------------------------------------------------------
    // dispatchSensorEvent 兜底：真实事件流到达时改写数据（拦截未生效的 ROM）
    // ------------------------------------------------------------------

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        val queueClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: XposedHelpers.findClassIfExists("android.hardware.SensorManager\$SensorEventQueue", classLoader)
            ?: return

        queueClass.declaredMethods.filter { it.name == "dispatchSensorEvent" }.forEach { m ->
            m.onceHook(beforeHook {
                injectClientEvent(args)
            })
        }
    }

    /**
     * 客户端兜底改写：按 handle→type 分流——步数按步频推进；
     * 朝向类直接覆盖为模拟 bearing 对应的旋转数据（不随真实转动变化）。
     */
    private fun injectClientEvent(args: Array<Any?>) {
        if (args.size < 4) return
        val handle = args[0] as? Int ?: return
        val values = args[1] as? FloatArray ?: return
        if (values.isEmpty()) return
        val timestamp = args[3] as? Long ?: return

        val type = sensorHandleTypeMap[handle] ?: return
        if (type in ROTATION_SENSOR_TYPES) {
            advanceSwing()
            val mock = rotationValuesFor(type)
            for (i in mock.indices) {
                if (i < values.size) values[i] = mock[i]
            }
            return
        }
        if (type != TYPE_STEP_COUNTER) return

        val now = timestamp
        var wholeSteps = 0L
        if (lastEventTimeNanos == 0L || now <= lastEventTimeNanos) {
            lastStepCount.set(values[0].toLong())
        } else {
            val dtSec = (now - lastEventTimeNanos) / 1_000_000_000.0
            if (dtSec in 0.0..10.0) {
                val added = cadenceForSpeed(speedCache) / 60.0 * dtSec
                wholeSteps = added.toLong()
                lastStepCount.addAndGet(wholeSteps)
            }
        }
        lastEventTimeNanos = now
        values[0] = lastStepCount.get().toFloat()
        // 步频推进：存储步频间隔（供摆动半周期持续时长，蜜罐规避）
        if (wholeSteps >= 1) {
            onStepAdvance()
        }
    }

    // ------------------------------------------------------------------
    // 速度同步：从注入位置的 extras 读取 portal_speed
    // ------------------------------------------------------------------

    private fun hookSpeedSync(classLoader: ClassLoader) {
        val cLocationManager = XposedHelpers.findClassIfExists("android.location.LocationManager", classLoader)
            ?: return

        // 从注入位置的 extras 同步速度与朝向（服务端权威值 → 客户端缓存）
        fun syncFromLocation(loc: Location) {
            val speed = loc.extras?.getDouble(EXTRA_PORTAL_SPEED)
            if (speed != null && speed > 0.0) {
                speedCache = speed
            }
            val bearing = loc.extras?.getDouble(EXTRA_PORTAL_BEARING)
            if (bearing != null) {
                bearingCache = bearing
            }
            val moving = loc.extras?.getBoolean(EXTRA_PORTAL_MOVING)
            if (moving != null) {
                movingCache = moving
            }
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

        cLocationManager.declaredMethods.filter { it.name == "getCurrentLocation" }.forEach { m ->
            m.onceHook(beforeHook {
                val callback = args.firstOrNull { a ->
                    a != null && a.javaClass.methods.any { it.name == "onLocation" }
                } ?: return@beforeHook
                callback.javaClass.onceHookAllMethod("onLocation", beforeHook {
                    val loc = args[0] as? Location ?: return@beforeHook
                    syncFromLocation(loc)
                })
            })
        }
    }

    private fun unlockGeoSensor(classLoader: ClassLoader) {
        val cSystemConfig = XposedHelpers.findClassIfExists("com.android.server.SystemConfig", classLoader)
            ?: return

        cSystemConfig.hookMethodAfter("getAvailableFeatures") {
            val features = result as? ArrayMap<String, FeatureInfo> ?: return@hookMethodAfter
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getAvailableFeatures: ${features.keys}")
            }
            // 现代设备基本自带传感器 feature，无需注入（历史代码已注释）
        }
    }
}
