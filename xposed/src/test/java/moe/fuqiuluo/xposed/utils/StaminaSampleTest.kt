package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 「生成」页的数据源（[StaminaCurve.sample]）单测。
 *
 * 这一页的全部意义是"和实际运行逻辑一致"，所以**不能用另一套公式去实现**，
 * 而要直接驱动 [StaminaModel]。这里就把"它确实等于逐拍跑一遍"钉死：
 * 同一个种子、同一步长手工重跑一次 [StaminaModel]，两条曲线必须逐点相同。
 */
class StaminaSampleTest {

    private val base = 3.05

    private fun cfg(randomPct: Double = 15.0) = StaminaConfig(
        enabled = true, decayPerMinute = 11.0, recoverCoefficient = 4.0,
        restAtPercent = 20.0, resumeAtPercent = 30.0, restSpeedFactor = 0.25,
        restSecondsCoefficient = 1.0, transitionSec = 3.0, walkSpeed = 1.10,
        minSpeedFactor = 0.75, randomPercent = randomPct,
        moveIgnoreWindowSec = 3.0, moveIgnoreSpeed = 12.0,
    )

    /** 生成页必须**就是**真实引擎：手工按运动循环的调用顺序重跑一遍，逐点比对 */
    @Test
    fun `生成曲线等于手工驱动的 StaminaModel`() {
        val c = cfg(randomPct = 15.0)          // 带随机，才谈得上"同种子同结果"
        val dt = 0.1
        val curve = StaminaCurve.sample(c, base, maxDistanceMeters = 2_000.0, dtSec = dt, random = Random(42))

        val model = StaminaModel()
        val rnd = Random(42)
        var multiplier = model.snapshot().speedScale
        var distance = 0.0
        var i = 1
        while (distance < 2_000.0) {
            val moved = base * multiplier * dt
            multiplier = model.tick(c, dt, base, moved, rnd)
            distance += moved
            // 曲线里存的是 Float ⇒ 容差要按 Float 精度给（1e-9 会比 Float 的 ε 还小，必然假红）
            assertEquals("第 $i 点的倍率", curve.multiplier[i].toDouble(), multiplier, 1e-6)
            assertEquals("第 $i 点的距离", curve.distanceM[i].toDouble(), distance, 1e-3)
            i++
        }
        assertEquals("点数应完全一致", i, curve.size)
        assertEquals("疲劳次数", model.snapshot().restCount, curve.restCount)
        assertEquals("疲劳累计时长", model.snapshot().restTotalSec, curve.restTotalSec, 1e-9)
    }

    /** 点一次"生成"就该换一条：不同随机源 ⇒ 曲线必须不同 */
    @Test
    fun `每次生成都是一条新曲线`() {
        val c = cfg()
        val a = StaminaCurve.sample(c, base, maxDistanceMeters = 2_000.0, dtSec = 0.1, random = Random(1))
        val b = StaminaCurve.sample(c, base, maxDistanceMeters = 2_000.0, dtSec = 0.1, random = Random(2))
        val n = minOf(a.size, b.size)
        val diff = (0 until n).maxOf { abs(a.multiplier[it] - b.multiplier[it]).toDouble() }
        println("两次生成的最大差异 = %.4f（点数 %d / %d）".format(diff, a.size, b.size))
        assertTrue("两次生成应该有可观察的差异（实际 %.4f）".format(diff), diff > 5e-3)
    }

    /** 生成页与理论页必须看得出区别：同一距离处，实测曲线会偏离解析曲线 */
    @Test
    fun `生成曲线与理论曲线不是同一条`() {
        val c = cfg()
        val theory = StaminaCurve.simulate(c, base, maxDistanceMeters = 2_000.0)

        fun at(curve: StaminaCurve.Curve, meters: Double): Double {
            var lo = 0
            var hi = curve.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (curve.distanceM[mid] <= meters) lo = mid else hi = mid - 1
            }
            return curve.multiplier[lo].toDouble()
        }

        // ⚠️ 不能只看一条生成曲线：单次抽到的衰减偏移可能很小（我就先踩过，抽到 0.0004 的偏差）。
        //    这里跨多个随机源取最大偏差，测的是"生成页会偏离理论页"这件事本身。
        val diffs = (1..12).map { seed ->
            val sample = StaminaCurve.sample(c, base, maxDistanceMeters = 2_000.0, dtSec = 0.1, random = Random(seed))
            (200..1_800 step 200).maxOf { abs(at(theory, it.toDouble()) - at(sample, it.toDouble())) }
        }
        println("12 次生成的偏差：${diffs.map { "%.3f".format(it) }}")
        assertTrue("总有几次生成会明显偏离理论曲线（最大 %.4f）".format(diffs.max()), diffs.max() > 5e-3)
    }
}
