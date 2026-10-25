package moe.fuqiuluo.portalex.debug

import android.app.Activity
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.ext.suPath
import moe.fuqiuluo.portalex.service.MockServiceHelper
import java.io.File

/**
 * 仅 adb/root 可用的调试入口。
 *
 * 组件在 Manifest 中是 exported=false：普通应用显式启动只会得到
 * ActivityNotFoundException，表现与“组件不存在”一致。
 *
 * 用法：
 *   adb shell su -c 'am start -n moe.fuqiuluo.portalex/.debug.DebugCommandActivity \
 *       --es cmd status'
 *   adb shell su -c 'am start ... --es cmd set --es gps on --es wifi off'
 *
 * 结果同时写入 logcat（PortalDebug）和
 * /sdcard/Android/data/moe.fuqiuluo.portalex/files/portal_debug.txt
 */
class DebugCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmd = intent.getStringExtra("cmd") ?: "status"
        try {
            val text = when (cmd) {
                "set" -> setCommand()
                "help" -> HELP
                else -> status()
            }
            Log.i(TAG, text)
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "portal_debug.txt").writeText(text)
            if (intent.getBooleanExtra("toast", false)) {
                Toast.makeText(this, "PortalDebug: $cmd", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "debug command failed", t)
            File(getExternalFilesDir(null) ?: filesDir, "portal_debug.txt")
                .writeText("ERROR: ${t.stackTraceToString()}")
        } finally {
            finishAndRemoveTask()
        }
    }

    private fun status(): String {
        val ctx = this
        val s = StringBuilder()
        s.appendLine("=== PortalEX debug ===")
        s.appendLine("package=${packageName}")
        s.appendLine("sdk=${android.os.Build.VERSION.SDK_INT}")
        s.appendLine("dev_mode=${Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1)}")
        s.appendLine("adb_enabled=${Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, -1)}")
        appendNetwork(s)
        s.appendLine("--- sim prefs ---")
        for (k in listOf("gps", "cell", "wifi")) {
            s.appendLine("$k.enabled=${ctx.sharedPrefs.getBoolean("sim_${k}_enabled", false)}")
            s.appendLine("$k.v1=${ctx.sharedPrefs.getString("sim_${k}_v1", "")}")
            s.appendLine("$k.v2=${ctx.sharedPrefs.getString("sim_${k}_v2", "")}")
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        s.appendLine("--- module commands ---")
        if (lm != null) {
            runCatching { MockServiceHelper.tryInitService(lm) }
            s.appendLine("service_init=${MockServiceHelper.isServiceInit()}")
            runCatching { s.appendLine("gnss_mock=${MockServiceHelper.isGnssMockStart(lm)}") }
            runCatching {
                val st = MockServiceHelper.getSensorStatus(lm)
                s.appendLine("sensor_status=" + (st?.keySet()?.joinToString(" ") { k ->
                    "$k=${st.get(k)}"
                } ?: "null"))
            }
        }
        s.appendLine()
        s.appendLine(HELP)
        return s.toString()
    }

    private fun appendNetwork(s: StringBuilder) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        s.appendLine("network.wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false}")
        s.appendLine("network.cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false}")
        @Suppress("DEPRECATION")
        s.appendLine("network.type=${cm.activeNetworkInfo?.type} ${cm.activeNetworkInfo?.typeName}")
        val wm = getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wm != null) {
            @Suppress("DEPRECATION")
            val wi = wm.connectionInfo
            s.appendLine("wifi.enabled=${wm.isWifiEnabled}")
            s.appendLine("wifi.ssid=${wi?.ssid}")
            s.appendLine("wifi.bssid=${wi?.bssid}")
            s.appendLine("wifi.rssi=${wi?.rssi}")
        }
    }

    private fun setCommand(): String {
        val ctx = this
        val out = StringBuilder("=== set ===\n")
        intent.getStringExtra("dev_mode")?.let { value ->
            val v = if (value == "0" || value.equals("off", true) || value.equals("false", true)) 0 else 1
            val adb = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
            val cmd = if (v == 0) {
                "settings put global development_settings_enabled 0; settings put global adb_enabled $adb"
            } else "settings put global development_settings_enabled 1"
            val rc = runCatching {
                ProcessBuilder(ctx.suPath, "-c", cmd).start().waitFor()
            }.getOrDefault(-1)
            out.appendLine("dev_mode=$v rc=$rc")
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            out.appendLine("location_manager=null")
            return out.toString()
        }
        fun flag(name: String): Boolean? = when (intent.getStringExtra(name)?.lowercase()) {
            "1", "on", "true", "yes" -> true
            "0", "off", "false", "no" -> false
            else -> null
        }
        flag("gps")?.let { on ->
            ctx.sharedPrefs.edit().putBoolean("sim_gps_enabled", on).apply()
            if (on) MockServiceHelper.startGnssMock(lm) else MockServiceHelper.stopGnssMock(lm)
            out.appendLine("gps=$on")
        }
        flag("wifi")?.let { on ->
            ctx.sharedPrefs.edit().putBoolean("sim_wifi_enabled", on).apply()
            if (on) MockServiceHelper.startWifiMock(lm) else MockServiceHelper.stopWifiMock(lm)
            out.appendLine("wifi=$on")
        }
        flag("cell")?.let { on ->
            ctx.sharedPrefs.edit().putBoolean("sim_cell_enabled", on).apply()
            MockServiceHelper.setSimConfig(lm, "cell", on, "460", "00")
            out.appendLine("cell=$on")
        }
        intent.getStringExtra("ssid")?.let { ssid ->
            ctx.sharedPrefs.edit().putString("sim_wifi_v1", ssid).apply()
            MockServiceHelper.setSimConfig(lm, "wifi", true, ssid, ctx.sharedPrefs.getString("sim_wifi_v2", "02:00:00:00:00:00").orEmpty())
            out.appendLine("ssid=$ssid")
        }
        return out.toString()
    }

    companion object {
        private const val TAG = "PortalDebug"
        private const val HELP = """
commands:
  --es cmd status
  --es cmd set [--es dev_mode 0|1] [--es gps on|off] [--es wifi on|off] [--es cell on|off] [--es ssid NAME]
result: logcat -s PortalDebug
file: /sdcard/Android/data/moe.fuqiuluo.portalex/files/portal_debug.txt
"""
    }
}
