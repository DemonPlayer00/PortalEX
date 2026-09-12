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
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.model.LatLng
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.Portal
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.permission.RequestPermissions
import moe.fuqiuluo.portalex.android.widget.FabBarView
import moe.fuqiuluo.portalex.bdmap.locateMe
import moe.fuqiuluo.portalex.bdmap.setMapConfig
import moe.fuqiuluo.portalex.databinding.FragmentCameraPointEditBinding
import moe.fuqiuluo.portalex.ext.gcj02
import moe.fuqiuluo.portalex.ext.mapType
import moe.fuqiuluo.portalex.ext.wgs84
import moe.fuqiuluo.portalex.ui.MapControlsHost
import moe.fuqiuluo.portalex.ui.viewmodel.BaiduMapViewModel
import moe.fuqiuluo.xposed.camera.CameraVideo
import moe.fuqiuluo.xposed.camera.CameraVideoPoint
import kotlin.random.Random

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

    private var _binding: FragmentCameraPointEditBinding? = null
    private val binding get() = _binding!!

    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()

    private lateinit var mLocationClient: LocationClient
    private lateinit var videoAdapter: CameraVideoAdapter

    /** 正在编辑的点（内存工作副本）；null = 还没加载（首次 onResume 时按参数或地图中心建） */
    private var working: CameraVideoPoint? = null

    /** 地图是否已经给出过有效中心：没就绪前不拿"北京默认中心"去覆盖坐标 */
    private var mapReady = false

    private val permissionRequester by lazy { RequestPermissions(requireActivity() as androidx.appcompat.app.AppCompatActivity) }

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
        addVideo(CameraMockStore.registerRecorded(requireContext(), file, null))
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            var ok = 0
            uris.forEach { uri ->
                val video = CameraMockStore.importVideo(requireContext(), uri)
                if (video != null) {
                    addVideo(video)
                    ok++
                }
            }
            Toast.makeText(
                requireContext(),
                if (ok == uris.size) "已导入 $ok 段视频" else "导入 $ok/${uris.size} 段（其余不可读）",
                Toast.LENGTH_SHORT
            ).show()
        }
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
            isMyLocationEnabled = true
            setMapConfig(
                baiduMapViewModel.perspectiveState,
                if (Random.nextBoolean()) R.drawable.icon_my_location else null
            )
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
            onDelete = { video -> confirmDelete(video) }
        )
        binding.cameraVideoList.layoutManager = LinearLayoutManager(requireContext())
        binding.cameraVideoList.adapter = videoAdapter

        initLocationClient()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // MapView 需要自己的一套生命周期
        binding.bmapView.onCreate(requireContext(), savedInstanceState)
    }

    private fun initLocationClient() {
        mLocationClient = LocationClient(requireContext())
        val option = LocationClientOption().apply {
            isOpenGps = true
            enableSimulateGps = false
            setIsNeedAddress(false)
            setNeedDeviceDirect(true)
            isLocationNotify = true
            setIgnoreKillProcess(true)
            setIsNeedLocationDescribe(false)
            setIsNeedLocationPoiList(false)
            isOpenGnss = true
            setIsNeedAltitude(false)
            locationMode = LocationClientOption.LocationMode.Hight_Accuracy
            setCoorType(Portal.DEFAULT_COORD_STR)
            setScanSpan(1000)
        }
        mLocationClient.locOption = option
        mLocationClient.registerLocationListener(object : BDAbstractLocationListener() {
            override fun onReceiveLocation(loc: BDLocation?) {
                if (loc == null) return
                val locData = MyLocationData.Builder()
                    .accuracy(loc.radius)
                    .direction(loc.direction)
                    .latitude(loc.latitude)
                    .longitude(loc.longitude)
                    .build()
                with(baiduMapViewModel) {
                    currentLocation = loc.wgs84
                    baiduMap.setMyLocationData(locData)
                }
            }
        })
        baiduMapViewModel.mLocationClient = mLocationClient
        mLocationClient.enableLocInForeground(1, baiduMapViewModel.mNotification)
        mLocationClient.start()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) binding.bmapView.onResume()
        (activity as? MainActivity)?.fabBar?.setActions(
            listOf(
                FabBarView.Action(
                    R.drawable.baseline_control_camera_24,
                    getString(R.string.camera_record)
                ) { startRecording() },
                FabBarView.Action(
                    R.drawable.ic_menu_gallery,
                    getString(R.string.camera_import)
                ) { importLauncher.launch(arrayOf("video/*")) },
                FabBarView.Action(
                    R.drawable.baseline_save_24,
                    getString(R.string.camera_point_save)
                ) { saveAndExit() },
                FabBarView.Action(
                    R.drawable.baseline_my_location_24,
                    getString(R.string.follow_location)
                ) { baiduMapViewModel.baiduMap.locateMe() }
            )
        )
        if (working == null) loadPoint()
        refreshVideos()
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
        if (::mLocationClient.isInitialized && mLocationClient.isStarted) {
            mLocationClient.disableLocInForeground(true)
            mLocationClient.stop()
        }
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
        binding.cameraCoordReadout.text = "准星 ${CameraMockFormat.coord(coord.first, coord.second)}"
    }

    private fun loadPoint() {
        val ctx = requireContext()
        val id = arguments?.getString(CameraMockFragment.ARG_POINT_ID)
        val existing = id?.let { CameraMockStore.point(ctx, it) }
        if (existing != null) {
            working = existing
            binding.cameraPointNameInput.setText(existing.name)
            runCatching {
                baiduMapViewModel.baiduMap.setMapStatus(
                    MapStatusUpdateFactory.newLatLngZoom(Pair(existing.lat, existing.lon).gcj02, 19f)
                )
            }
            mapReady = true
        } else {
            val center = crosshairWgs84() ?: Pair(39.908722, 116.397499)
            working = CameraMockStore.newPoint("", center.first, center.second)
        }
        updateCoordReadout()
    }

    private fun refreshVideos() {
        val videos = working?.videos.orEmpty()
        videoAdapter.replaceAll(videos)
        binding.cameraVideoLabel.text = getString(R.string.camera_video_list) + "（${videos.size}）"
        // 还没有视频时给出下一步该干什么，而不是一个空列表
        binding.cameraVideoList.visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun addVideo(video: CameraVideo) {
        val point = working ?: return
        working = point.copy(videos = point.videos + video)
        CameraMockStore.upsert(requireContext(), working!!)
        refreshVideos()
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
        lifecycleScope.launch {
            // 权限判定直接查系统状态：RequestPermissions 只负责"申请"，不负责"查询"
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                val (granted, _) = permissionRequester.request(setOf(Manifest.permission.CAMERA))
                if (granted.isEmpty()) {
                    Toast.makeText(ctx, "需要摄像头权限才能录制", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            recordLauncher.launch(Intent(ctx, CameraRecordActivity::class.java))
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

    private fun saveAndExit() {
        val point = working
        if (point == null) {
            findNavController().popBackStack()
            return
        }
        val name = binding.cameraPointNameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "请填写点位名称", Toast.LENGTH_SHORT).show()
            return
        }
        if (crosshairWgs84() == null) {
            Toast.makeText(requireContext(), "地图尚未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        persistIfMeaningful()
        Toast.makeText(requireContext(), "已保存「$name」", Toast.LENGTH_SHORT).show()
        findNavController().popBackStack()
    }
}
