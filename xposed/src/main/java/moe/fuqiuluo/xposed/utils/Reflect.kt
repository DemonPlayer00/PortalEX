package moe.fuqiuluo.xposed.utils

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 反射工具：**取代旧 Xposed API 的 `XposedHelpers`**（迁移第一步 S1）。
 *
 * ## 为什么是"在自建包里造一个同名对象"，而不是把 123 处调用点改写一遍
 *
 * 全模块用到旧 `XposedHelpers` 的地方约 **123 处**、12 种方法。逐个改写成
 * `java.lang.reflect` 的裸调用，等于把"参数匹配/继承链查找/装箱"这些**已经有既定语义**的
 * 细节在 123 个地方各重写一遍 —— 那是纯粹的放大器式改动。
 *
 * 所以这一步只做一件事：**在本包里用同样的名字、同样的签名，重新实现我们真正用到的那一小撮**。
 * 调用点只改一行 `import`（旧包 → 本包），行为与它们当初被写下时的语义保持一致。
 *
 * ## 语义刻意与旧实现对齐的地方（写清楚，免得以后当成 bug 改掉）
 *
 *  · `findClass` / `findMethodExact` / `findMethodBestMatch` / `getXxxField` 失败时**抛**
 *    （`ClassNotFoundException` / `NoSuchMethodError` / `NoSuchFieldError`）；
 *    带 `IfExists` 的版本返回 `null`。本模块的调用点一律 `catch (t: Throwable)`，
 *    所以抛什么不是关键，**"该抛的抛、该 null 的 null"** 才是关键；
 *  · 查找沿**继承链**向上（先本类，再父类），方法还会看接口；
 *  · `callMethod` 拆掉 `InvocationTargetException` 外壳，把**被调者真正的异常**抛出来 ——
 *    旧实现如此，很多调用点是靠这个语义才 catch 得到真正的错误；
 *  · `findMethodBestMatch` 按"参数个数 + 可赋值（含基本类型装箱）"挑方法，不按名字唯一性。
 */
object XposedHelpers {

    // ---------------------------------------------------------------- 类

    /** 找不到就抛（与旧实现一致：调用方通常不 catch，靠它把"这个 ROM 没有这个类"暴露出来） */
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> =
        Class.forName(className, false, classLoader)

    /** 找不到返回 null —— 绝大多数"这个 ROM 可能没有"的判断都走这个 */
    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? =
        runCatching { Class.forName(className, false, classLoader) }.getOrNull()

    // ---------------------------------------------------------------- 方法

