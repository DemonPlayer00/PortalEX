package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaminaWireTest {
    @Test
    fun `字段数与上线长度一致`() {
        val c = StaminaConfig()
        assertEquals(4, StaminaConfig.WIRE_SIZE)
        assertEquals(StaminaConfig.WIRE_SIZE, c.toWire().size)
    }

    @Test
    fun `往返无损`() {
        val c = StaminaConfig(
            enabled = true,
            criticalSpeedMps = 2.9,
            dPrimeMeters = 150.0,
            recoveryTauSec = 376.0,
        ).sanitized()
        val back = StaminaConfig.fromWire(c.toWire())!!
        assertEquals(c.enabled, back.enabled)
        assertEquals(c.criticalSpeedMps, back.criticalSpeedMps, 1e-4)
        assertEquals(c.dPrimeMeters, back.dPrimeMeters, 1e-4)
        assertEquals(c.recoveryTauSec, back.recoveryTauSec, 1e-3)
    }

    @Test
    fun `长度不符一律拒绝`() {
        assertNull(StaminaConfig.fromWire(null))
        assertNull(StaminaConfig.fromWire(FloatArray(3)))
        assertNull(StaminaConfig.fromWire(FloatArray(StaminaConfig.WIRE_SIZE + 1)))
        assertTrue(StaminaConfig.fromWire(FloatArray(StaminaConfig.WIRE_SIZE)) != null)
    }
}
