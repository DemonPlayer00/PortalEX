package moe.fuqiuluo.portalex.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import moe.fuqiuluo.xposed.utils.FakeLoc

/**
 * App 的**普通客户端位置源**：直接订阅 `LocationManager`（统一架构迁移步骤③）。
 *
 * ## 为什么不用百度定位 SDK（也不该用）
 *
 * 百度 SDK 是一个黑盒融合引擎（自己的进程、自己的网络定位），它给的点与我们注入的点
 * **不是同一个来源** ⇒ 地图上显示的位置永远无法作为"注入是否生效"的证据，而且它随时可能
 * 用真实定位把视角拉回去。迁移后 App 与任何第三方应用走同一条路：
 * **向框架订阅 → 收到的就是被注入链改写过的帧**（框架侧 `LocationServiceHook` 把同一 tick
 * 的同一帧投给所有注册），于是"地图上的点"就是"应用真正收到的点"。
 *
 * 百度 SDK 只保留"取城市名"这类与位置显示无关的用途（见 Home/RouteEdit 的注释）。
 *
 * ## 帧的成色
 *
 * 注入帧 = 世界坐标 + **保护性抖动**（幅度 ≈ 配置精度，默认 25m）。所以它与模块回读的
 * `FakeLoc.latitude/longitude`（未抖动的世界点）**必然有小差** —— 那不是误差，是设计。
 * 判据要的是"与第三方客户端**逐点相同**"，而同一 tick 的注册收到的是同一个对象。
 *
 * ## 多页共享
 *
 * 用一个监听器扇出给多个订阅者（Home / RouteEdit 同时活着时不会互相抢注：
 * `requestLocationUpdates` 是按 listener 记账的，谁 `stop` 谁就把别人的也停了）。
 */
object PortalLocationClient {

    private const val TAG = "PortalLocationClient"

    /** 最小订阅间隔：设置页允许 1ms，那等于让框架每毫秒回调一次 */
    private const val MIN_INTERVAL_MS = 100L

    /** 普通客户端视角的一帧（WGS84，与注入帧同一份数据） */
    data class Fix(
        val lat: Double,
        val lon: Double,
        val bearing: Float,
        val speed: Float,
        val accuracy: Float,
        val provider: String,
        val timeMillis: Long,
        val elapsedRealtimeNanos: Long,
    )

    private val subscribers = LinkedHashMap<Any, (Fix) -> Unit>()
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var intervalMs: Long = 0L

    @Volatile
    private var lastFix: Fix? = null

    @Volatile
    private var frameCount: Long = 0

    val isSubscribed: Boolean get() = listener != null

    fun lastFix(): Fix? = lastFix

    fun frameCount(): Long = frameCount

    /**
     * 订阅（幂等：同一 owner 重复订阅只替换回调）。
     *
     * @param intervalMs 期望出帧间隔（毫秒）。用 App 的「上报间隔」设置 —— 与模块时钟
     *   出帧节奏同一口径，客户端看到的就是模块发的那一份。
     */
    @SuppressLint("MissingPermission")
    fun subscribe(owner: Any, context: Context, intervalMs: Long, onFix: (Fix) -> Unit): Boolean {
        subscribers[owner] = onFix
        if (listener != null) return true

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.w(TAG, "定位服务不可用，位置订阅未建立")
            return false
        }
        val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = deliver(location)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("平台已废弃，但仍属接口的一部分")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val ok = runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f, l, Looper.getMainLooper())
        }.onFailure {
            // 权限被回收/服务异常：如实说"没订阅上"，不要静默假装在收帧
            Log.e(TAG, "requestLocationUpdates 失败：${it.message}", it)
        }.isSuccess
        if (!ok) {
            subscribers.remove(owner)
            return false
        }
        manager = lm
        listener = l
        this.intervalMs = interval
        Log.i(TAG, "已订阅 ${LocationManager.GPS_PROVIDER}（间隔 ${interval}ms）—— 普通客户端视角")
        return true
    }

    /** 退订（owner 粒度；最后一个退订时才真正移除框架注册） */
    fun unsubscribe(owner: Any) {
        subscribers.remove(owner)
        if (subscribers.isNotEmpty()) return
        stop()
    }

    /** 撤掉框架注册并清空诊断数据（会话/页面收尾用） */
    fun stop() {
        val l = listener
        listener = null
        subscribers.clear()
        if (l != null) {
            runCatching { manager?.removeUpdates(l) }
                .onFailure { Log.w(TAG, "removeUpdates 失败：${it.message}") }
            Log.i(TAG, "已退订 LocationManager（本次共收 $frameCount 帧）")
        }
        manager = null
        lastFix = null
        frameCount = 0L
    }

    private fun deliver(location: Location) {
        val fix = Fix(
            lat = location.latitude,
            lon = location.longitude,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            speed = if (location.hasSpeed()) location.speed else 0f,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
            provider = location.provider ?: "?",
            timeMillis = location.time,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        )
        lastFix = fix
        frameCount += 1
        // 回调在主线程（Looper.getMainLooper()），订阅者直接改 UI/地图
        subscribers.values.toList().forEach { it(fix) }
    }

    /** 诊断一行（Test 页）：把"App 收到的点"与"模块的权威世界点"摆在一起看 */
    fun statusLine(): String {
        val f = lastFix
        if (!isSubscribed) return "未订阅（地图页未打开？）"
        if (f == null) return "已订阅（间隔 ${intervalMs}ms），尚未收到帧"
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - f.elapsedRealtimeNanos) / 1_000_000
        // 与模块回读的世界点比较：差值 = 保护性抖动（幅度 ≈ 精度），不是误差
        val dy = (FakeLoc.latitude - f.lat) * 111_320.0
        val dx = (FakeLoc.longitude - f.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(f.lat))
        return "已收 %d 帧（间隔 %dms，最近 %dms 前）wgs84=%.6f,%.6f 与模块回读差 %.1fm".format(
            frameCount, intervalMs, ageMs, f.lat, f.lon, Math.hypot(dx, dy)
        )
    }
}
