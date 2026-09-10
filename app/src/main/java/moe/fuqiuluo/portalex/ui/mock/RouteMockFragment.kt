package moe.fuqiuluo.portalex.ui.mock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.widget.FabBarView
import moe.fuqiuluo.portalex.android.widget.RockerView
import moe.fuqiuluo.portalex.android.window.OverlayUtils
import moe.fuqiuluo.portalex.databinding.FragmentRouteMockBinding
import moe.fuqiuluo.portalex.ext.altitude
import moe.fuqiuluo.portalex.ext.drawOverOtherAppsEnabled
import moe.fuqiuluo.portalex.ext.jsonHistoricalRoutes
import moe.fuqiuluo.portalex.ext.selectRoute
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel
import moe.fuqiuluo.xposed.utils.FakeLoc
import androidx.navigation.findNavController

class RouteMockFragment : Fragment() {
    private var _binding: FragmentRouteMockBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteMockBinding.inflate(inflater, container, false)

        if (mockServiceViewModel.isServiceStart()) {
            binding.switchMock.text = "停止模拟"
            ContextCompat.getDrawable(requireContext(), R.drawable.rounded_play_disabled_24)?.let {
                binding.switchMock.icon = it
            }
        }

        binding.switchMock.setOnClickListener {
            if (mockServiceViewModel.isServiceStart()) {
                tryCloseService(it as MaterialButton)
            } else {
                tryOpenService(it as MaterialButton)
            }
        }

