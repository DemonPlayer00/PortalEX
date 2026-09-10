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
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.MyLocationData
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
    private var lastPoint: Pair<Double, Double>? = null

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

        baiduMapViewModel.baiduMap.setOnMapTouchListener {
            if (isDrawing) {
                val currentPoint = baiduMapViewModel.baiduMap.mapStatus.target.wgs84

                when (it.action) {
                    MotionEvent.ACTION_DOWN -> { // 新增 DOWN 事件处理
                        if (mPoints.size <= 0) {
                            appendPoint(currentPoint)
                        }
                        lastPoint = currentPoint
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (lastPoint == null) {
                            lastPoint = currentPoint
                        }
                        lastPoint?.let { lp -> drawLine(lp, currentPoint) }
                    }

                    MotionEvent.ACTION_UP -> {
                        appendPoint(currentPoint)
                        lastPoint = null // 关键修改：重置起点
                    }
                }
            }
        }

        return binding.root
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

    override fun onResume() {
        super.onResume()

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
            // 仅刷新着色，不重建功能集——避免切换时胶囊意外收起
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
                lastPoint = null
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
                isDrawing = false
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

    private fun refresh() {
        baiduMapViewModel.baiduMap.clear() // 清除之前的所有覆盖物
        drawRecordedSegments()
    }

    /** 绘制已记录线段：按逐段平滑标志着色（平滑 = 绿色，普通 = 蓝色） */
    private fun drawRecordedSegments() {
        for (i in 0 until mPoints.size - 1) {
            baiduMapViewModel.baiduMap.addOverlay(
                PolylineOptions()
                    .color(
                        if (mSmoothSegments.getOrElse(i) { false }) HistoricalRoute.COLOR_SMOOTH
                        else HistoricalRoute.COLOR_NORMAL
                    )
                    .width(10)
                    .points(listOf<LatLng>(mPoints[i].gcj02, mPoints[i + 1].gcj02))
            )
        }
    }

    private fun drawLine(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        baiduMapViewModel.baiduMap.clear() // 清除之前的所有覆盖物
        drawRecordedSegments()

        // 预览段与当前平滑开关同色，所见即所得
        baiduMapViewModel.baiduMap.addOverlay(
            PolylineOptions()
                .color(if (smoothDrawing) HistoricalRoute.COLOR_SMOOTH else HistoricalRoute.COLOR_NORMAL)
                .width(10)
                .points(listOf<LatLng>(start.gcj02, end.gcj02))
        )
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