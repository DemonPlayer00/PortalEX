package moe.fuqiuluo.xposed.camera

import java.io.File
import kotlin.random.Random

/**
 * system_server 侧的 UUID 视频库。
 *
 * 设计边界：
 *  · 本对象只在 system_server 里使用；DB 与视频都在系统目录；
 *  · PortalEX app 不再保存视频，只通过上传/下载接口读写这份库；
 *  · 选片只在这里发生，虚拟摄像头 provider 只消费 selection。
 *
 * 目录：
 *   /data/system/portalex/camera/library.json
 *   /data/system/portalex/camera/videos/<video-uuid>.mp4
 */
object CameraMockServerStore {

    private const val ROOT = "/data/system/portalex/camera"
    private const val LIBRARY = "$ROOT/library.json"
    private const val VIDEOS = "$ROOT/videos"

    fun root(): File = File(ROOT)
    fun videosDir(): File = File(VIDEOS).apply { if (!exists()) mkdirs() }
    fun libraryFile(): File = File(LIBRARY)

    /** 读库；坏 JSON/不存在都按空库处理，不把 system_server 拖垮。 */
    fun load(): List<CameraRoute> {
        val f = libraryFile()
        if (!f.exists()) return emptyList()
        return runCatching { CameraMockCodec.decodeRoutes(f.readText()) }
            .getOrDefault(emptyList())
    }

    /** 原子写库：先写临时文件再 rename，避免 app 下载到半截 JSON。 */
    fun save(routes: List<CameraRoute>) {
        root().mkdirs()
        videosDir()
        val f = libraryFile()
        val tmp = File("$LIBRARY.tmp")
        tmp.writeText(CameraMockCodec.encodeRoutes(routes))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun videoFile(videoId: String): File = File(videosDir(), "$videoId.mp4")

    /** 删除库中不再引用的视频文件（app 删除路段后由 system_server 同步清理）。 */
    fun pruneOrphanVideos(keepIds: Set<String>): Int {
        val dir = videosDir()
        var removed = 0
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBeforeLast('.')
            if (f.isFile && !keepIds.contains(id)) {
                if (runCatching { f.delete() }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    /** 同一摄像头会话上次选中的视频：其所有轨迹点距离按 x0.5 加权。 */
    private val sessionLastVideo = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 最近坐标选片。
     *
     * 算法已收口到 [CameraMockSelectionEngine]：这里只负责加载库、会话去重状态，
     * 以及把候选交给共享引擎。native cammock 只消费返回值，不再复制选片规则。
     */
    fun select(
        lat: Double?,
        lon: Double?,
        nowMs: Long = System.currentTimeMillis(),
        tagFilter: List<String> = emptyList(),
        session: String = "",
        random: Random = Random.Default
    ): CameraMockSelection? {
        val routes = load()
        if (routes.isEmpty()) return null
        val lastVideo = if (session.isNotBlank()) sessionLastVideo[session] else null
        val selected = CameraMockSelectionEngine.selectRoutes(
            routes = routes,
            lat = lat,
            lon = lon,
            tagFilter = tagFilter,
            session = session,
            lastVideoId = lastVideo,
            random = random,
            nowMs = nowMs,
        ) ?: return null
        if (session.isNotBlank()) {
            if (sessionLastVideo.size > 64) sessionLastVideo.clear()
            sessionLastVideo[session] = selected.videoId
        }
        return selected
    }

}
