package moe.fuqiuluo.portalex.ui.stamina

import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.widget.StaminaChartView
import moe.fuqiuluo.xposed.utils.StaminaCurve
import moe.fuqiuluo.portalex.databinding.FragmentStaminaBinding
import moe.fuqiuluo.portalex.ext.StaminaPrefs
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.StaminaController
import moe.fuqiuluo.portalex.ui.common.NumberRow
import moe.fuqiuluo.portalex.ui.common.renderNumberRows
import moe.fuqiuluo.xposed.utils.StaminaConfig

/**
 * **体力模拟**独立界面。
 *
 * ## 这一页负责什么
 *
 * 只负责"看与改"：显示当前体力/速度/休息状态、**倍率×距离预览图**，以及编辑模型参数。
 * 页面**不持有状态**：状态机在 system_server（[moe.fuqiuluo.xposed.utils.StaminaRuntime]），
 * 参数由 [StaminaController] 下发、读数由它回读 —— 所以关掉页面、甚至杀掉 App 都不会让模拟停下。
 *
 * ## 参数行是动态生成的
 *
 * 每一行 = 一个可编辑参数（标题/说明/当前值）。行本身在 `fragment_stamina.xml` 里
 * 只有容器；具体行由 [addNumberRow] 按同一模板插入 —— 好处是"加一个参数"
 * 只需在 [rows] 列表里加一条，不必再复制一段 XML（复制出来的行很容易漏改 id）。
 *
 * ## 为什么默认关闭
 *
 * 它会**改变模拟出来的配速**（这是它的目的），但也会让"设定速度"与"实际跑出的速度"
 * 不再相等。使用者多半是想要它，但不该在不知情时被改变 —— 故默认关闭，由这一页显式打开。
 */
class StaminaFragment : Fragment() {

    private var _binding: FragmentStaminaBinding? = null
    private val binding get() = _binding!!

    /** 参数行描述：标题、说明、取值、写回。列表顺序即界面顺序 */
    private data class Row(
        val title: String,
        val desc: String,
        val get: (StaminaConfig) -> Double,
        val set: (StaminaConfig, Double) -> StaminaConfig,
        val format: (Double) -> String,
        val hint: String,
    )

    private val rows: List<Row>
        get() = listOf(
            Row(
                title = getString(R.string.stamina_critical_speed),
                desc = getString(R.string.stamina_critical_speed_desc),
                get = { it.criticalSpeedMps },
                set = { c, v -> c.copy(criticalSpeedMps = v) },
                format = { "%.2f m/s".format(it) },
                hint = "临界速度 CS：高于它才消耗 D′。默认 2.90 m/s（约 10.4 km/h）",
            ),
            Row(
                title = getString(R.string.stamina_d_prime),
                desc = getString(R.string.stamina_d_prime_desc),
                get = { it.dPrimeMeters },
                set = { c, v -> c.copy(dPrimeMeters = v) },
                format = { "%.0f m".format(it) },
                hint = "D′：高于 CS 的有限距离能力。默认 150 m；越小越快耗尽",
            ),
            Row(
                title = getString(R.string.stamina_recovery_tau),
                desc = getString(R.string.stamina_recovery_tau_desc),
                get = { it.recoveryTauSec },
                set = { c, v -> c.copy(recoveryTauSec = v) },
                format = { "%.0f 秒".format(it) },
                hint = "D′ 恢复时间常数 τD′：越大恢复越慢。默认 376 秒",
            ),
        )

    private var refreshJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStaminaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()

        binding.staminaSwitch.isChecked = StaminaController.config().enabled
        binding.staminaSwitch.setOnCheckedChangeListener { _, isChecked ->
            val c = StaminaController.config().copy(enabled = isChecked)
            StaminaController.applyConfig(context, c)
            // 打开时复位：否则会继承上一次运行遗留的体力/休息计时，看起来像"一开就累"
            if (isChecked) StaminaController.resetStamina(context)
            renderRows()
            refreshStatus()
        }

