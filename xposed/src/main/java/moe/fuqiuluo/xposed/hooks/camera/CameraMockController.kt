@file:Suppress("PrivateApi")
package moe.fuqiuluo.xposed.hooks.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import moe.fuqiuluo.xposed.camera.CameraMockProtocol
import moe.fuqiuluo.xposed.camera.CameraMockSelection
import moe.fuqiuluo.xposed.camera.CameraMockSelector
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.PortalDiag
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * **摄像头模拟的进程内总控**：选片、排水面、喂帧、记账。
 *
 * 拦截的整体形态（为什么这么做，见 [Camera2MockHook]）：把应用交给相机的输出面**换成
 * 我们自己的"排水面"**，让真实相机照常跑但画面只进我们的桶（随即丢弃）；应用原来那几个
 * 面则由我们用 [CameraVideoPlayer] 直接渲染——它们本来就是"图形缓冲的生产者"，
 * 解码器/GL 能往里写，应用（预览、录像、编码）完全无感。
 *
 * 这样做的好处是**不需要伪造任何系统对象**：应用拿到的 session 是真的，只是它的输出
 * 被偷偷改了；一旦哪一环失败，我们只要不做替换，应用就照旧看真实摄像头（fail-inert）。
 *
 * 已知边界（写明，别当成 bug）：
 *  · 只喂"能当图形缓冲生产者"的面（SurfaceTexture/SurfaceView/MediaRecorder/MediaCodec 输入）；
 *    `ImageReader`（YUV 分析流）拿不到数据 —— 分析类功能在模拟期间会是空流；
 *  · 每个输出面各自起一条解码线程（预览 + 录像 = 两路解码）。先保证正确，再谈复用；
 *  · 物理摄像头**仍然被真实打开**（指示灯会亮）：我们只改画面去哪，不阻止采集。
 */
internal object CameraMockController {

    /** 应用原始输出面（= 我们要喂的面）。被 [Camera2MockHook] 用于 addTarget 重定向判定 */
    private val appSurfaces: MutableSet<Surface> =
        Collections.newSetFromMap(ConcurrentHashMap<Surface, Boolean>())

    private val players = ConcurrentHashMap<Surface, CameraVideoPlayer>()

    @Volatile
    private var selection: CameraMockSelection? = null

    @Volatile
    private var dummy: Surface? = null

    @Volatile
    private var drainReader: ImageReader? = null

    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null

    /** 本次会话是否已经在替换画面（用于诊断与 addTarget 判定） */
    @Volatile
    var intercepting: Boolean = false
        private set

    fun isAppSurface(surface: Surface): Boolean = appSurfaces.contains(surface)

    fun currentDummy(): Surface? = dummy

    /**
     * 准备一次拦截：清单允许 + 有命中素材 → 返回 true，并把应用的面登记下来。
     * 任何一环不满足都返回 false（应用照旧看真实摄像头）。
     */
    fun prepare(outputs: List<Surface>, packageName: String?): Boolean {
        if (outputs.isEmpty()) return false
        if (!CameraMockClient.hasContext()) return false
        // 冷缓存 + 主线程 ⇒ 不拦截（只在后台预热）。理由见 CameraMockClient 纪律 2：
        // 冷启 PortalEX 可能要几秒，拿它去堵别人的主线程换来的是一次 ANR。
        if (!CameraMockClient.isWarm() && Looper.myLooper() == Looper.getMainLooper()) {
            CameraMockClient.warmUpAsync()
            if (FakeLoc.enableDebugLog) {
                Logger.info("摄像头模拟：配置缓存未热且当前在主线程，本次不拦截（已后台预热）")
            }
            return false
        }
        if (!CameraMockClient.enabledFor(packageName)) return false
        val manifest = CameraMockClient.manifest()
        val position = CameraMockClient.position()
        val picked = CameraMockSelector.select(
            manifest, position?.first, position?.second,
            nowMs = System.currentTimeMillis(),
        ) ?: run {
            if (FakeLoc.enableDebugLog) Logger.info("摄像头模拟：开关开着但没有命中素材，本次不拦截")
            return false
        }
        if (dummySurface() == null) {
            PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK)
            Logger.warn("摄像头模拟：排水面创建失败，本次不拦截")
            return false
        }

        val previous = selection
        val samePick = previous != null &&
                previous.videoId == picked.videoId &&
                previous.startMs == picked.startMs
        // 同一段素材（同一起播偏移）且面还在 ⇒ 继续播：切前后摄/加录像面这类"重配会话"
        // 常常复用同一个预览面，全部推倒重来会让同一个 Surface 上挂两套 EGL（画面打架/报错）
        if (!samePick) stopFeeding()
        val next = outputs.toSet()
        players.keys.toList().forEach { surface ->
            if (surface !in next) players.remove(surface)?.stop()
        }

