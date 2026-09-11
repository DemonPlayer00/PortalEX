package moe.fuqiuluo.xposed.hooks.sensor

import android.os.Build
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.ModulePrefs
import java.io.File

/**
 * Binder 外周传感器模拟 —— 原生注入层的 Java 门面（**只在 system_server 里使用**）。
 *
 * 原生库（`libportalsensor.so`）由本模块自己编译，安装到模块 APK 的 lib 目录
 * （`:app` 开了 `useLegacyPackaging`，所以 .so 是被解压到
 * `/data/app/~~xxx/<pkg>-yyy/lib/<abi>/` 的，而不是压在 APK 里）。
 * LSPosed 把模块代码注入进 system_server 时，模块自己的 lib 目录对
 * system_server 是可读可执行的（与 LSPosed 加载模块 dex 同一条许可路径），
 * 因此这里直接按绝对路径 `System.load`。
 *
 * 任何一步失败（找不到 .so / dlopen 报错 / SELinux 拒绝）都只是让这个实验性
 * 功能**不生效**：load 失败返回 false，调用方保持既有行为不变。
 */
internal object BinderSensorNative {

    private const val LIB_NAME = "libportalsensor.so"

    @Volatile private var loaded = false
    @Volatile private var loadFailed = false
    @Volatile private var loadError = ""
    private val lock = Any()

    /** 最近一次装载失败的说明（诊断用） */
    fun lastLoadError(): String =
        "BinderSensorNative: load failed - ${loadError.ifEmpty { "unknown" }}"

