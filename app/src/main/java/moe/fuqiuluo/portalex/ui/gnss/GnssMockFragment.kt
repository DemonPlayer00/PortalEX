package moe.fuqiuluo.portalex.ui.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import moe.fuqiuluo.portalex.databinding.FragmentGnssMockBinding
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import android.os.Build
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.widget.SatelliteData
import moe.fuqiuluo.portalex.android.window.OverlayUtils
import moe.fuqiuluo.portalex.ext.enableAGPS
import moe.fuqiuluo.portalex.ext.enableGetFromLocation
import moe.fuqiuluo.portalex.ext.enableNMEA
import moe.fuqiuluo.portalex.ext.enableRequestGeofence
import moe.fuqiuluo.portalex.ext.gnssFlags
import moe.fuqiuluo.portalex.ext.gnssMockIntent
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.service.MockServiceHelper

class GnssMockFragment : Fragment() {
    private var _binding: FragmentGnssMockBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 检查所有请求的权限是否都被授予
        val allGranted = permissions.entries.all { it.value }

        if (allGranted) {
            Toast.makeText(requireContext(), R.string.gnss_permissions_granted, Toast.LENGTH_SHORT).show()
            setupGnssStatusCallback()
        } else {
            // 显示哪些权限被拒绝
            val deniedPermissions = permissions.filter { !it.value }.keys.joinToString()
            Toast.makeText(requireContext(), getString(R.string.gnss_permissions_denied, deniedPermissions), Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGnssMockBinding.inflate(inflater, container, false)
        val root: View = binding.root

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val permissionsToRequest = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val permissionsNeeded = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsNeeded.isEmpty()) {
            setupGnssStatusCallback()
        } else {
            requestMultiplePermissionsLauncher.launch(permissionsNeeded)
        }

        binding.switchRequestGeofence.isChecked = requireContext().enableRequestGeofence
        binding.switchRequestGeofence.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableRequestGeofence = isChecked
            Toast.makeText(requireContext(), R.string.gnss_restart, Toast.LENGTH_SHORT).show()
        }

