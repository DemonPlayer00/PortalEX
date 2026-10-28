package moe.fuqiuluo.portalex.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
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
    private val requestedProviders = mutableListOf<String>()

    @Volatile
    private var lastFix: Fix? = null

    @Volatile
    private var frameCount: Long = 0L

    /** 最近一帧落盘，页面/进程重建后仍能显示最后稳定坐标（室内无新 fix 时不至于空点）。 */
    private const val CACHE_PREFS = "portal_location_cache"
    private const val CACHE_TIME = "last_fix_time"
    private var cacheContext: Context? = null
    private var lastCachedAt = 0L

    val isSubscribed: Boolean get() = listener != null

    fun lastFix(): Fix? = lastFix

    fun frameCount(): Long = frameCount

    private fun loadCachedFix(ctx: Context): Fix? = runCatching {
        val sp = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val lat = sp.getFloat("lat", Float.NaN)
        val lon = sp.getFloat("lon", Float.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return null
        Fix(
            lat = lat.toDouble(), lon = lon.toDouble(),
            bearing = sp.getFloat("bearing", 0f),
            speed = sp.getFloat("speed", 0f),
            accuracy = sp.getFloat("accuracy", 0f),
            provider = sp.getString("provider", "cache").orEmpty(),
            timeMillis = sp.getLong("time", 0L),
            elapsedRealtimeNanos = sp.getLong("elapsed", 0L),
        )
    }.getOrNull()

    private fun saveCachedFix(ctx: Context, fix: Fix) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCachedAt < 2_000L && fix.timeMillis == 0L) return
        lastCachedAt = now
        ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("lat", fix.lat.toFloat())
            .putFloat("lon", fix.lon.toFloat())
            .putFloat("bearing", fix.bearing)
            .putFloat("speed", fix.speed)
            .putFloat("accuracy", fix.accuracy)
            .putString("provider", fix.provider)
            .putLong("time", fix.timeMillis)
            .putLong("elapsed", fix.elapsedRealtimeNanos)
            .apply()
    }

    /**
     * 订阅（幂等：同一 owner 重复订阅只替换回调）。
     *
     * @param intervalMs 期望出帧间隔（毫秒）。用 App 的「上报间隔」设置 —— 与模块时钟
     *   出帧节奏同一口径，客户端看到的就是模块发的那一份。
     */
    @SuppressLint("MissingPermission")
    fun subscribe(owner: Any, context: Context, intervalMs: Long, onFix: (Fix) -> Unit): Boolean {
        subscribers[owner] = onFix
        cacheContext = context.applicationContext
        if (lastFix == null) lastFix = loadCachedFix(context)
        if (listener != null) {
            // 页面重新订阅时 listener 仍在：把最近一帧立即补给它，
            // 否则暂停/静止时地图要等下一次定位回调，看起来像“没有坐标点”。
            lastFix?.let { onFix(it) }
            return true
        }
        // 新一次订阅 = 新一次统计；但保留/加载最近坐标，室内无新帧时仍能显示稳定点。
        if (lastFix == null) lastFix = loadCachedFix(context)
        frameCount = 0L

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

        // 只订阅 GPS 是“室内指针消失”的直接原因：GPS 在室内拿不到 fix，但平台 fused
        // （Wi-Fi/基站/GPS 融合）通常还能出点。API 31+ 优先同时订阅 fused + GPS；
        // 室外 GPS 精度更高，室内由 fused 兜底。
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val fusedEnabled = runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }
                    .getOrDefault(false)
                if (fusedEnabled) add(LocationManager.FUSED_PROVIDER)
            }
            if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.GPS_PROVIDER)
            }
            // 没有 fused 的机型（API < 31 或厂商裁剪）由网络定位兜底，
            // 室内/弱 GPS 场景仍然能显示坐标点。
            if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            // 服务刚启动 / 查询失败时仍尝试 GPS，避免完全不订阅
            if (isEmpty()) add(LocationManager.GPS_PROVIDER)
        }

        var successCount = 0
        providers.forEach { provider ->
            runCatching {
                lm.requestLocationUpdates(provider, interval, 0f, l, Looper.getMainLooper())
                true
            }.onSuccess {
                successCount += 1
                requestedProviders += provider
            }.onFailure {
                // 权限被回收/服务异常：如实记录；还有其它 provider 就继续
                Log.e(TAG, "requestLocationUpdates($provider) 失败：${it.message}", it)
            }
        }
        Log.i(TAG, "subscribe providers=" + requestedProviders + " success=" + successCount)
        if (successCount == 0) {
            subscribers.remove(owner)
            return false
        }

        // 先给地图一个最近点：刚打开页面时不必等下一次 GPS/fused 回调。
        providers.forEach { provider ->
            if (lastFix != null) return@forEach
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.let { deliver(it) }
        }

        manager = lm
        listener = l
        this.intervalMs = interval
        // API 30+ 追加「一次性取位轮询」：本机实测持续订阅（requestLocationUpdates）在
        // 模拟会话期间收不到任何帧，而 getCurrentLocation 能稳定拿到 —— 两个通道并存，
        // 谁先到用谁（都是标准系统 API）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startOneShotPolling(lm, interval)
        }
        Log.i(TAG, "已订阅 " + providers.joinToString("+") + "（间隔 " + interval + "ms）—— 普通客户端视角")
        return true
    }

    private var oneShotHandler: android.os.Handler? = null
    private var oneShotExecutor: java.util.concurrent.ExecutorService? = null

    /** 用 getCurrentLocation 周期性取位（标准 API；持续订阅不可用时的主力通道）。 */
    private fun startOneShotPolling(lm: LocationManager, interval: Long) {
        stopOneShotPolling()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        oneShotHandler = handler
        oneShotExecutor = executor
        val tick = object : Runnable {
            override fun run() {
                if (subscribers.isEmpty()) return
                runCatching {
                    @Suppress("MissingPermission")
                    lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, executor) { location ->
                        if (location != null) deliver(location)
                    }
                }
                handler.postDelayed(this, interval.coerceAtLeast(MIN_INTERVAL_MS))
            }
        }
        handler.post(tick)
    }

    private fun stopOneShotPolling() {
        oneShotHandler?.removeCallbacksAndMessages(null)
        oneShotHandler = null
        oneShotExecutor?.shutdownNow()
        oneShotExecutor = null
    }

    /** 退订（owner 粒度；最后一个退订时才真正移除框架注册） */
    fun unsubscribe(owner: Any) {
        subscribers.remove(owner)
        if (subscribers.isNotEmpty()) return
        stop()
    }

    /**
     * 撤掉框架注册（页面收尾用）。
     *
     * **保留**最近一帧与累计帧数：诊断页与地图页不是同一个页面（地图页一销毁订阅就撤了），
     * 若在这里清零，"刚才到底收没收到帧"就永远查不到 —— 那正是这类问题最难取证的地方。
     */
    fun stop() {
        val l = listener
        listener = null
        subscribers.clear()
        if (l != null) {
            runCatching { manager?.removeUpdates(l) }
                .onFailure { Log.w(TAG, "removeUpdates 失败：${it.message}") }
            Log.i(TAG, "已退订 LocationManager（本次共收 $frameCount 帧）")
        }
        stopOneShotPolling()
        manager = null
        requestedProviders.clear()
    }

    private fun deliver(location: Location) {
        // GPS 与 fused 同时订阅时，回调可能交错；只让更晚的帧推进地图，
        // 避免旧的 GPS 帧把刚到的 fused 室内点覆盖回去。
        val previous = lastFix
        if (previous != null && location.elapsedRealtimeNanos + 5_000_000L < previous.elapsedRealtimeNanos) {
            return
        }
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
        cacheContext?.let { saveCachedFix(it, fix) }
        frameCount += 1
        // 逐帧落盘（仅在调试日志打开时；见 FrameRecorder）：配速尖峰一类问题只能靠
        // "逐帧时间序列"定位，而这份序列必须来自客户端真正收到的那一份帧
        FrameRecorder.record(fix)
        Log.i(TAG, "fix provider=" + fix.provider + " " + fix.lat + "," + fix.lon)
        // 回调在主线程（Looper.getMainLooper()），订阅者直接改 UI/地图
        subscribers.values.toList().forEach { it(fix) }
    }


    /**
     * 调试用：**自持一个订阅**并开始逐帧记录（`debug` 打开时由 [moe.fuqiuluo.portalex.Portal] 调用）。
     *
     * 为什么不用"等某个页面订阅时顺手记"：自动播放经常在**页面没打开**的状态下进行
     * （悬浮窗 + 后台），而那时客户端根本不收帧 —— 恰好把要查的那一段漏掉。
     * 这里用一个固定 owner 自己订阅，与页面生命周期解耦。
     */
    fun startDebugRecording(context: Context) {
        FrameRecorder.start(context)
        subscribe(RECORDER_OWNER, context, MIN_INTERVAL_MS) { /* 记录发生在 deliver 里 */ }
    }

    /** 记录器自己的订阅 owner（与页面 owner 不冲突，页面进退不会撤掉它） */
    private val RECORDER_OWNER = Any()

    /** 诊断一行（Test 页）：把"App 收到的点"与"模块的权威世界点"摆在一起看 */
    fun statusLine(): String {
        val f = lastFix
        if (!isSubscribed) {
            return if (f == null) "未订阅（地图页未打开过）"
            else "已退订，上次订阅共收 %d 帧（最后 %.6f,%.6f @ %dms 前）".format(
                frameCount, f.lat, f.lon,
                (android.os.SystemClock.elapsedRealtimeNanos() - f.elapsedRealtimeNanos) / 1_000_000
            )
        }
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
