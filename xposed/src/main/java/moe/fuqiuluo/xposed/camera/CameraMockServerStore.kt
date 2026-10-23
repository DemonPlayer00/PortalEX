package moe.fuqiuluo.xposed.camera

import moe.fuqiuluo.xposed.utils.WorldMath
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
     * - 有轨迹：逐点计算距离，取最近的轨迹点，startMs = 该点 tMs；
     * - 无轨迹：用路线锚点，startMs = 0；
     * - 距离相差 ≤ 0.5m 的候选（同坐标/同位置多段视频）随机取一个；
     * - 标签：显式筛选全含；再叠加时间标签（白天/黑夜）。
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
        val timeTag = if (isDay(nowMs)) CameraTags.DAY else CameraTags.NIGHT
        val lastVideo = if (session.isNotBlank()) sessionLastVideo[session] else null
        val candidates = ArrayList<Candidate>()

        routes.forEach { route ->
            route.videos.forEach { video ->
                // 显式标签全含；时间标签只作为“优先”，不能让未打标签的视频全部落选。
                if (!CameraMockSelector.matches(video.tags, tagFilter)) return@forEach
                val timeMatch = video.tags.any { it.equals(timeTag, ignoreCase = true) }
                // 无标签视频也始终进入候选；时间标签不再做硬性优先过滤。
                val bias = if (lastVideo != null && video.id == lastVideo) 0.5 else 1.0
                if (video.track.isEmpty()) {
                    val distance = if (lat == null || lon == null) -1.0
                    else WorldMath.haversine(lat, lon, route.lat, route.lon) * bias
                    candidates += Candidate(route, video, 0L, distance, timeMatch)
                } else {
                    video.track.forEach { point ->
                        val distance = if (lat == null || lon == null) -1.0
                        else WorldMath.haversine(lat, lon, point.lat, point.lon) * bias
                        candidates += Candidate(route, video, point.tMs, distance, timeMatch)
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null

        val chosen = if (lat == null || lon == null) {
            // 无位置时所有样本等概率；不再让时间标签把无标签视频挤掉。
            candidates[random.nextInt(candidates.size)]
        } else {
            val minDistance = candidates.minOf { it.distanceM }
            val near = candidates.filter { it.distanceM <= minDistance + 0.5 }
            near[random.nextInt(near.size)]
        }
        val video = chosen.video
        if (session.isNotBlank()) {
            if (sessionLastVideo.size > 64) sessionLastVideo.clear()
            sessionLastVideo[session] = video.id
        }
        val start = if (video.durationMs > 0) {
            chosen.startMs.coerceIn(0L, video.durationMs - 1)
        } else chosen.startMs.coerceAtLeast(0L)
        return CameraMockSelection(
            pointId = chosen.route.id,
            pointName = chosen.route.name,
            videoId = video.id,
            videoName = video.name,
            fileName = video.file,
            durationMs = video.durationMs,
            startMs = start,
            distanceM = chosen.distanceM
        )
    }

    private fun isDay(nowMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 6..18
    }

    private data class Candidate(
        val route: CameraRoute,
        val video: CameraVideo,
        val startMs: Long,
        val distanceM: Double,
        val timeMatch: Boolean
    )
}
