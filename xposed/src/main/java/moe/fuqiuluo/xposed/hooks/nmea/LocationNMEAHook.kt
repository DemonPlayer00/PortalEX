package moe.fuqiuluo.xposed.hooks.nmea

import moe.fuqiuluo.xposed.utils.MethodHook
import moe.fuqiuluo.xposed.utils.MethodHookParam
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.onceHookBefore
import moe.fuqiuluo.xposed.utils.onceHookMethodBefore
import java.util.Collections
import moe.fuqiuluo.xposed.utils.Hooks

object LocationNMEAHook: BaseLocationHook() {
    operator fun invoke(classILocationManager: Class<*>) {
        hookGnssNmea(classILocationManager)

        val doNothingMethod = object: MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null || param.args.isEmpty()) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("doNothingMethod: ${param.method.name}")
                }

                if (FakeLoc.enable && !FakeLoc.enableNMEA) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method.name}: disable")
                    }
                    param.result = null
                }
            }
        }

        Hooks.hookAllMethods(classILocationManager, "addGnssMeasurementsListener", doNothingMethod)
        Hooks.hookAllMethods(classILocationManager, "removeGnssMeasurementsListener", doNothingMethod)
        Hooks.hookAllMethods(classILocationManager, "addGnssNavigationMessageListener", doNothingMethod)
        Hooks.hookAllMethods(classILocationManager, "removeGnssNavigationMessageListener", doNothingMethod)
        Hooks.hookAllMethods(classILocationManager, "addGnssAntennaInfoListener", doNothingMethod)
        Hooks.hookAllMethods(classILocationManager, "removeGnssAntennaInfoListener", doNothingMethod)
    }

    private fun hookGnssNmea(classILocationManager: Class<*>) {
        val hookedGnssCallback = Collections.synchronizedSet(HashSet<String>())
        val unhooks = classILocationManager.declaredMethods.filter {
            it.name == "registerGnssNmeaCallback" && it.parameterTypes.size > 1
        }.map { method ->
            method.onceHookBefore {
                val cIGnssNmeaCallback = (args[0] ?: return@onceHookBefore).javaClass

                if (hookedGnssCallback.contains(cIGnssNmeaCallback.name)) return@onceHookBefore
                hookedGnssCallback.add(cIGnssNmeaCallback.name)

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("registerGnssNmeaCallback: $cIGnssNmeaCallback")
                }

                cIGnssNmeaCallback.onceHookMethodBefore("onNmeaReceived", Long::class.java, String::class.java) {
                    if (FakeLoc.enableNMEA && !FakeLoc.enableAGPS) {
                        result = null // disable
                        return@onceHookMethodBefore
                    }

                    val nmea = args[1] as? String ?: return@onceHookMethodBefore
                    args[1] = injectNMEA(nmea) ?: nmea
                }
            }
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("found ${unhooks.size} registerGnssNmeaCallback")
        }
    }
}