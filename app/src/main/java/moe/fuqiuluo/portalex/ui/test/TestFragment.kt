package moe.fuqiuluo.portalex.ui.test

import moe.fuqiuluo.portalex.ext.fusedMode
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentTestBinding
import moe.fuqiuluo.portalex.ext.accuracy
import moe.fuqiuluo.portalex.ext.allowLandscape
import moe.fuqiuluo.portalex.ext.cadenceMock
import moe.fuqiuluo.portalex.ext.orientationMock
import moe.fuqiuluo.portalex.ext.minSatelliteCount
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.service.ImuProbe
import moe.fuqiuluo.portalex.service.StepProbe
import moe.fuqiuluo.portalex.service.StepTickProbe
import moe.fuqiuluo.portalex.ui.camera.CameraMockRuntime
import moe.fuqiuluo.portalex.ui.camera.CameraMockStore
import moe.fuqiuluo.portalex.ui.camera.cameraMockEnabled
import moe.fuqiuluo.portalex.ui.camera.cameraMockTagFilter
import moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel

/**
 * Test：数值总览页。
 *
 * 只做一件事——把**两侧的原始数值**摊开，便于对着目标应用看到的数比：
 *  · 系统侧（system_server，权威）：注入层是否装载、压制的真实事件数、**实际发出的步事件
 *    换算出的步频**、学到的 type→handle 映射、运动学量（速度/朝向/移动判定/步数）；
 *  · 应用侧（本进程）：偏好设置与模拟会话是否在跑。
 *
 * 为什么需要它：排查"应用显示 277 步/分、理论只有 178"这类问题时，最需要知道的是
 * **意图步频**（按设定速度算）与**实际发出的步频**（模块真的推了多少）是否一致——
 * 一致就说明问题在应用侧的算法，不一致才轮到模块。此前只能靠 adb + logcat 拼，
 * 这个页面把它变成一屏。
 */
class TestFragment : Fragment() {

