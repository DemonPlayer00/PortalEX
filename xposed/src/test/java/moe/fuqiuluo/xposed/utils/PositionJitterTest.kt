package moe.fuqiuluo.xposed.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * **保护性抖动**的回归测试：位置注入不能是"恒定坐标"，也不能是逐帧白噪声。
 *
 * 为什么必须有抖动：模拟位置如果每一帧都精确等于配置点，就是一个完美的静止点——
 * 真机 GPS 从不会这样（多径/电离层/时钟误差让它始终在米级游走）。所以
 * [BaseLocationHook.injectLocation] 的每一帧都走 [FakeLoc.jitterLocation]，
 * 由一个 Ornstein-Uhlenbeck 慢游走给出偏移（τ=[POSITION_JITTER_TAU]、
 * σ=[POSITION_JITTER_SIGMA]），并被 `accuracy` 夹在 ±accuracy 内。
 *
 * 本测试钉三件事：**存在、量级合理、随时间相关**（白噪声会破坏"帧间位移与上报速度自洽"）。
 * 注：真机上没法直接量（ColorOS 会打码日志里的坐标），所以量级以 host 测试为准。
 */
class PositionJitterTest {

    private val lat0 = 23.52536
    private val lon0 = 113.6064

    @After
    fun restoreAccuracy() {
        FakeLoc.accuracy = 25.0f
    }

    /** 采样：每 50ms 取一次注入坐标，换算成以米为单位的偏移 */
    private fun sample(n: Int, sleepMs: Long, bound: Double): List<Pair<Double, Double>> {
        val k = 111320.0
        val klon = k * cos(Math.toRadians(lat0))
        val out = ArrayList<Pair<Double, Double>>(n)
        repeat(n) {
            Thread.sleep(sleepMs)
            val (lat, lon) = FakeLoc.jitterLocation(lat0, lon0, bound)
            out.add((lat - lat0) * k to (lon - lon0) * klon)
        }
        return out
    }

    @Test
    fun jitter_exists() {
        val s = sample(50, 40, 25.0)
        val distinct = s.map { "${"%.7f".format(it.first)}" }.toSet().size
        assertTrue("注入坐标必须在抖：去重后只有 $distinct 个值", distinct > 20)
    }

    @Test
    fun jitter_staysWithinAccuracyBound() {
        // 上限就是 accuracy：偏移绝不允许超出
        val bound = 0.20
        val s = sample(40, 30, bound)
        s.forEach { (dn, de) ->
            assertTrue("纬度偏移越界：${dn}m > $bound", abs(dn) <= bound + 1e-6)
            assertTrue("经度偏移越界：${de}m > $bound", abs(de) <= bound + 1e-6)
        }
        // 极小上限也不能退化成正负无穷或 NaN
        val tiny = sample(5, 20, 0.0)
        tiny.forEach { (dn, de) ->
            assertTrue("bound=0 时必须收敛到 0，实测 $dn/$de", abs(dn) < 1e-9 && abs(de) < 1e-9)
        }
    }

    @Test
    fun jitter_hasRealisticStationaryMagnitude() {
        // 静止游走的理论量级：σ√(τ/2) = 0.18×√2.5 ≈ 0.28m；给足余量取 0.02~3m
        val s = sample(80, 40, 25.0)
        val all = s.flatMap { listOf(it.first, it.second) }
        val mean = all.average()
        val sd = sqrt(all.sumOf { (it - mean) * (it - mean) } / all.size)
        assertTrue("抖动标准差应在 0.02~3m（实测 ${"%.3f".format(sd)}m）——过小=坐标恒定，过大=不像 GPS", sd in 0.02..3.0)
    }

    @Test
    fun jitter_isTimeCorrelated_notWhiteNoise() {
        // 相邻采样（≈120ms）的位移，应显著小于远隔采样（≈2.4s）的位移：
        // 白噪声实现两者量级相同；真机 GPS 误差是时间相关的慢游走。
        val s = sample(60, 120, 25.0)
        fun d(a: Pair<Double, Double>, b: Pair<Double, Double>) =
            sqrt((a.first - b.first) * (a.first - b.first) + (a.second - b.second) * (a.second - b.second))
        val near = (1 until s.size).map { d(s[it], s[it - 1]) }.average()
        val far = (20 until s.size).map { d(s[it], s[it - 20]) }.average()
        assertTrue(
            "慢游走应满足 far > near（实测 near=${"%.3f".format(near)}m far=${"%.3f".format(far)}m）",
            far > near * 1.5
        )
        assertEquals("相邻帧位移不可能是 0（那就成了恒定坐标）", true, near > 0.0)
    }
}
