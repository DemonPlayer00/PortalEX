package moe.fuqiuluo.xposed.camera

import moe.fuqiuluo.xposed.utils.WorldMath
import kotlin.random.Random

/**
 * 摄像头选片的**唯一算法实现**。
 *
 * 所有调用方（目标应用进程 hook、system_server 的 native cammock 选片服务）
 * 都必须经过这里，cammock 只负责发请求和播放返回的视频。
 *
 * 纯函数，不读 Android/文件/全局状态；输入候选，输出选片。
 */
object CameraMockSelectionEngine {

    /** 标签匹配：空筛选 = 全部；非空筛选 = 视频必须包含全部标签（无标签视频不命中）。 */
    fun matches(videoTags: List<String>, filter: List<String>): Boolean {
        if (filter.isEmpty()) return true
        if (videoTags.isEmpty()) return false
        return filter.all { want -> videoTags.any { it.equals(want, ignoreCase = true) } }
    }

    /** 一个可参与选片的候选（同一个视频的每个轨迹点各算一个候选）。 */
    data class Candidate(
        val pointId: String,
        val pointName: String,
        val video: CameraVideo,
        val startMs: Long,
        val distanceM: Double,
    )

    /**
     * 从候选里选出最终视频。
     *
     * @param lat/lon null = 位置未知：在全部候选里等概率取一个。
     * @param nearToleranceM 最近候选容差：距离差在该范围内的候选视为同距，随机取。
     * @param randomStart true = 使用视频内随机整秒起播；false = 使用候选自带 startMs
     *   （轨迹候选的录制时间点）。
     */
    fun choose(
        candidates: List<Candidate>,
        lat: Double?,
        lon: Double?,
        nearToleranceM: Double = 0.5,
        randomStart: Boolean,
        random: Random = Random.Default,
    ): CameraMockSelection? {
        if (candidates.isEmpty()) return null
        val chosen = if (lat == null || lon == null) {
            candidates[random.nextInt(candidates.size)]
        } else {
            val minDistance = candidates.minOf { it.distanceM }
            val near = candidates.filter { it.distanceM <= minDistance + nearToleranceM }
            near[random.nextInt(near.size)]
        }
        val video = chosen.video
        val start = if (randomStart) {
            randomStartMs(video.durationMs, random)
        } else {
            if (video.durationMs > 0) chosen.startMs.coerceIn(0L, video.durationMs - 1)
            else chosen.startMs.coerceAtLeast(0L)
        }
        return CameraMockSelection(
            pointId = chosen.pointId,
            pointName = chosen.pointName,
            videoId = video.id,
            videoName = video.name,
            fileName = video.file,
            durationMs = video.durationMs,
            startMs = start,
            distanceM = chosen.distanceM,
        )
    }

    /** app 侧清单选片：先按“最近点”选点，再在该点内随机视频（与原语义一致）。 */
    fun selectManifest(
        manifest: CameraMockManifest,
        lat: Double?,
        lon: Double?,
        random: Random = Random.Default,
    ): CameraMockSelection? {
        val hits = manifest.points.mapNotNull { point ->
            val videos = point.videos.filter { matches(it.tags, manifest.tagFilter) }
            if (videos.isEmpty()) null else point to videos
        }
        if (hits.isEmpty()) return null
        val chosen = if (lat == null || lon == null) {
            val flat = hits.flatMap { (point, videos) -> videos.map { point to it } }
            flat[random.nextInt(flat.size)]
        } else {
            val nearest = hits.minWithOrNull(
                compareBy(
                    { WorldMath.haversine(lat, lon, it.first.lat, it.first.lon) },
                    { it.first.id }
                )
            )!!
            nearest.first to nearest.second[random.nextInt(nearest.second.size)]
        }
        val (point, video) = chosen
        val distance = if (lat == null || lon == null) -1.0
        else WorldMath.haversine(lat, lon, point.lat, point.lon)
        return CameraMockSelection(
            pointId = point.id,
            pointName = point.name,
            videoId = video.id,
            videoName = video.name,
            fileName = video.file,
            durationMs = video.durationMs,
            startMs = randomStartMs(video.durationMs, random),
            distanceM = distance,
        )
    }

    /**
     * system_server / native cammock 选片：按轨迹点建候选。
     *
     * 有轨迹时按轨迹点距离/time 轴选；无轨迹时按路线锚点。时间标签不作为硬过滤，
     * 只保留原来的会话去重加权（最近一段视频距离乘 0.5，避免连续两次选同一段）。
     */
    fun selectRoutes(
        routes: List<CameraRoute>,
        lat: Double?,
        lon: Double?,
        tagFilter: List<String>,
        session: String = "",
        lastVideoId: String? = null,
        random: Random = Random.Default,
    ): CameraMockSelection? {
        if (routes.isEmpty()) return null
        val candidates = ArrayList<Candidate>()
        routes.forEach { route ->
            route.videos.forEach { video ->
                if (!matches(video.tags, tagFilter)) return@forEach
                val bias = if (lastVideoId != null && video.id == lastVideoId) 0.5 else 1.0
                if (video.track.isEmpty()) {
                    val distance = if (lat == null || lon == null) -1.0
                    else WorldMath.haversine(lat, lon, route.lat, route.lon) * bias
                    candidates += Candidate(route.id, route.name, video, 0L, distance)
                } else {
                    video.track.forEach { track ->
                        val distance = if (lat == null || lon == null) -1.0
                        else WorldMath.haversine(lat, lon, track.lat, track.lon) * bias
                        candidates += Candidate(route.id, route.name, video, track.tMs, distance)
                    }
                }
            }
        }
        return choose(candidates, lat, lon, randomStart = false, random = random)
    }

    /** `[0, 时长)` 内的随机整秒；时长不足 1 秒返回 0。 */
    fun randomStartMs(durationMs: Long, random: Random = Random.Default): Long {
        val seconds = durationMs / 1000L
        if (seconds <= 0L) return 0L
        return random.nextLong(seconds) * 1000L
    }
}
