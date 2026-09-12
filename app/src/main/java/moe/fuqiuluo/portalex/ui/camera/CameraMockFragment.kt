package moe.fuqiuluo.portalex.ui.camera

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.widget.FabBarView
import moe.fuqiuluo.portalex.databinding.FragmentCameraMockBinding
import moe.fuqiuluo.portalex.ui.FabBarAvoidanceHost




import moe.fuqiuluo.xposed.camera.CameraMockSelector
import moe.fuqiuluo.xposed.camera.CameraTags
import moe.fuqiuluo.xposed.utils.WorldMath

/**
 * **摄像头模拟页**：与路线模拟页同构 —— 顶部状态卡（启动开关）+ 标签筛选 + 下方视频点列表。
 *
 * 本页的"启动"没有本地服务：它只是把清单里的 `enabled` 置位。真正的拦截发生在**目标应用
 * 自己的进程里**（模块侧 hook），因此这里额外显示"预计命中哪个点"，让用户不装测试应用
 * 也能看出规则有没有按预期工作。
 */
class CameraMockFragment : Fragment(), FabBarAvoidanceHost {

    companion object {
        const val ARG_POINT_ID = "pointId"
    }

    private var _binding: FragmentCameraMockBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CameraPointAdapter

    private val mockServiceViewModel by activityViewModels<moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel>()

    /** 重建筛选胶囊时抑制回调：先清空再填充会触发一次"全部取消勾选"，不该被当成用户操作 */
    private var rebuildingFilter = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraMockBinding.inflate(inflater, container, false)

        adapter = CameraPointAdapter(mutableListOf()) { point ->
            findNavController().navigate(
                R.id.nav_camera_point_edit,
                bundleOf(ARG_POINT_ID to point.id)
            )
        }
        binding.cameraPointList.layoutManager = LinearLayoutManager(requireContext())
        binding.cameraPointList.adapter = adapter

