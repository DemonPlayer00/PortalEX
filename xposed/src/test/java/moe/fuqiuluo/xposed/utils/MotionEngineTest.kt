package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 系统侧推进引擎的**行为契约**（纯 JVM，不碰 Android）。
 *
 * 这里钉的是迁移后最容易悄悄坏掉的三件事：
 *  1. **谁缩放**：位移 = 速度 × 倍率 × Δt —— 倍率 0.5 就必须只走一半
 *     （缩放搬错地方、或 App 又插一手缩放，都会在这里露出来）；
 *  2. **路线推进的几何**：沿折线按弧长插值、朝向取切线、末拍截断到终点；
 *  3. **播完的语义**：一次性 completed、重播从头开始、没有路线就不许播。
 */
class MotionEngineTest {

    /** 正北方向 100m ≈ 0.000899°；用四个点造一条 ~300m 的直线路径 */
    private fun straightPath(points: Int = 4, stepDeg: Double = 0.000899): Pair<DoubleArray, DoubleArray> {
        val lat = DoubleArray(points) { 30.0 + it * stepDeg }
        val lon = DoubleArray(points) { 120.0 }
        return Pair(lat, lon)
    }

    @Before
    fun reset() {
        MotionEngine.reset()
    }

    @Test
    fun routeDistance_isTheSumOfSegments() {
        val (lat, lon) = straightPath()
        MotionEngine.setRoute(lat, lon)
        // 3 段 × ~100m
        assertEquals(300.0, MotionEngine.distance(), 2.0)
        assertEquals(4, MotionEngine.status().points)
    }

    @Test
    fun firstBeat_snapsToRouteStart_withoutCountingDistance() {
        val (lat, lon) = straightPath()
        MotionEngine.setRoute(lat, lon)
        assertTrue(MotionEngine.setPlaying(true))
        val step = MotionEngine.beat(0.05, 3.0, 1.0, 0.0, 0.0)
        assertEquals(lat[0], step.lat, 1e-9)
        assertEquals(lon[0], step.lon, 1e-9)
        // 对齐不算位移 —— 否则一开跑就凭空扣一拍体力
        assertEquals(0.0, step.meters, 1e-9)
        assertEquals(0.0, MotionEngine.travelled(), 1e-9)
    }

    @Test
    fun travelled_isSpeedTickRate_afterSnap() {
        val (lat, lon) = straightPath()
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        MotionEngine.beat(0.05, 3.0, 1.0, 0.0, 0.0)   // 对齐拍
        var moved = 0.0
        repeat(100) {
            moved += MotionEngine.beat(0.05, 3.0, 1.0, 0.0, 0.0).meters
        }
        // 100 拍 × 50ms × 3.0m/s = 15m
        assertEquals(15.0, moved, 0.05)
        assertEquals(15.0, MotionEngine.travelled(), 0.05)
    }

    /** **缩放点唯一**：倍率直接乘在推进量上，没有第二处缩放 */
    @Test
    fun fatigueMultiplier_scalesTheAdvance() {
        val (lat, lon) = straightPath()
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        MotionEngine.beat(0.05, 3.0, 0.5, 0.0, 0.0)
        var moved = 0.0
        repeat(100) { moved += MotionEngine.beat(0.05, 3.0, 0.5, 0.0, 0.0).meters }
        assertEquals(7.5, moved, 0.05)   // = 15m 的一半
    }

    @Test
    fun tangentBearing_pointsAlongTheRoute() {
        val (lat, lon) = straightPath()
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        val first = MotionEngine.beat(0.05, 3.0, 1.0, 0.0, 0.0)
        // 正北向的路线：切线方位 ≈ 0°（不进位到 360）
        val bearing = first.bearing!!
        assertTrue("朝向应接近正北，实际 $bearing", bearing < 1.0 || bearing > 359.0)
    }

