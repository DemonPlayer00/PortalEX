package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaminaCurveTest {
    private val base = 3.05
    private val cfg = StaminaConfig(enabled = true, criticalSpeedMps = 2.90, dPrimeMeters = 150.0, recoveryTauSec = 376.0)

    @Test
    fun `曲线纵轴是 D-prime 余额且单调下降`() {
        val c = StaminaCurve.simulate(cfg, base, maxDistanceMeters = 3_000.0, dtSec = 0.25)
        assertEquals(1.0, c.multiplier[0].toDouble(), 1e-6)
        assertTrue("3km 内应明显消耗", c.multiplier.last() < 0.5)
        for (i in 1 until c.size) {
            assertTrue("余额不应上升", c.multiplier[i] <= c.multiplier[i - 1] + 1e-6)
        }
    }

    @Test
    fun `低于 CS 时余额恢复`() {
        val low = StaminaConfig(enabled = true, criticalSpeedMps = 2.90, dPrimeMeters = 150.0, recoveryTauSec = 60.0)
        val c = StaminaCurve.simulate(low, 2.0, maxDistanceMeters = 1_000.0, dtSec = 0.25)
        assertEquals(1.0, c.staminaEndPercent / 100.0, 1e-6)
    }

    @Test
    fun `sample 与 simulate 同一公式`() {
        val a = StaminaCurve.simulate(cfg, base, maxDistanceMeters = 1_000.0, dtSec = 0.1)
        val b = StaminaCurve.sample(cfg, base, maxDistanceMeters = 1_000.0, dtSec = 0.1)
        assertEquals(a.size, b.size)
        for (i in 0 until a.size) assertEquals(a.multiplier[i], b.multiplier[i], 1e-6f)
    }
}
