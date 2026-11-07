package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaminaModelTest {
    private val base = 3.05
    private val cfg = StaminaConfig(enabled = true, criticalSpeedMps = 2.90, dPrimeMeters = 150.0, recoveryTauSec = 376.0)

    @Test
    fun `高于 CS 时按 D-prime 公式消耗`() {
        val m = StaminaModel()
        m.tick(cfg, 60.0, base, base * 60.0)
        // 100*(3.05-2.90)/150 * 60 = 6 点
        assertEquals(94.0, m.snapshot().staminaPercent, 1e-6)
        assertEquals(1.0, m.snapshot().speedScale, 0.0)
    }

    @Test
    fun `低于 CS 时指数恢复`() {
        val m = StaminaModel()
        m.tick(cfg, 60.0, base, base * 60.0)
        m.tick(cfg, 188.0, 2.0, 2.0 * 188.0)
        // 94 -> 100-(6)e^-0.5 ≈ 96.36（Euler 一步为 97，允许步长误差）
        assertTrue(m.snapshot().staminaPercent in 96.0..98.0)
        assertEquals(1.0, m.snapshot().speedScale, 0.0)
    }

    @Test
    fun `实际配速低于 CS 时恢复 即使名义速度仍高`() {
        val m = StaminaModel()
        m.tick(cfg, 600.0, base, base * 600.0)
        val depleted = m.snapshot().staminaPercent
        // 名义 base 仍为 3.05，但本拍只走了 2.0 m/s 的实际位移
        m.tick(cfg, 60.0, base, 2.0 * 60.0)
        assertTrue("低实际配速应恢复：%.2f -> %.2f".format(
            depleted, m.snapshot().staminaPercent
        ), m.snapshot().staminaPercent > depleted)
    }

    @Test
    fun `D-prime 余额被夹在 0 到 100`() {
        val m = StaminaModel()
        m.tick(cfg, 10_000.0, base, base * 10_000.0)
        assertEquals(0.0, m.snapshot().staminaPercent, 0.0)
        m.reset()
        m.tick(cfg, 10_000.0, 2.0, 0.0)
        assertEquals(100.0, m.snapshot().staminaPercent, 1e-9)
    }

    @Test
    fun `配置只保留公式参数并做夹取`() {
        val wild = StaminaConfig(
            enabled = true, criticalSpeedMps = -5.0,
            dPrimeMeters = -1.0, recoveryTauSec = -1.0,
        ).sanitized()
        assertTrue(wild.criticalSpeedMps in 0.5..10.0)
        assertTrue(wild.dPrimeMeters >= 10.0)
        assertTrue(wild.recoveryTauSec >= 10.0)
    }
}
