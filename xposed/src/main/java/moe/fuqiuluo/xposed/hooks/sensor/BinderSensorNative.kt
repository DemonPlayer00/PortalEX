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
     * `[relroAddr, relroSize, pollAidl, pollFmqAidl, pollHidl, pollFmqHidl, rtRegister, rtSend, rtUnregister, rtIsActive]`
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

    /** 诊断字符串（已挂载/已改写槽位/已发事件数…） */
    external fun status(): String
}
