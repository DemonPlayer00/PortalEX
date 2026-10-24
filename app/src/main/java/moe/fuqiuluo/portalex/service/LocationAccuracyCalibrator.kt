package moe.fuqiuluo.portalex.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import moe.fuqiuluo.xposed.utils.FakeLoc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

/**
 * GPS / fused 定位精度与融合时序采集。
 *
 * 只在**有 GPS 信号**时记账：
 *  · GPS 精度 = 每次 GPS fix 自带的 `Location.accuracy`；
 *  · fused 精度 = 同一时间窗内 GPS 与 fused 的**水平距离**；
 *  · fused 延迟 = 配对帧的 GPS/fused 时间戳差；
 *  · GPS 间隔 = 相邻 GPS fix 的 elapsedRealtime 差；
 *  · 卫星字段 = GPS extras 里的 satellites / maxCn0 / meanCn0。
 *
 * 这是“测试项”，不直接改模拟精度设置。App 不在 LSPosed 作用域内，因此这里读到的是
 * 框架原生 fused，而不是被 PortalEX 改写后的 fused 结果；采集前应停止位置模拟，
 * 否则对比的语义会变成“GPS 与模拟点的差距”。
 */
object LocationAccuracyCalibrator {

    data class Result(
        val gpsAccuracyMeters: Float?,
        val fusedAccuracyMeters: Float?,
        val fusedDelayMs: Float?,
        val gpsIntervalMs: Float?,
        val satellites: Float?,
        val maxCn0: Float?,
        val meanCn0: Float?,
        val gpsSamples: Int,
        val fusedSamples: Int,
        val ok: Boolean,
        val message: String,
        val detail: String = "",
        /** GPS 卫星数的标准差；用于生成卫星数量波动。 */
        val satellitesJitter: Float? = null,
    )

    private const val TAG = "PortalLocationCalib"
    private const val DURATION_MS = 10_000L
    private const val NO_GPS_TIMEOUT_MS = 5_000L
    private const val PAIR_WINDOW_NANOS = 2_000_000_000L
    private const val MAX_GPS_AGE_NANOS = 5_000_000_000L

