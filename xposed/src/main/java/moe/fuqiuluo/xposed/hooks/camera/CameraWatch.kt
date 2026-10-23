@file:Suppress("PrivateApi")
package moe.fuqiuluo.xposed.hooks.camera

import android.app.AppOpsManager
import android.os.Bundle
import moe.fuqiuluo.xposed.utils.MethodHookParam
import moe.fuqiuluo.xposed.utils.XposedHelpers
import moe.fuqiuluo.xposed.camera.CameraWatchState
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.PortalDiag
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookAllMethodsAfter
import moe.fuqiuluo.xposed.utils.hookAllMethods
import moe.fuqiuluo.xposed.utils.toClass
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * **摄像头控制面**（system_server，Java，**对目标应用零注入**）。
 *
 * 为什么是这两个钩子点 —— 先把实测事实摆清楚（MI6/LineageOS 15 实测）：
 *  · 视频流根本**不经过 system_server**：app 直接 `ServiceManager.getService("media.camera")`
 *    找 cameraserver（`1373 1 cameraserver`，父进程是 init），再经 camera provider HAL
 *    （`1038 1 android.hardware.camera.provider@2.4-service`，同样 init 起）到底层；
 *  · system_server 里**只有控制面**：`media.camera.proxy`（`CameraServiceProxy`）与 `appops`。
 *  所以这一层能给出的、也是唯一能给出的东西是：**谁在什么时候用了哪个摄像头**，
 *  以及"放行 / 拒绝"的能力 —— 而不是画面。画面必须由 cameraserver/HAL 侧的原生件提供
 *  （另行推进），本文件是那条路的前置：它决定"什么时候该喂、喂给谁"。
 *
 * 两个数据源互补：
 *  1. `AppOpsService`（`startOperation`/`finishOperation`/`noteOperation`）—— **带 uid 与包名**。
 *     CameraService 在客户端初始化与每次捕获请求时都会来记账，因此 `noteOperation` 是**高频**
 *     回调（可能逐帧）：这里只在 `code == OP_CAMERA` 时进入慢路径，且交给
 *     [CameraWatchState] 做窗口去抖，活跃期内的重复一律丢弃。
 *  2. `CameraServiceProxy`（cameraserver 回调进来的 `notifyCameraState`）—— **带相机 id 与朝向**，
 *     补上 AppOps 拿不到的"前置还是后置"。Android 9 起才有这个方法，8.1 上只有
 *     `pingForUserUpdate`，所以两个源都挂、缺哪个都不影响另一个。
 *
 * 三条纪律：
 *  · 只**观察**，绝不改返回值 —— AppOps 是权限门，动它等于替系统做决定；
 *  · 任何一个族装不上都不影响其它族（[step]），失败进静默失败账；
 *  · 热路径上不做字符串拼接/日志：只在真的要记一条时才做（见 [CameraWatchState] 的返回值）。
 */
internal object CameraWatch {

    /**
     * `AppOpsManager.OP_CAMERA`。它带 `@hide`（不在公开 SDK 里），所以先反射取、
     * 取不到才退回字面量 26（该值自 API 21 起未变过）。
     */
    private val OP_CAMERA: Int = runCatching {
        XposedHelpers.getStaticIntField(AppOpsManager::class.java, "OP_CAMERA")
    }.getOrDefault(26)

    private val state = CameraWatchState()

    /** 每个被 hook 的方法 → [codeIndex, uidIndex, packageIndex]（见 [indexesOf]） */
    private val indexCache = ConcurrentHashMap<Method, IntArray>()

    /** 已经挂过的 AppOps 实现类（直接命中与延迟捕获可能都来一次，按类名去重） */
    private val hookedAppOps = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var installed = false

    /**
     * 安装。[loaders] 可以给多个候选类加载器 —— 实测踩过：system_server 里
     * ActivityThread 的类加载器与本进程的 `lpparam.classLoader` **能看到的
     * `com.android.server.*` 并不总是同一批**（本机 MI6/8.1 上两者都试才稳定），
     * 因此这里逐个尝试而不是赌一个。
     */
    fun install(vararg loaders: ClassLoader?) {
        if (installed) return
        installed = true
        val candidates = (loaders.filterNotNull() + ClassLoader.getSystemClassLoader())
            .distinct()
            .filter { it != null }
        step("AppOpsService") { hookAppOps(candidates) }
        step("CameraServiceProxy") { hookCameraServiceProxy(candidates) }
    }

