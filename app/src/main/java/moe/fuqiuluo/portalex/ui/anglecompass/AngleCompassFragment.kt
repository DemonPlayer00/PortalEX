package moe.fuqiuluo.portalex.ui.anglecompass

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentAngleCompassBinding
import moe.fuqiuluo.portalex.ext.orientationMock
import moe.fuqiuluo.portalex.ext.sensorNoise
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.SensorNoise

/**
 * **角度计和指南针模拟**页。
 *
 * ## 为什么这一页目前只有只读状态
 *
 * 用户口径：只搬「Setting」页里属于本域的**设置项**；噪声档不搬，与其它设置耦合的项也不搬。
 * 而「Setting」页里**没有**方向/角度类设置项 —— 本域的全部数值（磁场 σ、方向角 σ、
 * 旋转矢量 σ）都属于**传感器噪声档**，按口径留在「Calibration」页；把传感器事件替换掉的
 * 开关（Binder 外周传感器模拟）是全局项，也留在「Setting」页。
 *
 * ⇒ 这一页先作为**只读状态页**：把"当前注入的是什么"摊出来，并指明去哪改。以后真有本域
 * 设置项（例如朝向跟随策略）时直接往这里加行即可 —— 行模板与输入对话框已是三个页面共用的。
 *
 * ## 三路噪声各是什么
 *
 *  · **磁场 σ**（µT）：指南针的原始量，逐轴高斯抖动 ⇒ 罗盘抖动幅度
 *  · **方向角 σ**（rad）：融合出来的朝向抖动 ⇒ 表盘会不会飘
 *  · **旋转矢量 σ**：姿态四元数抖动 ⇒ AR / 精细方向类应用
 */
class AngleCompassFragment : Fragment() {

    private var _binding: FragmentAngleCompassBinding? = null
    private val binding get() = _binding!!

    /** 本域的三路噪声档（只读展示用；编辑仍在 Calibration 页） */
    private val angleItems
        get() = SensorNoise.ITEMS.filter {
            it.start == SensorNoise.MAG || it.start == SensorNoise.ORIENT || it.start == SensorNoise.ROTVEC
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAngleCompassBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // 噪声档可能在 Calibration 页刚被改过（含一键校准会连磁场一起填），回来重读
        refreshStatus()
    }

    /** 只读：当前注入档位 + 注入开关 + 当前朝向。改值请去 Calibration / Setting */
    private fun refreshStatus() {
        val context = requireContext()
        val values = context.sensorNoise
        val bearing = if (FakeLoc.hasBearings) {
            "%.1f°".format(FakeLoc.bearing)
        } else {
            "未显式下发（跟随路线切线 / 摇杆方向）"
        }
        binding.angleReadout.text = buildString {
            append(getString(R.string.angle_readout_injection, context.orientationMock))
            append('\n')
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