    fun collect(context: Context): Result {
        if (FakeLoc.enable) {
            return Result(null, null, null, null, null, null, null, 0, 0, false,
                "请先停止位置模拟：否则采到的是注入后的 GPS/fused")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result(null, null, null, null, null, null, null, 0, 0, false, "缺少精确定位权限")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Result(null, null, null, null, null, null, null, 0, 0, false, "定位服务不可用")
        if (!runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            // 没有 GPS 不应阻断其它自动校准：只跳过 GPS/fused 两项。
            return Result(
                null, null, null, null, null, null, null, 0, 0, true,
                "GPS 未启用，已跳过 GPS/fused 校准",
            )
        }

        val gpsValues = ArrayList<Float>()
        val fusedValues = ArrayList<Float>()
        val fusedDelayValues = ArrayList<Float>()
        val gpsIntervalValues = ArrayList<Float>()
        val satelliteValues = ArrayList<Float>()
        val maxCn0Values = ArrayList<Float>()
        val meanCn0Values = ArrayList<Float>()
        var lastGps: Location? = null
        var lastFused: Location? = null
        var lastGpsAtNanos = 0L
        var lastFusedAtNanos = 0L
        var lastPairedGpsAt = 0L
        var lastPairedFusedAt = 0L
        val firstGps = CountDownLatch(1)

        fun pair(gps: Location?, fused: Location?) {
            if (gps == null || fused == null) return
            if (gps.elapsedRealtimeNanos == lastPairedGpsAt &&
                fused.elapsedRealtimeNanos == lastPairedFusedAt
            ) return
            fusedValues += distanceMeters(gps, fused)
            val dt = abs(fused.time - gps.time).toFloat()
            if (dt in 0f..10_000f) fusedDelayValues += dt
            lastPairedGpsAt = gps.elapsedRealtimeNanos
            lastPairedFusedAt = fused.elapsedRealtimeNanos
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.provider == LocationManager.GPS_PROVIDER) {
                    if (!location.hasAccuracy()) return
                    val now = android.os.SystemClock.elapsedRealtimeNanos()
                    val age = now - location.elapsedRealtimeNanos
                    if (age > MAX_GPS_AGE_NANOS) return
                    val previous = lastGps
                    lastGps = location
                    if (previous != null) {
                        val dt = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000.0
                        if (dt > 100.0 && dt < 30_000.0) gpsIntervalValues += dt.toFloat()
                    }
                    lastGpsAtNanos = now
                    gpsValues += location.accuracy
                    collectExtras(location)?.let { (sat, maxCn0, meanCn0) ->
                        satelliteValues += sat
                        maxCn0Values += maxCn0
                        meanCn0Values += meanCn0
                    }
                    firstGps.countDown()
                    if (lastFusedAtNanos > 0L && now - lastFusedAtNanos <= PAIR_WINDOW_NANOS) {
                        pair(location, lastFused)
                    }
                } else if (location.provider == "fused") {
                    val now = android.os.SystemClock.elapsedRealtimeNanos()
                    lastFused = location
                    lastFusedAtNanos = now
                    // GPS 可能先到、fused 后到：两个方向都配对，避免它只在 GPS 回调时采不到。
                    if (lastGpsAtNanos > 0L && now - lastGpsAtNanos <= PAIR_WINDOW_NANOS) {
                        pair(lastGps, location)
                    }
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("platform interface")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        val registered = runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm.requestLocationUpdates("fused", 1000L, 0f, listener, Looper.getMainLooper())
        }.onFailure { Log.w(TAG, "register failed: ${it.message}") }.isSuccess
        if (!registered) {
            return Result(null, null, null, null, null, null, null, 0, 0, false, "定位监听注册失败")
        }

        return try {
            // 没有 GPS 就不要假装在采集：最多等到超时，明确报错。
            if (!firstGps.await(NO_GPS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // 无 GPS 时视为“跳过”而不是失败；传感器噪声档等自动校准仍可继续。
                return Result(
                    null, null, null, null, null, null, null, 0, 0, true,
                    "未收到 GPS，已跳过 GPS/fused 校准",
                )
            }
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < DURATION_MS) {
                Thread.sleep(100)
            }
            if (gpsValues.isEmpty()) {
                Result(null, null, null, null, null, null, null, 0, 0, false, "未采集到有效 GPS 精度")
            } else {
                val gpsMedian = median(gpsValues)
                val fusedMedian = medianOrNull(fusedValues)
                val delayMedian = medianOrNull(fusedDelayValues)
                val intervalMedian = medianOrNull(gpsIntervalValues)
                val satMedian = medianOrNull(satelliteValues)
                val satJitter = if (satelliteValues.size >= 2) {
                    val mean = satelliteValues.average()
                    kotlin.math.sqrt(satelliteValues.sumOf { (it - mean) * (it - mean) } / satelliteValues.size)
                        .toFloat()
                } else 0f
                val maxCn0Median = medianOrNull(maxCn0Values)
                val meanCn0Median = medianOrNull(meanCn0Values)
                Result(
                    gpsAccuracyMeters = gpsMedian,
                    fusedAccuracyMeters = fusedMedian,
                    fusedDelayMs = delayMedian,
                    gpsIntervalMs = intervalMedian,
                    satellites = satMedian,
                    maxCn0 = maxCn0Median,
                    meanCn0 = meanCn0Median,
                    gpsSamples = gpsValues.size,
                    fusedSamples = fusedValues.size,
                    ok = true,
                    message = buildString {
                        append("GPS ${gpsValues.size} 次")
                        if (fusedValues.isNotEmpty()) append(" / fused 配对 ${fusedValues.size} 次")
                        if (satelliteValues.isNotEmpty()) append(" / 卫星 ${satelliteValues.size} 次")
                    },
                    satellitesJitter = satJitter,
                    detail = buildString {
                        append("GPS accuracy median=%.2fm".format(gpsMedian))
                        fusedMedian?.let { append("；GPS-fused 水平差 median=%.2fm".format(it)) }
                        delayMedian?.let { append("；时间差 median=%.0fms".format(it)) }
                        intervalMedian?.let { append("；GPS 间隔 median=%.0fms".format(it)) }
                        satMedian?.let { append("；卫星 median=%.0f".format(it)) }
                        maxCn0Median?.let { append("；maxCn0 median=%.1f".format(it)) }
                        meanCn0Median?.let { append("；meanCn0 median=%.1f".format(it)) }
                    },
                )
            }
        } finally {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    private fun collectExtras(location: Location): Triple<Float, Float, Float>? {
        val extras = location.extras ?: return null
        val sat = extras.get("satellites")?.toString()?.toFloatOrNull()
        val maxCn0 = extras.get("maxCn0")?.toString()?.toFloatOrNull()
        val meanCn0 = extras.get("meanCn0")?.toString()?.toFloatOrNull()
        if (sat == null && maxCn0 == null && meanCn0 == null) return null
        return Triple(sat ?: 0f, maxCn0 ?: 0f, meanCn0 ?: 0f)
    }

    private fun distanceMeters(a: Location, b: Location): Float {
        val lat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dx = (a.longitude - b.longitude) * 111_320.0 * kotlin.math.cos(lat)
        val dy = (a.latitude - b.latitude) * 111_320.0
        return hypot(dx, dy).toFloat()
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    private fun medianOrNull(values: List<Float>): Float? =
        if (values.isEmpty()) null else median(values)
}
