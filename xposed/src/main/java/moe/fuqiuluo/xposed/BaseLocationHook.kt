package moe.fuqiuluo.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import kotlin.random.Random

abstract class BaseLocationHook: BaseDivineService() {
    fun injectLocation(originLocation: Location, realLocation: Boolean = true): Location {
        if (realLocation) {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    originLocation.provider == LocationManager.GPS_PROVIDER && originLocation.isComplete
                } else {
                    originLocation.provider == LocationManager.GPS_PROVIDER
                }
            ) {
                FakeLoc.lastLocation = originLocation
            }
        } else {
            originLocation.altitude = FakeLoc.offset_altitude
        }

        if (!FakeLoc.enable)
            return originLocation

        if (originLocation.latitude + originLocation.longitude == FakeLoc.latitude + FakeLoc.longitude) {
            // Already processed
            return originLocation
        }

        if (FakeLoc.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER) {
            originLocation.provider = LocationManager.GPS_PROVIDER
        }

        val location = Location(originLocation.provider ?: LocationManager.GPS_PROVIDER)
        location.accuracy = if (FakeLoc.accuracy != 0.0f) FakeLoc.accuracy else originLocation.accuracy
        val jitterLat = FakeLoc.jitterLocation()
        location.latitude = jitterLat.first
        location.longitude = jitterLat.second
        location.altitude = FakeLoc.offset_altitude
        // 速度由实际模拟位移推算（FakeLoc.measuredSpeed）：移动中 = 实测速度 ± 抖动，静止 = 0。
        // 早期实现直接写入配置速度，与注入的位置变化无关（跨帧对比位移与 speed 即可发现矛盾）。
        val speedAmp = Random.nextDouble(-FakeLoc.speedAmplitude, FakeLoc.speedAmplitude)
        val measuredSpeed = FakeLoc.measuredSpeed
        location.speed = if (FakeLoc.isMoving) {
            (measuredSpeed + speedAmp).coerceAtLeast(0.0).toFloat()
        } else {
            0.0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originLocation.hasSpeedAccuracy()) {
            // 速度精度：真实设备量级 0.1~0.5 m/s。原实现借用该字段承载模拟速度
            // （值恒等于速度，异常且可被检测），改为独立随机小值。
            location.speedAccuracyMetersPerSecond = Random.nextDouble(0.1, 0.5).toFloat()
        }

        if (location.altitude == 0.0) {
            location.altitude = 80.0
        }

        location.time = originLocation.time

        // final addition of zero is to remove -0 results. while these are technically within the
        // range [0, 360) according to IEEE semantics, this eliminates possible user confusion.
        var modBearing = FakeLoc.bearing % 360.0 + 0.0
        if (modBearing < 0) {
            modBearing += 360.0
        }
        location.bearing = modBearing.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 朝向精度：真实设备量级 1~5 度。原实现直接写入角度值（bAcc == bearing），异常。
            location.bearingAccuracyDegrees = Random.nextDouble(1.0, 5.0).toFloat()
        }

        location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.elapsedRealtimeUncertaintyNanos = originLocation.elapsedRealtimeUncertaintyNanos
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 垂直精度：原始位置未提供时（0）补合理随机值，避免恒为 0 的异常值
            location.verticalAccuracyMeters =
                if (originLocation.hasVerticalAccuracy() && originLocation.verticalAccuracyMeters > 0f) {
                    originLocation.verticalAccuracyMeters
                } else {
                    Random.nextDouble(5.0, 15.0).toFloat()
                }
        }
        originLocation.extras?.let {
            location.extras = it
        }
        if (location.extras == null) {
            location.extras = Bundle()
        }
        // 传感器模拟所需的模拟速度/朝向/移动状态（客户端进程 SystemSensorManagerHook 读取）。
        // 键名刻意中性化、不含模块特征，且仅在传感器模拟开启时写入，
        // 避免目标应用凭 extras 键名识别本模块。
        if (FakeLoc.sensorMockEnabled) {
            location.extras?.putDouble("spd", measuredSpeed)
            location.extras?.putDouble("brg", FakeLoc.bearing)
            location.extras?.putBoolean("mov", FakeLoc.isMoving)
        }
        location.extras?.putInt("satellites", Random.nextInt(8, 26))
        location.extras?.putInt("maxCn0", Random.nextInt(30, 50))
        location.extras?.putInt("meanCn0", Random.nextInt(20, 30))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (originLocation.hasMslAltitude()) {
                location.mslAltitudeMeters = FakeLoc.offset_altitude
            }
            if (originLocation.hasMslAltitudeAccuracy()) {
                // 高度精度：真实设备量级 1~5 米。原实现写入高度值本身（80.0），异常。
                location.mslAltitudeAccuracyMeters = Random.nextDouble(1.0, 5.0).toFloat()
            }
        }
        if (FakeLoc.hideMock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = false
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = true
            }
        }

        kotlin.runCatching {
            XposedHelpers.callMethod(location, "makeComplete")
        }.onFailure {
            Logger.error("makeComplete failed", it)
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("injectLocation success! $location")
        }

        return location
    }

    fun injectNMEA(nmeaStr: String): String {
        // 未启用模拟时，直接返回原始字符串
        if (!FakeLoc.enable) return nmeaStr

        return runCatching {
            val nmea = NMEA.valueOf(nmeaStr)
            when (val value = nmea.value) {
                is NmeaValue.GGA -> {
                    // 无效数据不修改，保留原始字符串
                    if (value.latitude == null || value.longitude == null) return nmeaStr
                    if (value.fixQuality == 0) return nmeaStr
                    updateLatLon(value, FakeLoc.latitude, FakeLoc.longitude)
                    value.toNmeaString()
                }
                is NmeaValue.GNS -> {
                    if (value.latitude == null || value.longitude == null) return nmeaStr
                    if (value.mode == "N") return nmeaStr
                    updateLatLon(value, FakeLoc.latitude, FakeLoc.longitude)
                    value.toNmeaString()
                }
                is NmeaValue.RMC -> {
                    if (value.latitude == null || value.longitude == null) return nmeaStr
                    if (value.status == "V") return nmeaStr
                    updateLatLon(value, FakeLoc.latitude, FakeLoc.longitude)
                    // 同步速度和航向（m/s → 节，1 m/s = 1.94384 knots）
                    value.speedKnots = FakeLoc.measuredSpeed * 1.94384
                    value.trackAngle = FakeLoc.bearing
                    value.toNmeaString()
                }
                // 其他语句类型（DTM、GSA、GSV、VTG）不做修改，原样返回
                else -> nmeaStr
            }
        }.onFailure {
            Logger.error("NMEA parse failed: ${it.message}, source = $nmeaStr")
        }.getOrDefault(nmeaStr)
    }

    // 辅助函数：更新GGA/GNS/RMC中的经纬度及半球
    // NMEA 坐标字段是度分格式（ddmm.mmmm）：十进制度 31.2304° → 度=31，分=0.2304*60=13.824 → 3113.824
    // （NmeaValue/解析侧将原始度分串直接 toDouble，故此处也必须输出度分数值，与 toNmeaString 的 %011.6f 配套）
    private fun updateLatLon(value: Any, lat: Double, lon: Double) {
        val latHemisphere = if (lat >= 0) "N" else "S"
        val lonHemisphere = if (lon >= 0) "E" else "W"

        val absLat = kotlin.math.abs(lat)
        val latDeg = absLat.toInt()
        val latMin = (absLat - latDeg) * 60
        val newLat = latDeg * 100.0 + latMin

        val absLon = kotlin.math.abs(lon)
        val lonDeg = absLon.toInt()
        val lonMin = (absLon - lonDeg) * 60
        val newLon = lonDeg * 100.0 + lonMin

        when (value) {
            is NmeaValue.GGA -> {
                value.latitude = newLat
                value.longitude = newLon
                value.latitudeHemisphere = latHemisphere
                value.longitudeHemisphere = lonHemisphere
            }
            is NmeaValue.GNS -> {
                value.latitude = newLat
                value.longitude = newLon
                value.latitudeHemisphere = latHemisphere
                value.longitudeHemisphere = lonHemisphere
            }
            is NmeaValue.RMC -> {
                value.latitude = newLat
                value.longitude = newLon
                value.latitudeHemisphere = latHemisphere
                value.longitudeHemisphere = lonHemisphere
            }
        }
    }
}