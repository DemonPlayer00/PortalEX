package moe.fuqiuluo.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import kotlin.random.Random

abstract class BaseLocationHook: BaseDivineService() {
    /**
     * @param speedWindowMs 速度平均窗口（毫秒），默认 1s = 真机 GPS 的自然出帧率。
     *   服务端坐标按点跳变推进，若按“相邻两帧”取瞬时速度，只有恰好包含跳变的帧才有速度，
     *   其余帧为 0 → 应用侧整段间隔不计步（步频掉到下限）。按窗口平均后任何一帧都代表
     *   “这段时间走了多少”，与采样相位无关。
     */
    fun injectLocation(originLocation: Location, realLocation: Boolean = true, speedWindowMs: Long = 1000L): Location {
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

        // 已注入判定：按字段严格比较（旧实现比较经纬度**之和**——不同坐标和值相同会误命中，
        // 命中即整体跳过改写，包括 extras 清洗）。默认坐标 (0,0) 不参与判定，避免真实 (0,0) fix 误命中。
        if (FakeLoc.latitude != 0.0 || FakeLoc.longitude != 0.0) {
            if (originLocation.latitude == FakeLoc.latitude && originLocation.longitude == FakeLoc.longitude) {
                // Already processed
                return originLocation
            }
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
        // 速度：按**交付间隔窗口**取平均（间隔参数在此进入数据链）——
        // 旧实现直接写瞬时 measuredSpeed（400ms 衰减窗口）：交付间隔由应用决定（可 900ms+）时，
        // 采样常踩到衰减谷值或跳变之间的空档 → 帧携带 vel=0 → 应用侧整段间隔不计步 → 步频偏低。
        val (frameSpeed, frameMoving) = FakeLoc.averageSpeedOverWindow(speedWindowMs)
        location.speed = if (frameMoving) {
            (frameSpeed + speedAmp).coerceAtLeast(0.0).toFloat()
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

        // 时间戳统一：注入帧一律携带「生成本帧的时刻」，与推送链 buildFrame() 同语义。
        // 旧实现沿用 originLocation.time/elapsedRealtimeNanos（真实 fix 的旧时刻），
        // 于是同一坐标在「推送帧」与「框架帧」上有两套时间——应用按时间差算速率/去重会自相矛盾。
        location.time = System.currentTimeMillis()

        // final addition of zero is to remove -0 results. while these are technically within the
        // range [0, 360) according to IEEE semantics, this eliminates possible user confusion.
        // 角度加工（模块层）：平滑趋近权威目标 + 微小扰动——
        // 自动播放直线段时 bearing 恒定，直接写入会让位置/指南针锁死在前进方向。
        var modBearing = FakeLoc.processedBearing() % 360.0 + 0.0
        if (modBearing < 0) {
            modBearing += 360.0
        }
        location.bearing = modBearing.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 朝向精度：真实设备量级 1~5 度。原实现直接写入角度值（bAcc == bearing），异常。
            location.bearingAccuracyDegrees = Random.nextDouble(1.0, 5.0).toFloat()
        }

        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
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
        // extras 只透传原始数据，不写任何模块自有键：extras 随 Parcel 到达每个拿到该
        // Location 的应用，键名再中性也是指纹（真实 Location 的 extras 不会长这样）。
        // 传感器侧改从标准字段（location.speed / location.bearing）取数，无需私有通路。
        // extras 只透传原始数据，不写任何模块自有键；但系统自带的卫星字段要改写——
        // 位置伪造到户外后，真实环境（室内）的 satellites=0 会与位置矛盾。
        location.extras = FakeLoc.sanitizeGnssExtras(originLocation.extras)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (originLocation.hasMslAltitude()) {
                location.mslAltitudeMeters = FakeLoc.offset_altitude
            }
            if (originLocation.hasMslAltitudeAccuracy()) {
                // 高度精度：真实设备量级 1~5 米。原实现写入高度值本身（80.0），异常。
                location.mslAltitudeAccuracyMeters = Random.nextDouble(1.0, 5.0).toFloat()
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
                    // 与注入帧统一口径：窗口平均（旧实现用瞬时 measuredSpeed，两条链对不上账）
                    value.speedKnots = FakeLoc.averageSpeedOverWindow(1000L).first * 1.94384
                    value.trackAngle = FakeLoc.processedBearing()
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