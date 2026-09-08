package moe.fuqiuluo.portal.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Point
import android.graphics.Outline
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewOutlineProvider
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.MyLocationConfiguration
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
import moe.fuqiuluo.portal.databinding.FragmentHomeBinding
import moe.fuqiuluo.portal.ext.gcj02
import moe.fuqiuluo.portal.ext.mapType
import moe.fuqiuluo.portal.ext.rawHistoricalLocations
import moe.fuqiuluo.portal.ext.selectRoute
import moe.fuqiuluo.portal.ext.wgs84
import moe.fuqiuluo.portal.ui.viewmodel.BaiduMapViewModel
import moe.fuqiuluo.portal.ui.viewmodel.HomeViewModel
import java.math.BigDecimal
import java.util.List
import kotlin.random.Random

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    // 胶囊裁剪宽度（outline 圆角矩形右端，收起=圆/展开=胶囊）
    private var fabBarClipWidth = 0

    private val homeViewModel by viewModels<HomeViewModel>()
    private lateinit var mLocationClient: LocationClient
    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Fixed the issue that the Fab was opening incorrectly after switching back to Home for Fragments
        homeViewModel.mFabOpened = false

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
                    BaiduMap.MAP_TYPE_NORMAL -> R.id.map_type_normal
                    BaiduMap.MAP_TYPE_SATELLITE -> R.id.map_type_satellite
                    else -> R.id.map_type_normal
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
        baiduMapViewModel.mNotification?.let {
            mLocationClient.enableLocInForeground(1, it)
        }
        mLocationClient.start()


        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.map_type_normal -> {
                    binding.bmapView.map.mapType = BaiduMap.MAP_TYPE_NORMAL
                }

                R.id.map_type_satellite -> {
                    binding.bmapView.map.mapType = BaiduMap.MAP_TYPE_SATELLITE
                }

                else -> {
                    Log.e("HomeFragment", "Unknown location view mode: $checkedId")
                }
            }
            context?.mapType = binding.bmapView.map.mapType
        }

        binding.fab.setOnClickListener { view ->
            val expandBar = binding.fabExpandBar
            val subFabList = listOf(binding.fabMyLocation, binding.fabGoto, binding.fabAdd)

            if (!homeViewModel.mFabOpened) {
                homeViewModel.mFabOpened = true
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
                homeViewModel.mFabOpened = false
                view.isClickable = false

                view.animate()
                    .rotation(0f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                // outline 裁剪窗口向左收回：胶囊 → 圆形。功能按钮 INVISIBLE
                // （占位保持布局宽度不跳变；不接收触摸 → 点击透视到地图）
                subFabList.forEach {
                    it.visibility = View.INVISIBLE
                    it.isEnabled = false
                }
                animateFabBarClip(collapsedFabBarWidth(), 200, AccelerateInterpolator())
                expandBar.postDelayed({ view.isClickable = true }, 220)
            }
        }

        binding.fabMyLocation.setOnClickListener {
            baiduMapViewModel.baiduMap.locateMe()
        }

        binding.fabGoto.setOnClickListener {
            showInputCoordinatesDialog()
        }

        binding.fabAdd.setOnClickListener {
            if (!showAddLocationDialog()) {
                Toast.makeText(requireContext(), "选择位置异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.showRoute.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requireContext().selectRoute?.route?.let {
                    previewRoute(it)
                    // 选中路线后，将视角移动到起点
                    baiduMapViewModel.baiduMap.setMapStatus(
                        MapStatusUpdateFactory.newLatLng(it.first().gcj02)
                    )
                }
            } else {
                baiduMapViewModel.baiduMap.clear()
            }
        }

        return root
    }

    /** 三点展开栏入口：地图类型（触发原 RadioGroup 监听） */
    fun selectMapType(checkId: Int) {
        binding.mapTypeGroup.check(checkId)
    }

    /** 三点展开栏入口：当前地图类型 */
    fun currentMapTypeId(): Int = binding.mapTypeGroup.checkedRadioButtonId

    /** 三点展开栏入口：切换显示路线（触发原复选框监听） */
    fun toggleShowRoute() {
        binding.showRoute.isChecked = !binding.showRoute.isChecked
    }

    /** 三点展开栏入口：当前显示路线状态 */
    fun isShowRouteChecked(): Boolean = binding.showRoute.isChecked

    private fun previewRoute(points: kotlin.collections.List<Pair<Double, Double>>) {
        baiduMapViewModel.baiduMap.clear() // 清除之前的所有覆盖物

        // 绘制之前记录的点到点的线
        for (i in 0 until points.size - 1) {
            baiduMapViewModel.baiduMap.addOverlay(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10)
                    .points(List.of<LatLng>(points[i].gcj02, points[i + 1].gcj02))
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bmapView.onCreate(requireContext(), savedInstanceState)

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
        listOf(binding.fabMyLocation, binding.fabGoto, binding.fabAdd).forEach {
            it.visibility = View.INVISIBLE
            it.isEnabled = false
        }

        // 复位展开按钮姿态：旋转动画写入的 rotation 会被 View saved state
        // 记录（有 id 的 View），Fragment 重建后恢复成 90°——但胶囊 clip 是
        // 代码字段重新初始化为圆形，导致「胶囊已收回、按钮仍旋转」的矛盾态
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

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddLocationDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_location, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etLocationName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editAddress = dialogView.findViewById<TextInputEditText>(R.id.etLocationAddress)
        editAddress.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editAddress.error = "地址不能为空"
            }
        }
        val editLatLon = dialogView.findViewById<TextInputEditText>(R.id.etLocationLatLon)
        editLatLon.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editLatLon.error = "经纬度不能为空"
            } else {
                val latLonArray = it.toString().split(",")
                if (latLonArray.size != 2) {
                    editLatLon.error = "经纬度格式错误"
                } else {
                    val lon = latLonArray[0].trim().toDoubleOrNull()
                    val lat = latLonArray[1].trim().toDoubleOrNull()
                    if (!checkLatLon(lat, lon)) {
                        editLatLon.error = "经纬度格式错误"
                    }
                }
            }
        }

        with(baiduMapViewModel) {
            if (markedLoc == null) {
                currentLocation?.let {
                    showDetailView = false
                    mGeoCoder?.reverseGeoCode(ReverseGeoCodeOption().location(it.gcj02))
                    markedLoc = it
                }
                editName.setText("当前位置-" + System.currentTimeMillis())
            } else {
                editName.setText("标点位置-" + System.currentTimeMillis())
            }

            val lat = BigDecimal.valueOf(markedLoc?.first ?: return false)
            val lon = BigDecimal.valueOf(markedLoc?.second ?: return false)

            editAddress.setText(markName ?: "位置地址")
            editLatLon.setText("${lon.toPlainString()}, ${lat.toPlainString()}")

            val builder = MaterialAlertDialogBuilder(requireContext())
            builder.setTitle(null)
            builder
                .setCancelable(false)
                .setView(dialogView)
                .setPositiveButton("保存") { _, _ ->
                    val latLonArray = editLatLon.text.toString().split(",")
                    val newLon = latLonArray[0].trim().toDoubleOrNull()
                    val newLat = latLonArray[1].trim().toDoubleOrNull()
                    var name = editName.text?.toString()
                    val address = editAddress.text?.toString()
                    if (name.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (address.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "地址不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (!checkLatLon(newLat, newLon)) {
                        Toast.makeText(requireContext(), "经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
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

                    with(requireContext()) {
                        val locations = rawHistoricalLocations.toMutableSet()
                        var count = 0
                        while (!locations.addLocation(name!!, address, newLat!!, newLon!!)) {
                            name = "$name(${++count})"
                        }
                        rawHistoricalLocations = locations
                    }

                    Toast.makeText(requireContext(), "位置已保存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        return true
    }

    @SuppressLint("MissingInflatedId")
    private fun showInputCoordinatesDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_coordinates, null)

        val latitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val longitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLongitude)

        baiduMapViewModel.currentLocation?.let {
            latitudeEditText.setText(BigDecimal.valueOf(it.first).toPlainString())
            longitudeEditText.setText(BigDecimal.valueOf(it.second).toPlainString())
        }

        latitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                latitudeEditText.error = "纬度不能为空"
            } else {
                val lat = it.toString().toDoubleOrNull()
                if (lat == null || lat !in -90.0..90.0) {
                    latitudeEditText.error = "纬度格式错误"
                }
            }
        }

        longitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                longitudeEditText.error = "经度不能为空"
            } else {
                val lon = it.toString().toDoubleOrNull()
                if (lon == null || lon !in -180.0..180.0) {
                    longitudeEditText.error = "经度格式错误"
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("输入经纬度(WGS84)")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                kotlin.runCatching {
                    val latitude = latitudeEditText.text.toString()
                    val longitude = longitudeEditText.text.toString()

                    if (latitude.isNotEmpty() && longitude.isNotEmpty()) with(baiduMapViewModel) {
                        val lat = latitude.toDoubleOrNull()
                        val lon = longitude.toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            throw IllegalArgumentException("Invalid latitude or longitude")
                        }

                        this.markedLoc = lat to lon

                        markMap(true)

                        if (perspectiveState == MyLocationConfiguration.LocationMode.FOLLOWING) {
                            perspectiveState = MyLocationConfiguration.LocationMode.NORMAL
                        }

                        mGeoCoder?.reverseGeoCode(ReverseGeoCodeOption().location(LatLng(lat, lon)))
                    } else {
                        Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                            .show()
                    }
                }.onFailure {
                    Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

    override fun onResume() {
        super.onResume()

        if (_binding != null)
            binding.bmapView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()

        baiduMapViewModel.isExists = false
        if (mLocationClient.isStarted)
            mLocationClient.stop()
        if (_binding != null) {
            binding.bmapView.map.isMyLocationEnabled = false
        }
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
            // Switching fragments causes the _binding to be empty, and the method is inexplicably triggered,
            // which does not conform to the google's lifecycle diagram representation
            binding.bmapView.onSaveInstanceState(outState)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}