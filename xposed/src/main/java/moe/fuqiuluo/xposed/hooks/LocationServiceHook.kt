@file:Suppress("KotlinConstantConditions")
@file:OptIn(ExperimentalUuidApi::class)

package moe.fuqiuluo.xposed.hooks

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.RemoteCommandHandler
import moe.fuqiuluo.xposed.hooks.gnss.GnssHook
import moe.fuqiuluo.xposed.hooks.miui.MiuiBlurLocationProviderHook
import moe.fuqiuluo.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import moe.fuqiuluo.xposed.hooks.nmea.LocationNMEAHook
import moe.fuqiuluo.xposed.hooks.provider.LocationProviderManagerHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.PortalDiag
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookAllMethods
import moe.fuqiuluo.xposed.utils.onceHook
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi


// 可见卫星数上限：与 FakeLoc.MAX_SATELLITES 共用同一常量（GnssStatus 推送与 Location.extras
// 的卫星字段必须同量级，否则同一时刻雷达与 extras 互相矛盾，构成交叉检测面）
private const val MAX_SATELLITES = FakeLoc.MAX_SATELLITES

// 载噪比范围，考虑不同轨道类型
private const val GEO_MIN_CN0 = 30.0f  // GEO卫星信号较强
private const val GEO_MAX_CN0 = 45.0f
private const val IGSO_MIN_CN0 = 25.0f
private const val IGSO_MAX_CN0 = 42.0f
private const val MEO_MIN_CN0 = 20.0f  // MEO卫星信号相对较弱
private const val MEO_MAX_CN0 = 40.0f

// 北斗频率
private const val BDS_B1I_FREQ = 1561.098f // MHz
private const val BDS_B2I_FREQ = 1207.140f
private const val BDS_B3I_FREQ = 1268.520f

private val satelliteList = listOf(
    BDSSatellite(1, OrbitType.GEO),
    BDSSatellite(2, OrbitType.GEO),
    BDSSatellite(3, OrbitType.GEO),
    BDSSatellite(4, OrbitType.GEO),
    BDSSatellite(5, OrbitType.GEO),
    BDSSatellite(6, OrbitType.IGSO),
    BDSSatellite(7, OrbitType.IGSO),
    BDSSatellite(8, OrbitType.IGSO),
    BDSSatellite(9, OrbitType.IGSO),
    BDSSatellite(10, OrbitType.IGSO),
    BDSSatellite(11, OrbitType.MEO),
    BDSSatellite(12, OrbitType.MEO),
    BDSSatellite(13, OrbitType.IGSO),
    BDSSatellite(14, OrbitType.MEO),
    BDSSatellite(16, OrbitType.IGSO),
    BDSSatellite(19, OrbitType.MEO),
    BDSSatellite(20, OrbitType.MEO),
    BDSSatellite(21, OrbitType.MEO),
    BDSSatellite(22, OrbitType.MEO),
    BDSSatellite(23, OrbitType.MEO),
    BDSSatellite(24, OrbitType.MEO),
    BDSSatellite(25, OrbitType.MEO),
    BDSSatellite(26, OrbitType.MEO),
    BDSSatellite(27, OrbitType.MEO),
    BDSSatellite(28, OrbitType.MEO),
    BDSSatellite(29, OrbitType.MEO),
    BDSSatellite(30, OrbitType.MEO),
    BDSSatellite(31, OrbitType.IGSO),
    BDSSatellite(32, OrbitType.MEO),
    BDSSatellite(33, OrbitType.MEO),
    BDSSatellite(34, OrbitType.MEO),
    BDSSatellite(35, OrbitType.MEO),
    BDSSatellite(36, OrbitType.MEO),
    BDSSatellite(37, OrbitType.MEO),
    BDSSatellite(38, OrbitType.IGSO),
    BDSSatellite(39, OrbitType.IGSO),
    BDSSatellite(40, OrbitType.IGSO),
    BDSSatellite(41, OrbitType.MEO),
    BDSSatellite(42, OrbitType.MEO),
    BDSSatellite(43, OrbitType.MEO),
    BDSSatellite(44, OrbitType.MEO),
    BDSSatellite(45, OrbitType.MEO),
    BDSSatellite(46, OrbitType.MEO),
    BDSSatellite(56, OrbitType.IGSO),
    BDSSatellite(57, OrbitType.MEO),
    BDSSatellite(58, OrbitType.MEO),
    BDSSatellite(59, OrbitType.GEO),
    BDSSatellite(60, OrbitType.GEO),
    BDSSatellite(61, OrbitType.GEO),
    BDSSatellite(62, OrbitType.GEO),
    BDSSatellite(48, OrbitType.MEO),
    BDSSatellite(50, OrbitType.MEO),
    BDSSatellite(47, OrbitType.MEO),
    BDSSatellite(49, OrbitType.MEO),
//    BDSSatellite(130, OrbitType.GEO),
//    BDSSatellite(143, OrbitType.GEO),
//    BDSSatellite(144, OrbitType.GEO),
)

object GnssFlags {
    // 基本标志位
    const val SVID_FLAGS_NONE = 0
    const val SVID_FLAGS_HAS_EPHEMERIS_DATA = (1 shl 0)
    const val SVID_FLAGS_HAS_ALMANAC_DATA = (1 shl 1)
    const val SVID_FLAGS_USED_IN_FIX = (1 shl 2)
    const val SVID_FLAGS_HAS_CARRIER_FREQUENCY = (1 shl 3)
    const val SVID_FLAGS_HAS_BASEBAND_CN0 = (1 shl 4)

    // 位移宽度
    const val SVID_SHIFT_WIDTH = 12
    const val CONSTELLATION_TYPE_SHIFT_WIDTH = 8
    const val CONSTELLATION_TYPE_MASK = 0xf

    // 星座类型（与 Android GnssStatus.CONSTELLATION_ 常量对应）
    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_SBAS = 2
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_QZSS = 4
    const val CONSTELLATION_BEIDOU = 5
    const val CONSTELLATION_GALILEO = 6
    const val CONSTELLATION_IRNSS = 7
}

sealed class OrbitType(val minCn0: Float, val maxCn0: Float, val elevationRange: ClosedRange<Float>) {
    object GEO : OrbitType(GEO_MIN_CN0, GEO_MAX_CN0, 35f..50f)
    object IGSO : OrbitType(IGSO_MIN_CN0, IGSO_MAX_CN0, 20f..60f)
    object MEO : OrbitType(MEO_MIN_CN0, MEO_MAX_CN0, 0f..90f)
}

data class BDSSatellite(
    val prn: Int,
    val type: OrbitType,
)

data class MockGnssData(
    val svCount: Int,
    val svidWithFlags: IntArray,
    val cn0s: FloatArray,
    val elevations: FloatArray,
    val azimuths: FloatArray,
    val carrierFreqs: FloatArray
)

