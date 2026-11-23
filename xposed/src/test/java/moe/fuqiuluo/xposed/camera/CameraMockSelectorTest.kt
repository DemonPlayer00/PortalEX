package moe.fuqiuluo.xposed.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 选片引擎的回归测试：标签语义、最近点、随机性、随机起播、循环映射。
 *
 * 这几条规则是**用户口径**直接落地的部分，改错了不会报错，只会"播错片子"，
 * 所以每条都用可复现的种子断言，而不是"看起来对"。
 */
class CameraMockSelectorTest {

    private fun v(id: String, tags: List<String>, durationMs: Long = 10_000, recordedAt: Long = 0L) =
        CameraVideo(id, "vid-$id", "$id.mp4", durationMs, tags, addedAt = 0L, recordedAt = recordedAt)

    /** 天安门 / 王府井 / 三里屯，间距依次 ~1.6km、~5km，便于构造"最近点" */
    private val pTiananmen = CameraVideoPoint(
        "p-tam", "天安门", 39.908722, 116.397499,
        listOf(v("a", listOf("晴", "白天")), v("b", listOf("阴", "白天")))
    )
    private val pWangfujing = CameraVideoPoint(
        "p-wfj", "王府井", 39.915000, 116.410000,
        listOf(v("c", listOf("白天")))
    )
    private val pSanlitun = CameraVideoPoint(
        "p-slt", "三里屯", 39.933000, 116.455000,
        listOf(v("d", listOf("黑夜")))
    )

    private fun manifest(filter: List<String> = emptyList()) = CameraMockManifest(
        enabled = true,
        tagFilter = CameraTags.sanitize(filter),
        points = listOf(pTiananmen, pWangfujing, pSanlitun),
        targets = emptyList(),
        updatedAt = 0L
    )

    @Test
    fun `空筛选命中全部视频`() {
        val m = manifest()
        assertTrue(CameraMockSelector.matches(listOf("黑夜"), emptyList()))
        assertTrue(CameraMockSelector.matches(emptyList(), emptyList()))
        assertTrue(m.points.all { p -> p.videos.all { CameraMockSelector.matches(it.tags, m.tagFilter) } })
    }

    @Test
    fun `全含语义：筛选里的每个标签都要有`() {
        assertTrue(CameraMockSelector.matches(listOf("晴", "白天", "自定义"), listOf("白天", "晴")))
        assertTrue(CameraMockSelector.matches(listOf("sUnNy"), listOf("sunny")))
        assertTrue(!CameraMockSelector.matches(listOf("晴"), listOf("晴", "白天")))
    }

    @Test
    fun `无标签素材在任意筛选下都命中`() {
        // 用户没给素材分类，就不该被分类筛选排除 —— 包括刚添加、还没打标签的图片
        assertTrue(CameraMockSelector.matches(emptyList(), listOf("白天")))
        assertTrue(CameraMockSelector.matches(emptyList(), listOf("晴", "白天", "自定义")))
        assertTrue(CameraMockSelector.matches(emptyList(), emptyList()))
    }

    @Test
    fun `取最近的命中点`() {
        // 位置在天安门旁：最近点必是天安门（且筛"白天"会排除三里屯）
        val sel = CameraMockSelector.select(manifest(listOf("白天")), 39.908, 116.397, Random(1))!!
        assertEquals("p-tam", sel.pointId)

        // 位置挪到王府井旁：最近点切换（天安门虽然也有"白天"，但更远）
        val sel2 = CameraMockSelector.select(manifest(listOf("白天")), 39.9151, 116.4101, Random(1))!!
        assertEquals("p-wfj", sel2.pointId)
    }

    @Test
    fun `最近点内多个命中视频时随机取一个且都取得到`() {
        val m = manifest(listOf("白天"))
        val picked = (1..40).map {
            CameraMockSelector.select(m, 39.908, 116.397, Random(it.toLong()))!!.videoId
        }.toSet()
        assertEquals(setOf("a", "b"), picked)
    }

    @Test
    fun `全部不命中时返回 null（模块侧据此不拦截）`() {
        assertNull(CameraMockSelector.select(manifest(listOf("雨")), 39.908, 116.397, Random(1)))
        // 位置未知也一样：没有命中项就是不拦截，不因为"不知道在哪"而随便播
        assertNull(CameraMockSelector.select(manifest(listOf("雨")), null, null, Random(1)))
    }