    /** 按候选名字 × 候选类加载器查找；找不到返回 null（调用方走延迟捕获兜底） */
    private fun findClass(loaders: List<ClassLoader>, names: List<String>): Class<*>? {
        for (name in names) {
            for (loader in loaders) {
                val c = name.toClass(loader)
                if (c != null) {
                    if (FakeLoc.enableDebugLog) Logger.debug("摄像头控制面：$name 命中（loader=${loader.javaClass.simpleName}）")
                    return c
                }
            }
        }
        return null
    }

    /** 命令通道回读：把观测到的活动塞进回包 */
    fun fill(rely: Bundle) {
        rely.putString(moe.fuqiuluo.xposed.utils.PortalProtocol.Key.CAMERA_EVENTS, state.dump())
    }

    fun dumpText(): String = state.dump()

    fun clear() = state.clear()

    // ---- AppOps ----

    private fun hookAppOps(loaders: List<ClassLoader>) {
        // 类名随版本变过：Android 10 起搬到 appop 子包，9 及以前在 com.android.server
        val clazz = findClass(
            loaders,
            listOf("com.android.server.appop.AppOpsService", "com.android.server.AppOpsService")
        )
        if (clazz != null) {
            hookAppOpsClass(clazz)
            return
        }
        // 兜底：不猜名字也不猜类加载器 —— 等 SystemServiceManager 把 AppOpsService 造出来，
        // 从**实例的类**直接挂。ROM 改包名/换类加载器都拦不住这一手。
        if (FakeLoc.enableDebugLog) {
            Logger.info("摄像头控制面：直接查找 AppOpsService 未命中，改为延迟捕获（SystemServiceManager）")
        }
        hookSystemServiceManager(loaders)
    }

