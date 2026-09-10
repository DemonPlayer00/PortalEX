package moe.fuqiuluo.portalex.ui.mock

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.map.Polyline
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.Portal
import moe.fuqiuluo.portalex.android.widget.FabBarView
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.bdmap.locateMe
import moe.fuqiuluo.portalex.bdmap.setMapConfig
import moe.fuqiuluo.portalex.databinding.FragmentRouteEditBinding
import moe.fuqiuluo.portalex.ext.gcj02
import moe.fuqiuluo.portalex.ext.jsonHistoricalRoutes
import moe.fuqiuluo.portalex.ext.mapType
import moe.fuqiuluo.portalex.ext.shiftAboveIme
import moe.fuqiuluo.portalex.ext.wgs84
import moe.fuqiuluo.portalex.ui.MapControlsHost
import moe.fuqiuluo.portalex.ui.viewmodel.BaiduMapViewModel
import java.math.BigDecimal
import kotlin.random.Random


class RouteEditFragment : Fragment(), MapControlsHost {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!

    private lateinit var mLocationClient: LocationClient
    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()

    private var mPoints: ArrayList<Pair<Double, Double>> = arrayListOf()

    /**
     * 逐端点平滑标志（与 [mPoints] 等长）：mSmoothSegments[i] = 「端点 i 之后的线段
     * （i → i+1）是否平滑」；最后一个端点没有后续线段（占位 false）。
     */
    private var mSmoothSegments: ArrayList<Boolean> = arrayListOf()
    private var isDrawing = false

    /** 已落地线段的覆盖物（与 mPoints 的相邻点对一一对应），refresh() 时整体重建 */
    private val mSegmentOverlays = arrayListOf<Polyline>()

    /**
     * 正在拖动的预览线段。
     *
     * 旧实现每次 ACTION_MOVE 都 clear() + 重画全部线段，整个覆盖物层每帧重建，
     * 肉眼可见地闪。现在预览段就地 [Polyline.setPoints] 更新，拖动过程中
     * 地图上的覆盖物对象始终不变（颜色用 setColor 原地改）；
     * ACTION_UP 时它直接“转正”为已落地线段，同样不重建。
     */
    private var mPreviewOverlay: Polyline? = null

    /** 平滑绘制开关（胶囊按钮控制）：打开时新绘制的线段标记为平滑（绿色） */
    private var smoothDrawing = false

