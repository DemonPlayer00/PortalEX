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
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 传感器模拟 hook（客户端方案 v3，融合 UseVector 主动注入能力）。
 *
 * 关键事实：`android.hardware.SystemSensorManager`/`SensorManager` 是 SDK 客户端类，
 * 运行在**每一个 app 进程**内，而不是 system_server。因此本模块必须在所有进程安装
 * （见 FakeLocation.handleLoadPackage）。
 *
 * 三种机制配合，覆盖有/无真实传感器的设备：
 * 1. **主动注入（主路径，参考 UseVector）**：伪造 TYPE_STEP_COUNTER 的 Sensor，
 *    拦截 registerListener（result=true 阻止真实注册），单线程调度器按当前步频
 *    周期性构造 SensorEvent 并直接回调 listener —— 设备没有步计数传感器也能工作。
 * 2. **伪造传感器暴露**：getDefaultSensor/getSensorList/getFullSensorsList 在系统
 *    缺失步计数器时注入伪造对象，让 App 认为设备支持步计数。
 * 3. **dispatchSensorEvent 改写（兜底）**：若拦截未生效（特殊 ROM 走了其他路径），
 *    真实事件流到达时改写 values，保持步频一致。
 *
 * 步频模型 = 速度模型 × 疲劳衰减（融合 UseVector 的衰减曲线）：
 *   baseCadence(步/min) = 60 + 30 * speed(m/s)，上限 220 —— 走路/跑步速度对应
 *   progress = 已走步数 / 5287（UseVector 衰减阈值）
 *   factor = 1.0 - 0.32 * progress^1.5（从 1.0 衰减到 0.68，模拟长跑疲劳）
 *   cadence = baseCadence * factor，下限 60
 *
 * 速度来源：模拟速度权威值在 system_server（FakeLoc.speed）。本进程通过 hook 定位
 * 回调，从注入位置的 extras（portal_speed，见 BaseLocationHook）同步速度缓存。
 */
object SystemSensorManagerHook {
    private const val TYPE_STEP_COUNTER = 19
    private const val EXTRA_PORTAL_SPEED = "portal_speed"

    // UseVector 兼容：步频衰减阈值（模拟 5287 步后疲劳到最低步频）
    private const val DECAY_STEPS = 5287
    // 衰减幅度：最高步频的 32% 会被疲劳吃掉（对应 190 → 130 步/min 的曲线）
    private const val DECAY_FACTOR = 0.32

    // listener -> 其注册线程的 Handler（有则 post，无则直接回调）
    private data class RegisteredListener(val listener: SensorEventListener, val handler: Handler?)

    private val registeredListeners = CopyOnWriteArraySet<RegisteredListener>()

    // sensor handle -> sensor type（dispatchSensorEvent 只提供 handle）
    private val sensorHandleTypeMap = ConcurrentHashMap<Int, Int>()

    // 全局累计步数（随机起点，模拟"已经走了不少"）
    private val globalSteps = AtomicInteger(kotlin.random.Random.nextInt(3000, 12000))
    // 本次会话开始后走的步数（疲劳进度用）
    private val actualStepsSinceStart = AtomicInteger(0)