        binding.switchGetFromLocation.isChecked = requireContext().enableGetFromLocation
        binding.switchGetFromLocation.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableGetFromLocation = isChecked
            Toast.makeText(requireContext(), R.string.gnss_restart, Toast.LENGTH_SHORT).show()
        }

        binding.switchEnableAgps.isChecked = requireContext().enableAGPS
        binding.switchEnableAgps.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableAGPS = isChecked
            Toast.makeText(requireContext(), R.string.gnss_restart, Toast.LENGTH_SHORT).show()
        }

        binding.switchEnableNmea.isChecked = requireContext().enableNMEA
        binding.switchEnableNmea.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableNMEA = isChecked
            Toast.makeText(requireContext(), R.string.gnss_restart, Toast.LENGTH_SHORT).show()
        }

        if (!::locationManager.isInitialized) {
            showToast(getString(R.string.gnss_location_service_failed))
            // 直接退出返回根布局，接下来的操作将不会执行！
            return root
        }
        // 逐星状态：多行文本，一行一颗卫星；空 = 全部交给模块随机
        binding.gnssGpsStatusCode.setText(
            requireContext().gnssFlags.joinToString("\n")
                .ifBlank { requireContext().sharedPrefs.getString("sim_gps_v1", "").orEmpty() }
        )
        binding.gnssGpsSatellites.setText(requireContext().sharedPrefs.getString("sim_gps_v2", "12"))
        refreshGnssButton()
        binding.switchGnssMock.setOnClickListener {
            val ctx = requireContext()
            if (ctx.gnssMockIntent) {
                ctx.gnssMockIntent = false
                tryCloseService(it as MaterialButton)
            } else {
                ctx.gnssMockIntent = true
                tryOpenService(it as MaterialButton)
            }
        }

        return root
    }

    /**
     * 打开 GNSS 模拟。
     *
     * 二类功能允许**伪启动**：用户意图（[moe.fuqiuluo.portalex.ext.gnssMockIntent]）
     * 一旦置位，按钮就保持"停用"态，哪怕这一刻模块还不能真正接管（路线模拟没开、
     * 注入层未就绪、悬浮窗权限缺失）。真正生效由系统侧 FeatureGate 判定，
     * 这里只负责把意图留住并在下次进入页面时补发。
     */
    private fun tryOpenService(button: MaterialButton) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            keepIntentOn(button, getString(R.string.gnss_overlay_required))
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            keepIntentOn(button, getString(R.string.gnss_injection_failed))
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                ConfigSync.push(requireContext(), locationManager)
                val started = MockServiceHelper.startGnssMock(locationManager)
                // 无论模块此刻是否接管，用户打开了就是打开了
                updateMockButtonState(button, getString(R.string.gnss_stop_mock), R.drawable.rounded_play_disabled_24)
                if (started) {
                    val flags = binding.gnssGpsStatusCode.text?.toString().orEmpty()
                        .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    val code = flags.firstOrNull() ?: ""
                    val sats = binding.gnssGpsSatellites.text?.toString().orEmpty()
                    requireContext().gnssFlags = flags
                    requireContext().sharedPrefs.edit().putString("sim_gps_v1", code).putString("sim_gps_v2", sats).apply()
                    MockServiceHelper.setSimConfig(locationManager, "gps", true, code, sats, flags.ifEmpty { null })
                    // 启动成功后自动重启GPS provider，稳定NMEA数据流
                    restartGpsProvider()
                } else {
                    showToast("已记住：开启路线模拟后生效")
                }
            } finally {
                button.isClickable = true
            }
        }
    }

    /** 意图已置位但暂时起不来：按钮保持"停用"态，只提示原因。 */
    private fun keepIntentOn(button: MaterialButton, reason: String) {
        updateMockButtonState(button, getString(R.string.gnss_stop_mock), R.drawable.rounded_play_disabled_24)
        showToast(reason + "（已记住，条件满足后生效）")
    }

    /**
     * 重启GPS Provider（模拟开关定位服务）
     * 通过清除辅助数据命令软重启GPS引擎，避免NMEA数据失效
     */
    private fun restartGpsProvider() {
        try {
            // 需要 android.permission.ACCESS_LOCATION_EXTRA_COMMANDS 权限（普通权限，默认授予）
            val success = locationManager.sendExtraCommand(
                LocationManager.GPS_PROVIDER,
                "delete_aiding_data",
                null
            )
            if (success) {
                Log.d("GnssMockFragment", "GPS模块已重置，辅助数据已清除")
                // 短暂等待GPS重新初始化
                Thread.sleep(200)

                // 注入网络时间，加速定位
                val timeResult = locationManager.sendExtraCommand(
                    LocationManager.GPS_PROVIDER,
                    "force_time_injection",
                    null
                )
                if (timeResult) {
                    Log.d("GnssMockFragment", "时间注入成功")
                } else {
                    Log.w("GnssMockFragment", "时间注入失败，可能设备不支持")
                }

                // 注入星历辅助数据（A-GPS）
                val xtraResult = locationManager.sendExtraCommand(
                    LocationManager.GPS_PROVIDER,
                    "force_xtra_injection",
                    null
                )
                if (xtraResult) {
                    Log.d("GnssMockFragment", "星历注入成功")
                } else {
                    Log.w("GnssMockFragment", "星历注入失败，可能设备不支持")
                }
            } else {
                Log.w("GnssMockFragment", "GPS重置命令执行失败，可能设备不支持")
            }
        } catch (e: SecurityException) {
            Log.e("GnssMockFragment", "缺少权限，无法重置GPS", e)
        } catch (e: Exception) {
            Log.e("GnssMockFragment", "重置GPS时发生异常", e)
        }
    }

    private fun tryCloseService(button: MaterialButton) {
        if (!MockServiceHelper.isServiceInit()) {
            updateMockButtonState(button, getString(R.string.gnss_start_mock), R.drawable.rounded_play_arrow_24)
            showToast(getString(R.string.gnss_injection_failed))
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isGnssMockStart(locationManager)) {
                        // 本来就没在跑：意图已清，按钮直接复位
                        updateMockButtonState(button, getString(R.string.gnss_start_mock), R.drawable.rounded_play_arrow_24)
                        return@withContext false
                    }

                    if (MockServiceHelper.stopGnssMock(locationManager)) {
                        val flags = binding.gnssGpsStatusCode.text?.toString().orEmpty()
                            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        val code = flags.firstOrNull() ?: ""
                        val sats = binding.gnssGpsSatellites.text?.toString().orEmpty()
                        MockServiceHelper.setSimConfig(locationManager, "gps", false, code, sats, flags.ifEmpty { null })
                        updateMockButtonState(button, getString(R.string.gnss_start_mock), R.drawable.rounded_play_arrow_24)
                        return@withContext true
                    } else {
                        showToast(getString(R.string.gnss_stop_failed))
                        return@withContext false
                    }
                }
            } finally {
                button.isClickable = true
            }
        }
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun refreshGnssButton() {
        // 以用户意图为准：模块侧暂时没接管（路线模拟未开等）也不把开关弹回去
        val intent = requireContext().gnssMockIntent
        val running = ::locationManager.isInitialized && MockServiceHelper.isGnssMockStart(locationManager)
        val on = intent || running
        binding.switchGnssMock.text = getString(if (on) R.string.gnss_stop_mock else R.string.gnss_start_mock)
        ContextCompat.getDrawable(
            requireContext(),
            if (on) R.drawable.rounded_play_disabled_24 else R.drawable.rounded_play_arrow_24
        )?.let { binding.switchGnssMock.icon = it }
    }

    private fun updateMockButtonState(button: MaterialButton, text: String, iconRes: Int) =
        lifecycleScope.launch(Dispatchers.Main) {
            button.text = text
            ContextCompat.getDrawable(requireContext(), iconRes)?.let {
                button.icon = it
            }
        }

    private fun setupGnssStatusCallback() {
        gnssStatusCallback = object: GnssStatus.Callback() {
            @SuppressLint("SetTextI18n")
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (_binding == null) return

                val satelliteCount = status.satelliteCount

                var inViewSatelliteCount = 0
                var inUseSatelliteCount = 0
                var signalStrength = arrayListOf<Float>()

                var gpsCount = 0
                var glonassCount = 0
                var beidouCount = 0

                val satellites = mutableListOf<SatelliteData>()
                for (i in 0 until satelliteCount) {
                    val svid = status.getSvid(i)
                    val carrierToNoise = status.getCn0DbHz(i)
                    val elevation = status.getElevationDegrees(i)
                    val azimuth = status.getAzimuthDegrees(i)
                    val usedInFix = status.usedInFix(i)

                    satellites.add(SatelliteData(
                        prn = svid,
                        snr = carrierToNoise,
                        elevation = elevation,
                        azimuth = azimuth,
                        hasAlmanac = false,
                        hasEphemeris = false,
                        usedInFix = usedInFix
                    ))

                    inViewSatelliteCount += 1
                    if (usedInFix) {
                        inUseSatelliteCount += 1
                        signalStrength.add(carrierToNoise)
                    }

                    when (svid) {
                        in 1..32 -> gpsCount += 1
                        in 65..96 -> glonassCount += 1
                        in 201..237 -> beidouCount += 1
                    }
                }

                val avgSignalStrength = signalStrength.average().toFloat()
                binding.signalStrengthRightItem.text = "${avgSignalStrength.toString().take(5)} dB-Hz"
                binding.inViewRightItem.text = "$inViewSatelliteCount"
                binding.inUseRightItem.text = "$inUseSatelliteCount"

                binding.gpsCount.text = "GPS: $gpsCount"
                binding.glonassCount.text = "GNS: $glonassCount"
                binding.beidouCount.text = "BD: $beidouCount"

                // 雷达只绘制前 40 颗；总数/使用数仍按完整 GnssStatus 统计。
                binding.satelliteRadaView.setSatellites(satellites.take(40))
            }
        }

        try {
            // Register the callback with the LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // For Android 11 and above
                locationManager.registerGnssStatusCallback(requireContext().mainExecutor, gnssStatusCallback!!)
            } else {
                // For Android versions before 11
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(gnssStatusCallback!!, null)
            }
        } catch (e: SecurityException) {
            Log.e("GnssMock", "registerGnssStatusCallback 失败: ${e.message}", e)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refreshGnssButton()
            // 意图在、模块没接管（上次失败或期间路线模拟重开过）→ 静默补发一次
            if (requireContext().gnssMockIntent &&
                ::locationManager.isInitialized &&
                !MockServiceHelper.isGnssMockStart(locationManager) &&
                MockServiceHelper.isServiceInit()
            ) {
                runCatching { MockServiceHelper.startGnssMock(locationManager) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        if (gnssStatusCallback != null)
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback!!)
    }
}