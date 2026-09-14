package moe.fuqiuluo.xposed.utils;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 单测用的假框架：**它本身不做任何事**，把每个调用转给 {@link TestHandler}。
 *
 * ## 为什么要有这个 Java 壳（而不是在 Kotlin 里直接继承 XposedModule）
 *
 * 1. 真实基类 `io.github.libxposed.api.XposedModule` 继承的 `XposedInterfaceWrapper`
 *    把这些方法都实现成 **final** —— 单测里没法替换（实测：`'hook' in
 *    XposedInterfaceWrapper is final`）。所以这里**直接实现 `XposedInterface`**。
 * 2. 泛型 `Invoker<T, U>` 是自引用声明（`T extends Invoker<T, U>`），在 Kotlin 里
 *    实现那堆 override 全是平台类型与型变噪音；Java 里照抄签名最不容易走样。
 *
 * 于是：生产代码认的是"模块实例实现了 `XposedInterface`"（`ModuleRuntime` 就是这样转型的），
 * 测试里换成这个壳，挂钩语义单测（{@code HooksChainTest}）就能自己驱动一次链并断言返回值。
 * ⚠️ 这个类只在 `src/test`，不会进模块 APK。
 */
final class FakeXposedModule implements XposedInterface {

    interface TestHandler {
        /** 返回 null 表示"本测试不关心这个成员" */
        Object invoke(String method, Object[] args);
    }

    private final TestHandler handler;

    FakeXposedModule(TestHandler handler) {
        this.handler = handler;
    }

    private Object call(String method, Object... args) {
        return handler.invoke(method, args);
    }

    // ---------------------------------------------------------------- 测试真正要用的

    @Override
    public HookBuilder hook(Executable executable) {
        return (HookBuilder) call("hook", executable);
    }

    @Override
    public void log(int priority, String tag, String msg) {
        call("log", priority, tag, msg);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Invoker<?, Method> getInvoker(Method method) {
        return (Invoker<?, Method>) call("getInvoker", method);
    }

    @Override
    public SharedPreferences getRemotePreferences(String group) {
        return (SharedPreferences) call("getRemotePreferences", group);
    }

    // ---------------------------------------------------------------- 其余成员：测试不关心

    @Override
    public void log(int priority, String tag, String msg, Throwable tr) {
        log(priority, tag, msg);
    }

    @Override
    public String getFrameworkName() {
        return "FakeFramework";
    }

    @Override
    public String getFrameworkVersion() {
        return "test";
    }

    @Override
    public long getFrameworkVersionCode() {
        return 0;
    }

    @Override
    public long getFrameworkProperties() {
        return 0;
    }

    @Override
    public HookBuilder hookClassInitializer(Class<?> clazz) {
        throw new UnsupportedOperationException("not used");
    }

    @Override
    public boolean deoptimize(Executable executable) {
        return false;
    }

    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return null;
    }

    @Override
    public String[] listRemoteFiles() {
        return new String[0];
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        throw new FileNotFoundException(name);
    }

    @Override
    public <T> CtorInvoker<T> getInvoker(Constructor<T> constructor) {
        throw new UnsupportedOperationException("not used");
    }
}
