package moe.fuqiuluo.xposed.hooks.sensor

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.fuqiuluo.xposed.utils.Logger
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 运行时投递通道（**只在 system_server 内**）—— 「投递 100% 由我们掌握」的那条路。
 *
 * 背景：poll 路径（[BinderSensorNative]）拿得到内容控制权，但**投递节拍**受框架的 HAL
 * 轮询支配（只订阅 on-change 传感器时，框架长时间阻塞，注入只能零星到达）。
 * 框架为"没有 HAL 背书的传感器"准备的机制是**运行时传感器**，它由
 * `SensorService::RuntimeSensorHandler` 自己的线程投递，完全不碰 HAL poll。
 *
 * 关键点（全部在真机固件上核实，见 `docs/binder-sensor-mock.md`「架构翻新」）：
 * 1. 这套 API **不需要写原生代码**：框架把 `registerRuntimeSensorNative` /
 *    `sendRuntimeSensorEventNative` / `unregisterRuntimeSensorNative` 作为**私有静态 JNI**
 *    挂在 `com.android.server.sensors.SensorService` 上，回调
 *    `SensorManagerInternal.RuntimeSensorCallback` 是**接口**（用 [Proxy] 实现即可）。
 *    我们只需要它的 `long mPtr`（原生 `NativeSensorService*`）。
 * 2. 投递是**广播式**的：`processRuntimeSensorEvents` 以 `scratch == nullptr` 调
 *    `sendEvents`，该分支不做 handle 过滤，整批写进每个活跃连接，真正的路由在**客户端**
 *    （按事件里的 `sensor` handle 查自己的传感器表）。所以**事件填真实 HAL handle 就能直达
 *    应用监听器**——这就是后续用来接管投递的机制。
 * 3. 必须先注册一个**载体**传感器：事件缓冲（256×104）与 `RuntimeSensorHandler` 线程
 *    只在第一次成功注册时创建。载体要**对客户端不可见** ⇒ `deviceId` 不能是
 *    `RuntimeSensor::DEFAULT_DEVICE_ID`（= 0，`SensorList::getUserSensors` 会据此过滤，
 *    已在设备二进制里逐条核对），并且类型用私有类型、`flags = 0`。
 * 4. `LocalService.sendSensorEvent` 有 `mRuntimeSensorHandles` 白名单，而原生
 *    `sendRuntimeSensorEvent` 不检查 handle —— 本类直接调**私有静态 JNI**，绕过白名单，
 *    从而能对**真实 handle** 投递（后续阶段用）。
 *
 * 安全边界：全程只有"反射 + 框架正规 JNI 入口 + 一个 [Proxy] 对象"，参数都是 JNI 平凡类型，
 * 不构造任何原生对象、不碰虚表/RefBase，最坏结果是抛异常 → 功能惰性（poll 路径照旧兜底）。
 */
internal object SystemRuntimeChannel {

    private const val CLS_SERVICE = "com.android.server.sensors.SensorService"
    private const val CLS_INTERNAL = "com.android.server.sensors.SensorManagerInternal"
    private const val CLS_CALLBACK =
        "com.android.server.sensors.SensorManagerInternal\$RuntimeSensorCallback"
    private const val CLS_LOCAL_SERVICES = "com.android.server.LocalServices"

    /** 载体设备号：必须 != 0（0 是 DEFAULT_DEVICE_ID，那会让载体现身客户端列表） */
    private const val CARRIER_DEVICE_ID = 0x0FA0
    private const val TYPE_DEVICE_PRIVATE_BASE = 0x10000
    private const val CARRIER_NAME = "portalex-runtime"
    private const val CARRIER_VENDOR = "portalex"

    /** 载体注册失败后的退避（supervisor tick 数，50ms/tick） */
    private const val FAIL_BACKOFF_TICKS = 40

    /** 解析失败后的重试间隔（解析很便宜，失败多半是时序问题——例如类还没被加载） */
    private const val RESOLVE_RETRY_NANOS = 10_000_000_000L

    @Volatile private var resolved = false
    @Volatile private var resolveError: String? = null
    @Volatile private var nextAttemptNanos = 0L
    @Volatile private var hintLoader: ClassLoader? = null
    private var loader: ClassLoader? = null

