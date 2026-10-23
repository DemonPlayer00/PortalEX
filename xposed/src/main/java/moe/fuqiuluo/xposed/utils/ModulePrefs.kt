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
 * ⚠️ **这条通道是"一次性快照"，不是"实时读取"**（2026-09-19 真机实测，见 [enabled] 的说明）。
 * 需要跟随用户改动的开关走 `put_config` 命令，别在这里读。
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
     * 读任意布尔开关（由调用方决定要不要缓存）。
     *
     * ⚠️ **实测（2026-09-19）：这条通道在 system_server 里只能当"一次性快照"用。**
     * `getRemotePreferences` 由框架按 group 在进程内缓存（`computeIfAbsent`），
     * 内容在构造时拉一次，之后靠框架推增量；而本机（LSPosed 2.2.0 / api 102）
     * **增量从未到达** —— App 在功能页改开关（写偏好 + 发命令）也好、
     * 用 root 原地改偏好文件也好，system_server 侧都收不到推送，
     * 于是**后续每次读拿到的都是进程第一次读时的旧值**。
     *
     * 结论（别再把这条通道当"实时开关源"）：需要跟随变化的开关一律走
     * `put_config` 命令（`RemoteCommandHandler`），与噪声档/速度等设置同一条路。
     * 本函数只适合"进程启动早期读一次、之后不再变"的场景。
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
