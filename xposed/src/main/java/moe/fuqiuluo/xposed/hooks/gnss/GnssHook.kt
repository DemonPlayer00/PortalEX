package moe.fuqiuluo.xposed.hooks.gnss

import android.location.Location
import moe.fuqiuluo.xposed.utils.MethodHook
import moe.fuqiuluo.xposed.utils.MethodHookParam
import moe.fuqiuluo.xposed.utils.XposedHelpers
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.hooks.blindhook.BlindHookLocation
import moe.fuqiuluo.xposed.hooks.blindhook.BlindHookLocation.invoke
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookAllMethods
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import java.util.Collections
import moe.fuqiuluo.xposed.utils.Hooks

object GnssHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        hookFrameworkGnss(classLoader)
        hookHALGnss(classLoader)
    }

    private fun hookFrameworkGnss(classLoader: ClassLoader) {
        val cGnssManagerService = XposedHelpers.findClassIfExists("com.android.server.location.GnssManagerService", classLoader) ?: return

        val doNothingMethod = object: MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null || param.args.isEmpty()) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("doNothingMethod: ${param.method.name}")
                }

                if (FakeLoc.enableMockGnss && !FakeLoc.enableAGPS) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method.name}: disable")
                    }
                    param.result = null
                }
            }
        }

        // AGPS Listener?
        Hooks.hookAllMethods(cGnssManagerService, "addGnssAntennaInfoListener", doNothingMethod)
        Hooks.hookAllMethods(cGnssManagerService, "addGnssMeasurementsListener", doNothingMethod)
        Hooks.hookAllMethods(cGnssManagerService, "addGnssNavigationMessageListener", doNothingMethod)
        Hooks.hookAllMethods(cGnssManagerService, "removeGnssAntennaInfoListener", doNothingMethod)
        Hooks.hookAllMethods(cGnssManagerService, "removeGnssMeasurementsListener", doNothingMethod)
        Hooks.hookAllMethods(cGnssManagerService, "removeGnssNavigationMessageListener", doNothingMethod)

        run {
            val hookedGnssCallback = Collections.synchronizedSet(HashSet<String>())
            val unhooks = cGnssManagerService.declaredMethods.filter {
                it.name == "registerGnssNmeaCallback" && it.parameterTypes.size > 1
            }.map { method ->
                Hooks.hookMethod(method, object : MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args[0] == null) return
                        val classListener = param.args[0].javaClass

                        if (hookedGnssCallback.contains(classListener.name)) return
                        hookedGnssCallback.add(classListener.name)

                        if (FakeLoc.enableDebugLog)
                            Logger.debug("registerGnssNmeaCallback: $classListener")
                        kotlin.runCatching {
                            XposedHelpers.findAndHookMethod(classListener, "onNmeaReceived", Long::class.java, String::class.java, object: MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (FakeLoc.enableMockGnss && !FakeLoc.enableAGPS) {
                                        if (FakeLoc.enableDebugLog)
                                            Logger.debug("GnssManagerService.onNmeaReceived: disable")
                                        param.result = null
                                        return
                                    }

                                    val nmea = param.args[1] as String
                                    param.args[1] = injectNMEA(nmea) ?: nmea
                                }
                            })
                        }.onFailure {
                            Logger.error("[onNmeaReceived hook failed: ${it.message} from GnssManagerService, please issue?", it)
                        }
                    }
                })
            }

            if (FakeLoc.enableDebugLog)
                Logger.debug("found ${unhooks.size} registerGnssNmeaCallback")
        }
    }

    private fun hookHALGnss(classLoader: ClassLoader) {
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V1_0.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V1_1.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V2_1.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V2_0.IGnss\$Stub", classLoader))
        var hookedGnssLocationProvider = 0
        val cGnssLocationProviderImpl = XposedHelpers.findClassIfExists("com.android.server.location.gnss.GnssLocationProviderImpl", classLoader)
        if (cGnssLocationProviderImpl != null) {
            BlindHookLocation(cGnssLocationProviderImpl, classLoader)
            hookedGnssLocationProvider = 1
        }

        if (hookedGnssLocationProvider == 0) {
            val cGnssLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.gnss.GnssLocationProvider", classLoader)
            if (cGnssLocationProvider != null) {
                BlindHookLocation(cGnssLocationProvider, classLoader)
                hookedGnssLocationProvider = 2
            }
        }

        if (hookedGnssLocationProvider == 0) {
            Logger.error("Failed to find GnssLocationProvider!")
        } else {
            Logger.info("GnssLocationProvider done, status: $hookedGnssLocationProvider")
        }

        val cGnssNative = XposedHelpers.findClassIfExists("com.android.server.location.gnss.hal.GnssNative", classLoader)
        if (cGnssNative != null) {
            BlindHookLocation(cGnssNative, classLoader)

            cGnssNative.hookAllMethods("reportLocationBatch", beforeHook {
                if (FakeLoc.enable && args.size > 1 && args[0] is Array<*>) {
                    val locations = args[0] as Array<Location>
                    locations.forEachIndexed { index, location ->
                        locations[index] = injectLocation(location)
                    }
                }
            })
        } else {
            Logger.error("GnssNative not found")
        }
    }

}