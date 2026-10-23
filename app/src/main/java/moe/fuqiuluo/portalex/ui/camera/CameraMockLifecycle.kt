package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import java.util.concurrent.Executors

/** 位置模拟关闭时，只通过 cammock 标准 stop 接口停止实现。 */
object CameraMockLifecycle {
    @Volatile private var stopping = false

    fun stopForLocationMockOff(context: Context) {
        if (!context.cameraMockEnabled || stopping) return
        stopping = true
        context.cameraMockEnabled = false
        Executors.newSingleThreadExecutor().execute {
            runCatching { CammockManager.stop(context) }
            stopping = false
        }
    }
}
