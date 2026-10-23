package moe.fuqiuluo.xposed.hooks

import android.location.Location
import moe.fuqiuluo.xposed.utils.MethodHook
import moe.fuqiuluo.xposed.utils.MethodHookParam
import moe.fuqiuluo.xposed.utils.XposedHelpers
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import moe.fuqiuluo.xposed.utils.Hooks

object LocationManagerHook: BaseLocationHook() {
    operator fun invoke(
        cLocationManager: Class<*>,
    ) {
        val hookGetLastKnownLocation = object: MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                if (param == null || param.hasThrowable() || param.result == null) return

                if (!FakeLoc.enable) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("${param.method.name}: injected!")
                }

                param.result = injectLocation(param.result as Location)
            }
        }
        if(cLocationManager.declaredMethods.filter {
            // getLastKnownLocation(String) 才是应用可见的一次性取位 API（参数个数 1）——
            // 旧实现限定 parameterTypes.size > 1，把它排除在外，回退去挂不存在的 getLastLocation。
            it.name == "getLastKnownLocation" || it.name == "getLastLocation"
        }.map {
            Hooks.hookMethod(it, hookGetLastKnownLocation)
        }.isEmpty()) {
            Hooks.hookAllMethods(cLocationManager, "getLastKnownLocation", hookGetLastKnownLocation)
            Hooks.hookAllMethods(cLocationManager, "getLastLocation", hookGetLastKnownLocation)
        }

        val hookOnLocation = object: MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.isEmpty() || param.args[0] == null) return

                if (!FakeLoc.enable) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("${param.method.name}: injected!")
                }

                when( param.args[0] ) {
                    is Location -> {
                        param.args[0] = injectLocation(param.args[0] as Location)
                    }
                    is List<*> -> {
                        val locations = param.args[0] as List<*>
                        param.args[0] = locations.map { injectLocation(it as Location) }
                    }
                    else -> {
                        Logger.error("Unknown method when hook hookOnLocation: ${param.method}")
                    }
                }
            }
        }

        if(cLocationManager.declaredMethods.filter {
                it.name == "requestFlush"
            }.map {
                Hooks.hookMethod(it, object : MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        // requestFlush 的重载：
                        //  requestFlush(LocationListener)  —— 单参，listener 在 args[0]
                        //  requestFlush(String, Executor, LocationListener)（API 31+）—— listener 在 args[2]
                        // 统一取最后一个参数作为 listener，避免固定索引越界或取到 Executor。
                        if (param == null || param.args.isEmpty()) return

                        val listener = param.args[param.args.size - 1]
                            ?: return
                        if (listener !is android.location.LocationListener) {
                            // 最后一个参数不是 listener（可能是版本差异），无法 hook
                            return
                        }
                        listener.javaClass.onceHookAllMethod("onLocationChanged", hookOnLocation)
                    }
                })
            }.isEmpty()) {
            Logger.error("Hook requestFlush failed")
        }

        val hookRequestLocationUpdates = object: MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null || param.args.isEmpty() || param.args[1] == null) return

                param.args.filterIsInstance<android.location.LocationListener>().also {
                    if (it.isEmpty()) {
                        Logger.error("No LocationListener found in requestLocationUpdates: ${param.method}(${
                            param.args?.joinToString { it?.javaClass.toString() }
                        })")
                    }
                }.forEach {
                    it.javaClass.onceHookAllMethod("onLocationChanged", hookOnLocation)
                }
            }
        }
        if(cLocationManager.declaredMethods.filter {
                it.name == "requestLocationUpdates"
            }.map {
                Hooks.hookMethod(it, hookRequestLocationUpdates)
            }.isEmpty()) {
            Logger.error("Hook requestLocationUpdates failed")
        }

        if(cLocationManager.declaredMethods.filter {
                it.name == "requestSingleUpdate"
            }.map {
                Hooks.hookMethod(it, hookRequestLocationUpdates)
            }.isEmpty()) {
            Logger.error("Hook requestSingleUpdate failed")
        }

        kotlin.runCatching {
            XposedHelpers.findClass("android.location.LocationManager\$GetCurrentLocationTransport", cLocationManager.classLoader)
        }.onSuccess {
            // 回调方法名随版本变化：老实现是 onLocation，Consumer 形态是 accept——两种都挂，
            // 不把方法名写死（写死过一次：onLocation 在本机 framework 上根本不存在，静默失效）
            it.onceHookAllMethod("onLocation", hookOnLocation)
            it.onceHookAllMethod("accept", hookOnLocation)
        }.onFailure {
            Hooks.log(it)
        }

        kotlin.runCatching {
            XposedHelpers.findClass("android.location.LocationManager\$BatchedLocationCallbackWrapper", cLocationManager.classLoader)
        }.onSuccess {
            it.onceHookAllMethod("onLocationChanged", hookOnLocation)
        }

        kotlin.runCatching {
            XposedHelpers.findClass("android.location.LocationManager\$LocationListenerTransport", cLocationManager.classLoader)
        }.onSuccess {
            it.onceHookAllMethod("onLocationChanged", hookOnLocation)
        }.onFailure {
            Hooks.log(it)
        }
    }
}