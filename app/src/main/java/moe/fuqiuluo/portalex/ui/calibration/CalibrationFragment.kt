package moe.fuqiuluo.portalex.ui.calibration

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentCalibrationBinding
import moe.fuqiuluo.portalex.ext.sensorNoise
import moe.fuqiuluo.portalex.ext.sensorNoiseReport
import moe.fuqiuluo.portalex.ext.shiftAboveIme
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.service.SensorNoiseCalibrator
import moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.SensorNoise
import kotlin.math.abs

/**
 * Calibration 页：注入噪声档的**可编辑列表** + **一键校准**。
 *
 * 列表就是 [SensorNoise.ITEMS]（逐轴 σ + 陀螺零偏），点一行改一行；一键校准把真实传感器的
 * 逐轴中位数与方差量出来后回填（只有 σ 与陀螺零偏会写进去，中位数其余部分只作参照显示）。
 *
 * 页面底部那行是**系统侧回读**（`get_sensor_status` 里由原生层回报的噪声档）——用来证明
 * 下发真的落到了 system_server 的原生注入层，而不是只写进了 App 的偏好。
 */
class CalibrationFragment : Fragment() {

    private var _binding: FragmentCalibrationBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    /** 行 → 该行数值显示控件（顺序与 [SensorNoise.ITEMS] 一致） */
    private val rowValues = mutableListOf<TextView>()

