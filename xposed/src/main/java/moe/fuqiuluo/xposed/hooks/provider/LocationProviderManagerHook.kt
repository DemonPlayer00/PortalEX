package moe.fuqiuluo.xposed.hooks.provider

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.CellIdentity
import android.telephony.CellInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.hooks.BasicLocationHook.injectLocation
import moe.fuqiuluo.xposed.hooks.blindhook.BlindHookLocation
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.diyHook
import moe.fuqiuluo.xposed.utils.hook
import moe.fuqiuluo.xposed.utils.onceHook
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import moe.fuqiuluo.xposed.utils.onceHookMethodBefore
import java.util.Collections
import kotlin.random.Random

object LocationProviderManagerHook {
    /** 逐条注入（保留批次长度与顺序）：返回可直接写回 mLocations 的列表 */
    private fun injectAll(locations: List<*>): ArrayList<Location> {
        val injected = ArrayList<Location>(maxOf(1, locations.size))
        locations.forEach { item -> (item as? Location)?.let { injected.add(injectLocation(it)) } }
        if (injected.isEmpty()) {
            injected.add(injectLocation(Location(LocationManager.GPS_PROVIDER)))
        }
        return injected
    }

    private val hookOnFetchLocationResult = beforeHook {
        if (args.isEmpty()) return@beforeHook
        if (!FakeLoc.enable) return@beforeHook

        if (FakeLoc.enableDebugLog) {
            Logger.debug("${method}: injected!")
        }

        val locationResult = args[0]
        val mLocationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
        if (mLocationsField == null) {
            Logger.error("Failed to find mLocations in LocationResult")
            return@beforeHook
        }
        mLocationsField.isAccessible = true
        val mLocations = mLocationsField.get(locationResult) as ArrayList<*>

        // 统一走主注入路径（BaseLocationHook.injectLocation）：
        // 一个注入实现、一套字段口径——否则同一 tick 里「一次性取位」与
        // 「持续注册」拿到的帧会在 bearing（平滑/原始）与 accuracy（配置值/原始帧）上分岔。
        // **逐条注入并保留批次长度**：旧实现替换为单元素，批量位置除首帧外全部丢失。
        mLocationsField.set(locationResult, injectAll(mLocations))
    }

    operator fun invoke(classLoader: ClassLoader) {
        hookLocationProviderManager(classLoader)
        hookDelegateLocationProvider(classLoader)
        hookPassiveLocationProvider(classLoader)
        hookAbstractLocationProvider(classLoader)
        hookOtherProvider(classLoader)
        hookGeofenceProvider(classLoader)
    }

