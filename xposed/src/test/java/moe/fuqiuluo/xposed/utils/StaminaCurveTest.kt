package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaminaCurveTest {
    private val base = 3.05
    private val cfg = StaminaConfig(enabled = true, criticalSpeedMps = 2.90, dPrimeMeters = 150.0, recoveryTauSec = 376.0)

    @Test
    fun `曲线纵轴是速度倍率 且体力下降`() {
        val c = StaminaCurve.simulate(cfg, base, maxDistanceMeters = 3_000.0, dtSec = 0.25)
        assertEquals(1.0, c.multiplier[0].toDouble(), 1e-6)
        assertTrue("3km 内应消耗 D′", c.staminaEndPercent < 100.0)
        assertTrue("低体力时速度应下降", c.multiplier.last() < 1.0)
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