        // 理论 / 生成 两页：点"生成"= 用真实引擎重跑一条（所以它同时是刷新键，
        // 已经在生成页时再点一次也重新跑 —— 这条由按钮自己的 click 兜住，
        // 因为 ToggleGroup 对"重复选中同一个按钮"不会回调）
        binding.staminaChartMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.stamina_chart_mode_generated -> generate()
                else -> showTheory()
            }
        }
        binding.staminaChartModeGenerated.setOnClickListener {
            if (binding.staminaChart.mode() == StaminaChartView.Mode.GENERATED) generate()
        }

        binding.staminaReset.setOnClickListener {
            StaminaController.resetStamina(requireContext())
            refreshStatus()
        }

        // 「重置数据」= 破坏性操作（旧参数不保留）⇒ 必须弹窗确认；开关状态保留，
        // 免得"恢复默认"顺手把功能关掉、用户以为坏了。
        binding.staminaResetData.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.stamina_reset_data)
                .setMessage(R.string.stamina_reset_data_confirm)
                .setPositiveButton(R.string.stamina_reset_data_ok) { _, _ -> resetAllData() }
                .setNegativeButton("取消", null)
                .show()
        }

        renderRows()
    }

    override fun onResume() {
        super.onResume()
        // 状态每秒刷新一次（体力在跑动中持续变化；这一页只是显示，不驱动模型）
        if (refreshJob?.isActive == true) return
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    refreshStatus()
                    delay(1000)
                }
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        super.onPause()
    }

    /** 把参数行按 [rows] 铺进容器（每次改参数后重建，保证显示与内存一致） */
    /**
     * 行渲染与输入对话框都在 [renderNumberRows]（与步频 / 角度指南针页共用一套）——
     * 本页只负责"从配置读值、写回配置"这两件事。
     */
    private fun renderRows() {
        renderNumberRows(
            layoutInflater, binding.staminaRows,
            rows.map { row ->
                NumberRow(
                    title = row.title,
                    desc = row.desc,
                    display = { row.format(row.get(StaminaController.config())) },
                    current = { row.get(StaminaController.config()) },
                    hint = { row.hint },
                    commit = { value ->
                        val updated = row.set(StaminaController.config(), value)
                        StaminaController.applyConfig(requireContext(), updated)
                        renderRows()
                        refreshStatus()
                    },
                )
            },
        )
    }

    private var chartConfig: StaminaConfig? = null

    /** 恢复出厂参数 + 重置运行时体力/统计（开关状态保留），并清掉已废弃的旧键 */
    private fun resetAllData() {
        val context = requireContext()
        val keepEnabled = StaminaController.config().enabled
        val defaults = StaminaConfig().copy(enabled = keepEnabled)
        StaminaController.applyConfig(context, defaults)   // 落库 + 立即生效
        StaminaPrefs.clearObsolete(context)
        StaminaController.resetStamina(context)

        binding.staminaSwitch.isChecked = keepEnabled
        renderRows()
        // ⚠️ 这里**不能**只把 chartConfig 标成 defaults 就完事：生成页的曲线是"一次跑法的快照"，
        //    那样它会以为自己的采样还新鲜，继续显示用旧参数跑出来的图（真机上就是这么露馅的）。
        //    所以按当前页签各自走一遍刷新路径。
        if (binding.staminaChart.mode() == StaminaChartView.Mode.GENERATED) {
            generate()
        } else {
            showTheory()
        }
        refreshStatus()
        Toast.makeText(context, R.string.stamina_reset_data_done, Toast.LENGTH_SHORT).show()
    }

    /** 理论页：解析积分 + ±随机包络（跟随参数自动刷新） */
    private fun showTheory() {
        val config = StaminaController.config()
        val base = requireContext().speed
        chartConfig = config
        binding.staminaChartSample.visibility = View.GONE
        binding.staminaChartMetrics.visibility = View.VISIBLE
        binding.staminaChartDesc.text = getString(R.string.stamina_chart_desc)
        binding.staminaChart.submit(config, base)
        renderMetrics(config, base)
    }

    /**
     * **把反推变成读数**：这套参数跑出来是什么样，直接算出来显示，不用联立公式。
     *
     * 数据直接取图表刚算好的那条理论曲线的指标（不重复积分），
     * 再加两条体检：疲劳期能不能回血、疲劳有没有真的降速。
     */
    private fun renderMetrics(config: StaminaConfig, base: Double) {
        val metrics = binding.staminaChart.theoryMetrics()
        val sb = StringBuilder()
        if (metrics == null) {
            sb.append("等待曲线…")
        } else {
            val exhausted = metrics.firstFatigueDistanceM
            if (exhausted < 0.0) {
                sb.append("当前参数下 10 km 内 D′ 不会耗尽")
            } else {
                sb.append("D′ 预计耗尽：%.2f km".format(exhausted / 1000.0))
            }
            sb.append("\nCS %.2f m/s · D′ %.0f m · τD′ %.0f s"
                .format(config.criticalSpeedMps, config.dPrimeMeters, config.recoveryTauSec))
        }
        binding.staminaChartMetrics.text = sb.toString()
    }

    private fun generate() {
        val context = requireContext()
        val config = StaminaController.config()
        val base = context.speed
        chartConfig = config
        val history = StaminaController.history()
        val curve = if (history.size >= 2) {
            // 生成页优先显示**实际运行采样**（逐秒配速），而不是再跑一条理论模拟。
            val distances = FloatArray(history.size)
            val multipliers = FloatArray(history.size)
            var d = 0.0
            history.forEachIndexed { i, s ->
                if (i > 0) {
                    val prev = history[i - 1]
                    d += (prev.paceMps + s.paceMps) * 0.5 * (s.elapsedSec - prev.elapsedSec)
                }
                distances[i] = d.toFloat()
                multipliers[i] = (s.paceMps / base).coerceIn(0.0, 1.0).toFloat()
            }
            StaminaCurve.Curve(
                distanceM = distances,
                multiplier = multipliers,
                metrics = StaminaCurve.Metrics(
                    averageSpeedMps = if (history.last().elapsedSec > 0)
                        d / history.last().elapsedSec else 0.0,
                ),
                elapsedSec = history.last().elapsedSec,
                staminaEndPercent = history.last().staminaPercent,
            )
        } else {
            val dtSec = context.reportDuration.coerceIn(1, 1000) / 1000.0
            StaminaCurve.sample(config, base, dtSec = dtSec)
        }

        binding.staminaChartMetrics.visibility = View.GONE
        binding.staminaChartDesc.text = getString(R.string.stamina_chart_desc)
        binding.staminaChart.submitGenerated(curve, base)

        val km = curve.distanceM.last() / 1000.0
        val minutes = curve.elapsedSec / 60.0
        binding.staminaChartSample.visibility = View.VISIBLE
        binding.staminaChartSample.text = if (history.size >= 2) {
            "实际采样：%.1f km / %.1f 秒".format(km, minutes * 60.0)
        } else {
            "暂无实际采样，显示公式预览：%.1f km / %.1f 分钟".format(km, minutes)
        }
    }

    private fun refreshStatus() {
        val context = requireContext()
        val readable = StaminaController.refresh(context)
        val snapshot = StaminaController.snapshot()
        val base = context.speed
        if (binding.staminaChart.mode() == StaminaChartView.Mode.THEORY) {
            binding.staminaChart.submit(StaminaController.config(), base)
            renderMetrics(StaminaController.config(), base)
        } else if (StaminaController.config() != chartConfig) {
            chartConfig = StaminaController.config()
            generate()
        }
        binding.staminaValue.text = if (readable) "%.1f %%".format(snapshot.staminaPercent) else "—"
        val phase = when {
            !readable -> getString(R.string.stamina_phase_unavailable)
            !StaminaController.config().enabled -> "未启用（不跟踪 D′）"
            !StaminaController.isWireApplied() -> getString(R.string.stamina_phase_not_applied)
            StaminaController.isRunning() -> "移动中（D′ 结算中）"
            else -> "空闲（D′ 恢复中）"
        }
        binding.staminaPhase.text = phase
        binding.staminaEffective.text = "基础 %.2f m/s ⇒ 当前 %.2f m/s".format(
            base, StaminaController.effectiveSpeed(base)
        )
        binding.staminaStats.text = "D′ 余额 %.1f%%".format(snapshot.staminaPercent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
