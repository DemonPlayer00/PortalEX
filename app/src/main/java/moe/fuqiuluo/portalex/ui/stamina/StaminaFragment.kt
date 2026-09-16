package moe.fuqiuluo.portalex.ui.stamina

import android.os.Bundle
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
import moe.fuqiuluo.portalex.databinding.FragmentStaminaBinding
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
                hint = "满速跑动时每分钟掉多少体力。默认 11 ⇒ 实测第一次休息在 3.9 km / 25 分左右" +
                        "（不是线性外推：体力越低恢复越快，后半段掉得更慢）",
            ),
            Row(
                title = getString(R.string.stamina_rest_at),
                desc = getString(R.string.stamina_rest_at_desc),
                get = { it.restAtPercent },
                set = { c, v -> c.copy(restAtPercent = v) },
                format = { "%.0f %%".format(it) },
                hint = "体力降到该值就进入休息。默认 20%",
            ),
            Row(
                title = getString(R.string.stamina_resume),
                desc = getString(R.string.stamina_resume_desc),
                get = { it.resumeAtPercent },
                set = { c, v -> c.copy(resumeAtPercent = v) },
                format = { "%.0f %%".format(it) },
                hint = "疲劳状态里的开跑线：体力低于它时冷却**反向**计数（欠账变大），达到/超过它时" +
                        "**正向**计数（还账），两边都越远越快；冷却回到 ≥0 就重新开跑。" +
                        "必须高于休息体力值（否则迟滞消失、会抖）。默认 30%",
            ),
            Row(
                title = getString(R.string.stamina_rest_coefficient),
                desc = getString(R.string.stamina_rest_coefficient_desc),
                get = { it.restSecondsCoefficient },
                set = { c, v -> c.copy(restSecondsCoefficient = v) },
                format = { "%.2f ×".format(it) },
                hint = "冷却时间系数：乘在恢复速度上（越大则回到阈值以上越慢）。默认 1.0",
            ),
            Row(
                title = getString(R.string.stamina_recover),
                desc = getString(R.string.stamina_recover_desc),
                get = { it.recoverCoefficient },
                set = { c, v -> c.copy(recoverCoefficient = v) },
                format = { "%.1f 点/分".format(it) },
                hint = "恢复速度系数：实际恢复 = 本值 × 恢复倍率(体力)。倍率在体力 100% 时为 1.0、" +
                        "0% 时为 2.0（越低回得越快）。默认 4.0 点/分",
            ),
            Row(
                title = getString(R.string.stamina_walk_speed),
                desc = getString(R.string.stamina_walk_speed_desc),
                get = { it.walkSpeed },
                set = { c, v -> c.copy(walkSpeed = v) },
                format = { "%.2f m/s".format(it) },
                hint = "休息时的速度（走路低值）。默认 1.10 m/s",
            ),
            Row(
                title = getString(R.string.stamina_floor),
                desc = getString(R.string.stamina_floor_desc),
                get = { it.minSpeedFactor },
                set = { c, v -> c.copy(minSpeedFactor = v) },
                format = { "%.2f ×".format(it) },
                hint = "疲劳降速下限（占基础速度比例）。默认 0.75 ⇒ 体力见底前最低到 2.29 m/s",
            ),
            Row(
                title = getString(R.string.stamina_rest_factor),
                desc = getString(R.string.stamina_rest_factor_desc),
                get = { it.restSpeedFactor },
                set = { c, v -> c.copy(restSpeedFactor = v) },
                format = { "%.2f ×".format(it) },
                hint = "疲劳时在当前疲劳倍率上再乘它；结果若低于「休息时速度」就以那个值为下限。" +
                        "默认 0.25（默认参数下由走路下限接管）",
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
            Row(
                title = getString(R.string.stamina_random),
                desc = getString(R.string.stamina_random_desc),
                get = { it.randomPercent },
                set = { c, v -> c.copy(randomPercent = v) },
                format = { "±%.0f %%".format(it) },
                hint = "随机化幅度：施加在「本轮衰减速率」与「本次休息时长」上，默认 ±15%",
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

        binding.staminaReset.setOnClickListener {
            StaminaController.resetStamina()
            refreshStatus()
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

    private fun refreshStatus() {
        val snapshot = StaminaController.snapshot()
        val base = requireContext().speed
        // 图与状态一起刷：submit 内部对"参数没变"直接返回，所以每秒调也无成本，
        // 却能顺带覆盖"在设置页改了基础速度"这种从外部发生的变化。
        binding.staminaChart.submit(StaminaController.config(), base)
        // 阶段与倍率都从体力接口读（本页不自己判断"算不算在跑"）
        binding.staminaValue.text = "%.1f %%".format(snapshot.staminaPercent)
        binding.staminaPhase.text = when {
            !StaminaController.config().enabled -> "未启用（体力不参与调制）"
            StaminaController.isResting() ->
                // 冷却进度：负数 = 还欠多少，回到 ≥0 就开跑（见 StaminaModel.cooldownSec）
                "疲劳中（冷却 %.1f｜阈值 %.0f%%｜回到 0 开跑）".format(
                    snapshot.cooldownSec, StaminaController.config().resumeAtPercent
                )
            StaminaController.isRunning() -> "跑动中（消耗中）"
            // 空闲 = 没有表象位移：**体力仍在持续恢复**（消耗才需要有位移）。
            // 这里明确写出恢复速率，免得用户看到数字在涨却不知道是不是正常。
            else -> "空闲（恢复中：约 %.1f 点/分）".format(StaminaController.recoveringPerMinute())
        }
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
