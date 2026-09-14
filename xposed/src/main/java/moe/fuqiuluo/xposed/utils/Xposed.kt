package moe.fuqiuluo.xposed.utils

import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val hookedMethods = Collections.synchronizedSet(mutableSetOf<String>())
private val hookOnceLock = ReentrantLock()

private fun generateOnceHookMethodKey(
    className: String,
    methodName: String,
    parameterTypes: Array<out Class<*>>
): String = "${className}#${methodName}(${parameterTypes.joinToString(",") { it.name } })"

private fun <T> ifNotHook(
    className: String,
    methodName: String,
    parameterTypes: Array<out Class<*>>,
    ifNotHooked: (key: String) -> T
): T? {
    val key = generateOnceHookMethodKey(className, methodName, parameterTypes)
    return if(hookedMethods.contains(key)) {
        null
    } else {
        ifNotHooked(key)
    }
}

/**
 * This method will only allow one hooker to add into Xposed.
 * @return Unhook object, you can use it to unhook the method.
 */
fun Method.onceHook(callback: MethodHook): MethodHook.Unhook? {
    return ifNotHook(declaringClass.name, name, parameterTypes) {
        hookOnceLock.withLock {
            // 锁内二次检查：避免两个线程同时通过外层 contains 检查后重复 hook 同一方法
            if (hookedMethods.contains(it)) {
                return@ifNotHook null
            }
            hookedMethods.add(it)
            try {
                Hooks.hookMethod(this, callback)
            } catch (t: Throwable) {
                // hook 失败时回滚标记，避免后续无法重试
                hookedMethods.remove(it)
                throw t
            }
        }
    }
}

/**
 * This method will only allow one hooker to add into Xposed.
 *
 * Note: The callback will only be executed before the original method.
 *
 * @return Unhook object, you can use it to unhook the method.
 */
fun Method.onceHookBefore(callback: MethodHookParam.() -> Unit): MethodHook.Unhook? {
    return onceHook(object : MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            kotlin.runCatching {
                param.callback()
            }.onFailure {
                Hooks.log(it)
            }
        }
    })
}

/**
 * This method will only allow one hooker to add into Xposed.
 * Note: the callback will only be executed after the original method.
 * @return Unhook object, you can use it to unhook the method.
 */
fun Method.onceHookAfter(callback: MethodHookParam.() -> Unit): MethodHook.Unhook? {
    return onceHook(object : MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            kotlin.runCatching {
                param.callback()
            }.onFailure {
                Hooks.log(it)
            }
        }
    })
}

/**
 * This method will only allow one hooker to add into Xposed.
 * @return set of Unhook object, you can use it to unhook the method.
 */
fun <T> Class<T>.onceHookAllMethod(methodName: String, callback: MethodHook): Set<MethodHook.Unhook> {
    val unhooks = mutableSetOf<MethodHook.Unhook>()
    hookOnceLock.withLock {
        declaredMethods.forEach { method ->
            if (method.name == methodName) {
                ifNotHook(name, methodName, method.parameterTypes) { key ->
                    // 锁内二次检查：避免并发重复 hook
                    if (hookedMethods.contains(key)) {
                        return@ifNotHook
                    }
                    val unhook = runCatching { method.hook(callback) }.getOrElse {
                        Hooks.log(it)
                        null
                    }
                    if (unhook != null) {
                        unhooks.add(unhook)
                        hookedMethods.add(key)
                    }
                }
            }
        }
    }
    return unhooks
}

/**
 * This method will only allow one hooker to add into Xposed.
 *
 * Note: if the method is not found, the method will return `null`.
 *
 * @return Unhook object, you can use it to unhook the method.
 */
fun <T> Class<T>.onceHookMethod(methodName: String, vararg parameterTypes: Class<*>, callback: MethodHook): MethodHook.Unhook? {
    return XposedHelpers.findMethodExactIfExists(this, methodName, *parameterTypes)?.onceHook(callback)
}

/**
 * This method will only allow one hooker to add into Xposed.
 *
 * Note: the callback will only be executed before the original method.
 *
 * @return Unhook object, you can use it to unhook the method.
 */
fun <T> Class<T>.onceHookMethodBefore(methodName: String, vararg parameterTypes: Class<*>, callback: MethodHookParam.() -> Unit): MethodHook.Unhook? {
    return XposedHelpers.findMethodExactIfExists(this, methodName, *parameterTypes)?.onceHookBefore(callback)
}