    /** 加载原生库。幂等；失败后不再重试（避免每 50ms 刷一次异常）。 */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadFailed) return false
        synchronized(lock) {
            if (loaded) return true
            if (loadFailed) return false
            try {
                val path = libraryPath()
                if (path != null) {
                    System.load(path)
                } else {
                    System.loadLibrary("portalsensor")
                }
                loaded = true
                Logger.info("BinderSensorNative: loaded ($path)")
            } catch (t: Throwable) {
                loadFailed = true
                loadError = t.message ?: t.toString()
                Logger.error("BinderSensorNative: load failed: $loadError", t)
            }
            return loaded
        }
    }

    /**
     * 模块自己的 .so 路径。
     *
     * 注意：LSPosed 注入模块时用的是 `LspModuleClassLoader`，dex 是**在内存里**加载的
     * （`InMemoryDexFile`），因此 `protectionDomain.codeSource.location` 为空——
     * 不能靠它反推 APK。可行的两条路：
     *   1. 该 classloader 的 `toString()` 里带 `module=<apk 绝对路径>`（LSPosed 自己的格式）；
     *   2. 用 system context 的 PackageManager 按模块包名查 `sourceDir`。
     * 拿到 APK 后取同级 `lib/<abi>/`——`:app` 开了 `extractNativeLibs`，.so 是解压出来的，
     * 可以直接 `System.load`。
     */
    private fun libraryPath(): String? {
        val abiDir = when {
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64"
            Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x86_64"
            else -> return null
        }
        for (apk in apkCandidates()) {
            val lib = File(File(apk).parentFile, "lib/$abiDir/$LIB_NAME")
            if (lib.exists()) return lib.absolutePath
        }
        return null
    }

    private fun apkCandidates(): List<String> {
        val out = LinkedHashSet<String>()
        // 1) LspModuleClassLoader.toString() 里的 module=<apk>
        runCatching {
            val s = BinderSensorNative::class.java.classLoader?.toString() ?: return@runCatching
            Regex("module=([^,\\]]+\\.apk)").find(s)?.groupValues?.get(1)?.let { out.add(it) }
        }
        // 2) PackageManager 按模块包名查 sourceDir
        runCatching {
            val pm = moe.fuqiuluo.xposed.utils.BinderUtils.getSystemContext()?.packageManager
            val pkg = ModulePrefs.modulePackage
            pm?.getApplicationInfo(pkg, 0)?.sourceDir?.let { out.add(it) }
        }.onFailure {
            Logger.debug("BinderSensorNative: package lookup failed: ${it.message}")
        }
        return out.toList()
    }

    // ---- native 接口 ----

    /**
     * 装载注入层：把 Java 侧解析出的平台符号偏移交给原生层，由它核对 vtable 槽后改写。
     *
     * [offsets] 顺序（与 native 侧 `install` 约定一致，见 LibSymbols.Resolved.toOffsets）：
     * `[relroAddr, relroSize, pollAidl, pollFmqAidl, pollHidl, pollFmqHidl]`
     *
     * 为什么要 poll **和** pollFmq 两套：AIDL HAL 的 `poll()` 在本机是个
     * `return 0` 的空实现，框架走的是 FMQ 那条路（`SensorService::threadLoop`
     * 先问 supportsMessageQueues，真时调 vtable 的 `pollFmq`）。只挂 `poll`
     * 等于挂在一条没人走的路上——实测就是这个结果。两套都挂，两种 HAL 形态都覆盖。
     */
    external fun install(offsets: LongArray): Boolean

    /** 注入总开关（关 = 真实事件原样放行，不做任何压制/注入） */
    external fun setActive(active: Boolean)

    /**
     * 投递节拍源：true = 运行时通道（Java 侧泵线程驱动生成），false = poll 路径（现状）。
     *
     * 两者**互斥**：打开后 poll 出口只压制真实事件、不再注入（否则同一条事件送两份）；
     * 关闭后立刻回到 poll 驱动，投递不中断。默认 false。
     */
    external fun setRuntimeClock(active: Boolean)

    /**
     * 把**步数两条流**（TYPE_STEP_COUNTER/DETECTOR）改由 poll 路径注入，其余类型仍走运行时通道。
     *
     * 缘由（实测）：Java 客户端从运行时通道拿到的计数器值是**陈旧恒定值**（标记值实验证明我们推的值
     * 没到客户端），而 NDK 客户端能看到真值 —— 该类型在"给 Java 客户端投递"这条路上被换值。
     * poll 路径此前能给应用送到正确值，故把这两条流放回去。
     */
    external fun setStepsViaPoll(on: Boolean)

    /**
     * 取一帧"截至 [nowNanos] 应发出的事件"（运行时通道专用）。
     *
     * [meta] 每事件 4 个 long：`handle / type / timestamp / values 个数`；
     * [values] 每事件 16 个 float（与 `sensors_event_t.data` 同宽）。
     * 值个数由原生层按类型给出（必须与框架 JNI 的 switch 一致）。
     * @return 写入的事件条数
     */
    external fun runtimeFrame(nowNanos: Long, meta: LongArray, values: FloatArray): Int

    /**
     * 用框架自己的传感器表播种 `type → handle`（三元组 `[type, handle, flags, ...]`）。
     * 这类映射不依赖真实事件，所以步数计数器这种"不走路就没有事件"的 on-change
     * 传感器也能拿到 handle；已知映射不会被它覆盖（真实事件携带的更可信）。
     */
    external fun setHandleMap(triples: LongArray)

    /** 状态快照（速度 m/s / 注入方位角度 / 是否移动 / 累计步数 / 该快照的时刻） */
    external fun updateState(
        speed: Double,
        azimuth: Double,
        moving: Boolean,
        steps: Long,
        nowNanos: Long
    )

    /**
     * 客户端视角的**开机总步数**（最近一次发出的 TYPE_STEP_COUNTER 值）。
     *
     * 应用若用"间歇读系统总步数、两次求差"的方式算步频（不少计步类应用的实现方式），
     * 它读到的就是这个数 —— Test 页把它单独打一行，便于和应用的读数直接对。
     */
    external fun stepCounterValue(): Long

    /**
     * 真实（HAL）STEP_COUNTER 的最近值：**模拟接管时的起点**。
     *
     * 真机的计数器是"开机以来累计"，应用按 Δ步数/Δt 算步频时依赖它**连续**。
     * 若模拟从一个凭空的值（例如随机 3000~12000）开始，第一帧就会是一次几千步的跳变，
     * 这类应用会被这一步跳变长期拉高读数。取真实值做起点则天然连续。
     * @return ≥0 = 已知；-1 = 未知（无步数传感器 / 还没收到过真实事件）
     */
    external fun realStepCounter(): Long

    /** 诊断字符串（已挂载/已改写槽位/已发事件数…） */
    external fun status(): String

    /**
     * 「应用期望频率」观测快照：应用注册传感器时请求的采样周期/批量延迟 + 发起方 uid。
     *
     * 取自 `SensorEventConnection::enableDisable` 的 vtable 槽（**只记录、原样放行**）。
     * 注意这是**原始请求值**；框架会用 `capRates()` 与厂商扩展再调整一次，
     * 要"像"应以框架采用值为准（见 SensorRateProbe 里的 dump 解析，那一份带 `selected`）。
     */
    external fun enableRequests(): String

    /**
     * 「按应用期望出数据」：把框架观测到的**采用速率**与**活跃状态**灌进栅格通道。
     *
     * 真机模型：HAL 按所有请求里最快那个（框架 dump 的 `selected`）出力，框架把每条事件
     * 原样广播给所有人。所以这里只需要"一个传感器一个速率"——`periodNs` = 采用值
     * （纳秒；0 = 最快档/未指定 ⇒ 用内置默认栅格），`active` = 有没有人订阅。
     * 没人订阅时该类型**静默**（真机 HAL 没被启用时同样一条都不出）。
     * `batchNs` = 框架 dump 的 `batching_period … selected`（0 = 逐条上报；非 0 时按批量边界成批放出）。
     * 只对栅格通道生效；步数两条流是 on-change，不受影响。
     */
    external fun setChannelHint(type: Int, periodNs: Long, batchNs: Long, active: Boolean)

    /**
     * 固定注入栅格（Hz；0 = 自动跟随框架采用值）。栅格只决定"能表现出来的最快速率"，
     * 调细不改变各传感器自己的采用速率。原生层会把 1e9/Hz 钳进 2.5~50ms。
     */
    external fun setGridHz(hz: Int)

    /** 先把所有栅格通道标成不活跃，随后按 dump 灌活跃者（缺席即静默） */
    external fun clearChannelHints()

    /**
     * Calibration 页：按索引设置某一路注入噪声的**半宽**（均匀分布 [−A, A]，等效 σ = A/√3）。
     *
     * 索引表与 [moe.fuqiuluo.xposed.utils.SensorNoise] / 原生 `VW_NOISE_*` 逐项一致。
     * 只改幅度，不动任何运动学量；原生层会丢弃负值/NaN 并把上限钳到 50。
     */
    external fun setNoise(index: Int, amp: Float)

    /** 噪声档回读（诊断字符串，格式 `o=0.150 m=0.360/0.210/0.560 …`） */
    external fun noiseProfile(): String
}
