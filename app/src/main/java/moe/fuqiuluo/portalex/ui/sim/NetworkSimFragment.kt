package moe.fuqiuluo.portalex.ui.sim

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentSimInfoBinding
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.service.MockServiceHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 三个侧栏页面的共用骨架：GPS 状态码 / Cell 基站 / Wi-Fi 信息。 */
class NetworkSimFragment : Fragment() {
    private var _binding: FragmentSimInfoBinding? = null
    private val binding get() = _binding!!
    private var kind: String = "gps"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSimInfoBinding.inflate(inflater, container, false)
        kind = arguments?.getString(ARG_KIND) ?: "gps"
        val ctx = requireContext()
        val title = when (kind) {
            "cell" -> getString(R.string.sim_cell_title)
            "wifi" -> getString(R.string.sim_wifi_title)
            else -> getString(R.string.sim_gps_title)
        }
        val desc = when (kind) {
            "cell" -> getString(R.string.sim_cell_desc)
            "wifi" -> getString(R.string.sim_wifi_desc)
            else -> getString(R.string.sim_gps_desc)
        }
        val hint = when (kind) {
            "cell" -> getString(R.string.sim_cell_hint)
            "wifi" -> getString(R.string.sim_wifi_hint)
            else -> getString(R.string.sim_gps_hint)
        }
        binding.simDesc.text = desc
        binding.simHint.text = hint
        val label1 = when (kind) {
            "cell" -> getString(R.string.sim_cell_param1)
            "wifi" -> getString(R.string.sim_wifi_param1)
            else -> getString(R.string.sim_gps_param1)
        }
        val label2 = when (kind) {
            "cell" -> getString(R.string.sim_cell_param2)
            "wifi" -> getString(R.string.sim_wifi_param2)
            else -> getString(R.string.sim_gps_param2)
        }
        (binding.simValue1.parent.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = label1
        (binding.simValue2.parent.parent as? com.google.android.material.textfield.TextInputLayout)?.hint = label2
        binding.simValue1.setText(ctx.sharedPrefs.getString("sim_${kind}_v1", ""))
        binding.simValue2.setText(ctx.sharedPrefs.getString("sim_${kind}_v2", ""))
        val key = "sim_${kind}_enabled"
        binding.simSwitch.isChecked = ctx.sharedPrefs.getBoolean(key, false)
        binding.simSwitch.setOnCheckedChangeListener { _, checked ->
            ctx.sharedPrefs.edit().putBoolean(key, checked).apply()
            ctx.sharedPrefs.edit()
                .putString("sim_${kind}_v1", binding.simValue1.text?.toString().orEmpty())
                .putString("sim_${kind}_v2", binding.simValue2.text?.toString().orEmpty())
                .apply()
            binding.simHint.text = if (checked) "$hint\n已启用" else "$hint\n未启用"
            if (kind == "gps" || kind == "wifi") {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            MockServiceHelper.setSimConfig(
                                lm, kind, checked,
                                binding.simValue1.text?.toString().orEmpty(),
                                binding.simValue2.text?.toString().orEmpty()
                            )
                            if (checked) {
                                if (kind == "gps") MockServiceHelper.startGnssMock(lm)
                                else MockServiceHelper.startWifiMock(lm)
                            } else {
                                if (kind == "gps") MockServiceHelper.stopGnssMock(lm)
                                else MockServiceHelper.stopWifiMock(lm)
                            }
                        }
                    }
                }
            }
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