    /** 当前注册在悬浮胶囊上的功能集（供选中态刷新） */
    private var fabActions: List<FabBarView.Action> = emptyList()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)

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
                if (Random.nextBoolean()) moe.fuqiuluo.portalex.R.drawable.icon_my_location else null
            )

            setOnMapClickListener(object : BaiduMap.OnMapClickListener {
                override fun onMapClick(loc: LatLng) {
                    // 绘制中不插手：markMap() 会 clear() 掉全部覆盖物，
                    // 而绘制时抬手本身就是一次「点击」，会把刚画好的路线一起抹掉。
                    if (isDrawing) return

                    // 默认获取的gcj02坐标，需要转换一下
                    baiduMapViewModel.markedLoc = loc.wgs84

                    lifecycleScope.launch {
                        baiduMapViewModel.showDetailView = false
                        baiduMapViewModel.mGeoCoder?.reverseGeoCode(
                            ReverseGeoCodeOption().location(
                                loc
                            )
                        )
                    }

                    // Fixed the issue that getting geolocation information was stuck
                    lifecycleScope.launch {
                        markMap()
                    }
                }

                override fun onMapPoiClick(poi: MapPoi) {}
            })

            setOnMapLongClickListener { loc ->
                if (loc == null) return@setOnMapLongClickListener
                if (isDrawing) return@setOnMapLongClickListener

                // 默认获取的gcj02坐标，需要转换一下
                baiduMapViewModel.markedLoc = loc.wgs84
                lifecycleScope.launch {
                    baiduMapViewModel.showDetailView = true
                    baiduMapViewModel.mGeoCoder?.reverseGeoCode(ReverseGeoCodeOption().location(loc))
                }
                lifecycleScope.launch {
                    markMap()
                }
            }

            binding.mapTypeGroup.check(
                when (mapType) {
                    BaiduMap.MAP_TYPE_NORMAL -> moe.fuqiuluo.portalex.R.id.map_type_normal
                    BaiduMap.MAP_TYPE_SATELLITE -> moe.fuqiuluo.portalex.R.id.map_type_satellite
                    else -> moe.fuqiuluo.portalex.R.id.map_type_normal
                }
            )
        }

        mLocationClient = LocationClient(requireContext())
        val option = LocationClientOption()
        option.isOpenGps = true
        option.enableSimulateGps = false
        option.setIsNeedAddress(true) /* 关掉这个无法获取当前城市 */
        option.setNeedDeviceDirect(true)
        option.isLocationNotify = true
        option.setIgnoreKillProcess(true)
        option.setIsNeedLocationDescribe(false)
        option.setIsNeedLocationPoiList(false)
        option.isOpenGnss = true
        option.setIsNeedAltitude(false)
        option.locationMode = LocationClientOption.LocationMode.Hight_Accuracy

        option.setCoorType(Portal.DEFAULT_COORD_STR)
        option.setScanSpan(1000)
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

                if (loc.city != null)
                    MainActivity.mCityString = loc.city

                with(baiduMapViewModel) {
                    currentLocation = loc.wgs84
                    baiduMap.setMyLocationData(locData)
                }
            }
        })
        baiduMapViewModel.mLocationClient = mLocationClient
        mLocationClient.enableLocInForeground(1, baiduMapViewModel.mNotification)
        mLocationClient.start()

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                moe.fuqiuluo.portalex.R.id.map_type_normal -> {
                    binding.bmapView.map.mapType = BaiduMap.MAP_TYPE_NORMAL
                }

                moe.fuqiuluo.portalex.R.id.map_type_satellite -> {
                    binding.bmapView.map.mapType = BaiduMap.MAP_TYPE_SATELLITE
                }

                else -> {
                    Log.e("HomeFragment", "Unknown location view mode: $checkedId")
                }
            }
            context?.mapType = binding.bmapView.map.mapType
        }

        // 地图状态变化（手指拖动、松手惯性、缩放、定位跳转都算）时同步预览段：
        // 保证屏幕上的线永远从「最新端点」连到「准星」（地图中心）。
        baiduMapViewModel.baiduMap.setOnMapStatusChangeListener(
            object : BaiduMap.OnMapStatusChangeListener {
                override fun onMapStatusChangeStart(status: MapStatus?) = syncPreviewSegment()
                override fun onMapStatusChangeStart(status: MapStatus?, reason: Int) =
                    syncPreviewSegment()

                override fun onMapStatusChange(status: MapStatus?) = syncPreviewSegment()
                override fun onMapStatusChangeFinish(status: MapStatus?) = syncPreviewSegment()
            }
        )

        baiduMapViewModel.baiduMap.setOnMapTouchListener {
            if (!isDrawing) return@setOnMapTouchListener
            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 第一个端点：画线前需要一个锚，就用准星当前的位置
                    if (mPoints.isEmpty()) appendPoint(currentCenter())
                    syncPreviewSegment()
                }

                MotionEvent.ACTION_MOVE -> syncPreviewSegment()

                MotionEvent.ACTION_UP -> commitPreviewAsSegment()

                // 手势被打断：预览段锚在最新端点上，留着继续跟随准星即可
                MotionEvent.ACTION_CANCEL -> syncPreviewSegment()
            }
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // MapView 需要自己的一套生命周期；不转发的话地图不会初始化/恢复渲染
        binding.bmapView.onCreate(requireContext(), savedInstanceState)
    }

    /** 三点展开栏入口：当前是否卫星图（未选中=普通图） */
    override fun isSatellite(): Boolean =
        binding.mapTypeGroup.checkedRadioButtonId == R.id.map_type_satellite

    /** 三点展开栏入口：切换卫星图/普通图（触发原 RadioGroup 监听） */
    override fun toggleSatellite() {
        binding.mapTypeGroup.check(
            if (isSatellite()) R.id.map_type_normal else R.id.map_type_satellite
        )
    }

    override fun onPause() {
        super.onPause()

        if (_binding != null) {
            binding.bmapView.onPause()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (_binding != null) {
            // 切换 Fragment 时该方法也会被触发，此时 view 已销毁，不能转发
            binding.bmapView.onSaveInstanceState(outState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 地图已随视图销毁，标记地图不再存在（主界面搜索选中会看这个标记）
        baiduMapViewModel.isExists = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 释放覆盖物与绘制态：它们都指向已随视图销毁的地图对象
        mSegmentOverlays.clear()
        mPreviewOverlay = null
        isDrawing = false
        // 先把覆盖物清干净，再销毁地图视图，释放 GL 线程与显存
        _binding?.bmapView?.onDestroy()
        _binding = null
    }

    override fun onResume() {
        super.onResume()

        // 回到前台时恢复地图渲染（与 HomeFragment 一致）
        if (_binding != null) {
            binding.bmapView.onResume()
        }

        // 注册悬浮胶囊功能集：路线模拟 = 开始绘制 / 平滑绘制 / 撤回 / 完成 / 我的位置
        // （胶囊为 Activity 级单实例，切换功能集自动重置收起态）
        var smoothActionRef: FabBarView.Action? = null
        val smoothAction = FabBarView.Action(
            R.drawable.baseline_smooth_route_24,
            getString(R.string.fab_smooth_draw),
            smoothDrawing
        ) {
            smoothDrawing = !smoothDrawing
            smoothActionRef?.active = smoothDrawing
            // 悬挂的预览段固定紫色（终点未确定），不受平滑开关影响；
            // 落地时才会按此时的开关换成蓝/绿，所以仅刷新工具箱着色
            (activity as? MainActivity)?.fabBar?.refreshActionTints(fabActions)
        }
        smoothActionRef = smoothAction

        fabActions = listOf(
            FabBarView.Action(
                R.drawable.baseline_add_location_24,
                getString(R.string.fab_start_route)
            ) {
                isDrawing = true
                mPoints = arrayListOf()
                mSmoothSegments = arrayListOf()
                mPreviewOverlay = null
                // 清掉上一次留在图上的路线，且把覆盖物列表重置到干净状态
                refresh()
                setCrosshairVisible(true)
            },
            smoothAction,
            FabBarView.Action(
                R.drawable.baseline_rollback_24,
                getString(R.string.rollback)
            ) {
                removeLastPoint()
                refresh()
            },
            FabBarView.Action(
                R.drawable.baseline_complete_24,
                getString(R.string.fab_complete_route)
            ) {
                // 悬挂的预览段也是屏幕上看得见的一段路，先落地再保存，
                // 否则保存出来的路线会比屏幕上少一截
                if (isDrawing) commitPreviewAsSegment()
                isDrawing = false
                setCrosshairVisible(false)
                if (!showAddRouteDialog()) {
                    Toast.makeText(requireContext(), "选择路线异常", Toast.LENGTH_SHORT).show()
                }
            },
            FabBarView.Action(
                R.drawable.baseline_my_location_24,
                getString(R.string.follow_location)
            ) {
                baiduMapViewModel.baiduMap.locateMe()
            }
        )
        (activity as? MainActivity)?.fabBar?.setActions(fabActions)
    }

    /** 追加端点：把「上一端点 → 新端点」线段的平滑标志记在上一端点索引上 */
    private fun appendPoint(point: Pair<Double, Double>) {
        if (mPoints.isNotEmpty()) {
            while (mSmoothSegments.size < mPoints.size) mSmoothSegments.add(false)
            mSmoothSegments[mPoints.size - 1] = smoothDrawing
        }
        mPoints.add(point)
        mSmoothSegments.add(false) // 新端点：其后尚无线段
    }

    /** 撤回最后端点；新末位端点无线段，标志复位 */
    private fun removeLastPoint() {
        if (mPoints.isEmpty()) return
        mPoints.removeAt(mPoints.size - 1)
        if (mSmoothSegments.size > mPoints.size) {
            mSmoothSegments.removeAt(mSmoothSegments.size - 1)
        }
        while (mSmoothSegments.size < mPoints.size) mSmoothSegments.add(false)
        if (mSmoothSegments.isNotEmpty()) {
            mSmoothSegments[mSmoothSegments.size - 1] = false
        }
    }

    /** 准星仅在绘制过程中显示，标出当前画笔（地图中心）的落点 */
    private fun setCrosshairVisible(visible: Boolean) {
        _binding?.drawCrosshair?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 第 index 段的颜色：平滑 = 绿，普通 = 蓝（越界按普通算） */
    private fun segmentColor(index: Int): Int =
        if (mSmoothSegments.getOrElse(index) { false }) HistoricalRoute.COLOR_SMOOTH
        else HistoricalRoute.COLOR_NORMAL

    private fun refresh() {
        // 唯一需要 clear() 的时机：路线点集被整体改变（开始绘制 / 撤回）
        baiduMapViewModel.baiduMap.clear()
        mSegmentOverlays.clear()
        mPreviewOverlay = null
        drawRecordedSegments()
        // 点集变了，连到准星的预览段要重新锚到新的末位端点
        syncPreviewSegment()
    }

    /** 当前地图中心（= 准星所在位置），wgs84 */
    private fun currentCenter(): Pair<Double, Double> =
        baiduMapViewModel.baiduMap.mapStatus.target.wgs84

    /** 两点是否同一位置（纬度/经度差都在 1e-9 度内） */
    private fun samePoint(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean =
        kotlin.math.abs(a.first - b.first) < 1e-9 &&
            kotlin.math.abs(a.second - b.second) < 1e-9

    /**
     * 把「最新端点 → 准星」的预览段对齐到当前地图状态。
     *
     * 这是预览段的唯一更新入口：不论地图是被手指拖动、松手后的惯性滑动、
     * 缩放还是定位跳转改变中心，线头都重新贴在准星上；起点永远取 [mPoints]
     * 的最后一个端点，不使用手势中途缓存的旧坐标——否则线的一端会落在
     * 数据里根本不存在的坐标上（拖动、惯性、缩放之后尤其明显）。
     */
    private fun syncPreviewSegment() {
        if (!isDrawing) return
        val anchor = mPoints.lastOrNull() ?: return  // 还没有端点：等第一次按下
        val center = currentCenter()
        if (samePoint(anchor, center)) {
            // 端点正好在准星上：没有待画的线段，把预览收走
            mPreviewOverlay?.let { baiduMapViewModel.baiduMap.removeOverLays(listOf(it)) }
            mPreviewOverlay = null
            return
        }
        drawLine(anchor, center)
    }

    /**
     * 抬手落地：预览段转正为已落地线段，另一端就是抬手瞬间的准星位置。
     * 准星没动（单击）时不产生零长的退化线段。
     */
    private fun commitPreviewAsSegment() {
        val center = currentCenter()
        val anchor = mPoints.lastOrNull()
        if (anchor == null) {
            appendPoint(center)
            return
        }
        if (samePoint(anchor, center)) {
            syncPreviewSegment()
            return
        }
        appendPoint(center)
        val preview = mPreviewOverlay
        if (preview != null) {
            // 预览段最后一次同步可能早于这次抬手：端点按数据校正一次
            preview.setPoints(listOf<LatLng>(anchor.gcj02, center.gcj02))
            preview.setColor(segmentColor(mPoints.size - 2))
            mSegmentOverlays.add(preview)
        } else {
            // 没有预览对象（例如中心变化没触发过回调）：按数据补一段
            addRecordedSegment(mPoints.size - 2)
        }
        mPreviewOverlay = null
    }

    /** 重画全部已落地线段，按逐段平滑标志着色（平滑 = 绿色，普通 = 蓝色） */
    private fun drawRecordedSegments() {
        mSegmentOverlays.clear()
        for (i in 0 until mPoints.size - 1) {
            addRecordedSegment(i)
        }
    }

    /** 新增第 index 段（mPoints[index] → mPoints[index+1]）的覆盖物 */
    private fun addRecordedSegment(index: Int): Polyline? {
        if (index < 0 || index + 1 >= mPoints.size) return null
        val overlay = baiduMapViewModel.baiduMap.addOverlay(
            PolylineOptions()
                .color(segmentColor(index))
                .width(10)
                .points(listOf<LatLng>(mPoints[index].gcj02, mPoints[index + 1].gcj02))
        ) as? Polyline ?: return null
        mSegmentOverlays.add(overlay)
        return overlay
    }

    /**
     * 预览段：首次为新建覆盖物，之后就地 setPoints 更新。
     *
     * 关键是不能 clear()——每帧重建整个覆盖物层就是闪烁的来源。
     * 端点由 [syncPreviewSegment] 指定：起点 = 最新端点，终点 = 准星（地图中心）；
     * 终点尚未确定，所以统一用紫色，落地时才换成对应的段色。
     */
    private fun drawLine(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        val points = listOf<LatLng>(start.gcj02, end.gcj02)
        val existing = mPreviewOverlay
        if (existing == null) {
            mPreviewOverlay = baiduMapViewModel.baiduMap.addOverlay(
                PolylineOptions()
                    .color(HistoricalRoute.COLOR_PREVIEW)
                    .width(10)
                    .points(points)
            ) as? Polyline
        } else {
            existing.setPoints(points)
        }
    }


    private fun markMap(moveEyes: Boolean = false) = with(baiduMapViewModel) {
        val loc = markedLoc!!.gcj02
        val ooA = MarkerOptions()
            .position(loc)
            .icon(mMapIndicator)
        baiduMap.clear()
        baiduMap.addOverlay(ooA)

        if (moveEyes) {
            baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLng(loc))
        }
    }

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddRouteDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_route, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etRouteName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editRoute = dialogView.findViewById<TextInputEditText>(R.id.etRouteSet)
        editRoute.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editRoute.error = "路线经纬度不能为空"
            } else {
                try {
                    val json = it.toString()
                    // 转为 LatLng 数组
                    val points = JSON.parseArray(json)
                    if (points.size < 2) {
                        editRoute.error = "路线经纬度至少需要两个点"
                    }
                    // 循环检查每个点的经纬度是否合法
                    for (point in points) {
                        val jsonObject = point as JSONObject
                        val latitude = jsonObject.getDouble("first")
                        val longitude = jsonObject.getDouble("second")
                        if (!checkLatLon(latitude, longitude)) {
                            editRoute.error = "路线经纬度格式错误"
                            return@addTextChangedListener
                        }
                    }
                } catch (e: Exception) {
                    editRoute.error = "路线经纬度json格式错误"
                }
            }
        }

        editRoute.setText(JSON.toJSONString(mPoints))

        // 平滑数组编辑框：每个端点对应其后线段是否平滑，最后一个端点无线段（占位 false）
        val editSmooth = dialogView.findViewById<TextInputEditText>(R.id.etRouteSmooth)
        editSmooth.setText(
            List(mPoints.size) { mSmoothSegments.getOrElse(it) { false } }
                .joinToString(",") { if (it) "true" else "false" }
        )

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        val dialog = builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val routeJson = editRoute.text.toString()

                var name = editName.text?.toString()
                if (name.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val points = JSON.parseArray(routeJson)
                if (points.size < 2) {
                    Toast.makeText(requireContext(), "路线经纬度至少需要两个点", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }

                // 循环检查每个点的经纬度是否合法
                for (point in points) {
                    val jsonObject = point as JSONObject
                    val latitude = jsonObject.getDouble("first")
                    val longitude = jsonObject.getDouble("second")
                    if (!checkLatLon(latitude, longitude)) {
                        Toast.makeText(requireContext(), "路线经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }
                }

                fun MutableSet<String>.addLocation(
                    name: String,
                    address: String,
                    lat: Double,
                    lon: Double
                ): Boolean {
                    if (any { it.split(",")[0] == name }) {
                        return false
                    }
                    add(
                        "$name,$address,${
                            BigDecimal.valueOf(lat).toPlainString()
                        },${BigDecimal.valueOf(lon).toPlainString()}"
                    )
                    return true
                }

                // 解析平滑数组（容错：空 = 全不平滑；长度不足补 false；非法字符拒绝保存）
                val smoothParsed = parseSmoothInput(editSmooth.text?.toString().orEmpty())
                if (smoothParsed == null) {
                    Toast.makeText(
                        requireContext(),
                        "平滑数组格式错误（true/false，逗号分隔）",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val smooth = List(mPoints.size) { smoothParsed.getOrElse(it) { false } }

                with(requireContext()) {
                    val routes = HistoricalRoute.parseList(jsonHistoricalRoutes)
                    routes.add(HistoricalRoute(name, mPoints, smooth))
                    jsonHistoricalRoutes = HistoricalRoute.listToJson(routes)
                }

                Toast.makeText(requireContext(), "路线已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
        dialog.shiftAboveIme(requireActivity().window.decorView)

        return true
    }

    /**
     * 解析平滑数组文本：支持 `true,false` / `[true,false]` / `1,0` / `T,F`。
     * 空文本 = 空列表（全不平滑）；任一元素非法返回 null。
     */
    private fun parseSmoothInput(text: String): List<Boolean>? {
        val cleaned = text.trim().removePrefix("[").removeSuffix("]").trim()
        if (cleaned.isEmpty()) return emptyList()
        val result = mutableListOf<Boolean>()
        for (item in cleaned.split(",")) {
            result.add(
                when (item.trim().lowercase()) {
                    "true", "1", "t" -> true
                    "false", "0", "f" -> false
                    else -> return null
                }
            )
        }
        return result
    }
}