internal object LocationServiceHook: BaseLocationHook() {
    /**
     * 一条位置监听器注册。
     *
     * [blocked] = 注册时被模块吞掉（`result = null`，框架**不会**回调它）→ 必须由推送链供帧；
     * false = 已放行真实注册（框架会回调，且帧已被注入链改写）→ **只在饿死时补帧**。
     * 旧实现不区分两者、对表内所有注册一律投递：被放行的注册会同时收到「框架帧」与
     * 「推送帧」两份坐标/时间都不同的位置——同一注册重复回调，且两套时间戳互相矛盾。
     */
    class ListenerRegistration(
        val provider: String,
        val listener: IInterface,
        val blocked: Boolean
    ) {
        /** 最近一次收到位置的时刻（框架投递到达、或我们推送成功时刷新） */
        val lastDeliveryNanos = AtomicLong(0L)
    }

    /**
     * 装一个 hook 族：失败只记一笔账 + 一条日志，**不影响其它族与后续段**。
     * "缺类"在 ROM 差异下是常态，所以它不算功能故障，只进 [PortalDiag] 与日志。
     */
    private inline fun hookFamily(name: String, block: () -> Unit) {
        kotlin.runCatching(block).onFailure {
            PortalDiag.fail(PortalDiag.Area.HOOK_INSTALL, it)
            Logger.error("$name 安装失败（已跳过，其余 hook 继续）：${it.message}", it)
        }
    }

    /** 已注册的位置监听器（**旧架构**：统一由宿主心跳推送，无按应用间隔节流） */
    val locationListeners = LinkedBlockingQueue<ListenerRegistration>()

    /** 一次性取位回调（getCurrentLocation）登记项：记录登记时刻，饿死过久才补投。
     *  应用室内无星时框架不会投递任何位置，若只登记不改写，应用就一直空等。 */
    private class OneShotCallback(val callback: IInterface, val registeredNanos: Long)

    private val oneShotCallbacks = LinkedBlockingQueue<OneShotCallback>()

    /** 最近一次向任何注册投递帧的时刻（保活线程据此判断是否需要补帧） */
    @Volatile private var lastDeliveryNanosGlobal = 0L

    /**
     * 饿死阈值：某注册超过该时长既没收到框架投递、也没收到我们推送时，才补帧。
     * 取值 **大于真机 GPS 的 1Hz 出帧周期**——正常定位下框架自己就在送帧（已被注入链改写），
     * 此时我们不该再插一份坐标/时间都不同的推送帧；只有真的没有源（室内无星、
     * 注册被拦死）才补，这才是「同一 tick 一帧」。
     */
    private const val FRAME_STARVATION_NANOS = 1_200_000_000L

    /** 保活线程节拍：静默期（无坐标变化、无心跳命令）也能保证注册不饿死 */
    private const val KEEP_ALIVE_TICK_MS = 500L

    private val keepAliveStarted = AtomicBoolean(false)

