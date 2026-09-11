package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldMath] 的纯函数测试 —— 这些逻辑以前埋在 594 行的 FakeLoc 里、**一条测试都没有**，
 * 而它们直接决定"位移/速度推算"和"卫星快照"的正确性（多进程一致性依赖后者）。
 */
class WorldMathTest {

    @Test
    fun haversine_samePointIsZero() {
        assertEquals(0.0, WorldMath.haversine(23.5, 113.6, 23.5, 113.6), 1e-9)
    }

    @Test
    fun haversine_oneDegreeOfLatitudeIsAbout111Km() {
        // 1° 纬度 ≈ 111.19 km（球面近似），容差取 0.2%
        val d = WorldMath.haversine(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 250.0)
    }

    @Test
    fun haversine_isSymmetric() {
        val a = WorldMath.haversine(22.5432, 114.0579, 39.9834, 116.3229)
        val b = WorldMath.haversine(39.9834, 116.3229, 22.5432, 114.0579)
        assertEquals(a, b, 1e-6)
        assertTrue("京深距离量级应合理：$a", a > 1_900_000 && a < 2_000_000)
    }

    @Test
    fun calculateBearing_cardinalDirections() {
        assertEquals(0.0, WorldMath.calculateBearing(0.0, 0.0, 1.0, 0.0), 1e-6)     // 正北
        assertEquals(90.0, WorldMath.calculateBearing(0.0, 0.0, 0.0, 1.0), 1e-6)    // 正东
        assertEquals(180.0, WorldMath.calculateBearing(1.0, 0.0, 0.0, 0.0), 1e-6)   // 正南
        assertEquals(270.0, WorldMath.calculateBearing(0.0, 1.0, 0.0, 0.0), 1e-6)   // 正西
    }

    @Test
    fun calculateBearing_alwaysInZeroToThreeSixty() {
        // 每行 = (起点纬度, 起点经度, 终点纬度, 终点经度)；刻意覆盖跨极点/跨半球的组合
        val samples = listOf(
            doubleArrayOf(0.0, 0.0, 0.0, -1.0),
            doubleArrayOf(-33.0, -70.5, 39.9, 116.3),
            doubleArrayOf(89.0, 0.0, -89.0, 180.0),
            doubleArrayOf(23.5, 113.6, 23.5, 113.6),
        )
        for (s in samples) {
            val b = WorldMath.calculateBearing(s[0], s[1], s[2], s[3])
            assertTrue("方位角必须落在 [0,360)：$b", b >= 0.0 && b < 360.0)
        }
    }

    @Test
    fun gnssSnapshot_isDeterministicPerBucket() {
        // 这条是"多进程拿到同一份卫星数据"的根据：同桶同参数必得同一结果
        val a = WorldMath.gnssSnapshotForBucket(1_700_000_000L, 12, 35)
        val b = WorldMath.gnssSnapshotForBucket(1_700_000_000L, 12, 35)
        assertEquals(a.svCount, b.svCount)
        assertArrayEquals(a.cn0s, b.cn0s, 0.0)
    }

    @Test
    fun gnssSnapshot_respectsBoundsAndShape() {
        for (bucket in 1_699_999_990L..1_700_000_010L) {
            val s = WorldMath.gnssSnapshotForBucket(bucket, 12, 35)
            assertTrue("svCount 越界：${s.svCount}", s.svCount in 12..35)
            assertEquals("cn0 个数必须等于卫星数", s.svCount, s.cn0s.size)
            for (cn0 in s.cn0s) assertTrue("cn0 越界：$cn0", cn0 >= 24.0 && cn0 <= 45.0)
        }
    }

    @Test
    fun gnssSnapshot_differentBucketsDiffer() {
        // 相邻秒通常不同（不是硬保证，但连续 20 秒内全同说明随机源坏了）
        val counts = (1_700_000_000L until 1_700_000_020L)
            .map { WorldMath.gnssSnapshotForBucket(it, 12, 35).svCount }
            .toSet()
        assertTrue("20 个桶的卫星数全都一样，随机源可疑：$counts", counts.size > 1)
    }

    @Test
    fun gnssSnapshot_minFloorIsRespected() {
        // 下限 = "模拟最少卫星数量"设置项；上限比下限小时不能让 Random 抛异常
        val s = WorldMath.gnssSnapshotForBucket(12345L, 12, 12)
        assertEquals(12, s.svCount)
        assertNotEquals(0, s.cn0s.size)
    }
}
