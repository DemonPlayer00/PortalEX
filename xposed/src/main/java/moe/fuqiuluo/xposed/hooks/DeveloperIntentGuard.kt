package moe.fuqiuluo.xposed.hooks

import android.content.Intent
import android.provider.Settings
import moe.fuqiuluo.xposed.utils.Hooks
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.XposedHelpers
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

    fun update(enabled: Boolean, packages: Set<String>) {
        this.enabled = enabled
        this.packages = packages.toSet()
        Logger.info("DeveloperIntentGuard: enabled=$enabled packages=${this.packages.size}")
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

    fun install(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(
            "com.android.server.wm.ActivityTaskManagerService", classLoader
        ) ?: run {
            Logger.warn("DeveloperIntentGuard: ActivityTaskManagerService 不存在，跳过")
            return
        }
        var hooks = 0
        for (name in listOf("startActivity", "startActivityAsUser")) {
            hooks += Hooks.hookAllMethods(cls, name, beforeHook {
                if (!enabled) return@beforeHook
                val intent = args.filterIsInstance<Intent>().firstOrNull()
                    ?: return@beforeHook
                if (!isDeveloperIntent(intent)) return@beforeHook
                val pkg = callingPackage(args) ?: return@beforeHook
                if (pkg !in packages) return@beforeHook
                Logger.info("DeveloperIntentGuard: blocked $pkg -> ${intent.action ?: intent.component}")
                result = 0
            }).size
        }
        Logger.info("DeveloperIntentGuard: installed hooks=$hooks")
    }
}
