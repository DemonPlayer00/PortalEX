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

                val originLocation = mLocations.firstOrNull() as? Location
                    ?: Location(LocationManager.GPS_PROVIDER)

                val location = Location(originLocation.provider)

                val jitterLat = FakeLoc.jitterLocation()
                location.latitude = jitterLat.first
                location.longitude = jitterLat.second
                location.altitude = FakeLoc.offset_altitude
                // 与主注入路径一致：移动中 = 实际位移推算速度±抖动，静止 = 0
                location.speed = if (FakeLoc.isMoving) {
                    (FakeLoc.measuredSpeed + Random.nextDouble(-FakeLoc.speedAmplitude, FakeLoc.speedAmplitude)).coerceAtLeast(0.0).toFloat()
                } else {
                    0.0f
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    location.speedAccuracyMetersPerSecond = Random.nextDouble(0.1, 0.5).toFloat()
                }

                location.time = originLocation.time
                location.accuracy = originLocation.accuracy
                var modBearing = FakeLoc.bearing % 360.0 + 0.0
                if (modBearing < 0) {
                    modBearing += 360.0
                }
                if (location.hasBearing()) {
                    location.bearing = modBearing.toFloat()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // 朝向精度：真实设备量级 1~5 度（原实现写入角度值，异常）
                    location.bearingAccuracyDegrees = Random.nextDouble(1.0, 5.0).toFloat()
                }
                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    location.elapsedRealtimeUncertaintyNanos = originLocation.elapsedRealtimeUncertaintyNanos
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                }
                // extras 透传原始数据，但系统自带的卫星字段要改写（理由同 BaseLocationHook）
                location.extras = FakeLoc.sanitizeGnssExtras(originLocation.extras)

                mLocationsField.set(locationResult, arrayListOf(location))
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