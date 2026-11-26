package moe.fuqiuluo.portalex.ui.camera

import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.LinearLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.Portal
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.service.MockServiceHelper
import androidx.core.os.bundleOf
import moe.fuqiuluo.portalex.android.widget.FabBarView
import moe.fuqiuluo.portalex.bdmap.setMapConfig
import moe.fuqiuluo.portalex.databinding.FragmentCameraPointEditBinding
import moe.fuqiuluo.portalex.ext.gcj02
import moe.fuqiuluo.portalex.ext.mapType
import moe.fuqiuluo.portalex.ext.wgs84
import moe.fuqiuluo.portalex.ui.MapControlsHost
import moe.fuqiuluo.portalex.ui.viewmodel.BaiduMapViewModel
import moe.fuqiuluo.xposed.utils.WorldMath
import moe.fuqiuluo.xposed.camera.CameraMockSelector
import moe.fuqiuluo.xposed.camera.CameraVideo
import moe.fuqiuluo.xposed.camera.CameraVideoPoint
import moe.fuqiuluo.xposed.camera.CameraVideoTrackPoint
import moe.fuqiuluo.xposed.camera.CameraTags
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.File

/**
 * **点位编辑页**：上半地图（准星 = 该点坐标），下半是这个点名下的素材清单
 * （应用内录制 / 手动添加图片 / 外部导入 / 打标签 / 删除）。
 *
 * 三个约定值得写下来：
 *  1. **坐标取准星**（地图中心），与路线模拟页同一套交互；入库前统一转成 wgs84 ——
 *     模块侧按 wgs84 与模拟位置比距离，两边不能各用一套坐标系。
 *  2. 素材的增删**立即落盘**（它是磁盘文件，留在内存里一旦退出就成了孤儿文件），
 *     而名称与坐标在"保存"（或按返回键时）才提交。
 *  3. **图片绑定的是"当前准星"这一个点**：落盘时它被编码成一帧 mp4（见
 *     [CameraStillMedia]），因此选片/播放与视频完全同构，只是起播偏移恒为 0。
 */
class CameraPointEditFragment : Fragment(), MapControlsHost {

    companion object {
        /** 手动导入图片时携带的 URI：进入后先选位置，再落库（不保存直接返回则丢弃） */
        const val ARG_PENDING_STILL = "pendingStill"
    }

    private var _binding: FragmentCameraPointEditBinding? = null
    private val binding get() = _binding!!

    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()

    private lateinit var videoAdapter: CameraVideoAdapter

    /** 正在编辑的点（内存工作副本）；null = 还没加载（首次 onResume 时按参数或地图中心建） */
    private var working: CameraVideoPoint? = null

    /** 地图是否已经给出过有效中心：没就绪前不拿"地图 SDK 默认中心"去覆盖坐标 */
    private var mapReady = false
    private var routeOverlay: Overlay? = null
    private var initialFitDone = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var routeExecutor = Executors.newSingleThreadExecutor()
    /** 手动导入图片：选图后进入本页先放位置，点"保存图片点位"才真正落库 */
    private var pendingStill: Uri? = null
    private var pendingSaved = false
    private var locating = false
    private var locateSampler: CameraLocationSampler? = null

