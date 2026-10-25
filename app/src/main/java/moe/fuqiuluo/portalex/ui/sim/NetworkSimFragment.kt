package moe.fuqiuluo.portalex.ui.sim

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import moe.fuqiuluo.portalex.databinding.FragmentSimInfoBinding
import moe.fuqiuluo.portalex.ext.sharedPrefs

/** 三个侧栏页面的共用骨架：GPS 状态码 / Cell 基站 / Wi-Fi 信息。 */
class NetworkSimFragment : Fragment() {
    private var _binding: FragmentSimInfoBinding? = null
    private val binding get() = _binding!!
    private var kind: String = "gps"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSimInfoBinding.inflate(inflater, container, false)
        kind = arguments?.getString(ARG_KIND) ?: "gps"
        val ctx = requireContext()
        val (title, desc, hint) = when (kind) {
            "cell" -> Triple("Cell 基站信息模拟", "模拟当前驻留基站，供目标应用读取 CellInfo。", "当前配置：MCC 460 / MNC 00 / 模拟基站待下发")
            "wifi" -> Triple("Wi-Fi 信息模拟", "模拟 WLAN 连接信息与扫描结果。", "当前配置：SSID / BSSID / RSSI 待下发")
            else -> Triple("GPS 状态码模拟", "模拟 GPS 定位状态码和卫星状态。", "当前配置：状态码 3 / 卫星状态由 GNSS 引擎提供")
        }
        binding.simTitle.text = title
        binding.simDesc.text = desc
        binding.simHint.text = hint
        val key = "sim_${kind}_enabled"
        binding.simSwitch.isChecked = ctx.sharedPrefs.getBoolean(key, false)
        binding.simSwitch.setOnCheckedChangeListener { _, checked ->
            ctx.sharedPrefs.edit().putBoolean(key, checked).apply()
            binding.simHint.text = if (checked) "$hint\n已启用" else "$hint\n未启用"
            Toast.makeText(ctx, if (checked) "$title 已启用" else "$title 已关闭", Toast.LENGTH_SHORT).show()
        }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_KIND = "sim_kind"
    }
}
