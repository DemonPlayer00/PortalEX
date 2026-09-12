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
import moe.fuqiuluo.portalex.android.widget.SensorTraceChart
import moe.fuqiuluo.portalex.databinding.FragmentCalibrationBinding
import moe.fuqiuluo.portalex.ext.sensorNoise
import moe.fuqiuluo.portalex.ext.sensorNoiseReport
import moe.fuqiuluo.portalex.ext.shiftAboveIme
import moe.fuqiuluo.portalex.service.ConfigSync
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
            .setMessage("会丢掉当前所有噪声项（含一键校准的结果），回到内置的通用量级默认值。")
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
        // 采集 dialog：**实时波形** + 静止自检读数（图上那条线就是判定用的量）
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_sensor_trace, null)
        val chart = dialogView.findViewById<SensorTraceChart>(R.id.trace_chart)
        val caption = dialogView.findViewById<TextView>(R.id.trace_caption)
        val progressText = dialogView.findViewById<TextView>(R.id.trace_progress)
        val trace = SensorNoiseCalibrator.Trace()
        progressText.text = progressText(0)

        val progress = MaterialAlertDialogBuilder(ctx)
            .setTitle("正在采集真实噪声")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> runToken++ }
            .create()
        // 与其它"改设置/采集"的对话框一致：点空白不关闭（避免误触把采集废掉）
        progress.setCanceledOnTouchOutside(false)
        progress.show()

        val base = ctx.sensorNoise
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SensorNoiseCalibrator.collect(
                        ctx, base,
                        onProgress = { ms ->
                            activity?.runOnUiThread {
                                if (token == runToken && progress.isShowing) {
                                    progressText.text = progressText(ms)
                                    renderTrace(chart, caption, trace)
                                }
                            }
                        },
                        trace = trace,
                    )
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

    /**
     * 把实时波形与"σ / 阈值"读数刷到 dialog 上。
     *
     * 读数口径与最终判定一致：加速度看**模长 σ**（判定用的就是它）；陀螺判定用的是**单轴 σ**
     * 而图上只有模长，所以这一项标注为参考值——不把不同的量写成同一个名字。
     */
    private fun renderTrace(
        chart: SensorTraceChart,
        caption: TextView,
        trace: SensorNoiseCalibrator.Trace,
    ) {
        val accel = trace.accel()
        val gyro = trace.gyro()
        // 横轴固定为采集窗口（8s）：曲线从左侧向右生长，形状不随样本数被拉伸
        chart.submit(
            SensorTraceChart.Series(trace.accelTime(), accel),
            SensorTraceChart.Series(trace.gyroTime(), gyro),
            SensorNoiseCalibrator.DURATION_MS.toFloat(),
        )
        // 量程（min~max）与 σ 一起放在图下：图内只有线条（高度 75dp，图内文字会压住曲线）
        // 注意括号：不括起来的话 `.format` 只作用于后一段字符串（第一段的占位符会原样显示）
        caption.text = ("加速度模长 σ=%.4f 范围 %.4f~%.4f（静止阈值 %.2f）\n" +
            "陀螺模长 σ=%.4f 范围 %.4f~%.4f（判定用单轴 σ≤%.2f，此处为参考）").format(
            sigmaOf(accel), rangeOf(accel).first, rangeOf(accel).second,
            SensorNoiseCalibrator.MAX_ACCEL_NORM_SIGMA,
            sigmaOf(gyro), rangeOf(gyro).first, rangeOf(gyro).second,
            SensorNoiseCalibrator.MAX_GYRO_SIGMA,
        )
    }

    /** 模长序列的区间（min, max；空序列给 0） */
    private fun rangeOf(data: FloatArray): Pair<Float, Float> =
        Pair(data.minOrNull() ?: 0f, data.maxOrNull() ?: 0f)

    /** 模长序列的标准差（n<2 时给 0） */
    private fun sigmaOf(data: FloatArray): Double {
        if (data.size < 2) return 0.0
        val mean = data.sumOf { it.toDouble() } / data.size
        var acc = 0.0
        data.forEach { val d = it - mean; acc += d * d }
        return kotlin.math.sqrt(acc / data.size)
    }

    private fun progressText(elapsedMs: Long): String {
        val total = SensorNoiseCalibrator.DURATION_MS / 1000.0
        // 窗口在**第一个有效样本**到达时才开始 ⇒ 之前显示"等待中"。
        // 旧实现从注册时刻就报"已采集 x 秒"，传感器慢的时候看着进度在跑、其实一个样本都没到。
        if (elapsedMs <= 0L) {
            return "请把手机放在平稳的表面（桌面），保持静止 ${total.toInt()} 秒。\n\n" +
                "正在等待传感器数据…（收到第一个样本才开始计时）"
        }
        return "请把手机放在平稳的表面（桌面），保持静止 ${total.toInt()} 秒。\n\n" +
            "已采集 %.1f / %.1f 秒\n\n采集期间检测到移动会作废重来。".format(elapsedMs / 1000.0, total)
    }

    // ---- 下发与回读 ----

    /** 把当前噪声档下发给系统侧（与设置页同一条 put_config 通道） */
    private fun pushConfig() {
        val lm = mockServiceViewModel.locationManager
        if (lm == null) {
            showToast("定位服务加载异常，噪声档未下发（已存本地）")
            return
        }
        val result = ConfigSync.push(requireContext(), lm)
        if (!result.isOk) {
            // 如实区分"没握手"和"系统侧拒绝"：两者都意味着**配置没生效**（本地已存）
            showToast("${result.message(requireContext())}（已存本地）")
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
