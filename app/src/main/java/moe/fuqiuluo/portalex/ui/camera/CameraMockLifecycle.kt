package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * 位置模拟关闭时的 cammock 收尾。
 *
 * **关键约束**：`cameraMockEnabled` 只能在 stop.sh 真正成功之后置 false。
 * 旧实现在调用 stop 前就先翻转开关；stop 失败/超时后界面显示“关闭”，
 * provider 却仍在运行并继续输出模拟画面 —— 这就是“假关闭”的来源。
 */
object CameraMockLifecycle {
    private const val TAG = "CameraMockLifecycle"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cammock-lifecycle").apply { isDaemon = true }
    }

    @Volatile private var stopping = false
    @Volatile var lastError: String? = null
        private set

    fun stopForLocationMockOff(context: Context) {
        if (!context.cameraMockEnabled || stopping) return
        stopping = true
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { CammockManager.stop(app) }
            result.onSuccess {
                // 只有实现自己的 stop.sh 成功，才允许界面变成“关闭”
                app.cameraMockEnabled = false
                lastError = null
                Log.i(TAG, "cammock stopped; cameraMockEnabled=false")
            }.onFailure {
                // 保留 true：provider 可能仍在运行，界面必须如实显示
                lastError = it.message
                Log.e(TAG, "cammock stop failed; keep cameraMockEnabled=true", it)
            }
            stopping = false
        }
    }
}
