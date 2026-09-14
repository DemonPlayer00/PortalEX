package moe.fuqiuluo.xposed.utils

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * 挂钩原语（取代旧 API 的 `XposedBridge.hookMethod` / `hookAllMethods` /
 * `hookAllConstructors` / `invokeOriginalMethod` / `log`）。
 *
 * ## 它是**过渡适配层**，也是整个迁移的枢纽
 *
 * 全模块用到旧 `XposedBridge` 的地方：`hookAllMethods` 34 · `log` 28 · `hookMethod` 16 ·
 * `invokeOriginalMethod` 4 · `hookAllConstructors` 2。调用点一律改成这里，
 * 而这里**在过渡期内部转调旧 API** —— 于是：
 *
 *  · 调用点与 `de.robv.android.xposed.*` **彻底脱钩**（除了本文件）；
 *  · 行为逐字不变（适配器只是把参数搬来搬去）；
 *  · S4 接 libxposed 时**只改这一个文件**，调用点一行不动。
 *
 * ## 本文件是唯一允许 import `de.robv.android.xposed.*` 的地方
 *
 * ⚠️ **内部实现必须写 `XposedBridge.xxx`，绝不能写 `Hooks.xxx`** ——
 * 本对象与旧 API 同名同形，一次全局重命名就会把 `XposedBridge.log` 改成 `Hooks.log`，
 * 变成**静默的自我递归**（编译期只报"类型推断失败"，运行期就是栈溢出）。
 * 这条踩过：S2 的全局替换后 `log`/`invokeOriginalMethod` 全部变成自调。
 *
 * ## 适配器的两条纪律
 *
 * 1. **回调里的异常绝不许穿给宿主**：旧 API 的 `XposedBridge` 自己会兜，但我们这边多了一层
 *    适配，必须在这里把 [MethodHook] 抛出的异常吞掉并记账 —— 宿主应用不该因为我们的钩子崩。
 * 2. **`args` 是同一个数组**：直接把旧 param 的数组递给 [MethodHookParam]，改动自然生效，
 *    不需要（也不该）回写，否则会出现"改了两份、谁赢看顺序"的问题。
 */
object Hooks {

    // ---------------------------------------------------------------- 挂单个方法

    fun hookMethod(method: Method, hook: MethodHook): MethodHook.Unhook? =
        runCatching { XposedBridge.hookMethod(method, adapt(hook)) }.getOrNull()?.let { legacy ->
            object : MethodHook.Unhook {
                override fun unhook() = runCatching { legacy.unhook() }.let { }
            }
        }

    fun hookMethod(ctor: Constructor<*>, hook: MethodHook): MethodHook.Unhook? =
        runCatching { XposedBridge.hookMethod(ctor, adapt(hook)) }.getOrNull()?.let { legacy ->
            object : MethodHook.Unhook {
                override fun unhook() = runCatching { legacy.unhook() }.let { }
            }
        }

    // ---------------------------------------------------------------- 挂一批

    /** 按名字挂某个类下的**全部同名方法**（含继承链），返回每个方法各一个句柄 */
    fun hookAllMethods(clazz: Class<*>, methodName: String, hook: MethodHook): Set<MethodHook.Unhook> =
        runCatching { XposedBridge.hookAllMethods(clazz, methodName, adapt(hook)) }
            .getOrDefault(emptySet())
            .map { legacy ->
                object : MethodHook.Unhook {
                    override fun unhook() = runCatching { legacy.unhook() }.let { }
                } as MethodHook.Unhook
            }.toSet()

    fun hookAllConstructors(clazz: Class<*>, hook: MethodHook): Set<MethodHook.Unhook> =
        runCatching { XposedBridge.hookAllConstructors(clazz, adapt(hook)) }
            .getOrDefault(emptySet())
            .map { legacy ->
                object : MethodHook.Unhook {
                    override fun unhook() = runCatching { legacy.unhook() }.let { }
                } as MethodHook.Unhook
            }.toSet()

    // ---------------------------------------------------------------- 调原方法

    /**
     * 在被挂钩的方法里"再调一次原方法"。
     *
     * 调用点实际用的形态是 `(method, thisObject, args)`。
     */
    fun invokeOriginalMethod(method: Member, thisObject: Any?, args: Array<Any?>?): Any? =
        runCatching { XposedBridge.invokeOriginalMethod(method, thisObject, args) }.getOrNull()

    /**
     * 旧 API 需要它自己的 param 对象 —— 这里从 [MethodHookParam] 里取出适配时留下的引用
     * （见 [adapt] 里的 `backing`）。过渡期专用，S4 会换成 libxposed 的 `Chain.proceed()`。
     */
    fun invokeOriginalMethod(param: MethodHookParam): Any? {
        val legacy = param.backing as? XC_MethodHook.MethodHookParam ?: return null
        return invokeOriginalMethod(legacy.method, legacy.thisObject, legacy.args)
    }

    // ---------------------------------------------------------------- 日志

    fun log(message: String) {
        runCatching { XposedBridge.log(message) }
    }

    fun log(t: Throwable) {
        runCatching { XposedBridge.log(t) }
    }

    // ---------------------------------------------------------------- 适配器

    /** 中立的 [MethodHook] → 旧 API 的 `XC_MethodHook`。**整个过渡期唯一的桥**。 */
    internal fun adapt(hook: MethodHook): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
            val neutral = MethodHookParam(param.thisObject, param.method, param.args)
            neutral.backing = param
            neutral.seedResult(param.result)
            dispatch { hook.beforeHookedMethod(neutral) }
            flush(neutral, param)
        }

        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
            val neutral = MethodHookParam(param.thisObject, param.method, param.args)
            neutral.backing = param
            // after 阶段：原方法已经跑完，先把它的返回值/异常填进去，回调才看得见
            neutral.seedResult(param.result)
            if (param.hasThrowable()) neutral.setThrowable(param.getThrowable())
            dispatch { hook.afterHookedMethod(neutral) }
            flush(neutral, param)
        }
    }

    /** 回调异常一律吞掉 + 记账：**绝不穿给宿主应用** */
    private inline fun dispatch(block: () -> Unit) {
        runCatching(block).onFailure { Logger.error("钩子回调异常（已吞）", it) }
    }

    /**
     * 把中立参数的改动写回旧 param。
     *
     * ⚠️ **只在回调真的赋过 `result` 时写回**（`returnEarly` 即"赋过"）：否则 after 阶段
     * 会把原方法的返回值**覆盖成 null** —— 那是"钩子什么都没做，却把返回结果弄没了"的事故。
     * `args` 不需要回写（同一个数组）。
     */
    private fun flush(neutral: MethodHookParam, legacy: XC_MethodHook.MethodHookParam) {
        if (neutral.returnEarly) {
            // 与旧 API 的合成属性同义：赋值即短路（原方法不执行 / 结果以此为准）
            runCatching { legacy.result = neutral.result }
        }
        if (neutral.hasThrowable()) {
            runCatching { legacy.setThrowable(neutral.getThrowable()) }
        }
    }
}