    /**
     * 构造一帧模拟位置：注入链 + 当前时刻时间戳（模块自己生成的新帧，不是转发的旧帧）。
     *
     * 返回 null = **当前没有任何可用的位置底子**（既没见过真实 fix 的载体，也没设过坐标）——
     * 这时**不推**：凭空造一个 (0,0) 的 gps 帧毫无意义，还会让应用看到一次"零度定位"。
     * 与融合路径同一条口径：只做「拦截-修改-转发」，不凭空生成。
     */
    private fun buildFrame(): Location? {
        val origin = FakeLoc.lastLocation
        if (origin == null && FakeLoc.latitude == 0.0 && FakeLoc.longitude == 0.0) return null
        return injectLocation(origin ?: Location("gps")).apply {
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    /** 无位置底子时的提示只打一次，别刷日志（会话刚开始、还没设点时会走到） */
    private val noOriginWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 向单个监听器投一帧；返回是否成功 */
    private fun deliverFrame(listener: IInterface, frame: Location): Boolean {
        var error: Throwable? = null
        kotlin.runCatching {
            val locations = listOf(frame)
            val mOnLocationChanged =
                XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", locations, null)
            XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(locations, null))
            return true
        }.onFailure {
            if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                return false
            }
            error = it
        }

        kotlin.runCatching {
            val mOnLocationChanged =
                XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", frame)
            XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(frame))
            return true
        }.onFailure {
            if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                return false
            }
            error = it
        }

        Logger.error("deliverFrame failed: " + error?.stackTraceToString())
        Logger.error("The listener all methods: " + listener.javaClass.declaredMethods.joinToString { it.name })
        return false
    }

    // A random command is generated to prevent some apps from detecting Portal
    operator fun invoke(classLoader: ClassLoader) {
        val cLocationManagerService = XposedHelpers.findClassIfExists("com.android.server.location.LocationManagerService", classLoader)
        if (cLocationManagerService == null) {
            hookLocationManagerServiceV2(classLoader)
        } else {
            onService(cLocationManagerService)
        }
    }

    fun onService(cILocationManager: Class<*>) {
    /*
     * ===================== onService 分段地图（60 行） =====================
     *
     * 这个函数**只剩一条按主题排好的调用链**：位置服务出口上的全部 hook 各自拆成
     * object 内 private fun（段 1~9，见各自 KDoc）。读代码时按段跳，不要当成平铺逻辑。
     *
     *   段 1  取位（getLastLocation）            立即更新虚拟坐标/计步/路线推进
     *   段 2  监听器注册家族                      requestLocationUpdates / removeUpdates /
     *                                            (un)registerLocationListener
     *   段 3  GNSS 批量回调（条件块）             addGnssBatchingCallback + onLocationBatch 注入
     *   段 4  围栏 / 取址 / 测试 provider         requestGeofence、getFromLocation(Name)、
     *                                            add/removeTestProvider、setTestProvider*
     *   段 5  GNSS 状态与批处理                   registerGnssStatusCallback 入队 + 主动推送、
     *                                            start/stopGnssBatch、requestListenerFlush
     *   段 6  取位请求（getCurrentLocation）      允许并注入 + 登记一次性投递表
     *   段 7  命令通道（sendExtraCommand）        portal provider + 厂商开关 + 指令分发
     *   段 8  provider 可见性                     isProviderEnabled(ForUser)：含握手门禁
     *   段 9  厂商/第三方 SDK 控制器包抑制         setExtraLocationControllerPackage*
     *
     * ⚠️ **顺序敏感**：同一方法上的多个回调按注册顺序执行（XposedBridge 语义），
     * 所以这些调用行的先后不能重排 —— 段函数只负责"装 hook"，调用顺序即注册顺序。
     *
     * 拆段时踩过的两个坑（别再踩）：
     *   ① 段首必须取**构造的开头**（例如段 8 的真开头是 `if(`，注释行在它内部）——
     *      按"向前吃注释"取边界会把 `if(` 留在上一段，切出语法错误；切完先做括号配平断言。
     *   ② 段函数必须放在 **object 内部**（它们用 oneShotCallbacks 等对象级成员；
     *      放到文件级会变成 Unresolved reference），并且替换段体时**别丢掉 onService 的收尾 `}`**。
     * =======================================================================
     */

        // Got instance of ILocationManager.Stub here, you can hook it
        // Not directly Class.forName because of this thing, it can't be reflected, even if I'm system_server?!?!

        if (FakeLoc.enableDebugLog) {
            Logger.debug("ILocationManager.Stub: class = $cILocationManager")
        }

        // 框架/厂商侧的 hook 族：**逐个兜底**。
        //
        // 为什么必须隔离：这些族里有大量"按 ROM 存在的类"（厂商融合、MIUI blur、厂商
        // NLP…）。在 LineageOS 这类没有厂商融合/NLP 的系统上，缺类是**正常情况**，
        // 必须静默跳过；旧实现让异常直接逃出 onService —— 表现是"模块看起来装了、
        // 其实只剩一半 hook"，且没有任何一条日志说明为什么。
        val loader = cILocationManager.classLoader
        if (loader == null) {
            Logger.error("ILocationManager.classLoader 为 null：跳过框架侧 hook 族（其余段继续）")
        } else {
            hookFamily("BasicLocationHook") { BasicLocationHook(loader) }
            hookFamily("GnssHook") { GnssHook(loader) }
            hookFamily("LocationProviderManagerHook") { LocationProviderManagerHook(loader) }
            hookFamily("MiuiBlurLocationProviderHook") { MiuiBlurLocationProviderHook(loader) }
            hookFamily("MiuiTelephonyManagerHook") { MiuiTelephonyManagerHook(loader) }
        }

        LocationNMEAHook(cILocationManager)

        hookLastLocation(cILocationManager)   // 段 1：取位（getLastLocation）
        hookListenerRegistration(cILocationManager)   // 段 2：监听器注册家族（requestLocationUpdates / (un)registerLocationListener）

        hookGnssBatchingCallback(cILocationManager)   // 段 3：GNSS 批量回调（条件块：onLocationBatch 注入）
        hookGeofenceAndTestProvider(cILocationManager)   // 段 4：围栏 / 取址 / 测试 provider
        hookGnssStatusAndBatch(cILocationManager)   // 段 5：GNSS 状态与批处理（回调入队 + 主动推送 + flush）
        hookCurrentLocation(cILocationManager)   // 取位请求（getCurrentLocation）：允许并注入，并登记一次性投递表
        hookExtraCommand(cILocationManager)   // 命令通道与 provider 开关的拦截（sendExtraCommand → portal 分发）
        hookProviderEnabled(cILocationManager)   // provider 可见性（isProviderEnabled / ForUser，含握手门禁）
        hookVendorControllerPackage(cILocationManager)   // 厂商/第三方 SDK 的"额外定位控制器包"抑制（AMAP 等）
    }

    private fun hookILocationListener(listener: Any) {
        val classListener = listener.javaClass
        if (FakeLoc.enableDebugLog)
            Logger.debug("will hook ILocationListener: ${classListener.name}")

        if(XposedBridge.hookAllMethods(classListener, "onLocationChanged", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 框架投递到达（无论是否开模拟）：刷新「最近收到位置」时刻
                    markFrameworkDelivery(param.thisObject)
                    if (param.args.isEmpty()) return
                    if (!FakeLoc.enable) return

                    when (param.args[0]) {
                        is Location -> {
                            val location = param.args[0] as? Location ?: run {
                                param.result = null
                                return
                            }
                            param.args[0] = injectLocation(location)
                        }

                        is List<*> -> {
                            val locations = param.args[0] as List<*>
                            param.args[0] = locations.map { injectLocation(it as Location) }
                        }
                        else -> Logger.error("onLocationChanged args is not `Location`")
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method}: injected! ${param.args[0]}")
                    }
                }
            }).isEmpty()) {
            Logger.error("hook onLocationChanged failed")
            return // If the hook fails, the listener is not added
        }
    }

    private fun addLocationListenerInner(provider: String, listener: IInterface, blocked: Boolean) {
        val mDeathRecipient = object: IBinder.DeathRecipient {
            override fun binderDied() {}
            override fun binderDied(who: IBinder) {
                who.unlinkToDeath(this, 0)
                removeLocationListenerByBinder(who)
            }
        }
        listener.asBinder().linkToDeath(mDeathRecipient, 0)
        locationListeners.removeIf { it.listener.asBinder() == listener.asBinder() }
        locationListeners.add(ListenerRegistration(provider, listener, blocked))
        hookILocationListener(listener)
    }

    private fun removeLocationListenerInner(listener: IInterface) {
        removeLocationListenerByBinder(listener.asBinder())
    }

    private fun removeLocationListenerByBinder(binder: IBinder) {
        locationListeners.removeIf { it.listener.asBinder() == binder }
    }

    /**
     * 心跳投递：位置变更（move / update_location）、显式广播（broadcast_location）、
     * 启动拉回各自在「坐标已改好」后调用。
     *
     * @param force true = 忽略饿死判定，对流经推送链的注册立刻投一帧
     *   （坐标刚变化 / 显式广播 / 启动拉回：拉回线程正是靠反复强制推送压制真实位置）。
     *
     * 投递规则：
     * - **force（坐标刚变化 / 显式广播 / 启动拉回 / 摇杆转向）→ 投给所有注册**：
     *   位置或朝向变了就必须让每个消费者立刻看到——已放行的注册同样需要，否则它们只能等
     *   保活补帧（1.2s 起），表现出来就是"跳一次、锁住一秒"（实测复现）。
     * - 非 force（保活补帧）→ 只投给**饿死**的注册：正常定位下框架自己在送帧，不该再叠一份。
     * - 一次性取位回调：登记后饿死过久才补投一次（框架先投递过的已从表里摘除，不会重复）。
     */
    fun callOnLocationChanged(force: Boolean = false) {
        // 会话外不投递：未开模拟时注入链本身不改写，推帧只会把真实帧原样再送一遍
        if (!FakeLoc.enable) return

        if (FakeLoc.enableDebugLog) {
            Logger.debug("==> callOnLocationChanged: ${locationListeners.size}, force=$force")
        }

        // 同一 tick（一次广播）= 同一帧：持续注册的监听器与一次性取位回调共用同一份注入对象。
        // 时间戳按当前时刻打：本函数产出的是模块自己生成的新帧。
        val frame = buildFrame() ?: run {
            if (noOriginWarned.compareAndSet(false, true)) {
                Logger.info("尚无位置底子（未设点/未见 fix），本次不推送——不凭空造帧")
            }
            return
        }
        val nowNanos = SystemClock.elapsedRealtimeNanos()

        var delivered = 0
        locationListeners.forEach { reg ->
            val starved = nowNanos - reg.lastDeliveryNanos.get() >= FRAME_STARVATION_NANOS
            val shouldPush = force || starved
            if (!shouldPush) return@forEach
            if (deliverFrame(reg.listener, frame)) {
                reg.lastDeliveryNanos.set(nowNanos)
                delivered++
            }
        }

        var oneShotDelivered = 0
        oneShotCallbacks.forEach { oneShot ->
            // 饥饿判定：登记后 FRAME_STARVATION_NANOS 内框架可能仍在投递，先等——避免与框架重复
            if (nowNanos - oneShot.registeredNanos < FRAME_STARVATION_NANOS) return@forEach

            var called = false
            var error: Throwable? = null
            kotlin.runCatching {
                val mOnLocation = XposedHelpers.findMethodBestMatch(oneShot.callback.javaClass, "onLocation", frame)
                XposedBridge.invokeOriginalMethod(mOnLocation, oneShot.callback, arrayOf(frame))
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    called = true // 应用进程已死：摘掉，不再重试
                } else {
                    error = it
                }
            }
            if (called) {
                oneShotCallbacks.removeIf { it.callback.asBinder() == oneShot.callback.asBinder() }
                oneShotDelivered++
            } else {
                Logger.error("callOnLocationChanged(one-shot) failed: " + error?.stackTraceToString())
            }
        }

        if (delivered > 0 || oneShotDelivered > 0) lastDeliveryNanosGlobal = nowNanos

        if (FakeLoc.enableDebugLog) {
            Logger.debug(
                "==> callOnLocationChanged: end (delivered=$delivered/${locationListeners.size}, oneShot=$oneShotDelivered)"
            )
        }
    }

    /** 框架投递到达：刷新该注册的「最近收到位置」时刻——「已放行的注册」据此判定饿死。 */
    private fun markFrameworkDelivery(listenerObject: Any?) {
        val binder = (listenerObject as? IInterface)?.asBinder() ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        locationListeners.forEach { reg ->
            if (reg.listener.asBinder() == binder) reg.lastDeliveryNanos.set(now)
        }
    }

    /**
     * 保活：没有心跳命令、坐标也没变化（设点后静止、只在室内等）时，仍保证被拦死的注册
     * 与一次性取位回调不饿死——否则应用会停在旧位置或回退到自己的真实位置（拉回）。
     * 只有「确有注册、且确实静默超过饿死阈值」才补一帧，正常定位下几乎不触发。
     */
    private fun startLocationKeepAlive() {
        if (!keepAliveStarted.compareAndSet(false, true)) return
        kotlin.concurrent.thread(name = "LocationKeepAlive", isDaemon = true, start = true) {
            while (true) {
                try {
                    Thread.sleep(KEEP_ALIVE_TICK_MS)
                    if (!FakeLoc.enable) continue
                    if (locationListeners.isEmpty() && oneShotCallbacks.isEmpty()) continue
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (now - lastDeliveryNanosGlobal >= FRAME_STARVATION_NANOS) {
                        callOnLocationChanged()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Logger.error("LocationKeepAlive", t)
                    Thread.sleep(1000)
                }
            }
        }
    }

    // ============ GNSS 模拟主动推送（SDK 36 适配） ============
    /** 已注册的 GNSS 状态回调（system_server 侧代理对象） */
    private val gnssStatusListeners = LinkedBlockingQueue<IInterface>()
    private val gnssPusherStarted = AtomicBoolean(false)

    private fun buildMockGnssData(): MockGnssData {
        // 星数与每星 C/N0 取**当前卫星快照**（时间桶确定性）：与 Location.extras 的
        // satellites/maxCn0/meanCn0 出自同一份数据——两个通道不可能再互相矛盾
        val snapshot = FakeLoc.currentGnssSnapshot()
        val svCount = snapshot.svCount
        return MockGnssData(
            svCount = svCount,
            svidWithFlags = IntArray(svCount),
            cn0s = FloatArray(svCount),
            elevations = FloatArray(svCount),
            azimuths = FloatArray(svCount),
            carrierFreqs = FloatArray(svCount)
        ).apply {
            val selectedSatellites = satelliteList.shuffled().take(svCount)
            selectedSatellites.forEachIndexed { index, sat ->
                svidWithFlags[index] = 0

                val hasEphemeris = Random.nextFloat() > 0.1f    // 90%概率有星历
                val hasAlmanac = Random.nextFloat() > 0.05f     // 95%概率有年历
                val usedInFix = Random.nextFloat() > 0.3f       // 70%概率用于定位
                val hasCarrierFreq = true                       // 总是有载波频率
                val hasBasebandCn0 = true                       // 总是有基带载噪比

                var flags = GnssFlags.SVID_FLAGS_NONE
                if (hasEphemeris) flags = flags or GnssFlags.SVID_FLAGS_HAS_EPHEMERIS_DATA
                if (hasAlmanac) flags = flags or GnssFlags.SVID_FLAGS_HAS_ALMANAC_DATA
                if (usedInFix) flags = flags or GnssFlags.SVID_FLAGS_USED_IN_FIX
                if (hasCarrierFreq) flags = flags or GnssFlags.SVID_FLAGS_HAS_CARRIER_FREQUENCY
                if (hasBasebandCn0) flags = flags or GnssFlags.SVID_FLAGS_HAS_BASEBAND_CN0

                svidWithFlags[index] = (sat.prn shl GnssFlags.SVID_SHIFT_WIDTH) or
                        ((GnssFlags.CONSTELLATION_BEIDOU and GnssFlags.CONSTELLATION_TYPE_MASK) shl GnssFlags.CONSTELLATION_TYPE_SHIFT_WIDTH) or
                        flags

                // C/N0 来自快照（同一时间桶内所有进程/通道一致），不再各掷一次骰子
                cn0s[index] = snapshot.cn0s[index].toFloat()
                elevations[index] = Random.nextFloat(sat.type.elevationRange.start, sat.type.elevationRange.endInclusive)
                azimuths[index] = Random.nextFloat(0f, 360f)
                carrierFreqs[index] = when (Random.nextInt(3)) {
                    0 -> BDS_B1I_FREQ
                    1 -> BDS_B2I_FREQ
                    else -> BDS_B3I_FREQ
                }
            }
        }
    }

    /** 构造模拟 GnssStatus（7 参私有构造，反射）。失败返回 null。 */
    private fun buildMockGnssStatus(): Any? {
        val mockGps = buildMockGnssData()
        return runCatching {
            val cGnssStatus = XposedHelpers.findClass("android.location.GnssStatus", LocationServiceHook::class.java.classLoader!!)
            val mConstructor = cGnssStatus.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 7
            }?.also { it.isAccessible = true }
                ?: return null
            mConstructor.newInstance(
                mockGps.svCount,
                mockGps.svidWithFlags,
                mockGps.cn0s,
                mockGps.elevations,
                mockGps.azimuths,
                mockGps.carrierFreqs,
                FloatArray(mockGps.svCount) {
                    mockGps.cn0s[it] - Random.nextFloat(2f, 5f)
                }
            )
        }.onFailure { e ->
            Logger.error("buildMockGnssStatus failed", e)
        }.getOrNull()
    }

    /** 主动向已注册的 GNSS 状态回调推送模拟卫星数据（守护线程每 1s 调用）。 */
    fun pushGnssStatus() {
        if (!FakeLoc.enableMockGnss) return
        if (gnssStatusListeners.isEmpty()) return

        val status = buildMockGnssStatus() ?: return
        gnssStatusListeners.forEach { listener ->
            runCatching {
                val method = listener.javaClass.methods.firstOrNull { m ->
                    m.name == "onSvStatusChanged" &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0].name == "android.location.GnssStatus"
                } ?: listener.javaClass.methods.firstOrNull { it.name == "onSvStatusChanged" }
                    ?: return@runCatching
                XposedBridge.invokeOriginalMethod(method, listener, arrayOf(status))
            }.onFailure { e ->
                if (e is InvocationTargetException && e.targetException is DeadObjectException) return@forEach
                Logger.error("pushGnssStatus failed for ${listener.javaClass.name}", e)
            }
        }
        if (FakeLoc.enableDebugLog) {
            Logger.debug("==> pushGnssStatus: pushed to ${gnssStatusListeners.size}")
        }
    }

    private fun startGnssStatusPusher() {
        if (!gnssPusherStarted.compareAndSet(false, true)) return
        kotlin.concurrent.thread(name = "GnssStatusPusher", isDaemon = true, start = true) {
            while (true) {
                try {
                    if (FakeLoc.enableMockGnss && gnssStatusListeners.isNotEmpty()) {
                        pushGnssStatus()
                        Thread.sleep(1000)
                    } else {
                        Thread.sleep(500)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Logger.error("GnssStatusPusher", t)
                    Thread.sleep(1000)
                }
            }
        }
    }

    /** 安装 GNSS 状态回调注入 hook（方法查找放宽到 public 方法，兼容 SDK 36 声明差异） */
    private fun hookGnssStatusListener(cIGnssStatusListener: Class<*>) {
        val mSvStatusChanged = cIGnssStatusListener.methods.firstOrNull { it.name == "onSvStatusChanged" }
        if (mSvStatusChanged == null) {
            Logger.error("find onSvStatusChanged failed! class=${cIGnssStatusListener.name}")
        } else {
            mSvStatusChanged.onceHook(beforeHook {
                if (!FakeLoc.enableMockGnss) return@beforeHook

                val mockGps = buildMockGnssData()

                if (args[0] is Int) {
                    args[0] = mockGps.svCount
                    args[1] = mockGps.svidWithFlags
                    args[2] = mockGps.cn0s
                    args[3] = mockGps.elevations
                    args[4] = mockGps.azimuths
                    if (args.size > 5) {
                        args[5] = mockGps.carrierFreqs
                    }
                    if (args.size > 6) {
                        args[6] = FloatArray(mockGps.svCount) {
                            mockGps.cn0s[it] - Random.nextFloat(2f, 5f)
                        }
                    }
                    return@beforeHook
                }

                if (args[0] != null && args[0].javaClass.name == "android.location.GnssStatus") {
                    runCatching {
                        val mConstructor = args[0].javaClass.declaredConstructors.firstOrNull {
                            it.parameterTypes.size == 7
                        }.also {
                            it?.isAccessible = true
                        }
                        if (mConstructor != null) {
                            args[0] = mConstructor.newInstance(
                                mockGps.svCount,
                                mockGps.svidWithFlags,
                                mockGps.cn0s,
                                mockGps.elevations,
                                mockGps.azimuths,
                                mockGps.carrierFreqs,
                                FloatArray(mockGps.svCount) {
                                    mockGps.cn0s[it] - Random.nextFloat(2f, 5f)
                                }
                            )
                        } else {
                            Logger.error("onSvStatusChanged: unsupported version: ${method}, constructor not found")
                        }
                    }.onFailure {
                        XposedBridge.log(it)
                    }
                    return@beforeHook
                }

                Logger.error("onSvStatusChanged: unsupported version: $method")
            })
        }

        cIGnssStatusListener.onceHookAllMethod("onNmeaReceived", beforeHook {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("onNmeaReceived")
            }
            if (FakeLoc.enableMockGnss) result = null
        })
    }

    private fun hookLocationManagerServiceV2(classLoader: ClassLoader) {
        // As a system_server, the hook can get all the location information here
        kotlin.runCatching {
            XposedHelpers.findClass("android.location.ILocationManager\$Stub", classLoader)
        }.onSuccess {
            fun hookOnTransactForServiceInstance(m: Method) {
                val isHooked = AtomicBoolean(false)
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param?.thisObject == null || param.args.size < 4) return

                        val thisObject = param.thisObject
                        val code = param.args[0] as? Int ?: return
                        val data = param.args[1] as? Parcel ?: return
                        val reply = param.args[2] as? Parcel ?: return
                        val flags = param.args[3] as? Int ?: return

                        if (isHooked.compareAndSet(false, true)) {
                            onService(thisObject.javaClass)
                        }

                        if (!FakeLoc.enable) {
                            return
                        }

                        if (FakeLoc.enable && code == 43) {
                            param.result = true
                        }

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("ILocationManager.Stub: onTransact(code=$code)")
                        }
                    }
                })
            }

            it.declaredMethods.forEach {
                if (it.name == "onTransact") {
                    hookOnTransactForServiceInstance(it)

                    // Hey, hey, you've found onTransact, what else are you looking for
                    // It's time to end the cycle! BaKa!
                    return@forEach
                }
            }
        }.onFailure {
            Logger.error("ILocationManager.Stub not found", it)
        }