/**
 * This method will only allow one hooker to add into Xposed.
 * Note: the callback will only be executed after the original method.
 * @return Unhook object, you can use it to unhook the method.
 */
fun <T> Class<T>.onceHookMethodAfter(methodName: String, vararg parameterTypes: Class<*>, callback: MethodHookParam.() -> Unit): MethodHook.Unhook? {
    return XposedHelpers.findMethodExactIfExists(this, methodName, *parameterTypes)?.onceHookAfter(callback)
}

/**
 * This method will only allow one hooker to add into Xposed.
 *
 * Note: The original method will be executed when callback `shouldDoNothing` returns false.
 *
 * @return Unhook object, you can use it to unhook the method.
 */
fun <T> Class<T>.onceHookDoNothingMethod(methodName: String, vararg parameterTypes: Class<*>, shouldDoNothing: MethodHookParam.() -> Boolean): MethodHook.Unhook? {
    return onceHookMethodBefore(methodName, *parameterTypes) {
        if (kotlin.runCatching { shouldDoNothing() }.onFailure { Hooks.log(it) }.getOrNull() == true) {
            result = null
        }
    }
}

/**
 * This method will be hooked.
 * @return set of Unhook object, you can use it to unhook the method
 */
fun <T> Class<T>.hookAllMethods(methodName: String, callback: MethodHook): Set<MethodHook.Unhook> {
    return Hooks.hookAllMethods(this, methodName, callback)
}

/**
 * This method will be hooked,
 * but the callback will only be executed before the original method
 */
fun <T> Class<T>.hookAllMethodsBefore(methodName: String, callback: MethodHookParam.() -> Unit): Set<MethodHook.Unhook> {
    return hookAllMethods(methodName, object : MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            kotlin.runCatching {
                param.callback()
            }.onFailure {
                Hooks.log(it)
            }
        }
    })
}

/**
 * This method will be hooked,
 * but the callback will only be executed after the original method
 */
fun <T> Class<T>.hookAllMethodsAfter(methodName: String, callback: MethodHookParam.() -> Unit): Set<MethodHook.Unhook> {
    return hookAllMethods(methodName, object : MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            kotlin.runCatching {
                param.callback()
            }.onFailure {
                Hooks.log(it)
            }
        }
    })
}

/**
 * This method will be hooked.
 *
 * Note: If this method is an interface method or an abstract method, `IllegalArgumentException` will be thrown
 *
 * @return Unhook object, you can use it to unhook the method
 */
fun Method.hook(callback: MethodHook): MethodHook.Unhook? {
    return Hooks.hookMethod(this, callback)
}

/**
 * This method will be hooked,
 * but the callback will only be executed before the original method
 */
fun Method.hookBefore(callback: MethodHookParam.() -> Unit): MethodHook.Unhook? {
    return hook(object : MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            kotlin.runCatching {
                param.callback()
            }.onFailure {
                Hooks.log(it)
            }
        }
    })
}

/**
 * This method will be hooked,
 * but the callback will only be executed after the original method
 */
fun Method.hookAfter(callback: MethodHookParam.() -> Unit): MethodHook.Unhook? {
    return hook(object : MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            kotlin.runCatching {
                param.callback()
            }.onFailure {
                Hooks.log(it)
            }
        }
    })
}

/**
 * This method will be hooked.
 *
 * Note: If this method is not found, the method will return `null`
 *
 * @return Unhook object, you can use it to unhook the method
 */
fun <T> Class<T>.hookMethod(methodName: String, vararg parameterTypes: Class<*>, callback: MethodHook): MethodHook.Unhook? {
    return XposedHelpers.findMethodExactIfExists(this, methodName, *parameterTypes)?.hook(callback)
}

/**
 * This method will be hooked,
 * but the callback will only be executed before the original method
 */
fun <T> Class<T>.hookMethodBefore(methodName: String, vararg parameterTypes: Class<*>, callback: MethodHookParam.() -> Unit): MethodHook.Unhook? {
    return XposedHelpers.findMethodExactIfExists(this, methodName, *parameterTypes)?.hookBefore(callback)
}

/**
 * This method will be hooked,
 * but the callback will only be executed after the original method
 */
fun <T> Class<T>.hookMethodAfter(methodName: String, vararg parameterTypes: Class<*>, callback: MethodHookParam.() -> Unit): MethodHook.Unhook? {
    return XposedHelpers.findMethodExactIfExists(this, methodName, *parameterTypes)?.hookAfter(callback)
}