    @Test
    fun `位置未知时在所有命中视频里等概率取一个`() {
        val m = manifest()
        val picked = (1..80).map {
            CameraMockSelector.select(m, null, null, Random(it.toLong()))!!.videoId
        }.toSet()
        // 命中集合 = 全部 4 个视频，且分布不该只落在某一个点上
        assertEquals(setOf("a", "b", "c", "d"), picked)
        val sel = CameraMockSelector.select(m, null, null, Random(7))!!
        assertEquals(-1.0, sel.distanceM, 0.0)
    }

    @Test
    fun `随机起播是整秒且落在时长内`() {
        val m = manifest(listOf("白天"))
        val starts = (1..50).map {
            CameraMockSelector.select(m, 39.908, 116.397, Random(it.toLong()))!!.startMs
        }
        assertTrue(starts.all { it % 1000L == 0L })
        assertTrue(starts.all { it in 0L until 10_000L })
        assertTrue(starts.toSet().size > 1) // 确实在随机，而不是恒 0

        assertEquals(0L, CameraMockSelector.randomStartMs(0, Random(1)))
        assertEquals(0L, CameraMockSelector.randomStartMs(999, Random(1)))
        assertEquals(0L, CameraMockSelector.randomStartMs(1000, Random(1)))
        assertTrue(CameraMockSelector.randomStartMs(5000, Random(3)) in 0L..4000L)
    }

    @Test
    fun `循环时间轴：播到末尾回文件头`() {
        // 从 7s 起播、时长 10s：+2s → 9s；再 +2s → 回绕 1s；再 +10s → 仍是 1s
        assertEquals(9_000L, CameraMockSelector.loopPositionMs(7_000, 2_000, 10_000))
        assertEquals(1_000L, CameraMockSelector.loopPositionMs(7_000, 4_000, 10_000))
        assertEquals(1_000L, CameraMockSelector.loopPositionMs(7_000, 14_000, 10_000))
        // 起始偏移越界时钳进时长内（脏数据不产生非法 seek 位置）：99s → duration-1 = 9999ms
        assertEquals(9_999L, CameraMockSelector.loopPositionMs(99_000, 0, 10_000))
        assertEquals(0L, CameraMockSelector.loopPositionMs(5_000, 1_000, 0))
    }

    @Test
    fun `距离相同按 id 定序保证可复现`() {
        // 两个点与查询点等距（正南北对称），结果必须稳定
        // 纬度取 10/0，查询点取 5：两条边都是精确的 5.0 度，距离逐位相等（不用近似的等距点）
        val a = CameraVideoPoint("p-a", "A", 10.0, 0.0, listOf(v("x", listOf("白天"))))
        val b = CameraVideoPoint("p-b", "B", 0.0, 0.0, listOf(v("y", listOf("白天"))))
        val m = CameraMockManifest(
            enabled = true, tagFilter = emptyList(), points = listOf(b, a), targets = emptyList(), updatedAt = 0
        )
        val picked = (1..10).map {
            CameraMockSelector.select(m, 5.0, 0.0, Random(it.toLong()))!!.pointId
        }.toSet()
        assertEquals(setOf("p-a"), picked)
    }

    @Test
    fun `按一天中的时刻选择最近的视频且忽略日期`() {
        val day = 24L * 60L * 60L * 1000L
        val hour = 60L * 60L * 1000L
        // 当前 12:00；A=11:59（差 1 分钟），B=次日 13:01（差 61 分钟）
        val now = 200L * day + 12L * hour
        val a = v("time-a", emptyList(), recordedAt = 100L * day + 11L * hour + 59L * 60_000L)
        val b = v("time-b", emptyList(), recordedAt = 300L * day + 13L * hour + 60_000L)
        val point = CameraVideoPoint("p-time", "时刻", 39.9087, 116.3975, listOf(a, b))
        val m = CameraMockManifest(true, emptyList(), listOf(point), emptyList(), 0L)
        val picked = CameraMockSelector.select(m, 39.9087, 116.3975, Random(1), nowMs = now)!!
        assertEquals("time-a", picked.videoId)
        assertEquals(60_000L, CameraMockSelectionEngine.timeOfDayDistanceMs(a.recordedAt, now))
    }

