@file:Suppress("UNCHECKED_CAST")
package moe.fuqiuluo.xposed.hooks.sensor

import android.content.pm.FeatureInfo
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.location.Location
import android.util.ArrayMap
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookAllMethods
import moe.fuqiuluo.xposed.utils.hookMethodAfter
import moe.fuqiuluo.xposed.utils.onceHook
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 传感器模拟 hook（客户端方案）。
 *
 * 关键事实：`android.hardware.SystemSensorManager` 是 SDK 客户端类，运行在**每一个 app 进程**内，
 * 而不是 system_server。因此本模块必须在所有进程安装（见 FakeLocation.handleLoadPackage），
 * 而不是只在 "android" 进程内安装——旧实现挂错进程，导致 hook 从未被触发。
 *
 * 注入模型：步计数器（TYPE_STEP_COUNTER）的数据按「当前模拟速度」对应的步频推进，
 * 符合真实运动规律：
 *   慢走 ≈ 90-100 步/min（~1.2 m/s）
 *   快走 ≈ 110-120 步/min（~1.5 m/s）
 *   慢跑 ≈ 160-170 步/min（~3.0 m/s）
 *   快跑 ≈ 180-200 步/min（~4.5+ m/s）
 * 拟合公式：cadence(步/min) = 60 + 30 * speed(m/s)，上限 220。
 *
 * 速度来源：模拟速度的权威值在 system_server 进程（FakeLoc.speed）。
 * 本进程通过 hook 定位回调，从注入位置的 extras（portal_speed，见 BaseLocationHook）
 * 同步速度缓存，避免跨进程权限问题。
 */
object SystemSensorManagerHook {
    private const val TYPE_STEP_DETECTOR = 18
    private const val TYPE_STEP_COUNTER = 19
    private const val EXTRA_PORTAL_SPEED = "portal_speed"

    // listener -> sensor type（记录注册）
    private val listenerMap = ConcurrentHashMap<SensorEventListener, Int>()

    // sensor handle -> sensor type（dispatchSensorEvent 只提供 handle）
    private val sensorHandleTypeMap = ConcurrentHashMap<Int, Int>()

    // 步频注入状态（每进程独立）
    private val lastStepCount = AtomicLong(0)
    @Volatile private var lastEventTimeNanos = 0L

    // 缓存的速度 (m/s)，默认慢走
    @Volatile private var speedCache = 1.5

    operator fun invoke(classLoader: ClassLoader) {
        unlockGeoSensor(classLoader)

        hookSystemSensorManager(classLoader)
        hookSystemSensorManagerQueue(classLoader)
        hookSpeedSync(classLoader)
    }

    /** 步频-速度模型（步/min） */
    private fun cadenceForSpeed(speed: Double): Double {
        return (60.0 + 30.0 * speed).coerceIn(60.0, 220.0)
    }

    private fun hookSystemSensorManager(classLoader: ClassLoader) {
        val cSystemSensorManager = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader)
            ?: return

        val hookRegisterListenerImpl = beforeHook {
            val listener = args[0] as? SensorEventListener ?: return@beforeHook
            val sensor = args[1] as? Sensor ?: return@beforeHook
            // Sensor.getHandle() 是 @SystemApi 隐藏方法，反射调用
            val handle = runCatching {
                XposedHelpers.callMethod(sensor, "getHandle") as? Int
            }.getOrNull() ?: return@beforeHook
            listenerMap[listener] = sensor.type
            sensorHandleTypeMap[handle] = sensor.type
            if (FakeLoc.enableDebugLog) {
                Logger.debug("registerListenerImpl: sensor=${sensor.name} type=${sensor.type} handle=${handle}")
            }
        }
        cSystemSensorManager.declaredMethods.filter {
            it.name == "registerListenerImpl" && it.parameterTypes.size >= 2
                    && it.parameterTypes[0] == SensorEventListener::class.java
                    && it.parameterTypes[1] == Sensor::class.java
        }.forEach {
            it.onceHook(hookRegisterListenerImpl)
        }

