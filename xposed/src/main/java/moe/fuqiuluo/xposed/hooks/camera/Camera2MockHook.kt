@file:Suppress("PrivateApi", "unused")
package moe.fuqiuluo.xposed.hooks.camera

import android.hardware.camera2.CameraCaptureSession
import android.os.Handler
import android.view.Surface
import moe.fuqiuluo.xposed.utils.MethodHookParam
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.PortalDiag
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookMethod
import moe.fuqiuluo.xposed.utils.toClass

/**
 * **Camera2 拦截**（跑在目标应用进程里）。
 *
 * 四个钩子，各管一件事：
 *  1. `CameraDeviceImpl#createCaptureSession(List<Surface>, …)`：把应用给的输出面**换成我们的
 *     排水面**再交给真实相机（真实画面进桶即丢），并在会话配置成功时开始给应用原来的面喂视频；
 *  1b. `CameraDeviceImpl#createCaptureSession(SessionConfiguration, Handler)`（API 28+）：同构的第二条路，
 *     **CameraX 走的就是这条**。应用的面在 `OutputConfiguration` 里，掏出来后重建一个
 *     `SessionConfiguration`（公开构造器）再交下去；
 *  2. `CaptureRequest$Builder#addTarget(Surface)`：应用随后会用**它自己的面**构造请求，
 *     而会话里没有这些面（会被判为 "Surface is not part of the session"）—— 这里把它
 *     重定向到排水面，应用侧代码一行都不用改；
 *  3. `CameraDeviceImpl#close()` / `CameraCaptureSessionImpl#close()`：停喂、释放解码线程。
 *
 * 为什么不动 `CameraManager.openCamera`：应用拿到的 `CameraDevice`/`CameraCaptureSession`
 * 都是真对象（我们没有伪造任何系统类），只是"输出去了哪"被改了 —— 伪造对象在
 * 各 ROM 上的字段/方法差异是无穷无尽的坑，而换面只需要几个稳定的方法名。
 *
 * **API 28 的类一律只用反射**（`minSdk = 24`）：`SessionConfiguration`/`OutputConfiguration`
 * 在老设备上不存在，静态引用会让整个类解析失败，进而把别的钩子一起拖下水。
 */
internal object Camera2MockHook {

    private const val DEVICE_IMPL = "android.hardware.camera2.impl.CameraDeviceImpl"
    private const val SESSION_IMPL = "android.hardware.camera2.impl.CameraCaptureSessionImpl"
    private const val SESSION_CONFIG = "android.hardware.camera2.params.SessionConfiguration"
    private const val REQUEST_BUILDER = "android.hardware.camera2.CaptureRequest\$Builder"
    private const val CAMERA_MANAGER = "android.hardware.camera2.CameraManager"

    /** `SessionConfiguration.SESSION_TYPE_REGULAR`（反射拿不到时的兜底值） */
    private const val SESSION_TYPE_REGULAR = 0

    /** 只打印一次的重载清单（换 ROM 时它是第一手资料） */
    @Volatile
    private var dumpedOverloads = false

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

