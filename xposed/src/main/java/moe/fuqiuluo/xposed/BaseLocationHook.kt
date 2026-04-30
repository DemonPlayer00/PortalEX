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
        val speedAmp = Random.nextDouble(-FakeLoc.speedAmplitude, FakeLoc.speedAmplitude)
        location.speed = (originLocation.speed + speedAmp).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originLocation.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond = (FakeLoc.speed + speedAmp).toFloat()
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
            location.bearingAccuracyDegrees = modBearing.toFloat()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (location.hasBearingAccuracy() && location.bearingAccuracyDegrees == 0.0f) {
                location.bearingAccuracyDegrees = 1.0f
            }
        }

        if (location.speed == 0.0f) {
            location.speed = 1.2f
        }

        location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.elapsedRealtimeUncertaintyNanos = originLocation.elapsedRealtimeUncertaintyNanos
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
        }
        originLocation.extras?.let {
            location.extras = it
        }
        if (location.extras == null) {
            location.extras = Bundle()
        }
        location.extras?.putDouble("latlon", location.latitude + location.longitude)
        location.extras?.putInt("satellites", Random.nextInt(8, 45))
        location.extras?.putInt("maxCn0", Random.nextInt(30, 50))
        location.extras?.putInt("meanCn0", Random.nextInt(20, 30))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (originLocation.hasMslAltitude()) {
                location.mslAltitudeMeters = FakeLoc.offset_altitude
            }
            if (originLocation.hasVerticalAccuracy()) {
                location.mslAltitudeAccuracyMeters = FakeLoc.offset_altitude.toFloat()
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
            location.extras?.putBoolean("portal.enable", true)
            location.extras?.putBoolean("is_mock", true)
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
                    value.speedKnots = FakeLoc.speed * 1.94384
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
    private fun updateLatLon(value: Any, lat: Double, lon: Double) {
        val latHemisphere = if (lat >= 0) "N" else "S"
        val lonHemisphere = if (lon >= 0) "E" else "W"

        val latDeg = lat.toInt()
        val latMin = (lat - latDeg) * 60
        val newLat = latDeg + latMin / 100.0

        val lonDeg = lon.toInt()
        val lonMin = (lon - lonDeg) * 60
        val newLon = lonDeg + lonMin / 100.0

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