//        // This is the intrusive hook
//        kotlin.runCatching {
//            XposedHelpers.findClass("android.location.ILocationManager\$Stub\$Proxy", cLocationManager.classLoader)
//        }.onSuccess {
//            it.declaredMethods.forEach {
//                XposedBridge.hookMethod(it, object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam?) {
//                        if (param == null) return
//
//                        XposedBridge.log("[Portal] ILocationManager.Stub.Proxy: c = ${param.thisObject?.javaClass}, m = ${param.method}")
//                    }
//                })
//            }
//        }
    }

    private inline fun handleInstruction(command: String, rely: Bundle): Boolean {
        // provider 入口：任何应用都能调用 ⇒ 由 handleInstruction 内的调用者门禁把关
        return RemoteCommandHandler.handleInstruction(
            command, rely, RemoteCommandHandler.Origin.PROVIDER
        )
    }

    /**
     * 取位请求（getCurrentLocation）：允许并注入，并登记一次性投递表
     *
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（仅整体缩进对齐）。注册顺序必须与
     * [onService] 里的调用顺序一致 —— 同一方法上的多个回调按注册顺序执行。
     */

    /**
     * 取位（getLastLocation）：立即更新虚拟坐标/计步/路线推进。
     *
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（含段首横幅、仅整体缩进对齐）。
     * 注册顺序必须与 [onService] 里的调用顺序一致。
     */
    private fun hookLastLocation(cILocationManager: Class<*>) {
        // ===== 段 1 · 取位（getLastLocation）：立即更新虚拟坐标/计步/路线推进 =====
        if(cILocationManager.hookAllMethods("getLastLocation", afterHook {
                // android 7.0.0 ~ 10.0.0
                // Location getLastLocation(in LocationRequest request, String packageName);
                // android 11.0.0
                // Location getLastLocation(in LocationRequest request, String packageName, String featureId);
                // android 12.0.0 ~ 15.0.0
                // @nullable Location getLastLocation(String provider, in LastLocationRequest request, String packageName, @nullable String attributionTag);
                // Why are there so... I'm really speechless

                // Virtual Coordinate: Instantly update the latest virtual coordinates
                // Roulette Move: Each request moves a certain distance
                // Route Simulation: Move according to a preset route
                //val uid = FqlUtils.getCallerUid()
                // Determine whether it is an app that needs a hook
                if (!FakeLoc.enable) return@afterHook

                // **不凭空生成**：框架说"没有上一次位置"（result == null）时保持 null。
                // 旧实现在这里造一帧 Location("gps") 并当成 result 返回 —— 把"没有位置"
                // 改成了"有一次定位"，既违背「拦截-修改-转发」的口径，也让应用拿到一个
                // 它自己都没期待过的 fix。应用真要位置会走请求更新/getCurrentLocation，
                // 那两条路径我们照常喂（见段 2 与段 6）。
                val location = result as? Location ?: return@afterHook

                result = injectLocation(location)

                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getLastLocation: injected! $result")
                }
        }).isEmpty()) {
            Logger.error("hook getLastLocation failed")
        }

    }
    private fun hookCurrentLocation(cILocationManager: Class<*>) {
        cILocationManager.hookAllMethods("getCurrentLocation", beforeHook {
            // 不同 Android 版本参数位置不同：
            //  老版本: getCurrentLocation(LocationRequest, ILocationCallback, String packageName)
            //  新版本: getCurrentLocation(String provider, LocationRequest, ILocationCallback, String packageName, ...)
            // 所以不能用固定索引 args[2]，改为按“存在 onLocation 回调方法”的特征查找。
            val callback = args.firstOrNull { arg ->
                arg != null && arg.javaClass.methods.any { it.name == "onLocation" }
            } as? IInterface ?: return@beforeHook

            if (FakeLoc.enableDebugLog) {
                Logger.debug("getCurrentLocation: injected!")
            }

            // 允许并注入（语义已定）：**不再 result = null 吞掉调用**。
            // 未开模拟 = 完全透传真实位置；模拟会话中：
            //   ① 回调上改写——框架真投递时把位置换成模拟位置；
            //   ② 登记进一次性投递表——无真实定位源（室内无星）时，由推送链
            //      （callOnLocationChanged，与监听器同一节拍）补投一次模拟位置。
            // 旧实现两条都不做：调用被吞 → 应用一个位置都拿不到，
            // 且传感器模拟的取数链也跟着断（见 SystemSensorManagerHook）。
            if (!FakeLoc.enable) return@beforeHook

            val classCallback = callback.javaClass
            classCallback.onceHookAllMethod("onLocation", beforeHook onLocation@ {
                val location = args[0] as? Location ?: return@onLocation

                // 框架已投递：从一次性投递表里摘下，避免同一回调被投两次
                (thisObject as? IInterface)?.let { self ->
                    oneShotCallbacks.removeIf { it.callback.asBinder() == self.asBinder() }
                }

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("onLocation(getCurrentLocation): injected!")
                }

                args[0] = injectLocation(location)
            })
            oneShotCallbacks.removeIf { it.callback.asBinder() == callback.asBinder() }
            oneShotCallbacks.add(OneShotCallback(callback, SystemClock.elapsedRealtimeNanos()))
        })

        // ===== 段 7 · 命令通道（sendExtraCommand → portal provider 分发）=====
    }

    /**
     * 命令通道与 provider 开关的拦截（sendExtraCommand → portal 分发）
     *
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（仅整体缩进对齐）。注册顺序必须与
     * [onService] 里的调用顺序一致 —— 同一方法上的多个回调按注册顺序执行。
     */
    private fun hookExtraCommand(cILocationManager: Class<*>) {
        cILocationManager.hookAllMethods("sendExtraCommand", beforeHook {
            if (args.size < 3) return@beforeHook

            val provider = args[0] as String
            val command = args[1] as String
            val outResult = args[2] as? Bundle

            if (FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused") {
                result = false
                return@beforeHook
            }

            // If the GPS provider is enabled, the GPS provider is disabled
            if(provider == "gps" && FakeLoc.enable) {
                result = false
                return@beforeHook
            }

            if(provider == "LOCATION_BIG_DATA") {
                result = false
                return@beforeHook
            }

            // Not the provider of the portal, does not process
            if (provider != "portal") {
                if (FakeLoc.enableDebugLog)
                    Logger.debug("sendExtraCommand provider: $provider, command: $command, result: $result")
                return@beforeHook
            }
            if (outResult == null) return@beforeHook

            if (handleInstruction(command, outResult)) {
                result = true
            }
        })

        // ===== 段 8 · provider 可见性（isProviderEnabled / ForUser，含握手门禁）=====
    }

    /**
     * provider 可见性（isProviderEnabled / ForUser，含握手门禁）
     *
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（仅整体缩进对齐）。注册顺序必须与
     * [onService] 里的调用顺序一致 —— 同一方法上的多个回调按注册顺序执行。
     */
    private fun hookProviderEnabled(cILocationManager: Class<*>) {
        if(
        // boolean isProviderEnabledForUser(String provider, int userId); from android 9.0.0
            XposedBridge.hookAllMethods(
                cILocationManager,
                "isProviderEnabledForUser",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.size < 2 || param.args[0] == null) return
                        val provider = param.args[0] as String
                        var userId = param.args[1] as Int
                        if (provider == "portal") {
                            if (userId == 0) {
                                userId = BinderUtils.getCallerUid()
                            }
                            param.result = BinderUtils.isLocationProviderEnabled(userId)
                        } else if (FakeLoc.enable && provider == "network") {
                            param.result = !FakeLoc.enable
                        } else if (FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused") {
                            param.result = false
                            return
                        } else {
                            if (FakeLoc.enableDebugLog) {
                                 Logger.debug("isProviderEnabledForUser provider: $provider, userId: $userId")
                            }
                        }
                    }
                }).isEmpty()
        ) {
            // boolean isProviderEnabled(String provider);
            XposedBridge.hookAllMethods(
                cILocationManager,
                "isProviderEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.isEmpty() || param.args[0] == null) return
                        val provider = param.args[0] as String
                        val userId = BinderUtils.getCallerUid()
                        if (provider == "portal" && BinderUtils.isLocationProviderEnabled(userId)) {
                            param.result = true
                        } else if (FakeLoc.enable && provider == "network") {
                            param.result = !FakeLoc.enable
                        } else if (FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused") {
                            param.result = false
                            return
                        }
                    }
                })
        }


        // ===== 段 9 · 厂商/第三方 SDK 的"额外定位控制器包"抑制 =====
    }

    /**
     * 厂商/第三方 SDK 的"额外定位控制器包"抑制（AMAP 等）
     *
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（仅整体缩进对齐）。注册顺序必须与
     * [onService] 里的调用顺序一致 —— 同一方法上的多个回调按注册顺序执行。
     */
    private fun hookVendorControllerPackage(cILocationManager: Class<*>) {
        // F**k You! AMAP Service!
        XposedBridge.hookAllMethods(cILocationManager, "setExtraLocationControllerPackageEnabled", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enable) {
                    param.args[0] = false
                }
            }
        })

        XposedBridge.hookAllMethods(cILocationManager, "setExtraLocationControllerPackage", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enable) {
                    param.result = null
                }
            }
        })

        startGnssStatusPusher()
        startLocationKeepAlive()

    }

    /**
     * 监听器注册家族：requestLocationUpdates / unregisterLocationListener / registerLocationListener。
     * 
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（含段首横幅、仅整体缩进对齐）。
     * 注册顺序必须与 [onService] 里的调用顺序一致。
     */
    private fun hookListenerRegistration(cILocationManager: Class<*>) {
        // ===== 段 2 · 监听器注册家族（requestLocationUpdates / (un)registerLocationListener）=====
        // android 12 and later remove `requestLocationUpdates`
        cILocationManager.hookAllMethods("requestLocationUpdates", beforeHook {
            // android 7.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener, String packageName);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in Location location);
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //    void onProviderEnabled(String provider);
            //    void onProviderDisabled(String provider);
            //}
            //
            // android 7.1.1 ~ 9.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            //            in PendingIntent intent, String packageName);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in Location location);
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //    void onProviderEnabled(String provider);
            //    void onProviderDisabled(String provider);
            //
            // android 10.0.0
            // oneway interface ILocationListener
            //{
            //    @UnsupportedAppUsage
            //    void onLocationChanged(in Location location);
            //    @UnsupportedAppUsage
            //    void onProviderEnabled(String provider);
            //    @UnsupportedAppUsage
            //    void onProviderDisabled(String provider);
            //    // --- deprecated ---
            //    @UnsupportedAppUsage
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //}
            //
            // android 11.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            //            in PendingIntent intent, String packageName, String featureId, String listenerId);
            //
            // oneway interface ILocationListener
            //{
            //    @UnsupportedAppUsage
            //    void onLocationChanged(in Location location);
            //    @UnsupportedAppUsage
            //    void onProviderEnabled(String provider);
            //    @UnsupportedAppUsage
            //    void onProviderDisabled(String provider);
            //    // called when the listener is removed from the server side; no further callbacks are expected
            //    void onRemoved();
            //}
            // android 12 and later
            // remove this method
            val provider = kotlin.runCatching {
                XposedHelpers.callMethod(args[0], "getProvider") as? String
            }.getOrNull() ?: "gps"

            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("requestLocationUpdates: listener is null: $method")
                return@beforeHook
            }

            if(FakeLoc.enableDebugLog) {
                Logger.debug("requestLocationUpdates: injected! $listener")
            }

            // 被吞掉（blocked=true）的注册框架不会回调 → 必须由推送链供帧。
            // （旧实现在此之后还有一段 `disableFusedLocation && provider=="fused"` 分支，
            //   由于上面 FakeLoc.enable 分支已 return，实际不可达——已删除。）
            addLocationListenerInner(provider, listener, blocked = FakeLoc.enable)

            if (FakeLoc.enable) {
                result = null
            }
        })
        cILocationManager.hookAllMethods("removeUpdates", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("removeUpdates: listener is null: $method")
                return@afterHook
            }
            if(FakeLoc.enableDebugLog) {
                Logger.debug("removeUpdates: injected! $listener")
            }

            removeLocationListenerInner(listener)
        })
        cILocationManager.hookAllMethods("registerLocationListener", beforeHook {
            // android 12 ~ android 15
            // void registerLocationListener(String provider, in LocationRequest request, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in List<Location> locations, in @nullable IRemoteCallback onCompleteCallback);
            //    void onProviderEnabledChanged(String provider, boolean enabled);
            //    void onFlushComplete(int requestCode);
            //}
            val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                kotlin.runCatching {
                    XposedHelpers.callMethod(args[1], "getProvider") as? String
                }.getOrNull()
            } else {
                args[0] as? String
            } ?: "gps"

            // 网络注册拦截仅在模拟会话期间（enable=true）生效；未开模拟时完全透传，
            // 否则所有依赖网络定位的应用拿不到真实位置、状态栏也不会有定位图标。
            if (FakeLoc.enable && provider == "network") {
                if (FakeLoc.enableDebugLog) Logger.debug("Blocked network provider registration")
                result = null
                return@beforeHook
            }


            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("registerLocationListener: listener is null: $method")
                return@beforeHook
            }

            if(FakeLoc.enableDebugLog) {
                Logger.debug("registerLocationListener: injected! $listener, from ${BinderUtils.getUidPackageNames()}")
            }

            addLocationListenerInner(provider, listener, blocked = false)

            // 持续注册同样**不拦**（语义已定：允许并注入）：放行真实注册，帧在
            // 各注入关口被改为模拟值，并由推送链（callOnLocationChanged）持续供帧。
            // 拦掉一次「注册成功但永远没有回调」= 真机上不存在的异常态，本身就是特征；
            // 而且持续取位是实体运动类应用的主数据源，拦了它连模拟数据都送不进去。

            if (FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused") {
                result = null
                return@beforeHook
            }
        })
        cILocationManager.hookAllMethods("unregisterLocationListener", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("unregisterLocationListener: listener is null: $method")
                return@afterHook
            }
            if(FakeLoc.enableDebugLog) {
                Logger.debug("unregisterLocationListener: injected! $listener")
            }

            removeLocationListenerInner(listener)
        })
    }

    /**
     * GNSS 批量回调（条件块）：addGnssBatchingCallback 内再挂 onLocationBatch 注入。
     * 
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（含段首横幅、仅整体缩进对齐）。
     */
    private fun hookGnssBatchingCallback(cILocationManager: Class<*>) {
        // ===== 段 3 · GNSS 批量回调（条件块：onLocationBatch 注入）=====
        run {
            cILocationManager.hookAllMethods("addGnssBatchingCallback", beforeHook {
                if (hasThrowable() || args.isEmpty() || args[0] == null) return@beforeHook
                val callback = args[0] ?: return@beforeHook
                val classCallback = callback.javaClass

                if(FakeLoc.enableDebugLog) {
                    Logger.debug("addGnssBatchingCallback: injected!")
                }

                classCallback.onceHookAllMethod("onLocationBatch", beforeHook onLocationBatch@ {
                    if (args.isEmpty()) return@onLocationBatch

                    if (!FakeLoc.enable) {
                        return@onLocationBatch
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("onLocationBatch: injected!")
                    }

                    // onLocationBatch(List<Location> locations, @nullable IRemoteCallback cb)
                    // 也可能是单 Location 的旧版回调。两种形态都要支持，避免 List 强转 Location 崩溃。
                    when (val data = args[0] ?: return@onLocationBatch) {
                        is Location -> {
                            args[0] = injectLocation(data)
                        }
                        is List<*> -> {
                            args[0] = data.mapNotNull { it as? Location }.map { injectLocation(it) }
                        }
                        else -> {
                            Logger.error("onLocationBatch: unknown arg type ${data.javaClass.name}")
                        }
                    }
                })
            })
        }
    }

    /**
     * 围栏 / 取址 / 测试 provider：requestGeofence、(add/remove)TestProvider、getFromLocation*。
     * 
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（含段首横幅、仅整体缩进对齐）。
     */
    private fun hookGeofenceAndTestProvider(cILocationManager: Class<*>) {
        // ===== 段 4 · 围栏 / 取址 / 测试 provider =====
        cILocationManager.hookAllMethods("requestGeofence", beforeHook {
            if (FakeLoc.enable && FakeLoc.disableRequestGeofence && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("requestGeofence: injected!")
                }
                result = null
            }
        })
