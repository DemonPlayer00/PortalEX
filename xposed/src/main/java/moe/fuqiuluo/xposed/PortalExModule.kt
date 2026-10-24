package moe.fuqiuluo.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.ModuleRuntime

/**
 * **libxposed 现代入口**（登记在 `META-INF/xposed/java_init.list`）。
 *
 * ## 为什么要有第二个入口
 *
 * 上游已宣告 XSharedPreferences 一类旧 API 即将废弃，legacy 模块要迁到 libxposed：
 * 入口从"实现 `IXposedHookLoadPackage` 的任意类"变成"继承 [XposedModule] 的类"，
 * 挂钩从 before/after 变成 `Hooker.intercept(Chain)` 单回调拦截器链。
 *
 * 迁移期两条入口并存（`assets/xposed_init` 与 `META-INF/xposed/java_init.list`），
 * 但**"装什么"只有一份实现** —— 都调到 [FakeLocation.install]：
 * 于是本类只负责"把框架给的进程信息翻译成 [FakeLocation.ProcessInfo]"，一行 hook 逻辑都不重复。
 * 重复触发由 `FakeLocation.injectedPackages` 挡掉（libxposed 的 `onPackageReady`
 * 对同一进程可能因多个包而多次回调）。
 *
 * ## 与旧入口的对应关系（迁移的等价性依据）
 *
 * | 旧 API | libxposed |
 * | --- | --- |
 * | `handleLoadPackage(lpparam)` + `lpparam.classLoader/packageName/appInfo` | [onPackageReady] |
 * | `handleLoadPackage` 里包名 `"android"` 的分支 | [onSystemServerStarting] |
 * | `lpparam.processName` | [onModuleLoaded] 的 `processName`（**每进程只来一次**，故记在字段里） |
 *
 * 注意 `onSystemServerStarting` **只给类加载器**，没有包名 —— 这里显式补 `"android"`，
 * 好让 system_server 与旧入口走**同一个 when 分支**（否则框架侧 hook 会静默少装一半）。
 */
class PortalExModule : XposedModule() {

    /**
     * 本进程名。`onModuleLoaded` 保证每进程恰好一次，且早于任何包回调；
     * 而 `PackageReadyParam` 里**没有**进程名（旧 API 的 `LoadPackageParam` 有），
     * 所以在这里先存下来，供 `FusedStatus` 之类的诊断使用。
     */
    private var processName: String? = null

