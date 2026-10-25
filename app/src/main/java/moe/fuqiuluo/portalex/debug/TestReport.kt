package moe.fuqiuluo.portalex.debug

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.TelephonyManager
import android.net.wifi.WifiManager
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.service.FusedProbe
import moe.fuqiuluo.portalex.service.MockServiceHelper

/** 与 UI 无关的实时调试报告；root 命令直接调用，不依赖 Test 页面是否打开。 */
object TestReport {
    fun build(ctx: Context): String {
        val out = StringBuilder()
        out.appendLine("=== PortalEX live report ===")
        out.appendLine("package=${ctx.packageName}")
        out.appendLine("sdk=${android.os.Build.VERSION.SDK_INT}")
        out.appendLine("dev_mode=${Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1)}")
        out.appendLine("adb_enabled=${Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, -1)}")

        out.appendLine()
        out.appendLine("--- network ---")
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            @Suppress("DEPRECATION")
            val ni = cm.activeNetworkInfo
            out.appendLine("active=${cm.activeNetwork}")
            out.appendLine("wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false}")
            out.appendLine("cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false}")
            out.appendLine("type=${ni?.type} ${ni?.typeName} state=${ni?.state}")
        }.onFailure { out.appendLine("error=${it.message}") }

        out.appendLine()
        out.appendLine("--- wifi ---")
        runCatching {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wi = wm.connectionInfo
            out.appendLine("enabled=${wm.isWifiEnabled}")
            out.appendLine("ssid=${wi?.ssid}")
            out.appendLine("bssid=${wi?.bssid}")
            out.appendLine("rssi=${wi?.rssi}")
            out.appendLine("frequency=${wi?.frequency}")
            out.appendLine("scans=${runCatching { wm.scanResults?.size }.getOrDefault(-1)}")
        }.onFailure { out.appendLine("error=${it.message}") }

        out.appendLine()
        out.appendLine("--- gps ---")
        runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            out.appendLine("provider=${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            out.appendLine("last=${loc?.latitude}, ${loc?.longitude}")
            out.appendLine("satellites=${loc?.extras?.getInt("satellites", -1)}")
            out.appendLine("extras=${loc?.extras?.keySet()?.joinToString { "$it=${loc.extras?.get(it)}" } ?: "null"}")
        }.onFailure { out.appendLine("error=${it.message}") }

        out.appendLine()
        out.appendLine("--- cell ---")
        runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            out.appendLine("dataState=${tm.dataState}")
            out.appendLine("networkType=${tm.dataNetworkType}")
            @Suppress("DEPRECATION")
            val cells = tm.allCellInfo
            out.appendLine("count=${cells?.size ?: 0}")
            cells?.forEachIndexed { i, c ->
                out.appendLine("cell[$i]=${c.javaClass.simpleName} registered=${c.isRegistered}")
            }
        }.onFailure { out.appendLine("error=${it.javaClass.simpleName}: ${it.message}") }

        out.appendLine()
        out.appendLine("--- sim config ---")
        for (k in listOf("gps", "cell", "wifi")) {
            out.appendLine("$k.enabled=${ctx.sharedPrefs.getBoolean("sim_${k}_enabled", false)}")
            out.appendLine("$k.v1=${ctx.sharedPrefs.getString("sim_${k}_v1", "")}")
            out.appendLine("$k.v2=${ctx.sharedPrefs.getString("sim_${k}_v2", "")}")
        }

        out.appendLine()
        out.appendLine("--- module ---")
        runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            MockServiceHelper.tryInitService(lm)
            out.appendLine("service_init=${MockServiceHelper.isServiceInit()}")
            out.appendLine("gnss_mock=${MockServiceHelper.isGnssMockStart(lm)}")
            val st = MockServiceHelper.getSensorStatus(lm)
            out.appendLine("sensor_status=" + (st?.keySet()?.joinToString(" ") { "$it=${st.get(it)}" } ?: "null"))
        }.onFailure { out.appendLine("error=${it.message}") }

        out.appendLine()
        out.appendLine("--- fused ---")
        out.appendLine(runCatching { FusedProbe.status(ctx) }.getOrElse { "error=${it.message}" })
        return out.toString()
    }
}
