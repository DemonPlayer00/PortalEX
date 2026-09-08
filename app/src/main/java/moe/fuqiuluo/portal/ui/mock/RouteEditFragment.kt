package moe.fuqiuluo.portal.ui.mock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
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
import moe.fuqiuluo.portal.MainActivity
import moe.fuqiuluo.portal.Portal
import moe.fuqiuluo.portal.R
import moe.fuqiuluo.portal.bdmap.locateMe
import moe.fuqiuluo.portal.bdmap.setMapConfig
import moe.fuqiuluo.portal.databinding.FragmentRouteEditBinding
import moe.fuqiuluo.portal.ext.gcj02
import moe.fuqiuluo.portal.ext.jsonHistoricalRoutes
import moe.fuqiuluo.portal.ext.mapType
import moe.fuqiuluo.portal.ext.wgs84
import moe.fuqiuluo.portal.ui.viewmodel.BaiduMapViewModel
import moe.fuqiuluo.portal.ui.viewmodel.HomeViewModel
import java.math.BigDecimal
import java.util.List
import kotlin.random.Random


class RouteEditFragment : Fragment() {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!

    private val routeEditViewModel by viewModels<HomeViewModel>()
    private lateinit var mLocationClient: LocationClient
    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()

    private var mPoints: ArrayList<Pair<Double, Double>> = arrayListOf()
    private var isDrawing = false
    private var lastPoint: Pair<Double, Double>? = null