/**
 * This method will be hooked.
 *
 * Note: The original method will be executed when callback `shouldDoNothing` returns false
 *
 * @return Unhook object, you can use it to unhook the method
 */
fun <T> Class<T>.hookDoNothingMethod(methodName: String, vararg parameterTypes: Class<*>, shouldDoNothing: MethodHookParam.() -> Boolean): MethodHook.Unhook? {
    return hookMethodBefore(methodName, *parameterTypes) {
        if (kotlin.runCatching { shouldDoNothing() }.onFailure { Hooks.log(it) }.getOrNull() == true) {
            result = null
        }
    }
}

/**
 * @return MethodHook object, you can use it to hook some method
 */
fun beforeHook(
    callback: MethodHookParam.() -> Unit
): MethodHook {
    return object: MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            kotlin.runCatching {
                param.callback()
            }.onFailure {
                Hooks.log(it)
            }
        }
    }
}

/**
 * @return MethodHook object, you can use it to hook some method
 */
fun afterHook(
    callback: MethodHookParam.() -> Unit
): MethodHook {
    return object: MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            kotlin.runCatching {
                param.callback()
            }.onFailure {
                Hooks.log(it)
            }
        }
    }
}

/**
 * Calls a method on the given object instance.
 *
 * @param methodName The name of the method to call.
 * @param args The arguments to pass to the method.
 * @return The result of the method call.
 */
fun <T> T.callMethod(methodName: String, vararg args: Any?): Any? {
    return XposedHelpers.callMethod(this, methodName, *args)
}

/**
 * Calls a static method on the given class.
 *
 * @param methodName The name of the method to call.
 * @param args The arguments to pass to the method.
 * @return The result of the method call.
 */
fun <T> Class<T>.callStaticMethod(methodName: String, vararg args: Any?): Any? {
    return XposedHelpers.callStaticMethod(this, methodName, *args)
}

/**
 * Finds a class by its name if it exists.
 *
 * @receiver The name of the class to find.
 * @param classLoader The class loader to use for finding the class.
 * @return The class if it exists, or `null` if it does not.
 */
fun String.toClass(classLoader: ClassLoader?): Class<*>? {
    return XposedHelpers.findClassIfExists(this, classLoader)
}

/**
 * Finds a class by its name.
 *
 * @receiver The name of the class to find.
 * @param classLoader The class loader to use for finding the class.
 * @return The class.
 * @throws ClassNotFoundError If the class does not exist.
 */
fun String.toClassOrThrow(classLoader: ClassLoader?): Class<*> {
    return XposedHelpers.findClass(this, classLoader)
}

/**
 * Hooks a method with optional before and after callbacks.
 *
 * @param hookOnce If true, the method will only be hooked once. ('unhook' immediately after triggering a full callback)
 * @param soleHook If true, the method will only allow one hooker to add into Xposed.
 * @param after Callback to be executed after the method is called.
 * @param before Callback to be executed before the method is called.
 *          If the callback returns true and `hookOnce` is true, the method will be unhooked.
 * @return Unhook object, you can use it to unhook the method.
 */
fun Method.diyHook(
    hookOnce: Boolean = false,
    soleHook: Boolean = false,
    before: MethodHookParam.() -> Boolean = { false },
    after: MethodHookParam.() -> Unit = {},
): MethodHook.Unhook? {
    var unhook: MethodHook.Unhook? = null
    val unhookCallback = {
        if (soleHook) {
            hookedMethods.remove(generateOnceHookMethodKey(declaringClass.name, name, parameterTypes))
        }
    }
    val baseHooker = {
        unhook = Hooks.hookMethod(this, object: MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                kotlin.runCatching {
                    if (before(param)) {
                        unhook?.unhook()
                        unhookCallback()
                    }
                }.onFailure {
                    Hooks.log(it)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                kotlin.runCatching {
                    after(param)
                    if (hookOnce) {
                        unhook?.unhook()
                        unhookCallback()
                    }
                }.onFailure {
                    Hooks.log(it)
                }
            }
        })
    }
    if (soleHook) {
        ifNotHook(declaringClass.name, name, parameterTypes) { key ->
            hookOnceLock.withLock {
                // 锁内二次检查：避免并发重复 hook
                if (hookedMethods.contains(key)) {
                    return@withLock
                }
                hookedMethods.add(key)
                try {
                    baseHooker()
                } catch (t: Throwable) {
                    hookedMethods.remove(key)
                    throw t
                }
            }
        }
    } else baseHooker()
    return unhook
}