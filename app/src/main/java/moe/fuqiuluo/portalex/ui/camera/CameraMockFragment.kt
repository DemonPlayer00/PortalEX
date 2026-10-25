package moe.fuqiuluo.portalex.ui.camera

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
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
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.Overlay
import com.baidu.mapapi.map.OverlayOptions
import com.baidu.mapapi.map.PolylineOptions
import moe.fuqiuluo.portalex.ext.gcj02
import moe.fuqiuluo.portalex.ext.mapType
import moe.fuqiuluo.portalex.service.FeatureDependency
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.ui.FabBarAvoidanceHost
import moe.fuqiuluo.portalex.ui.MapControlsHost
import moe.fuqiuluo.portalex.ui.viewmodel.BaiduMapViewModel
import moe.fuqiuluo.xposed.camera.CameraVideoPoint




import moe.fuqiuluo.xposed.camera.CameraMockSelector
import moe.fuqiuluo.xposed.camera.CameraTags
import moe.fuqiuluo.xposed.camera.CameraVideoTrackPoint
import org.json.JSONArray
import java.util.concurrent.Executors
import moe.fuqiuluo.xposed.utils.WorldMath

/**
 * **摄像头模拟页**：与路线模拟页同构 —— 顶部状态卡（启动开关）+ 标签筛选 + 下方视频点列表。
 *
 * 本页的"启动"没有本地服务：它只是把清单里的 `enabled` 置位。真正的拦截发生在**目标应用
 * 自己的进程里**（模块侧 hook），因此这里额外显示"预计命中哪个点"，让用户不装测试应用
 * 也能看出规则有没有按预期工作。
 */
class CameraMockFragment : Fragment(), FabBarAvoidanceHost, MapControlsHost {

    companion object {
        const val ARG_POINT_ID = "pointId"

        /** 地图标记的附加数据键（点击标记 → 打开该点的编辑页） */
        private const val EXTRA_POINT_ID = "cameraPointId"
        private const val REQUEST_CAMERA = 2001
    }

    private var _binding: FragmentCameraMockBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CameraPointAdapter

    private val mockServiceViewModel by activityViewModels<moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel>()

    /** 与路线编辑页共用同一个地图实例（同一 Activity 内只有一张地图是"活着"的） */
    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()

    /** 点位覆盖物：刷新时整层重建，避免"删了点位地图上还留着" */
    private var pointOverlays: List<Overlay> = emptyList()

    /** 重建筛选胶囊时抑制回调：先清空再填充会触发一次"全部取消勾选"，不该被当成用户操作 */
    private var rebuildingFilter = false

    private var mapExpanded = false
    private val main = Handler(Looper.getMainLooper())
    private var cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastPoints: List<CameraVideoPoint> = emptyList()
    private var refreshToken = 0
    private var mapDrawToken = 0
    private var pendingRecordAfterPermission = false
    private val cammockImportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val ctx = requireContext()
        setFabLoading(true)
        Executors.newSingleThreadExecutor().execute {
            val result = runCatching { CammockManager.importArchive(ctx, uri) }
                .getOrElse {
                    CammockManager.Install(
                        supported = false, models = emptyList(),
                        message = "导入失败：${it.message}",
                    )
                }
            main.post {
                setFabLoading(false)
                if (!uiAlive()) return@post
                Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                if (!result.supported) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("机型适配警告")
                        .setMessage(result.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                updateImplementationUi()
                refresh()
            }
        }
    }

