package moe.fuqiuluo.portalex.ui.viewmodel

import android.app.Notification
import androidx.lifecycle.ViewModel
import com.baidu.location.LocationClient
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MyLocationConfiguration
import com.baidu.mapapi.map.MyLocationData
import moe.fuqiuluo.portalex.R
import com.baidu.mapapi.model.LatLng
import moe.fuqiuluo.portalex.ext.Loc4j
import moe.fuqiuluo.portalex.service.PortalLocationClient
import com.baidu.mapapi.search.geocode.GeoCoder
import moe.fuqiuluo.portalex.bdmap.setMapConfig

class BaiduMapViewModel: ViewModel() {
    var isExists = false
    lateinit var baiduMap: BaiduMap
    lateinit var mLocationClient: LocationClient

    /**
     * Current location
     * WGS84
     */
    var currentLocation: Pair<Double, Double>? = null

    /** 首页地图相机（GCJ02）：页面销毁后仍由 Activity 级 ViewModel 保存。 */
    var mapCenter: LatLng? = null
    var mapZoom: Float = 19f

    /** 路线页选点后请求首页地图下一次可见时居中（WGS84）。 */
    var pendingCenterWgs: Pair<Double, Double>? = null

    fun requestCenter(lat: Double, lon: Double) {
        pendingCenterWgs = lat to lon
    }

    var markName: String? = null

    /**
     * Marked location
     * WGS84
     * first => latitude
     * second => longitude
     */
    var markedLoc: Pair<Double, Double>? = null
    var showDetailView = false

    /* Notification */
    var mNotification: Notification? = null

    /**
     * 2024.10.10: Cancels the default follow perspective
     */
    var perspectiveState = MyLocationConfiguration.LocationMode.NORMAL
        set(value) {
            field = value
            baiduMap.setMapConfig(value, null)
        }

    val mMapIndicator: BitmapDescriptor? by lazy {
        BitmapDescriptorFactory.fromResource(R.drawable.icon_selected_location_16)
    }

    var mGeoCoder: GeoCoder? = null

    /**
     * 用**普通客户端**收到的一帧（`LocationManager` 的注入帧，WGS84）更新"我的位置"。
     *
     * 为什么集中在这里：Home 与 RouteEdit 两页要同一套换算（WGS84 → 地图的 GCJ02），
     * 各自写一遍必然漂移 —— 同一个坐标在两页差几十米是这类项目的老毛病。
     *
     * ⚠️ 这里**不再**接受百度 SDK 的定位结果：黑盒融合引擎与注入链不是同一来源，
     * 拿它画点就等于让地图说谎（见 [moe.fuqiuluo.portalex.service.PortalLocationClient]）。
     */
    fun applyFix(lat: Double, lon: Double, bearing: Float, accuracy: Float) {
        val gcj = Loc4j.wgs2gcj(lat, lon)
        val data = MyLocationData.Builder()
            .accuracy(accuracy)
            .direction(bearing)
            .latitude(gcj.first)
            .longitude(gcj.second)
            .build()
        currentLocation = lat to lon
        baiduMap.setMyLocationData(data)
    } 

    /** 把地图中心移到当前定位点；没有可用帧时返回 false。 */
    fun locateToCurrent(): Boolean {
        val wgs = currentLocation ?: PortalLocationClient.lastFix()?.let { it.lat to it.lon } ?: return false
        val gcj = Loc4j.wgs2gcj(wgs.first, wgs.second)
        baiduMap.setMapStatus(
            com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLng(LatLng(gcj.first, gcj.second))
        )
        return true
    }
}