    private var serviceCls: Class<*>? = null
    private var internalCls: Class<*>? = null
    private var callbackCls: Class<*>? = null
    private var mPtrField: Field? = null
    private var registerNative: Method? = null
    private var sendNative: Method? = null
    private var unregisterNative: Method? = null
    private var callbackProxy: Any? = null

    @Volatile private var serviceInstance: Any? = null
    @Volatile private var ptr = 0L
    @Volatile private var carrierHandle = 0
    @Volatile private var phase = "idle"
    @Volatile private var lastError = ""
    @Volatile private var failTicks = 0
    @Volatile private var sentCount = 0L
    @Volatile private var configCalls = 0
    @Volatile private var sendFails = 0L

    /** 是否已注册载体（后续阶段据此决定"投递走哪条路"） */
    val carrierReady: Boolean get() = carrierHandle != 0
    val nativePtr: Long get() = ptr

    // ------------------------------------------------------------------
    // 解析与实例捕获（S1：只读，无副作用）
    // ------------------------------------------------------------------

    /**
     * 解析类/方法/字段，并装上构造函数 hook 以捕获 `SensorService` 实例。
     *
     * **可重试**：失败只记日志并退避 [RESOLVE_RETRY_NANOS]，下次（开关变化/载体引导时）
     * 再试——失败原因常常只是时序（类还没加载）。
     */
    fun attach(classLoader: ClassLoader?) {
        if (resolved) return
        if (classLoader != null) hintLoader = classLoader
        val now = System.nanoTime()
        if (now < nextAttemptNanos) return
        synchronized(this) {
            if (resolved || System.nanoTime() < nextAttemptNanos) return
            // 注意：**不能想当然用 ActivityThread 的 classLoader**。本机实测它在 system_server 里
            // 是 BootClassLoader，看不到 services.jar 里的 `com.android.server.*`
            // （报 ClassNotFoundException 就是踩了这一条）。system_server 自己的类是
            // **系统类加载器**（`ClassLoader.getSystemClassLoader()`）加载的，
            // 所以按候选列表逐个试，取第一个真能看见目标类的。
            val cl = loader ?: findLoader(hintLoader)
            if (cl == null) {
                resolveError = "no ClassLoader can see $CLS_SERVICE"
                phase = "unresolved"
                nextAttemptNanos = System.nanoTime() + RESOLVE_RETRY_NANOS
                Logger.error("SystemRuntimeChannel: 没有任何类加载器能看到 $CLS_SERVICE")
                return
            }
            loader = cl
            var step = "Class.forName($CLS_SERVICE)"
            val r = runCatching {
                val svc = Class.forName(CLS_SERVICE, false, cl)
                step = "Class.forName($CLS_INTERNAL)"
                val internal = Class.forName(CLS_INTERNAL, false, cl)
                step = "Class.forName($CLS_CALLBACK)"
                val cb = Class.forName(CLS_CALLBACK, false, cl)

                step = "getDeclaredMethod(registerRuntimeSensorNative)"
                val reg = svc.getDeclaredMethod(
                    "registerRuntimeSensorNative",
                    Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, String::class.java, String::class.java,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, cb
                )
                step = "getDeclaredMethod(sendRuntimeSensorEventNative)"
                val send = svc.getDeclaredMethod(
                    "sendRuntimeSensorEventNative",
                    Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                    FloatArray::class.java
                )
                step = "getDeclaredMethod(unregisterRuntimeSensorNative)"
                val unreg = svc.getDeclaredMethod(
                    "unregisterRuntimeSensorNative",
                    Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                step = "getDeclaredField(mPtr)"
                val ptrField = svc.getDeclaredField("mPtr")
                step = "setAccessible"
                listOf(reg, send, unreg).forEach { it.isAccessible = true }
                ptrField.isAccessible = true

                step = "store"
                serviceCls = svc
                internalCls = internal
                callbackCls = cb
                registerNative = reg
                sendNative = send
                unregisterNative = unreg
                mPtrField = ptrField
            }
            r.onFailure {
                resolveError = "$step: ${it.javaClass.simpleName}: ${it.message}"
                phase = "unresolved"
                nextAttemptNanos = System.nanoTime() + RESOLVE_RETRY_NANOS
                Logger.error("SystemRuntimeChannel: 解析失败，运行时通道不生效：$resolveError", it)
                return
            }
            resolved = true
            resolveError = null
            Logger.info("SystemRuntimeChannel: 已解析（register/send/unregister/mPtr 全部就位）")
            installCtorHook()
            refreshInstance()
        }
    }

    /** 找一个**真能看见** `com.android.server.sensors.SensorService` 的类加载器。 */
    private fun findLoader(hint: ClassLoader?): ClassLoader? {
        val candidates = LinkedHashSet<ClassLoader>()
        // LSPosed 交给 hook 的 classLoader 是**系统服务的类加载器**（既有 hook 也用它找
        // com.android.server.*），优先信它；其余候选只是兜底。
        hint?.let { candidates.add(it) }
        runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()?.let { candidates.add(it) }
        runCatching { ClassLoader.getSystemClassLoader()?.parent }.getOrNull()?.let { candidates.add(it) }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentActivityThread").invoke(null).javaClass.classLoader
        }.getOrNull()?.let { candidates.add(it) }
        for (cl in candidates) {
            if (runCatching { Class.forName(CLS_SERVICE, false, cl) }.isSuccess) {
                Logger.info("SystemRuntimeChannel: 类加载器选定 ${cl.javaClass.name}")
                return cl
            }
        }
        Logger.error(
            "SystemRuntimeChannel: 候选类加载器都看不到 $CLS_SERVICE：" +
                    candidates.joinToString { it.javaClass.name }
        )
        return null
    }

