package moe.fuqiuluo.xposed.utils;

import java.lang.reflect.Member;

/**
 * 挂钩回调的**框架中立**参数（取代旧 API 的 {@code XC_MethodHook.MethodHookParam}）。
 *
 * ## 为什么要造一个自己的类型
 *
 * 全模块有 **44 处** `override fun before/afterHookedMethod(param: MethodHookParam)`。
 * 那些 override 的**签名必须与基类一致** —— 只要基类还是旧 API 的 `XC_MethodHook`，
 * 参数类型就不能换。所以迁移的正确切法是：**连基类一起换成我们自己的**
 * （[MethodHook] + 本类），于是那 44 处**一个字都不用改**。
 *
 * 过渡期由 [Hooks] 里的适配器把本类型与旧 API 的 param 对接；S4 之后换成接 libxposed 的
 * `MethodHook.Param` —— 调用点依然一行不动。
 *
 * ## 为什么这个类必须是 **Java**
 *
 * 旧 API 是 Java 类，字段在 Kotlin 眼里是**平台类型**（`Object!` / `Object[]!`），
 * 于是 `param.thisObject.javaClass`、`args[0].javaClass`、`result.javaClass` 这类写法**天然合法**，
 * 不需要 `!!` 或 `?.`。用 Kotlin 重写会让它们全部变成 `Any?`，被迫在 8 个调用点插空断言 ——
 * 那是"换 API"被顺手变成"改代码"，而且会一路蔓延到 44 个回调里。
 * libxposed 的 `MethodHook.Param` 同样是 Java 类，**今天能编译的调用点明天照样能编译**。
 *
 * ## 一个必须复刻的语义（踩过，别改）
 *
 * 旧 API 的 `result` 是 **private 字段 + getResult/setResult**，Kotlin 里是**合成属性**：
 * `param.result = x` 实际调 `setResult(x)` —— 而它会**同时**把 `returnEarly` 置真，
 * 也就是"原方法不再执行"。本项目正是靠这个行为做权限检查的旁路
 * （见 `RemoteCommandHandler` 里那句"在 beforeHook 里直接 `result = true`，
 * 平台自己的权限检查会被跳过"）。**直接把字段写成 public 就不是这个语义了**，
 * 所以这里保持 private 字段 + 同名 setter，与本类注释里的说明严格一致。
 */
public class MethodHookParam {

    /** 方法接收者（静态方法为 null）。与旧 API 同名同义，非 final 以保持一致。 */
    public Object thisObject;

    /** 被挂钩的成员（方法与构造函数都用它）。 */
    public Member method;

    /**
     * 实参数组。**与旧 API 是同一个数组实例**（适配器直接把原数组递过来），
     * 所以 `args[i] = x` 的改动会原样生效，不需要回写。
     */
    public Object[] args;

    /**
     * 原方法是否已被"提前返回"短路。与旧 API 的 `returnEarly` 同名同义。
     * 由 [setResult] 置位 —— 调用点不需要也不应该自己碰它。
     */
    public boolean returnEarly;

    private Object result;

    private Throwable throwable;

    /**
     * 过渡期专用：适配器把它对应的**旧 API 的 param** 挂在这里，
     * 供 `Hooks.invokeOriginalMethod(param)` 实现"再调一次原方法"。
     * S4 之后这里会换成 libxposed 的 `Chain`。**除适配器外不要碰**。
     */
    public Object backing;

    /** 仅适配器可见：调用点不要自己 new，框架会把参数递进来。 */
    public MethodHookParam(Object thisObject, Member method, Object[] args) {
        this.thisObject = thisObject;
        this.method = method;
        this.args = args;
    }

    /**
     * 返回值。**赋值即短路**（见类注释）—— 这是合成属性 setter，与旧 API 逐字一致。
     */
    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
        this.returnEarly = true;
    }

    public boolean hasThrowable() {
        return throwable != null;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    /**
     * 适配器专用：把"原方法的返回值"填进来（after 钩子用），**不置 returnEarly** ——
     * 于是 `param.result == null` 这类判断与旧 API 语义一致。
     */
    public void seedResult(Object value) {
        this.result = value;
    }
}