    @Test
    fun routeCompletion_stopsPlaying_andIsReportedOnce() {
        val (lat, lon) = straightPath(points = 3, stepDeg = 0.000899)  // ~200m
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        var guard = 0
        while (MotionEngine.status().playing && guard++ < 10_000) {
            MotionEngine.beat(0.5, 3.0, 1.0, 0.0, 0.0)   // 1.5m/拍
        }
        val st = MotionEngine.status()
        assertFalse("走到终点后应自动停播", st.playing)
        assertEquals(st.distanceMeters, st.travelledMeters, 1e-6)
        assertTrue(MotionEngine.takeCompleted())
        // 一次性：读过就清
        assertFalse(MotionEngine.takeCompleted())
        assertEquals(lat.last(), MotionEngine.beat(0.05, 3.0, 1.0, lat.last(), lon.last()).lat, 1e-9)
    }

    /**
     * 两个语义分得很清（与旧 App 实现一致）：
     *  · 中途停播再播 ⇒ **接着走**（不是从头再来，否则每次暂停都白跑）；
     *  · 播完之后再播 ⇒ 从头开始（否则第二次点播放什么都不会发生）。
     */
    @Test
    fun stopAndPlay_resumes_butPlayAfterCompletion_restarts() {
        val (lat, lon) = straightPath(points = 3)   // ~200m
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        repeat(50) { MotionEngine.beat(0.5, 3.0, 1.0, 0.0, 0.0) }   // 75m
        val half = MotionEngine.travelled()
        assertTrue(half > 0.0)
        MotionEngine.setPlaying(false)
        MotionEngine.setPlaying(true)
        assertEquals("停播再播应接着走", half, MotionEngine.travelled(), 1e-9)

        var guard = 0
        while (MotionEngine.status().playing && guard++ < 10_000) {
            MotionEngine.beat(0.5, 3.0, 1.0, 0.0, 0.0)
        }
        assertTrue(MotionEngine.takeCompleted())
        MotionEngine.setPlaying(true)
        assertEquals("播完之后再播应从头开始", 0.0, MotionEngine.travelled(), 1e-9)
    }

    @Test
    fun noRoute_meansNoPlayback() {
        assertFalse(MotionEngine.setPlaying(true))
        // 单点/空路线视为清空
        assertEquals(0, MotionEngine.setRoute(doubleArrayOf(30.0), doubleArrayOf(120.0)))
        assertFalse(MotionEngine.setPlaying(true))
    }

    @Test
    fun rocker_movesAlongItsBearing() {
        MotionEngine.setRocker(active = true, bearing = 90.0)   // 正东
        val start = Pair(30.0, 120.0)
        var lat = start.first
        var lon = start.second
        repeat(10) {
            val step = MotionEngine.beat(0.05, 4.0, 1.0, lat, lon)
            lat = step.lat
            lon = step.lon
        }
        // 10 拍 × 50ms × 4m/s = 2m，正东 ⇒ 纬度几乎不变、经度变大
        assertEquals(start.first, lat, 1e-9)
        assertTrue("应向东移动，实际经度 $lon", lon > start.second)
        val moved = WorldMath.haversine(start.first, start.second, lat, lon)
        assertEquals(2.0, moved, 0.05)
    }

    @Test
    fun idle_doesNotMove() {
        val step = MotionEngine.beat(0.05, 3.0, 1.0, 30.0, 120.0)
        assertFalse(step.moved)
        assertEquals(0.0, step.meters, 1e-9)
        assertEquals(MotionEngine.Mode.IDLE, MotionEngine.status().mode)
    }

    @Test
    fun rockerIsSuppressed_whileRouteIsPlaying() {
        val (lat, lon) = straightPath()
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setRocker(active = true, bearing = 180.0)
        MotionEngine.setPlaying(true)
        MotionEngine.beat(0.05, 3.0, 1.0, lat[0], lon[0])
        val step = MotionEngine.beat(0.05, 3.0, 1.0, lat[0], lon[0])
        // 自动播放中方向由路线切线决定，摇杆不得抢占（旧实现也是这条口径）
        assertEquals("经度不应被摇杆的 180° 带偏", lon[0], step.lon, 1e-9)
        assertTrue("应沿路线（正北）推进，实际纬度 ${step.lat}", step.lat > lat[0])
    }
}