        binding.switchCameraMock.setOnClickListener { toggleMock() }
        // 说明与目标应用放进对话框：它们"读一次就够"，留在页面上会把视频点列表挤没
        binding.cameraMockInfo.setOnClickListener { showInfoDialog() }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        avoidFabBar()
        (activity as? MainActivity)?.fabBar?.setActions(
            listOf(
                FabBarView.Action(
                    R.drawable.baseline_add_location_alt_24,
                    getString(R.string.camera_point_new)
                ) {
                    // 不带参数 = 新建：点位坐标取当前地图中心（编辑页里再微调）
                    findNavController().navigate(R.id.nav_camera_point_edit)
                },
                FabBarView.Action(
                    R.drawable.baseline_settings_24,
                    getString(R.string.camera_mock_targets)
                ) {
                    showTargetsDialog()
                }
            )
        )
        /* 页面回到前台时对齐一次产帧线程：开关可能是在别处（或上一次进程）打开的，
         * 而"开着的开关"必须对应一个真的在产帧的线程，否则 HAL 那边会一直发最后一帧 */
        CameraMockVideoSource.sync(requireContext())
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        val context = requireContext()
        val points = CameraMockStore.points(context).sortedBy { it.name }
        adapter.replaceAll(points)
        rebuildFilterChips()
        updateSwitch()
        updateStatus(points)
    }

    private fun rebuildFilterChips() {
        val context = requireContext()
        val group: ChipGroup = binding.cameraMockFilter
        val selected = context.cameraMockTagFilter
        rebuildingFilter = true
        group.removeAllViews()
        CameraMockStore.allTags(context).forEach { tag ->
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.item_camera_tag_chip, group, false) as Chip
            chip.text = tag
            chip.isChecked = selected.any { it.equals(tag, ignoreCase = true) }
            chip.setOnCheckedChangeListener { _, _ ->
                if (rebuildingFilter) return@setOnCheckedChangeListener
                val checked = (0 until group.childCount)
                    .map { group.getChildAt(it) as Chip }
                    .filter { it.isChecked }
                    .map { it.text.toString() }
                context.cameraMockTagFilter = CameraTags.sanitize(checked).toSet()
                updateSwitch()
                updateStatus(CameraMockStore.points(context))
            }
            group.addView(chip)
        }
        rebuildingFilter = false
    }

    private fun updateSwitch() {
        val enabled = requireContext().cameraMockEnabled
        binding.switchCameraMock.text =
            getString(if (enabled) R.string.camera_mock_stop else R.string.camera_mock_start)
        ContextCompat.getDrawable(
            requireContext(),
            if (enabled) R.drawable.rounded_play_disabled_24 else R.drawable.rounded_play_arrow_24
        )?.let { binding.switchCameraMock.icon = it }
    }

    private fun updateStatus(points: List<moe.fuqiuluo.xposed.camera.CameraVideoPoint>) {
        val context = requireContext()
        val enabled = context.cameraMockEnabled
        val filter = context.cameraMockTagFilter
        val videos = points.sumOf { it.videos.size }
        binding.cameraMockStatus.text = if (enabled) {
            "已启动 · ${points.size} 个点位 / $videos 段视频"
        } else {
            "未启动 · ${points.size} 个点位 / $videos 段视频"
        }

        // 预计命中：把模块侧的选片规则在本页复算一遍（位置未知时不猜）
        val position = CameraMockRuntime.position()
        val manifest = CameraMockStore.manifest(context)
        binding.cameraMockPosition.text = when {
            !enabled -> getString(R.string.camera_mock_position_idle)
            position == null -> getString(R.string.camera_mock_position_unknown)
            else -> {
                val hits = manifest.points.mapNotNull { p ->
                    val matched = p.videos.count { CameraMockSelector.matches(it.tags, manifest.tagFilter) }
                    if (matched == 0) null else Triple(p, matched, WorldMath.haversine(position.first, position.second, p.lat, p.lon))
                }
                val nearest = hits.minByOrNull { it.third }
                if (nearest == null) {
                    "筛选「${if (filter.isEmpty()) "全部" else filter.joinToString("、")}」下没有可用视频 —— 目标应用将看到真实摄像头"
                } else {
                    "当前模拟位置 ${CameraMockFormat.coord(position.first, position.second)} → 命中「${nearest.first.name}」" +
                            "（${nearest.second} 段匹配，距离 ${nearest.third.toInt()} m）"
                }
            }
        }

    }

    /** ⓘ：玩法说明 + 当前目标应用 + 系统侧观测到的摄像头活动（页面顶部只留"一眼要看"的三行） */
    private fun showInfoDialog() {
        val context = requireContext()
        val targets = context.cameraMockTargets
        val targetLine = if (targets.isEmpty()) {
            getString(R.string.camera_mock_targets_unset)
        } else {
            getString(R.string.camera_mock_targets_value, targets.joinToString("、"))
        }
        // 控制面回读：system_server 侧（AppOps + CameraServiceProxy）看到的相机活动，
        // 对目标应用零注入 —— 拦截尚未接入时，这块就是"能不能看见"的全部依据
        val events = runCatching {
            val lm = mockServiceViewModel.locationManager
            if (lm == null) null else moe.fuqiuluo.portalex.service.MockServiceHelper.getCameraEvents(lm)
        }.getOrNull()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.camera_mock)
            .setMessage(
                getString(R.string.camera_mock_hint) + "\n\n" + targetLine +
                        "\n\n最近摄像头活动（系统侧观测，零应用注入）：\n" + (events ?: "（暂无：模拟未运行或系统服务未就绪）")
            )
            .setPositiveButton(R.string.got_it, null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun toggleMock() {
        val context = requireContext()
        val next = !context.cameraMockEnabled
        context.cameraMockEnabled = next
        /* 开关直接决定产帧线程的启停：关掉即让 HAL 停止接管（露出真实相机） */
        CameraMockVideoSource.sync(context)
        updateSwitch()
        updateStatus(CameraMockStore.points(context))
        val hint = if (next) {
            if (context.cameraMockTargets.isEmpty()) {
                R.string.camera_mock_started_open
            } else {
                R.string.camera_mock_started_targeted
            }
        } else {
            R.string.camera_mock_stopped
        }
        Toast.makeText(context, getString(hint), Toast.LENGTH_LONG).show()
    }

    /**
     * 目标应用清单：**空 = 不限制**（凡被注入且开关打开的进程都命中）。
     * 用一行文本编辑而不是应用选择器：包名是我们唯一能可靠比对的东西，
     * 而"列出已安装应用"会引入 `QUERY_ALL_PACKAGES` 这类与项目立场冲突的权限。
     */
    private fun showTargetsDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(context.cameraMockTargets.joinToString("\n"))
            hint = "com.example.app"
            setSingleLine(false)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.camera_mock_targets)
            .setMessage(R.string.camera_mock_targets_desc)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                context.cameraMockTargets = input.text.toString()
                    .split('\n', ',', ' ', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    /**
     * 列表卡与标题避让悬浮胶囊（与路线回放页同一套规则：竖屏让底部、横屏让左侧）。
     * 本页在 configChanges 下不重新 inflate，所以要被 Activity 在旋转时叫一次。
     */
    override fun avoidFabBar() {
        val fabBar = (activity as? MainActivity)?.fabBar ?: return
        val card = _binding?.cameraPointCard ?: return
        val lp = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return

        val corner = resources.getDimensionPixelSize(R.dimen.fab_corner_margin)
        val gap = resources.getDimensionPixelSize(R.dimen.fab_avoid_gap)
        val base = resources.getDimensionPixelSize(R.dimen.fab_sub_margin)
        val avoid = corner + fabBar.collapsedSize + gap
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wantStart = if (landscape) avoid else base
        val wantBottom = if (landscape) base else avoid
        if (lp.marginStart != wantStart || lp.bottomMargin != wantBottom) {
            lp.marginStart = wantStart
            lp.bottomMargin = wantBottom
            card.layoutParams = lp
        }

        val label = _binding?.cameraPointLabel ?: return
        val labelLp = label.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val labelMargin = resources.getDimensionPixelSize(R.dimen.history_label_margin)
        val labelOffset = labelMargin - base
        val wantLabelStart = if (landscape) avoid + labelOffset else labelMargin
        if (labelLp.marginStart != wantLabelStart) {
            labelLp.marginStart = wantLabelStart
            label.layoutParams = labelLp
        }
    }
}
