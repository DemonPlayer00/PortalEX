package moe.fuqiuluo.xposed.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.hooks.blindhook.BlindHookLocation
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.hookAllMethodsAfter
import moe.fuqiuluo.xposed.utils.hookAllMethodsBefore
import moe.fuqiuluo.xposed.utils.toClassOrThrow
import kotlin.random.Random

object BasicLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
//        val hookSetLatitude = object: XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//                if (!FakeLocationConfig.enable) return
//
//            }
//        }
//        val hookSetLongitude = object: XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//
//
//            }
//        }
//        XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setLatitude", Double::class.java, hookSetLatitude)
//        XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setLongitude", Double::class.java, hookSetLongitude)

        kotlin.runCatching {
            val cLocationResult = "android.location.LocationResult".toClassOrThrow(classLoader)
            BlindHookLocation(cLocationResult, classLoader)

            cLocationResult.hookAllMethodsAfter("asList") {
                if (!FakeLoc.enable) return@hookAllMethodsAfter

                val locations = result as List<*>
                result = locations.map { injectLocation(it as Location) }
            }

            cLocationResult.hookAllMethodsBefore("writeToParcel") {
                if (!FakeLoc.enable) return@hookAllMethodsBefore

                val locationResult = thisObject
                val mLocationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
                if (mLocationsField == null) {
                    Logger.error("Failed to find mLocations in LocationResult")
                    return@hookAllMethodsBefore
                }
                mLocationsField.isAccessible = true
                val mLocations = mLocationsField.get(locationResult) as ArrayList<*>

                // 统一走主注入路径（BaseLocationHook.injectLocation）：
                // writeToParcel 是帧跨进程的必经关口，这里口径必须与推送链一致。
                // **逐条注入并保留批次长度**：旧实现只取 first 再写回单元素 ArrayList，
                // 批量位置（onLocationBatch / flush 批次）除首帧外全部丢失。
                val injected = ArrayList<Location>(maxOf(1, mLocations.size))
                mLocations.forEach { item ->
                    (item as? Location)?.let { injected.add(injectLocation(it)) }
                }
                if (injected.isEmpty()) {
                    injected.add(injectLocation(Location(LocationManager.GPS_PROVIDER)))
                }
                mLocationsField.set(locationResult, injected)
            }
        }.onFailure {
           Logger.error("Failed to hook LocationResult", it)
        }

        Location::class.java.hookAllMethodsBefore("set") {
            if (!FakeLoc.enable) return@hookAllMethodsBefore
            args[0] = injectLocation(args[0] as Location)
        }
//        if (FakeLocationConfig.DEBUG) {
//            // Track the invocation of AutoNavi map system services
//            val cBundle = XposedHelpers.findClass("android.os.Bundle", classLoader)
//            XposedBridge.hookAllMethods(cBundle, "putInt", object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    val key = param.args[0] as? String
//                    val value = param.args[1] as Int
//
//                    if (key == "amap" || key == "resubtype" || key == "maxCn0") {
//                        XposedBridge.log(RuntimeException())
//                    }
//                }
//            })
//            XposedBridge.hookAllMethods(Location::class.java, "setExtras", object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    XposedBridge.log(RuntimeException())
//                }
//            })
//        }


    }
}