    /**
     * 按**精确签名**取方法。`parameterTypes` 里可以混 `Class` 与 `String`（旧实现两种都收）——
     * 调用点大量使用 `"android.location.Location"` 这种写法，所以必须支持字符串。
     */
    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Any?): Method {
        val types = parameterTypes.map { toClass(it) }.toTypedArray()
        var c: Class<*>? = clazz
        while (c != null) {
            runCatching { return c.getDeclaredMethod(methodName, *types).apply { isAccessible = true } }
            c = c.superclass
        }
        throw NoSuchMethodError("${clazz.name}.$methodName(${types.joinToString { it.simpleName }})")
    }

    fun findMethodExactIfExists(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Any?
    ): Method? = runCatching { findMethodExact(clazz, methodName, *parameterTypes) }.getOrNull()

    /**
     * 按**实参**挑方法：参数个数相同、且每个形参都能接受对应实参（基本类型按装箱比）。
     *
     * 为什么不图省事只按名字+个数挑：`onLocationChanged(Location)` 与
     * `onLocationChanged(List<Location>)` 在某些 ROM 上同时存在，挑错了就是"把 List 塞进 Location"
     * 的 `IllegalArgumentException`。
     */
    fun findMethodBestMatch(clazz: Class<*>, methodName: String, vararg args: Any?): Method {
        val candidates = collectMethods(clazz).filter { it.name == methodName }
        // 先按个数筛，再按可赋值挑；都不中则退回"个数相同"的第一个（旧实现也是这个兜底）
        val byCount = candidates.filter { it.parameterTypes.size == args.size }
        byCount.firstOrNull { m ->
            m.parameterTypes.withIndex().all { (i, p) -> accepts(p, args[i]) }
        }?.let {
            it.isAccessible = true
            return it
        }
        byCount.firstOrNull()?.let {
            it.isAccessible = true
            return it
        }
        throw NoSuchMethodError("${clazz.name}.$methodName/${args.size} args")
    }

    private fun collectMethods(clazz: Class<*>): List<Method> {
        val out = ArrayList<Method>(32)
        val seen = HashSet<String>(32)
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredMethods.forEach { m ->
                // 同名同参只留最先遇到的那份（子类优先）——父类重写不该覆盖子类
                val key = m.name + "/" + m.parameterTypes.joinToString(",") { it.name }
                if (seen.add(key)) out.add(m)
            }
            c.interfaces.forEach { i ->
                runCatching {
                    i.declaredMethods.forEach { m ->
                        val key = m.name + "/" + m.parameterTypes.joinToString(",") { it.name }
                        if (seen.add(key)) out.add(m)
                    }
                }
            }
            c = c.superclass
        }
        return out
    }

    /** 形参 `p` 能不能接受实参 `arg`（null 只对非基本类型成立；基本类型按装箱比） */
    private fun accepts(p: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !p.isPrimitive
        val a = arg.javaClass
        if (p.isAssignableFrom(a)) return true
        val boxed = primitiveToBoxed(p) ?: return false
        return boxed.isAssignableFrom(a)
    }

    private fun primitiveToBoxed(p: Class<*>): Class<*>? = when (p) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> null
    }

    // ---------------------------------------------------------------- 调用

    /** 调实例方法；**把 `InvocationTargetException` 拆掉**，抛出被调者真正的异常（旧实现如此） */
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        val target = requireNotNull(obj) { "callMethod：实例为 null（$methodName）" }
        return invoke(findMethodBestMatch(target.javaClass, methodName, *args), target, args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? =
        invoke(findMethodBestMatch(clazz, methodName, *args), null, args)

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val ctor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.size == args.size }
            ?: throw NoSuchMethodError("${clazz.name}/<init>/${args.size} args")
        ctor.isAccessible = true
        return try {
            ctor.newInstance(*args)
        } catch (t: java.lang.reflect.InvocationTargetException) {
            throw t.cause ?: t
        }
    }

    private fun invoke(m: Method, obj: Any?, args: Array<out Any?>): Any? = try {
        m.invoke(obj, *args)
    } catch (t: java.lang.reflect.InvocationTargetException) {
        throw t.cause ?: t
    }

    // ---------------------------------------------------------------- 字段

    /** 沿继承链找字段（找不到返回 null） */
    fun findFieldIfExists(clazz: Class<*>, fieldName: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            runCatching { return c.getDeclaredField(fieldName).apply { isAccessible = true } }
            c = c.superclass
        }
        return null
    }

    fun findField(clazz: Class<*>, fieldName: String): Field =
        findFieldIfExists(clazz, fieldName) ?: throw NoSuchFieldError("${clazz.name}.$fieldName")

    fun getObjectField(obj: Any?, fieldName: String): Any? {
        val target = requireNotNull(obj) { "getObjectField：实例为 null（$fieldName）" }
        return findField(target.javaClass, fieldName).get(target)
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        val target = requireNotNull(obj) { "setObjectField：实例为 null（$fieldName）" }
        findField(target.javaClass, fieldName).set(target, value)
    }

    fun getIntField(obj: Any?, fieldName: String): Int {
        val target = requireNotNull(obj) { "getIntField：实例为 null（$fieldName）" }
        return findField(target.javaClass, fieldName).getInt(target)
    }

    fun setIntField(obj: Any?, fieldName: String, value: Int) {
        val target = requireNotNull(obj) { "setIntField：实例为 null（$fieldName）" }
        findField(target.javaClass, fieldName).setInt(target, value)
    }

    fun getLongField(obj: Any?, fieldName: String): Long {
        val target = requireNotNull(obj) { "getLongField：实例为 null（$fieldName）" }
        return findField(target.javaClass, fieldName).getLong(target)
    }

    fun getStaticIntField(clazz: Class<*>, fieldName: String): Int =
        findField(clazz, fieldName).getInt(null)

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? =
        findField(clazz, fieldName).get(null)

    /** 诊断用：把"这个类里有什么"打出来（换 ROM 时的第一手资料） */
    fun describe(clazz: Class<*>): String = buildString {
        append(clazz.name).append(" methods=[")
        append(collectMethods(clazz).joinToString(",") { m ->
            val static = if (Modifier.isStatic(m.modifiers)) "static " else ""
            static + m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
        })
        append("]")
    }

    // ---------------------------------------------------------------- 挂钩（过渡）

    /*
     * 这两个是**挂钩**而不是反射。旧 API 把它们放在 `XposedHelpers` 里，所以调用点写成
     * `XposedHelpers.findAndHookMethod(...)`；为了不打断这些调用点，这里给出同名入口，
     * 内部转调本模块自己的 hook helper（`utils/Xposed.kt`）。
     *
     * ⚠️ 它们是**过渡件**：S3 会把整套 helper 换成 libxposed 的 `MethodHook`/`Chain`，
     * 那时这里（连同 `MethodHook` 的依赖）一起消失。
     */
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): Any? {
        val args = parameterTypesAndCallback.toList()
        val callback = args.lastOrNull()
        require(callback is MethodHook) { "findAndHookMethod：最后一个参数必须是回调" }
        val types = args.dropLast(1)
        return findMethodExact(clazz, methodName, *types.toTypedArray()).hook(callback)
    }

    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): Any? = findAndHookMethod(findClass(className, classLoader), methodName, *parameterTypesAndCallback)

    fun findAndHookConstructor(clazz: Class<*>, vararg parameterTypesAndCallback: Any?): Any? {
        val args = parameterTypesAndCallback.toList()
        val callback = args.lastOrNull()
        require(callback is MethodHook) { "findAndHookConstructor：最后一个参数必须是回调" }
        val types = args.dropLast(1).map { toClass(it) }.toTypedArray()
        val ctor = clazz.getDeclaredConstructor(*types).apply { isAccessible = true }
        // 本模块的 helper 只给了 `Method.hook`；构造函数要走旧 API 的 `Member` 版本
        // （同样是过渡件，S3 一起换掉）。
        return Hooks.hookMethod(ctor, callback)
    }

    private fun toClass(type: Any?): Class<*> = when (type) {
        is Class<*> -> type
        is String -> Class.forName(type, false, XposedHelpers::class.java.classLoader)
        else -> throw IllegalArgumentException("不支持的参数类型描述：$type")
    }
}
