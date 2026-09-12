@file:Suppress("PrivateApi", "unused")
package moe.fuqiuluo.xposed.hooks.camera

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import moe.fuqiuluo.xposed.camera.CameraMockCodec
import moe.fuqiuluo.xposed.camera.CameraMockManifest
import moe.fuqiuluo.xposed.camera.CameraMockProtocol
import moe.fuqiuluo.xposed.camera.CameraMockSelection
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.PortalDiag
import java.util.concurrent.Executors

/**
 * **摄像头模拟的配置客户端**（跑在被注入的**目标应用进程**里）。
 *
 * 与 PortalEX 的通信只走一条路：`ContentResolver.call/openFile` 到我们自己的 provider。
 * 为什么不用现成的 `sendExtraCommand("portal", …)` 命令通道：那条通道被刻意限死在
 * PortalEX 的 uid 与 SYSTEM_UID 上（`BinderUtils.moduleOwnerUid`），普通应用进程连
 * 握手都过不去；而 provider 是"任何进程都能按授权名访问、门禁在服务端"的形态，
 * 正好适合"跨到别人的进程"这件事。
 *
 * 四条纪律：
 *  1. **按需拉取**：只在真的要开摄像头时问，且带 [CACHE_TTL_MS] 缓存 —— provider 被访问时
 *     系统会顺带把 PortalEX 进程拉起来，绝不能变成每次启动应用都冷启一次 app；
 *  2. **不阻塞主线程**：冷缓存 + 主线程 ⇒ 本次**不拦截**，只丢一个后台预热（见 [warmUpAsync]）。
 *     冷启 PortalEX（地图 SDK 初始化）可能要几百毫秒到几秒，拿这个去堵别人的主线程
 *     换来的是一次 ANR —— 宁可第一次开摄像头不生效；
 *  3. **失败即静默降级**：拉不到清单就沿用缓存；被明确拒绝（denied）立刻退回"不拦截"；
 *  4. **每次请求必经的钩子只读缓存**（`addTarget` 那条路一次跨进程调用都不做）。
 */
internal object CameraMockClient {

    private const val CACHE_TTL_MS = 3_000L

    private val lock = Any()

