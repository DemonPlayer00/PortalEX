package moe.fuqiuluo.portalex.ui.cadence

import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.ext.orientationMock
import moe.fuqiuluo.portalex.ext.cadenceMock
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentCadenceBinding
import moe.fuqiuluo.portalex.ext.cadenceScale
import moe.fuqiuluo.portalex.ext.cadenceWobbleAmp
import moe.fuqiuluo.portalex.ext.cadenceWobbleRnd
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.ui.common.NumberRow
import moe.fuqiuluo.portalex.ui.common.renderNumberRows
import moe.fuqiuluo.xposed.utils.FakeLoc

/**
 * **步频与加速度模拟**独立页：把"目标应用看到的步频"相关的设置项集中到一处。
 *
 * ## 这条链是怎么走的（页面底部的说明与它一致）
 *
 * ```
 * 速度 → FakeLoc.cadenceForSpeed(速度) → 步频意图(步/分) → 原生层按步间隔摊成步事件
 *          ↑ 乘 cadenceScale（本页唯一一行）
 * 事件密度还受 Setting 页的 传感器上报频率(Hz) 与 位置上报间隔(ms) 影响
 * ```
 * 公式**只有一处**（`FakeLoc.cadenceForSpeed`），所以本页不自己算步频，只显示它的结果 ——
 * 页面顶部那行"当前步频意图"就是直接问它要的。
 *
 * ## 为什么这几项从「Setting」页搬过来
 *
 * 用户口径：为步频与加速度模拟开单独页面并把相关设置项移过来。散在设置页里时，
 * 调步频要去"Setting"里翻两行、还看不出它们与速度的耦合关系。
 */
class CadenceFragment : Fragment() {

    private var _binding: FragmentCadenceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCadenceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 外周传感器模拟（本页那一侧）。**开关即下发**（不等下次握手）—— 否则用户会以为"点了没反应"。
        // 另一侧的值原样带着走：这条命令一次带两侧，别把对方的状态覆盖成默认。
        binding.cadenceMockSwitch.isChecked = requireContext().cadenceMock
        binding.cadenceMockSwitch.setOnCheckedChangeListener { _, checked ->
            val ctx = requireContext()
            ctx.cadenceMock = checked
            // 失败必须说出来：静默失败会让用户以为"关了却还在注入" —— 那正是最该避免的骗人开关
            val r = ConfigSync.setSensorMock(
                ctx, ctx.getSystemService(LocationManager::class.java), ctx.cadenceMock, ctx.orientationMock
            )
            if (!r.isOk) {
                android.widget.Toast.makeText(ctx, "外周模拟下发失败：$r", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        renderRows()
        refreshReadout()
    }

    override fun onResume() {
        super.onResume()
        // 速度可能在别的页面被改（它在 Setting 页），回来时把读数刷新
        renderRows()
        refreshReadout()
    }

    private fun renderRows() {
        val context = requireContext()
        renderNumberRows(
            layoutInflater, binding.cadenceRows,
            listOf(
                NumberRow(
                    title = getString(R.string.cadence_scale),
                    desc = getString(R.string.cadence_scale_desc),
                    display = { "%.2f ×".format(context.cadenceScale) },
                    current = { context.cadenceScale.toDouble() },
                    hint = {
                        "步频倍率：微调「步频 ↔ 速度」的关系。1 = 不调整（逐位保持原公式）；" +
                                ">1 = 同速度下步频更高，<1 = 更低。范围 0.2~3.0"
                    },
                    commit = { value ->
                        context.cadenceScale = value.toFloat()
                        renderRows()
                        refreshReadout()
                    },
                ),
                // 外周模拟·步频侧的两条波动参数（%）：与噪声档两层，这里只调"抖多少"
                wobbleRow(
                    title = getString(R.string.wobble_amp),
                    desc = getString(R.string.wobble_amp_desc),
                    get = { context.cadenceWobbleAmp },
                    set = { context.cadenceWobbleAmp = it },
                    hint = getString(R.string.wobble_amp_hint_cadence),
                ),
                wobbleRow(
                    title = getString(R.string.wobble_rnd),
                    desc = getString(R.string.wobble_rnd_desc),
                    get = { context.cadenceWobbleRnd },
                    set = { context.cadenceWobbleRnd = it },
                    hint = getString(R.string.wobble_rnd_hint_cadence),
                ),
            ),
        )
    }

    /**
     * 一条波动参数行（%）：保存后**立刻下发**，与开关同一条命令。
     *
     * 下发失败必须说出来 —— 静默失败会变成"改了却没生效"的骗人开关（本项目的既有教训）。
     */
    private fun wobbleRow(
        title: String,
        desc: String,
        get: () -> Float,
        set: (Float) -> Unit,
        hint: String,
    ) = NumberRow(
        title = title,
        desc = desc,
        display = { "%.0f%%".format(get()) },
        current = { get().toDouble() },
        hint = { hint },
        commit = { value ->
            set(value.toFloat())
            renderRows()
            val ctx = requireContext()
            val r = ConfigSync.setSensorMock(
                ctx, ctx.getSystemService(LocationManager::class.java), ctx.cadenceMock, ctx.orientationMock
            )
            if (!r.isOk) {
                android.widget.Toast.makeText(ctx, "波动参数下发失败：$r", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
    )

    /**
     * 顶部读数：**直接问公式要结果**，不在这里复算一遍。
     * 顺带把当前基础速度摊出来 —— 步频与它强耦合，藏在 Setting 页里看不见才是问题。
     */
    private fun refreshReadout() {
        val context = requireContext()
        val speed = context.speed
        val cadence = FakeLoc.cadenceForSpeed(speed)
        binding.cadenceReadout.text = getString(
            R.string.cadence_readout,
            "%.2f".format(speed),
            cadence,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
