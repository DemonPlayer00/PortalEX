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
import moe.fuqiuluo.portalex.ext.shiftAboveIme
import moe.fuqiuluo.portalex.service.StaminaController
import moe.fuqiuluo.xposed.utils.StaminaConfig

/**
 * **体力模拟**独立界面。
 *
 * ## 这一页负责什么
 *
 * 只负责"看与改"：显示当前体力/速度/休息状态、**倍率×距离预览图**，以及编辑模型参数。
 * **模型本身与状态在 [StaminaController]**，运动循环每拍驱动它
 * （见 `MockServiceViewModel.ensureMotionLoop`）—— 这一页不参与推进，
 * 所以关掉页面也不会让模拟停下。
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
                title = getString(R.string.stamina_decay),
                desc = getString(R.string.stamina_decay_desc),
                get = { it.decayPerMinute },
                set = { c, v -> c.copy(decayPerMinute = v) },
                format = { "%.1f 点/分".format(it) },
                hint = "满速跑动时每分钟掉多少体力。默认 22 ⇒ 首次疲劳约在 1.4 km / 9 分" +
                        "（别按线性外推：体力越低恢复越快、消耗也降，后半段掉得更慢）",
            ),
            Row(
                title = getString(R.string.stamina_rest_at),
                desc = getString(R.string.stamina_rest_at_desc),
                get = { it.restAtPercent },
                set = { c, v -> c.copy(restAtPercent = v) },
                format = { "%.0f %%".format(it) },
                hint = "体力降到该值就进入疲劳。默认 15%（配合开跑阈值 25% 形成迟滞带）",
            ),
            Row(
                title = getString(R.string.stamina_resume),
                desc = getString(R.string.stamina_resume_desc),
                get = { it.resumeAtPercent },
                set = { c, v -> c.copy(resumeAtPercent = v) },
                format = { "%.0f %%".format(it) },
                hint = "疲劳倒计时的**速率参考点**：体力低于它 ⇒ 倒计时变慢（最低 0.25x），" +
                        "达到/超过 ⇒ 变快（最多 3x），越远越快。必须高于疲劳体力值。默认 25%",
            ),
            Row(
                title = getString(R.string.stamina_fatigue_sec),
                desc = getString(R.string.stamina_fatigue_sec_desc),
                get = { it.fatigueSec },
                set = { c, v -> c.copy(fatigueSec = v) },
                format = { "%.0f 秒".format(it) },
                hint = "一次疲劳的**倒计时预算**：进疲劳按下这个秒数，倒完即开跑。" +
                        "实际时长通常更长（低于开跑阈值时倒得慢，默认约 2 倍），看下面结果行。默认 80 秒",
            ),
            Row(
                title = getString(R.string.stamina_walk_speed),
                desc = getString(R.string.stamina_walk_speed_desc),
                get = { it.walkSpeed },
                set = { c, v -> c.copy(walkSpeed = v) },
                format = { "%.2f m/s".format(it) },
                hint = "疲劳期间的速度。默认 1.10 m/s（走路）",
            ),
            Row(
                title = getString(R.string.stamina_floor),
                desc = getString(R.string.stamina_floor_desc),
                get = { it.minSpeedFactor },
                set = { c, v -> c.copy(minSpeedFactor = v) },
                format = { "%.2f ×".format(it) },
                hint = "跑动段的下限（占基础速度比例）。默认 0.75 ⇒ 进疲劳前最低约 2.29 m/s",
            ),
            Row(
                title = getString(R.string.stamina_recover),
                desc = getString(R.string.stamina_recover_desc),
                get = { it.recoverCoefficient },
                set = { c, v -> c.copy(recoverCoefficient = v) },
                format = { "%.1f 点/分".format(it) },
                hint = "恢复速度系数：体力 100% 时按本值回，0% 时翻倍（越低回得越快）。默认 6.0 点/分",
            ),
            Row(
                title = getString(R.string.stamina_transition),
                desc = getString(R.string.stamina_transition_desc),
                get = { it.transitionSec },
                set = { c, v -> c.copy(transitionSec = v) },
                format = { "%.1f 秒".format(it) },
                hint = "进出疲劳时速度平滑的时长：默认 3 秒（把台阶摊成斜坡）。" +
                        "每次方向变化重抽一次（× ±随机化幅度）。0 = 直接跳变",
            ),
            Row(
                title = getString(R.string.stamina_random),
                desc = getString(R.string.stamina_random_desc),
                get = { it.randomPercent },
                set = { c, v -> c.copy(randomPercent = v) },
                format = { "±%.0f %%".format(it) },
                hint = "随机化幅度：施加在「本轮衰减速率」「本次过渡时长」「本次疲劳时长」上，默认 ±15%",
            ),
            Row(
                title = getString(R.string.stamina_ignore_window),
                desc = getString(R.string.stamina_ignore_window_desc),
                get = { it.moveIgnoreWindowSec },
                set = { c, v -> c.copy(moveIgnoreWindowSec = v) },
                format = { "%.1f 秒".format(it) },
                hint = "忽略窗口：窗口内的平均表象速度也参与异常判定。默认 3 秒",
            ),
            Row(
                title = getString(R.string.stamina_ignore_speed),
                desc = getString(R.string.stamina_ignore_speed_desc),
                get = { it.moveIgnoreSpeed },
                set = { c, v -> c.copy(moveIgnoreSpeed = v) },
                format = { "%.1f m/s".format(it) },
                hint = "忽略阈值：表象移动**快于**它时本拍不计消耗（防瞬移/异常帧一瞬抽干体力）。默认 12 m/s",
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
            if (isChecked) StaminaController.resetStamina()
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
            StaminaController.resetStamina()
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
    private fun renderRows() {
        val container = binding.staminaRows
        container.removeAllViews()
        val config = StaminaController.config()
        rows.forEach { row ->
            container.addView(buildRow(row, config))
        }
    }

    private fun buildRow(row: Row, config: StaminaConfig): View {
        val rowView = layoutInflater.inflate(R.layout.item_stamina_value, binding.staminaRows, false)
        rowView.findViewById<TextView>(R.id.stamina_row_title).text = row.title
        rowView.findViewById<TextView>(R.id.stamina_row_desc).text = row.desc
        rowView.findViewById<TextView>(R.id.stamina_row_value).text = row.format(row.get(config))
        rowView.setOnClickListener {
            showNumberDialog(row.title, row.get(StaminaController.config()), row.hint) { value ->
                val updated = row.set(StaminaController.config(), value)
                StaminaController.applyConfig(requireContext(), updated)
                renderRows()
                refreshStatus()
            }
        }
        return rowView
    }

    private var chartConfig: StaminaConfig? = null

    /** 恢复出厂参数 + 重置运行时体力/统计（开关状态保留），并清掉已废弃的旧键 */
    private fun resetAllData() {
        val context = requireContext()
        val keepEnabled = StaminaController.config().enabled
        val defaults = StaminaConfig().copy(enabled = keepEnabled)
        StaminaController.applyConfig(context, defaults)   // 落库 + 立即生效
        StaminaPrefs.clearObsolete(context)
        StaminaController.resetStamina()

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
        if (metrics == null || !metrics.hasFatigue) {
            sb.append(getString(R.string.stamina_metrics_no_fatigue, config.restAtPercent))
        } else {
            sb.append(getString(R.string.stamina_metrics_label)).append("：")
            sb.append(
                getString(
                    R.string.stamina_metrics,
                    "%.1f".format(metrics.firstFatigueDistanceM / 1000.0),
                    "%.1f".format(metrics.firstFatigueStartSec / 60.0),
                    "%.1f".format(metrics.firstFatigueSec / 60.0),
                    if (metrics.firstCycleSec > 0) "%.1f".format(metrics.firstCycleSec / 60.0) else "—",
                    "%.2f".format(metrics.fatigueSpeedMps),
                    metrics.fatigueCountWithin(1_500.0),
                    metrics.fatigueCountWithin(2_000.0),
                )
            )
        }
        // 体检：这两条是"参数会把模拟弄坏"的判据，直接说人话，别让用户自己推
        val decayLimit = StaminaCurve.decayLimitForRecovery(config, base)
        if (decayLimit.isFinite() && config.decayPerMinute > decayLimit * 0.95) {
            sb.append("\n").append(getString(R.string.stamina_health_decay, "%.1f".format(decayLimit)))
        }
        if (!StaminaCurve.fatigueSlowsDown(config, base)) {
            sb.append("\n").append(getString(R.string.stamina_health_no_slowdown))
        }
        binding.staminaChartMetrics.text = sb.toString()
    }

    /**
     * 生成页：**用真实引擎跑一遍**（[StaminaCurve.sample] 直接驱动 [StaminaModel]，
     * 与运动循环同一套调用），每次点都换一条随机路径，并把这次的成绩摊在图下面。
     */
    private fun generate() {
        val context = requireContext()
        val config = StaminaController.config()
        val base = context.speed
        chartConfig = config
        // 报点间隔取设置里的值：它在真机上直接决定每拍位移，进而决定曲线细节
        val dtSec = context.reportDuration.coerceIn(1, 1000) / 1000.0
        val curve = StaminaCurve.sample(config, base, dtSec = dtSec)

        binding.staminaChartMetrics.visibility = View.GONE
        binding.staminaChartDesc.text = getString(R.string.stamina_chart_desc_generated)
        binding.staminaChart.submitGenerated(curve, base)

        val km = curve.distanceM.last() / 1000.0
        val minutes = curve.elapsedSec / 60.0
        val avgPace = if (km > 0.01) curve.elapsedSec / km else 0.0
        binding.staminaChartSample.visibility = View.VISIBLE
        binding.staminaChartSample.text = getString(
            R.string.stamina_chart_sample,
            "%.1f".format(km),
            "%.1f".format(minutes),
            "%d:%02d".format((avgPace / 60).toInt(), (avgPace % 60).toInt()),
            curve.restCount,
            "%.1f".format(curve.restTotalSec / 60.0),
        )
    }

    private fun refreshStatus() {
        val snapshot = StaminaController.snapshot()
        val base = requireContext().speed
        // 图与状态一起刷：submit 内部对"参数没变"直接返回，所以每秒调也无成本，
        // 却能顺带覆盖"在设置页改了基础速度"这种从外部发生的变化。
        // ⚠️ 只在"理论"页这么做：生成页是**一次跑法的快照**，每秒重跑既没意义也会让曲线乱跳。
        if (binding.staminaChart.mode() == StaminaChartView.Mode.THEORY) {
            binding.staminaChart.submit(StaminaController.config(), base)
            // 结果/体检也要跟着参数走（submit 内部有"没变就返回"的短路，这里重算很便宜）
            renderMetrics(StaminaController.config(), base)
        } else if (StaminaController.config() != chartConfig) {
            // 参数被改了 ⇒ 生成页快照已经过期，重跑一条（否则图上还挂着旧参数的跑法）
            chartConfig = StaminaController.config()
            generate()
        }
        // 阶段与倍率都从体力接口读（本页不自己判断"算不算在跑"）
        binding.staminaValue.text = "%.1f %%".format(snapshot.staminaPercent)
        val phase = when {
            !StaminaController.config().enabled -> "未启用（体力不参与调制）"
            StaminaController.isResting() ->
                // 冷却进度：负数 = 还欠多少，回到 ≥0 就开跑（见 StaminaModel.cooldownSec）
                "疲劳中（还剩 %.0f 秒｜参考点 %.0f%%）".format(
                    snapshot.fatigueRemainingSec, StaminaController.config().resumeAtPercent
                )
            StaminaController.isRunning() -> "跑动中（消耗中）"
            // 空闲 = 没有表象位移：**体力仍在持续恢复**（消耗才需要有位移）。
            // 这里明确写出恢复速率，免得用户看到数字在涨却不知道是不是正常。
            else -> "空闲（恢复中：约 %.1f 点/分）".format(StaminaController.recoveringPerMinute())
        }
        // 过渡中就把进度摊出来：不然"刚开跑却还是走路速度"看起来像 bug
        binding.staminaPhase.text =
            if (snapshot.blend > 0.01 && snapshot.blend < 0.99) {
                "%s ｜过渡 %.0f%%".format(phase, snapshot.blend * 100)
            } else phase
        binding.staminaEffective.text = "基础 %.2f m/s ⇒ 当前 %.2f m/s".format(
            base, StaminaController.effectiveSpeed(base)
        )
        binding.staminaStats.text = "疲劳 %d 次 / 共 %.1f 分钟".format(
            snapshot.restCount, snapshot.restTotalSec / 60.0
        )
    }

    private fun showNumberDialog(titleText: String, current: Double, hint: String, handler: (Double) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.title).text = titleText
        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(if (current == current.toLong().toDouble()) current.toLong().toString() else current.toString())

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(null)
            .setMessage(hint)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val parsed = value.text.toString().trim().toDoubleOrNull()
                if (parsed != null) handler(parsed)
            }
            .setNegativeButton("取消", null)
            .show()
            .also { it.shiftAboveIme(requireActivity().window.decorView) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
