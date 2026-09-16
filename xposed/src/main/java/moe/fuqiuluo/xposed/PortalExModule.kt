package moe.fuqiuluo.xposed

import io.github.libxposed.api.XposedModule
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

    private fun install(packageName: String, classLoader: ClassLoader, isSystemApp: Boolean) {
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
