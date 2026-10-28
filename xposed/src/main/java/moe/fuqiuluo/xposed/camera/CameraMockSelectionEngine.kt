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
    /** 同一地点判定：距最近路线 50m 内的其它路线才参与时刻比较。 */
    const val SAME_PLACE_RADIUS_M = 50.0
    /** 距离代价的软拐点：超过 20m 后按二次方加速。 */
    const val DISTANCE_SOFT_LIMIT_M = 20.0
    /** 同一会话保留上次视频的时刻迟滞：避免每 3 秒刷新就换片。 */
    const val VIDEO_STICKY_TIME_MS = 120_000L

    /** 标签匹配：空筛选 = 全部；非空筛选 = 视频必须包含全部标签（无标签视频不命中）。 */
    fun matches(videoTags: List<String>, filter: List<String>): Boolean {
        if (filter.isEmpty()) return true
        if (videoTags.isEmpty()) return false
        return filter.all { want -> videoTags.any { it.equals(want, ignoreCase = true) } }
    }

    /**
     * 只比较“一天中的时刻”，忽略日期；12:00 与 12:00 的距离是 0，23:59 与 00:01 是 2 分钟。
     * recordedAt <= 0（旧数据没有录制时间）返回 Long.MAX_VALUE，不参与时间优先。
     */
    fun timeOfDayDistanceMs(aMs: Long, bMs: Long): Long {
        if (aMs <= 0L || bMs <= 0L) return Long.MAX_VALUE
        val day = 24L * 60L * 60L * 1000L
        val a = Math.floorMod(aMs, day)
        val b = Math.floorMod(bMs, day)
        val d = kotlin.math.abs(a - b)
        return minOf(d, day - d)
    }

    /**
     * 在候选里按“时刻最近”挑一批：先找最小时刻距离，允许 [toleranceMs] 内并列；
     * 全都没有录制时间时返回 emptyList，由调用方回退到原随机逻辑。
     */
    private fun nearestTimeCandidates(
        candidates: List<Candidate>,
        nowMs: Long?,
        toleranceMs: Long = 60_000L,
    ): List<Candidate> {
        if (nowMs == null) return emptyList()
        val timed = candidates.filter { it.video.recordedAt > 0L }
        if (timed.isEmpty()) return emptyList()
        val minTime = timed.minOf { timeOfDayDistanceMs(it.video.recordedAt, nowMs) }
        return timed.filter { timeOfDayDistanceMs(it.video.recordedAt, nowMs) <= minTime + toleranceMs }
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
        nowMs: Long? = null,
    ): CameraMockSelection? {
        if (candidates.isEmpty()) return null
        val chosen = if (lat == null || lon == null) {
            val timed = nearestTimeCandidates(candidates, nowMs)
            val pool = timed.ifEmpty { candidates }
            pool[random.nextInt(pool.size)]
        } else {
            val minDistance = candidates.minOf { it.distanceM }
            val near = candidates.filter { it.distanceM <= minDistance + nearToleranceM }
            val timed = nearestTimeCandidates(near, nowMs)
            val pool = timed.ifEmpty { near }
            pool[random.nextInt(pool.size)]
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
        nowMs: Long? = null,
    ): CameraMockSelection? {
        val hits = manifest.points.mapNotNull { point ->
            val videos = point.videos.filter { matches(it.tags, manifest.tagFilter) }
            if (videos.isEmpty()) null else point to videos
        }
        if (hits.isEmpty()) return null
        val now = nowMs ?: 0L
        val chosen = if (lat == null || lon == null) {
            val flat = hits.flatMap { (point, videos) -> videos.map { point to it } }
            val timed = if (nowMs == null) emptyList() else flat.filter { it.second.recordedAt > 0L }
            val pool = if (timed.isNotEmpty()) {
                val minTime = timed.minOf { timeOfDayDistanceMs(it.second.recordedAt, now) }
                timed.filter { timeOfDayDistanceMs(it.second.recordedAt, now) <= minTime + 60_000L }
                    .ifEmpty { timed }
            } else flat
            pool[random.nextInt(pool.size)]
        } else {
            val nearest = hits.minWithOrNull(
                compareBy(
                    { WorldMath.haversine(lat, lon, it.first.lat, it.first.lon) },
                    { it.first.id }
                )
            )!!
            val videos = nearest.second
            val timed = if (nowMs == null) emptyList() else videos.filter { it.recordedAt > 0L }
            val pool = if (timed.isNotEmpty()) {
                val minTime = timed.minOf { timeOfDayDistanceMs(it.recordedAt, now) }
                timed.filter { timeOfDayDistanceMs(it.recordedAt, now) <= minTime + 60_000L }
                    .ifEmpty { timed }
            } else videos
            nearest.first to pool[random.nextInt(pool.size)]
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
     * 路线代价：20m 以内按线性、超过 20m 按二次方加速；时间用“分钟”作为同量级
     * 的轻量代价，因此近处可由时刻决定，远处即使时刻更近也会被距离压过去。
     */
    private fun routeCost(distanceM: Double, timeMs: Long): Double {
        val d = distanceM.coerceAtLeast(0.0)
        val distanceCost = if (d <= DISTANCE_SOFT_LIMIT_M) d
        else DISTANCE_SOFT_LIMIT_M +
            (d - DISTANCE_SOFT_LIMIT_M) * (d - DISTANCE_SOFT_LIMIT_M) / 10.0
        val timeMinutes = if (timeMs == Long.MAX_VALUE) 0.0 else timeMs / 60_000.0
        return distanceCost + timeMinutes
    }

    /**
     * system_server / native cammock 选片。
     *
     * 代价顺序（不是把时间和距离硬加权）：
     *  1. 先把路线按“当前坐标到其轨迹/锚点的最小距离”分组；
     *  2. 只有距最近路线 [SAME_PLACE_RADIUS_M] 内的路线才算同一地点，才参与时刻比较；
     *  3. 同一地点的路线内优先选时刻最近的视频；不同地点时距离优先，远处时刻再近也不选；
     *  4. 同一会话保留上次路线/视频（带时间迟滞），避免每 3 秒刷新一次就换片。
     */
    fun selectRoutes(
        routes: List<CameraRoute>,
        lat: Double?,
        lon: Double?,
        tagFilter: List<String>,
        session: String = "",
        lastVideoId: String? = null,
        lastRouteId: String? = null,
        random: Random = Random.Default,
        nowMs: Long? = null,
    ): CameraMockSelection? {
        if (routes.isEmpty()) return null

        // 候选粒度是**视频的每个轨迹点**，不是"整条录制的路线"。
        // 旧实现先把路线按 min 距离聚成一个桶、再在桶内按时刻选视频 ——
        // 只要路线覆盖了这片区域，min 距离永远≈0，"走到哪"就被抹平了：
        // 无论路线模拟播到哪里，选出来的都是同一条视频。这也是本次修复的核心。
        data class Cand(
            val route: CameraRoute,
            val video: CameraVideo,
            val lat: Double,
            val lon: Double,
            val startMs: Long,
            val distanceM: Double,
            val timeMs: Long,
        )

        val candidates = ArrayList<Cand>()
        routes.forEach { route ->
            val videos = route.videos.filter { matches(it.tags, tagFilter) }
            if (videos.isEmpty()) return@forEach
            videos.forEach { video ->
                val timeMs = if (video.recordedAt > 0L && nowMs != null) {
                    timeOfDayDistanceMs(video.recordedAt, nowMs)
                } else Long.MAX_VALUE
                if (video.track.isNotEmpty()) {
                    video.track.forEach { tp ->
                        val d = if (lat == null || lon == null) -1.0
                        else WorldMath.haversine(lat, lon, tp.lat, tp.lon)
                        candidates += Cand(route, video, tp.lat, tp.lon, tp.tMs, d, timeMs)
                    }
                } else {
                    val d = if (lat == null || lon == null) -1.0
                    else WorldMath.haversine(lat, lon, route.lat, route.lon)
                    candidates += Cand(route, video, route.lat, route.lon, 0L, d, timeMs)
                }
            }
        }
        if (candidates.isEmpty()) return null

        val chosen = if (lat == null || lon == null) {
            // 位置未知：按时刻选，再回退随机（与旧实现一致）。
            val timed = candidates.filter { it.timeMs != Long.MAX_VALUE }
            val pool = timed.ifEmpty { candidates }
            val minTime = pool.minOf { it.timeMs }
            val nearTime = pool.filter {
                it.timeMs == Long.MAX_VALUE || it.timeMs <= minTime + 60_000L
            }
            (nearTime.ifEmpty { pool }).random(random)
        } else {
            val minDistance = candidates.minOf { it.distanceM }
            val samePlace = candidates.filter { it.distanceM <= minDistance + SAME_PLACE_RADIUS_M }
            // 会话粘性：只在上次那条视频**当前位置仍然贴得很近**时保留（5m），
            // 一旦沿路线走开就自然换片 —— 不再用 2 分钟时刻迟滞把选片钉死。
            val sticky = lastVideoId?.let { id ->
                samePlace.filter { it.video.id == id }.minByOrNull { it.distanceM }
            }?.takeIf { it.distanceM <= minDistance + 5.0 }
            val pool = if (sticky != null) listOf(sticky) else samePlace
            // 同一位置内仍然优先时刻更接近的视频（时间只是轻量项，不再跨位置生效）。
            val timed = pool.filter { it.timeMs != Long.MAX_VALUE }
            if (timed.isEmpty()) {
                pool.random(random)
            } else {
                val minTime = timed.minOf { it.timeMs }
                val nearTime = timed.filter { it.timeMs <= minTime + 60_000L }
                (nearTime.ifEmpty { timed }).random(random)
            }
        }

        val start = if (chosen.video.durationMs > 0L) {
            chosen.startMs.coerceIn(0L, chosen.video.durationMs - 1)
        } else {
            chosen.startMs.coerceAtLeast(0L)
        }
        return CameraMockSelection(
            pointId = chosen.route.id,
            pointName = chosen.route.name,
            videoId = chosen.video.id,
            videoName = chosen.video.name,
            fileName = chosen.video.file,
            durationMs = chosen.video.durationMs,
            startMs = start,
            distanceM = chosen.distanceM,
        )
    }

    /** `[0, 时长)` 内的随机整秒；时长不足 1 秒返回 0。 */
    fun randomStartMs(durationMs: Long, random: Random = Random.Default): Long {
        val seconds = durationMs / 1000L
        if (seconds <= 0L) return 0L
        return random.nextLong(seconds) * 1000L
    }
}
