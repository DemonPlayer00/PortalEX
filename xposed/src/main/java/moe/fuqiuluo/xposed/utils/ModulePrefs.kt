package moe.fuqiuluo.xposed.utils

import java.io.File

/**
 * 跨进程读取本模块自己的 SharedPreferences（实验性开关的传播通道）。
 *
 * 为什么需要它：模块设置存在 PortalEX 应用自己的 `portal` 偏好里，而开关要在
 * **每个被注入的进程**（system_server、各应用进程）里被读到——旧的 binder 通道
 * （`sendExtraCommand("portal", …)`）被刻意限死在 PortalEX 自己的 uid 上
 * （`BinderUtils.isLocationProviderEnabled`），普通应用进程拿不到 key，也就拿不到配置。
 *
 * LSPosed 提供了 XSharedPreferences（注入进程可直接读模块应用的 prefs 文件），
 * 这正是模块分发开关的标准通道。这里**全程反射调用**：
 * - 类/构造签名随 LSPosed 版本有过变化（旧版 `(String, String)`，新版接受 `File`）；
 * - 反射失败 / prefs 不可读时一律返回 null，调用方按"开关未开启"处理，
 *   即**回落到既有行为**，绝不因为读不到设置而改变模块功能。
 */
internal object ModulePrefs {

    private const val PREFS_NAME = PortalProtocol.PREFS_NAME

    /** 诊断输出：这条通道每个被注入的应用进程都会走一遍，默认安静，只在调试模式下说话 */
    private inline fun dbg(msg: String) {
        if (FakeLoc.enableDebugLog) Logger.info("ModulePrefs: $msg")
    }
    private const val FALLBACK_PACKAGE = "moe.fuqiuluo.portalex"

    /** 模块自己的包名：从模块 APK 路径反推（`/data/app/~~X/<pkg>-<hash>/base.apk`），
     *  fork 改名后也正确。 */
    internal val modulePackage: String by lazy {
        runCatching {
            val loc = ModulePrefs::class.java.protectionDomain?.codeSource?.location
                ?: return@runCatching null
            val apk = File(loc.toURI())
            val dir = apk.parentFile?.name ?: return@runCatching null
            dir.substringBeforeLast('-').takeIf { it.isNotBlank() }
        }.getOrNull() ?: FALLBACK_PACKAGE
    }

    /**
     * 读取实验性开关 `binderSensorMock`。进程内缓存一次（开关语义是"下一个应用进程
     * 启动周期生效"，与其它需要重启的配置项一致）。
     * @return true/false = 读到了；null = 读不到（调用方必须按"未开启"处理）
     */
    fun binderSensorMockEnabled(): Boolean? = cachedBinderSensorMock

    private val cachedBinderSensorMock: Boolean? by lazy { readBoolean(PortalProtocol.Pref.BINDER_SENSOR_MOCK) }

    private fun readBoolean(key: String): Boolean? {
        val prefs = open() ?: run {
            dbg("XSharedPreferences unavailable, treat $key as off")
            return null
        }
        return runCatching {
            prefs.javaClass.getMethod("reload").invoke(prefs)
            val v = prefs.javaClass
                .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(prefs, key, false) as? Boolean
            dbg("$key=$v (via ${prefs.javaClass.name})")
            v
        }.onFailure {
            Logger.error("ModulePrefs: read $key failed: ${it.message}", it)
        }.getOrNull()
    }

    /** 依次尝试 LSPosed 的两种构造方式，任一成功即返回实例 */
    private fun open(): Any? {
        val cls = loadClass() ?: return null
        // 旧签名：XSharedPreferences(String packageName, String prefFileName)
        runCatching {
            cls.getConstructor(String::class.java, String::class.java)
                .newInstance(modulePackage, PREFS_NAME)
        }.onSuccess {
            dbg("opened via (String,String)")
            return it
        }.onFailure {
            dbg("(String,String) ctor unavailable: ${it.message}")
        }
        // 新签名：XSharedPreferences(File prefFile)
        return runCatching {
            val file = File("/data/user/0/$modulePackage/shared_prefs/$PREFS_NAME.xml")
            dbg("trying File ctor, exists=${file.exists()} canRead=${file.canRead()}")
            cls.getConstructor(File::class.java).newInstance(file)
        }.onFailure {
            Logger.error("ModulePrefs: XSharedPreferences unavailable: ${it.message}", it)
        }.getOrNull()
    }

    /**
     * 找到 XSharedPreferences 类。它在 LSPosed 的 framework dex 里，但**不在模块
     * classloader 的可见白名单里**：直接 `Class.forName` 会 ClassNotFound（实测）。
     * 因此依次尝试：模块 classloader → XposedBridge 的 classloader（framework dex 的
     * 加载者）→ 系统 classloader。找不到就返回 null（调用方按"开关未开启"处理）。
     */
    private fun loadClass(): Class<*>? {
        val name = "de.robv.android.xposed.XSharedPreferences"
        val loaders = listOf(
            "module" to ModulePrefs::class.java.classLoader,
            "XposedBridge" to runCatching { Class.forName("de.robv.android.xposed.XposedBridge") }
                .getOrNull()?.classLoader,
            "system" to ClassLoader.getSystemClassLoader()
        )
        for ((tag, loader) in loaders) {
            val c = runCatching { Class.forName(name, false, loader) }.getOrNull()
            if (c != null) {
                if (tag != "module") dbg("XSharedPreferences via $tag loader")
                return c
            }
        }
        dbg("no XSharedPreferences class, treat binderSensorMock as off")
        return null
    }
}