    private val videoArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val ctx = requireContext()
        setFabLoading(true)
        Executors.newSingleThreadExecutor().execute {
            val result = runCatching { CameraMockArchive.importZip(ctx, uri) }
                .getOrElse { CameraMockArchive.ImportResult(false, message = "导入失败：${it.message}") }
            main.post {
                setFabLoading(false)
                if (result.ok) autoUploadLibrary(ctx)
                if (!uiAlive()) return@post
                Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                if (result.ok) refresh()
            }
        }
    }

    private val recordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val fileName = data.getStringExtra(CameraRecordActivity.RESULT_FILE_NAME)
            ?: return@registerForActivityResult
        val file = CameraMockStore.videoFile(requireContext(), fileName)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "录制文件丢失", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val track = parseTrack(data.getStringExtra(CameraRecordActivity.RESULT_TRACK_JSON))
        val recordedAt = data.getLongExtra(CameraRecordActivity.RESULT_RECORDED_AT, 0L)
        val start = track.firstOrNull()?.let { it.lat to it.lon }
            ?: CameraMockRuntime.position() ?: run {
                Toast.makeText(requireContext(), "录制没有可用位置", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
        val video = CameraMockStore.registerRecorded(
            requireContext(), file, null,
            recordedAt = recordedAt,
            track = track
        )
        val points = CameraMockStore.points(requireContext())
        val previous = points.minByOrNull { p ->
            val end = p.videos.lastOrNull()?.track?.lastOrNull()
            val lat = end?.lat ?: p.lat
            val lon = end?.lon ?: p.lon
            WorldMath.haversine(start.first, start.second, lat, lon)
        }
        val distance = previous?.let { p ->
            val end = p.videos.lastOrNull()?.track?.lastOrNull()
            val lat = end?.lat ?: p.lat
            val lon = end?.lon ?: p.lon
            WorldMath.haversine(start.first, start.second, lat, lon)
        } ?: Double.MAX_VALUE
        if (previous != null && distance <= 80.0) {
            val merged = previous.copy(
                videos = (previous.videos + video).sortedBy { it.recordedAt }
            )
            CameraMockStore.upsert(requireContext(), merged)
            Toast.makeText(
                requireContext(),
                "已追加到「${previous.name}」：${track.size} 个轨迹点",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            val point = CameraMockStore.newPoint(
                "路线 " + java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.getDefault()
                ).format(java.util.Date(recordedAt.takeIf { it > 0 } ?: System.currentTimeMillis())),
                start.first, start.second
            )
            CameraMockStore.upsert(requireContext(), point.copy(videos = listOf(video)))
            Toast.makeText(
                requireContext(),
                "已新建路线：${track.size} 个轨迹点，视频已串联",
                Toast.LENGTH_SHORT
            ).show()
        }
        refresh()
        autoUploadLibrary()
    }

    private fun autoUploadLibrary(context: android.content.Context? = null) {
        val ctx = context ?: if (uiAlive()) requireContext() else return
        val ids = CameraMockStore.routes(ctx).flatMap { r -> r.videos.map { it.id } }
        Executors.newSingleThreadExecutor().execute {
            val result = CammockManager.upload(ctx)
            main.post {
                result.onSuccess { n ->
                    CameraMockUploadState.markUploaded(ctx, ids)
                    if (!uiAlive()) return@onSuccess
                    Toast.makeText(ctx, "已通过 cammock 上传 $n 段视频", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    CameraMockUploadState.markFailed(ctx, ids)
                    if (!uiAlive()) return@onFailure
                    Toast.makeText(ctx, "自动上传失败，可在视频点内重试：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (cameraExecutor.isShutdown) cameraExecutor = Executors.newSingleThreadExecutor()
        _binding = FragmentCameraMockBinding.inflate(inflater, container, false)

        adapter = CameraPointAdapter(mutableListOf()) { point ->
            findNavController().navigate(
                R.id.nav_camera_point_edit,
                bundleOf(ARG_POINT_ID to point.id)
            )
        }
        binding.cameraPointList.layoutManager = LinearLayoutManager(requireContext())
        binding.cameraPointList.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val point = adapter[position]
                val ctx = requireContext()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("删除路段")
                    .setMessage("将删除「${point.name}」及其下全部 ${point.videos.size} 段视频，无法恢复。")
                    .setNegativeButton("取消") { _, _ -> adapter.notifyItemChanged(position) }
                    .setPositiveButton("删除") { _, _ ->
                        CameraMockStore.removePoint(ctx, point.id)
                        autoUploadLibrary()
                        refresh()
                        Toast.makeText(ctx, "已删除路段和 ${point.videos.size} 段视频", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }).attachToRecyclerView(binding.cameraPointList)

        /* MapView 需要自己的一套生命周期（onCreate/onResume/onPause/onDestroy），
         * 不转发的话地图不会初始化、恢复渲染 —— 与路线编辑页同一套写法 */
        binding.cameraMockMap.onCreate(requireContext(), savedInstanceState)
        binding.cameraMockMap.showZoomControls(false)
        binding.cameraMockMap.showScaleControl(false)
        with(baiduMapViewModel) { baiduMap = binding.cameraMockMap.map }
        binding.cameraMockMap.map.mapType = context?.mapType ?: BaiduMap.MAP_TYPE_NORMAL
        /* 点标记 → 直接进这个点的编辑页：地图上看到的和列表里点的是同一个东西 */
        binding.cameraMockMap.map.setOnMarkerClickListener { marker ->
            val id = marker.extraInfo?.getString(EXTRA_POINT_ID)
            if (id != null) {
                findNavController().navigate(
                    R.id.nav_camera_point_edit,
                    bundleOf(ARG_POINT_ID to id)
                )
            }
            true
        }

        binding.switchCameraMock.setOnClickListener { toggleMock() }
        binding.cameraMockImportImpl.setOnClickListener {
            cammockImportLauncher.launch(zipPickerIntent())
        }
        binding.cameraMockUpdateImpl.setOnClickListener {
            cammockImportLauncher.launch(zipPickerIntent())
        }
        binding.cameraMockUninstallImpl.setOnClickListener { confirmUninstallCammock() }
        binding.cameraMockMapToggle.setOnClickListener { toggleMap() }
        // 说明与目标应用放进对话框：它们"读一次就够"，留在页面上会把视频点列表挤没
        binding.cameraMockInfo.setOnClickListener { showInfoDialog() }
        binding.cameraMockCreateTag.setOnClickListener { showTagManager() }

        return binding.root
    }

    /**
     * 不要用 OpenDocument 的任意 MIME：部分 OEM 文件管理器会把它当成“打开文件”，
     * 点击 zip 直接交给解压/归档应用，导致 PortalEX 收不到 RESULT。
     * 这里强制 ACTION_OPEN_DOCUMENT + zip MIME，只允许“选择并返回 URI”。
     */
    private fun uiAlive(): Boolean = isAdded && _binding != null

    private var importFabBar: FabBarView? = null

    private fun setFabLoading(value: Boolean) {
        if (value) importFabBar = (activity as? MainActivity)?.fabBar
        val bar = importFabBar ?: (activity as? MainActivity)?.fabBar
        bar?.setLoading(value)
        if (!value) importFabBar = null
    }

    private fun zipPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/zip"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        // OnePlus/ColorOS 的 PickerActivity 会对 zip 做“智能解压/识别安装包”，
        // 选中后直接进入它自己的解压或安装流程，不返回 URI。
        // 优先强制使用 AOSP DocumentsUI 的原生文件选择器；不存在时再回退系统默认。
        val native = ComponentName(
            "com.android.documentsui",
            "com.android.documentsui.picker.PickActivity",
        )
        val available = try {
            requireContext().packageManager.getActivityInfo(native, 0)
            true
        } catch (_: Exception) {
            false
        }
        if (available) component = native
    }

    override fun onResume() {
        super.onResume()
        binding.cameraMockMap.onResume()
        avoidFabBar()
        updateFabActions()
        /* 页面回到前台时对齐一次产帧线程：开关可能是在别处（或上一次进程）打开的，
         * 而"开着的开关"必须对应一个真的在产帧的线程，否则 HAL 那边会一直发最后一帧 */
        refresh()
    }

    override fun onPause() {
        binding.cameraMockMap.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        refreshToken++
        mapDrawToken++
        cameraExecutor.shutdownNow()
        binding.cameraMockMap.onDestroy()
        super.onDestroyView()
        _binding = null
    }

    private fun updateFabActions() {
        val fabBar = (activity as? MainActivity)?.fabBar ?: return
        // 录制完成自动上传；下载/预览已转移到路线编辑页。
        fabBar.setActions(
            listOf(
                FabBarView.Action(
                    R.drawable.baseline_control_camera_24,
                    getString(R.string.camera_record)
                ) { startRecordingFromMap() },
                FabBarView.Action(
                    R.drawable.baseline_file_download_24,
                    getString(R.string.camera_import_archive)
                ) {
                    videoArchiveLauncher.launch(zipPickerIntent())
                }
            )
        )
    }

    /**
     * 地图展开时的相机图标：先确认有位置、且没有开启任何位置模拟，才进入录制。
     * 录制期间 CameraRecordActivity 每秒采样 CameraMockRuntime.position()，
     * 结果中的 track 会随视频一起落库。
     */


    private fun startRecordingFromMap() {
        val locationManager = requireContext()
            .getSystemService(LocationManager::class.java)
        val locationOn = locationManager != null && (
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                runCatching { locationManager.isLocationEnabled }.getOrDefault(false)
            } else {
                runCatching {
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                        (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                            locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER))
                }.getOrDefault(false)
            }
            )
        if (!locationOn) {
            Toast.makeText(requireContext(), "请先打开位置服务，再录制移动轨迹", Toast.LENGTH_LONG).show()
            return
        }
        if (requireContext().cameraMockEnabled) {
            Toast.makeText(requireContext(), "请先关闭虚拟摄像头，再录制现场视频", Toast.LENGTH_LONG).show()
            return
        }
        val lm = mockServiceViewModel.locationManager
        if (lm != null && MockServiceHelper.isMockStart(lm)) {
            Toast.makeText(requireContext(), "请先关闭位置模拟，再录制真实移动轨迹", Toast.LENGTH_LONG).show()
            return
        }
        // 室内可能暂时没有最后已知位置；不阻塞拍摄，录制期间由融合定位补点。
        val position = CameraMockRuntime.position()
        if (position == null) {
            Toast.makeText(
                requireContext(),
                "正在使用融合定位，开始录制后请稍等几秒",
                Toast.LENGTH_LONG
            ).show()
        }
        confirmRecording()
    }

    /** 拍摄前明确使用方法：脸在自拍镜头中，然后边走边录。 */
    private fun confirmRecording() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("录制路线")
            .setMessage("请确保脸在自拍镜头中，点击开始后保持拍摄并走动；视频时间轴会同时记录移动轨迹。")
            .setPositiveButton("开始") { _, _ -> launchRecorder() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchRecorder() {
        val missing = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (missing.isNotEmpty()) {
            pendingRecordAfterPermission = true
            requestPermissions(missing.toTypedArray(), REQUEST_CAMERA)
            return
        }
        recordLauncher.launch(Intent(requireContext(), CameraRecordActivity::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && pendingRecordAfterPermission) {
            pendingRecordAfterPermission = false
            val cameraOk = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val locationOk = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (cameraOk && locationOk) {
                recordLauncher.launch(Intent(requireContext(), CameraRecordActivity::class.java))
            } else {
                Toast.makeText(requireContext(), "需要摄像头和位置权限才能录制轨迹", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseTrack(json: String?): List<CameraVideoTrackPoint> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CameraVideoTrackPoint(
                    tMs = o.optLong("t", 0L),
                    lat = o.optDouble("lat", Double.NaN),
                    lon = o.optDouble("lon", Double.NaN)
                ).takeIf { it.lat.isFinite() && it.lon.isFinite() }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 展开/收起地图。
     *
     * 为什么默认收起：地图 180dp 是"固定高度"，展开时会把列表卡（0dp/weight=1）挤到只剩一条缝
     * （实测点位的名字/坐标/标签全看不见）。所以把选择权交给用户：要"参照地图操作"时点一下展开。
     */
    private fun toggleMap() {
        val card = binding.cameraMockMapCard
        val show = card.visibility != View.VISIBLE
        card.visibility = if (show) View.VISIBLE else View.GONE
        mapExpanded = show
        updateFabActions()
        binding.cameraMockMapToggle.setText(
            if (show) R.string.camera_mock_hide_map else R.string.camera_mock_show_map
        )
        if (show) {
            /* 从 GONE 变 VISIBLE 后要让它重新走一遍恢复流程，否则纹理可能是白的 */
            runCatching { binding.cameraMockMap.onResume() }
            drawPoints(sortedPoints(lastPoints))
        } else {
            runCatching { binding.cameraMockMap.onPause() }
        }
    }

    // ---- 地图操作（MapControlsHost：三点展开栏的"卫星图/普通图"共用同一套） ----

    override fun isSatellite(): Boolean =
        runCatching { binding.cameraMockMap.map.mapType == BaiduMap.MAP_TYPE_SATELLITE }
            .getOrDefault(false)

    override fun toggleSatellite() {
        val target =
            if (isSatellite()) BaiduMap.MAP_TYPE_NORMAL else BaiduMap.MAP_TYPE_SATELLITE
        runCatching { binding.cameraMockMap.map.mapType = target }
        context?.mapType = target
    }

    /**
     * 把「视频点」与「当前表象位置」画到地图上。
     *
     * 为什么要画：选片规则是"**距当前表象位置最近的命中点**" —— 只给列表的话，
     * 用户没法判断"现在到底会播哪一个"。地图上"我在这、点在那边"是一眼的事。
     */
    /**
     * 地图覆盖物在后台线程构建，主线程只做 addOverlays。
     * 大量轨迹线时，逐点创建 OverlayOptions + gcj02 换算放在 UI 线程会卡住页面切换。
     */
    private fun drawPoints(points: List<CameraVideoPoint>) {
        if (!uiAlive()) return
        val token = ++mapDrawToken
        val pointIcon = BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16)
        val snapshot = points.toList()
        cameraExecutor.execute {
            val options = buildOverlayOptions(snapshot, pointIcon)
            main.post {
                if (token != mapDrawToken || !uiAlive()) return@post
                val map = runCatching { binding.cameraMockMap.map }.getOrNull() ?: return@post
                pointOverlays.forEach { runCatching { it.remove() } }
                pointOverlays = if (options.isNotEmpty()) {
                    runCatching { map.addOverlays(options) }
                        .onFailure { Log.w("CameraMockFragment", "addOverlays 失败（地图标记没画出来）", it) }
                        .getOrDefault(emptyList())
                } else emptyList()
                Log.i(
                    "CameraMockFragment",
                    "地图路线：点位 ${snapshot.size} 个 / 覆盖物 ${pointOverlays.size} 个"
                )
                snapshot.firstOrNull()?.let { point ->
                    runCatching {
                        map.setMapStatus(MapStatusUpdateFactory.newLatLng((point.lat to point.lon).gcj02))
                    }
                }
            }
        }
    }

    /** 纯数据构建，不碰 View/Map；在 cameraExecutor 上运行。 */
    private fun buildOverlayOptions(
        points: List<CameraVideoPoint>,
        pointIcon: com.baidu.mapapi.map.BitmapDescriptor
    ): List<OverlayOptions> {
        val options = ArrayList<OverlayOptions>(points.size + 1)
        points.forEach { point ->
            val bundle = android.os.Bundle().apply { putString(EXTRA_POINT_ID, point.id) }
            options += MarkerOptions()
                .position((point.lat to point.lon).gcj02)
                .icon(pointIcon)
                .title("${point.name}（${point.videos.size} 段）")
                .extraInfo(bundle)
        }
        points.forEach { point ->
            point.videos.forEach { video ->
                if (video.track.size >= 2) {
                    val line = video.track.map { (it.lat to it.lon).gcj02 }
                    options += PolylineOptions()
                        .points(line)
                        .width(10)
                        .color(0xFF1E88E5.toInt())
                        .zIndex(1)
                }
            }
        }
        return options
    }

    /** 最新变更的路线/视频点排在上面；同一时间按名称稳定排序。 */
    private fun sortedPoints(points: List<CameraVideoPoint>): List<CameraVideoPoint> =
        points.sortedWith(
            compareByDescending<CameraVideoPoint> {
                it.videos.maxOfOrNull { v -> maxOf(v.addedAt, v.recordedAt) } ?: 0L
            }.thenBy { it.name }
        )

    /**
     * 路线集合从 SharedPreferences JSON 解码也要离开 UI 线程；大库切换页面时不再阻塞。
     */
    private fun refresh() {
        if (!uiAlive()) return
        val context = requireContext().applicationContext
        val token = ++refreshToken
        cameraExecutor.execute {
            val points = runCatching { CameraMockStore.points(context) }
                .getOrDefault(emptyList())
            main.post {
                if (token != refreshToken || !uiAlive()) return@post
                applyPoints(points)
            }
        }
    }

    private fun applyPoints(points: List<CameraVideoPoint>) {
        val context = requireContext()
        val filter = context.cameraMockTagFilter
        fun matches(point: CameraVideoPoint): Boolean =
            point.videos.any { v ->
                if (filter.isEmpty()) true
                else v.tags.isNotEmpty() && filter.all { t -> v.tags.any { it.equals(t, true) } }
            }
        val sorted = sortedPoints(points)
            .sortedWith(compareByDescending<CameraVideoPoint> { matches(it) })
        lastPoints = sorted
        adapter.setFilterTags(filter)
        adapter.replaceAll(sorted)
        drawPoints(sorted)
        rebuildFilterChips()
        updateSwitch()
        updateStatus(sorted)
        updateImplementationUi()
    }

    private fun rebuildFilterChips() {
        val context = requireContext()
        val group: ChipGroup = binding.cameraMockFilter
        val selected = context.cameraMockTagFilter
        rebuildingFilter = true
        group.removeAllViews()
        CameraMockStore.allTags(context, lastPoints).forEach { tag ->
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
                refresh()
            }
            group.addView(chip)
        }
        rebuildingFilter = false
    }

    /**
     * 启动摄像机模拟时按系统时间自动选择白天/黑夜标签：
     * 06:00~17:59 = 白天，其余 = 黑夜。弹窗只提示一次（本次启动）。
     */
    private fun autoSelectTimeTag(context: android.content.Context) {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val tag = if (hour in 6..17) CameraTags.DAY else CameraTags.NIGHT
        val last = context.cameraMockTimeCheckAt
        val crossed = last <= 0L || dayNightPeriod(last) != dayNightPeriod(now.timeInMillis)
        // 每次启动都记录检查点；只有跨过 06:00 或 18:00 才提示。
        context.cameraMockTimeCheckAt = now.timeInMillis
        if (!crossed || !uiAlive()) return
        val hhmm = "%02d:%02d".format(hour, now.get(java.util.Calendar.MINUTE))
        MaterialAlertDialogBuilder(context)
            .setTitle("时间标签已跨越")
            .setMessage("当前时间 $hhmm，已跨越 06:00 / 18:00 分界，建议切换为「$tag」。")
            .setPositiveButton("切换") { _, _ ->
                context.cameraMockTagFilter = setOf(tag)
                refresh()
            }
            .setNegativeButton("保持当前", null)
            .show()
    }

    /** 0 = 黑夜，1 = 白天；只比较时刻，不比较日期。 */
    private fun dayNightPeriod(ms: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return if (c.get(java.util.Calendar.HOUR_OF_DAY) in 6..17) 1 else 0
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

    private fun confirmUninstallCammock() {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle("卸载摄像头实现")
            .setMessage("将删除 app 私有目录和 /data/local/tmp/portalex/cammock 下的实现文件。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("卸载") { _, _ ->
                binding.cameraMockUninstallImpl.isEnabled = false
                Executors.newSingleThreadExecutor().execute {
                    val result = CammockManager.uninstall(context)
                    main.post {
                        context.cameraMockEnabled = false
                        if (!uiAlive()) return@post
                        binding.cameraMockUninstallImpl.isEnabled = true
                        updateSwitch()
                        updateImplementationUi()
                        Toast.makeText(context, result.getOrElse { "卸载失败" }, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun showTagManager() {
        val ctx = requireContext()
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val chips = com.google.android.material.chip.ChipGroup(ctx).apply {
            isSingleSelection = false
            setPadding(0, 8, 0, 8)
        }
        fun rebuildChips() {
            chips.removeAllViews()
            CameraMockStore.allTags(ctx).forEach { tag ->
                chips.addView(com.google.android.material.chip.Chip(ctx).apply {
                    text = tag
                    isCheckable = true
                })
            }
        }
        rebuildChips()
        root.addView(chips)

        fun selectedTags(): List<String> = (0 until chips.childCount)
            .map { chips.getChildAt(it) as com.google.android.material.chip.Chip }
            .filter { it.isChecked }.map { it.text.toString() }

        fun deleteSelected() {
            val selected = selectedTags()
                .filterNot { CameraTags.isPreset(it) }
            if (selected.isEmpty()) {
                Toast.makeText(ctx, "预设标签不可删除；请选择自定义标签", Toast.LENGTH_SHORT).show()
                return
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("删除标签")
                .setMessage("将从标签库和所有视频中移除：${selected.joinToString()}")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    CameraMockStore.removeTags(ctx, selected)
                    refresh()
                }
                .show()
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("标签管理")
            .setView(root)
            .setNeutralButton("添加标签") { _, _ -> createTagDialog() }
            .setNegativeButton("删除选中") { _, _ -> deleteSelected() }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun createTagDialog(onCreated: (() -> Unit)? = null) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "标签名（最多 ${moe.fuqiuluo.xposed.camera.CameraTags.MAX_LEN} 字）"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("创建标签")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val ok = CameraMockStore.addCustomTag(ctx, input.text?.toString().orEmpty())
                Toast.makeText(ctx, if (ok) "标签已创建" else "标签为空或已存在", Toast.LENGTH_SHORT).show()
                refresh()
                onCreated?.invoke()
            }
            .show()
    }

    private fun updateImplementationUi() {
        if (!uiAlive()) return
        val ctx = requireContext()
        val installed = CammockManager.isInstalled(ctx)
        binding.cameraMockImplStatus.text = if (installed) {
            "实现已安装：" + CammockManager.supportedModels(ctx).joinToString().ifBlank { "未声明机型" }
        } else {
            "需要安装摄像头实现（按机型适配）"
        }
        binding.cameraMockImportImpl.text = if (installed) "重导" else "导入"
        binding.cameraMockUpdateImpl.visibility = if (installed) View.VISIBLE else View.GONE
        binding.cameraMockUninstallImpl.visibility = if (installed) View.VISIBLE else View.GONE
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


    }

    /** ⓘ：玩法说明 + 当前目标应用 + 系统侧观测到的摄像头活动（页面顶部只留"一眼要看"的三行） */
    private fun showInfoDialog() {
        val context = requireContext()
        // 控制面回读：system_server 侧（AppOps + CameraServiceProxy）看到的相机活动，
        // 对目标应用零注入 —— 拦截尚未接入时，这块就是"能不能看见"的全部依据
        val events = runCatching {
            val lm = mockServiceViewModel.locationManager
            if (lm == null) null else moe.fuqiuluo.portalex.service.MockServiceHelper.getCameraEvents(lm)
        }.getOrNull()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.camera_mock)
            .setMessage(
                getString(R.string.camera_mock_hint) +
                        "\n\n最近摄像头活动（系统侧观测，零应用注入）：\n" + (events ?: "（暂无：模拟未运行或系统服务未就绪）")
            )
            .setPositiveButton(R.string.got_it, null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun toggleMock() {
        val context = requireContext()
        val next = !context.cameraMockEnabled
        android.util.Log.i("CameraMockFragment", "toggleMock next=$next enabled=${context.cameraMockEnabled} installed=${CammockManager.isInstalled(context)} module=${CammockManager.isModule(context)}")
        if (next && !CammockManager.isInstalled(context)) {
            MaterialAlertDialogBuilder(context)
                .setTitle("未安装摄像头实现")
                .setMessage("摄像头 hook 实现按机型单独适配，需要先导入 cammock zip。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("导入实现") { _, _ ->
                    cammockImportLauncher.launch(zipPickerIntent())
                }
                .show()
            return
        }
        // cammock 是特例：严格依赖位置/路线模拟，关闭时必须随位置模拟一起停，
        // 因此不能在位置模拟未启动时"预启用"。
        if (next && !FeatureDependency.isRouteActive(context)) {
            Toast.makeText(context, R.string.camera_requires_route, Toast.LENGTH_LONG).show()
            return
        }
        binding.switchCameraMock.isEnabled = false
        binding.cameraMockStatus.text = if (next) "正在启动 cammock…" else "正在停止 cammock…"
        Executors.newSingleThreadExecutor().execute {
            android.util.Log.i("CameraMockFragment", "call cammock ${if (next) "start" else "stop"}")
            val result = if (next) CammockManager.start(context) else CammockManager.stop(context)
            android.util.Log.i("CameraMockFragment", "cammock result success=${result.isSuccess} value=${result.getOrNull()} error=${result.exceptionOrNull()?.message}")
            main.post {
                if (result.isSuccess) {
                    context.cameraMockEnabled = next
                } else {
                    context.cameraMockEnabled = !next
                }
                if (!uiAlive()) return@post
                binding.switchCameraMock.isEnabled = true
                if (result.isSuccess) {
                    if (next) autoSelectTimeTag(context)
                    updateSwitch()
                    updateStatus(lastPoints)
                    Toast.makeText(
                        context,
                        result.getOrDefault(if (next) "摄像头模拟已启动" else "摄像头模拟已停止"),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    updateSwitch()
                    Toast.makeText(context, "cammock 执行失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
        return
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
