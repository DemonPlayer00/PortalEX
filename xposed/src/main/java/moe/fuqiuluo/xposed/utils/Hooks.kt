package moe.fuqiuluo.xposed.utils

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * 挂钩原语（libxposed 现代 API 的唯一出口）。
 *
 * ## 这一层做什么
 *
 * 把"挂钩"收敛到一处：调用点只说"挂哪个方法、回调里做什么"，
 * 至于框架给的是 `Chain`、参数在链上怎么传、原方法怎么绕开钩子再调一次 —— 都在这里。
 *
 * 全模块用量：`hookAllMethods` 34 · `log` 29 · `hookMethod` 16 ·
 * `invokeOriginalMethod` 4 · `hookAllConstructors` 2。
 *
 * ## 实现选择：`Chain` 单回调拦截器（**不再走旧 XposedBridge**）
 *
 * libxposed 的挂钩是 `hook(executable).intercept(Hooker)`，回调形态是
 * `intercept(chain)` 的**单回调拦截器链**；旧 API 是 before/after 两个回调。
 * 两者语义不同，但可以精确对齐 —— 对齐规则集中在 [intercept] 一处：
 *
 * | 旧行为 | 本层实现 |
 * | --- | --- |
 * | `beforeHookedMethod` 里改 `args` | 链上的参数拷成数组交给回调，回调改完由 `chain.proceed(args)` 用改后的值 |
 * | `beforeHookedMethod` 里 `result = x` | 不调 `chain.proceed()`，直接返回该结果（`returnEarly` 即"赋过值"） |
 * | 只实现了 before | 跑完回调就 `proceed(args)` |
 * | `afterHookedMethod` 里改 `result` | **先 `proceed()` 拿到原返回值**，再跑 after；after 赋过值就用 after 的 |
 * | `afterHookedMethod` 里 `setThrowable` | 先记下 `proceed()` 抛出的异常，after 跑完再按需抛出 |
 * | 回调抛异常 | 吞掉并记账，**绝不穿给宿主进程**（本体方法按"没写过这个钩子"继续） |
 *
 * ## 两条纪律
 *
 * 1. **回调里的异常不许穿给宿主**：旧 `XposedBridge` 自己会兜，这一层也必须兜
 *    —— 宿主应用不该因为我们的钩子崩。见 [guard]。
 * 2. **`args` 是同一个数组**：回调拿到的是"本次调用的参数数组"，`args[i] = x` 直接生效，
 *    不需要（也不该）回写。
 *
 * ## 与框架实例的关系
 *
 * `hook()` 是**模块实例**（`XposedModule` 实现 `XposedInterface`）的方法，
 * 而调用点散在 hook 深处、拿不到实例 —— 所以实例在入口 `onModuleLoaded` 里
 * 登记到 [ModuleRuntime]，这里按需取。取不到时挂不上钩子：返回 null 并记一条日志，
 * **不抛异常**（调用点多数在启动路径上，抛出去等于把一个钩子失败升级成进程事故）。
 */
object Hooks {

    // ---------------------------------------------------------------- 挂单个方法

    /** 挂一个方法。构造器走重载（内部统一按 [Executable] 处理） */
    fun hookMethod(method: Method, hook: MethodHook): MethodHook.Unhook? = hook(method, hook)

    fun hookMethod(ctor: Constructor<*>, hook: MethodHook): MethodHook.Unhook? = hook(ctor, hook)

    // ---------------------------------------------------------------- 挂一批

