package moe.fuqiuluo.xposed.utils;

/**
 * 挂钩回调基类（取代旧 API 的 {@code XC_MethodHook}）。
 *
 * ## 为什么这个基类必须是 **Java**，而不是 Kotlin
 *
 * 旧 API 的 `XC_MethodHook` 是 Java 类，它的参数在 Kotlin 眼里是**平台类型** `MethodHookParam!` ——
 * 于是全模块的 override 里两种写法**同时合法且并存**：`param: MethodHookParam`（30 处）
 * 和 `param: MethodHookParam?`（14 处，回调体里自己判空）。
 *
 * 如果拿 Kotlin 写这个基类，参数类型只能二选一，另一边的 30/14 处立刻编译不过 ——
 * 那就要为一个纯机械的迁移去改 44 个回调的语义，正是迁移里最不该做的事
 * （"换 API" 被顺手变成 "改行为"）。用 Java 写基类，参数同样是平台类型，
 * **两种 override 都合法**，44 处一个字都不用动。
 *
 * 顺带对齐了终点：libxposed 的 `MethodHook.Param` 也是 Java 类，同样带平台类型语义 ——
 * 这些 override 在 S4 之后依然不需要改。
 *
 * 与旧 API 的差别只有一处：**类名**（`XC_MethodHook` → [MethodHook]）。方法名、参数、调用时机全同。
 */
public abstract class MethodHook {

    /**
     * 本次回调**是否实现了 before 阶段**。
     *
     * 为什么需要它：旧 API 是 before/after 两个回调，框架按你有没有重写来决定调不调；
     * 而 libxposed 是**单回调拦截器链**（`intercept(chain)`），框架只会调我们那一个方法 ——
     * 于是"这个钩子到底要不要跑某个阶段"必须由我们自己判断，否则会给只写了 after 的钩子
     * 白跑一次 before（甚至因为 before 为空而改变短路判断）。
     * 见 {@code Hooks.intercept}：只实现了 after 时，`chain.proceed()` 的返回值必须留住。
     */
    public final boolean hasBefore() {
        return getClass() != MethodHook.class && declares("beforeHookedMethod");
    }

    /** 同 {@link #hasBefore()}，对应 after 阶段。 */
    public final boolean hasAfter() {
        return getClass() != MethodHook.class && declares("afterHookedMethod");
    }

    /** 本类层次里是否**声明过**（重写过）某个回调方法 */
    private boolean declares(String name) {
        Class<?> c = getClass();
        while (c != null && c != MethodHook.class) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /** 原方法执行**之前**调用。赋值 `param.result` 即短路（见 [MethodHookParam] 的类注释）。 */
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** 原方法执行**之后**调用，此时 `param.result` 已是原方法的返回值。 */
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** 取消挂钩的句柄（取代 `XC_MethodHook.Unhook`）。 */
    public interface Unhook {
        void unhook();
    }
}

