package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import moe.fuqiuluo.portalex.vcam.CameraRealFake

/** 摄像头模拟与位置模拟的联动：位置模拟一停，虚拟相机也必须退出。 */
object CameraMockLifecycle {
    @Volatile private var restoring = false

    fun stopForLocationMockOff(context: Context) {
        if (!context.cameraMockEnabled || restoring) return
        restoring = true
        context.cameraMockEnabled = false
        CameraMockVideoSource.sync(context)
        Thread({
            runCatching { CameraRealFake.setFake(context, false) }
            restoring = false
        }, "camera-mock-restore").start()
    }
}
