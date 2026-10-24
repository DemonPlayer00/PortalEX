package moe.fuqiuluo.portalex.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import moe.fuqiuluo.xposed.utils.FakeLoc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * GPS / fused 定位精度采集。
 *
 * 只在**有 GPS 信号**时记账：
 *  · GPS 精度 = 每次 GPS fix 自带的 `Location.accuracy`；
 *  · fused 精度 = 同一时间窗内 GPS 与 fused 的**水平距离**。
 *
 * 这是“测试项”，不直接改模拟精度设置。App 不在 LSPosed 作用域内，因此这里读到的是
 * 框架原生 fused，而不是被 PortalEX 改写后的 fused 结果；采集前应停止位置模拟，
 * 否则对比的语义会变成“GPS 与模拟点的差距”。
 */
object LocationAccuracyCalibrator {

    data class Result(
        val gpsAccuracyMeters: Float?,
        val fusedAccuracyMeters: Float?,
        val gpsSamples: Int,
        val fusedSamples: Int,
        val ok: Boolean,
        val message: String,
        val detail: String = "",
    )

    private const val TAG = "PortalLocationCalib"
    private const val DURATION_MS = 10_000L
    private const val NO_GPS_TIMEOUT_MS = 5_000L
    private const val PAIR_WINDOW_NANOS = 2_000_000_000L
    private const val MAX_GPS_AGE_NANOS = 5_000_000_000L

    fun collect(context: Context): Result {
        if (FakeLoc.enable) {
            return Result(null, null, 0, 0, false, "请先停止位置模拟：否则采到的是注入后的 GPS/fused")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result(null, null, 0, 0, false, "缺少精确定位权限")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Result(null, null, 0, 0, false, "定位服务不可用")
        if (!runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            return Result(null, null, 0, 0, false, "GPS provider 未启用")
        }

        val gpsValues = ArrayList<Float>()
        val fusedValues = ArrayList<Float>()
        var lastGps: Location? = null
        var lastFused: Location? = null
        var lastGpsAtNanos = 0L
        var lastFusedAtNanos = 0L
        val firstGps = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.provider == LocationManager.GPS_PROVIDER) {
                    if (!location.hasAccuracy()) return
                    val now = android.os.SystemClock.elapsedRealtimeNanos()
                    val age = now - location.elapsedRealtimeNanos
                    if (age > MAX_GPS_AGE_NANOS) return
                    lastGps = location
                    lastGpsAtNanos = now
                    gpsValues += location.accuracy
                    firstGps.countDown()
                    // 配对：GPS 到达时，取最近一帧 fused 计算水平距离。
                    val fused = lastFused
                    if (fused != null && lastFusedAtNanos > 0L &&
                        now - lastFusedAtNanos <= PAIR_WINDOW_NANOS
                    ) {
                        fusedValues += distanceMeters(location, fused)
                    }
                } else if (location.provider == "fused" ||
                    location.provider == "fused"
                ) {
                    lastFused = location
                    lastFusedAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
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
            return Result(null, null, 0, 0, false, "定位监听注册失败")
        }

        return try {
            // 没有 GPS 就不要假装在采集：最多等到超时，明确报错。
            if (!firstGps.await(NO_GPS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Result(null, null, 0, 0, false, "未收到 GPS 信号，无法采集")
            }
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < DURATION_MS) {
                Thread.sleep(100)
            }
            if (gpsValues.isEmpty()) {
                Result(null, null, 0, 0, false, "未采集到有效 GPS 精度")
            } else {
                val gpsMedian = median(gpsValues)
                val fusedMedian = if (fusedValues.isEmpty()) null else median(fusedValues)
                Result(
                    gpsAccuracyMeters = gpsMedian,
                    fusedAccuracyMeters = fusedMedian,
                    gpsSamples = gpsValues.size,
                    fusedSamples = fusedValues.size,
                    ok = fusedMedian != null,
                    message = if (fusedMedian == null) "有 GPS，但没有可配对的 fused 结果"
                    else "采集完成：GPS ${gpsValues.size} 次 / fused 配对 ${fusedValues.size} 次",
                    detail = buildString {
                        append("GPS accuracy median=%.2fm".format(gpsMedian))
                        if (fusedMedian != null) append("；GPS-fused 水平差 median=%.2fm".format(fusedMedian))
                    },
                )
            }
        } finally {
            runCatching { lm.removeUpdates(listener) }
            main.removeCallbacksAndMessages(null)
        }
    }

    private fun distanceMeters(a: Location, b: Location): Float {
        val lat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dx = (a.longitude - b.longitude) * 111_320.0 * kotlin.math.cos(lat)
        val dy = (a.latitude - b.latitude) * 111_320.0
        return hypot(dx, dy).toFloat()
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }
}
