package moe.fuqiuluo.xposed.utils

import io.github.libxposed.api.XposedInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 挂钩原语的语义单测：用**假框架**把 [Hooks] 的 before/after 对齐规则钉死。
 *
 * ## 为什么值得写
 *
 * [Hooks] 是全模块唯一的挂钩出口（`hookAllMethods` 34 + `log` 29 + `hookMethod` 16 +
 * `invokeOriginalMethod` 4 + `hookAllConstructors` 2 个调用点），而它做的事是
 * **把旧 before/after 语义翻译成 libxposed 的单回调拦截器链**。这种翻译错一条就是
 * "钩子静默失效"或"宿主方法被意外短路"，两者在真机上都不容易看出来 ——
 * 真机只能证明"日志出现了"，证明不了"语义对"。
 *
 * 假框架（[FakeXposedModule] + 本文件的 handler）把 `hook()` 拿到的钩子记下来，
 * 测试**自己驱动一次链**（`hooker.intercept(chain)`，chain 真去反射调目标方法），
 * 于是返回值与副作用都是真实可断言的。覆盖最容易错的三处：
 *
 *  1. before 里 `result = x` 必须**短路原方法**；
 *  2. after 必须**先拿到原返回值**再跑回调，只有 after 赋过值才覆盖；
 *  3. 回调抛异常**不许穿给宿主**（旧 `XposedBridge` 自己会兜，这一层也必须兜）。
 *
 * `invokeOriginalMethod` 走 `getInvoker(ORIGIN)`：假 Invoker 真去反射调原方法，
 * 于是"绕开钩子再调一次"这条也能验。
 */
class HooksChainTest {

    // ------------------------------------------------------------------ 测试目标

    class Probe {
        var lastProvider: String = "(unset)"
        var lastN: Int = -1
        var ranAs: String = "none"

        fun tagged(provider: String, n: Int): String {
            lastProvider = provider
            lastN = n
            return "tag($provider,$n)"
        }

        /** 供"绕过钩子再调一次"的用例：原方法体会打上标记 */
        fun original(provider: String): String {
            ranAs = "original"
            return "real:$provider"
        }

        fun boom(): String = throw IllegalStateException("target-boom")
    }

    // ------------------------------------------------------------------ 假框架

    private val logged = ArrayList<String>()
    private lateinit var fake: FakeXposedModule

    /** 已挂钩：executable → 钩子 */
    private val installed = ArrayList<Pair<Executable, XposedInterface.Hooker>>()

    private fun installFakeFramework() {
        installed.clear()
        logged.clear()
        fake = FakeXposedModule { method, args ->
            when (method) {
                "hook" -> hookBuilder(args!![0] as Executable)
                "log" -> {
                    logged += args!![2] as String
                    Any()
                }
                "getInvoker" -> invokerFor(args!![0] as Method)
                else -> null
            }
        }
        ModuleRuntime.attach(fake)
    }

    @After
    fun detach() {
        // ModuleRuntime 的登记是进程内静态状态：测完换成中性对象，避免串味
        ModuleRuntime.attach(Any())
    }

    /** 假 HookBuilder：`intercept` 时把钩子记下来，`set*` 返回自身（链式） */
    private fun hookBuilder(executable: Executable): Any {
        val self = arrayOfNulls<Any>(1)
        val builder = Proxy.newProxyInstance(
            XposedInterface.HookBuilder::class.java.classLoader,
            arrayOf(XposedInterface.HookBuilder::class.java),
        ) { _, m, args ->
            when (m.name) {
                "intercept" -> {
                    installed += executable to (args!![0] as XposedInterface.Hooker)
                    object : XposedInterface.HookHandle {
                        override fun getExecutable(): Executable = executable
                        override fun unhook() {}
                    }
                }
                else -> self[0]!!
            }
        }
        self[0] = builder
        return builder
    }

    /** 假 Invoker：`getInvoker(m).invoke(obj, args)` 真去反射调原方法 */
    private fun invokerFor(method: Method): Any {
        val self = arrayOfNulls<Any>(1)
        val invoker = Proxy.newProxyInstance(
            XposedInterface.Invoker::class.java.classLoader,
            arrayOf(XposedInterface.Invoker::class.java),
        ) { _, m, args ->
            when (m.name) {
                "setType" -> self[0]!!
                "invoke" -> {
                    method.isAccessible = true
                    val thisObject = args!![0]
                    @Suppress("UNCHECKED_CAST")
                    val rest = (args[1] as? Array<Any?>) ?: emptyArray()
                    try {
                        method.invoke(thisObject, *rest)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
                else -> throw UnsupportedOperationException(m.name)
            }
        }
        self[0] = invoker
        return invoker
    }

    /** 一次"框架调用被挂钩方法"的模拟：把链交给钩子，钩子内部会走 `chain.proceed()` */
    private fun callThroughHook(thisObject: Any?, m: Method, vararg args: Any?): Any? {
        val hooker = installed.firstOrNull { it.first == m }?.second
            ?: error("没有挂到 $m 上")
        return hooker.intercept(FakeChain(thisObject, m, args.toList()))
    }

    private class FakeChain(
        private val thisObject: Any?,
        private val executable: Executable,
        private val args: List<Any?>,
    ) : XposedInterface.Chain {
        override fun getExecutable(): Executable = executable
        override fun getThisObject(): Any? = thisObject
        override fun getArgs(): List<Any?> = args
        override fun getArg(index: Int): Any? = args[index]
        override fun proceed(): Any? = proceed(args.toTypedArray())

        override fun proceed(newArgs: Array<out Any?>): Any? {
            val m = executable as Method
            m.isAccessible = true
            return try {
                m.invoke(thisObject, *newArgs)
            } catch (e: InvocationTargetException) {
                // 真框架的 proceed() 直接抛原异常；反射会包一层，这里拆掉以保持等价
                throw e.targetException
            }
        }

        override fun proceedWith(result: Any): Any? = result
        override fun proceedWith(result: Any, newArgs: Array<out Any?>): Any? = result
    }

    private fun taggedMethod(): Method =
        Probe::class.java.getMethod("tagged", String::class.java, Int::class.javaPrimitiveType)

    // ------------------------------------------------------------------ 用例

    @Test
    fun `before 里赋 result 必须短路原方法`() {
        installFakeFramework()
        val probe = Probe()
        val m = taggedMethod()

        Hooks.hookMethod(m, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = "hijacked"
            }
        })

        assertEquals("hijacked", callThroughHook(probe, m, "gps", 7))
        assertEquals("原方法体不该执行", "(unset)", probe.lastProvider)
        assertEquals("原方法体不该执行", -1, probe.lastN)
    }