    private fun hookAbstractLocationProvider(classLoader: ClassLoader) {
        run {
            val cAbstractLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.AbstractLocationProvider", classLoader)
                ?: return@run
            val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                ?: return@run
            val mReportLocation = XposedHelpers.findMethodExactIfExists(cAbstractLocationProvider.javaClass, "reportLocation", cLocationResult)
                ?: return@run

            mReportLocation.onceHook(hookOnFetchLocationResult)
        }

        run {
            val cInternalState = XposedHelpers.findClassIfExists("com.android.server.location.provider.AbstractLocationProvider\$InternalState", classLoader)
                ?: return@run

            XposedBridge.hookAllConstructors(cInternalState, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] ?: return

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("AbstractLocationProvider.InternalState: injected!")
                    }

                    // will hook class AbstractLocationProvider.Listener, Be careful not to repeat the hooker!
                    listener.javaClass.onceHookAllMethod("onReportLocation", hookOnFetchLocationResult)
                }
            })
        }

    }

    private fun hookPassiveLocationProvider(classLoader: ClassLoader) {
        val cPassiveLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.PassiveLocationProvider", classLoader)
            ?: return
        val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
            ?: return
        val updateLocation = XposedHelpers.findMethodExactIfExists(cPassiveLocationProvider, "updateLocation", cLocationResult)
            ?: return

        updateLocation.hook(hookOnFetchLocationResult)
    }

    private fun hookDelegateLocationProvider(classLoader: ClassLoader) {
        val cDelegateLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.DelegateLocationProvider", classLoader)
            ?: return

        val waitForInitialization = XposedHelpers.findMethodExactIfExists(cDelegateLocationProvider, "waitForInitialization") ?: return
        waitForInitialization.diyHook(
            hookOnce = true,
            before = {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("DelegateLocationProvider.waitForInitialization: injected!")
                }

                val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                    ?: return@diyHook true
                XposedHelpers.findMethodExactIfExists(thisObject.javaClass, "onReportLocation", cLocationResult)?.onceHook(hookOnFetchLocationResult)
                XposedHelpers.findMethodExactIfExists(thisObject.javaClass, "reportLocation", cLocationResult)?.onceHook(hookOnFetchLocationResult)

                return@diyHook true
            }
        )
    }

    private fun hookLocationProviderManager(classLoader: ClassLoader) {
        val cLocationProviderManager = XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager", classLoader)
            ?: return
        BlindHookLocation(cLocationProviderManager, classLoader)

        XposedBridge.hookAllMethods(cLocationProviderManager, "setRealProvider", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val locationProvider = param.args[0]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("setRealProvider: $locationProvider")
                }
            }
        })
        XposedBridge.hookAllMethods(cLocationProviderManager, "setMockProvider", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val locationProvider = param.args[0]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("setMockProvider: $locationProvider")
                }
            }
        })
        XposedBridge.hookAllMethods(cLocationProviderManager, "sendExtraCommand", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if(param.args.size < 4) return
                val command = param.args[2]

                if (command == "force_xtra_injection" || command == "CMD_SHOW_GPS_TIPS_CONFIG") {
                    param.result = null
                    return
                }

                val extras = param.args[3]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("sendExtraCommand: $command, $extras")
                }
            }
        })

        run {
            val hookedListeners = Collections.synchronizedSet(HashSet<String>())
            if(cLocationProviderManager.onceHookAllMethod("getCurrentLocation", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 回调不写死索引：新签名 (provider, request, ILocationCallback, packageName, …)
                    // 的 args[3] 是包名字符串，旧签名 (request, ILocationCallback, packageName) 是 args[2]。
                    val callback = param.args.firstOrNull { arg ->
                        arg != null && arg.javaClass.methods.any { it.name == "onLocation" }
                    } ?: return

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("getCurrentLocation injected: $callback")
                    }

                    // 允许并注入：不再 param.result = null。未开模拟透传真实位置；
                    // 模拟会话中回调上改写为模拟位置（与 LocationServiceHook 同语义；
                    // 该层不重复登记一次性投递表，避免同一回调被投两次）。
                    if (!FakeLoc.enable) return

                    val classCallback = callback.javaClass
                    if (hookedListeners.contains(classCallback.name)) return // Prevent repeated hooking
                    if (XposedBridge.hookAllMethods(classCallback, "onLocation", object: XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam?) {
                            if (param == null || param.args.isEmpty()) return
                            val location = (param.args[0] ?: return) as Location

                            if (FakeLoc.enableDebugLog)
                                Logger.debug("onLocation(LocationProviderManager.getCurrentLocation): injected!")
                            param.args[0] = injectLocation(location)
                        }
                    }).isEmpty()) {
                        Logger.error("hook onLocation(LocationProviderManager.getCurrentLocation) failed")
                    }

                    hookedListeners.add(classCallback.name)
                }
            }).isEmpty()) {
                Logger.error("hook LocationProviderManager.getCurrentLocation failed")
            }
        }

        // 注意：此 hook 通过 findMethodExactIfExists("onReportLocation") 无参查找，
        // AOSP 的 onReportLocation(LocationResult) 是有参私有方法，因此该回调实际不会触发。
        // 历史版本曾在这里把 mRegistrations 替换为空 ArrayMap（清空所有注册），
        // 一旦某 ROM 存在无参重载就会直接破坏位置上报——现已移除该危险替换，
        // 保留只读的日志与注入逻辑作为兜底（若某 ROM 真的触发，也只会注入而不会清空）。
        cLocationProviderManager.onceHookMethodBefore("onReportLocation") {
            if (!FakeLoc.enable) {
                return@onceHookMethodBefore
            }

            val locationResult = args[0] ?: return@onceHookMethodBefore

            val mLocationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
                ?: return@onceHookMethodBefore
            mLocationsField.isAccessible = true
            val mLocations = mLocationsField.get(locationResult) as? ArrayList<*>
                ?: return@onceHookMethodBefore

            // 同 hookOnFetchLocationResult：统一走主注入路径 + 保留批次长度
            mLocationsField.set(locationResult, injectAll(mLocations))

            if (FakeLoc.enableDebugLog) {
                Logger.debug("onReportLocation: injected!")
            }
        }
    }

    private fun hookGeofenceProvider(classLoader: ClassLoader) {
        val cGeofenceManager = XposedHelpers.findClassIfExists("com.android.server.geofence.GeofenceManager", classLoader)
            ?: return
        BlindHookLocation(cGeofenceManager, classLoader)
    }

    private fun hookOtherProvider(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cGnssLocationProvider = XposedHelpers.findClassIfExists("com.android.location.provider.LocationProviderBase", classLoader)
                ?: return@runCatching
            if(BlindHookLocation(cGnssLocationProvider, classLoader) == 0) {
                cGnssLocationProvider.onceHookMethodBefore("reportLocation", Location::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("LocationProviderBase.reportLocation: injected!")
                    }
                    args[0] = injectLocation(args[0] as Location)
                }
            }
            cGnssLocationProvider.onceHookMethodBefore("reportLocations", List::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("LocationProviderBase.reportLocations: injected!")
                }
                args[0] = (args[0] as List<*>).map {
                    injectLocation(it as Location)
                }
            }
        }.onFailure {
            Logger.warn("Failed to hook LocationProviderBase", it)
        }

        kotlin.runCatching {
            val cGnssLocationProvider = XposedHelpers.findClass("com.android.server.location.gnss.GnssLocationProvider", classLoader)
            cGnssLocationProvider.onceHookMethodBefore("onReportLocation", Boolean::class.java, Location::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                args[1] = injectLocation(args[1] as Location)

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.onReportLocation: injected! ${args[1]}")
                }
            }

            cGnssLocationProvider.onceHookMethodBefore("onReportLocations", Boolean::class.java, Array<Location>::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                args[0] = (args[0] as Array<*>).map {
                    injectLocation(it as Location)
                }.toTypedArray()

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.onReportLocations: injected! ${args[0]}")
                }
            }

            cGnssLocationProvider.onceHookMethodBefore("getCellType", CellInfo::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.getCellType: injected!")
                }

                result = 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cGnssLocationProvider.onceHookMethodBefore("getCidFromCellIdentity", CellIdentity::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("GnssLocationProvider.getCidFromCellIdentity: injected!")
                    }

                    result = -1L
                }

                cGnssLocationProvider.onceHookMethodBefore("setRefLocation", Int::class.java, CellIdentity::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("GnssLocationProvider.setRefLocation: injected!")
                    }

                    args[0] = 114514 // disable AGPS
                }
            }
        }.onFailure {
            Logger.warn("Failed to hook GnssLocationProvider", it)
        }
    }
}