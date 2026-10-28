package moe.fuqiuluo.portalex.debug

import android.app.Activity
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import moe.fuqiuluo.portalex.ext.allowLandscape
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.ext.suPath
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.ui.mock.HistoricalRoute
import moe.fuqiuluo.portalex.ext.jsonHistoricalRoutes
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
                "test" -> TestReport.build(this)
                "start" -> startMock()
                "stop" -> stopMock()
                "set" -> setCommand()
                "route" -> routeTest()
                "point" -> pointTest()
                "rocker" -> rockerTest()
                "apis" -> apiTest()
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

    private fun startMock(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "location_manager=null"
        runCatching { MockServiceHelper.tryInitService(lm) }
        val ok = runCatching {
            MockServiceHelper.tryOpenMock(lm, speed = 3.05, altitude = 80.0, accuracy = 25f)
        }.getOrDefault(false)
        // 完整模拟：位置会话 + GNSS 状态流 + Wi-Fi 信息开关
        val gnss = runCatching { MockServiceHelper.startGnssMock(lm) }.getOrDefault(false)
        val wifi = runCatching { MockServiceHelper.startWifiMock(lm) }.getOrDefault(false)
        return "start=$ok gnss=$gnss wifi=$wifi service_init=${MockServiceHelper.isServiceInit()}"
    }

    private fun stopMock(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "location_manager=null"
        val ok = runCatching { MockServiceHelper.tryCloseMock(lm) }.getOrDefault(false)
        return "stop=$ok"
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
        intent.getStringExtra("landscape")?.let { value ->
            val on = value == "1" || value.equals("on", true) || value.equals("true", true)
            ctx.allowLandscape = on
            out.appendLine("landscape=$on")
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

    /**
     * 单点测试：把一个坐标当作「单点路线」下发，验证位置通道是否还活着。
     * 单点在模块侧会走 updateCoordinate 分支（不依赖播放），所以它能区分
     * 「命令通道坏了」和「路线推进坏了」。
     */
    private fun pointTest(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "location_manager=null"
        runCatching { MockServiceHelper.tryInitService(lm) }
        val started = runCatching { MockServiceHelper.tryOpenMock(lm, 3.05, 80.0, 25f) }.getOrDefault(false)
        val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: 23.52517
        val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: 113.60646
        val uploaded = MockServiceHelper.setRoute(lm, doubleArrayOf(lat), doubleArrayOf(lon))
        Thread.sleep(800)
        val loc = MockServiceHelper.getLocation(lm)
        val motion = MockServiceHelper.getSensorStatus(lm)?.getBundle("motion")
        return "started=$started uploaded=$uploaded target=$lat,$lon loc=$loc motion=$motion"
    }

    /**
     * 摇杆测试：按住某个方位 2 秒，看模块是否真的在推进（排查“摇杆无效”）。
     */
    private fun rockerTest(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "location_manager=null"
        runCatching { MockServiceHelper.tryInitService(lm) }
        val started = runCatching { MockServiceHelper.tryOpenMock(lm, 3.05, 80.0, 25f) }.getOrDefault(false)
        val bearing = intent.getStringExtra("bearing")?.toDoubleOrNull() ?: 90.0
        val before = MockServiceHelper.getLocation(lm)
        val ok = MockServiceHelper.setRocker(lm, true, bearing)
        Thread.sleep(2000)
        val after = MockServiceHelper.getLocation(lm)
        MockServiceHelper.setRocker(lm, false, bearing)
        return "started=$started setRocker=$ok bearing=$bearing before=$before after=$after"
    }

    /**
     * 各类系统定位 API 的并列测试：看哪条路径能拿到（模拟）位置。
     * 覆盖：getLastKnownLocation / requestLocationUpdates（多 provider）/ requestSingleUpdate /
     * getCurrentLocation（API 30+）/ FusedLocationProviderClient（Play Services）。
     */
    private fun apiTest(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "location_manager=null"
        runCatching { MockServiceHelper.tryInitService(lm) }
        val out = StringBuilder("=== location apis ===\n")
        out.appendLine("sdk=" + android.os.Build.VERSION.SDK_INT)
        out.appendLine("mock_start=" + runCatching { MockServiceHelper.isMockStart(lm) }.getOrDefault(false))
        out.appendLine("providers=" + runCatching { lm.allProviders }.getOrDefault(emptyList<Any>()))
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.PASSIVE_PROVIDER)
        }
        providers.forEach { p ->
            out.appendLine("enabled[" + p + "]=" + runCatching { lm.isProviderEnabled(p) }.getOrDefault(false))
            out.appendLine("lastKnown[" + p + "]=" + runCatching { lm.getLastKnownLocation(p) }
                .getOrNull()?.let { it.latitude.toString() + "," + it.longitude })
        }

        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        fun bump(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        // 回调必须投递到**非主线程**的 looper：本方法在主线程上 sleep 等结果，
        // 用主线程 looper 收帧会被自己的 sleep 卡住（第一次测出的 callbacks={} 就是这个假阴性）。
        val callbackThread = android.os.HandlerThread("api-test")
        callbackThread.start()
        val callbackLooper = callbackThread.looper
        val multiListener = object : LocationListener {
            override fun onLocationChanged(location: Location) { bump("multi") }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("平台已废弃") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        providers.forEach { p ->
            val ok = runCatching {
                lm.requestLocationUpdates(p, 200L, 0f, multiListener, callbackLooper)
            }.isSuccess
            out.appendLine("requestUpdates[" + p + "]=" + ok)
        }

        val singleListener = object : LocationListener {
            override fun onLocationChanged(location: Location) { bump("single") }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("平台已废弃") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        // 频率策略验证：同一 provider 上两个不同期望间隔的订阅
        val fastListener = object : LocationListener {
            override fun onLocationChanged(location: Location) { bump("fast50") }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("平台已废弃") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val slowListener = object : LocationListener {
            override fun onLocationChanged(location: Location) { bump("slow2000") }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("平台已废弃") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 50L, 0f, fastListener, callbackLooper) }
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, slowListener, callbackLooper) }

        val singleOk = runCatching {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, singleListener, callbackLooper)
        }.isSuccess
        out.appendLine("requestSingleUpdate[gps]=" + singleOk)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val currentOk = runCatching {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null,
                    java.util.concurrent.Executors.newSingleThreadExecutor()) { location ->
                    if (location != null) bump("getCurrentLocation")
                }
            }.isSuccess
            out.appendLine("getCurrentLocation[gps]=" + currentOk)
        }

        val fused = runCatching {
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        }.getOrNull()
        if (fused != null) {
            runCatching {
                fused.lastLocation.addOnSuccessListener { location ->
                    out.appendLine("fused.lastLocation=" + (location?.let { it.latitude.toString() + "," + it.longitude }))
                }
            }
            runCatching {
                fused.requestLocationUpdates(
                    com.google.android.gms.location.LocationRequest.create()
                        .setInterval(200L).setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY),
                    object : com.google.android.gms.location.LocationCallback() {
                        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                            if (result.lastLocation != null) bump("fused")
                        }
                    },
                    android.os.Looper.getMainLooper()
                )
            }
        } else {
            out.appendLine("fused_client=null")
        }

        Thread.sleep(4000)
        runCatching { lm.removeUpdates(multiListener) }
        runCatching { lm.removeUpdates(singleListener) }
        runCatching { lm.removeUpdates(fastListener) }
        runCatching { lm.removeUpdates(slowListener) }
        runCatching { fused?.removeLocationUpdates(object : com.google.android.gms.location.LocationCallback() {}) }
        out.appendLine("callbacks=" + counts)
        callbackThread.quitSafely()
        return out.toString()
    }

    private fun routeTest(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "location_manager=null"
        val routes = HistoricalRoute.parseList(sharedPrefs.getString("routes", "").orEmpty())
        if (routes.isEmpty()) return "routes=empty"
        val route = routes.firstOrNull { it.route.size >= 2 } ?: return "routes=no-multipoint"
        runCatching { MockServiceHelper.tryInitService(lm) }
        val started = runCatching { MockServiceHelper.tryOpenMock(lm, 3.05, 80.0, 25f) }.getOrDefault(false)
        if (!started) return "start=false"
        val lats = route.route.map { it.first }.toDoubleArray()
        val lons = route.route.map { it.second }.toDoubleArray()
        val uploaded = MockServiceHelper.setRoute(lm, lats, lons)
        val playing = MockServiceHelper.setRoutePlaying(lm, true)
        Thread.sleep(1500)
        val loc = MockServiceHelper.getLocation(lm)
        val motion = MockServiceHelper.getSensorStatus(lm)?.getBundle("motion")
        MockServiceHelper.setRoutePlaying(lm, false)
        return "route=${route.name} points=${lats.size} uploaded=$uploaded playing=$playing loc=$loc motion=$motion"
    }

    companion object {
        private const val TAG = "PortalDebug"
        private const val HELP = """
PortalEX root debug interface
=============================
Entry:
  adb shell su -c 'am start -n moe.fuqiuluo.portalex/.debug.DebugCommandActivity --es cmd <cmd>'

Commands:
  status
      Dump app-side config, network, Wi-Fi, dev/adb flags and full sensor_status.

  start
      Start full location simulation (speed 3.05, altitude 80, accuracy 25).

  stop
      Stop the simulation session.

  test
      Dump the last rendered text of the Test page.
      Requires opening PortalEX -> Test once after the app starts.

  help
      Print this document.

  set
      Change simulation switches. Multiple flags may be combined:
        --es dev_mode 0|1        close/open developer options entry
        --es gps on|off          start/stop GNSS mock
        --es wifi on|off         start/stop WLAN mock
        --es cell on|off         enable/disable CellInfo mock
        --es ssid NAME           set fake Wi-Fi SSID

Examples:
  su -c 'am start -n moe.fuqiuluo.portalex/.debug.DebugCommandActivity \
      --es cmd status'
  su -c 'am start -n moe.fuqiuluo.portalex/.debug.DebugCommandActivity \
      --es cmd set --es gps on --es wifi on --es cell on'
  su -c 'am start -n moe.fuqiuluo.portalex/.debug.DebugCommandActivity \
      --es cmd set --es dev_mode 0'
  su -c 'am start -n moe.fuqiuluo.portalex/.debug.DebugCommandActivity \
      --es cmd test'

Output:
  adb logcat -s PortalDebug
  /sdcard/Android/data/moe.fuqiuluo.portalex/files/portal_debug.txt
  (status/set/test/help all write the same file; last command wins)

Access:
  Component is not exported. Only root/adb root can start it.
  Non-root adb gets SecurityException: not exported.
  Ordinary apps cannot invoke it.

Notes:
  - test builds a fresh report; no need to open the Test page.
  - gps/wifi/cell flags also update sim_* preferences.
  - dev_mode=0 restores current adb_enabled value to keep USB debugging.
"""
    }
}
