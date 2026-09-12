package moe.fuqiuluo.xposed.camera

import moe.fuqiuluo.xposed.utils.WorldMath
import kotlin.random.Random

/**
 * **选片引擎**：给定清单 + 当前表象位置，决定这次到底播哪一段、从第几秒起。
 *
 * 规则（用户口径，逐条落在这里，避免散进 hook 里）：
 *  1. 候选 = 标签筛选命中的视频；**命中的点**才有资格参与距离比较；
 *  2. 取"距离当前表象位置最近"的那个点；
 *  3. 该点内命中的视频**随机取一个**；
 *  4. 起播位置 = `[0, 时长)` 内的**随机整秒**；之后播到末尾回文件头继续（循环）——
 *     即偏移只在选中时掷一次骰子，循环期间不再变。
 *
 * 本对象**纯函数**：不读全局状态、不碰 Android API，因此可以在 JVM 上直接测
 * （见 `CameraMockSelectorTest`）。模块侧只负责把位置喂进来。
 */
object CameraMockSelector {

    /**
     * 标签匹配：**全含**语义 —— 筛选里的每个标签都必须出现在视频标签里。
     * 空筛选 = 全部命中（不筛选）；视频无标签时只在空筛选下命中。
     */
    fun matches(videoTags: List<String>, filter: List<String>): Boolean {
        if (filter.isEmpty()) return true
        return filter.all { want -> videoTags.any { it.equals(want, ignoreCase = true) } }
    }

    /**
     * 选片。
     *
     * @param lat/lon 当前**表象位置**（wgs84）。传 null = 位置未知（例如本次没开位置模拟）：
     *   此时不做距离比较，在**所有**命中的视频里等概率随机取一个 —— 比"报错/不拦截"更符合
     *   "摄像头模拟可以独立于位置模拟单跑"的直觉，也比"强行按第一个点"更不容易撞上死角。
     * @return null = 没有任何命中项（模块侧据此**不拦截**，把真实摄像头留给应用）
     */
    fun select(
        manifest: CameraMockManifest,
        lat: Double?,
        lon: Double?,
        random: Random = Random.Default
    ): CameraMockSelection? {
        val hits = manifest.points.mapNotNull { point ->
            val videos = point.videos.filter { matches(it.tags, manifest.tagFilter) }
            if (videos.isEmpty()) null else point to videos
        }
        if (hits.isEmpty()) return null

        val chosen: Pair<CameraVideoPoint, CameraVideo> = if (lat == null || lon == null) {
            // 位置未知：在全部命中视频里等概率取一个（先摊平，避免"视频多的点更容易中"）
            val flat = hits.flatMap { (point, videos) -> videos.map { point to it } }
            flat[random.nextInt(flat.size)]
        } else {
            // 最近的点（距离相同则按 id 定序，保证同一份清单下结果可复现），点内再随机
            val nearest = hits.minWithOrNull(
                compareBy(
                    { WorldMath.haversine(lat, lon, it.first.lat, it.first.lon) },
                    { it.first.id }
                )
            )!!
            val videos = nearest.second
            nearest.first to videos[random.nextInt(videos.size)]
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
            distanceM = distance
        )
    }

    /**
     * 随机起播偏移：`[0, 时长)` 内的随机**整秒**。
     * 时长未知（0）或不足 1 秒时返回 0 —— 此时"随机起播"没有可分辨的取值，
     * 由解码器播到 EOS 再回绕即可。
     */
    fun randomStartMs(durationMs: Long, random: Random = Random.Default): Long {
        val seconds = durationMs / 1000L
        if (seconds <= 0L) return 0L
        return random.nextLong(seconds) * 1000L
    }

    /** 播放时间轴：把"从 [startMs] 起播、播完回绕"的循环映射成文件内时间（毫秒）。
     *  纯函数，供播放器与单测共用：`elapsedSinceStart` 为从起播算起的累计播放时间。 */
    fun loopPositionMs(startMs: Long, elapsedSinceStartMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val start = startMs.coerceIn(0L, durationMs - 1)
        val total = start + elapsedSinceStartMs.coerceAtLeast(0L)
        return total % durationMs
    }
}