//        cILocationManager.hookAllMethods("removeGeofence", beforeHook {
//        })

        cILocationManager.hookAllMethods("getFromLocation", beforeHook {
            if (FakeLoc.enable && FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getFromLocation: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("getFromLocationName", beforeHook {
            if (FakeLoc.enable && FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getFromLocationName: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("addTestProvider", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("addTestProvider: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("removeTestProvider", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("removeTestProvider: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("setTestProviderLocation", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("setTestProviderLocation: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("setTestProviderEnabled", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("setTestProviderEnabled: injected!")
                }
                result = null
            }
        })
    }

    /**
     * GNSS 状态与批处理：registerGnssStatusCallback 入队 + 主动推送、start/stopGnssBatch、requestListenerFlush。
     * 
     * 从 [onService] 拆出的具名小节：**内容逐字未改**（含段首横幅、仅整体缩进对齐）。
     */
    private fun hookGnssStatusAndBatch(cILocationManager: Class<*>) {
        // ===== 段 5 · GNSS 状态与批处理（回调入队 + 主动推送 + flush）=====
        // ============ GNSS 状态注入（适配 SDK 36/ColorOS） ============
        // registerGnssStatusCallback：
        // 1) 回调对象入队（死亡自动移除），供主动推送使用；
        // 2) 安装 onSvStatusChanged 注入 hook——方法查找用 methods（public，含继承/接口），
        //    不再用 declaredMethods：实测 SDK 36 上 Proxy 类声明差异导致 onceHookAllMethod
        //    找不到（find onSvStatusChanged failed!），卫星注入 hook 装不上、雷达恒 0G。
        // 3) 模拟开启时立即主动推送一次。
        // 主动推送由 GnssStatusPusher 守护线程驱动：即使室内 GNSS 引擎闲置、系统从不回调，
        // 雷达也能持续收到模拟卫星数据（与 callOnLocationChanged 同机制）。
        XposedBridge.hookAllMethods(cILocationManager, "registerGnssStatusCallback", object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if(param == null || param.args.isEmpty() || param.args[0] == null) return

                    val callback = param.args[0] ?: return
                    val cIGnssStatusListener = callback.javaClass

                    if(FakeLoc.enableDebugLog) {
                        Logger.debug("registerGnssStatusCallback: injected! ${cIGnssStatusListener.name}")
                    }

                    val listener = callback as? IInterface ?: return
                    if (!gnssStatusListeners.contains(listener)) {
                        val mDeathRecipient = object: IBinder.DeathRecipient {
                            override fun binderDied() {}
                            override fun binderDied(who: IBinder) {
                                who.unlinkToDeath(this, 0)
                                gnssStatusListeners.remove(listener)
                            }
                        }
                        kotlin.runCatching { listener.asBinder().linkToDeath(mDeathRecipient, 0) }
                        gnssStatusListeners.add(listener)
                    }

                    hookGnssStatusListener(cIGnssStatusListener)

                    if (FakeLoc.enableMockGnss) {
                        pushGnssStatus()
                    }
                }
            })

        cILocationManager.hookAllMethods("unregisterGnssStatusCallback", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: return@afterHook
            if (FakeLoc.enableDebugLog) {
                Logger.debug("unregisterGnssStatusCallback: ${listener.javaClass.name}")
            }
            gnssStatusListeners.remove(listener)
        })
        // android 11+
        // @EnforcePermission("LOCATION_HARDWARE")
        // void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
        //
        // void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
        cILocationManager.hookAllMethods("startGnssBatch", beforeHook {
            if(FakeLoc.enableDebugLog) {
                Logger.debug("startGnssBatch: injected!")
            }

            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                    Logger.error("startGnssBatch: listener is null: $method")
                    return@beforeHook
                }

                addLocationListenerInner("GnssBatch", listener, blocked = false)
            }
        })
        cILocationManager.hookAllMethods("stopGnssBatch", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("stopGnssBatch: injected!")
                }
            }
            //locationListeners.removeIf { it.first == "GnssBatch" }
        })

        //  void requestListenerFlush(String provider, in ILocationListener listener, int requestCode);
        cILocationManager.hookAllMethods("requestListenerFlush", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("requestListenerFlush: injected!")
                }

                val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                    Logger.error("requestListenerFlush: listener is null: $method")
                    return@beforeHook
                }

                // flush = 「把缓冲位置都给我」：同样允许并注入（语义与其余取位路径一致）。
                // 旧实现 result = null 吞掉 flush 请求 → 应用等不到 flush 回调，是真机上
                // 不存在的异常态，本身就构成特征。
                addLocationListenerInner("gps", listener, blocked = false)
            }
        })
    }
}

private fun Random.nextFloat(min: Float, max: Float): Float {
    return nextFloat() * (max - min) + min
}