    /** 构造函数 hook：boot 期实例化时把实例抓住（`mPtr` 随后由异步任务写入）。 */    private fun installCtorHook() {
        val cls = serviceCls ?: return
        runCatching {
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    serviceInstance = param.thisObject
                    Logger.info("SystemRuntimeChannel: 捕获 SensorService 实例（构造函数）")
                }
            })
        }.onFailure { Logger.warn("SystemRuntimeChannel: 构造函数 hook 失败：${it.message}") }
    }

    /**
     * 拿实例并刷新 `mPtr`。两条互为备份的路：
     * 1. 构造函数 hook 捕获的实例；
     * 2. `LocalServices.getService(SensorManagerInternal)` → `LocalService` → 其 `this$0`。
     */
    private fun refreshInstance(): Any? {
        var inst = serviceInstance
        if (inst == null) {
            inst = runCatching {
                val ls = Class.forName(CLS_LOCAL_SERVICES, false, serviceCls?.classLoader)
                val getService = ls.getMethod("getService", Class::class.java)
                getService.isAccessible = true
                val local = getService.invoke(null, internalCls)
                outerOf(local)
            }.onFailure {
                Logger.debug("SystemRuntimeChannel: LocalServices 取实例失败：${it.message}")
            }.getOrNull()
            if (inst != null) {
                serviceInstance = inst
                Logger.info("SystemRuntimeChannel: 捕获 SensorService 实例（LocalServices）")
            }
        }
        inst?.let {
            runCatching { ptr = mPtrField?.getLong(it) ?: 0L }
                .onFailure { lastError = "mPtr: ${it.message}" }
        }
        return inst
    }

    /** 内部类实例的外层对象（标准合成字段 `this$0`；找不到就按类型扫）。 */
    private fun outerOf(local: Any?): Any? {
        if (local == null) return null
        val f = local.javaClass.declaredFields.firstOrNull { field ->
            !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    (field.name == "this\$0" || serviceCls?.isAssignableFrom(field.type) == true)
        } ?: return null
        f.isAccessible = true
        return f.get(local)
    }

    // ------------------------------------------------------------------
    // 载体（S2）
    // ------------------------------------------------------------------

    /**
     * 确保载体传感器已注册（幂等、可重试）。返回载体 handle，0 表示尚未就绪。
     *
     * 它的作用不是"提供数据"，而是**启动投递机制**：事件缓冲与 `RuntimeSensorHandler`
     * 线程都只在第一次成功注册时创建。载体本身对客户端不可见（`deviceId != 0`）。
     */
    fun ensureCarrier(): Int {
        if (carrierHandle != 0) return carrierHandle
        if (!resolved) {
            attach(null)          // 失败可重试（时序类失败很常见）
            if (!resolved) return 0
        }
        if (failTicks > 0) {
            failTicks--
            return 0
        }
        synchronized(this) {
            if (carrierHandle != 0) return carrierHandle
            val inst = refreshInstance()
            if (inst == null) {
                failTicks = FAIL_BACKOFF_TICKS
                phase = "no-instance"
                lastError = "SensorService 实例未取到"
                Logger.warn("SystemRuntimeChannel: 实例未取到，稍后重试")
                return 0
            }
            if (ptr == 0L) {
                // 原生服务在构造时异步启动（SystemServerInitThreadPool），早于此就是 0
                phase = "ptr-pending"
                return 0
            }
            val cb = callbackProxy ?: buildCallback().also { callbackProxy = it }
            if (cb == null) {
                failTicks = FAIL_BACKOFF_TICKS
                phase = "no-callback"
                return 0
            }
            val h = runCatching {
                registerNative!!.invoke(
                    null, ptr, CARRIER_DEVICE_ID, TYPE_DEVICE_PRIVATE_BASE,
                    CARRIER_NAME, CARRIER_VENDOR,
                    1.0f, 0.001f, 0.0f, 0, 0, 0, cb
                ) as Int
            }.onFailure {
                lastError = it.cause?.message ?: it.message ?: it.toString()
                Logger.error("SystemRuntimeChannel: 注册载体失败：$lastError", it)
            }.getOrDefault(0)
            if (h <= 0) {
                failTicks = FAIL_BACKOFF_TICKS
                phase = "carrier-failed"
                return 0
            }
            carrierHandle = h
            phase = "carrier-ok"
            lastError = ""
            Logger.info(
                "SystemRuntimeChannel: 载体已注册 handle=${hex(h.toLong())} " +
                        "(deviceId=0x${CARRIER_DEVICE_ID.toString(16)}, type=$TYPE_DEVICE_PRIVATE_BASE, ptr=${hex(ptr)})"
            )
            return h
        }
    }

    /** 回调代理：框架只在客户端使能/改速率时回调我们；数据是我们主动推的，这里只需回 0。 */
    private fun buildCallback(): Any? {
        val cls = callbackCls ?: return null
        val loader = cls.classLoader ?: return null
        return runCatching {
            Proxy.newProxyInstance(loader, arrayOf(cls), InvocationHandler { _, method, args ->
                when {
                    method.name == "toString" -> "PortalExRuntimeSensorCallback"
                    method.name == "onConfigurationChanged" -> {
                        configCalls++
                        Logger.info(
                            "SystemRuntimeChannel: 回调 onConfigurationChanged handle=0x" +
                                    (args?.getOrNull(0) as? Int ?: 0).toString(16) +
                                    " enabled=${args?.getOrNull(1)} " +
                                    "periodUs=${args?.getOrNull(2)} batchUs=${args?.getOrNull(3)}"
                        )
                        0
                    }
                    method.name == "onDirectChannelCreated" -> 0
                    method.name == "onDirectChannelConfigured" -> 0
                    method.returnType == java.lang.Boolean.TYPE -> false
                    method.returnType == java.lang.Integer.TYPE -> 0
                    method.returnType == java.lang.Long.TYPE -> 0L
                    else -> null
                }
            })
        }.onFailure {
            Logger.error("SystemRuntimeChannel: 构造回调代理失败：${it.message}", it)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 投递（S3 起使用）
    // ------------------------------------------------------------------

    /** 单帧上限（与原生层一致）；缓冲复用，避免每帧分配 */
    private const val MAX_FRAME = 32
    private val frameMeta = LongArray(MAX_FRAME * 4)
    private val frameValues = FloatArray(MAX_FRAME * 16)
    private val valueScratch = HashMap<Int, FloatArray>()

    @Volatile private var delivering = false
    @Volatile private var pumpFrames = 0L
    @Volatile private var pumpEvents = 0L

    val isDelivering: Boolean get() = delivering

    /**
     * 开始投递：先把原生层的 poll 路径降级为"只压制"，之后由 [pump] 驱动生成与发送。
     *
     * **必须在泵线程起来之前调用**（否则会出现"两边都不发"的空窗）；失败返回 false，
     * 调用方应保持 poll 路径不变（功能不降级）。
     */
    fun startDelivery(): Boolean {
        if (delivering) return true
        if (!carrierReady) return false
        return runCatching {
            BinderSensorNative.setRuntimeClock(true)
            delivering = true
            Logger.info("SystemRuntimeChannel: 投递已交给运行时通道（poll 路径转纯压制）")
            true
        }.onFailure {
            lastError = "startDelivery: ${it.message}"
            Logger.error("SystemRuntimeChannel: 切换投递节拍失败", it)
        }.getOrDefault(false)
    }

    /** 停投递并**立刻**把节拍交回 poll 路径（顺序不能反：先停泵再切时钟）。 */
    fun stopDelivery() {
        if (!delivering) return
        delivering = false
        runCatching { BinderSensorNative.setRuntimeClock(false) }
            .onFailure { Logger.warn("SystemRuntimeChannel: 交回 poll 节拍失败：${it.message}") }
        Logger.info(
            "SystemRuntimeChannel: 投递交回 poll 路径（frames=$pumpFrames sent=$sentCount errors=$sendFails）"
        )
    }

    /**
     * 一帧：取到期事件并逐条投递。[nowNanos] 必须是 `CLOCK_BOOTTIME`（与框架事件同基）。
     * @return 实际投递成功的事件条数
     */
    fun pump(nowNanos: Long): Int {
        if (!delivering || !carrierReady) return 0
        val n = runCatching { BinderSensorNative.runtimeFrame(nowNanos, frameMeta, frameValues) }
            .onFailure {
                sendFails++
                lastError = "frame: ${it.message}"
            }
            .getOrDefault(0)
        if (n <= 0) return 0
        pumpFrames++
        var sent = 0
        for (i in 0 until n) {
            val handle = frameMeta[i * 4].toInt()
            val type = frameMeta[i * 4 + 1].toInt()
            val ts = frameMeta[i * 4 + 2]
            val count = frameMeta[i * 4 + 3].toInt().coerceIn(1, 16)
            // handle 还没学到就不送：宁可少一条，也不能把 A 传感器的数据写进 B 传感器
            if (handle == 0) continue
            val values = scratchFor(count)
            System.arraycopy(frameValues, i * 16, values, 0, count)
            if (send(handle, type, ts, values)) sent++ else sendFails++
        }
        pumpEvents += sent
        return sent
    }

    private fun scratchFor(n: Int): FloatArray = valueScratch.getOrPut(n) { FloatArray(n) }

    /**
     * 推送一条事件（**handle 可以是真实 HAL handle** —— 原生入口不检查归属）。
     *
     * 值布局必须与框架 JNI 的 switch 一致：加速度/磁场/方向等 → 3 个 float；
     * 步数计数器/检测器 → 1 个 float；旋转矢量等 → 前 N 个 float 原样进 `data`。
     */
    fun send(handle: Int, type: Int, timestampNanos: Long, values: FloatArray): Boolean {
        if (ptr == 0L || carrierHandle == 0) return false
        val ok = runCatching {
            sendNative!!.invoke(null, ptr, handle, type, timestampNanos, values) as Boolean
        }.onFailure {
            lastError = "send: ${it.cause?.message ?: it.message}"
        }.getOrDefault(false)
        if (ok) sentCount++
        return ok
    }

    /** 释放载体（开关关闭时调用；线程与缓冲由框架保留复用）。 */
    fun releaseCarrier() {
        stopDelivery()
        val h = carrierHandle
        if (h == 0 || ptr == 0L) return
        carrierHandle = 0
        runCatching { unregisterNative!!.invoke(null, ptr, h) }
            .onFailure { Logger.warn("SystemRuntimeChannel: 释放载体失败：${it.message}") }
        phase = "released"
        Logger.info("SystemRuntimeChannel: 载体已释放 handle=0x${h.toString(16)}")
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    /** 单行状态（Test 页 / logcat）。指针按无符号十六进制打印（Long 有符号会打印成 0x-4bff…）。 */
    fun status(): String {
        val err = if (lastError.isEmpty()) "" else " err=$lastError"
        return "rtch=$phase resolved=$resolved ptr=${hex(ptr)} " +
                "carrier=${hex(carrierHandle.toLong())} delivering=$delivering " +
                "frames=$pumpFrames events=$pumpEvents sent=$sentCount fails=$sendFails " +
                "cb=$configCalls$err"
    }

    private fun hex(v: Long): String = "0x" + java.lang.Long.toHexString(v)

    /** S1 探针：只解析 + 取实例 + 读 mPtr，不做任何注册/发送。 */
    fun probe(): String {
        if (!resolved) attach(null)
        if (resolved) refreshInstance()
        return status()
    }

    /** 解析失败原因（诊断用；成功时为 null） */
    val resolveFailure: String? get() = resolveError
}