    private var _binding: FragmentTestBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private var refreshJob: Job? = null
    private var gpsListener: LocationListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Test 页的 CellInfo 探针需要 READ_PHONE_STATE；只在进入本页时申请。
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.READ_PHONE_STATE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 0x51)
        }

        // "普通应用视角"探针：真订阅步数传感器（进入本页即开始，离开即停）
        runCatching { StepProbe.start(requireContext().applicationContext) }
            .onFailure { android.util.Log.w("TestFragment", "StepProbe start failed", it) }
        // 期望 1 秒更新间隔的监听器测试：专给"同刻双份 / 周期性步数尖峰"定责用
        runCatching { StepTickProbe.start(requireContext().applicationContext) }
            .onFailure { android.util.Log.w("TestFragment", "StepTickProbe start failed", it) }
        // IMU 平滑度探针：把"是否平滑"变成可读的数（|Δ| 中位/最大 + accel=gravity+linear 残差）
        runCatching { ImuProbe.start(requireContext().applicationContext) }
            .onFailure { android.util.Log.w("TestFragment", "ImuProbe start failed", it) }
        // Test 页自己订阅一次 GPS，让 getLastKnownLocation / gps.last 有真实注入帧可看。
        runCatching {
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            gpsListener = listener
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        }.onFailure { android.util.Log.w("TestFragment", "GPS probe subscribe failed", it) }
        if (refreshJob?.isActive == true) return
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refresh()
                // 2s：这些字符串是人读的，1s 刷新只会把 system_server 的诊断查询放大一倍。
                delay(2000)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        // 离开本页即停掉"普通应用视角"探针（真订阅，别在后台白耗）
        runCatching { StepProbe.stop() }
        runCatching { StepTickProbe.stop() }
        runCatching { ImuProbe.stop() }
        gpsListener?.let {
            runCatching {
                (requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                    .removeUpdates(it)
            }
        }
        gpsListener = null
        super.onPause()
    }

    private suspend fun refresh() {
        val ctx = context ?: return
        val text = buildString {
            appendLine(moe.fuqiuluo.portalex.debug.TestReport.summary(ctx))
            appendLine("═══ 应用侧（本进程）═══")
            appendLine("包名           ${ctx.packageName}")
            appendLine("外周模拟(步频)  ${if (ctx.cadenceMock) "开" else "关"}")
            appendLine("外周模拟(朝向)  ${if (ctx.orientationMock) "开" else "关"}")
            appendLine("设定速度       ${"%.2f".format(ctx.speed)} m/s")
            appendLine("最大上报间隔   ${ctx.reportDuration} ms")
            appendLine("模拟精度/星数  ${"%.1f".format(ctx.accuracy)} m / ${ctx.minSatelliteCount}")
            appendLine("允许横屏       ${ctx.allowLandscape}")
            appendLine("服务已握手     ${MockServiceHelper.isServiceInit()}")
            appendLine("会话运行中     ${mockServiceViewModel.isServiceStart()}")
            appendLine("作用域         LSPosed 决定；本页只统计系统侧数据")

            appendLine()
            appendLine("═══ 系统 API 原始探针（本进程）═══")
            runCatching {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                appendLine("活动网络       ${network ?: "无"}")
                appendLine("WIFI 传输      ${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false}")
                appendLine("CELLULAR 传输  ${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false}")
                @Suppress("DEPRECATION")
                val ni = cm.activeNetworkInfo
                appendLine("NetworkInfo    type=${ni?.type} ${ni?.typeName} state=${ni?.state}")
            }.onFailure { appendLine("Connectivity   ${it.message}") }

            runCatching {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val wi = wm.connectionInfo
                appendLine("Wi-Fi enabled  ${wm.isWifiEnabled}")
                appendLine("Wi-Fi SSID     ${wi?.ssid}")
                appendLine("Wi-Fi BSSID    ${wi?.bssid}")
                appendLine("Wi-Fi RSSI     ${wi?.rssi}")
                appendLine("Wi-Fi scans    ${runCatching { wm.scanResults?.size }.getOrDefault(-1)}")
            }.onFailure { appendLine("Wi-Fi          ${it.message}") }

            runCatching {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                appendLine("数据状态       ${tm.dataState}")
                appendLine("网络类型       ${tm.dataNetworkType}")
                @Suppress("DEPRECATION")
                appendLine("CellInfo       ${tm.allCellInfo?.joinToString { it.javaClass.simpleName } ?: "无"}")
            }.onFailure { appendLine("Telephony      ${it.message}") }

            runCatching {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                appendLine("GPS provider   ${lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)}")
                appendLine("GPS last       ${loc?.latitude}, ${loc?.longitude} / ${loc?.extras}")
            }.onFailure { appendLine("Location       ${it.message}") }

            appendLine()
            appendLine("═══ 网络模拟：配置 / 实测 ═══")
            appendLine("-- 配置 --")
            for (k in listOf("gps", "cell", "wifi")) {
                appendLine("$k.enabled=${ctx.sharedPrefs.getBoolean("sim_${k}_enabled", false)}")
                appendLine("$k.v1=${ctx.sharedPrefs.getString("sim_${k}_v1", "")}")
                appendLine("$k.v2=${ctx.sharedPrefs.getString("sim_${k}_v2", "")}")
            }
            appendLine("-- 实测 --")
            runCatching {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                @Suppress("DEPRECATION")
                val ni = cm.activeNetworkInfo
                appendLine("network.type=${ni?.type} ${ni?.typeName} state=${ni?.state}")
                appendLine("network.wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false}")
                appendLine("network.cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false}")
            }.onFailure { appendLine("network.error=${it.message}") }

            appendLine()
            appendLine("═══ GPS 状态码模拟：实测 ═══")
            runCatching {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                appendLine("gps.provider=${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                appendLine("gps.last=${loc?.latitude}, ${loc?.longitude}")
                appendLine("gps.extras=${loc?.extras?.keySet()?.joinToString { "$it=${loc.extras?.get(it)}" } ?: "null"}")
                appendLine("gps.satellites=${loc?.extras?.getInt("satellites", -1)}")
            }.onFailure { appendLine("gps.error=${it.message}") }

            appendLine()
            appendLine("═══ Cell 基站信息模拟：实测 ═══")
            runCatching {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                appendLine("cell.dataState=${tm.dataState}")
                appendLine("cell.networkType=${tm.dataNetworkType}")
                @Suppress("DEPRECATION")
                val cells = tm.allCellInfo
                appendLine("cell.count=${cells?.size ?: 0}")
                cells?.forEachIndexed { i, c ->
                    appendLine("cell[$i]=${c.javaClass.simpleName} registered=${c.isRegistered}")
                }
            }.onFailure { appendLine("cell.error=${it.javaClass.simpleName}: ${it.message}") }

            appendLine()
            appendLine("═══ Wi-Fi 信息模拟：实测 ═══")
            runCatching {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val wi = wm.connectionInfo
                appendLine("wifi.enabled=${wm.isWifiEnabled}")
                appendLine("wifi.ssid=${wi?.ssid}")
                appendLine("wifi.bssid=${wi?.bssid}")
                appendLine("wifi.rssi=${wi?.rssi}")
                appendLine("wifi.frequency=${wi?.frequency}")
                appendLine("wifi.scanCount=${runCatching { wm.scanResults?.size }.getOrDefault(-1)}")
            }.onFailure { appendLine("wifi.error=${it.message}") }

            appendLine()
            appendLine("═══ 融合定位：实测 ═══")
            appendLine(moe.fuqiuluo.portalex.service.FusedProbe.status(requireContext()))

            // 摄像头模拟是唯一注入目标应用的功能族，它的"有没有生效"只能靠 provider
            // 两侧的旁证看出来：谁在什么时候来取过清单、模块侧回报拦到了什么。
            // 真机日志常常拿不到，这一块就是那类问题的第一现场。
            val points = CameraMockStore.points(ctx)
            appendLine()
            appendLine("═══ 摄像头模拟 ═══")
            appendLine("开关           ${if (ctx.cameraMockEnabled) "开" else "关"}")
            appendLine("素材           ${points.size} 个视频点 / ${points.sumOf { it.videos.size }} 段视频")
            appendLine("标签筛选       ${ctx.cameraMockTagFilter.joinToString("、").ifEmpty { "（不筛选）" }}")
            appendLine(
                "当前表象位置   " + (CameraMockRuntime.position()?.let {
                    "%.6f, %.6f".format(it.first, it.second)
                } ?: "未知")
            )
            appendLine("── provider 访问（模块侧来取清单/素材）──")
            appendLine(CameraMockRuntime.dumpAccess())
            appendLine("── 模块侧回传（选中素材 / 起播秒 / 失败原因）──")
            appendLine(CameraMockRuntime.dumpReports())

            // 控制面（system_server 侧、对目标应用零注入）：谁在什么时候用了哪个摄像头
            val lmForCamera = mockServiceViewModel.locationManager
            appendLine("── 系统侧摄像头控制面（AppOps + CameraServiceProxy）──")
            appendLine(
                if (lmForCamera == null) "（定位服务未就绪，命令通道不可用）"
                else MockServiceHelper.getCameraEvents(lmForCamera) ?: "（系统侧无应答：模块未在 system_server 内活动？）"
            )

            val lm = mockServiceViewModel.locationManager
            if (lm == null) {
                appendLine()
                appendLine("!! 定位服务未就绪（权限未授予时 locationManager 为空），")
                appendLine("   无法向系统侧查询。请授予权限后重进本页。")
                return@buildString
            }

            val status = withContext(Dispatchers.IO) { MockServiceHelper.getSensorStatus(lm) }
            if (status == null) {
                appendLine()
                appendLine("!! 系统侧无应答（模块未在 system_server 内活动？）")
                return@buildString
            }

            appendLine()
            appendLine("═══ 系统侧（system_server，权威）═══")
            appendLine("实验开关       ${yn(status.getBoolean("flag"))}")
            appendLine("模拟会话       ${yn(status.getBoolean("mock_running"))}")
            appendLine("原生层装载     ${yn(status.getBoolean("native_ready"))}")
            appendLine("注入激活       ${yn(status.getBoolean("active"))}")
            // 静默失败记账（模块侧）：非 none 说明注入层/投递链正在降级运行 —— 比"功能没了"
            // 更难发现的那类故障，在这里必须一眼可见
            appendLine("静默失败计数   ${status.getString("diag") ?: "（旧版模块无此字段）"}")
            appendLine()
            appendLine("── 步频：意图 vs 实际 ──")
            appendLine("意图步频       ${status.getInt("cadence_intent")} 步/分（按实测速度算）")
            appendLine("设定速度       ${"%.2f".format(status.getDouble("configured_speed"))} m/s")
            appendLine("实测速度       ${"%.2f".format(status.getDouble("measured_speed"))} m/s")
            appendLine("移动判定       ${yn(status.getBoolean("moving"))}")
            appendLine("步数累计       ${status.getLong("steps_total")}")
            appendLine("开机总步数     ${status.getLong("steps_boot")}（我们推送的 STEP_COUNTER 值；本次会话起点 ${status.getLong("steps_base")}）")
            appendLine()
            appendLine("── 注入计数（原生层）──")
            appendLine(prettyNative(status.getString("native")))
            appendLine()
            appendLine("── 厂商私有传感器（监控，不接管）──")
            appendLine(status.getString("priv_sensors"))
            appendLine()
            appendLine("── 应用期望频率（框架采用值 / 客户端原始请求）──")
            appendLine(status.getString("sensor_rates"))
            appendLine()
            appendLine("── 融合定位（普通应用视角，通用接口问框架）──")
            // **不用模块私有通道**：这一项只用 LocationManager / Settings 问框架，
            // 与任何普通应用拿到的答案一致（本进程不在作用域内 ⇒ 这里是未注入的真值）。
            // 模块内部"钩子装没装上"另有一处（设置页的三态据此启用/禁用），本页不混进来看。
            appendLine(moe.fuqiuluo.portalex.service.FusedProbe.status(requireContext()))
            appendLine()
            appendLine("── 握手门禁（谁能换钥匙/发指令）──")
            appendLine(status.getString("portal_gate"))
            appendLine()
            appendLine("── 运行时投递通道（投递 100% 可控那条路）──")
            appendLine(prettyNative(status.getString("rt_channel")))
            appendLine()
            appendLine("── 普通应用视角（本进程真订阅，每 5s 轮询一次总步数）──")
            // 地图页那条 LocationManager 订阅的现场读数：这是"App 与第三方客户端收到同一份帧"
            // 的**最直接**证据（同一 tick 的注册拿到的是同一个注入对象）
            appendLine("位置订阅       ${moe.fuqiuluo.portalex.service.PortalLocationClient.statusLine()}")
            appendLine(moe.fuqiuluo.xposed.hooks.sensor.frozen.SystemSensorManagerHook.stepTraceText())
            appendLine(StepProbe.status())
            appendLine()
            appendLine("── 期望 1 秒更新间隔的监听器（逐事件定责）──")
            appendLine(StepTickProbe.status())
            appendLine()
            appendLine("── IMU 平滑度（逐事件 |Δ| 与恒等式）──")
            appendLine(ImuProbe.status())
            appendLine()
            // 体力与推进：迁移后都在 system_server（App 只回读）——所以这里是
            // "页面看到的"与"实跑的"是否同一个世界的唯一现场证据
            appendLine("── 体力（系统侧状态机）──")
            appendLine(status.getString("stamina") ?: "（旧版模块无此字段）")
            appendLine("参数已同步     ${yn(status.getBoolean("stamina_wire"))}")
            appendLine()
            appendLine("── 推进（系统侧引擎）──")
            appendLine(status.getString("motion") ?: "（旧版模块无此字段）")
            appendLine("路线进度       ${"%.0f".format(status.getDouble("route_travelled"))} / ${"%.0f".format(status.getDouble("route_distance"))} m")
            appendLine()
            appendLine("── 位置模拟 ──")
            appendLine("坐标           ${"%.6f".format(status.getDouble("lat"))}, ${"%.6f".format(status.getDouble("lon"))}")
            appendLine("海拔           ${"%.2f".format(status.getDouble("altitude"))} m")
            appendLine("朝向目标/帧    ${"%.1f".format(status.getDouble("bearing_target"))}° / ${"%.1f".format(status.getDouble("bearing_frame"))}°")
            appendLine("GNSS 模拟      ${yn(status.getBoolean("gnss_mock"))}")
        }
        withContext(Dispatchers.Main) {
            _binding?.testOutput?.text = text
        }
    }

    private fun yn(b: Boolean) = if (b) "是" else "否"

    /** 原生层 status 是一行长串：按空格拆开，每项一行，便于看 */
    private fun prettyNative(raw: String?): String {
        if (raw.isNullOrBlank()) return "（无）"
        return raw.split(' ').filter { it.isNotBlank() }.joinToString("\n") { "  $it" }
    }

    override fun onDestroyView() {
        refreshJob?.cancel()
        refreshJob = null
        _binding = null
        super.onDestroyView()
    }
}
