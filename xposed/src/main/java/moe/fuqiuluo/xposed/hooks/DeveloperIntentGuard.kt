package moe.fuqiuluo.xposed.hooks

import android.content.Intent
import android.provider.Settings
import android.database.MatrixCursor
import android.net.Uri
import dalvik.system.PathClassLoader
import android.os.Binder
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.Hooks
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.ModuleRuntime
import moe.fuqiuluo.xposed.utils.PortalProtocol
import moe.fuqiuluo.xposed.utils.XposedHelpers
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook

/**
 * 按应用拦截“开发者选项”Intent。
 *
 * 拦截点在 system_server 的 ActivityTaskManagerService，而不是目标应用的
 * Context.startActivity：显式组件、startActivityForResult 和 PendingIntent
 * 最终都会经过这里。命中后直接返回 0（不启动 Activity），应用看到的是
 * “启动了但没有界面”的结果，不抛异常。
 */
object DeveloperIntentGuard {
    @Volatile private var enabled = false
    @Volatile private var packages: Set<String> = emptySet()
    @Volatile private var installed = false

    fun update(enabled: Boolean, packages: Set<String>) {
        this.enabled = enabled
        this.packages = packages.toSet()
        Logger.info("DeveloperIntentGuard: enabled=$enabled packages=${this.packages.size}")
        // 配置到达时兜底安装：系统启动阶段若类加载顺序不满足，仍能在真正使用时装上。
        if (!installed) {
            val cl = runCatching {
                Class.forName("com.android.server.am.ActivityManagerService").classLoader
            }.getOrNull()
                ?: Thread.currentThread().contextClassLoader
                ?: DeveloperIntentGuard::class.java.classLoader
            Logger.info("DeveloperIntentGuard: retry install cl=${cl?.javaClass?.name}")
            install(cl)
        }
    }

    private fun isDeveloperIntent(intent: Intent): Boolean {
        if (intent.action == Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) return true
        val c = intent.component ?: return false
        return c.packageName == "com.android.settings" &&
            c.className.contains("DevelopmentSettings", ignoreCase = true)
    }

    private fun callingPackage(args: Array<Any?>): String? =
        args.filterIsInstance<String>()
            .firstOrNull { it.contains('.') && !it.startsWith("android.") }

    private fun field(obj: Any?, name: String): Any? = runCatching {
        val f = obj!!.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.get(obj)
    }.getOrNull()

    private fun callerPackageFrom(obj: Any?, startIntent: Intent): String? {
        // OnePlus/ColorOS 的 PendingIntent 路径：ActivityTaskManager 的 callingUid 是 1000，
        // 但 Intent 内保存了原始创建者 uid（日志里的 mCallingUid=10468）。优先用它。
        val intentUid = field(startIntent, "mCallingUid") as? Int
        if (intentUid != null && intentUid >= 0) {
            BinderUtils.getUidPackageNames(uid = intentUid)?.firstOrNull()?.let { return it }
        }
        val req = field(obj, "mRequest") ?: obj
        (field(req, "callingUid") as? Int)?.let { uid ->
            BinderUtils.getUidPackageNames(uid = uid)?.firstOrNull()?.let { return it }
        }
        var pkg = field(req, "callingPackage") as? String
        if (pkg.isNullOrBlank()) pkg = field(req, "mCallingPackage") as? String
        if (!pkg.isNullOrBlank()) return pkg
        return BinderUtils.getUidPackageNames(uid = Binder.getCallingUid())?.firstOrNull()
    }

    private fun checkAndBlock(obj: Any?, intent: Intent): Boolean {
        if (!enabled || !isDeveloperIntent(intent)) return false
        val pkg = callerPackageFrom(obj, intent) ?: return false
        Logger.info("DeveloperIntentGuard: candidate pkg=$pkg selected=${pkg in packages}")
        if (pkg !in packages) return false
        Logger.info("DeveloperIntentGuard: blocked $pkg -> ${intent.action ?: intent.component}")
        return true
    }