    /**
     * 按名字挂某个类下的**全部同名方法**（含继承链），返回每个方法各一个句柄。
     *
     * 匹配 = 沿 `superclass` 链收集 `declaredMethods` 里同名者，按"名字 + 形参类型"去重：
     * **重写只挂一次**（与旧 `XposedBridge.hookAllMethods` 一致）。
     * 钩子挂在**声明它的那个类**上 —— 挂在子类会漏掉"子类自己的重写"。
     */
    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        hook: MethodHook,
    ): Set<MethodHook.Unhook> {
        val out = LinkedHashSet<MethodHook.Unhook>()
        findMethods(clazz, methodName).forEach { m ->
            hook(m, hook)?.let { out.add(it) }
        }
        return out
    }

    /** 挂某个类的全部构造器（沿继承链：每个类声明的构造器各挂一份） */
    fun hookAllConstructors(clazz: Class<*>, hook: MethodHook): Set<MethodHook.Unhook> {
        val out = LinkedHashSet<MethodHook.Unhook>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredConstructors.forEach { ctor ->
                hook(ctor, hook)?.let { out.add(it) }
            }
            c = c.superclass
        }
        return out
    }

    // ---------------------------------------------------------------- 调原方法

    /**
     * 在被挂钩的方法**之外**再调一次原方法（绕开本模块的钩子）。
     *
     * 旧 API 的 `XposedBridge.invokeOriginalMethod`；新 API 的对应物是
     * `getInvoker(executable).setType(ORIGIN).invoke(thisObject, args)` ——
     * `ORIGIN` 表示"从原方法起算"，所以这次反射调用**不会**再进我们自己的钩子（不会自递归）。
     *
     * ⚠️ **不是 `chain.proceed()`**：`proceed()` 只能用在钩子回调内部，
     * 而本函数的调用点都在**回调之外**（延迟投递/守护线程里补发一次回调对象）。
     * 需要"在钩子里再调一次原方法"时应直接用 `chain.proceed()`。
     *
     * @return 原方法的返回值；方法不是 `Method` / 框架未就绪 / 调用抛错时返回 null
     *         （四个调用点都是"尽力而为"语义，失败即当次不投递）
     */
    fun invokeOriginalMethod(method: Member, thisObject: Any?, args: Array<Any?>?): Any? {
        val m = method as? Method ?: return null
        val api = ModuleRuntime.framework() ?: return null
        return runCatching {
            api.getInvoker(m)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(thisObject, *(args ?: emptyArray()))
        }.onFailure {
            Logger.error("Hooks: 调原方法失败（${m.name}）：${it.message}", it)
        }.getOrNull()
    }

    /**
     * 用中立参数再调一次原方法 —— 只取其中的成员/接收者/实参，语义与
     * [invokeOriginalMethod] 相同，供只拿到 `param` 的调用点使用。
     */
    fun invokeOriginalMethod(param: MethodHookParam): Any? =
        invokeOriginalMethod(param.method, param.thisObject, param.args)

    // ---------------------------------------------------------------- 日志

    /** 模块日志出口。tag 统一 [TAG]，于是 `adb logcat -s PortalEX` 能单挑模块日志 */
    fun log(message: String) = log(Log.INFO, message)

    fun log(t: Throwable) = log(Log.ERROR, t.stackTraceToString())

    fun log(priority: Int, message: String) {
        val api = ModuleRuntime.framework()
        val ok = api != null && runCatching { api.log(priority, TAG, message) }.isSuccess
        // 框架通道不可用（早期启动 / 单元测试）时回落到系统日志，别把信息丢了
        if (!ok) runCatching { Log.println(priority, TAG, message) }
    }

    private const val TAG = "PortalEX"

    // ---------------------------------------------------------------- 实现

    /** `hook()` 的公共实现：取框架实例 → 建钩子 → 失败只记账不抛 */
    private fun hook(executable: Executable, hook: MethodHook): MethodHook.Unhook? {
        val api = ModuleRuntime.framework()
        if (api == null) {
            Logger.error("Hooks: 框架实例未就绪，跳过挂钩 ${executable.name}", null)
            return null
        }
        return runCatching {
            val handle = api.hook(executable).intercept(intercept(hook))
            object : MethodHook.Unhook {
                override fun unhook() {
                    runCatching { handle.unhook() }
                }
            }
        }.onFailure {
            Logger.error("Hooks: 挂钩失败（${executable.name}）：${it.message}", it)
        }.getOrNull()
    }

    /**
     * 中立回调 → libxposed `Hooker`。**全模块唯一的拦截器实现**，语义对齐见类注释。
     */
    private fun intercept(hook: MethodHook): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        // 参数：链给的是 List，而回调要数组语义（`args[i] = x` 直接生效）
        val args: Array<Any?> = chain.args.toTypedArray()
        val param = MethodHookParam(chain.thisObject, chain.executable, args)

        if (hook.hasBefore()) guard("before") { hook.beforeHookedMethod(param) }

        // before 里赋过 result ⇒ 原方法不执行，直接短路（旧 API 的 setResult 语义）
        if (param.returnEarly) return@Hooker param.result

        var original: Any? = null
        var thrown: Throwable? = null
        try {
            original = chain.proceed(args)
        } catch (t: Throwable) {
            thrown = t
        }

        // after 语义：**先拿到原返回值再跑回调**；原方法抛异常时 after 仍要跑，
        // 抛出的异常留到 after 之后再抛。after 阶段赋过 result 则以它为准。
        if (hook.hasAfter()) {
            param.seedResult(original)
            thrown?.let { param.setThrowable(it) }
            guard("after") { hook.afterHookedMethod(param) }
        }

        return@Hooker when {
            hook.hasAfter() && param.returnEarly -> param.result
            thrown != null -> throw thrown
            else -> original
        }
    }

    /** 回调异常一律吞掉 + 记账：**绝不穿给宿主应用**（本体方法按"没这个钩子"继续） */
    private inline fun guard(phase: String, block: () -> Unit) {
        runCatching(block).onFailure {
            Logger.error("钩子回调异常（已吞，$phase）", it)
        }
    }

    /**
     * 沿继承链找同名方法，按"名字 + 形参类型"去重。
     *
     * 用 `declaredMethods` 而不是 `methods`：后者会把父类方法**以子类为声明者**再列一遍，
     * 于是"父类声明 + 子类重写"会被挂两次。按声明类挂则天然不重复。
     */
    private fun findMethods(clazz: Class<*>, name: String): List<Method> {
        val out = ArrayList<Method>()
        val seen = HashSet<String>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredMethods.forEach { m ->
                if (m.name == name && seen.add(m.name + m.parameterTypes.joinToString(","))) {
                    out.add(m)
                }
            }
            c = c.superclass
        }
        return out
    }
}
