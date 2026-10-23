package moe.fuqiuluo.portalex.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.Overlay
import com.baidu.mapapi.map.OverlayOptions
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.model.LatLngBounds
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.Portal
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.widget.FabBarView
import moe.fuqiuluo.portalex.bdmap.setMapConfig
import moe.fuqiuluo.portalex.databinding.FragmentCameraPointEditBinding
import moe.fuqiuluo.portalex.ext.gcj02
import moe.fuqiuluo.portalex.ext.mapType
import moe.fuqiuluo.portalex.ext.wgs84
import moe.fuqiuluo.portalex.ui.MapControlsHost
import moe.fuqiuluo.portalex.ui.viewmodel.BaiduMapViewModel
import moe.fuqiuluo.xposed.utils.WorldMath
import moe.fuqiuluo.xposed.camera.CameraVideo
import moe.fuqiuluo.xposed.camera.CameraVideoPoint
import moe.fuqiuluo.xposed.camera.CameraVideoTrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.File

/**
 * **视频点编辑页**：上半地图（准星 = 该点坐标），下半是这个点名下的视频清单
 * （应用内录制 / 外部导入 / 打标签 / 删除）。
 *
 * 两个约定值得写下来：
 *  1. **坐标取准星**（地图中心），与路线模拟页同一套交互；入库前统一转成 wgs84 ——
 *     模块侧按 wgs84 与模拟位置比距离，两边不能各用一套坐标系。
 *  2. 视频的增删**立即落盘**（它是磁盘文件，留在内存里一旦退出就成了孤儿文件），
 *     而名称与坐标在"保存"（或按返回键时）才提交。
 */
class CameraPointEditFragment : Fragment(), MapControlsHost {

    private companion object {
        const val REQUEST_CAMERA = 1001
    }

    private var _binding: FragmentCameraPointEditBinding? = null
    private val binding get() = _binding!!

    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()

    private lateinit var videoAdapter: CameraVideoAdapter

    /** 正在编辑的点（内存工作副本）；null = 还没加载（首次 onResume 时按参数或地图中心建） */
    private var working: CameraVideoPoint? = null

    /** 地图是否已经给出过有效中心：没就绪前不拿"北京默认中心"去覆盖坐标 */
    private var mapReady = false
    private var routeOverlay: Overlay? = null
    private var initialFitDone = false

    /**
     * 摄像头权限用**经典 requestPermissions + onRequestPermissionsResult**，
     * 不走 `RequestPermissions`（它内部是 `registerForActivityResult`）。
     *
     * 实测踩过（MI6/LineageOS 15，安装后首次点「录制视频」即崩）：
     * `registerForActivityResult` 要求"在 STARTED 之前注册"，而本页是用户点进来的，
     * 那一刻 **Activity 已经 RESUMED** —— 在 Fragment.onCreate 里注册同样抛
     * `IllegalStateException: LifecycleOwner ... is attempting to register while current state is RESUMED`。
     * 经典 API 没有这个时序约束，而且本页只需要"申请一次、拿到就录"。
     */
    private var pendingRecordAfterPermission = false

