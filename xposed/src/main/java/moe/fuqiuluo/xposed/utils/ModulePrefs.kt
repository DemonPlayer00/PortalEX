package moe.fuqiuluo.xposed.utils

import java.io.File

/**
 * 跨进程读取本模块自己的 SharedPreferences（实验性开关的传播通道）。
 *
 * 为什么需要它：模块设置存在 PortalEX 应用自己的 `portal` 偏好里，而开关要在
 * **每个被注入的进程**（system_server、各应用进程）里被读到 —— 旧的 binder 通道
 * （`sendExtraCommand("portal", …)`）被刻意限死在 PortalEX 自己的 uid 上
 * （`BinderUtils.isLocationProviderEnabled`），普通应用进程拿不到 key，也就拿不到配置。
 *
 * 通道 = libxposed 的 `XposedInterface.getRemotePreferences(group)`（框架把偏好投递进
 * 被注入进程，只读）。旧代码里那条 `XSharedPreferences` **反射链已删除**：
 * 上游已宣布它废弃，替代品就是现在这条；且旧入口不存在之后，
 * 反射那套 classloader 兜底也失去意义。
 *
 * 读不到时一律返回 null，调用方按"开关未开启"处理，即**回落到既有行为**，
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

    /**
     * 步频侧外周传感器模拟开关（`null` = 读不到；调用方按"未开启"处理，与旧读取器同口径）。
     * 注意**不缓存**：这两个开关会被 App 在两个功能页里随时改，缓存会把页面上的改动吃掉。
     */
    fun cadenceMockEnabled(): Boolean? = readBoolean(PortalProtocol.Pref.CADENCE_MOCK, def = true)

    /** 角度与指南针侧外周传感器模拟开关（不缓存，理由同上） */
    fun orientationMockEnabled(): Boolean? = readBoolean(PortalProtocol.Pref.ORIENTATION_MOCK, def = true)

    /**
     * 读任意布尔开关（**不缓存**，由调用方决定要不要缓存）。
     *
     * 存在的理由：不是每个开关都能靠 `put_config` 送达（那条路要求系统侧已握手），
     * 而 App 侧偏好一落盘就是权威。调用方拿它做"下发值之外的第二判据"，
     * 于是"刚打开开关、下发还没到"这段窗口不会读到旧结论。
     *
     * @return true/false = 读到了；null = 通道不可用（调用方按"未开启"处理）
     */
    fun enabled(key: String, def: Boolean = false): Boolean? = readBoolean(key, def)

    private fun readBoolean(key: String, def: Boolean): Boolean? {
        // libxposed 远程偏好：框架把模块 App 的偏好投递进被注入进程（只读）。
        // 这是**唯一**通道 —— 旧 XSharedPreferences 那条反射链已随旧入口一起删除：
        // 它点名要被废弃，且在旧入口不存在之后根本没有可用的 classloader 去加载它。
        val prefs = ModuleRuntime.remotePreferences(PREFS_NAME) ?: run {
            dbg("no preference channel, treat $key as off")
            return null
        }
        return runCatching {
            prefs.getBoolean(key, def)
        }.onFailure {
            Logger.error("ModulePrefs: 远程偏好读 $key 失败：${it.message}", it)
        }.getOrNull()?.also { dbg("$key=$it (libxposed remote)") }
    }
}