    @Test
    fun `距离优先于远处路线的更近时刻`() {
        val day = 24L * 60L * 60L * 1000L
        val hour = 60L * 60L * 1000L
        val now = 200L * day + 12L * hour
        fun route(id: String, lat: Double, recordedAt: Long) = CameraRoute(
            id, id, lat, 116.3975, 0L,
            listOf(CameraVideo(id + "-v", id, "$id.mp4", 10_000, emptyList(),
                addedAt = 0L, recordedAt = recordedAt))
        )
        val near = route("near", 39.9087, 100L * day + 8L * hour)
        val far = route("far", 39.9500, 100L * day + 12L * hour) // 时刻更近但地点远
        val sel = CameraMockSelectionEngine.selectRoutes(
            listOf(near, far), 39.9087, 116.3975, emptyList(),
            nowMs = now, random = Random(1)
        )!!
        assertEquals("near-v", sel.videoId)
    }

    @Test
    fun `同一地点按时刻选择且会话内视频有迟滞`() {
        val day = 24L * 60L * 60L * 1000L
        val hour = 60L * 60L * 1000L
        val now = 200L * day + 12L * hour
        val a = CameraVideo("a", "a", "a.mp4", 10_000, emptyList(),
            addedAt = 0L, recordedAt = 100L * day + 11L * hour + 58L * 60_000L)
        val b = CameraVideo("b", "b", "b.mp4", 10_000, emptyList(),
            addedAt = 0L, recordedAt = 100L * day + 12L * hour)
        val route = CameraRoute("r", "r", 39.9087, 116.3975, 0L, listOf(a, b))
        val picked = CameraMockSelectionEngine.selectRoutes(
            listOf(route), 39.9087, 116.3975, emptyList(), nowMs = now, random = Random(1)
        )!!
        assertEquals("b", picked.videoId)
        val sticky = CameraMockSelectionEngine.selectRoutes(
            listOf(route), 39.9087, 116.3975, emptyList(),
            lastVideoId = "a", lastRouteId = "r", nowMs = now, random = Random(1)
        )!!
        assertEquals("a", sticky.videoId)
    }
    @Test
    fun `选片探针记录查询坐标与选中轨迹点`() {
        val picked = CameraMockSelector.select(
            manifest(), pTiananmen.lat, pTiananmen.lon, Random(1), nowMs = 0L
        )!!
        assertEquals(pTiananmen.lat, picked.queryLat, 1e-9)
        assertEquals(pTiananmen.lon, picked.queryLon, 1e-9)
        assertEquals(pTiananmen.lat, picked.selectedLat, 1e-9)
        assertEquals(pTiananmen.lon, picked.selectedLon, 1e-9)
        assertEquals(0.0, picked.nearestDistanceM, 1e-3)
        assertEquals(0.0, picked.distanceM, 1e-3)
        assertTrue(picked.candidateCount > 0)
    }

    // ------------------------------------------------ 静止图片（一帧 + 一个点位）

    private fun still(
        id: String,
        lat: Double,
        lon: Double,
        recordedAt: Long = 0L,
        trackOverride: List<CameraVideoTrackPoint>? = null
    ) = CameraVideo(
        id = id, name = "图片-$id", file = "$id.mp4", durationMs = 3_000,
        tags = emptyList(), addedAt = 0L, recordedAt = recordedAt,
        track = trackOverride ?: listOf(CameraVideoTrackPoint(0L, lat, lon)),
        kind = CameraMediaKind.STILL
    )

    private fun stillRoute(id: String, lat: Double, lon: Double, recordedAt: Long = 0L) =
        CameraRoute(id, id, lat, lon, 0L, listOf(still("$id-v", lat, lon, recordedAt)))

    @Test
    fun `静止图片按它绑定的单点参与最近点选择`() {
        val near = stillRoute("near", 39.9087, 116.3975)
        val far = stillRoute("far", 39.9500, 116.3975)
        val sel = CameraMockSelectionEngine.selectRoutes(
            listOf(far, near), 39.9087, 116.3975, emptyList(), nowMs = 0L, random = Random(1)
        )!!
        assertEquals("near-v", sel.videoId)
        assertEquals(CameraMediaKind.STILL, sel.kind)
        assertTrue(sel.isStill)
        // 每张图只有"它自己那一个点"，所以候选数 = 图片数
        assertEquals(2, sel.candidateCount)
        assertEquals(0L, sel.startMs)
        assertEquals(0.0, sel.distanceM, 1e-3)
    }

