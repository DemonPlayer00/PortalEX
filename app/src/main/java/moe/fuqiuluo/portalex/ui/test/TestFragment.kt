package moe.fuqiuluo.portalex.ui.test

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentTestBinding
import moe.fuqiuluo.portalex.ext.accuracy
import moe.fuqiuluo.portalex.ext.allowLandscape
import moe.fuqiuluo.portalex.ext.binderSensorMock
import moe.fuqiuluo.portalex.ext.minSatelliteCount
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.service.StepProbe
import moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel

/**
 * Test：数值总览页。
 *
 * 只做一件事——把**两侧的原始数值**摊开，便于对着目标应用看到的数比：
 *  · 系统侧（system_server，权威）：注入层是否装载、压制的真实事件数、**实际发出的步事件
 *    换算出的步频**、学到的 type→handle 映射、运动学量（速度/朝向/移动判定/步数）；
 *  · 应用侧（本进程）：偏好设置与模拟会话是否在跑。
 *
 * 为什么需要它：排查"应用显示 277 步/分、理论只有 178"这类问题时，最需要知道的是
 * **意图步频**（按设定速度算）与**实际发出的步频**（模块真的推了多少）是否一致——
 * 一致就说明问题在应用侧的算法，不一致才轮到模块。此前只能靠 adb + logcat 拼，
 * 这个页面把它变成一屏。
 */
class TestFragment : Fragment() {

    private var _binding: FragmentTestBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private var refreshJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // "普通应用视角"探针：真订阅步数传感器（进入本页即开始，离开即停）
        runCatching { StepProbe.start(requireContext().applicationContext) }
            .onFailure { android.util.Log.w("TestFragment", "StepProbe start failed", it) }
        if (refreshJob?.isActive == true) return
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        // 离开本页即停掉"普通应用视角"探针（真订阅，别在后台白耗）
        runCatching { StepProbe.stop() }
        super.onPause()
    }

    private suspend fun refresh() {
        val ctx = context ?: return
        val text = buildString {
            appendLine("═══ 应用侧（本进程）═══")
            appendLine("包名           ${ctx.packageName}")
            appendLine("实验开关       ${if (ctx.binderSensorMock) "开" else "关"}")
            appendLine("设定速度       ${"%.2f".format(ctx.speed)} m/s")
            appendLine("上报间隔       ${ctx.reportDuration} ms")
            appendLine("模拟精度/星数  ${"%.1f".format(ctx.accuracy)} m / ${ctx.minSatelliteCount}")
            appendLine("允许横屏       ${ctx.allowLandscape}")
            appendLine("服务已握手     ${MockServiceHelper.isServiceInit()}")
            appendLine("会话运行中     ${mockServiceViewModel.isServiceStart()}")
            appendLine("作用域         LSPosed 决定；本页只统计系统侧数据")

            val lm = mockServiceViewModel.locationManager
            if (lm == null) {
                appendLine()
                appendLine("!! 定位服务未就绪（权限未授予时 locationManager 为空），")
                appendLine("   无法向系统侧查询。请授予权限后重进本页。")
                return@buildString
            }

            val status = withContext(Dispatchers.IO) { MockServiceHelper.getSensorStatus(lm) }
            if (status == null) {
                appendLine()
                appendLine("!! 系统侧无应答（模块未在 system_server 内活动？）")
                return@buildString
            }

            appendLine()
            appendLine("═══ 系统侧（system_server，权威）═══")
            appendLine("实验开关       ${yn(status.getBoolean("flag"))}")
            appendLine("模拟会话       ${yn(status.getBoolean("mock_running"))}")
            appendLine("原生层装载     ${yn(status.getBoolean("native_ready"))}")
            appendLine("注入激活       ${yn(status.getBoolean("active"))}")
            appendLine()
            appendLine("── 步频：意图 vs 实际 ──")
            appendLine("意图步频       ${status.getInt("cadence_intent")} 步/分（按实测速度算）")
            appendLine("设定速度       ${"%.2f".format(status.getDouble("configured_speed"))} m/s")
            appendLine("实测速度       ${"%.2f".format(status.getDouble("measured_speed"))} m/s")
            appendLine("移动判定       ${yn(status.getBoolean("moving"))}")
            appendLine("步数累计       ${status.getLong("steps_total")}")
            appendLine("开机总步数     ${status.getLong("steps_boot")}（我们推送的 STEP_COUNTER 值；本次会话起点 ${status.getLong("steps_base")}）")
            appendLine()
            appendLine("── 注入计数（原生层）──")
            appendLine(prettyNative(status.getString("native")))
            appendLine()
            appendLine("── 运行时投递通道（投递 100% 可控那条路）──")
            appendLine(prettyNative(status.getString("rt_channel")))
            appendLine()
            appendLine("── 普通应用视角（本进程真订阅，每 5s 轮询一次总步数）──")
            appendLine(StepProbe.status())
            appendLine()
            appendLine("── 位置模拟 ──")
            appendLine("坐标           ${"%.6f".format(status.getDouble("lat"))}, ${"%.6f".format(status.getDouble("lon"))}")
            appendLine("海拔           ${"%.2f".format(status.getDouble("altitude"))} m")
            appendLine("朝向目标/帧    ${"%.1f".format(status.getDouble("bearing_target"))}° / ${"%.1f".format(status.getDouble("bearing_frame"))}°")
            appendLine("GNSS 模拟      ${yn(status.getBoolean("gnss_mock"))}")
        }
        withContext(Dispatchers.Main) {
            _binding?.testOutput?.text = text
        }
    }

    private fun yn(b: Boolean) = if (b) "是" else "否"

    /** 原生层 status 是一行长串：按空格拆开，每项一行，便于看 */
    private fun prettyNative(raw: String?): String {
        if (raw.isNullOrBlank()) return "（无）"
        return raw.split(' ').filter { it.isNotBlank() }.joinToString("\n") { "  $it" }
    }

    override fun onDestroyView() {
        refreshJob?.cancel()
        refreshJob = null
        _binding = null
        super.onDestroyView()
    }
}
