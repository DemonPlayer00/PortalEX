package moe.fuqiuluo.xposed.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **手工构造的库 JSON 能不能被解码**。
 *
 * 为什么值得留一条：真机 bring-up 时常常需要"绕过 UI 直接塞一份素材进库"
 * （录一段视频要点十几下，而验证注入平面只关心"有没有命中素材"）。
 * 这类 JSON 是人手写的，最容易在字段名/类型上与 [CameraMockCodec] 悄悄错开 ——
 * 错开的后果是"库看起来是空的"，而不是报错，非常难查。
 */
class MockLibraryJsonTest {

    @Test
    fun decodeHandcraftedLibraryJson() {
        // 与 adb 写入 /data/data/<pkg>/shared_prefs/portal.xml 的 cameraMockLibrary 值逐字一致
        val json = """[{"id":"pt-test","name":"测试点","lat":23.5248,"lon":113.6093,""" +
                """"videos":[{"id":"vid-test","name":"合成测试片","file":"mock_test.mp4",""" +
                """"durationMs":8000,"tags":["白天"],"addedAt":1789229900000}]}]"""

        val points = CameraMockCodec.decodePoints(json)

        assertEquals(1, points.size)
        val point = points[0]
        assertEquals("pt-test", point.id)
        assertEquals(23.5248, point.lat, 1e-9)
        assertEquals(113.6093, point.lon, 1e-9)
        assertEquals(1, point.videos.size)
        assertEquals("mock_test.mp4", point.videos[0].file)
        assertEquals(8000L, point.videos[0].durationMs)
        assertEquals(listOf("白天"), point.videos[0].tags)
    }
}