        with(mockServiceViewModel) {
            // 打勾框只反映悬浮摇杆真实状态（rocker.isStart 为唯一事实源）——
            // 位置模拟/路线模拟共用同一悬浮摇杆，避免盲 toggle 造成跨页状态分叉。
            binding.rocker.isChecked = rocker.isStart
            binding.rocker.setOnClickListener {
                if (locationManager == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isServiceStart()) {
                    Toast.makeText(requireContext(), "请先启动模拟", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!requireContext().drawOverOtherAppsEnabled()) {
                    Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val checkedTextView = it as CheckedTextView
                checkedTextView.toggle()
                lifecycleScope.launch(Dispatchers.Main) {
                    if (checkedTextView.isChecked) {
                        // show() 失败（权限被回收/宿主重建）时回退勾选态，避免状态说谎
                        if (!rocker.show()) {
                            checkedTextView.isChecked = false
                            Toast.makeText(requireContext(), "悬浮摇杆显示失败", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        rocker.hide()
                        rockerCoroutineController.pause()
                    }
                }
            }
            rocker.setRockerListener(object : RockerView.Companion.OnMoveListener {
                override fun onAngle(angle: Double) {
                    // 自动播放中由路线切线控制朝向，手动摇杆不抢占
                    handleRockerAngle(angle)
                }

                override fun onLockChanged(isLocked: Boolean) {
                    isRockerLocked = isLocked
                }

                override fun onFinished() {
                    if (!isRockerLocked) {
                        rockerCoroutineController.pause()
                    }
                }

                override fun onStarted() {
                    rockerCoroutineController.resume()
                }
            })
        }

        requireContext().selectRoute?.let {
            if (it.route.size < 2) {
                // 存储中的选中路线已损坏（端点不足）：清除选中项，不带着幽灵路线进播放器
                requireContext().selectRoute = null
                mockServiceViewModel.clearSelectedRoute()
            } else {
                binding.mockRouteName.text = it.name
                mockServiceViewModel.selectRouteForPlayback(it)
            }
        }


        var locations = requireContext().jsonHistoricalRoutes
//        val routes = Json.decodeFromString<List<HistoricalRoute>>(locations)
        // 如果locations是空字符串，则创建默认
        if (locations.isEmpty()) {
            val defaultRoute = HistoricalRoute(
                "默认路线",
                mutableListOf(Pair(39.908822, 116.397465), Pair(39.907951, 116.397500))
            )
            val defaultRoutes = mutableListOf(defaultRoute)
            requireContext().jsonHistoricalRoutes = HistoricalRoute.listToJson(defaultRoutes)
            locations = requireContext().jsonHistoricalRoutes
        }
        val routes = HistoricalRoute.parseList(locations)

        val historicalRouteAdapter = HistoricalRouteAdapter(routes.sortedBy { it.name }
            .toMutableList()) { route, isLongClick ->
            if (isLongClick) {
                Toast.makeText(requireContext(), "长按", Toast.LENGTH_SHORT).show()
            } else {
                binding.mockRouteName.text = route.name
                // 选中路线 → 立即完整重置播放器（重选同一条也从头开始）
                mockServiceViewModel.selectRouteForPlayback(route)
                requireContext().selectRoute = route

                val lm = mockServiceViewModel.locationManager
                if (lm != null && MockServiceHelper.isMockStart(lm)) {
                    // 取第一个点（脏数据可能没有端点：提示而不是崩溃）
                    val first = route.route.firstOrNull()
                    if (first == null) {
                        Toast.makeText(requireContext(), "路线端点不足，无法定位", Toast.LENGTH_SHORT).show()
                    } else if (MockServiceHelper.setLocation(
                            lm,
                            first.first,
                            first.second
                        )
                    ) {
                        Toast.makeText(requireContext(), "位置更新成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "更新位置失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }


        val recyclerView = binding.historicalRouteList
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = historicalRouteAdapter

        ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                // 滑动过程中条目可能已失效（NO_POSITION = -1）→ 直接忽略，避免越界
                if (position == RecyclerView.NO_POSITION) {
                    return
                }
                val location = historicalRouteAdapter[position]
                with(requireContext()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除路线")
                        .setMessage("确定要删除路线(${location.name})吗？")
                        .setPositiveButton("删除") { _, _ ->
                            historicalRouteAdapter.removeItem(position)
                            // 只删「名称 + 端点」都一致的那一条：同名但内容不同的路线不受影响
                            HistoricalRoute.parseList(jsonHistoricalRoutes)
                                .apply {
                                    removeAll {
                                        it.name == location.name && it.route == location.route
                                    }
                                }
                                .let {
                                    jsonHistoricalRoutes = HistoricalRoute.listToJson(it)
                                }
                            // 删的正是当前选中路线 → 一并清掉选中项与播放器状态（幽灵路线）
                            if (selectRoute == location) {
                                selectRoute = null
                                mockServiceViewModel.clearSelectedRoute()
                                binding.mockRouteName.text = ""
                            }
                            showToast("已删除路线")
                        }
                        .setNegativeButton("取消", { _, _ ->
                            historicalRouteAdapter.notifyItemChanged(position)
                        })
                        .show()
                }
            }
        }).attachToRecyclerView(recyclerView)

        return binding.root
    }


    private fun tryOpenService(button: MaterialButton) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            showToast("请授权悬浮窗权限")
            return
        }

        val selectedRoute = mockServiceViewModel.selectedRoute ?: run {
            showToast("请选择一个路线")
            return
        }

        // 端点不足的脏数据路线无法播放：提前拦截，避免播放器里空转/越界
        if (selectedRoute.route.isEmpty()) {
            showToast("路线端点不足，无法播放")
            return
        }

        val lm = mockServiceViewModel.locationManager ?: run {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            val context = requireContext()
            val speed = context.speed
            val altitude = context.altitude
            val accuracy = FakeLoc.accuracy

            button.isClickable = false
            try {
                withContext(Dispatchers.IO) {
                    if (MockServiceHelper.tryOpenMock(
                            lm,
                            speed,
                            altitude,
                            accuracy
                        )
                    ) {
                        updateMockButtonState(
                            button,
                            "停止模拟",
                            R.drawable.rounded_play_disabled_24
                        )
                    } else {
                        showToast("模拟服务启动失败")
                        return@withContext
                    }

                    val first = selectedRoute.route.firstOrNull()
                    if (first == null) {
                        showToast("路线端点不足，无法更新起点")
                    } else if (MockServiceHelper.setLocation(
                            lm,
                            first.first,
                            first.second
                        )
                    ) {
                        showToast("更新路线起点位置成功")
                    } else {
                        showToast("更新位置失败")
                    }
                }
            } finally {
                button.isClickable = true
            }
        }


    }

    private fun tryCloseService(button: MaterialButton) {
        val lm = mockServiceViewModel.locationManager ?: run {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                val isClosed = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isMockStart(lm)) {
                        showToast("模拟服务未启动")
                        return@withContext false
                    }

                    if (MockServiceHelper.tryCloseMock(lm)) {
                        updateMockButtonState(button, "开始模拟", R.drawable.rounded_play_arrow_24)
                        return@withContext true
                    } else {
                        showToast("模拟服务停止失败")
                        return@withContext false
                    }
                }
                if (isClosed && mockServiceViewModel.rocker.isStart) {
                    mockServiceViewModel.rocker.hide()
                    mockServiceViewModel.rockerCoroutineController.pause()
                    // 视图可能已销毁（返回/切页后协程才回来）：binding 可空访问
                    _binding?.let {
                        it.rocker.isClickable = false
                        it.rocker.isChecked = mockServiceViewModel.rocker.isStart
                        it.rocker.isClickable = true
                    }
                }
            } finally {
                button.isClickable = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 与 MockFragment/HomeFragment 等项目内其它 Fragment 保持一致：释放视图引用
        _binding = null
    }


    override fun onStart() {
        super.onStart()
        // 每次页面可见都对齐悬浮摇杆真实状态：覆盖跨页切换后 Android
        // 视图状态恢复（onCreateView 同步早于 onViewStateRestored）导致的过期勾选。
        binding.rocker.isChecked = mockServiceViewModel.rocker.isStart
    }

    override fun onResume() {
        super.onResume()

        // 注册悬浮胶囊功能集：路线回放 = 添加/编辑路线
        // （胶囊为 Activity 级单实例，切换功能集自动重置收起态）
        (activity as? MainActivity)?.fabBar?.setActions(
            listOf(
                FabBarView.Action(
                    R.drawable.baseline_route_24,
                    getString(R.string.add_route)
                ) {
                    activity?.findNavController(R.id.nav_host_fragment_content_main)
                        ?.navigate(R.id.nav_route_edit)
                }
            )
        )
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun updateMockButtonState(button: MaterialButton, text: String, iconRes: Int) =
        lifecycleScope.launch(Dispatchers.Main) {
            button.text = text
            ContextCompat.getDrawable(requireContext(), iconRes)?.let {
                button.icon = it
            }
        }

}