        appSurfaces.clear()
        appSurfaces.addAll(outputs)
        selection = picked
        intercepting = true
        CameraMockClient.report(
            packageName,
            picked,
            "picked（${position?.let { "距离 ${picked.distanceM.toInt()}m" } ?: "位置未知"}）"
        )
        if (FakeLoc.enableDebugLog) {
            Logger.info(
                "摄像头模拟：命中 ${picked.pointName}/${picked.videoName}，起点 ${picked.startMs / 1000}s，" +
                        "输出面 ${outputs.size} 个"
            )
        }
        return true
    }

    /** 会话配置成功后开始喂帧（每个应用输出面一路解码） */
    fun startFeeding(packageName: String?) {
        val picked = selection ?: return
        var started = 0
        appSurfaces.forEach { surface ->
            if (players.containsKey(surface)) return@forEach
            if (surface == dummy || !surface.isValid) return@forEach
            val player = startPlayer(surface, picked, packageName) ?: return@forEach
            players[surface] = player
            started++
        }
        if (started == 0) {
            PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK)
            Logger.warn("摄像头模拟：没有任何输出面可以喂帧（画面将保持黑/静止）")
            CameraMockClient.report(packageName, picked, "no-surface")
        }
    }

    private fun startPlayer(
        surface: Surface,
        picked: CameraMockSelection,
        packageName: String?
    ): CameraVideoPlayer? {
        val source = ProviderVideoSource.open(CameraMockClient.videoUri(picked.videoId)) ?: run {
            PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK)
            Logger.warn("摄像头模拟：取视频失败（${picked.videoName}）")
            CameraMockClient.report(packageName, picked, "open-failed")
            return null
        }
        val player = CameraVideoPlayer(
            source = source,
            target = surface,
            startMs = picked.startMs,
            loop = true,
            listener = object : CameraVideoPlayer.Listener {
                override fun onStarted(durationMs: Long) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.info("摄像头模拟：开始播放 ${picked.videoName}（时长 ${durationMs}ms）")
                    }
                }

                override fun onFirstFrame() {
                    CameraMockClient.report(packageName, picked, "first-frame")
                }

                override fun onFinished() = Unit

                override fun onError(t: Throwable) {
                    // 一个面失败不影响别的面；记账后由播放器自行退出
                    PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK, t)
                    Logger.warn("摄像头模拟：播放失败（${t.message}）")
                    CameraMockClient.report(packageName, picked, "play-error:${t.javaClass.simpleName}")
                }
            }
        )
        return runCatching {
            player.start()
            player
        }.onFailure {
            PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK, it)
            runCatching { source.close() }
        }.getOrNull()
    }

    /** 停掉全部喂帧（会话关闭 / 设备关闭 / 重新配置时调用） */
    fun stopFeeding() {
        players.values.forEach { runCatching { it.stop() } }
        players.clear()
        intercepting = false
    }

    /** 彻底释放（设备关闭）：连应用面登记一起清掉，避免下次误判 */
    fun release() {
        stopFeeding()
        appSurfaces.clear()
        selection = null
    }

    /**
     * 真实相机的"排水面"：一个没人要看的 [ImageReader]，我们主动把画面取走丢掉。
     *
     * 为什么必须有它：应用请求的目标面被我们换掉后，会话总得有个合法输出（`setRepeatingRequest`
     * 的每个 target 都必须在会话里），否则应用侧会拿到 `IllegalArgumentException`。
     * 尺寸取 640x480 + YUV_420_888 —— 这是所有设备的保底组合，最不容易在配置期被拒。
     */
    private fun dummySurface(): Surface? {
        dummy?.let { if (it.isValid) return it }
        synchronized(this) {
            dummy?.let { if (it.isValid) return it }
            return runCatching {
                val thread = readerThread ?: HandlerThread("PortalEX-CameraMockDrain").also {
                    it.start()
                    readerThread = it
                    readerHandler = Handler(it.looper)
                }
                val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
                // 必须持续取走：不取会让生产者阻塞在缓冲队列上，真实相机会卡住
                reader.setOnImageAvailableListener({ r ->
                    runCatching { r.acquireLatestImage()?.close() }
                }, readerHandler)
                drainReader = reader
                dummy = reader.surface
                dummy
            }.onFailure {
                PortalDiag.fail(PortalDiag.Area.CAMERA_MOCK, it)
                Logger.error("摄像头模拟：创建排水面失败", it)
            }.getOrNull()
        }
    }

    /** 诊断单行（Test 页展示用） */
    fun statusLine(): String {
        val picked = selection
        return if (picked == null) {
            "idle"
        } else {
            "intercepting=${intercepting} players=${players.size} video=${picked.videoName} start=${picked.startMs / 1000}s"
        }
    }

    /** 供报告回传的键名（与 provider 侧一致，避免两边手打字符串） */
    internal val reportKeys = CameraMockProtocol.Key
}
