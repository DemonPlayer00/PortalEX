@file:Suppress("PrivateApi", "unused")
package moe.fuqiuluo.xposed.hooks.camera

import android.hardware.camera2.CameraCaptureSession
import android.os.Handler
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookMethod
import moe.fuqiuluo.xposed.utils.toClass

/**
 * **Camera2 拦截**（跑在目标应用进程里）。
 *
 * 三个钩子，各管一件事：
 *  1. `CameraDeviceImpl#createCaptureSession(List<Surface>, …)`：把应用给的输出面**换成我们的
 *     排水面**再交给真实相机（真实画面进桶即丢），并在会话配置成功时开始给应用原来的面喂视频；
 *  2. `CaptureRequest$Builder#addTarget(Surface)`：应用随后会用**它自己的面**构造请求，
 *     而会话里没有这些面（会被判为 "Surface is not part of the session"）—— 这里把它
 *     重定向到排水面，应用侧代码一行都不用改；
 *  3. `CameraDeviceImpl#close()` / `CameraCaptureSessionImpl#close()`：停喂、释放解码线程。
 *
 * 为什么不动 `CameraManager.openCamera`：应用拿到的 `CameraDevice`/`CameraCaptureSession`
 * 都是真对象（我们没有伪造任何系统类），只是"输出去了哪"被改了 —— 伪造对象在
 * 各 ROM 上的字段/方法差异是无穷无尽的坑，而换面只需要两个稳定的方法名。
 *
 * **未实现**：API 28+ 的 `createCaptureSession(SessionConfiguration, …)`（CameraX 走这条）。
 * 这条路径上的应用目前**拿不到模拟画面，但也不会被弄坏**（我们不动它，应用看真实摄像头）。
 * 见 [noteUnsupportedSessionConfig]。
 */
internal object Camera2MockHook {

    private const val DEVICE_IMPL = "android.hardware.camera2.impl.CameraDeviceImpl"
    private const val SESSION_IMPL = "android.hardware.camera2.impl.CameraCaptureSessionImpl"
    private const val SESSION_CONFIG = "android.hardware.camera2.params.SessionConfiguration"
    private const val REQUEST_BUILDER = "android.hardware.camera2.CaptureRequest\$Builder"
    private const val CAMERA_MANAGER = "android.hardware.camera2.CameraManager"

    @Volatile
    private var unsupportedLogged = false

    fun install(classLoader: ClassLoader, packageName: String) {
        val deviceImpl = DEVICE_IMPL.toClass(classLoader)
        if (deviceImpl == null) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("摄像头模拟：本机没有 $DEVICE_IMPL（Camera2 路径跳过）")
            }
            return
        }

        val stateCallbackClass = CameraCaptureSession.StateCallback::class.java
        val handlerClass = Handler::class.java

        // 0) 预热：openCamera 一发生就在**后台线程**把清单/位置取回来放进缓存。
        //    这一步决定了"第一次开摄像头能不能立刻生效"——见 CameraMockClient 纪律 2。
        CAMERA_MANAGER.toClass(classLoader)?.let { manager ->
            manager.hookMethod(
                "openCamera",
                String::class.java,
                stateCallbackClass,
                handlerClass,
                callback = beforeHook { CameraMockClient.warmUpAsync() }
            )
            manager.hookMethod(
                "openCamera",
                String::class.java,
                java.util.concurrent.Executor::class.java,
                stateCallbackClass,
                callback = beforeHook { CameraMockClient.warmUpAsync() }
            )
        }

        // 1) 会话创建：换面 + 包一层回调（配置成功才开始喂帧）
        deviceImpl.hookMethod(
            "createCaptureSession",
            List::class.java,
            stateCallbackClass,
            handlerClass,
            callback = beforeHook { interceptSession(this, packageName) }
        )

        // 1b) API 28+ 的另一条重载：只记账不拦截（见类注释的"未实现"）
        val sessionConfigClass = SESSION_CONFIG.toClass(classLoader)
        if (sessionConfigClass != null) {
            deviceImpl.hookMethod(
                "createCaptureSession",
                sessionConfigClass,
                stateCallbackClass,
                handlerClass,
                callback = beforeHook { noteUnsupportedSessionConfig(packageName) }
            )
        }

        // 2) 请求目标重定向：应用的面 → 排水面
        REQUEST_BUILDER.toClass(classLoader)?.hookMethod(
            "addTarget",
            Surface::class.java,
            callback = beforeHook {
                val surface = args.getOrNull(0) as? Surface
                val dummy = CameraMockController.currentDummy()
                if (surface != null && dummy != null && CameraMockController.isAppSurface(surface)) {
                    args[0] = dummy
                }
            }
        )

        // 3) 关闭：停喂（设备关闭连登记一起清）
        deviceImpl.hookMethod("close", callback = afterHook { CameraMockController.release() })
        SESSION_IMPL.toClass(classLoader)?.hookMethod(
            "close",
            callback = afterHook { CameraMockController.stopFeeding() }
        )

        if (FakeLoc.enableDebugLog) {
            Logger.info("摄像头模拟：Camera2 hook 已安装（$packageName）")
        }
    }

    /**
     * 换面 + 包回调。
     *
     * 失败语义：只要 [CameraMockController.prepare] 说"不拦"，这里**什么都不做** ——
     * 参数原样交给真实方法，应用拿到的是真实摄像头。
     */
    private fun interceptSession(param: XC_MethodHook.MethodHookParam, packageName: String?) {
        val outputs = (param.args.getOrNull(0) as? List<*>)
            ?.filterIsInstance<Surface>()
            ?: return
        if (!CameraMockController.prepare(outputs, packageName)) return
        val dummy = CameraMockController.currentDummy() ?: return
        val original = param.args.getOrNull(1) as? CameraCaptureSession.StateCallback ?: return

        param.args[0] = listOf(dummy)
        param.args[1] = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                // 先喂帧再回调：应用一旦在回调里立刻抓预览/开录，画面已经在流动了
                CameraMockController.startFeeding(packageName)
                original.onConfigured(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                CameraMockController.stopFeeding()
                original.onConfigureFailed(session)
            }

            override fun onReady(session: CameraCaptureSession) = original.onReady(session)

            override fun onActive(session: CameraCaptureSession) = original.onActive(session)

            override fun onClosed(session: CameraCaptureSession) {
                CameraMockController.stopFeeding()
                original.onClosed(session)
            }

            override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) =
                original.onSurfacePrepared(session, surface)
        }
    }

    /**
     * CameraX 等走 `SessionConfiguration` 的应用：我们**不拦截**（框架第一阶段没实现），
     * 但要让用户看得见"为什么没生效" —— 静默不生效是本仓最忌讳的失败形态。
     */
    private fun noteUnsupportedSessionConfig(packageName: String?) {
        if (!CameraMockClient.enabledFor(packageName)) return
        if (unsupportedLogged) return
        unsupportedLogged = true
        Logger.warn("摄像头模拟：应用走的是 SessionConfiguration 路径（暂不支持），本次不拦截：$packageName")
        CameraMockClient.report(packageName, null, "unsupported-session-config")
    }
}