    @Test
    fun `静止图片的起播偏移恒为 0（时长再长也不随机到中间）`() {
        val route = stillRoute("r", 39.9087, 116.3975)
        (1..30).forEach { seed ->
            val viaRoutes = CameraMockSelectionEngine.selectRoutes(
                listOf(route), 39.9087, 116.3975, emptyList(), nowMs = 0L, random = Random(seed.toLong())
            )!!
            assertEquals(0L, viaRoutes.startMs)
            val viaManifest = CameraMockSelector.select(
                CameraMockManifest(
                    enabled = true, tagFilter = emptyList(),
                    points = CameraMockCodec.routesToPoints(listOf(route)),
                    targets = emptyList(), updatedAt = 0L
                ),
                39.9087, 116.3975, Random(seed.toLong()), nowMs = 0L
            )!!
            assertEquals(0L, viaManifest.startMs)
        }
    }

    @Test
    fun `同一地点的图片按一天中的时刻挑最接近的那张`() {
        val day = 24L * 60L * 60L * 1000L
        val hour = 60L * 60L * 1000L
        val now = 200L * day + 12L * hour
        val noon = stillRoute("noon", 39.9087, 116.3975, recordedAt = 100L * day + 12L * hour)
        val dawn = stillRoute("dawn", 39.9087, 116.3975, recordedAt = 100L * day + 6L * hour)
        val sel = CameraMockSelectionEngine.selectRoutes(
            listOf(dawn, noon), 39.9087, 116.3975, emptyList(), nowMs = now, random = Random(1)
        )!!
        assertEquals("noon-v", sel.videoId)
    }

    @Test
    fun `无标签图片在标签筛选下仍可被选中`() {
        // stillRoute 造出来的图片 tags 为空；筛选"白天"不应把它排除
        val route = stillRoute("bare", 39.9087, 116.3975)
        val sel = CameraMockSelectionEngine.selectRoutes(
            listOf(route), 39.9087, 116.3975, listOf("白天"), nowMs = 0L, random = Random(1)
        )
        assertNotNull(sel)
        assertEquals("bare-v", sel!!.videoId)
        assertEquals(0L, sel.startMs)
    }

    @Test
    fun `图片的点位坏掉时退回路线锚点而不是丢帧`() {
        val broken = CameraVideo(
            id = "broken-v", name = "坏图", file = "broken.mp4", durationMs = 3_000,
            tags = emptyList(), addedAt = 0L, recordedAt = 0L,
            track = emptyList(), kind = CameraMediaKind.STILL
        )
        val route = CameraRoute("r", "锚点", 39.9087, 116.3975, 0L, listOf(broken))
        val sel = CameraMockSelectionEngine.selectRoutes(
            listOf(route), 39.9087, 116.3975, emptyList(), nowMs = 0L, random = Random(1)
        )!!
        assertEquals("broken-v", sel.videoId)
        assertEquals(39.9087, sel.selectedLat, 1e-9)
        assertEquals(0L, sel.startMs)
    }

    @Test
    fun `路线探针记录最近候选与选中候选的距离差`() {
        val day = 24L * 60L * 60L * 1000L
        val hour = 60L * 60L * 1000L
        val now = 200L * day + 12L * hour
        val near = CameraRoute(
            "near", "near", 39.9087, 116.3975, 0L,
            listOf(CameraVideo("near-v", "near", "near.mp4", 10_000, emptyList(), 0L))
        )
        val far = CameraRoute(
            "far", "far", 39.9500, 116.3975, 0L,
            listOf(CameraVideo("far-v", "far", "far.mp4", 10_000, emptyList(), 0L))
        )
        val sel = CameraMockSelectionEngine.selectRoutes(
            listOf(near, far), 39.9087, 116.3975, emptyList(), nowMs = now, random = Random(1)
        )!!
        assertEquals("near-v", sel.videoId)
        assertEquals(sel.nearestDistanceM, sel.distanceM, 1e-6)
        assertEquals(2, sel.candidateCount)
    }

}
