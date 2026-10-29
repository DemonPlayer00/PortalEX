package moe.fuqiuluo.portalex.ui.baseband

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentBasebandMockBinding
import moe.fuqiuluo.portalex.ext.cellEntries
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.ext.wifiEntries
import moe.fuqiuluo.portalex.service.MockServiceHelper

/** 基站 + Wi-Fi 合并后的基带模拟页。 */
class BasebandMockFragment : Fragment() {
    private var _binding: FragmentBasebandMockBinding? = null
    private val binding get() = _binding!!
    private var switchListenerAttached = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentBasebandMockBinding.inflate(inflater, container, false)
        val prefs = requireContext().sharedPrefs
        val cell = prefs.getString("sim_cell_v1", "460/00").orEmpty().split("/", limit = 2)
        val cell2 = prefs.getString("sim_cell_v2", "123456789/1234").orEmpty().split("/", limit = 2)
        binding.basebandMcc.setText(cell.getOrNull(0).orEmpty())
        binding.basebandMnc.setText(cell.getOrNull(1).orEmpty())
        binding.basebandCid.setText(cell2.getOrNull(0).orEmpty())
        binding.basebandTac.setText(cell2.getOrNull(1).orEmpty())
        binding.basebandSsid.setText(prefs.getString("sim_wifi_v1", "CMCC-EDU"))
        binding.basebandBssid.setText(prefs.getString("sim_wifi_v2", "02:00:00:00:00:00"))
        binding.basebandSwitch.isChecked = prefs.getBoolean("sim_baseband_enabled", false)
        // 监听器**不能**在这里挂：Fragment 的 view state 恢复发生在 onCreateView 之后，
        // 恢复 checked 状态会触发 OnCheckedChangeListener → 自动 push 一次配置。
        // 改为 onResume/onViewStateRestored 之后挂（见 attachSwitchListener）。
        // 长按开关 = 清空「校准收集」的多条目列表，回到手填的单条配置
        binding.basebandSwitch.setOnLongClickListener {
            requireContext().cellEntries = emptyList()
            requireContext().wifiEntries = emptyList()
            android.widget.Toast.makeText(
                requireContext(), getString(R.string.baseband_collection_cleared), android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }
        return binding.root
    }

    /**
     * 在视图状态恢复完成后才挂开关监听器。
     *
     * Android 会把 `SwitchMaterial` 的 checked 状态作为 view state 恢复，恢复动作发生在
     * onCreateView 之后；如果监听器那时已经挂上，恢复过程就会触发 `push()`，产生
     * "点进页面就改配置"的副作用。
     */
    private fun attachSwitchListener() {
        if (switchListenerAttached) return
        binding.basebandSwitch.setOnCheckedChangeListener { _, on -> push(on) }
        switchListenerAttached = true
    }

    override fun onResume() {
        super.onResume()
        attachSwitchListener()
        // 这里**不再弹** "已收集 N 条" 的 toast：它读的是历史列表，不是本次扫描，
        // 每次进页面都弹会被误认为"自动收集了附近 Wi-Fi"。
    }

    private fun push(enabled: Boolean) {
        val ctx = requireContext()
        val mcc = binding.basebandMcc.text?.toString().orEmpty()
        val mnc = binding.basebandMnc.text?.toString().orEmpty()
        val cid = binding.basebandCid.text?.toString().orEmpty()
        val tac = binding.basebandTac.text?.toString().orEmpty()
        val ssid = binding.basebandSsid.text?.toString().orEmpty()
        val bssid = binding.basebandBssid.text?.toString().orEmpty()
        val prefs = ctx.sharedPrefs
        // 监听器只应由用户操作触发；万一别处又触发了，值没变就不要重写偏好/重发命令。
        val unchanged = prefs.getBoolean("sim_baseband_enabled", false) == enabled &&
                prefs.getString("sim_cell_v1", "").orEmpty() == "$mcc/$mnc" &&
                prefs.getString("sim_cell_v2", "").orEmpty() == "$cid/$tac" &&
                prefs.getString("sim_wifi_v1", "").orEmpty() == ssid &&
                prefs.getString("sim_wifi_v2", "").orEmpty() == bssid
        if (unchanged) return
        prefs.edit()
            .putBoolean("sim_baseband_enabled", enabled)
            .putBoolean("sim_cell_enabled", enabled)
            .putBoolean("sim_wifi_enabled", enabled)
            .putString("sim_cell_v1", "$mcc/$mnc")
            .putString("sim_cell_v2", "$cid/$tac")
            .putString("sim_wifi_v1", ssid)
            .putString("sim_wifi_v2", bssid)
            .apply()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // 收集过列表就带上（列表优先，首条是"当前连接"），否则发手填的单条
                val cells = ctx.cellEntries
                val wifis = ctx.wifiEntries
                MockServiceHelper.setSimConfig(
                    lm, "cell", enabled, "$mcc/$mnc", "$cid/$tac",
                    cells.ifEmpty { null }
                )
                MockServiceHelper.setSimConfig(
                    lm, "wifi", enabled, ssid, bssid,
                    wifis.ifEmpty { null }
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        switchListenerAttached = false
        _binding = null
    }
}