    fun install(classLoader: ClassLoader) {
        Logger.info("DeveloperIntentGuard: install called installed=$installed cl=${classLoader.javaClass.name}")
        if (installed) {
            Logger.info("DeveloperIntentGuard: already installed, skip")
            return
        }
        // system_server 重启后内存状态会丢：从 App 写入的同一份偏好恢复。
        runCatching {
            val prefs = ModuleRuntime.remotePreferences(PortalProtocol.PREFS_NAME)
            if (prefs != null) {
                enabled = prefs.getBoolean(
                    PortalProtocol.Key.DEV_INTENT_GUARD_ENABLED, enabled
                )
                packages = prefs.getStringSet(
                    PortalProtocol.Key.DEV_INTENT_GUARD_PACKAGES, packages
                ) ?: packages
                Logger.info("DeveloperIntentGuard: restored enabled=$enabled packages=${packages.size}")
            }
        }
        val cls = XposedHelpers.findClassIfExists(
            "com.android.server.wm.ActivityTaskManagerService", classLoader
        ) ?: run {
            Logger.warn("DeveloperIntentGuard: ActivityTaskManagerService 不存在 cl=${classLoader.javaClass.name}，跳过")
            return
        }
        Logger.info("DeveloperIntentGuard: ActivityTaskManagerService found")
        var hooks = 0
        for (name in listOf(
            "startActivity", "startActivityAsUser", "startActivityAndWait", "startActivityWithConfig"
        )) {
            hooks += Hooks.hookAllMethods(cls, name, beforeHook {
                if (!enabled) return@beforeHook
                val intent = args.filterIsInstance<Intent>().firstOrNull()
                    ?: return@beforeHook
                if (!isDeveloperIntent(intent)) return@beforeHook
                val pkg = callingPackage(args) ?: return@beforeHook
                Logger.info("DeveloperIntentGuard: candidate pkg=$pkg selected=${pkg in packages}")
                if (pkg !in packages) return@beforeHook
                Logger.info("DeveloperIntentGuard: blocked $pkg -> ${intent.action ?: intent.component}")
                result = if (method.name.contains("AndWait")) {
                    runCatching {
                        Class.forName("android.app.WaitResult").getDeclaredConstructor().newInstance()
                    }.getOrNull()
                } else 0
            }).size
            Logger.info("DeveloperIntentGuard: ATM hooks for $name total=$hooks")
        }
        Logger.info("DeveloperIntentGuard: ATM stage hooks=$hooks")
        val ams = XposedHelpers.findClassIfExists(
            "com.android.server.am.ActivityManagerService", classLoader
        )
        if (ams != null) {
            Logger.info("DeveloperIntentGuard: ActivityManagerService found")
            for (name in listOf(
                "startActivity", "startActivityAndWait",
                "startActivityAsUser", "startActivityWithFeature"
            )) {
                hooks += Hooks.hookAllMethods(ams, name, beforeHook {
                    if (!enabled) return@beforeHook
                    val intent = args.filterIsInstance<Intent>().firstOrNull()
                        ?: return@beforeHook
                    if (!isDeveloperIntent(intent)) return@beforeHook
                    val pkg = callingPackage(args) ?: return@beforeHook
                    Logger.info("DeveloperIntentGuard: candidate pkg=$pkg selected=${pkg in packages}")
                    if (pkg !in packages) return@beforeHook
                    Logger.info("DeveloperIntentGuard: blocked $pkg -> ${intent.action ?: intent.component}")
                    result = if (method.name.contains("AndWait")) {
                        runCatching {
                            Class.forName("android.app.WaitResult").getDeclaredConstructor().newInstance()
                        }.getOrNull()
                    } else 0
                }).size
                Logger.info("DeveloperIntentGuard: AMS hooks for $name total=$hooks")
            }
        } else {
            Logger.warn("DeveloperIntentGuard: ActivityManagerService 不存在")
        }
        Logger.info("DeveloperIntentGuard: AMS stage hooks=$hooks")
        val starter = XposedHelpers.findClassIfExists(
            "com.android.server.wm.ActivityStarter", classLoader
        )
        Logger.info("DeveloperIntentGuard: ActivityStarter found=${starter != null}")
        if (starter != null) {
            for (m in listOf("executeRequest", "execute")) {
                hooks += Hooks.hookAllMethods(starter, m, beforeHook {
                    // executeRequest(Request) 的 Request 在 args[0]；execute() 才是 thisObject.mRequest。
                    val req = args.firstOrNull {
                        it != null && it.javaClass.name.contains("ActivityStarter\$Request")
                    } ?: field(thisObject, "mRequest") ?: thisObject
                    val intent = field(req, "intent") as? Intent
                        ?: field(req, "mIntent") as? Intent
                    if (intent != null && checkAndBlock(req, intent)) result = 0
                }).size
                Logger.info("DeveloperIntentGuard: Starter $m total=$hooks")
            }
        }
        Logger.info("DeveloperIntentGuard: Starter stage hooks=$hooks")
        val settingsProvider = XposedHelpers.findClassIfExists(
            "com.android.providers.settings.SettingsProvider", classLoader
        ) ?: runCatching {
            // SettingsProvider 在自己的 APK 里，system_server 默认 classloader 看不到。
            val apkLoader = PathClassLoader(
                "/system/priv-app/SettingsProvider/SettingsProvider.apk",
                classLoader
            )
            XposedHelpers.findClassIfExists(
                "com.android.providers.settings.SettingsProvider", apkLoader
            )
        }.getOrNull()
        Logger.info("DeveloperIntentGuard: SettingsProvider found=${settingsProvider != null}")
        if (settingsProvider != null) {
            Hooks.hookAllMethods(settingsProvider, "query", afterHook {
                if (!enabled) return@afterHook
                val uri = args.getOrNull(0) as? Uri ?: return@afterHook
                if (uri.pathSegments.size < 2 ||
                    uri.pathSegments[0] != "global" ||
                    uri.pathSegments[1] != "development_settings_enabled"
                ) return@afterHook
                val pkg = BinderUtils.getUidPackageNames(uid = Binder.getCallingUid())
                    ?.firstOrNull() ?: return@afterHook
                if (pkg !in packages) return@afterHook
                val projection = (args.getOrNull(1) as? Array<*>)?.map { it?.toString() ?: "" }
                    ?: listOf("value")
                val cursor = MatrixCursor(projection.toTypedArray(), 1)
                cursor.addRow(projection.map { if (it == "value") 0 else null }.toTypedArray())
                result = cursor
                Logger.info("DeveloperIntentGuard: spoof development_settings_enabled=0 for $pkg")
            })
            hooks++
        }
        if (hooks > 0) installed = true
        Logger.info("DeveloperIntentGuard: installed hooks=$hooks installed=$installed")
    }
}
