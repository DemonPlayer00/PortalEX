package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体力参数的**跨进程定序**（App → 模块的 `stamina_config`）单测。
 *
 * 这段代码的危险性在于：顺序错位**不会报错**，只会让参数悄悄对不上（例如把"疲劳时长"
 * 读成"疲劳体力值"）。所以两件事必须钉住：元素个数与字段数一致、往返无损。
 */
class StaminaWireTest {

    @Test
    fun `字段数与上线长度一致`() {
        // 数一遍 data class 的属性（用 toWire 的长度对照声明处的 WIRE_SIZE）
        val c = StaminaConfig()
        assertEquals(StaminaConfig.WIRE_SIZE, c.toWire().size)
        // 12 = enabled, decay, recover, restAt, resumeAt, fatigueSec, transition, walk,
        //      minSpeedFactor, random, ignoreWindow, ignoreSpeed
        assertEquals(12, StaminaConfig.WIRE_SIZE)
    }

    @Test
    fun `往返无损`() {
        val c = StaminaConfig(
            enabled = true, decayPerMinute = 22.0, recoverCoefficient = 6.0,
            restAtPercent = 15.0, resumeAtPercent = 25.0, fatigueSec = 80.0,
            transitionSec = 3.0, walkSpeed = 1.1, minSpeedFactor = 0.75,
            randomPercent = 20.0, moveIgnoreWindowSec = 3.0, moveIgnoreSpeed = 12.0,
        ).sanitized()
        val back = StaminaConfig.fromWire(c.toWire())!!
        /*
         * ⚠️ 不能直接 assertEquals(c, back)：上线是 **Float**（跨进程用 float[] 传），
         * 而模型内部是 Double —— `1.1` 这一类十进制小数存进 32 位必然有 ~1e-7 的相对误差
         * （实测 1.1f = 1.100000023841858）。逐字段给容差才是这条通路的正确期望。
         */
        assertEquals(c.enabled, back.enabled)
        assertEquals(c.decayPerMinute, back.decayPerMinute, 1e-4)
        assertEquals(c.recoverCoefficient, back.recoverCoefficient, 1e-4)
        assertEquals(c.restAtPercent, back.restAtPercent, 1e-4)
        assertEquals(c.resumeAtPercent, back.resumeAtPercent, 1e-4)
        assertEquals(c.fatigueSec, back.fatigueSec, 1e-3)
        assertEquals(c.transitionSec, back.transitionSec, 1e-3)
        assertEquals(c.walkSpeed, back.walkSpeed, 1e-4)
        assertEquals(c.minSpeedFactor, back.minSpeedFactor, 1e-4)
        assertEquals(c.randomPercent, back.randomPercent, 1e-3)
        assertEquals(c.moveIgnoreWindowSec, back.moveIgnoreWindowSec, 1e-3)
        assertEquals(c.moveIgnoreSpeed, back.moveIgnoreSpeed, 1e-3)
    }

    @Test
    fun `长度不符一律拒绝 不读半个配置`() {
        assertNull(StaminaConfig.fromWire(null))
        assertNull(StaminaConfig.fromWire(FloatArray(3)))
        assertNull(StaminaConfig.fromWire(FloatArray(StaminaConfig.WIRE_SIZE + 1)))
        assertTrue(StaminaConfig.fromWire(FloatArray(StaminaConfig.WIRE_SIZE)) != null)
    }
}
