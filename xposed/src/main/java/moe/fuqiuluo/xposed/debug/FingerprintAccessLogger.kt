package moe.fuqiuluo.xposed.debug

import android.content.Context
import android.os.Binder
import android.os.Process
import moe.fuqiuluo.xposed.hooks.camera.CameraMockServerContext
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.hookAllMethodsBefore
import moe.fuqiuluo.xposed.utils.toClass
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 临时指纹读取审计器。
 *
 * **不注入目标应用**：只挂在 system_server / com.android.phone 的系统服务实现上，
 * 通过 Binder.getCallingUid() 记录“哪个应用调用了一次系统 API”。
 *
 * 局限：Build.*、Settings.Secure.get*、SensorManager 等纯客户端 API 不会经过
 * system_server，无法在这一层看到；只能覆盖经过系统服务的读取。
 */
internal object FingerprintAccessLogger {
    private const val LOG_PATH = "/data/system/portalex/fingerprint_access.log"
    private const val MAX_LOG = 4L * 1024 * 1024
    private val inLog = ThreadLocal.withInitial { false }
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lastLogAt = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 256
    }
    @Volatile private var installed = false

    fun install(classLoader: ClassLoader?, systemClassLoader: ClassLoader?) {
        if (installed) return
        installed = true
        val loaders = listOf(classLoader, systemClassLoader).filterNotNull().distinct()
        try {
            hookPackageManager(loaders)
            hookSettings(loaders)
            hookSystemServices(loaders)
        } catch (t: Throwable) {
            Logger.warn("指纹审计器安装失败 ${t.message}")
        }
    }

    /** com.android.phone 进程入口。 */
    fun installPhone(classLoader: ClassLoader?) {
        val loaders = listOfNotNull(classLoader, ClassLoader.getSystemClassLoader()).distinct()
        hookClass(loaders, "com.android.phone.PhoneSubInfoController",
            listOf("getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber"))
        hookClass(loaders, "com.android.phone.PhoneInterfaceManager",
            listOf("getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber"))
    }

    private fun hookPackageManager(loaders: List<ClassLoader>) {
        for (className in listOf(
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.PackageManagerService${'$'}IPackageManagerImpl"
        )) {
            hookClass(loaders, className,
                listOf(
                    "getInstalledPackages", "getInstalledApplications",
                    "getPackageInfo", "getPackagesForUid",
                    "queryIntentActivities", "queryIntentServices",
                    "resolveIntent", "checkPermission"
                ))
        }
    }

    private fun hookSettings(loaders: List<ClassLoader>) {
        hookClass(loaders, "com.android.providers.settings.SettingsProvider",
            listOf("query", "call"))
        // Settings.Secure/String 走 ContentProvider.Transport；仅拦截 settings URI，
        // 否则会把所有 ContentProvider 查询都写进指纹日志。
        val transport = findClass(loaders, "android.content.ContentProvider${'$'}Transport")
            ?: return
        listOf("query", "call").forEach { method ->
            runCatching {
                transport.hookAllMethodsBefore(method) {
                    val uri = args?.firstOrNull()?.toString().orEmpty()
                    if (uri.contains("settings") || uri.contains("android_id")) {
                        record("ContentProvider.Transport.$method", args)
                    }
                }
            }.onFailure { Logger.warn("指纹审计 hook Transport.$method 失败 ${it.message}") }
        }
    }

    private fun findClass(loaders: List<ClassLoader>, name: String): Class<*>? {
        return name.toClass(loaders.firstOrNull()) ?: loaders.asSequence()
            .mapNotNull { name.toClass(it) }.firstOrNull()
    }

    private fun hookSystemServices(loaders: List<ClassLoader>) {
        hookClass(loaders, "com.android.server.location.LocationManagerService",
            listOf("getLastLocation", "getCurrentLocation", "requestLocationUpdates"))
        hookClass(loaders, "com.android.server.wifi.WifiServiceImpl",
            listOf("getConnectionInfo", "getScanResults"))
        hookClass(loaders, "com.android.server.am.ActivityManagerService",
            listOf("getRunningAppProcesses", "getRunningTasks", "getRecentTasks"))
        hookClass(loaders, "com.android.server.accounts.AccountManagerService",
            listOf("getAccounts", "getAccountsAsUser"))
        hookClass(loaders, "com.android.server.pm.UserManagerService",
            listOf("getUserInfo", "getUsers", "isUserUnlocked"))
    }

    private fun hookClass(loaders: List<ClassLoader>, className: String, methods: List<String>) {
        val clazz = findClass(loaders, className) ?: run {
            Logger.warn("指纹审计：找不到 $className")
            return
        }
        Logger.warn("指纹审计：已找到 $className，声明方法=${clazz.declaredMethods.size}")
        methods.forEach { method ->
            runCatching {
                val count = clazz.hookAllMethodsBefore(method) {
                    record("$className.$method", args)
                }.size
                Logger.warn("指纹审计：hook $className.$method -> $count")
            }.onFailure { Logger.warn("指纹审计 hook $className.$method 失败 ${it.message}") }
        }
    }

    private fun record(api: String, args: Array<Any?>?) {
        if (inLog.get() == true) return
        val uid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        if (uid < Process.FIRST_APPLICATION_UID) return
        inLog.set(true)
        try {
            val pkg = runCatching { callerPackage(uid) }.getOrNull() ?: "?"
            val detail = args?.joinToString(",") { value ->
                when (value) {
                    null -> "null"
                    is String -> value.take(120)
                    is Array<*> -> "array(${value.size})"
                    else -> value.toString().take(80)
                }
            }.orEmpty()
            val key = "$uid|$api"
            val now = System.currentTimeMillis()
            val duplicate = synchronized(lastLogAt) {
                val last = lastLogAt[key] ?: 0L
                if (now - last < 250L) true else { lastLogAt[key] = now; false }
            }
            if (!duplicate) {
                append("${dateFormat.format(Date())} uid=$uid pkg=$pkg api=$api args=$detail")
            }
        } finally {
            inLog.set(false)
        }
    }

    private fun callerPackage(uid: Int): String? {
        val ctx: Context? = CameraMockServerContext.get() ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
        return runCatching { ctx?.packageManager?.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
    }

    @Synchronized
    private fun append(line: String) {
        runCatching {
            val file = File(LOG_PATH)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_LOG) file.renameTo(File("$LOG_PATH.old"))
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray()) }
        }.onFailure { Logger.warn("指纹审计写入失败 ${it.message}") }
    }
}
