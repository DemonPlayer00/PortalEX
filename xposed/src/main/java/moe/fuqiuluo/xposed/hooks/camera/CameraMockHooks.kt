@file:Suppress("PrivateApi")
package moe.fuqiuluo.xposed.hooks.camera

import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.PortalDiag

/**
 * **摄像头模拟的安装入口**（只对用户应用进程调用）。
 *
 * 与传感器模拟的部署方式**刻意不同**：传感器只需系统框架侧动手（对目标应用零 hook），
 * 而摄像头 API 全跑在应用自己的进程里（`CameraManager`/`CameraDeviceImpl` 都是客户端类），
 * 因此这是本模块唯一一个"必须注入到目标应用"的功能族。
 *
 * 两条纪律：
 *  1. **注入范围由用户在 LSPosed 里控制**：本模块不主动扩大作用域。装在这里的钩子
 *     只在应用真的申请摄像头时才做决定，平时零开销；
 *  2. **每个族各自兜底**（见 [step]）：某个 ROM 缺类/改签名只影响那一族，
 *     不会连带把别的钩子拖下水，且失败会记进静默失败账（Test 页可见）。
 */
internal object CameraMockHooks {

    fun install(classLoader: ClassLoader, packageName: String) {
        step("Camera2") { Camera2MockHook.install(classLoader, packageName) }
        // Camera1（android.hardware.Camera）：老应用仍在用，作为下一阶段接入。
        // 这里先只在调试模式下留一句，免得用户以为"装了就该全平台生效"。
        if (FakeLoc.enableDebugLog) {
            Logger.debug("摄像头模拟：Camera1（已废弃 API）路径尚未接入：$packageName")
        }
    }

    /** 单个 hook 族安装：失败只记账 + 一条日志，其余族继续 */
    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            PortalDiag.fail(PortalDiag.Area.HOOK_INSTALL, it)
            Logger.error("摄像头模拟：$name 安装失败（已跳过）：${it.message}", it)
        }
    }
}