    private val fetcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PortalEX-CameraMockFetch").apply { isDaemon = true }
    }

    @Volatile
    private var cachedManifest: CameraMockManifest = CameraMockManifest.DISABLED

    @Volatile
    private var cachedPosition: Pair<Double, Double>? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var cachedAt = 0L

    /** 服务端明确拒绝（不在目标清单里）：本进程不再询问，也不再拦截 */
    @Volatile
    private var refused = false

    @Volatile
    private var reportedUnavailable = false

    private val appContext: Context? get() = CameraMockEnv.appContext()

    /** 有没有 Application（没有就什么都做不了：拿不到 ContentResolver） */
    fun hasContext(): Boolean = appContext != null

    /** 缓存是否新鲜（新鲜 = 调用方可以立刻做决定，不必跨进程） */
    fun isWarm(): Boolean = loaded && SystemClock.uptimeMillis() - cachedAt < CACHE_TTL_MS

    /**
     * 后台预热：在 `CameraManager.openCamera` 被调用的那一刻发起。
     *
     * 这是"不阻塞主线程"与"第一次开摄像头就生效"之间的折中：从 openCamera 到
     * createCaptureSession（以及应用真正开始消费画面）通常有几百毫秒，
     * 足够把清单与位置取回来缓存上 —— 而这一段时间**不占用**应用的任何线程。
     */
    fun warmUpAsync() {
        if (refused || isWarm()) return
        runCatching {
            fetcher.execute {
                runCatching {
                    manifest(allowFetch = true)
                    position(allowFetch = true)
                }
            }
        }
    }

    /**
     * 当前清单。`allowFetch=false` 时**只读缓存**（供每次相机请求都会经过的钩子使用）。
     */
    fun manifest(allowFetch: Boolean = true): CameraMockManifest {
        if (refused) return CameraMockManifest.DISABLED
        if (isWarm()) return cachedManifest
        if (!allowFetch) return cachedManifest

        synchronized(lock) {
            if (isWarm()) return cachedManifest
            val reply = call(CameraMockProtocol.Method.MANIFEST, null)
            val status = reply?.getString(CameraMockProtocol.Key.STATUS)
            cachedAt = SystemClock.uptimeMillis()
            loaded = true
            if (status != null && status.startsWith("denied")) {
                refused = true
                cachedManifest = CameraMockManifest.DISABLED
                if (FakeLoc.enableDebugLog) Logger.info("摄像头模拟：provider 拒绝（$status），本进程不再拦截")
                return cachedManifest
            }
            val json = reply?.getString(CameraMockProtocol.Key.MANIFEST_JSON)
            if (json == null) {
                if (!reportedUnavailable) {
                    reportedUnavailable = true
                    PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK)
                    if (FakeLoc.enableDebugLog) Logger.info("摄像头模拟：拉不到清单（沿用缓存）")
                }
                return cachedManifest
            }
            val decoded = CameraMockCodec.decode(json)
            if (decoded == null) {
                PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK)
                if (FakeLoc.enableDebugLog) Logger.info("摄像头模拟：清单解码失败（沿用缓存）")
                return cachedManifest
            }
            cachedManifest = decoded
            return cachedManifest
        }
    }

    /** 本包是否应该被拦截：开关打开 + 目标清单允许 */
    fun enabledFor(packageName: String?): Boolean {
        val m = manifest()
        if (!m.enabled) return false
        if (m.targets.isEmpty()) return true
        if (packageName == null) return false
        return m.targets.any { it == packageName }
    }

    /**
     * 当前表象位置（wgs84）。取不到返回 null —— 调用方（[CameraMockSelector.select]）
     * 会把"位置未知"当成"不比较距离、在全部匹配素材里随机取"，而不是当成 0,0。
     */
    fun position(allowFetch: Boolean = true): Pair<Double, Double>? {
        if (isWarm()) return cachedPosition
        if (!allowFetch) return cachedPosition
        val reply = call(CameraMockProtocol.Method.POSITION, null) ?: return cachedPosition
        val position = if (reply.getBoolean(CameraMockProtocol.Key.HAS_POSITION, false)) {
            val lat = reply.getDouble(CameraMockProtocol.Key.LAT, Double.NaN)
            val lon = reply.getDouble(CameraMockProtocol.Key.LON, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) null else lat to lon
        } else null
        cachedPosition = position
        return position
    }

    /** 回传"这次拦到了什么"，让用户在 Test 页就能看见结果（真机日志常常拿不到） */
    fun report(packageName: String?, selection: CameraMockSelection?, status: String) {
        val extras = Bundle().apply {
            putString(CameraMockProtocol.Key.PACKAGE, packageName)
            putString(CameraMockProtocol.Key.STATUS, status)
            if (selection != null) {
                putString(CameraMockProtocol.Key.POINT_NAME, selection.pointName)
                putString(CameraMockProtocol.Key.VIDEO_NAME, selection.videoName)
                putString(CameraMockProtocol.Key.VIDEO_ID, selection.videoId)
                putLong(CameraMockProtocol.Key.START_MS, selection.startMs)
                putDouble(CameraMockProtocol.Key.DISTANCE_M, selection.distanceM)
            }
        }
        // 上报不该挡着摄像头：丢到预热线程上去做（失败也只是少一条观测记录）
        runCatching { fetcher.execute { call(CameraMockProtocol.Method.REPORT, extras) } }
    }

    /** 视频 URI（provider 侧 `openFile` 用它定位视频并做门禁校验） */
    fun videoUri(videoId: String): Uri =
        Uri.parse("content://${CameraMockProtocol.AUTHORITY}/${CameraMockProtocol.PATH_VIDEO_PREFIX}$videoId")
            .buildUpon()
            .appendQueryParameter("uid", Process.myUid().toString())
            .appendQueryParameter("pkg", appContext?.packageName ?: "")
            .build()

    private fun call(method: String, extras: Bundle?): Bundle? {
        val ctx = appContext ?: return null
        val payload = (extras ?: Bundle()).apply {
            putString(CameraMockProtocol.Key.PACKAGE, ctx.packageName)
            // 自报 uid：服务端拿它和内核给出的 Binder.getCallingUid() 比对 ——
            // 只声明包名是可以伪造的，uid 不能（这条门禁的已知边界见 provider 注释）
            putInt("uid", Process.myUid())
        }
        return runCatching {
            ctx.contentResolver.call(
                Uri.parse("content://${CameraMockProtocol.AUTHORITY}/${CameraMockProtocol.PATH_MANIFEST}"),
                method,
                null,
                payload
            )
        }.onFailure {
            if (FakeLoc.enableDebugLog) Logger.debug("摄像头模拟：provider 调用失败 ${it.message}")
        }.getOrNull()
    }
}
