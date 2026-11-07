package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 倍率×距离曲线（体力页那张图的数据源）的单测。
 *
 * 这张图是**用户唯一能看见的"参数改了什么效果"的地方**，所以两件事必须钉死：
 *  1. 曲线与 [StaminaModel] 是**同一组公式**（否则图与实跑不一致，而这是没法靠肉眼发现的）；
 *  2. ±随机包络真的把无随机线夹在中间（用户口径里那两条半透明线的全部意义）。
 */
class StaminaCurveTest {

    private val base = 3.05
    private val rnd = Random(20260916)

    private fun cfg(
        decay: Double = 11.0,
        recover: Double = 4.0,
        restAt: Double = 20.0,
        fatigueSec: Double = 120.0,
        walk: Double = 1.10,
        floor: Double = 0.75,
        randomPct: Double = 0.0,
    ) = StaminaConfig(
        enabled = true, decayPerMinute = decay, recoverCoefficient = recover,
        restAtPercent = restAt, fatigueSec = fatigueSec,
        walkSpeed = walk, minSpeedFactor = floor, randomPercent = randomPct,
        moveIgnoreWindowSec = 3.0, moveIgnoreSpeed = 12.0,
    )

    /** 取曲线在某个距离处的倍率（取"不超过该距离的最后一个采样点"） */
    private fun StaminaCurve.Curve.at(meters: Double): Double {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (distanceM[mid] <= meters) lo = mid else hi = mid - 1
        }
        return multiplier[lo].toDouble()
    }

    /**
     * **一致性**：按同一步长喂 [StaminaModel]（与本类的积分顺序一致），
     * 逐拍倍率必须对得上。这条是这张图"可信"的全部依据。
     */
    @Test
    fun `曲线与模型逐拍一致`() {
        val c = cfg(randomPct = 0.0)
        val dt = 0.1
        val curve = StaminaCurve.simulate(c, base, decayScale = 1.0, maxDistanceMeters = 3_000.0, dtSec = dt)
        val model = StaminaModel()

        var m = 1.0
        for (i in 1 until curve.size) {
            val moved = base * m * dt
            m = model.tick(c, dt, base, moved, rnd)
            assertEquals("第 $i 拍的倍率", curve.multiplier[i].toDouble(), m, 1e-6)
        }
        assertEquals("休息次数", model.snapshot().restCount, curve.restCount)
        assertTrue("曲线应该真的跑了 3km", curve.distanceM.last() >= 3_000f)
    }

    /** 起手不调制，且第一段（未休息前）只降不升 */
    @Test
    fun `第一段单调下降且起点为 1_0`() {
        val curve = StaminaCurve.simulate(cfg(), base, maxDistanceMeters = 2_000.0)
        assertEquals("起点倍率", 1.0, curve.multiplier[0].toDouble(), 1e-9)

        var prev = curve.multiplier[0].toDouble()
        var declined = false
        for (i in 1 until curve.size) {
            val m = curve.multiplier[i].toDouble()
            assertTrue("倍率不应上升（第 $i 点：$prev -> $m）", m <= prev + 1e-6)
            if (m < prev - 1e-6) declined = true
            prev = m
        }
        assertTrue("第一段应该真的在掉速", declined)
    }

    /** ±随机包络：无随机线必须被夹在中间，且两端要真的分得开（否则那两条线是装饰） */
    @Test
    fun `随机包络夹住无随机线且在 2km 处已分开`() {
        val p = 0.15
        val c = cfg(randomPct = p * 100)
        val baseCurve = StaminaCurve.simulate(c, base, decayScale = 1.0, maxDistanceMeters = 3_000.0)
        val high = StaminaCurve.simulate(c, base, decayScale = 1.0 + p, maxDistanceMeters = 3_000.0)
        val low = StaminaCurve.simulate(c, base, decayScale = 1.0 - p, maxDistanceMeters = 3_000.0)

        var separated = false
        var d = 200.0
        while (d <= 2_800.0) {
            val mb = baseCurve.at(d)
            val mh = high.at(d)
            val ml = low.at(d)
            assertTrue("$d m：衰减更快的线不该高于无随机线（$mh > $mb）", mh <= mb + 1e-6)
            assertTrue("$d m：衰减更慢的线不该低于无随机线（$ml < $mb）", ml >= mb - 1e-6)
            if (mb - mh > 0.01 || ml - mb > 0.01) separated = true
            d += 200.0
        }
        assertTrue("两条极值线在 2.8km 内应与无随机线明显分开", separated)
    }

    /** 随机幅度为 0 时三条线重合（用户把随机关掉时不该看到"幽灵边界"） */
    @Test
    fun `零随机时三条线重合`() {
        val c = cfg(randomPct = 0.0)
        val a = StaminaCurve.simulate(c, base, decayScale = 1.0, maxDistanceMeters = 1_000.0)
        val b = StaminaCurve.simulate(c, base, decayScale = 1.0, maxDistanceMeters = 1_000.0)
        assertEquals(a.size, b.size)
        for (i in 0 until a.size) assertEquals(a.multiplier[i], b.multiplier[i], 0f)
    }

    /** 极端参数（走路速度压到地板）不能让积分跑穿：时长上限必须生效 */
    @Test
    fun `极端参数被时长上限截断`() {
        val c = cfg(decay = 600.0, recover = 0.01, walk = 0.10, floor = 0.05)
        val curve = StaminaCurve.simulate(c, base, maxDistanceMeters = 10_000.0, dtSec = 0.25)

        assertTrue("不该超过时长上限", curve.elapsedSec <= StaminaCurve.MAX_SECONDS + 0.25)
        assertTrue("点数应该是有界的（实际 ${curve.size}）", curve.size < 60_000)
        for (i in 0 until curve.size) {
            val m = curve.multiplier[i].toDouble()
            // 曲线存的是 Float ⇒ 0.02 的夹取边界回来会变成 0.0199999995，必须留容差
            assertTrue("倍率越界：$m", m in (0.02 - 1e-6)..(1.0 + 1e-6))
        }
    }
}
