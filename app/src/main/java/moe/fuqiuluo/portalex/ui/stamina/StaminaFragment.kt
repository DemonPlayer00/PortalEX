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
 * 只负责"看与改"：显示当前体力/速度/休息状态，以及编辑模型参数。
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
                hint = "满速跑动时每分钟掉多少体力。默认 11 ⇒ 100→20 约 7.3 分钟歇一次",
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
                title = getString(R.string.stamina_rest_seconds),
                desc = getString(R.string.stamina_rest_seconds_desc),
                get = { it.restSeconds },
                set = { c, v -> c.copy(restSeconds = v) },
                format = { "%.0f 秒".format(it) },
                hint = "每次休息持续多久。默认 35 秒（实际时长还会按随机化幅度浮动）",
            ),
            Row(
                title = getString(R.string.stamina_recover),
                desc = getString(R.string.stamina_recover_desc),
                get = { it.recoverPerSecond },
                set = { c, v -> c.copy(recoverPerSecond = v) },
                format = { "%.1f 点/秒".format(it) },
                hint = "休息期间每秒回多少体力。默认 2.0 ⇒ 35 秒约回 70 点",
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
        val effective = base * StaminaController.currentScale
        binding.staminaValue.text = "%.1f %%".format(snapshot.staminaPercent)
        binding.staminaPhase.text = if (snapshot.resting) {
            "休息中：还剩 %.0f 秒（速度降到走路 %.2f m/s）".format(
                snapshot.restRemainingSec, StaminaController.config().walkSpeed
            )
        } else if (StaminaController.config().enabled) {
            "跑动中"
        } else {
            "未启用（体力不参与调制）"
        }
        binding.staminaEffective.text = "基础 %.2f m/s ⇒ 当前 %.2f m/s".format(base, effective)
        binding.staminaStats.text = "休息 %d 次 / 共 %.1f 分钟".format(
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
