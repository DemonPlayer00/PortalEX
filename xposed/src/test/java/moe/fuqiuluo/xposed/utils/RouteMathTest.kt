package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteMath] 的宿主回归测试：路线几何（弧长 → 位置 / 切线朝向）。
 *
 * 这层是 2026-09-12 那次架构调整引入的：路线推进从 App 挪到 system_server 后，
 * App 只交付**展开后的折线**，位置/朝向全部由这里插值 —— 算错了就是"位置偏离路线、
 * 朝向乱转"，而且只在真机上看得到，所以必须有本层回归。
 */
class RouteMathTest {

    /** 纬度方向 n 米 ≈ 多少度 */
    private fun dLat(m: Double) = m / 111_320.0

    private fun lineNorth(meters: Double, n: Int = 11): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val lat = DoubleArray(n) { dLat(meters * it / (n - 1)) }
        val lon = DoubleArray(n)
        return Triple(lat, lon, RouteMath.cumulative(lat, lon))
    }

    @Test
    fun cumulative_isZeroBasedAndMonotonic() {
        val (_, _, cum) = lineNorth(100.0)
        assertEquals(0.0, cum[0], 1e-9)
        assertTrue("累计距离必须单调不减", (1 until cum.size).all { cum[it] >= cum[it - 1] })
        assertEquals("100m 直线总长", 100.0, cum.last(), 0.2)
    }

    @Test
    fun pointAt_interpolatesAndClamps() {
        val (lat, lon, cum) = lineNorth(100.0)
        // 起点 / 终点
        assertEquals(lat[0], RouteMath.pointAt(lat, lon, cum, 0.0).first, 1e-9)
        assertEquals(lat.last(), RouteMath.pointAt(lat, lon, cum, 100.0).first, 1e-9)
        // 越界夹取（不允许跑到路线外）
        assertEquals(lat[0], RouteMath.pointAt(lat, lon, cum, -50.0).first, 1e-9)
        assertEquals(lat.last(), RouteMath.pointAt(lat, lon, cum, 999.0).first, 1e-9)
        // 中间：50m 处的纬度应 ≈ 半程
        assertEquals(dLat(50.0), RouteMath.pointAt(lat, lon, cum, 50.0).first, 1e-6)
    }

    @Test
    fun pointAt_handlesDegenerateInputs() {
        assertEquals(0.0, RouteMath.pointAt(DoubleArray(0), DoubleArray(0), DoubleArray(0), 10.0).first, 0.0)
        val single = RouteMath.pointAt(doubleArrayOf(1.5), doubleArrayOf(2.5), doubleArrayOf(0.0), 10.0)
        assertEquals(1.5, single.first, 0.0)
        assertEquals(2.5, single.second, 0.0)
    }

    @Test
    fun bearingAt_followsDirectionAndFallsBackAtEnd() {
        // 正北直线：朝向 ≈ 0°
        val (lat, lon, cum) = lineNorth(100.0)
        assertEquals(0.0, RouteMath.bearingAt(lat, lon, cum, 10.0), 0.5)
        // 终点处前视退化 ⇒ 回退到最后一段方向（仍是正北，绝不返回 0 以外的随机值）
        assertEquals(0.0, RouteMath.bearingAt(lat, lon, cum, 100.0), 0.5)

        // 正东直线：朝向 ≈ 90°
        val eastLat = DoubleArray(11) { 23.5 }
        val eastLon = DoubleArray(11) { 113.6 + it * (100.0 / 10) / (111_320.0 * Math.cos(Math.toRadians(23.5))) }
        val eastCum = RouteMath.cumulative(eastLat, eastLon)
        assertEquals(90.0, RouteMath.bearingAt(eastLat, eastLon, eastCum, 20.0), 1.0)
    }

    @Test
    fun bearingAt_skipsDuplicatePoints() {
        // 起手就是重复点：不能因为 atan2(0,0) 给出 0° 而把朝向锁死
        val lat = doubleArrayOf(23.5, 23.5, 23.5 + dLat(20.0))
        val lon = doubleArrayOf(113.6, 113.6, 113.6)
        val cum = RouteMath.cumulative(lat, lon)
        assertEquals(0.0, RouteMath.bearingAt(lat, lon, cum, 0.0), 0.5)
    }
}