        // 1b) API 28+ 的 SessionConfiguration 重载（CameraX 走这条）
        val sessionConfigClass = SESSION_CONFIG.toClass(classLoader)
        if (sessionConfigClass != null) {
            deviceImpl.hookMethod(
                "createCaptureSession",
                sessionConfigClass,
                handlerClass,
                callback = beforeHook { interceptSessionConfig(this, packageName, sessionConfigClass) }
            )
        } else if (FakeLoc.enableDebugLog) {
            Logger.debug("摄像头模拟：本机没有 $SESSION_CONFIG（只覆盖 List 路径）")
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
            dumpOverloads(deviceImpl)
            Logger.info("摄像头模拟：Camera2 hook 已安装（$packageName）")
        }
    }

    /**
     * 换面 + 包回调。
     *
     * 失败语义：只要 [CameraMockController.prepare] 说"不拦"，这里**什么都不做** ——
     * 参数原样交给真实方法，应用拿到的是真实摄像头。
     */
    private fun interceptSession(param: MethodHookParam, packageName: String?) {
        val outputs = (param.args.getOrNull(0) as? List<*>)
            ?.filterIsInstance<Surface>()
            ?: return
        if (!CameraMockController.prepare(outputs, packageName)) return
        val dummy = CameraMockController.currentDummy() ?: return
        val original = param.args.getOrNull(1) as? CameraCaptureSession.StateCallback ?: return

        param.args[0] = listOf(dummy)
        param.args[1] = wrapCallback(original, packageName)
    }

    /**
     * `SessionConfiguration` 路径：把应用请求的输出面全部换成排水面后**重建**一份配置。
     *
     * 三个 API 都是公开构造器，所以不需要伪造系统对象：
     *  · `OutputConfiguration(Surface)`（API 24）
     *  · `SessionConfiguration(int, List<OutputConfiguration>, Executor, StateCallback)`（API 28）
     *
     * **不保留会话参数**（`setSessionParameters`）：真实相机的画面我们整个丢弃，
     * 而 List 路径本来就没有会话参数 —— MI6 上实测不带参数真实相机照样跑。
     */
    private fun interceptSessionConfig(
        param: MethodHookParam,
        packageName: String?,
        configClass: Class<*>
    ) {
        val config = param.args.getOrNull(0) ?: return
        val configs = callGetter(config, "getOutputConfigurations") as? List<*> ?: return
        val outputs = surfacesOfConfigs(configs) ?: return
        if (!CameraMockController.prepare(outputs, packageName)) return
        val dummy = CameraMockController.currentDummy() ?: return

        val original = callGetter(config, "getStateCallback") as? CameraCaptureSession.StateCallback
            ?: return
        val executor = callGetter(config, "getExecutor") as? java.util.concurrent.Executor ?: return
        val sessionType = (callGetter(config, "getSessionType") as? Int) ?: SESSION_TYPE_REGULAR
        // 用实例的真实类去找构造器（某些 ROM 上 OutputConfiguration 会是子类）
        val outputConfigClass = configs.firstOrNull()?.javaClass ?: return

        val rebuilt = runCatching {
            val oc = outputConfigClass.getConstructor(Surface::class.java).newInstance(dummy)
            configClass.getConstructor(
                Int::class.javaPrimitiveType,
                List::class.java,
                java.util.concurrent.Executor::class.java,
                CameraCaptureSession.StateCallback::class.java
            ).newInstance(sessionType, listOf(oc), executor, wrapCallback(original, packageName))
        }.onFailure {
            PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK, it)
            Logger.warn("摄像头模拟：重建 SessionConfiguration 失败，本次不拦截（${it.message}）")
            CameraMockClient.report(packageName, null, "session-config-rebuild-failed")
        }.getOrNull() ?: return

        param.args[0] = rebuilt
        if (FakeLoc.enableDebugLog) {
            Logger.info("摄像头模拟：SessionConfiguration 路径已换面（type=$sessionType，面 ${outputs.size} 个）")
        }
    }

    /** 把应用给的回调包一层：配置成功先开始喂帧，再回调应用（应用一进回调就抓预览也不会看到空白） */
    private fun wrapCallback(
        original: CameraCaptureSession.StateCallback,
        packageName: String?
    ): CameraCaptureSession.StateCallback = object : CameraCaptureSession.StateCallback() {
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

    /**
     * 从 `SessionConfiguration` 的输出配置里掏出应用请求的全部输出面。
     *
     * `OutputConfiguration#getSurfaces()`（多面）是 @hide，`getSurface()`（单面，已废弃）是公开的 ——
     * 两个都试，取到哪个算哪个；一个都没有就返回 null（= 不拦截）。
     */
    private fun surfacesOfConfigs(configs: List<*>): List<Surface>? {
        val out = ArrayList<Surface>(configs.size)
        configs.forEach { oc ->
            if (oc == null) return@forEach
            val many = (callGetter(oc, "getSurfaces") as? List<*>)?.filterIsInstance<Surface>()
            if (!many.isNullOrEmpty()) {
                out.addAll(many)
            } else {
                (callGetter(oc, "getSurface") as? Surface)?.let { out.add(it) }
            }
        }
        return out.ifEmpty { null }
    }

    private fun callGetter(target: Any, name: String): Any? =
        runCatching { target.javaClass.getMethod(name).invoke(target) }
            .onFailure {
                if (FakeLoc.enableDebugLog) Logger.debug("摄像头模拟：$name 不可用（${it.javaClass.simpleName}）")
            }
            .getOrNull()

    /** 换 ROM/换 Android 版本时，先把 `createCaptureSession` 的重载清单打出来 —— 这是最省事的第一手资料 */
    private fun dumpOverloads(deviceImpl: Class<*>) {
        if (dumpedOverloads) return
        dumpedOverloads = true
        runCatching {
            val lines = deviceImpl.declaredMethods
                .filter { it.name == "createCaptureSession" }
                .joinToString(" | ") { m -> m.parameterTypes.joinToString(",") { it.simpleName } }
            Logger.info("摄像头模拟：createCaptureSession 重载 = $lines")
        }
    }
}
