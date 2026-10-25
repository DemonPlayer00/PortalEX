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
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.service.FeatureDependency
import moe.fuqiuluo.portalex.service.MockServiceHelper

/** 基站 + Wi-Fi 合并后的基带模拟页。 */
class BasebandMockFragment : Fragment() {
    private var _binding: FragmentBasebandMockBinding? = null
    private val binding get() = _binding!!

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
        binding.basebandSwitch.setOnCheckedChangeListener { _, on -> push(on) }
        return binding.root
    }

    private fun push(enabled: Boolean) {
        val ctx = requireContext()
        val mcc = binding.basebandMcc.text?.toString().orEmpty()
        val mnc = binding.basebandMnc.text?.toString().orEmpty()
        val cid = binding.basebandCid.text?.toString().orEmpty()
        val tac = binding.basebandTac.text?.toString().orEmpty()
        val ssid = binding.basebandSsid.text?.toString().orEmpty()
        val bssid = binding.basebandBssid.text?.toString().orEmpty()
        if (enabled && !FeatureDependency.isRouteActive(ctx)) {
            // 先记住意图：一类（路线/GNSS）启用时由 FeatureDependency 统一真正启动。
            ctx.sharedPrefs.edit()
                .putBoolean("sim_baseband_enabled", true)
                .putBoolean("sim_cell_enabled", true)
                .putBoolean("sim_wifi_enabled", true)
                .putString("sim_cell_v1", "$mcc/$mnc")
                .putString("sim_cell_v2", "$cid/$tac")
                .putString("sim_wifi_v1", ssid)
                .putString("sim_wifi_v2", bssid)
                .apply()
            android.widget.Toast.makeText(
                ctx, R.string.baseband_waiting_master, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        ctx.sharedPrefs.edit()
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
                MockServiceHelper.setSimConfig(lm, "cell", enabled, "$mcc/$mnc", "$cid/$tac")
                MockServiceHelper.setSimConfig(lm, "wifi", enabled, ssid, bssid)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
