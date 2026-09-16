package moe.fuqiuluo.xposed.hooks.settings

import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Hooks
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.MethodHook
import moe.fuqiuluo.xposed.utils.MethodHookParam
import moe.fuqiuluo.xposed.utils.ModulePrefs
import moe.fuqiuluo.xposed.utils.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * **隐藏开发者模式**：把 `Settings.Global` 里"开发者选项 / USB 调试是否开启"的读取
 * 拦成"未开启"。
 *
 * ## 为什么挂在 `Settings.Global` 上
 *
 * 这两项在框架里的**权威存储**是 settings provider 的两条 `global` 记录：
 *
 * | 设置项 | 键 | 语义 |
 * | --- | --- | --- |
 * | 开发者选项总开关 | `development_settings_enabled` | 关掉它，设置里那一栏就是"未开启" |
 * | USB 调试 | `adb_enabled` | 开发者选项里的第一条开关 |
 *
 * 而调用方（设置应用、SystemUI、以及任何想看这两项的应用）拿到它们的**入口就是
 * `android.provider.Settings.Global.getInt/getString`** —— 这是 `@hide` 之外的公开 API，
 * 于是"在读取处据实返回未开启"这一条，覆盖了**所有**走公开 API 的调用者，
 * 且不需要去改 provider 的数据库（改库是更重、更容易和系统打架的做法）。
 *
 * ## 语义与边界（说清楚，别让人以为它万能）
 *
 * - **只作用于被注入的进程**。LSPosed 作用域里没有的进程照旧读到真值 —— 本模块在
 *   system_server 里，所以系统框架侧一致；设置应用要一起盖住就得把
 *   `com.android.settings` 加进作用域（`scope.list` 里刻意**不写**：那份清单会被框架
 *   当成"推荐"并自动加入作用域，替用户改作用域不是这个开关该干的事）。
 * - **`proceed()` 照旧会被调用**（先让真值读出来，再把结果换掉）。这不是浪费：
 *   读取路径上可能有 provider 侧的初始化副作用，绕过它反而可能把 provider 弄成未初始化。
 * - **不改写数据库**：关掉开关后一切立刻恢复真实状态，没有需要回滚的残留。
 * - 目标键是**硬编码**的（不来自 App）：这份映射属于协议的一部分，App 只负责开关，
 *   不下发"要伪装哪几个键"—— 否则一个被改过的 App 就能让模块去伪装任意设置。
 */
object DeveloperModeHook {

    /** 目标键 → 未开启时该返回的值。值刻意与真实语义一致：0/"0"/false 都是"未开启"。 */
    private val HIDDEN_VALUES: Map<String, Any> = mapOf(
        // 开发者选项总开关
        "development_settings_enabled" to 0,
        // USB 调试
        "adb_enabled" to 0,
        // 无线调试（Android 11+，与 adb_enabled 是两条独立记录）
        "adb_wifi_enabled" to 0,
        // ADB 授权超时（毫秒；开着的时候是有效值，未开启语义上是 0）
        "adb_authorization_timeout" to 0,
    )

    /** 只安装一次：这些方法在进程里是单例静态方法，重复挂钩会挂两遍 */
    private val installed = AtomicBoolean(false)

    /** 命中次数（诊断用：证明钩子真的在被读，而不是"装上了没人走"） */
    private val hits = AtomicLong(0)

    // ---- 开关读取：内存下发值优先，其次读模块 App 的偏好 ----
    // 为什么要有第二条：put_config 只在"系统侧已握手"时送达，而 App 侧偏好在用户一按开关
    // 就落了盘。于是"开关刚打开、系统侧还没收到下发"这段窗口里，偏好能兜住结论；
    // 而且它对**每个被注入进程**都成立（不只 system_server）。
    // 缓存 1s：命中路径是热路径（每个 Settings 读取都过这里），不能每次都读一遍偏好。
    private const val ENABLED_CACHE_MS = 1_000L
    private val enabledCachedAt = AtomicLong(0)
    @Volatile private var enabledCached = false

