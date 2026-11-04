package moe.fuqiuluo.portalex.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最顶层 Java 未捕获异常处理。主进程崩溃后由独立的 :crash 进程
 * 显示 CrashActivity，避免主线程已经坏掉时无法弹框。
 */
object CrashHandler {
    const val EXTRA_ERROR = "portalex.crash.error"
    const val TITLE = "PortalEX Oops..."

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val details = buildDetails(app, thread, throwable)
                val intent = Intent(app, CrashActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(EXTRA_ERROR, details)
                }
                app.startActivity(intent)
                // 给 :crash 进程一点启动时间，再把原异常交给系统处理。
                Thread.sleep(350)
            }
            runCatching { previous?.uncaughtException(thread, throwable) }
        }
    }

    private fun buildDetails(context: Context, thread: Thread, throwable: Throwable): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("unknown")
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        return buildString {
            appendLine("时间: $now")
            appendLine("版本: $version")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("进程: ${context.packageName}  pid=${Process.myPid()}")
            appendLine("线程: ${thread.name}  tid=${thread.id}")
            appendLine()
            appendLine("未捕获异常:")
            append(sw.toString())
        }
    }
}
