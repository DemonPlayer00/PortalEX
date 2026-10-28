package moe.fuqiuluo.xposed.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单编解码的回归测试。
 *
 * 重点不是"能不能编出来"，而是**读侧对畸形输入的容错**：清单是从另一个进程拿来的，
 * 它坏掉时唯一可接受的后果是"这次不拦截"，绝不能变成目标应用里的崩溃或半死不活的状态。
 */
class CameraMockCodecTest {

    private fun video(
        id: String = "v1",
        name: String = "视频1",
        file: String = "cam_v1.mp4",
        durationMs: Long = 65_000,
        tags: List<String> = listOf("晴", "白天"),
        addedAt: Long = 1_700_000_000_000
    ) = CameraVideo(id, name, file, durationMs, tags, addedAt)

    private fun point(id: String = "p1", videos: List<CameraVideo> = listOf(video())) =
        CameraVideoPoint(id, "路口 $id", 39.908822, 116.397465, videos)

    private fun manifest(
        enabled: Boolean = true,
        filter: List<String> = listOf("白天"),
        points: List<CameraVideoPoint> = listOf(point()),
        targets: List<String> = listOf("com.example.app")
    ) = CameraMockManifest(enabled, filter, points, targets, 1_700_000_000_000)

    @Test
    fun `往返保持全部字段`() {
        val src = manifest(points = listOf(point("p1"), point("p2", listOf(video("v9", durationMs = 0)))))
        val out = CameraMockCodec.decode(CameraMockCodec.encode(src))
        assertNotNull(out)
        out!!
        assertTrue(out.enabled)
        assertEquals(listOf("白天"), out.tagFilter)
        assertEquals(listOf("com.example.app"), out.targets)
        assertEquals(1_700_000_000_000L, out.updatedAt)
        assertEquals(2, out.points.size)
        assertEquals(src.points[0], out.points[0])
        assertEquals(src.points[1], out.points[1])
    }

    @Test
    fun `经纬度精确往返`() {
        val p = CameraVideoPoint("p", "极端坐标", -33.8688197, 151.2092955, emptyList())
        val out = CameraMockCodec.decode(CameraMockCodec.encode(manifest(points = listOf(p))))!!
        assertEquals(-33.8688197, out.points[0].lat, 0.0)
        assertEquals(151.2092955, out.points[0].lon, 0.0)
    }

    @Test
    fun `名称里的引号反斜杠换行与 emoji 都能往返`() {
        val tricky = "他说\"走这边\"\\然后是\n换行 🚗 和制表\t符"
        val src = manifest(
            points = listOf(point("p1", listOf(video(name = tricky, tags = listOf("自定义\"标签")))))
        )
        val out = CameraMockCodec.decode(CameraMockCodec.encode(src))!!
        assertEquals(tricky, out.points[0].videos[0].name)
        assertEquals("自定义\"标签", out.points[0].videos[0].tags[0])
    }

    @Test
    fun `语法错误返回 null 而不是抛异常`() {
        assertNull(CameraMockCodec.decode("{"))
        assertNull(CameraMockCodec.decode("not json at all"))
        assertNull(CameraMockCodec.decode("[1,2,3]"))
        assertNull(CameraMockCodec.decode("{\"enabled\": true} trailing"))
    }

    @Test
    fun `缺少 enabled 视为不可用`() {
        assertNull(CameraMockCodec.decode("{\"points\":[]}"))
        // enabled 必须是布尔：写成字符串/数字不接受（宁可关闭也不能猜错开关）
        assertNull(CameraMockCodec.decode("{\"enabled\":\"true\"}"))
        assertNull(CameraMockCodec.decode("{\"enabled\":1}"))
    }

    @Test
    fun `单个视频条目损坏只丢它自己`() {
        val json = """
            {"version":1,"enabled":true,"tagFilter":[],"targets":[],"updatedAt":7,
             "points":[{"id":"p1","name":"点","lat":1.0,"lon":2.0,"videos":[
                {"id":"good","name":"好","file":"a.mp4","durationMs":1000,"tags":["晴"],"addedAt":1},
                {"id":"bad-no-file","name":"坏","durationMs":1000,"tags":[],"addedAt":1},
                {"name":"没有id","file":"c.mp4","durationMs":1000,"tags":[],"addedAt":1}
             ]}]}
        """.trimIndent()
        val out = CameraMockCodec.decode(json)!!
        assertEquals(1, out.points.size)
        assertEquals(1, out.points[0].videos.size)
        assertEquals("good", out.points[0].videos[0].id)
    }

    @Test
    fun `点条目缺 id 或坐标时被丢弃`() {
        val json = """
            {"enabled":true,"points":[
              {"name":"没有id","lat":1.0,"lon":2.0,"videos":[]},
              {"id":"p2","name":"没有坐标","videos":[]},
              {"id":"p3","name":"好点","lat":3.0,"lon":4.0,"videos":[]}
            ]}
        """.trimIndent()
        val out = CameraMockCodec.decode(json)!!
        assertEquals(listOf("p3"), out.points.map { it.id })
    }

    @Test
    fun `标签归一化：去空白去重截断且大小写不敏感`() {
        val tags = CameraTags.sanitize(listOf(" 晴 ", "晴", "SUNNY", "sunny", "", "   ", "x".repeat(80)))
        assertEquals(listOf("晴", "SUNNY", "x".repeat(CameraTags.MAX_LEN)), tags)
    }

    @Test
    fun `视频标签数量上限`() {
        val many = (1..40).map { "t$it" }
        assertEquals(CameraTags.MAX_COUNT, CameraTags.sanitize(many).size)
    }

    @Test
    fun `关闭态清单解码后 enabled 为假`() {
        val out = CameraMockCodec.decode(CameraMockCodec.encode(CameraMockManifest.DISABLED))!!
        assertFalse(out.enabled)
        assertTrue(out.points.isEmpty())
    }
}