    /** 真正的挂载：只看方法名，不依赖类名（延迟捕获路径拿到的就是实例的类） */
    private fun hookAppOpsClass(clazz: Class<*>) {
        if (!hookedAppOps.add(clazz.name)) return
        // 只挂与"会话开始/结束/记账"有关的方法；checkOperation 是纯查询（权限门），
        // 它被调用的频率更高且不代表真的在用相机，因此刻意不挂。
        val names = listOf("startOperation", "finishOperation", "noteOperation", "noteOperationUnchecked")
        var count = 0
        names.forEach { name ->
            count += clazz.hookAllMethods(name, beforeHook { onAppOpsCall(this, name) }).size
        }
        if (count == 0) {
            PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK)
            Logger.warn("摄像头控制面：${clazz.name} 上一个方法都没挂上（签名与预期不符）")
            return
        }
        Logger.info("摄像头控制面：AppOps hook 已安装（${clazz.name}，$count 个方法）")
    }

    /** 延迟捕获：SystemServiceManager 造出 AppOpsService 实例时顺手把它的类挂上 */
    private fun hookSystemServiceManager(loaders: List<ClassLoader>) {
        val ssm = findClass(loaders, listOf("com.android.server.SystemServiceManager")) ?: run {
            PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK)
            Logger.warn("摄像头控制面：找不到 SystemServiceManager，延迟捕获也不可用")
            return
        }
        ssm.hookAllMethodsAfter("startService") {
            val service = result ?: return@hookAllMethodsAfter
            if (service.javaClass.simpleName == "AppOpsService") hookAppOpsClass(service.javaClass)
        }
    }

    /**
     * AppOps 热路径。
     *
     * 顺序刻意如此：**先按参数下标取 op code 并早退**（绝大多数调用不是相机），
     * 只有确认是相机才去碰 uid/包名与状态机。
     */
    private fun onAppOpsCall(param: MethodHookParam, methodName: String) {
        // Xposed 的 MethodHookParam.method 声明为 Member，这里就是 Method（hookAllMethods 只挂方法）
        val method = param.method as? Method ?: return
        val idx = indexCache[method] ?: indexesOf(method).also { indexCache[method] = it }
        val codeIndex = idx[0]
        if (codeIndex < 0) return
        val code = param.args.getOrNull(codeIndex) as? Int ?: return
        if (code != OP_CAMERA) return

        val uidIndex = idx[1]
        val pkgIndex = idx[2]
        val uid = if (uidIndex >= 0) param.args.getOrNull(uidIndex) as? Int ?: -1 else -1
        if (uid < 0) return
        val pkg = if (pkgIndex >= 0) param.args.getOrNull(pkgIndex) as? String else null

        val recorded = when (methodName) {
            "startOperation" -> state.onAppOp(uid, pkg, starting = true)
            "finishOperation" -> state.onAppOp(uid, pkg, starting = false)
            else -> state.onAppOp(uid, pkg, starting = false, noteOnly = true)
        }
        if (recorded && FakeLoc.enableDebugLog) {
            Logger.info("摄像头控制面：$methodName uid=$uid pkg=$pkg")
        }
    }

    /**
     * 从方法签名推出 `[opCode, uid, package]` 的参数下标。
     *
     * 为什么按"第几个 Int / 第几个 String"推而不是写死签名：这些方法在各版本上
     * 加过参数（attributionTag、isPrivileged、…），但**相对顺序一直是
     * `(…, code: Int, uid: Int, packageName: String, …)`**。按类型序号取更耐版本漂移。
     * 一次性算好存 [indexCache]，热路径上只做一次哈希查找。
     */
    private fun indexesOf(method: Method): IntArray {
        val types = method.parameterTypes
        var firstInt = -1
        var secondInt = -1
        var firstString = -1
        types.forEachIndexed { i, t ->
            when {
                t == Int::class.javaPrimitiveType -> if (firstInt < 0) firstInt = i else if (secondInt < 0) secondInt = i
                t == String::class.java -> if (firstString < 0) firstString = i
            }
        }
        return intArrayOf(firstInt, secondInt, firstString)
    }

    // ---- cameraserver 的状态通知 ----

    private fun hookCameraServiceProxy(loaders: List<ClassLoader>) {
        val clazz = findClass(loaders, listOf("com.android.server.camera.CameraServiceProxy")) ?: return
        var count = 0
        listOf("notifyCameraState", "notifyPhysicalCameraState").forEach { name ->
            count += clazz.hookAllMethods(name, beforeHook { onCameraState(this) }).size
        }
        if (count > 0) {
            Logger.info("摄像头控制面：CameraServiceProxy hook 已安装（${clazz.name}，$count 个方法）")
        } else if (FakeLoc.enableDebugLog) {
            // 没命中就把**真实方法名**打出来：朝向信号（cameraId/facing/state）只从这条路来，
            // 而 AIDL 接口的方法集在各版本上挪过位置 —— 与其猜，不如让设备自己说。
            val methods = clazz.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .joinToString(", ") { it.name }
            Logger.info("摄像头控制面：CameraServiceProxy 上没有预期的状态方法；公开方法=[$methods]")
        }
    }

    /** `notifyCameraState(String cameraId, int facing, int newState)`：按类型取，同样不写死签名 */
    private fun onCameraState(param: MethodHookParam) {
        val method = param.method as? Method ?: return
        val types = method.parameterTypes
        var cameraId: String? = null
        val ints = ArrayList<Int>(2)
        types.forEachIndexed { i, t ->
            when {
                t == String::class.java && cameraId == null -> cameraId = param.args.getOrNull(i) as? String
                t == Int::class.javaPrimitiveType -> (param.args.getOrNull(i) as? Int)?.let { ints.add(it) }
            }
        }
        if (ints.size < 2) return
        val facing = ints[0]
        val newState = ints[1]
        if (state.onCameraState(cameraId, facing, newState) && FakeLoc.enableDebugLog) {
            Logger.info("摄像头控制面：camera=$cameraId ${CameraWatchState.facingName(facing)} ${CameraWatchState.stateName(newState)}")
        }
    }

    /** 单个 hook 族：失败只记账 + 一条日志，其余族继续（ROM 差异是常态） */
    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            PortalDiag.fail(PortalDiag.Area.HOOK_INSTALL, it)
            Logger.error("摄像头控制面：$name 安装失败（已跳过）：${it.message}", it)
        }
    }
}
