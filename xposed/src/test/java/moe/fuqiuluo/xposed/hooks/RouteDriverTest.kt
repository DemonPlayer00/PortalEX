package moe.fuqiuluo.xposed.hooks

import moe.fuqiuluo.xposed.utils.FakeLoc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteDriver] 的宿主回归测试（推进语义，不起线程）。
 *
 * 盯三件事：
 *  1. **按真实 Δt 推进**：`advance(dt)` 的距离 = `speed × dt`（这是从 App 挪到
 *     system_server 的核心理由，也是修掉"交付速度 −19%"那次的同一口径）；
 *  2. **单次推进有上限**：长冻结后不补出一个大跳变（会被瞬移判定拦下 ⇒ 该帧速度归 0）；
 *  3. 装载校验与终点行为：坏路线拒绝、到终点精确落在末点并置 finished。
 */
class RouteDriverTest {

    private fun dLat(m: Double) = m / 111_320.0

    private fun straightRoute(meters: Double, n: Int = 11): Pair<DoubleArray, DoubleArray> {
        val lat = DoubleArray(n) { dLat(meters * it / (n - 1)) }
        return lat to DoubleArray(n)
    }

    @After
    fun cleanup() {
        RouteDriver.clear()
        FakeLoc.enable = false
        FakeLoc.speed = 3.05
        FakeLoc.latitude = 0.0
        FakeLoc.longitude = 0.0
    }

    @Test
    fun setRoute_rejectsBadInput() {
        assertFalse("空数组", RouteDriver.setRoute(null, null, 100))
        assertFalse("单点", RouteDriver.setRoute(doubleArrayOf(1.0), doubleArrayOf(1.0), 100))
        assertFalse(
            "越界值",
            RouteDriver.setRoute(doubleArrayOf(0.0, 95.0), doubleArrayOf(0.0, 0.0), 100)
        )
        assertEquals(0.0, RouteDriver.total, 0.0)
    }

    @Test
    fun advance_movesBySpeedTimesRealDelta() {
        val (lat, lon) = straightRoute(100.0)
        assertTrue(RouteDriver.setRoute(lat, lon, 100))
        FakeLoc.enable = true
        FakeLoc.speed = 2.0
        val before = FakeLoc.latitude
        // 真实经过 1s ⇒ 前进 2m
        RouteDriver.advance(1000.0)
        val moved = (FakeLoc.latitude - before) * 111_320.0
        assertEquals("1s @2m/s ≈ 2m", 2.0, moved, 0.05)
    }

    @Test
    fun advance_capsSingleStepAfterLongFreeze() {
        val (lat, lon) = straightRoute(1000.0)
        assertTrue(RouteDriver.setRoute(lat, lon, 100))
        FakeLoc.enable = true
        FakeLoc.speed = 3.5
        val before = FakeLoc.latitude
        // 冻结 20s：上限 3s ⇒ 最多前进 ~10.5m（而不是 70m 的大跳变）
        RouteDriver.advance(20_000.0)
        val moved = (FakeLoc.latitude - before) * 111_320.0
        assertTrue("单次推进应被上限截断，实测 ${"%.1f".format(moved)}m", moved in 9.0..12.0)
    }

    @Test
    fun advance_finishesExactlyOnLastPointAndStops() {
        val (lat, lon) = straightRoute(50.0)
        assertTrue(RouteDriver.setRoute(lat, lon, 100))
        FakeLoc.enable = true
        FakeLoc.speed = 3.5
        var guard = 0
        while (RouteDriver.advance(1000.0) && guard++ < 100) { /* 跑到终点 */ }
        assertTrue("应标记 finished", RouteDriver.isFinished)
        assertFalse("终点的下一次 advance 应返回 false（已停）", RouteDriver.advance(1000.0))
        assertEquals("必须精确落在末点", lat.last(), FakeLoc.latitude, 1e-9)
        assertEquals("路线总长应保持不变", 50.0, RouteDriver.total, 0.5)
    }
}
