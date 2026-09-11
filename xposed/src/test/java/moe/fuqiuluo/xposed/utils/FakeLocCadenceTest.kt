package moe.fuqiuluo.xposed.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FakeLoc.cadenceForSpeed] 与 [FakeLoc.cadenceScale] 的宿主回归测试。
 *
 * 口径（FakeLoc.kt 第 158-168、210-214 行）：
 *  - 基础公式 `((60 + 30*speed) * 1.15).toInt().coerceIn(60, 220)`；
 *  - `cadenceScale == 1.0` ⇒ **逐位**返回基础值（既有行为不变）；
 *  - 非 1 时**在基础值 clamp 之后**乘倍率，再 clamp 进 30..300。
 *
 * `cadenceScale` 是全局可变状态 ⇒ 每个用例前后都写回 1.0，用例之间不互相污染。
 */
class FakeLocCadenceTest {

    @Before
    fun resetScale() {
        FakeLoc.cadenceScale = 1.0
    }

    @After
    fun restoreScale() {
        FakeLoc.cadenceScale = 1.0
    }

    /** 与生产同式的基础公式，仅用于「逐位相等」的对照。 */
    private fun baseFormula(speed: Double): Int =
        ((60.0 + 30.0 * speed) * 1.15).toInt().coerceIn(60, 220)

    private val sampleSpeeds = listOf(0.0, 0.5, 1.0, 3.05, 5.0, 10.0)

    @Test
    fun scaleOne_matchesBaseFormula_bitForBit() {
        FakeLoc.cadenceScale = 1.0
        for (s in sampleSpeeds) {
            assertEquals(
                "speed=$s 处未逐位等于基础公式",
                baseFormula(s),
                FakeLoc.cadenceForSpeed(s)
            )
        }
    }

    @Test
    fun scaleOne_matchesHardcodedBaselineValues() {
        // 独立算出的字面量锚点：防止「测试公式与生产公式一起被改错」时测试仍然通过
        FakeLoc.cadenceScale = 1.0
        assertEquals(69, FakeLoc.cadenceForSpeed(0.0))     // 60*1.15 = 69.0
        assertEquals(86, FakeLoc.cadenceForSpeed(0.5))     // 86.25 → 截断
        assertEquals(103, FakeLoc.cadenceForSpeed(1.0))    // 103.4999… → 截断
        assertEquals(174, FakeLoc.cadenceForSpeed(3.05))   // 174.225 → 截断
        assertEquals(220, FakeLoc.cadenceForSpeed(5.0))    // 241.5 → 上钳
        assertEquals(220, FakeLoc.cadenceForSpeed(10.0))   // 414 → 上钳
    }

    @Test
    fun baseFormula_truncatesAndClamps() {
        // 下界：speed=0 已高于 60，故 60 只在负速度下可达
        assertEquals(60, baseFormula(-1.0))
        // 截断（而非四舍五入）：86.25 → 86
        assertEquals(86, baseFormula(0.5))
        // 上界夹紧
        assertEquals(220, baseFormula(100.0))
        assertEquals(220, FakeLoc.cadenceForSpeed(100.0))
    }

    @Test
    fun scaleTwo_staysWithin30To300_andNotBelowBase() {
        FakeLoc.cadenceScale = 2.0
        for (s in sampleSpeeds) {
            val base = baseFormula(s)
            val v = FakeLoc.cadenceForSpeed(s)
            assertTrue("speed=$s: $v 超过 300", v <= 300)
            assertTrue("speed=$s: $v 低于基础值 $base", v >= base)
            assertTrue("speed=$s: $v 低于 30", v >= 30)
        }
        // 上钳位确实会被触达
        assertEquals(300, FakeLoc.cadenceForSpeed(10.0))
        assertEquals(300, FakeLoc.cadenceForSpeed(3.05))     // 174*2 = 348 → 300
        assertEquals(138, FakeLoc.cadenceForSpeed(0.0))      // 69*2 = 138，未触界
    }

    @Test
    fun scalePointTwo_staysWithin30To300_andNotAboveBase() {
        FakeLoc.cadenceScale = 0.2
        for (s in sampleSpeeds) {
            val base = baseFormula(s)
            val v = FakeLoc.cadenceForSpeed(s)
            assertTrue("speed=$s: $v 低于 30", v >= 30)
            assertTrue("speed=$s: $v 高于基础值 $base", v <= base)
            assertTrue("speed=$s: $v 超过 300", v <= 300)
        }
        // 下钳位确实会被触达
        assertEquals(30, FakeLoc.cadenceForSpeed(0.0))       // 69*0.2 = 13.8 → 30
        assertEquals(30, FakeLoc.cadenceForSpeed(1.0))       // 103*0.2 = 20.6 → 30
        assertEquals(35, FakeLoc.cadenceForSpeed(3.05))      // 174*0.2 = 34.8 → 35（四舍五入）
        assertEquals(44, FakeLoc.cadenceForSpeed(10.0))      // 220*0.2 = 44
    }

    @Test
    fun scale_isAppliedAfterBaseClamp_notBefore() {
        // 判别性用例：speed=10 时基础值 414 已被钳到 220。
        // 先钳后乘 ⇒ 110；先乘后钳 ⇒ 207。两者可区分，故此处断言锁死「先钳后乘」。
        FakeLoc.cadenceScale = 0.5
        assertEquals(110, FakeLoc.cadenceForSpeed(10.0))
    }

    @Test
    fun scaleUsesRounding_atHalfStep() {
        // Math.round 而非截断：220*0.25 = 55.0 精确；改用 174*0.5 = 87.0 也精确。
        // 取一个小数部分 ≥ .5 的点：103*0.5 = 51.5 → round 52（截断会是 51）。
        FakeLoc.cadenceScale = 0.5
        assertEquals(52, FakeLoc.cadenceForSpeed(1.0))
    }

    @Test
    fun monotonicNonDecreasingOverSpeedRange_atScaleOne() {
        FakeLoc.cadenceScale = 1.0
        var prev = Int.MIN_VALUE
        var i = 0
        while (i <= 100) {
            val s = i * 0.1
            val v = FakeLoc.cadenceForSpeed(s)
            assertTrue("speed=$s 处出现回落：$prev → $v", v >= prev)
            prev = v
            i++
        }
        assertEquals(220, prev)
    }

    @Test
    fun monotonicNonDecreasingOverSpeedRange_atScaleTwo() {
        FakeLoc.cadenceScale = 2.0
        var prev = Int.MIN_VALUE
        var i = 0
        while (i <= 100) {
            val s = i * 0.1
            val v = FakeLoc.cadenceForSpeed(s)
            assertTrue("speed=$s 处出现回落：$prev → $v", v >= prev)
            prev = v
            i++
        }
        assertEquals(300, prev)
    }

    @Test
    fun defaultScaleIsOne_andOtherScalesDoNotLeakAcrossCases() {
        // @Before 已复位；这里显式验证复位契约本身
        assertEquals(1.0, FakeLoc.cadenceScale, 0.0)
        assertEquals(baseFormula(3.05), FakeLoc.cadenceForSpeed(3.05))

        FakeLoc.cadenceScale = 3.0
        assertEquals(300, FakeLoc.cadenceForSpeed(3.05))     // 174*3 = 522 → 300
        FakeLoc.cadenceScale = 1.0
        assertEquals(baseFormula(3.05), FakeLoc.cadenceForSpeed(3.05))
    }
}