    private var loadToken = 0
    private var routeDrawToken = 0
    private var fitToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingStill = arguments?.getString(ARG_PENDING_STILL)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private var pendingExport: CameraVideo? = null
    private var cameraLoading: CameraLoadingDialog? = null
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val video = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        cameraLoading = (cameraLoading ?: CameraLoadingDialog(ctx)).also {
            it.show("正在导出视频与路径…")
        }
        Executors.newSingleThreadExecutor().execute {
            val ok = runCatching {
                val videoFile = CameraMockStore.videoFile(ctx, video.file)
                val exportSource = if (videoFile.isFile) {
                    videoFile
                } else {
                    CameraMockSyncClient.exportVideo(ctx, video.id).getOrThrow()
                }
                ctx.contentResolver.openOutputStream(uri)?.use { output ->
                    java.util.zip.ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(java.util.zip.ZipEntry(video.file))
                        exportSource.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        val track = JSONObject().apply {
                            put("videoId", video.id)
                            put("videoName", video.name)
                            put("pointId", working?.id ?: "")
                            put("pointName", working?.name ?: "")
                            put("pointLat", working?.lat ?: video.track.firstOrNull()?.lat ?: 0.0)
                            put("pointLon", working?.lon ?: video.track.firstOrNull()?.lon ?: 0.0)
                            put("file", video.file)
                            put("durationMs", video.durationMs)
                            put("recordedAt", video.recordedAt)
                            put("kind", video.kind)
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
                    if (!uiAlive()) return@runOnUiThread
                    Toast.makeText(ctx, "导出失败：${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
            activity?.runOnUiThread {
                cameraLoading?.dismiss()
                cameraLoading = null
                if (!uiAlive()) return@runOnUiThread
                if (ok.isSuccess) {
                    Toast.makeText(ctx, "已导出素材与路径时间轴", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportVideo(video: CameraVideo) {
        pendingExport = video
        exportLauncher.launch("cammock-${video.name.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")}.zip")
    }

    // ---------------------------------------------------------------- 手动添加图片

    /** 图片选择器：**只选图片**，位置由地图准星决定（与"坐标取准星"同一套约定） */
    private val stillLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) addStillFromUri(uri)
    }

    private var stillBusy = false
    private var stillExecutor = Executors.newSingleThreadExecutor()

    /**
     * 手动添加：选一张图片 → 编码成"一帧视频" → 绑定到**当前准星坐标**。
     *
     * 为什么位置用准星而不是再弹一个选点页：本页的整个交互就是"地图中心 = 坐标"
     * （名称/坐标在离开时按准星落库）。多一个选点弹窗只会让"这个点在哪"出现两份答案。
     */
    private fun pickStill() {
        if (stillBusy) return
        if (working == null) {
            Toast.makeText(requireContext(), "点位还没加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        // 位置取"此刻的准星"：地图还没给出有效中心时，拿到的会是默认中心，绑定就错了
        if (crosshairWgs84() == null) {
            Toast.makeText(requireContext(), "地图还没就绪，请稍候再添加", Toast.LENGTH_SHORT).show()
            return
        }
        stillLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun addStillFromUri(uri: Uri) {
        val ctx = requireContext()
        val point = working ?: return
        val coord = crosshairWgs84() ?: (point.lat to point.lon)
        stillBusy = true
        cameraLoading = (cameraLoading ?: CameraLoadingDialog(ctx)).also {
            it.show(getString(R.string.camera_still_importing))
        }
        val appContext = ctx.applicationContext
        stillExecutor.execute {
            val video = runCatching {
                CameraMockStore.importStill(appContext, uri, coord.first, coord.second)
            }.onFailure {
                android.util.Log.w("CameraPointEdit", "图片导入失败", it)
            }.getOrNull()
            main.post {
                // 先复位状态再判存活：页面在编码期间被销毁时，这个标志位不能卡在 true，
                // 否则下次进来点"添加图片"会一直没反应。
                stillBusy = false
                cameraLoading?.dismiss()
                cameraLoading = null
                if (!uiAlive()) return@post
                val current = working ?: return@post
                if (video == null) {
                    Toast.makeText(ctx, R.string.camera_still_none, Toast.LENGTH_LONG).show()
                    return@post
                }
                // 这个点位此前没有素材时，让点位坐标跟上图片的绑定位置：
                // 否则"点位在这、唯一一张图在那边"，地图预览与选片会对不上。
                val anchor = if (current.videos.isEmpty()) coord else (current.lat to current.lon)
                working = current.copy(
                    lat = anchor.first,
                    lon = anchor.second,
                    videos = (current.videos + video).sortedBy { it.recordedAt }
                )
                CameraMockStore.upsert(ctx, working!!)
                refreshVideos()
                CameraAutoUpload.trigger(ctx)
                Toast.makeText(
                    ctx,
                    getString(R.string.camera_still_bound, CameraMockFormat.coord(coord.first, coord.second)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (routeExecutor.isShutdown) routeExecutor = Executors.newSingleThreadExecutor()
        if (stillExecutor.isShutdown) stillExecutor = Executors.newSingleThreadExecutor()
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
            onEdit = { video -> editVideo(video) },
            onDelete = { video -> confirmDelete(video) },
            onExport = { video -> exportVideo(video) },
            onRetry = { _ -> retryUpload() }
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
        updateFabActions()
        CameraAutoUpload.trigger(requireContext()) { ok ->
            if (ok && uiAlive()) refreshVideos()
        }
        if (working == null) {
            loadPoint()
        } else {
            // 从录制页回来时视频是录制页直接写入库的：用库里的最新副本刷新本页
            if (pendingStill == null) {
                working = CameraMockStore.point(requireContext(), working!!.id) ?: working
            }
            refreshVideos()
            fitRouteToMap()
        }
        updateCoordReadout()
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) binding.bmapView.onPause()
        // 按返回键离开：名称/坐标也要落地（视频早已即时保存），否则用户改完名字白改
        // "待保存图片"模式没有点保存 = 放弃这次导入，不能留下空点位
        if ((isRemoving || !isAdded) && !(pendingStill != null && !pendingSaved)) persistIfMeaningful()
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
        locating = false
        locateSampler?.stop()
        locateSampler = null
        loadToken++
        routeDrawToken++
        fitToken++
        cameraLoading?.dismiss()
        cameraLoading = null
        stillBusy = false
        stillExecutor.shutdownNow()
        routeExecutor.shutdownNow()
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

    /**
     * 路线 JSON 解码放在后台；点位多时不要在点击瞬间阻塞主线程。
     * 结果回到主线程后再设置编辑副本、刷新视频和地图。
     */
    private fun loadPoint() {
        val ctx = requireContext().applicationContext
        val id = arguments?.getString(CameraMockFragment.ARG_POINT_ID)
        val token = ++loadToken
        routeExecutor.execute {
            val existing = id?.let { CameraMockStore.point(ctx, it) }
            main.post {
                if (token != loadToken || !uiAlive()) return@post
                if (existing != null) {
                    working = existing
                    binding.cameraPointNameInput.setText(existing.name)
                    mapReady = true
                } else {
                    val center = crosshairWgs84() ?: Pair(39.908722, 116.397499)
                    working = CameraMockStore.newPoint("", center.first, center.second)
                }
                if (pendingStill != null) {
                    binding.cameraPendingHint.visibility = View.VISIBLE
                    binding.cameraPendingHint.text = getString(R.string.camera_pending_still_hint)
                }
                updateCoordReadout()
                refreshVideos()
                fitRouteToMap()
            }
        }
    }

    private data class RoutePreview(
        val single: Pair<Double, Double>?,
        val points: List<LatLng>,
    )

    private fun buildRoutePreview(point: CameraVideoPoint): RoutePreview {
        val raw = point.videos.orEmpty()
            .flatMap { it.track }
            .sortedBy { it.tMs }
        // 室内静止时百度可能连续返回同一坐标：去重后再决定折线/单点。
        val distinct = raw.distinctBy { "%.6f,%.6f".format(it.lat, it.lon) }
        if (distinct.size < 2) {
            val single = distinct.firstOrNull() ?: CameraVideoTrackPoint(0L, point.lat, point.lon)
            return RoutePreview((single.lat to single.lon), emptyList())
        }
        return RoutePreview(null, distinct.map { (it.lat to it.lon).gcj02 })
    }

    /**
     * 折线/标记的数据准备在后台线程完成，主线程只负责 addOverlay/remove。
     */
    private fun drawRoute() {
        if (!uiAlive()) return
        val point = working ?: return
        val map = runCatching { baiduMapViewModel.baiduMap }.getOrNull() ?: return
        val token = ++routeDrawToken
        val icon = BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16)
        routeExecutor.execute {
            val preview = runCatching { buildRoutePreview(point) }.getOrNull()
            main.post {
                if (token != routeDrawToken || !uiAlive()) return@post
                routeOverlay?.let { runCatching { it.remove() } }
                routeOverlay = null
                val p = preview ?: return@post
                routeOverlay = runCatching {
                    if (p.single != null) {
                        map.addOverlay(
                            MarkerOptions()
                                .position((p.single.first to p.single.second).gcj02)
                                .icon(icon)
                        )
                    } else {
                        map.addOverlay(
                            PolylineOptions()
                                .points(p.points)
                                .width(8)
                                .color(0xFF1E88E5.toInt())
                                .zIndex(2)
                        )
                    }
                }.getOrNull()
            }
        }
    }

    private data class FitPlan(
        val center: LatLng,
        val zoom: Float?,
        val bounds: LatLngBounds?,
    )

    private fun buildFitPlan(point: CameraVideoPoint): FitPlan? {
        val raw = point.videos.orEmpty()
            .flatMap { it.track }
            .sortedBy { it.tMs }
            .ifEmpty { listOf(CameraVideoTrackPoint(0L, point.lat, point.lon)) }
        val distinct = raw.distinctBy { "%.6f,%.6f".format(it.lat, it.lon) }
        val centerLat = (distinct.minOf { it.lat } + distinct.maxOf { it.lat }) / 2.0
        val centerLon = (distinct.minOf { it.lon } + distinct.maxOf { it.lon }) / 2.0
        val center = (centerLat to centerLon).gcj02
        if (distinct.size < 2) return FitPlan(center, 19f, null)
        // 用外接矩形对角线代替 O(n²) 全点对最大距离；大路线不再在 UI 线程做平方级计算。
        val minLat = distinct.minOf { it.lat }
        val maxLat = distinct.maxOf { it.lat }
        val minLon = distinct.minOf { it.lon }
        val maxLon = distinct.maxOf { it.lon }
        val span = WorldMath.haversine(minLat, minLon, maxLat, maxLon)
        val zoom = when {
            span < 8 -> 19f
            span < 25 -> 18f
            span < 60 -> 17f
            span < 150 -> 16f
            span < 400 -> 15f
            else -> null
        }
        if (zoom != null) return FitPlan(center, zoom, null)
        val builder = LatLngBounds.Builder()
        distinct.forEach { builder.include((it.lat to it.lon).gcj02) }
        return FitPlan(center, null, runCatching { builder.build() }.getOrNull())
    }

    /**
     * 路线预览初始视角：数据计算在后台，主线程只提交 MapStatus。
     */
    private fun fitRouteToMap() {
        if (!uiAlive()) return
        val map = runCatching { baiduMapViewModel.baiduMap }.getOrNull() ?: return
        val point = working ?: return
        val token = ++fitToken
        val w = binding.bmapView.width.takeIf { it > 0 } ?: 1080
        val h = binding.bmapView.height.takeIf { it > 0 } ?: 720
        routeExecutor.execute {
            val plan = runCatching { buildFitPlan(point) }.getOrNull()
            main.post {
                if (token != fitToken || !uiAlive() || plan == null) return@post
                runCatching {
                    if (plan.zoom != null) {
                        map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(plan.center, plan.zoom))
                    } else if (plan.bounds != null) {
                        map.setMapStatus(MapStatusUpdateFactory.newLatLngBounds(plan.bounds, w, h))
                    }
                }
                initialFitDone = true
            }
        }
    }

    private fun uiAlive(): Boolean = isAdded && _binding != null

    private fun refreshVideos() {
        if (!uiAlive()) return
        val filter = requireContext().cameraMockTagFilter
        fun matches(v: CameraVideo): Boolean = CameraMockSelector.matches(v.tags, filter)
        val videos = working?.videos.orEmpty()
            .sortedWith(
                compareByDescending<CameraVideo> { matches(it) }
                    .thenByDescending { maxOf(it.addedAt, it.recordedAt) }
            )
        videoAdapter.setFilterTags(filter)
        videoAdapter.replaceAll(videos)
        binding.cameraVideoLabel.text = if (videos.isEmpty()) {
            // 空列表时把"下一步"写在这里：入口都在悬浮胶囊里，不提示会只剩一块空白卡片。
            if (pendingStill != null) {
                getString(R.string.camera_video_list) + "（0）· 点胶囊「" +
                    getString(R.string.camera_pending_save) + "」完成导入"
            } else {
                getString(R.string.camera_video_list) + "（0）· 用右下角「" +
                    getString(R.string.camera_still_add) + "」选图并绑定准星位置"
            }
        } else {
            getString(R.string.camera_video_list) + "（${videos.size}）"
        }
        // 还没有视频时给出下一步该干什么，而不是一个空列表
        binding.cameraVideoList.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
        drawRoute()
    }

    private fun addVideo(video: CameraVideo) {
        val point = working ?: return
        working = point.copy(videos = point.videos + video)
        CameraMockStore.upsert(requireContext(), working!!)
        refreshVideos()
    }

    private fun retryUpload() {
        val ctx = requireContext()
        // 上传的是整库：成功后就该把全库的"待上传"标记一起清掉，
        // 否则其它点位的行会一直显示「重试上传」（虽然重复上传是幂等的）。
        val ids = CameraMockStore.points(ctx).flatMap { it.videos }.map { it.id }
        Toast.makeText(ctx, "正在重试上传…", Toast.LENGTH_SHORT).show()
        Executors.newSingleThreadExecutor().execute {
            val result = if (CameraMockSyncClient.importLibrary(ctx)) {
                Result.success(ids.size)
            } else {
                Result.failure(IllegalStateException("system_server 素材同步失败"))
            }
            activity?.runOnUiThread {
                if (result.isSuccess) {
                    CameraMockUploadState.markUploaded(ctx, ids)
                } else {
                    CameraMockUploadState.markFailed(ctx, ids)
                }
                if (!uiAlive()) return@runOnUiThread
                if (result.isSuccess) {
                    Toast.makeText(ctx, "重试上传成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        ctx,
                        "重试仍失败：${result.exceptionOrNull()?.message}（素材仍在本地，注入框架后可再试）",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                refreshVideos()
            }
        }
    }



    private fun editVideo(video: CameraVideo) {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val nameLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply {
            hint = "素材名字"
        }
        val nameEdit = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            setText(video.name)
            setSingleLine(true)
        }
        nameLayout.addView(nameEdit)
        root.addView(nameLayout)

        val chips = com.google.android.material.chip.ChipGroup(ctx).apply {
            isSingleSelection = false
            setPadding(0, 12, 0, 0)
        }
        CameraMockStore.allTags(ctx).forEach { tag ->
            // M3 Filter 芯片：选中态直接用主题色药丸表达，不再手写 checkable 外观
            val chip = LayoutInflater.from(ctx)
                .inflate(R.layout.item_camera_tag_chip, chips, false)
                as com.google.android.material.chip.Chip
            chip.text = tag
            chip.isChecked = video.tags.any { it.equals(tag, true) }
            chip.setOnCheckedChangeListener { _, _ -> }
            chips.addView(chip)
        }
        root.addView(chips)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("编辑素材")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val point = working ?: return@setPositiveButton
                val name = nameEdit.text?.toString()?.trim().orEmpty().ifEmpty { video.name }
                val tags = (0 until chips.childCount)
                    .map { chips.getChildAt(it) as com.google.android.material.chip.Chip }
                    .filter { it.isChecked }.map { it.text.toString() }
                    .let { CameraTags.sanitize(it).toList() }
                working = point.copy(videos = point.videos.map {
                    if (it.id == video.id) it.copy(name = name, tags = tags) else it
                })
                CameraMockStore.upsert(ctx, working!!)
                refreshVideos()
                    }
            .show()
    }

    private fun confirmDelete(video: CameraVideo) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.camera_video_delete)
            .setMessage("删除后该素材文件也会从库里移除：${video.name}")
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

    /** 进入录制页：先把当前点位落库（录制页按 pointId 追加视频），再导航过去 */
    private fun openCapture() {
        val ctx = requireContext()
        val point = working ?: return
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        if (ctx.cameraMockEnabled) {
            Toast.makeText(ctx, "请先关闭虚拟摄像头，再录制现场视频", Toast.LENGTH_LONG).show()
            return
        }
        if (lm != null && MockServiceHelper.isMockStart(lm)) {
            Toast.makeText(ctx, "请先关闭位置模拟，再录制真实移动轨迹", Toast.LENGTH_LONG).show()
            return
        }
        if (CameraMockStore.point(ctx, point.id) == null) {
            val name = binding.cameraPointNameInput.text?.toString()?.trim().orEmpty()
            working = point.copy(name = name.ifBlank { getString(R.string.camera_point_unnamed) })
            CameraMockStore.upsert(ctx, working!!)
        }
        findNavController().navigate(
            R.id.nav_camera_capture,
            bundleOf(CameraCaptureFragment.ARG_POINT_ID to point.id)
        )
    }

    /** 胶囊动作：定位（两种模式都有）+ 录制/添加图片，或"保存图片点位" */
    private fun updateFabActions() {
        val bar = (activity as? MainActivity)?.fabBar ?: return
        val locate = FabBarView.Action(
            R.drawable.baseline_my_location_24,
            getString(R.string.camera_locate)
        ) { locateMe() }
        bar.setActions(
            if (pendingStill != null) {
                listOf(
                    locate,
                    FabBarView.Action(
                        R.drawable.baseline_save_24,
                        getString(R.string.camera_pending_save)
                    ) { savePendingStill() }
                )
            } else {
                listOf(
                    locate,
                    FabBarView.Action(
                        R.drawable.baseline_control_camera_24,
                        getString(R.string.camera_record)
                    ) { openCapture() },
                    // 手动添加：选图片 + 用准星选位置
                    FabBarView.Action(
                        R.drawable.baseline_add_a_photo_24,
                        getString(R.string.camera_still_add)
                    ) { pickStill() }
                )
            }
        )
    }

    /**
     * 定位：把地图中心移到**当前位置**（准星即取点位置，所以这也等于"把准星放回我脚下"）。
     * 优先用模块回读的表象位置（开启模拟时就是模拟位置）；没有则短暂等待一次融合定位。
     */
    private fun locateMe() {
        if (locating) return
        CameraMockRuntime.position()?.let { position ->
            moveMapTo(position)
            return
        }
        val ctx = requireContext()
        locating = true
        Toast.makeText(ctx, "正在获取当前位置…", Toast.LENGTH_SHORT).show()
        val sampler = CameraLocationSampler(ctx)
        locateSampler = sampler
        sampler.onLocation = { position ->
            main.post {
                if (!locating) return@post
                locating = false
                sampler.stop()
                locateSampler = null
                if (uiAlive()) moveMapTo(position)
            }
        }
        sampler.start()
        // 没有可用定位（室内/未授权）：别让"正在获取"停留太久
        main.postDelayed({
            if (locating) {
                locating = false
                sampler.stop()
                locateSampler = null
                if (uiAlive()) {
                    Toast.makeText(requireContext(), "未获取到当前位置", Toast.LENGTH_SHORT).show()
                }
            }
        }, 5000L)
    }

    private fun moveMapTo(position: Pair<Double, Double>) {
        val map = runCatching { binding.bmapView.map }.getOrNull() ?: return
        runCatching {
            val zoom = map.mapStatus?.zoom ?: 0f
            map.setMapStatus(
                if (zoom < 17f) {
                    MapStatusUpdateFactory.newLatLngZoom(position.gcj02, 17f)
                } else {
                    MapStatusUpdateFactory.newLatLng(position.gcj02)
                }
            )
        }
        updateCoordReadout()
        Toast.makeText(requireContext(), "已定位到当前位置", Toast.LENGTH_SHORT).show()
    }

    /** 待保存图片：编码成单帧素材并绑定到当前准星，落库后返回上一页 */
    private fun savePendingStill() {
        val ctx = requireContext()
        val uri = pendingStill ?: return
        val point = working ?: return
        if (stillBusy) return
        val coord = crosshairWgs84() ?: (point.lat to point.lon)
        stillBusy = true
        cameraLoading = (cameraLoading ?: CameraLoadingDialog(ctx)).also {
            it.show(getString(R.string.camera_still_importing))
        }
        val appContext = ctx.applicationContext
        stillExecutor.execute {
            val video = runCatching {
                CameraMockStore.importStill(appContext, uri, coord.first, coord.second)
            }.onFailure {
                android.util.Log.w("CameraPointEdit", "图片导入失败", it)
            }.getOrNull()
            main.post {
                stillBusy = false
                cameraLoading?.dismiss()
                cameraLoading = null
                if (!uiAlive()) return@post
                if (video == null) {
                    Toast.makeText(ctx, R.string.camera_still_none, Toast.LENGTH_LONG).show()
                    return@post
                }
                val typed = binding.cameraPointNameInput.text?.toString()?.trim().orEmpty()
                working = point.copy(
                    name = typed.ifBlank { video.name },
                    lat = coord.first,
                    lon = coord.second,
                    videos = listOf(video)
                )
                CameraMockStore.upsert(ctx, working!!)
                CameraAutoUpload.trigger(ctx, force = true)
                pendingSaved = true
                pendingStill = null
                Toast.makeText(
                    ctx,
                    getString(R.string.camera_still_bound, CameraMockFormat.coord(coord.first, coord.second)),
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().popBackStack()
            }
        }
    }

    /** 名称/坐标写回库里；只有"有点内容"的点才落盘，避免空点位污染列表 */
    private fun persistIfMeaningful() {
        if (pendingStill != null && !pendingSaved) return
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
