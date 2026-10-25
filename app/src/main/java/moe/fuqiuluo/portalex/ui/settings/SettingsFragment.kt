package moe.fuqiuluo.portalex.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.databinding.FragmentSettingsBinding
import moe.fuqiuluo.portalex.ext.allowLandscape
import moe.fuqiuluo.portalex.ext.altitude
import moe.fuqiuluo.portalex.ext.debug
import moe.fuqiuluo.portalex.ext.fusedMode
import moe.fuqiuluo.portalex.ext.disableWifiScan
import moe.fuqiuluo.portalex.ext.needDowngradeToCdma
import moe.fuqiuluo.portalex.ext.needOpenSELinux
import moe.fuqiuluo.portalex.ext.keepAliveInBackground
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.ext.suPath

import moe.fuqiuluo.portalex.ext.shiftAboveIme
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.service.MockKeepAliveService
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.xposed.utils.FusedMode
import moe.fuqiuluo.xposed.utils.PortalProtocol
import moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    @SuppressLint("SetTextI18n")
    /** 本机是否有融合定位（`refreshFusedState` 查得；无则整行禁用） */
    private var fusedAvailable = true

    /**
     * 查询系统侧的融合定位状态：决定本行**是否可用**，并把当前处置写在右侧（与其它设置项一致）。
     *
     * · 没有融合定位的机型 ⇒ 整行置灰 + 显示getString(R.string.not_applicable)，不做"看着能点其实没用"的假设置；
     * · 有 ⇒ 右侧显示当前档位（拒绝 / 放行（不推荐） / 伪装）；调试模式打开时把系统侧的
     *   hook 状态单行诊断显示在标题下（同时系统侧也会打一条 debug 日志）。
     */
    private fun refreshFusedState() {
        val ctx = context ?: return
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager
        if (lm == null) {
            setFusedRow(enabled = false, value = getString(R.string.not_applicable), desc = null)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { MockServiceHelper.getFusedState(lm) }.getOrNull()
            }
            if (_binding == null) return@launch
            if (state == null) {
                // 系统侧没应答（模块未生效/未重启）：保持可用，但如实说明
                setFusedRow(
                    enabled = true,
                    value = fusedLabel(ctx.fusedMode),
                    desc = getString(R.string.fused_no_response)
                )
                return@launch
            }
            fusedAvailable = state.getBoolean(PortalProtocol.Key.FUSED_AVAILABLE, false)
            val mode = FusedMode.sanitize(
                state.getInt(PortalProtocol.Key.FUSED_MODE, ctx.fusedMode)
            )
            setFusedRow(
                enabled = fusedAvailable,
                value = if (fusedAvailable) fusedLabel(mode) else getString(R.string.not_applicable),
                desc = if (!fusedAvailable) getString(R.string.fused_unavailable)
                else if (ctx.debug) state.getString(PortalProtocol.Key.FUSED_STATUS)
                else null
            )
        }
    }

    /** 更新本行的可用性/右侧值/说明（说明为空则用默认文案） */
    private fun setFusedRow(enabled: Boolean, value: String, desc: String?) {
        val b = _binding ?: return
        b.dfusedLayout.isEnabled = enabled
        b.dfusedLayout.isClickable = enabled
        b.dfusedLayout.alpha = if (enabled) 1f else 0.5f
        b.dfusedValue.text = value
        b.dfusedDesc.text = desc ?: getString(R.string.dfused_desc)
    }

    private fun fusedLabel(mode: Int): String = when (FusedMode.sanitize(mode)) {
        FusedMode.REJECT -> getString(R.string.fused_reject)
        FusedMode.ALLOW -> getString(R.string.fused_allow)
        else -> getString(R.string.fused_disguise)
    }

    private fun refreshLanguageValue() {
        val locales = AppCompatDelegate.getApplicationLocales()
        binding.languageValue.text = when {
            locales.isEmpty -> getString(R.string.language_system)
            locales.toLanguageTags().startsWith("zh") -> getString(R.string.language_zh)
            else -> getString(R.string.language_en)
        }
    }

    private fun showLanguageDialog() {
        val options = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_zh),
            getString(R.string.language_en)
        )
        val current = binding.languageValue.text.toString()
        val checked = options.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val locales = when (which) {
                    1 -> LocaleListCompat.forLanguageTags("zh-CN")
                    2 -> LocaleListCompat.forLanguageTags("en-US")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
                refreshLanguageValue()
                requireActivity().recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setDeveloperMode(enabled: Boolean) {
        val ctx = context ?: return
        val resolver = ctx.contentResolver
        val adbEnabled = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 1)
        }.getOrDefault(1)
        // 开关语义就是系统开发者模式本身；关闭后再把 adb_enabled 写回，
        // 尽量保住 MI6 的 USB 调试等已有设置。
        val cmd = if (enabled) {
            "settings put global development_settings_enabled 1"
        } else {
            "settings put global development_settings_enabled 0; " +
                "settings put global adb_enabled $adbEnabled"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ProcessBuilder(ctx.suPath, "-c", cmd).start().waitFor() == 0
                }.getOrDefault(false)
            }
            if (_binding == null) return@launch
            if (ok) {
                showToast(if (enabled) getString(R.string.dev_mode_on) else getString(R.string.dev_mode_off))
            } else {
                // 命令失败就回到真实值，不制造假成功
                val actual = runCatching {
                    Settings.Global.getInt(
                        resolver,
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                        1
                    ) != 0
                }.getOrDefault(true)
                binding.developerModeSwitch.setOnCheckedChangeListener(null)
                binding.developerModeSwitch.isChecked = actual
                binding.developerModeSwitch.setOnCheckedChangeListener { _, checked ->
                    setDeveloperMode(checked)
                }
                showToast(getString(R.string.dev_mode_failed))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refreshBatteryOptimizationState()
            refreshFusedState()
        }
    }

    /** 电池优化白名单状态：文案如实反映，不做假成功 */
    private fun refreshBatteryOptimizationState() {
        val ctx = context ?: return
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val exempt = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        binding.batteryOptimizationState.text = if (exempt) {
            getString(R.string.battery_added)
        } else {
            getString(R.string.battery_not_added)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val context = requireContext()
        binding.suPathValue.text = context.suPath
        binding.suPathLayout.setOnClickListener {
            showDialog(getString(R.string.su_path_title), context.suPath) { path ->
                val p = path.trim()
                if (p.isEmpty()) {
                    showToast(getString(R.string.su_path_invalid))
                    return@showDialog
                }
                context.suPath = p
                binding.suPathValue.text = context.suPath
                showToast(getString(R.string.su_path_updated))
            }
        }
        // 只有一个开发者模式开关：开关状态直接反映系统当前值。
        val developerModeEnabled = runCatching {
            Settings.Global.getInt(
                requireContext().contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                1
            ) != 0
        }.getOrDefault(true)
        binding.developerModeSwitch.isChecked = developerModeEnabled
        binding.developerModeSwitch.setOnCheckedChangeListener { _, checked ->
            setDeveloperMode(checked)
        }
        refreshLanguageValue()
        binding.languageLayout.setOnClickListener { showLanguageDialog() }

        binding.selinuxSwitch.isChecked = context.needOpenSELinux
        binding.selinuxSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.needOpenSELinux = isChecked
                showToast(if (isChecked) getString(R.string.selinux_on) else getString(R.string.selinux_off))
            }
        })

        binding.altitudeValue.text = getString(R.string.altitude_format, context.altitude)
        binding.speedValue.text = getString(R.string.speed_format, context.speed)
        binding.reportDurationValue.text = "%dms".format(context.reportDuration)

        binding.altitudeLayout.setOnClickListener {
            showDialog(getString(R.string.altitude), binding.altitudeValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast(getString(R.string.altitude_invalid))
                    return@showDialog
                } else if (value > 10000) {
                    showToast(getString(R.string.altitude_max))
                    return@showDialog
                }
                context.altitude = value
                binding.altitudeValue.text = getString(R.string.altitude_format, value)
            }
        }

        binding.speedLayout.setOnClickListener {
            showDialog(getString(R.string.rocker_speed), binding.speedValue.text.toString().let { it.substring(0, it.length - 3) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast(getString(R.string.speed_invalid))
                    return@showDialog
                } else if (value > 1000) {
                    showToast(getString(R.string.speed_max))
                    return@showDialog
                }
                context.speed = value
                binding.speedValue.text = getString(R.string.speed_format, value)
            }
        }

        binding.debugSwitch.isChecked = context.debug
        binding.debugSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.debug = isChecked
                showToast(if (isChecked) getString(R.string.debug_on) else getString(R.string.debug_off))
                updateRemoteConfig()
                // 融合处置那一行的说明位在调试模式下显示 hook 状态诊断 ⇒ 开关一变就要重画，
                // 否则会停在旧文案上（看着像"状态没变"）
                refreshFusedState()
            }
        })

        // 允许获取当前位置（新）：语义已定为「允许并注入」——不再提供拦截开关。
        // 开关保留在设置页仅作说明（布局里 checked=true / enabled=false），不再写任何配置。

        // 允许注册位置监听器：语义已定为「允许并注入」——持续拒绝回调在真机上不存在，
        // 本身就是特征；开关保留在设置页仅作说明（布局里 checked=true / enabled=false）。

        // 「融合定位处置」：与其它设置项同一交互 —— 点一行，弹三选一（拒绝 / 放行 / 伪装）。
        binding.dfusedLayout.setOnClickListener {
            if (!fusedAvailable) {
                showToast(getString(R.string.fused_unavailable))
                return@setOnClickListener
            }
            val modes = intArrayOf(FusedMode.REJECT, FusedMode.ALLOW, FusedMode.DISGUISE)
            val labels = modes.map { fusedLabel(it) }.toTypedArray()
            val checked = modes.indexOf(requireContext().fusedMode).coerceAtLeast(0)
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.fused_title))
                .setSingleChoiceItems(labels, checked) { d, which ->
                    val mode = modes[which]
                    requireContext().fusedMode = mode
                    binding.dfusedValue.text = fusedLabel(mode)
                    showToast(getString(R.string.fused_applied, fusedLabel(mode)))
                    updateRemoteConfig()
                    refreshFusedState()
                    d.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
            // **点空白处不关闭**：这是"改设置"的对话框，误触空白关掉会让人以为没生效/白操作一次。
            // 退出路径保持明确：选中某一档，或按「取消」/返回键。
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
        }
        refreshFusedState()

        binding.cdmaSwitch.isChecked = context.needDowngradeToCdma

        binding.cdmaSwitch.isChecked = context.needDowngradeToCdma
        binding.cdmaSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.needDowngradeToCdma = isChecked
                showToast(if (isChecked) getString(R.string.cdma_on) else getString(R.string.cdma_off))
                updateRemoteConfig()
            }
        })

        // 「传感器模拟」开关已移除：传感器 hook 恒安装（仅由 LSPosed 作用域决定是否注入），
        // 偏好项从未被模块读取——留着就是一个骗人的开关。
        //
        // 外周传感器模拟的开关**已搬到两个功能页**（步频页 / 角度和指南针页）：
        // 一个总开关按传感器类别拆成两个，放在设置页只会掩盖"哪一侧被关了"。

        // 「后台保活」（默认开）：前台服务 + partial wake lock，防止 :app 被 Cached Apps Freezer 冻结。
        // 关掉时立刻撤服务；开着且模拟在跑时立刻起服务（不必等下次开模拟）。
        binding.keepAliveSwitch.isChecked = requireContext().keepAliveInBackground
        binding.keepAliveSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().keepAliveInBackground = isChecked
            val ctx = requireContext()
            if (isChecked) {
                // 只有"无人值守仍在推进"（自动播放）才需要立刻起服务；
                // 仅仅开着会话不算 —— 空闲时保持系统默认省电行为
                if (mockServiceViewModel.isAutoPlaying) {
                    MockKeepAliveService.start(ctx)
                }
                showToast(getString(R.string.keepalive_on))
            } else {
                MockKeepAliveService.stop(ctx)
                showToast(getString(R.string.keepalive_off))
            }
        }

        // 「电池优化白名单」：点一下申请系统豁免（这就是"后台活跃权限"的系统入口）
        binding.batteryOptimizationLayout.setOnClickListener {
            val pm = requireContext().getSystemService(android.content.Context.POWER_SERVICE)
                as? android.os.PowerManager
            if (pm?.isIgnoringBatteryOptimizations(requireContext().packageName) == true) {
                showToast(getString(R.string.battery_already_whitelisted))
                return@setOnClickListener
            }
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }.onFailure {
                // 个别 ROM 没有这个 Activity：退回「电池优化」列表页，让用户手动加白
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }.onFailure { e -> showToast(getString(R.string.battery_open_failed, e.message)) }
            }
        }
        refreshBatteryOptimizationState()

        // 「步频倍率」：微调步频↔速度，默认 1.0；整数或小数都可
        binding.reportDurationLayout.setOnClickListener {
            showDialog(getString(R.string.item_report_duration_title), binding.reportDurationValue.text.toString().let {
                it.substring(0, it.length - 2)
            }) {
                val value = it.toIntOrNull()
                // 下限 1ms：0 会让摇杆/播放循环 delay(0) 空转并触发除零
                if (value == null || value < 1) {
                    Toast.makeText(context, getString(R.string.report_invalid), Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, getString(R.string.report_max), Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.reportDuration = value
                binding.reportDurationValue.text = "%dms".format(value)
            }
        }

        binding.disableWlanScanSwitch.isChecked = requireContext().disableWifiScan
        binding.disableWlanScanSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().disableWifiScan = isChecked
            with(mockServiceViewModel) {
                val lm = locationManager
                if (lm == null) {
                    showToast(getString(R.string.wifi_service_unavailable))
                    return@setOnCheckedChangeListener
                }
                if (isChecked) {
                    if(!MockServiceHelper.startWifiMock(lm)) {
                        showToast(getString(R.string.wifi_disable_failed))
                    }
                } else {
                    if(!MockServiceHelper.stopWifiMock(lm)) {
                        showToast(getString(R.string.wifi_enable_failed))
                    }
                }
            }
        }

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
                showToast(getString(R.string.config_sync_failed))
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
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                handler(value.text.toString())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
        dialog.shiftAboveIme(requireActivity().window.decorView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}