    /** 一次校准的令牌：作废的采集（用户取消/页面销毁）不许回写 */
    private var runToken = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibrationBinding.inflate(inflater, container, false)
        buildRows()
        renderValues(requireContext().sensorNoise)
        binding.calibStatus.text = requireContext().sensorNoiseReport
        binding.calibRun.setOnClickListener { runCalibration() }
        binding.calibReset.setOnClickListener { confirmReset() }
        refreshEcho()
        return binding.root
    }

    // ---- 列表 ----

    private fun buildRows() {
        val inflater = LayoutInflater.from(requireContext())
        val list: LinearLayout = binding.calibList
        SensorNoise.ITEMS.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.layout_noise_row, list, false)
            row.findViewById<TextView>(R.id.noise_title).text = item.title
            row.findViewById<TextView>(R.id.noise_desc).text = item.desc
            val value = row.findViewById<TextView>(R.id.noise_value)
            rowValues += value
            row.setOnClickListener { editRow(index) }
            list.addView(row)
        }
    }

    private fun renderValues(values: FloatArray) {
        SensorNoise.ITEMS.forEachIndexed { index, item ->
            rowValues.getOrNull(index)?.text = SensorNoise.formatItem(item, values)
        }
    }

    private fun editRow(index: Int) {
        val item = SensorNoise.ITEMS[index]
        val current = requireContext().sensorNoise
        showInputDialog(item.title, SensorNoise.formatItem(item, current)) { text ->
            val parsed = SensorNoise.parseItem(item, text).getOrElse {
                showToast(it.message ?: "输入不合法")
                return@showInputDialog
            }
            val updated = SensorNoise.withItem(current, item, parsed)
            requireContext().sensorNoise = updated
            renderValues(requireContext().sensorNoise)
            pushConfig()
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("恢复默认噪声档")
            .setMessage("会丢掉当前所有噪声项（含一键校准的结果）。默认值取自 PKG110 实测。")
            .setPositiveButton("恢复") { _, _ ->
                requireContext().sensorNoise = SensorNoise.DEFAULTS.copyOf()
                renderValues(requireContext().sensorNoise)
                showToast("已恢复默认噪声档")
                pushConfig()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 一键校准 ----

    private fun runCalibration() {
        val ctx = requireContext()
        // 前置：模拟会话在跑时不能校准。
        // 框架侧的注入一旦生效就会**压制真实事件**，那时采到的全是我们自己生成的假数据
        // （自我校准）；开关打开但会话没跑时真实事件原样透传，可以校准。
        val lm = mockServiceViewModel.locationManager
        val sessionRunning = FakeLoc.enable || (lm != null && runCatching {
            MockServiceHelper.isMockStart(lm)
        }.getOrDefault(false))
        if (sessionRunning) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("无法校准")
                .setMessage(
                    "模拟正在运行：框架侧此刻在压制真实传感器，采到的只会是我们自己注入的数据。\n\n" +
                        "请先到 Location Mock 页停止模拟，再回来校准。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val token = ++runToken
        val progress = MaterialAlertDialogBuilder(ctx)
            .setTitle("正在采集真实噪声")
            .setMessage(progressText(0))
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> runToken++ }
            .create()
        progress.show()

        val base = ctx.sensorNoise
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SensorNoiseCalibrator.collect(ctx, base) { ms ->
                        activity?.runOnUiThread {
                            if (token == runToken && progress.isShowing) progress.setMessage(progressText(ms))
                        }
                    }
                }.getOrElse {
                    SensorNoiseCalibrator.Result(base, "", false, "采集异常：${it.message}")
                }
            }
            if (token != runToken) {
                runCatching { progress.dismiss() }
                showToast("已取消校准")
                return@launch
            }
            runCatching { progress.dismiss() }
            if (!result.ok) {
                _binding?.calibStatus?.text = result.message
                showToast(result.message)
                return@launch
            }
            ctx.sensorNoise = result.values
            ctx.sensorNoiseReport = result.report
            renderValues(ctx.sensorNoise)
            _binding?.calibStatus?.text = result.report
            showToast(result.message)
            pushConfig()
        }
    }

    private fun progressText(elapsedMs: Long): String =
        "请把手机放在平稳的表面（桌面），保持静止 ${SensorNoiseCalibrator.DURATION_MS / 1000} 秒。\n\n" +
            "已采集 %.1f / %.1f 秒\n\n采集期间检测到移动会作废重来。".format(
                elapsedMs / 1000.0, SensorNoiseCalibrator.DURATION_MS / 1000.0,
            )

    // ---- 下发与回读 ----

    /** 把当前噪声档下发给系统侧（与设置页同一条 put_config 通道） */
    private fun pushConfig() {
        val lm = mockServiceViewModel.locationManager
        if (lm == null) {
            showToast("定位服务加载异常，噪声档未下发（已存本地）")
            return
        }
        if (!MockServiceHelper.putConfig(lm, requireContext())) {
            showToast("下发失败：系统侧未接受（已存本地）")
            return
        }
        // 系统侧是**单向下发**（proxy 用 transact 无应答），紧接着的状态回读很可能跑在配置前面
        // ⇒ 实测会出现"刚保存完却回读到旧值"的假告警。延后一点再回读。
        binding.root.postDelayed({ _binding?.let { refreshEcho() } }, 400)
    }

    /**
     * 系统侧回读：`get_sensor_status` 里的 `noise_profile` 由原生层直接回报，
     * 与 App 侧的值对上才算真的生效。
     */
    private fun refreshEcho() {
        val lm = mockServiceViewModel.locationManager ?: run {
            binding.calibEcho.text = "系统侧回读：定位服务未就绪"
            return
        }
        val bundle = runCatching { MockServiceHelper.getSensorStatus(lm) }.getOrNull()
        if (bundle == null) {
            binding.calibEcho.text = "系统侧回读：模块未握手（LSPosed 未注入？）"
            return
        }
        if (!bundle.getBoolean("native_ready", false)) {
            binding.calibEcho.text = "系统侧回读：原生注入层未装载（「Binder 外周传感器模拟」关闭时正常）"
            return
        }
        val echo = bundle.getString("noise_profile") ?: ""
        if (echo.isBlank() || echo == "n/a") {
            // 注入层还是旧版本（LSPosed 在开机时装载 .so，换包后要重启才换得掉）
            binding.calibEcho.text = "系统侧回读：注入层未提供噪声档（旧版本 .so；重启手机后生效）"
            return
        }
        val local = requireContext().sensorNoise
        val digits = 4
        val matched = runCatching {
            val remote = echo.substringAfter("gy=").substringBefore(" bias=").split('/')
                .map { it.toFloat() }
            remote.size == 3 && (0 until 3).all { abs(remote[it] - local[SensorNoise.GYRO + it]) < 1e-4 }
        }.getOrDefault(false)
        val verdict = if (matched) "✅ 与原生的陀螺 σ 一致" else "⚠️ 与本地值不一致（可能还没下发成功）"
        binding.calibEcho.text = "系统侧回读：$echo\n$verdict"
    }

    // ---- 杂项 ----

    private fun showToast(message: String) {
        val ctx = activity ?: return
        ctx.runOnUiThread { Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
    }

    @SuppressLint("MissingInflatedId")
    private fun showInputDialog(titleText: String, valueText: String, handler: (String) -> Unit) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.title).text = titleText
        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(valueText)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(null)
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ -> handler(value.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
        dialog.shiftAboveIme(requireActivity().window.decorView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 页面销毁即作废在飞的采集：回写会碰到已释放的 binding
        runToken++
        rowValues.clear()
        _binding = null
    }
}