    private val recordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fileName = result.data?.getStringExtra(CameraRecordActivity.RESULT_FILE_NAME)
            ?: return@registerForActivityResult
        val file = CameraMockStore.videoFile(requireContext(), fileName)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "录制文件丢失", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val track = parseTrack(result.data?.getStringExtra(CameraRecordActivity.RESULT_TRACK_JSON))
        val recordedAt = result.data?.getLongExtra(CameraRecordActivity.RESULT_RECORDED_AT, 0L) ?: 0L
        addVideo(
            CameraMockStore.registerRecorded(
                requireContext(), file, null,
                recordedAt = recordedAt,
                track = track
            )
        )
    }

    private var pendingExport: CameraVideo? = null
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val video = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        Executors.newSingleThreadExecutor().execute {
            val ok = runCatching {
                val videoFile = CameraMockStore.videoFile(ctx, video.file)
                val exportStream = if (videoFile.isFile) {
                    videoFile.inputStream()
                } else {
                    // 本地已由 system_server 导入删除：通过 selection socket 取 fd。
                    CameraMockServerBridge.openVideo(video.id).getOrElse { error("无法从 system_server 取视频：${it.message}") }
                }
                ctx.contentResolver.openOutputStream(uri)?.use { output ->
                    java.util.zip.ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(java.util.zip.ZipEntry(video.file))
                        exportStream.use { it.copyTo(zip) }
                        zip.closeEntry()
                        val track = JSONObject().apply {
                            put("videoId", video.id)
                            put("videoName", video.name)
                            put("file", video.file)
                            put("durationMs", video.durationMs)
                            put("recordedAt", video.recordedAt)
                            put("tags", JSONArray(video.tags))
                            put("track", JSONArray().apply {
                                video.track.forEach { p -> put(JSONObject().apply {
                                    put("t", p.tMs); put("lat", p.lat); put("lon", p.lon)
                                }) }
                            })
                        }
                        zip.putNextEntry(java.util.zip.ZipEntry("track.json"))
                        zip.write(track.toString().toByteArray())
                        zip.closeEntry()
                    }
                } ?: error("无法写入导出文件")
            }.onFailure { exception ->
                activity?.runOnUiThread {
                    Toast.makeText(ctx, "导出失败：${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
            if (ok.isSuccess) activity?.runOnUiThread {
                Toast.makeText(ctx, "已导出视频与路径时间轴", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportVideo(video: CameraVideo) {
        pendingExport = video
        exportLauncher.launch("cammock-${video.name.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")}.zip")
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraPointEditBinding.inflate(inflater, container, false)

        with(baiduMapViewModel) {
            isExists = true
            baiduMap = binding.bmapView.map
        }
        with(binding.bmapView) {
            showZoomControls(true)
            showScaleControl(true)
            logoPosition = LogoPosition.logoPostionRightTop
        }

        with(binding.bmapView.map) {
            setMapStatus(MapStatusUpdateFactory.zoomTo(19f))
            mapType = context?.mapType ?: BaiduMap.MAP_TYPE_NORMAL
            compassPosition = Point(50, 50)
            setCompassEnable(true)
            uiSettings.isCompassEnabled = true
            uiSettings.isOverlookingGesturesEnabled = true
            // 预览只显示路线，不再画当前位置/定位点
            isMyLocationEnabled = false
            setMapConfig(baiduMapViewModel.perspectiveState, null)
            setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
                override fun onMapStatusChangeStart(status: MapStatus?) = Unit
                override fun onMapStatusChangeStart(status: MapStatus?, reason: Int) = Unit
                override fun onMapStatusChange(status: MapStatus?) = Unit
                override fun onMapStatusChangeFinish(status: MapStatus?) {
                    // 第一次拿到有效中心才算就绪：之后的读写都以此为准
                    mapReady = true
                    updateCoordReadout()
                }
            })
            binding.mapTypeGroup.check(
                when (mapType) {
                    BaiduMap.MAP_TYPE_SATELLITE -> R.id.map_type_satellite
                    else -> R.id.map_type_normal
                }
            )
        }

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.bmapView.map.mapType = when (checkedId) {
                R.id.map_type_satellite -> BaiduMap.MAP_TYPE_SATELLITE
                else -> BaiduMap.MAP_TYPE_NORMAL
            }
            context?.mapType = binding.bmapView.map.mapType
        }

        videoAdapter = CameraVideoAdapter(
            mutableListOf(),
            onEditTags = { video -> editTags(video) },
            onDelete = { video -> confirmDelete(video) },
            onExport = { video -> exportVideo(video) }
        )
        binding.cameraVideoList.layoutManager = LinearLayoutManager(requireContext())
        binding.cameraVideoList.adapter = videoAdapter

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // MapView 需要自己的一套生命周期
        binding.bmapView.onCreate(requireContext(), savedInstanceState)
    }


    override fun onResume() {
        super.onResume()
        if (_binding != null) binding.bmapView.onResume()
        (activity as? MainActivity)?.fabBar?.setActions(
            listOf(
                FabBarView.Action(
                    R.drawable.baseline_control_camera_24,
                    getString(R.string.camera_record)
                ) { startRecording() }
            )
        )
        if (working == null) loadPoint()
        refreshVideos()
        fitRouteToMap()
        updateCoordReadout()
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) binding.bmapView.onPause()
        // 按返回键离开：名称/坐标也要落地（视频早已即时保存），否则用户改完名字白改
        if (isRemoving || !isAdded) persistIfMeaningful()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) binding.bmapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        baiduMapViewModel.isExists = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.bmapView?.onDestroy()
        _binding = null
    }

    // ---- 地图控件（右上角三点栏）----

    override fun isSatellite(): Boolean =
        binding.mapTypeGroup.checkedRadioButtonId == R.id.map_type_satellite

    override fun toggleSatellite() {
        binding.mapTypeGroup.check(
            if (isSatellite()) R.id.map_type_normal else R.id.map_type_satellite
        )
    }

    // ---- 数据 ----

    /** 准星坐标（wgs84） */
    private fun crosshairWgs84(): Pair<Double, Double>? {
        if (!mapReady) return null
        return runCatching { baiduMapViewModel.baiduMap.mapStatus.target.wgs84 }.getOrNull()
    }

    private fun updateCoordReadout() {
        val coord = crosshairWgs84() ?: return
        binding.cameraCoordReadout.text = "路线中心 ${CameraMockFormat.coord(coord.first, coord.second)}"
    }

    private fun loadPoint() {
        val ctx = requireContext()
        val id = arguments?.getString(CameraMockFragment.ARG_POINT_ID)
        val existing = id?.let { CameraMockStore.point(ctx, it) }
        if (existing != null) {
            working = existing
            binding.cameraPointNameInput.setText(existing.name)
            mapReady = true
            drawRoute()
            fitRouteToMap()
        } else {
            val center = crosshairWgs84() ?: Pair(39.908722, 116.397499)
            working = CameraMockStore.newPoint("", center.first, center.second)
        }
        updateCoordReadout()
    }

    /** 路线预览：把当前点下所有视频的轨迹连成一条蓝色折线（不再画准星）。 */
    private fun drawRoute() {
        val map = runCatching { baiduMapViewModel.baiduMap }.getOrNull() ?: return
        routeOverlay?.let { runCatching { it.remove() } }
        routeOverlay = null
        val raw = working?.videos.orEmpty()
            .flatMap { it.track }
            .sortedBy { it.tMs }
        // 室内静止时百度可能连续返回同一坐标：12 个点全相同。
        // 去重后不足 2 个点就没有“路线形状”，只画一个蓝色定位点，避免零长折线。
        val distinct = raw.distinctBy { "%.6f,%.6f".format(it.lat, it.lon) }
        if (distinct.size < 2) {
            val point = raw.firstOrNull() ?: working?.let {
                CameraVideoTrackPoint(0L, it.lat, it.lon)
            } ?: return
            routeOverlay = runCatching {
                map.addOverlay(
                    MarkerOptions()
                        .position((point.lat to point.lon).gcj02)
                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16))
                )
            }.getOrNull()
            return
        }
        val points = distinct.map { (it.lat to it.lon).gcj02 }
        val options = PolylineOptions()
            .points(points)
            .width(8)
            .color(0xFF1E88E5.toInt())
            .zIndex(2)
        routeOverlay = runCatching { map.addOverlay(options) }.getOrNull()
    }

    /**
     * 路线预览初始视角：按轨迹实际跨度自动选择缩放级别，让路线/单点都清晰可见。
     *  - 单点或静止轨迹：19 级近景 + 点标记；
     *  - 短距离室内路线：按跨度 19/18/17 级；
     *  - 长路线：用 LatLngBounds 适配整条路线形状和总体位置。
     */
    private fun fitRouteToMap() {
        val map = runCatching { baiduMapViewModel.baiduMap }.getOrNull() ?: return
        val point = working ?: return
        val raw = point.videos.orEmpty()
            .flatMap { it.track }
            .sortedBy { it.tMs }
            .ifEmpty { listOf(CameraVideoTrackPoint(0L, point.lat, point.lon)) }
        val distinct = raw.distinctBy { "%.6f,%.6f".format(it.lat, it.lon) }
        val centerLat = (distinct.minOf { it.lat } + distinct.maxOf { it.lat }) / 2.0
        val centerLon = (distinct.minOf { it.lon } + distinct.maxOf { it.lon }) / 2.0
        val center = (centerLat to centerLon).gcj02

        if (distinct.size < 2) {
            runCatching {
                map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(center, 19f))
            }
            initialFitDone = true
            return
        }

        val span = distinct.maxOf { a ->
            distinct.maxOf { b ->
                WorldMath.haversine(a.lat, a.lon, b.lat, b.lon)
            }
        }
        val zoom = when {
            span < 8 -> 19f
            span < 25 -> 18f
            span < 60 -> 17f
            span < 150 -> 16f
            span < 400 -> 15f
            else -> null
        }
        if (zoom != null) {
            runCatching {
                map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(center, zoom))
            }
            initialFitDone = true
            return
        }

        // 长路线：bounds 保证完整形状，视野由 SDK 按地图 View 尺寸计算。
        val route = distinct.map { (it.lat to it.lon).gcj02 }
        val builder = LatLngBounds.Builder()
        route.forEach { builder.include(it) }
        val bounds = runCatching { builder.build() }.getOrNull()
        if (bounds != null) {
            val w = binding.bmapView.width.takeIf { it > 0 } ?: 1080
            val h = binding.bmapView.height.takeIf { it > 0 } ?: 720
            runCatching {
                map.setMapStatus(MapStatusUpdateFactory.newLatLngBounds(bounds, w, h))
            }
            initialFitDone = true
        }
    }

    private fun refreshVideos() {
        val videos = working?.videos.orEmpty()
        videoAdapter.replaceAll(videos)
        binding.cameraVideoLabel.text = getString(R.string.camera_video_list) + "（${videos.size}）"
        // 还没有视频时给出下一步该干什么，而不是一个空列表
        binding.cameraVideoList.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
        drawRoute()
    }

    private fun addVideo(video: CameraVideo) {
        val point = working ?: return
        working = point.copy(videos = point.videos + video)
        CameraMockStore.upsert(requireContext(), working!!)
        refreshVideos()
        autoUploadLibrary()
    }

    private fun autoUploadLibrary() {
        val ctx = requireContext()
        Executors.newSingleThreadExecutor().execute {
            CameraMockServerBridge.upload(ctx)
        }
    }



    private fun editTags(video: CameraVideo) {
        val ctx = requireContext()
        CameraTagDialog.show(
            context = ctx,
            selected = video.tags,
            libraryTags = CameraMockStore.allTags(ctx)
        ) { tags ->
            val point = working ?: return@show
            working = point.copy(
                videos = point.videos.map { if (it.id == video.id) it.copy(tags = tags) else it }
            )
            CameraMockStore.upsert(ctx, working!!)
            refreshVideos()
        }
    }

    private fun confirmDelete(video: CameraVideo) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.camera_video_delete)
            .setMessage("删除后该视频文件也会从库里移除：${video.name}")
            .setPositiveButton(R.string.delete) { _, _ ->
                val point = working ?: return@setPositiveButton
                CameraMockStore.deleteVideoFile(ctx, video.file)
                working = point.copy(videos = point.videos.filterNot { it.id == video.id })
                CameraMockStore.upsert(ctx, working!!)
                refreshVideos()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun startRecording() {
        val ctx = requireContext()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            recordLauncher.launch(Intent(ctx, CameraRecordActivity::class.java))
            return
        }
        pendingRecordAfterPermission = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    @Deprecated("经典权限回调：本页必须绕过 registerForActivityResult 的注册时序限制（见字段注释）")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        val wanted = pendingRecordAfterPermission
        pendingRecordAfterPermission = false
        when {
            granted && wanted -> recordLauncher.launch(Intent(requireContext(), CameraRecordActivity::class.java))
            !granted -> Toast.makeText(requireContext(), "需要摄像头权限才能录制", Toast.LENGTH_SHORT).show()
        }
    }

    /** 名称/坐标写回库里；只有"有点内容"的点才落盘，避免空点位污染列表 */
    private fun persistIfMeaningful() {
        val point = working ?: return
        val name = binding.cameraPointNameInput.text?.toString()?.trim().orEmpty()
        val center = crosshairWgs84()
        val merged = point.copy(
            name = name.ifEmpty { point.name },
            lat = center?.first ?: point.lat,
            lon = center?.second ?: point.lon
        )
        val meaningful = merged.name.isNotBlank() || merged.videos.isNotEmpty() ||
                CameraMockStore.point(requireContext(), merged.id) != null
        if (!meaningful) return
        working = merged.copy(
            name = merged.name.ifBlank { getString(R.string.camera_point_unnamed) }
        )
        CameraMockStore.upsert(requireContext(), working!!)
    }


}
