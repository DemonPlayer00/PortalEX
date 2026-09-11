package moe.fuqiuluo.portalex.ui.mock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `HistoricalLocation` 的序列化契约。
 *
 * 它是**持久化格式**（历史位置存在 SharedPreferences 的多行 CSV 里，见 Perfs.kt 的
 * `locations`），所以"写出去再读回来是否一致"是硬要求：格式坏了等于用户的历史位置丢失，
 * 而这条链路上没有任何测试 —— 本文件补上。
 */
class HistoricalLocationTest {

    private fun roundtrip(loc: HistoricalLocation): HistoricalLocation =
        HistoricalLocation.fromString(loc.toString())

    @Test
    fun `普通字段往返一致`() {
        val loc = HistoricalLocation("家", "广东省深圳市南山区某路 1 号", 22.543210, 114.057868)
        assertEquals(loc, roundtrip(loc))
    }

    @Test
    fun `字段含逗号时往返一致`() {
        val loc = HistoricalLocation("公司, 分部", "北京市海淀区, 中关村大街", 39.983424, 116.322987)
        assertEquals(loc, roundtrip(loc))
    }

    @Test
    fun `字段含双引号时往返一致`() {
        // CSV 里双引号必须转义成两个双引号；只加引号不转义会让解析器把引号当状态开关
        val loc = HistoricalLocation("他说\"走这边\"", "标注\"偏航\"的路口", 30.0, 120.0)
        assertEquals(loc, roundtrip(loc))
    }

    @Test
    fun `含逗号与引号混合时往返一致`() {
        val loc = HistoricalLocation("A,\"B\"", "C,\"D\"", 1.0, 2.0)
        assertEquals(loc, roundtrip(loc))
    }

    @Test
    fun `经纬度用普通十进制而不是科学计数法`() {
        // 两个都要盯：既不能是科学计数法（1.0E-5），也不能是 BigDecimal(double) 展开的
        // 二进制精确值（0.0000100000000000000008180305391…）。
        // 注意 1e-5 会写成 "0.000010"（BigDecimal 保留 Double.toString 的 scale）——
        // 这是**实测结果**，不是笔误。
        val loc = HistoricalLocation("原点附近", "", 1.0E-5, -1.0E-5)
        val text = loc.toString()
        assertEquals("原点附近,,0.000010,-0.000010", text)
        assertFalse("不该出现科学计数法", text.contains("E-"))
        assertEquals(loc, HistoricalLocation.fromString(text))

        val normal = HistoricalLocation("常规坐标", "", 22.54321, 114.057868)
        assertEquals("常规坐标,,22.54321,114.057868", normal.toString())
    }

    @Test
    fun `负坐标与整数坐标往返一致`() {
        val loc = HistoricalLocation("南半球", "西经", -33.0, -70.5)
        assertEquals(loc, roundtrip(loc))
    }

    @Test
    fun `字段数不对时明确报错而不是静默给出错误数据`() {
        // 静默容错会写进偏好、下次读出来就是一条"看起来正常但位置错了"的记录
        assertThrows(IllegalArgumentException::class.java) {
            HistoricalLocation.fromString("只有,三个,字段")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoricalLocation.fromString("多,出,一,个,字段")
        }
    }

    @Test
    fun `坐标非法时报错`() {
        assertThrows(NumberFormatException::class.java) {
            HistoricalLocation.fromString("家,地址,not-a-number,114.05")
        }
    }
}