        val hookUnregisterListenerImpl = beforeHook {
            val listener = args[0] as? SensorEventListener ?: return@beforeHook
            listenerMap.remove(listener)
            if (FakeLoc.enableDebugLog) {
                Logger.debug("unregisterListenerImpl: $listener")
            }
        }
        cSystemSensorManager.declaredMethods.filter {
            it.name == "unregisterListenerImpl" && it.parameterTypes.isNotEmpty()
                    && it.parameterTypes[0] == SensorEventListener::class.java
        }.forEach {
            it.onceHook(hookUnregisterListenerImpl)
        }

        if (FakeLoc.enableDebugLog) {
            cSystemSensorManager.hookAllMethods("getSensorList", afterHook {
                Logger.debug("getSensorList: type=${args[0]} -> $result")
            })
            cSystemSensorManager.hookAllMethods("getFullSensorsList", afterHook {
                Logger.debug("getFullSensorsList -> $result")
            })
        }
    }

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        // Android 12+ 后 SensorEventQueue 移到了 SensorManager 内部类；老版本在 SystemSensorManager 下
        val queueClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: XposedHelpers.findClassIfExists("android.hardware.SensorManager\$SensorEventQueue", classLoader)
            ?: return

        queueClass.declaredMethods.filter { it.name == "dispatchSensorEvent" }.forEach { m ->
            m.onceHook(beforeHook {
                injectStepCounter(args)
            })
        }
    }

    /**
     * 改写步计数器数据。
     * dispatchSensorEvent(int handle, float[] values, int inAccuracy, long timestamp)
     * 首次事件以真实值为基准（避免跳变），之后按当前速度对应的步频单调推进。
     */
    private fun injectStepCounter(args: Array<Any?>) {
        if (args.size < 4) return
        if (!FakeLoc.enable) return

        val handle = args[0] as? Int ?: return
        val values = args[1] as? FloatArray ?: return
        if (values.isEmpty()) return
        val timestamp = args[3] as? Long ?: return

        val type = sensorHandleTypeMap[handle] ?: return
        if (type != TYPE_STEP_COUNTER) return

        val now = timestamp
        if (lastEventTimeNanos == 0L || now <= lastEventTimeNanos) {
            // 首次事件或时间戳异常：对齐真实累计值
            lastStepCount.set(values[0].toLong())
        } else {
            val dtSec = (now - lastEventTimeNanos) / 1_000_000_000.0
            if (dtSec in 0.0..10.0) {
                val added = cadenceForSpeed(speedCache) / 60.0 * dtSec
                lastStepCount.addAndGet(added.toLong())
            }
        }
        lastEventTimeNanos = now
        values[0] = lastStepCount.get().toFloat()
    }

    /**
     * 同步模拟速度：hook 本进程的定位回调。
     * system_server 注入位置时会在 extras 写入 portal_speed（见 BaseLocationHook.injectLocation），
     * 任意 app 进程都能在自己的定位回调里读到，无需额外权限。
     */
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

        // getCurrentLocation 的单次回调（不同版本参数位置不同，特征查找 callback）
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

        val openGLVersion = run {
            val cSystemProperties = XposedHelpers.findClassIfExists("android.os.SystemProperties", classLoader)
                ?: return@run 0
            XposedHelpers.callStaticMethod(cSystemProperties, "getInt", "ro.opengles.version", FeatureInfo.GL_ES_VERSION_UNDEFINED) as Int
        }

        cSystemConfig.hookMethodAfter("getAvailableFeatures") {
            val features = result as? ArrayMap<String, FeatureInfo> ?: return@hookMethodAfter
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getAvailableFeatures: ${features.keys}")
            }
            // 历史实现曾在此注入 FEATURE_SENSOR_* feature（已在原代码中注释掉），
            // 现代设备基本都自带这些 feature，无需再注入。
        }
    }
}
