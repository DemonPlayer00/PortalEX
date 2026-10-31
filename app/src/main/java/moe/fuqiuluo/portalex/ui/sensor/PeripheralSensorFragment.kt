package moe.fuqiuluo.portalex.ui.sensor

import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentPeripheralSensorBinding
import moe.fuqiuluo.portalex.ext.cadenceMock
import moe.fuqiuluo.portalex.ext.cadenceScale
import moe.fuqiuluo.portalex.ext.cadenceWobbleAmp
import moe.fuqiuluo.portalex.ext.cadenceWobbleRnd
import moe.fuqiuluo.portalex.ext.orientationMock
import moe.fuqiuluo.portalex.ext.orientationWobbleAmp
import moe.fuqiuluo.portalex.ext.orientationWobbleRnd
import moe.fuqiuluo.portalex.ext.sensorNoise
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.ui.common.NumberRow
import moe.fuqiuluo.portalex.ui.common.SensorNativeGate
import moe.fuqiuluo.portalex.ui.common.renderNumberRows
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.SensorNoise

/**
 * 统一的外周传感器模拟页。
 *
 * 以前步频/加速度与角度/指南针分成两个页面、两个开关；现在由一个开关同时
 * 控制两侧的偏好与下发，避免出现“只开了一侧却以为全开”的状态分裂。
 */
class PeripheralSensorFragment : Fragment() {

    private var _binding: FragmentPeripheralSensorBinding? = null
    private val binding get() = _binding!!

    private val angleItems
        get() = SensorNoise.ITEMS.filter {
            it.start == SensorNoise.MAG || it.start == SensorNoise.ORIENT || it.start == SensorNoise.ROTVEC
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPeripheralSensorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx0 = requireContext()
        // 旧版本可能留下“只开一侧”的偏好；统一页面按 OR 迁移成同一状态。
        val unified = ctx0.cadenceMock || ctx0.orientationMock
        if (ctx0.cadenceMock != ctx0.orientationMock) {
            ctx0.cadenceMock = unified
            ctx0.orientationMock = unified
        }
        binding.peripheralMockSwitch.isChecked = unified
        if (unified) {
            // 默认开启时也要走门禁：库缺失就提示，不允许“开关看起来开着、native 实际没加载”。
            SensorNativeGate.ensure(
                this,
                onReady = { refresh() },
                onBlocked = {
                    ctx0.cadenceMock = false
                    ctx0.orientationMock = false
                    binding.peripheralMockSwitch.isChecked = false
                },
            )
        }
        binding.peripheralMockSwitch.setOnCheckedChangeListener { _, checked ->
            val ctx = requireContext()
            fun push() {
                val r = ConfigSync.setSensorMock(
                    ctx, ctx.getSystemService(LocationManager::class.java),
                    ctx.cadenceMock, ctx.orientationMock,
                )
                if (!r.isOk) {
                    android.widget.Toast.makeText(
                        ctx, "外周模拟下发失败：$r", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            if (!checked) {
                ctx.cadenceMock = false
                ctx.orientationMock = false
                push()
                return@setOnCheckedChangeListener
            }
            SensorNativeGate.ensure(
                this,
                onReady = {
                    ctx.cadenceMock = true
                    ctx.orientationMock = true
                    push()
                    refresh()
                },
                onBlocked = {
                    ctx.cadenceMock = false
                    ctx.orientationMock = false
                    binding.peripheralMockSwitch.isChecked = false
                },
            )
        }
        binding.sensorNativeUpdate.setOnClickListener {
            SensorNativeGate.update(this) {
                refresh()
            }
        }
        binding.sensorNativeRemove.setOnClickListener {
            SensorNativeGate.remove(this) {
                val ctx = requireContext()
                ctx.cadenceMock = false
                ctx.orientationMock = false
                binding.peripheralMockSwitch.isChecked = false
                refresh()
            }
        }
        renderRows()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        renderRows()
        refresh()
    }

    private fun renderRows() {
        val context = requireContext()
        renderNumberRows(
            layoutInflater, binding.peripheralRows,
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
                        refresh()
                    },
                ),
                wobbleRow(
                    getString(R.string.wobble_amp), getString(R.string.wobble_amp_desc),
                    get = { context.cadenceWobbleAmp },
                    set = { context.cadenceWobbleAmp = it },
                    hint = getString(R.string.wobble_amp_hint_cadence),
                ),
                wobbleRow(
                    getString(R.string.wobble_rnd), getString(R.string.wobble_rnd_desc),
                    get = { context.cadenceWobbleRnd },
                    set = { context.cadenceWobbleRnd = it },
                    hint = getString(R.string.wobble_rnd_hint_cadence),
                ),
                wobbleRow(
                    getString(R.string.wobble_amp), getString(R.string.wobble_amp_desc),
                    get = { context.orientationWobbleAmp },
                    set = { context.orientationWobbleAmp = it },
                    hint = getString(R.string.wobble_amp_hint_orientation),
                ),
                wobbleRow(
                    getString(R.string.wobble_rnd), getString(R.string.wobble_rnd_desc),
                    get = { context.orientationWobbleRnd },
                    set = { context.orientationWobbleRnd = it },
                    hint = getString(R.string.wobble_rnd_hint_orientation),
                ),
            ),
        )

    }

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
                ctx, ctx.getSystemService(LocationManager::class.java),
                ctx.cadenceMock, ctx.orientationMock,
            )
            if (!r.isOk) {
                android.widget.Toast.makeText(
                    ctx, "波动参数下发失败：$r", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        },
    )

    private fun refresh() {
        val context = requireContext()
        binding.cadenceReadout.text = getString(
            R.string.cadence_readout,
            "%.2f".format(context.speed),
            FakeLoc.cadenceForSpeed(context.speed),
        )
        val values = context.sensorNoise
        val bearing = if (FakeLoc.hasBearings) "%.1f°".format(FakeLoc.bearing)
        else "未显式下发（跟随路线切线 / 摇杆方向）"
        binding.angleReadout.text = buildString {
            angleItems.forEach { item ->
                append(item.title).append('：').append(SensorNoise.formatItem(item, values))
                append(' ').append(item.unit).append('\n')
            }
            append(getString(R.string.angle_readout_bearing, bearing))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