    // 胶囊裁剪宽度（outline 圆角矩形右端，收起=圆/展开=胶囊）
    private var fabBarClipWidth = 0


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
                if (Random.nextBoolean()) moe.fuqiuluo.portal.R.drawable.icon_my_location else null
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
                    BaiduMap.MAP_TYPE_NORMAL -> moe.fuqiuluo.portal.R.id.map_type_normal
                    BaiduMap.MAP_TYPE_SATELLITE -> moe.fuqiuluo.portal.R.id.map_type_satellite
                    else -> moe.fuqiuluo.portal.R.id.map_type_normal
                }
            )
        }

        binding.fab.setOnClickListener { view ->
            val expandBar = binding.fabExpandBar
            val subFabList = listOf(
                binding.fabStart,
                binding.fabRollback,
                binding.fabComplete,
                binding.fabMyLocation
            )

            if (!routeEditViewModel.mFabOpened) {
                routeEditViewModel.mFabOpened = true
                view.isClickable = false

                view.animate()
                    .rotation(90f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                // outline 裁剪窗口向右展开：圆形 → 胶囊（右端始终圆角）
                subFabList.forEach {
                    it.visibility = View.VISIBLE
                    it.isEnabled = true
                }
                animateFabBarClip(expandBar.width, 220, DecelerateInterpolator())
                expandBar.postDelayed({ view.isClickable = true }, 240)
            } else {
                routeEditViewModel.mFabOpened = false
                view.isClickable = false

                view.animate()
                    .rotation(0f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                // outline 裁剪窗口向左收回：胶囊 → 圆形。功能按钮 INVISIBLE
                // （占位不跳变、不接收触摸 → 点击透视到地图）
                subFabList.forEach {
                    it.visibility = View.INVISIBLE
                    it.isEnabled = false
                }
                animateFabBarClip(collapsedFabBarWidth(), 200, AccelerateInterpolator())
                expandBar.postDelayed({ view.isClickable = true }, 220)
            }
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
                moe.fuqiuluo.portal.R.id.map_type_normal -> {
                    binding.bmapView.map.mapType = BaiduMap.MAP_TYPE_NORMAL
                }

                moe.fuqiuluo.portal.R.id.map_type_satellite -> {
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
                            mPoints.add(currentPoint)
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
                        mPoints.add(currentPoint)
                        lastPoint = null // 关键修改：重置起点
                    }
                }
            }
        }

        binding.fabStart.setOnClickListener {
            isDrawing = true;
            mPoints = arrayListOf()
            lastPoint = null; // 重置上一个点
        }

        binding.fabRollback.setOnClickListener {
            // 撤回上一个点并且刷新地图
            if (mPoints.size > 0) {
                mPoints.removeAt(mPoints.size - 1)
                refresh()
            }
        }

        binding.fabComplete.setOnClickListener {
            isDrawing = false
            if (!showAddRouteDialog()) {
                Toast.makeText(requireContext(), "选择路线异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabMyLocation.setOnClickListener {
            baiduMapViewModel.baiduMap.locateMe()
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 胶囊默认收缩：outline 圆角矩形裁剪（右端始终圆角）
        val expandBar = binding.fabExpandBar
        expandBar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0,
                    fabBarClipWidth.coerceIn(0, v.width),
                    v.height,
                    fabBarCornerRadius()
                )
            }
        }
        expandBar.clipToOutline = true
        fabBarClipWidth = collapsedFabBarWidth()
        expandBar.invalidateOutline()

        // 功能按钮初始 INVISIBLE（占位不跳变、不拦截点击、不误触）
        listOf(binding.fabStart, binding.fabRollback, binding.fabComplete, binding.fabMyLocation)
            .forEach {
                it.visibility = View.INVISIBLE
                it.isEnabled = false
            }

        // 复位展开按钮姿态：旋转动画写入的 rotation 会被 View saved state
        // 记录（有 id 的 View），Fragment 重建后恢复成 90°——与胶囊 clip
        // 重新初始化为圆形矛盾，必须显式复位
        binding.fab.rotation = 0f
        binding.fab.isClickable = true
    }

    /** 胶囊收起态宽度：展开按钮 48dp + 容器 padding 12dp */
    private fun collapsedFabBarWidth(): Int =
        (60 * resources.displayMetrics.density).toInt()

    /** 胶囊背景半径：高度一半（60dp → 30dp），收起态即正圆 */
    private fun fabBarCornerRadius(): Float =
        30f * resources.displayMetrics.density

    /** 驱动 outline 裁剪宽度动画（内容零拉伸，右端始终圆角） */
    private fun animateFabBarClip(
        toWidth: Int,
        duration: Long,
        interpolator: Interpolator
    ) {
        val expandBar = binding.fabExpandBar
        val fromWidth = fabBarClipWidth
        val animator = ValueAnimator.ofInt(fromWidth, toWidth)
        animator.duration = duration
        animator.interpolator = interpolator
        animator.addUpdateListener { a ->
            fabBarClipWidth = a.animatedValue as Int
            expandBar.invalidateOutline()
        }
        animator.start()
    }

    private fun refresh() {
        baiduMapViewModel.baiduMap.clear() // 清除之前的所有覆盖物

        // 绘制之前记录的点到点的线
        for (i in 0 until mPoints.size - 1) {
            baiduMapViewModel.baiduMap.addOverlay(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10)
                    .points(List.of<LatLng>(mPoints[i].gcj02, mPoints[i + 1].gcj02))
            )
        }
    }

    private fun drawLine(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        baiduMapViewModel.baiduMap.clear() // 清除之前的所有覆盖物

        // 绘制之前记录的点到点的线
        for (i in 0 until mPoints.size - 1) {
            baiduMapViewModel.baiduMap.addOverlay(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10)
                    .points(List.of<LatLng>(mPoints[i].gcj02, mPoints[i + 1].gcj02))
            )
        }

        baiduMapViewModel.baiduMap.addOverlay(
            PolylineOptions()
                .color(Color.argb(178, 0, 78, 255))
                .width(10)
                .points(List.of<LatLng>(start.gcj02, end.gcj02))
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

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
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

                val route = JSON.toJSONString(points)
                with(requireContext()) {
                    val routes = jsonHistoricalRoutes
                    val jsonArray: JSONArray = if (routes.isNotEmpty()) {
                        JSON.parseArray(routes)
                    } else {
                        JSONArray()
                    }
                    val historicalRoute = HistoricalRoute(name, mPoints)
                    jsonArray.add(historicalRoute)
                    jsonArray.toJSONString().also {
                        jsonHistoricalRoutes = it
                    }
                }

                Toast.makeText(requireContext(), "路线已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()

        return true
    }
}