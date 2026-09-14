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
 * LSPosed 曾提供 XSharedPreferences（注入进程可直接读模块应用的 prefs 文件），这正是模块
 * 分发开关的标准通道；而**现代框架已宣布它即将废弃**，替代品是 libxposed 的
 * `XposedInterface.getRemotePreferences(group)`（框架把偏好投递进被注入进程，只读）。
 * 于是这里按**新通道优先**的顺序读：
 *
 *  1. [ModuleRuntime.remotePreferences] —— libxposed 远程偏好（入口在 `onModuleLoaded` 里登记）；
 *  2. 旧 XSharedPreferences（反射）—— 过渡期兼容，等旧入口摘掉后再删。
 *
 * 旧通道**全程反射调用**：类/构造签名随 LSPosed 版本变过（旧版 `(String, String)`，
 * 新版接受 `File`），且该类不在模块 classloader 的可见白名单里。
 * 两条通道失败时一律返回 null，调用方按"开关未开启"处理，即**回落到既有行为**，
 * 绝不因为读不到设置而改变模块功能。
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
     * 读取开关 `binderSensorMock`。进程内缓存一次（开关语义是"下一个应用进程
     * 启动周期生效"，与其它需要重启的配置项一致）。
     *
     * 默认值必须与 [moe.fuqiuluo.portalex.ext.binderSensorMock] 一致（同为 true）：
     * 这条通道读的是同一份偏好，缺键时给出的结论不能和 App 侧相反。
     * @return true/false = 读到了；null = 读不到（调用方必须按"未开启"处理）
     */
    fun binderSensorMockEnabled(): Boolean? = cachedBinderSensorMock

    private val cachedBinderSensorMock: Boolean? by lazy {
        readBoolean(PortalProtocol.Pref.BINDER_SENSOR_MOCK, def = true)
    }

    private fun readBoolean(key: String, def: Boolean): Boolean? {
        // ① libxposed 远程偏好（现代通道，框架内置）
        ModuleRuntime.remotePreferences(PREFS_NAME)?.let { prefs ->
            val v = runCatching { prefs.getBoolean(key, def) }.onFailure {
                Logger.error("ModulePrefs: 远程偏好读 $key 失败：${it.message}", it)
            }.getOrNull()
            if (v != null) {
                dbg("$key=$v (libxposed remote)")
                return v
            }
        }

        // ② 旧 XSharedPreferences（过渡期兼容；现代框架已宣布废弃）
        val prefs = open() ?: run {
            dbg("no preference channel, treat $key as off")
            return null
        }
        return runCatching {
            prefs.javaClass.getMethod("reload").invoke(prefs)
            val v = prefs.javaClass
                .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(prefs, key, def) as? Boolean
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