    @Test
    fun `before 改 args 必须传给原方法`() {
        installFakeFramework()
        val probe = Probe()
        val m = taggedMethod()

        Hooks.hookMethod(m, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = "network"
                param.args[1] = 42
            }
        })

        assertEquals("tag(network,42)", callThroughHook(probe, m, "gps", 7))
        assertEquals("network", probe.lastProvider)
        assertEquals(42, probe.lastN)
    }

    @Test
    fun `after 能看见原返回值并覆盖它`() {
        installFakeFramework()
        val probe = Probe()
        val m = taggedMethod()
        var seen: Any? = null

        Hooks.hookMethod(m, object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                seen = param.result
                param.result = "overridden"
            }
        })

        assertEquals("overridden", callThroughHook(probe, m, "gps", 7))
        assertEquals("after 阶段应看到原方法返回值", "tag(gps,7)", seen)
        assertEquals("原方法确实跑过", "gps", probe.lastProvider)
    }

    @Test
    fun `只实现 after 时不得丢掉原返回值`() {
        installFakeFramework()
        val probe = Probe()
        val m = taggedMethod()
        var ran = false

        Hooks.hookMethod(m, object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                ran = true
            }
        })

        // 关键：after 没覆盖时必须原样返回原方法结果（写错就是"钩子把返回值弄没了"）
        assertEquals("tag(gps,7)", callThroughHook(probe, m, "gps", 7))
        assertTrue(ran)
    }

    @Test
    fun `原方法抛异常时 after 仍执行 且异常照旧抛出`() {
        installFakeFramework()
        val probe = Probe()
        val m = Probe::class.java.getMethod("boom")
        var afterRan = false
        var sawThrowable = false

        Hooks.hookMethod(m, object : MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                afterRan = true
                sawThrowable = param.hasThrowable()
            }
        })

        val thrown = runCatching { callThroughHook(probe, m) }.exceptionOrNull()
        assertTrue("原方法异常必须抛出，实得：$thrown", thrown is IllegalStateException)
        assertTrue(afterRan)
        assertTrue("after 阶段应看得到异常", sawThrowable)
    }

    @Test
    fun `回调抛异常不许穿给宿主`() {
        installFakeFramework()
        val probe = Probe()
        val m = taggedMethod()

        Hooks.hookMethod(m, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                throw RuntimeException("hook-boom")
            }
        })

        // before 抛异常 ⇒ 吞掉并记账，然后按"没这个钩子"继续跑原方法
        assertEquals("tag(gps,7)", callThroughHook(probe, m, "gps", 7))
        assertTrue("应记一条吞异常日志，实得：$logged", logged.any { it.contains("钩子回调异常") })
    }

    @Test
    fun `invokeOriginalMethod 绕开钩子调原方法`() {
        installFakeFramework()
        val probe = Probe()
        val m = Probe::class.java.getMethod("original", String::class.java)

        // 挂一个"短路"钩子：正常调用会被拦成 hijacked
        Hooks.hookMethod(m, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = "hijacked"
            }
        })
        assertEquals("hijacked", callThroughHook(probe, m, "gps"))

        // 而 invokeOriginalMethod 走 getInvoker(ORIGIN)：必须真的执行原方法体
        probe.ranAs = "none"
        assertEquals("real:gps", Hooks.invokeOriginalMethod(m, probe, arrayOf("gps")))
        assertEquals("original", probe.ranAs)
    }

    @Test
    fun `hookAllMethods 同名只挂一次 且挂到真实方法上`() {
        installFakeFramework()
        val m = taggedMethod()

        val unhooks = Hooks.hookAllMethods(Probe::class.java, "tagged", object : MethodHook() {})
        assertEquals("同名方法只应挂一次", 1, unhooks.size)
        assertEquals("Probe 只声明了一个 tagged 重载", 1, installed.size)

        // 挂的必须是那个真实方法对象（不是空转）：callThroughHook 找不到会 error
        callThroughHook(Probe(), m, "gps", 1)

        // 构造器：Probe 只有隐式默认构造器 ⇒ 恰好一个
        assertEquals(1, Hooks.hookAllConstructors(Probe::class.java, object : MethodHook() {}).size)
        val ctor: Constructor<*> = Probe::class.java.declaredConstructors.single()
        assertEquals(0, ctor.parameterCount)
    }
}