    // 调度器（单线程，与 UseVector 一致）
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Portal-StepInjector").apply { isDaemon = true }
    }
    private var currentScheduledFuture: ScheduledFuture<*>? = null

    // 伪造的步计数传感器（无真实传感器时暴露给 App）
    private var fakeStepSensor: Sensor? = null
    // 真实步计数传感器（存在时事件挂它名下，降低检测面）
    private var realStepSensor: Sensor? = null

    // 速度缓存（m/s），默认慢走
    @Volatile private var speedCache = 1.5

    // dispatchSensorEvent 兜底状态（每进程独立）
    private val lastStepCount = AtomicLong(0)
    @Volatile private var lastEventTimeNanos = 0L

    operator fun invoke(classLoader: ClassLoader) {
        unlockGeoSensor(classLoader)

        createFakeStepSensor()
        hookSensorExposure(classLoader)
        hookRegisterListener(classLoader)
        hookUnregisterListener(classLoader)
        hookSystemSensorManagerQueue(classLoader)
        hookSpeedSync(classLoader)
    }

    /** 步频-速度模型（步/min），未乘疲劳系数 */
    private fun baseCadenceForSpeed(speed: Double): Int {
        return (60.0 + 30.0 * speed).toInt().coerceIn(60, 220)
    }

    /** 当前即时步频 = 速度步频 × 疲劳衰减 */
    private fun currentCadence(): Int {
        val base = baseCadenceForSpeed(speedCache)
        val progress = (actualStepsSinceStart.get().toDouble() / DECAY_STEPS).coerceIn(0.0, 1.0)
        val factor = 1.0 - DECAY_FACTOR * progress.pow(1.5)
        return (base * factor).roundToInt().coerceAtLeast(60)
    }

    /** 下一次事件间隔（ms）：步频换算 + ±10% 抖动（参考 UseVector） */
    private fun nextDelayMs(): Long {
        val cadence = currentCadence()
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
            if (registeredListeners.isNotEmpty()) {
                val step = globalSteps.incrementAndGet()
                actualStepsSinceStart.incrementAndGet()
                val event = createSensorEvent(step) ?: return
                for (registered in registeredListeners) {
                    try {
                        val handler = registered.handler
                        if (handler != null) {
                            handler.post { runCatching { registered.listener.onSensorChanged(event) } }
                        } else {
                            registered.listener.onSensorChanged(event)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("[Portal] step listener callback failed: ${t.message}")
                    }
                }
                if (FakeLoc.enableDebugLog && actualStepsSinceStart.get() % 200 == 0) {
                    Logger.debug("step injector: total=$step cadence=${currentCadence()}/min speed=${speedCache}")
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[Portal] step injector failed: ${t.message}")
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

    private fun createSensorEvent(stepCount: Int): SensorEvent? {
        return try {
            val event: SensorEvent = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                SensorEvent::class.java.getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(TYPE_STEP_COUNTER)
            } else {
                SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }.newInstance(TYPE_STEP_COUNTER)
            }
            SensorEvent::class.java.getDeclaredField("values").apply {
                isAccessible = true
                if (get(event) == null) set(event, FloatArray(3))
            }
            event.values[0] = stepCount.toFloat()
            event.timestamp = System.nanoTime()
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

            SensorEvent::class.java.getDeclaredField("sensor").apply {
                isAccessible = true
                val sensor = realStepSensor ?: fakeStepSensor
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
                        if (sensor?.type == TYPE_STEP_COUNTER) {
                            val l = listener
                            if (l != null) {
                                if (registeredListeners.add(RegisteredListener(l, handler))) {
                                    if (FakeLoc.enableDebugLog) Logger.debug("registerListener intercepted, listeners=${registeredListeners.size}")
                                    startInjectorIfNeeded()
                                }
                            }
                            // 阻止真实注册：步数完全由我们注入，避免双数据流
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
    // dispatchSensorEvent 兜底：真实事件流到达时改写步数（拦截未生效的 ROM）
    // ------------------------------------------------------------------

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        val queueClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: XposedHelpers.findClassIfExists("android.hardware.SensorManager\$SensorEventQueue", classLoader)
            ?: return

        queueClass.declaredMethods.filter { it.name == "dispatchSensorEvent" }.forEach { m ->
            m.onceHook(beforeHook {
                injectStepCounter(args)
            })
        }
    }

    private fun injectStepCounter(args: Array<Any?>) {
        if (args.size < 4) return
        val handle = args[0] as? Int ?: return
        val values = args[1] as? FloatArray ?: return
        if (values.isEmpty()) return
        val timestamp = args[3] as? Long ?: return

        val type = sensorHandleTypeMap[handle] ?: return
        if (type != TYPE_STEP_COUNTER) return

        val now = timestamp
        if (lastEventTimeNanos == 0L || now <= lastEventTimeNanos) {
            lastStepCount.set(values[0].toLong())
        } else {
            val dtSec = (now - lastEventTimeNanos) / 1_000_000_000.0
            if (dtSec in 0.0..10.0) {
                val added = currentCadence() / 60.0 * dtSec
                lastStepCount.addAndGet(added.toLong())
            }
        }
        lastEventTimeNanos = now
        values[0] = lastStepCount.get().toFloat()
    }

    // ------------------------------------------------------------------
    // 速度同步：从注入位置的 extras 读取 portal_speed
    // ------------------------------------------------------------------

    private fun hookSpeedSync(classLoader: ClassLoader) {
        val cLocationManager = XposedHelpers.findClassIfExists("android.location.LocationManager", classLoader)
            ?: return

        val hookSpeed = beforeHook {
            val loc = args[0] as? Location ?: return@beforeHook
            val speed = loc.extras?.getDouble(EXTRA_PORTAL_SPEED)
            if (speed != null && speed > 0.0) {
                speedCache = speed
            }
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
                    val speed = loc.extras?.getDouble(EXTRA_PORTAL_SPEED)
                    if (speed != null && speed > 0.0) {
                        speedCache = speed
                    }
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
