package moe.fuqiuluo.xposed.utils

import android.content.SharedPreferences

/**
 * 模块实例的进程内登记处 —— 供**非入口**代码取用只有框架才给得出的东西。
 *
 * ## 为什么需要它
 *
 * libxposed 的 [io.github.libxposed.api.XposedInterface] 是实现接口的**模块实例**方法：
 * `getRemotePreferences(group)`（跨进程读模块 App 的偏好）、`getModuleApplicationInfo()`
 * 之类都挂在实例上，而模块实例只在入口类（`PortalExModule`）里拿得到。
 * 可需要这些能力的代码（[ModulePrefs]）跑在 hook 回调深处，拿不到入口引用 ——
 * 所以入口在 `onModuleLoaded` 里把实例登记到这里，别处按需取。
 *
 * ## 为什么字段类型是 `Any?` 而不是 `XposedInterface?`
 *
 * 过渡期两条入口并存：旧入口（`FakeLocation`）在**不支持 libxposed 的框架**上也会跑。
 * 若这里的字段类型写成 `XposedInterface`，那么这些框架上**加载本类**时就要解析
 * `io.github.libxposed.api.XposedInterface` —— 而那个类只有框架的 framework.dex 提供，
 * 于是读偏好这件小事会把整个类拖成 `NoClassDefFoundError`。
 * 存成 `Any?`、用的时候在 [runCatching] 里转型，则为"没有这条通道"保留了干净的降级路径。
 *
 * ## 纪律
 *
 * 本类**只做登记与取用**，不含任何业务判断：取不到就返回 null，由调用方决定回落行为
 * （见 [ModulePrefs]：读不到一律按"开关未开启"处理）。
 */
internal object ModuleRuntime {

    /** 模块实例。`onModuleLoaded` 每进程恰好一次，之后只读 —— volatile 保证可见性。 */
    @Volatile
    private var instance: Any? = null

    /** 入口登记。重复登记无害（同进程只会有一次 `onModuleLoaded`，但双入口期可能两条都到）。 */
    fun attach(module: Any) {
        instance = module
    }

    /** 诊断用：当前进程是否已经接上 libxposed 通道。 */
    fun attached(): Boolean = instance != null

    /**
     * 读模块 App 的偏好（框架投递，被注入进程里**只读**）。
     *
     * @param group 偏好文件名（不含 `.xml`），即 App 侧 `getSharedPreferences(name, …)` 的 `name`
     * @return 读不到（未接上通道 / 框架不支持 / 框架拒绝）一律 null
     */
    fun remotePreferences(group: String): SharedPreferences? {
        val module = instance ?: return null
        return runCatching {
            // 直接写类型（不反射）：签名由编译期保证，改 API 时立刻编译报错，而不是运行期静默失败。
            // 万一框架没提供这个类，转型/调用会抛 NoClassDefFoundError —— 被上面的 runCatching
            // 吞掉并记账，降级为"读不到"，与旧通道不可用时的行为一致。
            val api = module as io.github.libxposed.api.XposedInterface
            api.getRemotePreferences(group)
        }.onFailure {
            Logger.error("ModuleRuntime: 取远程偏好失败（$group）：${it.message}", it)
        }.getOrNull()
    }
}
