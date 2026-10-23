package moe.fuqiuluo.portalex.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import moe.fuqiuluo.portalex.ext.wgs84
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 录制期间的位置采样：融合定位优先，室内不要求必须看到 GPS。
 *
 * Android 12 起平台提供 FUSED_PROVIDER；它会把 Wi-Fi / 基站 / GPS 融合成一条
 * 1Hz 左右的流，室内通常仍有网络定位。没有 FUSED_PROVIDER 的旧系统退回
 * NETWORK + GPS；两者都拿不到时保留最后一次已知位置。
 *
 * 这里只做"录制时间轴上的位置采样"，不参与位置模拟；模拟开关仍由上层判断。
 */
class CameraLocationSampler(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)
    private var started = false

    @Volatile
    private var latest: Location? = null

    private var fusedClient: FusedLocationProviderClient? = null
    private var baiduClient: LocationClient? = null
    private var usingGms = false

    /** 首个/后续定位回调；Activity 用它决定何时真正开始录视频。 */
    var onLocation: ((Pair<Double, Double>) -> Unit)? = null

    private val gmsCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                latest = it
                Log.i("CameraLocation", "GMS fused: ${it.latitude},${it.longitude} acc=${it.accuracy}")
                onLocation?.invoke(it.latitude to it.longitude)
            }
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latest = location
            onLocation?.invoke(location.latitude to location.longitude)
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun start() {
        if (started) return
        if (!hasLocationPermission()) return
        started = true
        latest = bestLastKnown()

        // 室内优先用 GMS 融合定位：地图/跑步应用通常也是这条链路。
        runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            fusedClient = client
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && latest == null) latest = loc
            }
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000L
            ).setMinUpdateIntervalMillis(1000L).build()
            client.requestLocationUpdates(request, gmsCallback, Looper.getMainLooper())
            usingGms = true
            Log.i("CameraLocation", "GMS fused requested (balanced)")
        }.onFailure {
            Log.w("CameraLocation", "GMS fused unavailable, fallback LocationManager", it)
            usingGms = false
        }

        // GMS fused 与系统 LocationManager 同时请求：GMS 请求成功不代表
        // 系统侧真的启动了 provider（实测 FUSED ProviderRequest[OFF] 时 GPS 也没启动）。
        // 两路互补，谁先回调谁用。
        if (manager != null) {
            providers().forEach { provider ->
                runCatching {
                    manager.requestLocationUpdates(
                        provider, 1000L, 0f, listener, Looper.getMainLooper()
                    )
                    Log.i("CameraLocation", "LocationManager request: $provider")
                }.onFailure {
                    Log.w("CameraLocation", "LocationManager request failed: $provider", it)
                }
            }
        }
        // 地图/跑步应用在室内通常用百度定位 SDK（Wi-Fi + 基站 + GNSS 融合）；
        // 系统 LocationManager/GMS 在室内可能完全没有回调，因此再加一路百度定位。
        runCatching {
            val client = LocationClient(context)
            val option = LocationClientOption().apply {
                isOpenGps = true
                isOpenGnss = true
                enableSimulateGps = false
                setIsNeedAddress(false)
                setIsNeedLocationDescribe(false)
                setIsNeedLocationPoiList(false)
                locationMode = LocationClientOption.LocationMode.Hight_Accuracy
                setScanSpan(1000)
            }
            client.locOption = option
            client.registerLocationListener(object : BDAbstractLocationListener() {
                override fun onReceiveLocation(loc: BDLocation?) {
                    val wgs = loc?.wgs84 ?: return
                    val pair = wgs.first to wgs.second
                    latest = Location("baidu").apply {
                        latitude = pair.first
                        longitude = pair.second
                        time = System.currentTimeMillis()
                    }
                    Log.i("CameraLocation", "Baidu: ${pair.first},${pair.second} type=${loc.locType}")
                    onLocation?.invoke(pair)
                }
            })
            client.start()
            baiduClient = client
            Log.i("CameraLocation", "Baidu location requested")
        }.onFailure {
            Log.w("CameraLocation", "Baidu location unavailable", it)
        }

        if (latest == null) latest = bestLastKnown()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { fusedClient?.removeLocationUpdates(gmsCallback) }
        fusedClient = null
        baiduClient?.let { runCatching { it.stop() } }
        baiduClient = null
        usingGms = false
        if (manager != null) runCatching { manager.removeUpdates(listener) }
    }

    /** 最新融合/网络/GPS 位置；调用方再回退到 CameraMockRuntime.position()。 */
    fun latestPosition(): Pair<Double, Double>? =
        latest?.let { it.latitude to it.longitude }

    private fun providers(): List<String> {
        val list = ArrayList<String>(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += LocationManager.FUSED_PROVIDER
        }
        list += LocationManager.NETWORK_PROVIDER
        list += LocationManager.GPS_PROVIDER
        return list.filter { p ->
            runCatching { manager?.isProviderEnabled(p) == true }.getOrDefault(false)
        }
    }

    private fun bestLastKnown(): Location? {
        val ps = providers()
        val fallback = ps.mapNotNull { p ->
            runCatching { manager?.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }
        Log.i("CameraLocation", "lastKnown providers=$ps -> ${fallback?.let { "${it.latitude},${it.longitude}" } ?: "null"}")
        return fallback
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
