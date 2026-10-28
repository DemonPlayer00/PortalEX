@file:Suppress("UNCHECKED_CAST", "PrivateApi")
package moe.fuqiuluo.xposed.hooks.wlan

import android.net.wifi.WifiInfo
import android.os.Build
import android.util.ArrayMap
import dalvik.system.PathClassLoader
import moe.fuqiuluo.xposed.utils.XposedHelpers
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.FeatureGate
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.hooks.NetworkTypeSpoof
import moe.fuqiuluo.xposed.utils.NetworkSimConfig
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookAllMethods
import moe.fuqiuluo.xposed.utils.toClass

object WlanHook {
    operator fun invoke(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cSystemServerClassLoaderFactory = XposedHelpers.findClassIfExists("com.android.internal.os.SystemServerClassLoaderFactory", classLoader)
            if (cSystemServerClassLoaderFactory == null) {
                if (FakeLoc.enableDebugLog) Logger.debug("本机没有 SystemServerClassLoaderFactory，跳过 WiFi 层")
                return
            }
            val sLoadedPaths = XposedHelpers.getStaticObjectField(cSystemServerClassLoaderFactory, "sLoadedPaths") as ArrayMap<String, PathClassLoader>
            val wifiClassLoader = sLoadedPaths.firstNotNullOfOrNull {
                if (it.key.contains("service-wifi.jar")) it.value else null
            }
            if (wifiClassLoader == null) {
                if (FakeLoc.enableDebugLog) Logger.debug("没找到 service-wifi.jar 的 classloader，跳过 WiFi 层")
                return
            }
            val wifiClazz = "com.android.server.wifi.WifiServiceImpl".toClass(wifiClassLoader)
            if (wifiClazz == null) {
                if (FakeLoc.enableDebugLog) Logger.debug("本机没有 WifiServiceImpl，跳过")
                return
            }
            hookWifiServiceImpl(wifiClazz)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cSystemServiceManager = XposedHelpers.findClassIfExists("com.android.server.SystemServiceManager", classLoader)
            if (cSystemServiceManager == null) {
                if (FakeLoc.enableDebugLog) Logger.debug("本机没有 SystemServiceManager，跳过")
                return
            }
            cSystemServiceManager.hookAllMethods("loadClassFromLoader", afterHook {
                if (args[0] == "com.android.server.wifi.WifiService") {
                    kotlin.runCatching {
                        val classloader = args[1] as PathClassLoader
                        val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")
                        hookWifiServiceImpl(wifiClazz)
                    }.onFailure {
                        Logger.error("Failed to hook WifiService", it)
                    }
                }
            })
        }
    }

    /**
     * 按配置的 Wi-Fi 条目改写**真实扫描到的** ScanResult（SSID/BSSID）。
     *
     * 为什么不自己构造 ScanResult：它的构造器是隐藏 API，字段随版本变化（13+ 的 SSID 变成
     * WifiSsid 对象）；改写现成对象的字段既稳定，又保留本机真实的信号强度/频段。
     *
     * @return true = 至少改写了一条（调用方据此决定是否清空列表）
     */
    private fun applyWifiEntries(items: List<Any>): Boolean {
        val entries = NetworkSimConfig.wifiList
        if (entries.isEmpty() || items.isEmpty()) return false
        var applied = 0
        items.forEachIndexed { i, item ->
            val parts = entries[i % entries.size].split("|", limit = 2)
            val ssid = parts.getOrNull(0).orEmpty()
            val bssid = parts.getOrNull(1).orEmpty()
            runCatching {
                val wifiSsidField = runCatching { item.javaClass.getField("wifiSsid") }.getOrNull()
                if (wifiSsidField != null) {
                    val cls = Class.forName("android.net.wifi.WifiSsid")
                    val value = cls.getMethod("fromString", String::class.java).invoke(null, ssid)
                    wifiSsidField.set(item, value)
                } else {
                    item.javaClass.getField("SSID").set(item, "\"" + ssid + "\"")
                }
                if (bssid.isNotBlank()) item.javaClass.getField("BSSID").set(item, bssid)
                applied++
            }
        }
        return applied > 0
    }

    private fun hookWifiServiceImpl(wifiClazz: Class<*>) {
        if (!FakeLoc.hookWifi) return

        wifiClazz.hookAllMethods("getConnectionInfo", beforeHook {
            val packageName = args[0] as String
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getConnectionInfo with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            val targetSpoof = packageName == NetworkTypeSpoof.TARGET
            if (targetSpoof) Logger.info("WlanHook: target getConnectionInfo")
            if (FeatureGate.isWifiHookActive() &&
                (FakeLoc.enableMockWifi || targetSpoof) &&
                !BinderUtils.isSystemPackages(packageName)
            ) {
                val wifiInfo = WifiInfo::class.java.getConstructor().newInstance()
                XposedHelpers.callMethod(wifiInfo, "setMacAddress", "02:00:00:00:00:00")
                // 设定为空（列表与单值都没填）⇒ 返回空，而不是把系统真实连接原样透传
                val (cfgSsid, cfgBssid) = NetworkSimConfig.firstWifi()
                val blank = cfgSsid.isBlank() && cfgBssid.isBlank()
                XposedHelpers.callMethod(wifiInfo, "setBSSID", if (blank) "" else cfgBssid)
                // Android 15 的 setSSID 参数类型已改为 WifiSsid；失败不影响其它字段。
                runCatching {
                    val wifiSsid = Class.forName("android.net.wifi.WifiSsid")
                    val value = wifiSsid.getMethod("fromString", String::class.java)
                        .invoke(null, if (blank) "" else cfgSsid)
                    XposedHelpers.callMethod(wifiInfo, "setSSID", value)
                }
                XposedHelpers.callMethod(wifiInfo, "setNetworkId", 1)
                XposedHelpers.callMethod(wifiInfo, "setRssi", NetworkSimConfig.wifiRssi)
                XposedHelpers.callMethod(wifiInfo, "setLinkSpeed", 0)
                XposedHelpers.callMethod(wifiInfo, "setFrequency", 0)
                result = wifiInfo
            }
        })

        wifiClazz.hookAllMethods("getScanResults", afterHook {
            val packageName = args[0] as? String
            if (packageName.isNullOrEmpty()) {
                return@afterHook
            }

            if (FakeLoc.enableDebugLog)
                Logger.debug("In getScanResults with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (FeatureGate.isWifiHookActive() &&
                (FakeLoc.enableMockWifi || packageName == NetworkTypeSpoof.TARGET)
            ) {
                if(result == null) {
                    return@afterHook 
                }
                
                if (result is List<*>) {
                    // 配了 Wi-Fi 条目就改写真实扫描列表（数量与信号沿用本机扫描），否则按旧行为清空
                    val items = (result as List<*>).filterNotNull()
                    if (!applyWifiEntries(items)) result = arrayListOf<Any>()
                    return@afterHook
                } // 针对小米系列机型的wifi扫描返回

                if (result is Array<*>) {
                    val items = (result as Array<*>).filterNotNull()
                    if (!applyWifiEntries(items)) result = arrayOf<Any>()
                    return@afterHook
                } // 针对一加系列机型的wifi扫描返回

                // 在高于安卓10的版本，Google 引入了 APEX（Android Pony EXpress）文件格式来封装系统组件，包括系统服务~！
                // 上面的代码在高版本将无效导致应用可以通过网络AGPS到正常的位置（现象就是位置拉回）
                // 这里针对一个普通的版本进行一个修复
                val resultClass = result.javaClass
                if (resultClass.name.contains("ParceledListSlice")) runCatching {
                    // Android 10+：列表被包成 ParceledListSlice，就地改写其中的 ScanResult
                    runCatching {
                        val list = resultClass.getMethod("getList").invoke(result) as? List<*>
                        if (list != null && applyWifiEntries(list.filterNotNull())) return@afterHook
                    }
                    val constructor = resultClass.getConstructor(List::class.java)
                    if (!constructor.isAccessible) {
                        constructor.isAccessible = true
                    }
                    result = constructor.newInstance(emptyList<Any>())
                    return@afterHook
                }.onFailure {
                    Logger.error("getScanResults: ParceledListSlice failed", it)
                }

                if (FakeLoc.enableDebugLog) {
                    Logger.error("getScanResults: Unknown return type: ${result?.javaClass?.name}")
                }
            }
        })
    }
}
