package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusedRewriteTest {

    @Test
    fun defaultsAreNeutral() {
        val o = FusedOverrides()
        assertEquals("fused", o.provider)
        assertEquals(0f, o.accuracy)
        assertEquals(0f, o.verticalAccuracy)
        assertTrue(o.speed.isNaN())
        assertTrue(o.bearing.isNaN())
        assertTrue(o.altitude.isNaN())
        assertEquals(0L, o.locationAgeMs)
        assertTrue(o.keepExtras)
    }

    @Test
    fun fusedRewriteIsEnabledByDefaultAndCanBeDisabled() {
        val old = FusedRewrite.enabled
        try {
            FusedRewrite.enabled = true
            assertTrue(FusedRewrite.enabled)
            FusedRewrite.enabled = false
            assertFalse(FusedRewrite.enabled)
        } finally {
            FusedRewrite.enabled = old
        }
    }
}
