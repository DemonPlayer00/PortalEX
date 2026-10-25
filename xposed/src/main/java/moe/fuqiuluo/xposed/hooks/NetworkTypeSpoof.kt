package moe.fuqiuluo.xposed.hooks

import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.Hooks
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.XposedHelpers
import moe.fuqiuluo.xposed.utils.afterHook

/**
 * 只针对体适能：把 ConnectivityService 返回的当前网络能力改成“移动数据”。
 *
 * 不修改 WifiManager/Telephony 的全局状态；仅按 Binder 调用 UID 修改返回值。
 * NetworkCapabilities 的 transport 是应用判断 WiFi/移动数据最常用、也最可靠的入口。
 */
object NetworkTypeSpoof {
    const val TARGET = "com.bxkj.student"
    @Volatile private var installed = false
    @Volatile private var enabled = true

    fun install(classLoader: ClassLoader) {
        if (installed) return
        fun resolve(): Class<*>? {
            XposedHelpers.findClassIfExists("android.net.connectivity.com.android.server.ConnectivityService", classLoader)
                ?.let { return it }
            val factory = XposedHelpers.findClassIfExists(
                "com.android.internal.os.SystemServerClassLoaderFactory", classLoader
            ) ?: return null
            val paths = runCatching {
                XposedHelpers.getStaticObjectField(factory, "sLoadedPaths") as Map<*, *>
            }.getOrNull() ?: return null
            for ((path, loader) in paths) {
                val p = path?.toString() ?: continue
                if (!p.contains("Connectivity") && !p.contains("tethering") &&
                    !p.contains("services")) continue
                val l = loader as? ClassLoader ?: continue
                Logger.info("NetworkTypeSpoof: try loader path=$p")
                XposedHelpers.findClassIfExists(
                    "android.net.connectivity.com.android.server.ConnectivityService", l
                )?.let { return it }
            }
            return null
        }
        val cls = resolve() ?: run {
            Logger.warn("NetworkTypeSpoof: ConnectivityService 不存在")
            return
        }
        val count = Hooks.hookAllMethods(cls, "getNetworkCapabilities", afterHook {
            if (!enabled) return@afterHook
            val uid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
            val pkg = BinderUtils.getUidPackageNames(uid = uid)?.firstOrNull()
            Logger.info("NetworkTypeSpoof: getNetworkCapabilities uid=$uid pkg=${pkg ?: "?"}")
            if (pkg != TARGET) return@afterHook
            val original = result as? NetworkCapabilities ?: return@afterHook
            val copy = runCatching {
                NetworkCapabilities::class.java
                    .getConstructor(NetworkCapabilities::class.java)
                    .newInstance(original)
            }.getOrNull() ?: return@afterHook
            runCatching {
                copy.javaClass.getMethod("removeTransportType", Int::class.javaPrimitiveType)
                    .invoke(copy, NetworkCapabilities.TRANSPORT_WIFI)
            }
            runCatching {
                copy.javaClass.getMethod("addTransportType", Int::class.javaPrimitiveType)
                    .invoke(copy, NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            runCatching {
                copy.javaClass.getMethod("addCapability", Int::class.javaPrimitiveType)
                    .invoke(copy, NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            result = copy
            Logger.info("NetworkTypeSpoof: $pkg -> CELLULAR (was WIFI=${original.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)})")
        }).size
        val netInfoHooks = Hooks.hookAllMethods(cls, "getActiveNetworkInfo", afterHook {
            val uid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
            val pkg = BinderUtils.getUidPackageNames(uid = uid)?.firstOrNull()
            Logger.info("NetworkTypeSpoof: getActiveNetworkInfo uid=$uid pkg=${pkg ?: "?"}")
            if (pkg != TARGET) return@afterHook
            val info = NetworkInfo::class.java
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, String::class.java)
                .newInstance(0, 0, "MOBILE", "")
            runCatching { info.javaClass.getMethod("setDetailedState", NetworkInfo.DetailedState::class.java, String::class.java).invoke(info, NetworkInfo.DetailedState.CONNECTED, null) }
            runCatching { info.javaClass.getMethod("setIsAvailable", Boolean::class.javaPrimitiveType).invoke(info, true) }
            result = info
        }).size
        Logger.info("NetworkTypeSpoof: installed hooks=$count netInfo=$netInfoHooks")
        installed = count > 0 || netInfoHooks > 0
    }
}