    /**
     * 最近一次 [install] 的入参。**热重载要用它重装**：框架在热重载时只回调
     * `onHotReloading/onHotReloaded`，不会再把 `onSystemServerStarting`/`onPackageReady`
     * 重放一遍，所以新实例必须自己知道"当初装的是谁"。
     *
     * 存的是**目标进程的 classLoader**（system_server / 应用自己的），不是模块自己的 ——
     * 热重载换掉的是**模块的类加载器**，目标的那个对象身份不变，可以安全跨实例传递。
     */
    private var lastPackage: String? = null
    private var lastClassLoader: ClassLoader? = null
    private var lastIsSystemApp: Boolean = true

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        // 偏好通道（ModulePrefs）要从实例上取：只有入口拿得到 XposedInterface。
        ModuleRuntime.attach(this)
        Logger.info(
            "libxposed 入口就绪：进程=${param.processName} systemServer=${param.isSystemServer}" +
                    " api=${runCatching { apiVersion }.getOrDefault(-1)}" +
                    " framework=${runCatching { frameworkName }.getOrDefault("?")}"
        )
    }

    /**
     * system_server：本机 LSPosed 走这个回调（旧入口对应的是包名 `"android"` 的分支）。
     * `isSystemApp = true` 与旧入口一致 —— `system_server` 的 appInfo 在部分 ROM 上为 null，
     * 不能靠它判定，[FakeLocation.install] 里另有显式的包名白名单兜底。
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        install(packageName = "android", classLoader = param.classLoader, isSystemApp = true)
    }

    /**
     * 应用进程：类加载器已就绪（旧入口的 `handleLoadPackage` 对应点）。
     *
     * 用 `onPackageReady` 而不是 `onPackageLoaded`：后者在 `AppComponentFactory` 实例化**之前**，
     * 拿到的是 default classloader；本模块要 hook 的是应用自己的类（Camera2 客户端），
     * 必须等**最终**的 classloader —— 旧入口给的也是最终的那个。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        val isSystemApp = (param.applicationInfo.flags and
                (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        install(param.packageName, param.classLoader, isSystemApp)
    }

    /**
     * **热重载 · 旧实例收尾**（API 102）。
     *
     * 框架在模块 APK 更新后，为**已注入的进程**（含 system_server）重新加载新 dex，
     * 并回调本方法让旧实例交班。返回值语义：**true = 允许继续热重载**
     * （框架的默认实现即返回 true；返回 false 会中止这次重载、保留旧实例）。
     *
     * ## 状态怎么交（这里有个必须绕开的坑）
     *
     * `setSavedInstanceState(Object)` 收的是任意对象，但**旧实例的类在新实例的类加载器里
     * 不是同一个 Class** —— 直接传自定义类型（如 data class）过去，新实例 `as` 会失败。
     * 所以只用**引导类加载器就能表达的类型**：`Array<Any?>` 里塞 String / ClassLoader / Boolean。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        Logger.info("热重载：旧实例交班（进程=$processName 目标=${lastPackage ?: "?"}）")
        val cl = lastClassLoader
        if (cl == null) {
            // 还没装过任何东西（例如只有 onModuleLoaded 跑过）⇒ 没有要交的班，允许重载即可
            return true
        }
        // 只用引导类加载器的类型：String / ClassLoader / Boolean
        param.setSavedInstanceState(arrayOf<Any?>(lastPackage, cl, lastIsSystemApp))
        return true
    }

    /**
     * **热重载 · 新实例接手**（API 102）。
     *
     * 两件事，顺序不能反：
     *  1. **先摘掉旧实例的钩子** —— 框架把旧句柄原样给了我们（[HotReloadedParam.oldHookHandles]）。
     *     不摘就会**双份注入**：同一方法上挂两份回调，交付帧发两遍、步数涨两倍。
     *     句柄对象属于旧类加载器，但 API 接口类由框架提供（父子共享），所以 `unhook()` 可以直接调；
     *     仍然套一层反射兜底，避免不同框架实现的类型归属差异。
     *  2. 再按旧实例交下来的入参**重装**（新加载器的静态是干净的）。
     *
     * ⚠️ **会话状态会丢**：`VirtualWorld`/`MotionEngine`/`LocConfig` 等都是模块类加载器里的
     * 单例，换加载器就等于全部归零（位置、体力、路线、开关）。所以热重载之后**模拟会话是停的**，
     * 需要 App 重新下发配置/起会话。把状态也搬过去是下一步（`LocConfig` 那批字段的
     * snapshot/restore），本方法目前只保证"机制可用且不双挂"。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        val old = param.oldHookHandles
        var unhooked = 0
        for (h in old) {
            if (h == null) continue
            val ok = runCatching {
                h.javaClass.getMethod("unhook").invoke(h)
                true
            }.getOrElse {
                Logger.warn("热重载：摘旧钩子失败：${it.message}")
                false
            }
            if (ok) unhooked++
        }
        Logger.info("热重载：新实例接手（进程=$processName）旧钩子 ${old.size} 个，已摘 $unhooked 个")

        @Suppress("UNCHECKED_CAST")
        val saved = param.savedInstanceState as? Array<Any?>
        val pkg = saved?.getOrNull(0) as? String
        val cl = saved?.getOrNull(1) as? ClassLoader
        val isSystem = saved?.getOrNull(2) as? Boolean ?: true
        if (pkg == null || cl == null) {
            Logger.warn("热重载：没有可用的重装入参（旧实例未交班）——需要 App 侧重新触发安装")
            return
        }
        install(pkg, cl, isSystem)
    }

    private fun install(packageName: String, classLoader: ClassLoader, isSystemApp: Boolean) {
        lastPackage = packageName
        lastClassLoader = classLoader
        lastIsSystemApp = isSystemApp
        // 入口回调整体兜底：任何异常都不许穿回框架（穿了就是宿主进程崩，且表现成"模块加载失败"）。
        runCatching {
            FakeLocation.install(
                FakeLocation.ProcessInfo(
                    packageName = packageName,
                    processName = processName,
                    classLoader = classLoader,
                    isSystemApp = isSystemApp
                )
            )
        }.onFailure {
            Logger.error("libxposed 入口安装失败（已跳过）：$packageName：${it.message}", it)
        }
    }
}
