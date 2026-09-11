package moe.fuqiuluo.portalex.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.databinding.FragmentSettingsBinding
import moe.fuqiuluo.portalex.ext.accuracy
import moe.fuqiuluo.portalex.ext.allowLandscape
import moe.fuqiuluo.portalex.ext.altitude
import moe.fuqiuluo.portalex.ext.binderSensorMock
import moe.fuqiuluo.portalex.ext.cadenceScale
import moe.fuqiuluo.portalex.ext.debug
import moe.fuqiuluo.portalex.ext.disableFusedProvider
import moe.fuqiuluo.portalex.ext.disableWifiScan
import moe.fuqiuluo.portalex.ext.loopBroadcastlocation
import moe.fuqiuluo.portalex.ext.minSatelliteCount
import moe.fuqiuluo.portalex.ext.needDowngradeToCdma
import moe.fuqiuluo.portalex.ext.needOpenSELinux
import moe.fuqiuluo.portalex.ext.sensorGridHz
import moe.fuqiuluo.portalex.ext.reportDuration

import moe.fuqiuluo.portalex.ext.shiftAboveIme
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel
import moe.fuqiuluo.portalex.ui.viewmodel.SettingsViewModel

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val settingsViewModel =
            ViewModelProvider(this)[SettingsViewModel::class.java]

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val context = requireContext()
        binding.selinuxSwitch.isChecked = context.needOpenSELinux
        binding.selinuxSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.needOpenSELinux = isChecked
                showToast(if (isChecked) "已开启SELinux" else "已关闭SELinux")
            }
        })

        binding.altitudeValue.text = "%.2f米".format(context.altitude)
        binding.speedValue.text = "%.2f米/秒".format(context.speed)
        binding.accuracyValue.text = "%.2f米".format(context.accuracy)
        binding.reportDurationValue.text = "%dms".format(context.reportDuration)
        binding.sensorGridValue.text = context.sensorGridHz.let { if (it <= 0) "自动" else "${it}Hz" }
        binding.cadenceScaleValue.text = "%.2f×".format(context.cadenceScale)
        binding.satelliteCountValue.text = "%d颗".format(context.minSatelliteCount)

        binding.altitudeLayout.setOnClickListener {
            showDialog("设置海拔高度", binding.altitudeValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("海拔高度不合法")
                    return@showDialog
                } else if (value > 10000) {
                    showToast("海拔高度不能超过10000米")
                    return@showDialog
                }
                context.altitude = value
                binding.altitudeValue.text = "%.2f米".format(value)
            }
        }

        binding.speedLayout.setOnClickListener {
            showDialog("设置速度", binding.speedValue.text.toString().let { it.substring(0, it.length - 3) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("速度不合法")
                    return@showDialog
                } else if (value > 1000) {
                    showToast("速度不能超过1000米/秒")
                    return@showDialog
                }
                context.speed = value
                binding.speedValue.text = "%.2f米/秒".format(value)
            }
        }

        binding.accuracyLayout.setOnClickListener {
            showDialog("设置精度", binding.accuracyValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toFloatOrNull()
                if (value == null || value < 0.0) {
                    Toast.makeText(context, "精度不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "精度不能超过1000米", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.accuracy = value
                binding.accuracyValue.text = "%.2f米".format(value)
            }
        }

        binding.debugSwitch.isChecked = context.debug
        binding.debugSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.debug = isChecked
                showToast(if (isChecked) "已开启调试模式" else "已关闭调试模式")
                updateRemoteConfig()
            }
        })

        // 允许获取当前位置（新）：语义已定为「允许并注入」——不再提供拦截开关。
        // 开关保留在设置页仅作说明（布局里 checked=true / enabled=false），不再写任何配置。

        // 允许注册位置监听器：语义已定为「允许并注入」——持续拒绝回调在真机上不存在，
        // 本身就是特征；开关保留在设置页仅作说明（布局里 checked=true / enabled=false）。

        binding.dfusedSwitch.isChecked = context.disableFusedProvider
        binding.dfusedSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.disableFusedProvider = isChecked
                showToast(if (isChecked) "已禁用FusedProvider" else "已启用FusedProvider")
                updateRemoteConfig()
            }
        })

        binding.cdmaSwitch.isChecked = context.needDowngradeToCdma
        binding.cdmaSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.needDowngradeToCdma = isChecked
                showToast(if (isChecked) "已降级为CDMA" else "已取消降级为CDMA")
                updateRemoteConfig()
            }
        })

        // 「传感器模拟」开关已移除：传感器 hook 恒安装（仅由 LSPosed 作用域决定是否注入），
        // 偏好项从未被模块读取——留着就是一个骗人的开关。
        //
        // 「Binder 外周传感器模拟」（实验性，默认关）：打开后模拟改由 system_server 侧的
        // 原生注入层在系统框架层完成——目标应用一个 hook 都不装，也不依赖底层传感器是
        // 否在工作。开关下发到系统侧失败（原生层挂不上）时会明确提示，不做假成功。
        binding.binderSensorMockSwitch.isChecked = requireContext().binderSensorMock
        binding.binderSensorMockSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().binderSensorMock = isChecked
            showToast(if (isChecked) "已开启外周传感器模拟" else "已关闭外周传感器模拟")
            updateRemoteConfig()
        }

        // 「步频倍率」：微调步频↔速度，默认 1.0；整数或小数都可
        binding.cadenceScaleLayout.setOnClickListener {
            showDialog("步频倍率（1=不调整）", "%.2f".format(context.cadenceScale)) {
                val v = it.trim().toDoubleOrNull()
                if (v == null || v <= 0.0 || v > 10.0) {
                    Toast.makeText(context, "请输入 0.2 ~ 3.0 的数值", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                requireContext().cadenceScale = v.toFloat()
                binding.cadenceScaleValue.text = "%.2f×".format(requireContext().cadenceScale)
                showToast("步频倍率：%.2f×（同速度下步频×%.2f）".format(requireContext().cadenceScale, requireContext().cadenceScale))
                updateRemoteConfig()
            }
        }

        // 「注入栅格分辨率」：0=自动（跟随框架采用值）；也可填任意 Hz（钳 20~400）
        binding.sensorGridLayout.setOnClickListener {
            val cur = context.sensorGridHz
            showDialog("注入栅格分辨率（Hz，0=自动）", if (cur <= 0) "0" else cur.toString()) {
                val v = it.trim().toIntOrNull()
                if (v == null || v < 0) {
                    Toast.makeText(context, "请输入 0 或 20~400 的整数", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                requireContext().sensorGridHz = v
                binding.sensorGridValue.text = requireContext().sensorGridHz.let {
                    if (it <= 0) "自动" else "${it}Hz"
                }
                showToast(if (v <= 0) "栅格：自动（跟随框架采用值）" else "栅格：${requireContext().sensorGridHz}Hz")
                updateRemoteConfig()
            }
        }

        binding.reportDurationLayout.setOnClickListener {
            showDialog("设置上报间隔", binding.reportDurationValue.text.toString().let {
                it.substring(0, it.length - 2)
            }) {
                val value = it.toIntOrNull()
                // 下限 1ms：0 会让摇杆/播放循环 delay(0) 空转并触发除零
                if (value == null || value < 1) {
                    Toast.makeText(context, "上报间隔不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "上报间隔不能大于1s", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.reportDuration = value
                binding.reportDurationValue.text = "%dms".format(value)
            }
        }

        binding.satelliteCountLayout.setOnClickListener {
            showDialog("设置最小模拟卫星数量", binding.satelliteCountValue.text.toString().let {
                it.substring(0, it.length - 1)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(context, "数量不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 35) {
                    Toast.makeText(context, "卫星数量不能超过35", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.minSatelliteCount = value
                binding.satelliteCountValue.text = "%d颗".format(value)
                updateRemoteConfig()
            }
        }

        binding.disableWlanScanSwitch.isChecked = requireContext().disableWifiScan
        binding.disableWlanScanSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().disableWifiScan = isChecked
            with(mockServiceViewModel) {
                val lm = locationManager
                if (lm == null) {
                    showToast("定位服务加载异常，无法切换WLAN扫描")
                    return@setOnCheckedChangeListener
                }
                if (isChecked) {
                    if(!MockServiceHelper.startWifiMock(lm)) {
                        showToast("禁用WLAN扫描失败: 无法连接到系统服务")
                    }
                } else {
                    if(!MockServiceHelper.stopWifiMock(lm)) {
                        showToast("启用WLAN扫描失败: 无法连接到系统服务")
                    }
                }
            }
        }

        binding.loopBroadcastLocationSwitch.isChecked = requireContext().loopBroadcastlocation
        binding.loopBroadcastLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().loopBroadcastlocation = isChecked
        }

        binding.allowLandscapeSwitch.isChecked = requireContext().allowLandscape
        binding.allowLandscapeSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().allowLandscape = isChecked
            // 立即生效：MainActivity 是 configChanges 的单 Activity，不会重建
            (activity as? MainActivity)?.applyOrientationPreference()
        }
        return root
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRemoteConfig() {
        val context = requireContext()
        with(mockServiceViewModel) {
            val lm = locationManager
            if (lm == null) {
                showToast("定位服务加载异常，配置未同步")
                return
            }
            // 三态结果：成功 / 没握手 / 系统侧拒绝 —— 后者绝不能说"成功"（那是假成功）
            showToast(ConfigSync.push(context, lm).message(context))
        }
    }

    @SuppressLint("MissingInflatedId")
    private fun showDialog(titleText: String, valueText: String, handler: (String) -> Unit) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_input, null)

        val title = dialogView.findViewById<TextView>(R.id.title)
        title.text = titleText

        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(valueText)

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        val dialog = builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                handler(value.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
        dialog.shiftAboveIme(requireActivity().window.decorView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}