    private fun hideEnabled(): Boolean {
        if (FakeLoc.hideDeveloperMode) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - enabledCachedAt.get() < ENABLED_CACHE_MS) return enabledCached
        val fromPrefs = ModulePrefs.enabled(moe.fuqiuluo.xposed.utils.PortalProtocol.Pref.HIDE_DEVELOPER_MODE)
        enabledCached = fromPrefs == true
        enabledCachedAt.set(now)
        return enabledCached
    }


    /**
     * 安装钩子。由安装器在 [moe.fuqiuluo.xposed.FakeLocation.install] 里调用一次。
     *
     * 钩子**常驻**（装上就不摘），命中与否由 [FakeLoc.hideDeveloperMode] 现场决定 ——
     * 这样 App 侧切换开关不需要重新安装钩子，也让"关掉开关"是真的什么都不做。
     *
     * @param classLoaders 目标进程的类加载器，**按顺序尝试**。
     *   为什么给多个：`android.provider.Settings` 属于 framework，
     *   而 system_server 里"看得到 framework 的加载器"与"看得到 `com.android.server.*`
     *   的加载器"并不总是同一个（本仓在别处也踩过这个坑）—— 与其猜哪个对，
     *   不如逐个试到能加载出 `Settings$Global` 为止。
     */
    fun install(vararg classLoaders: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        val global = classLoaders.firstNotNullOfOrNull { loader ->
            runCatching {
                XposedHelpers.findClassIfExists("android.provider.Settings\$Global", loader)
            }.getOrNull()
        }
        if (global == null) {
            Logger.error("DeveloperModeHook: 找不到 Settings\$Global，隐藏开发者模式不生效", null)
            installed.set(false)
            return
        }

        // 四个口子都要堵：调用点可能用 getInt / getString / getLong / getBoolean 任一种形态问
        var count = 0
        count += hookOne(global, "getInt")
        count += hookOne(global, "getString")
        count += hookOne(global, "getLong")
        count += hookOne(global, "getBoolean")
        Logger.info(
            "DeveloperModeHook: 已挂 $count 个 Settings.Global 读取口" +
                    "（隐藏开发者模式" + (if (FakeLoc.hideDeveloperMode) "已开启" else "当前关闭") + "）"
        )

        // 诊断探针：把"谁在 service 侧读这几条键、用哪个方法"记下来。
        // 为什么要它：设置应用在**被注入**的前提下仍未命中上面的钩子，说明它不走
        // `Settings.Global` 静态方法（很可能是 ContentResolver/缓存路径）——
        // 这条探针给出定量答案（caller 包名 + 方法名），而不是猜。
        // 只记前若干条，且只认我们关心的键，避免刷日志。
        classLoaders.forEach { probe(classLoaders, it) }
    }

    /** 已打印的探针条目数（只看前几条就够定性） */
    private val probeLogged = AtomicLong(0)

    private fun probe(classLoaders: Array<out ClassLoader>, loader: ClassLoader) {
        val provider = runCatching {
            XposedHelpers.findClassIfExists("com.android.providers.settings.SettingsProvider", loader)
                ?: XposedHelpers.findClassIfExists("android.provider.SettingsProvider", loader)
        }.getOrNull() ?: return
        Hooks.hookAllMethods(provider, "call", object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args
                // call(String method, String arg, Bundle extras) 与 call(String, String, String, Bundle)
                val method = args.getOrNull(0) as? String ?: return
                val key = args.getOrNull(1) as? String ?: return
                if (key !in HIDDEN_VALUES) return
                if (probeLogged.incrementAndGet() > 12L) return
                Logger.info("DevModeProbe: $key 经 service 读取：method=$method caller=${callerName()}")
            }
        })
    }

    /**
     * 调用方标识：provider 侧的 Binder 调用者 uid，能反查包名就带上。
     *
     * 为什么用 uid 而不是"当前进程"：`SettingsProvider.call` 是在 **system_server** 里执行的，
     * 想知道"是哪个应用来读的"只能看 Binder 调用者 —— 这正是这条探针要回答的问题。
     */
    private fun callerName(): String = runCatching {
        val uid = android.os.Binder.getCallingUid()
        val ctx = currentApplicationContext()
        val packages = ctx?.packageManager?.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) "uid=$uid" else "uid=$uid(${packages.joinToString("/")})"
    }.getOrDefault("uid=?")

    /** 系统进程里的 application context（反查包名用；拿不到就只报 uid） */
    private fun currentApplicationContext(): android.content.Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val app = at.getMethod("currentApplication").invoke(null)
        app as? android.content.Context
    }.getOrNull()


    /** 挂一个同名方法（可能是多条重载，逐个挂） */
    private fun hookOne(global: Class<*>, methodName: String): Int {
        val unhooks = Hooks.hookAllMethods(global, methodName, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hideEnabled()) return
                val args = param.args
                // 两个重载形态的首参都是 ContentResolver，键在第 2 个实参
                if (args.size < 2) return
                val key = args[1] as? String ?: return
                val hidden = HIDDEN_VALUES[key] ?: return
                // 目标方法的返回类型决定要给什么值：getString 要字符串，其余要数字
                param.result = if (isStringReturn(param)) hidden.toString() else hidden
                val n = hits.incrementAndGet()
                // 首次命中打一条 **info**：它证明的是"开关真的生效了"，而不是"钩子装上了没人走"。
                // 只打一次；之后是否继续记由调试开关决定（避免刷日志）。
                if (n == 1L) {
                    Logger.info("DeveloperModeHook: 首次命中 $key ⇒ ${param.result}（隐藏开发者模式生效）")
                }
                if (FakeLoc.enableDebugLog && (n <= 5 || n % 20 == 0L)) {
                    Logger.debug("DeveloperModeHook: $key ⇒ ${param.result}（第 $n 次命中）")
                }
            }
        })
        return unhooks.size
    }

    /** 被挂钩方法的返回类型是不是 String（决定塞 `"0"` 还是 `0`） */
    private fun isStringReturn(param: MethodHookParam): Boolean {
        val m = param.method as? java.lang.reflect.Method ?: return false
        return m.returnType == String::class.java
    }

    /** 诊断：命中次数（Test 页/日志用；0 说明"开关开着但没人来读"） */
    fun hitCount(): Long = hits.get()
}
