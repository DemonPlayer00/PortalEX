package moe.fuqiuluo.xposed.utils

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * [SensorNoise] 的宿主回归测试：索引契约 / sanitize / parseItem / withItem / encode-decode / formatItem。
 *
 * 断言全部取自 SensorNoise.kt 与 virtual_world.h 的真实口径（σ 行非负、零偏槽可负且下限为 −max、
 * 单项上限 = 该行 max、超长截断、NaN 与损坏串退回默认）。
 */
class SensorNoiseTest {

    private lateinit var savedLocale: Locale

    @Before
    fun pinLocale() {
        // format/formatItem 走 String.format(...)（使用默认 Locale）——测试里钉死 US，
        // 使「小数位 = 该行 digits」的断言落在逗号/点号上仍然确定。
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    // ---------------------------------------------------------------- 索引契约

    @Test
    fun count_isTwenty() {
        assertEquals(20, SensorNoise.COUNT)
        assertEquals(SensorNoise.COUNT, SensorNoise.DEFAULTS.size)
    }

    @Test
    fun slotBases_matchNativeLayout() {
        assertEquals(0, SensorNoise.GYRO)
        assertEquals(3, SensorNoise.GYRO_BIAS)
        assertEquals(6, SensorNoise.ACCEL)
        assertEquals(9, SensorNoise.GRAVITY)
        assertEquals(12, SensorNoise.LINEAR)
        assertEquals(15, SensorNoise.MAG)
        assertEquals(18, SensorNoise.ORIENT)
        assertEquals(19, SensorNoise.ROTVEC)
    }

    @Test
    fun items_coverEverySlotExactlyOnceAndInOrder() {
        val starts = SensorNoise.ITEMS.map { it.start }
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 19), starts)

        var expectedNext = 0
        for (item in SensorNoise.ITEMS) {
            assertEquals("行「${item.title}」起点不连续", expectedNext, item.start)
            expectedNext = item.end + 1
        }
        assertEquals("ITEMS 必须恰好铺满 0..COUNT-1", SensorNoise.COUNT, expectedNext)

        val hits = IntArray(SensorNoise.COUNT)
        for (item in SensorNoise.ITEMS) for (i in item.start..item.end) hits[i]++
        assertArrayEquals("槽位被重复覆盖或有空洞", IntArray(SensorNoise.COUNT) { 1 }, hits)

        // 行形状：前 6 行三轴，末 2 行标量
        assertEquals(
            listOf(3, 3, 3, 3, 3, 3, 1, 1),
            SensorNoise.ITEMS.map { it.size }
        )
    }

    @Test
    fun itemsStartMatchNamedConstants() {
        assertRowStart(SensorNoise.GYRO, "陀螺仪噪声")
        assertRowStart(SensorNoise.GYRO_BIAS, "陀螺仪零偏")
        assertRowStart(SensorNoise.ACCEL, "加速度计噪声")
        assertRowStart(SensorNoise.GRAVITY, "重力噪声")
        assertRowStart(SensorNoise.LINEAR, "线性加速度噪声")
        assertRowStart(SensorNoise.MAG, "磁场噪声")
        assertRowStart(SensorNoise.ORIENT, "方向角噪声")
        assertRowStart(SensorNoise.ROTVEC, "旋转矢量噪声")
    }

    private fun assertRowStart(start: Int, titlePrefix: String) {
        val item = SensorNoise.ITEMS.first { it.title.startsWith(titlePrefix) }
        assertEquals(titlePrefix, start, item.start)
    }

    @Test
    fun isBiasSlot_trueOnlyForThreeToFive() {
        for (i in 0 until SensorNoise.COUNT) {
            val expected = i in 3..5
            assertEquals("isBiasSlot($i)", expected, SensorNoise.isBiasSlot(i))
        }
        assertFalse(SensorNoise.isBiasSlot(-1))
        assertFalse(SensorNoise.isBiasSlot(6))
        assertFalse(SensorNoise.isBiasSlot(19))
        assertFalse(SensorNoise.isBiasSlot(20))

        // isBiasSlot 与 Item.isBias 必须一致（Task/UI 两处判定不能分叉）
        for (item in SensorNoise.ITEMS) {
            assertEquals(
                "Item.isBias 与 isBiasSlot 在行「${item.title}」上分叉",
                SensorNoise.isBiasSlot(item.start),
                item.isBias
            )
        }
    }

    @Test
    fun defaultsAreAllInsideTheirRowLimit() {
        for (i in 0 until SensorNoise.COUNT) {
            val v = SensorNoise.DEFAULTS[i]
            assertFalse("DEFAULTS[$i] 是 NaN", v.isNaN())
            val row = SensorNoise.ITEMS.first { i in it.start..it.end }
            assertTrue("DEFAULTS[$i]=$v 超出行「${row.title}」上限 ${row.max}", abs(v) <= row.max)
        }
        // 零偏默认必须是 0（原生口径：陀螺零参考物理上就是 0）
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), SensorNoise.DEFAULTS.copyOfRange(3, 6), 0f)
    }

    // ---------------------------------------------------------------- sanitize

    @Test
    fun sanitize_null_returnsFreshDefaultsCopy() {
        val a = SensorNoise.sanitize(null)
        val b = SensorNoise.sanitize(null)
        assertArrayEquals(SensorNoise.DEFAULTS, a, 0f)
        assertNotSame("sanitize 不能把 DEFAULTS 本体交出去", SensorNoise.DEFAULTS, a)
        assertNotSame("两次调用不能共享同一个数组", a, b)

        a[0] = 12345f
        assertNotSame("修改返回值污染了 DEFAULTS", 12345f, SensorNoise.DEFAULTS[0])
        assertEquals(SensorNoise.DEFAULTS[0], SensorNoise.sanitize(null)[0], 0f)
    }

    @Test
    fun sanitize_defaultsAreFixedPoint() {
        val once = SensorNoise.sanitize(SensorNoise.DEFAULTS)
        assertArrayEquals(SensorNoise.DEFAULTS, once, 0f)
        assertNotSame(SensorNoise.DEFAULTS, once)
    }

    @Test
    fun sanitize_shortArray_isPaddedWithDefaults() {
        val out = SensorNoise.sanitize(floatArrayOf(0.01f))
        assertEquals(SensorNoise.COUNT, out.size)
        assertEquals(0.01f, out[0], 0f)
        for (i in 1 until SensorNoise.COUNT) {
            assertEquals("缺位未补默认值: $i", SensorNoise.DEFAULTS[i], out[i], 0f)
        }
    }

    @Test
    fun sanitize_longArray_isTruncatedToCount() {
        val raw = FloatArray(SensorNoise.COUNT + 5) { 0.01f }
        raw[SensorNoise.COUNT + 4] = 999f
        val out = SensorNoise.sanitize(raw)
        assertEquals(SensorNoise.COUNT, out.size)
        for (i in 0 until SensorNoise.COUNT) assertEquals(0.01f, out[i], 0f)
        assertFalse("越界元素漏进了结果", out.any { it == 999f })
    }

    @Test
    fun sanitize_nan_fallsBackToDefault() {
        val raw = SensorNoise.DEFAULTS.copyOf()
        raw[0] = Float.NaN          // σ 槽
        raw[3] = Float.NaN          // 零偏槽
        raw[19] = Float.NaN         // 标量 σ 槽
        val out = SensorNoise.sanitize(raw)
        assertEquals(SensorNoise.DEFAULTS[0], out[0], 0f)
        assertEquals(SensorNoise.DEFAULTS[3], out[3], 0f)
        assertEquals(SensorNoise.DEFAULTS[19], out[19], 0f)
    }

    @Test
    fun sanitize_negativeSigma_clampedToZero() {
        val raw = SensorNoise.DEFAULTS.copyOf()
        raw[0] = -0.001f            // 陀螺 σ
        raw[9] = -1f                // 重力 σ
        raw[18] = -3f               // 方向角 σ（标量）
        val out = SensorNoise.sanitize(raw)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[9], 0f)
        assertEquals(0f, out[18], 0f)
    }

    @Test
    fun sanitize_negativeBias_isPreserved() {
        val raw = SensorNoise.DEFAULTS.copyOf()
        raw[3] = -0.2f
        raw[4] = -0.4999f
        raw[5] = -0.5f              // 恰为 −max，仍是合法
        val out = SensorNoise.sanitize(raw)
        assertEquals(-0.2f, out[3], 0f)
        assertEquals(-0.4999f, out[4], 0f)
        assertEquals(-0.5f, out[5], 0f)
    }

    @Test
    fun sanitize_biasBeyondLimit_clampedSymmetric() {
        val raw = SensorNoise.DEFAULTS.copyOf()
        raw[3] = -9f                // 零偏行 max = 0.5
        raw[4] = 9f
        val out = SensorNoise.sanitize(raw)
        assertEquals(-0.5f, out[3], 0f)
        assertEquals(0.5f, out[4], 0f)
    }

    @Test
    fun sanitize_overRowMax_isClampedToRowMax() {
        val raw = SensorNoise.DEFAULTS.copyOf()
        raw[0] = 0.5f               // 陀螺 σ max 0.05
        raw[6] = 4f                 // 加速度 σ max 0.5
        raw[15] = 100f              // 磁场 σ max 5
        raw[18] = 100f              // 方向角 σ max 5
        raw[19] = 1f                // 旋转矢量 σ max 0.05
        val out = SensorNoise.sanitize(raw)
        assertEquals(0.05f, out[0], 0f)
        assertEquals(0.5f, out[6], 0f)
        assertEquals(5f, out[15], 0f)
        assertEquals(5f, out[18], 0f)
        assertEquals(0.05f, out[19], 0f)
    }

    @Test
    fun sanitize_boundaryEqualToMax_isKept() {
        val raw = SensorNoise.DEFAULTS.copyOf()
        raw[15] = 5f
        raw[19] = 0.05f
        val out = SensorNoise.sanitize(raw)
        assertEquals(5f, out[15], 0f)
        assertEquals(0.05f, out[19], 0f)
    }

    // ---------------------------------------------------------------- parseItem

    private val gyroSigma get() = SensorNoise.ITEMS[0]        // 0..2, max 0.05, 4 位
    private val gyroBias get() = SensorNoise.ITEMS[1]         // 3..5, max 0.5,  4 位
    private val rotVec get() = SensorNoise.ITEMS[7]           // 19,   标量,  max 0.05

    @Test
    fun parseItem_singleNumberFillsWholeRow() {
        val r = SensorNoise.parseItem(gyroSigma, "0.01")
        assertTrue("单值应填满三轴: ${r.exceptionOrNull()?.message}", r.isSuccess)
        assertArrayEquals(floatArrayOf(0.01f, 0.01f, 0.01f), r.getOrThrow(), 0f)
    }

    @Test
    fun parseItem_acceptsSlashCommaChineseCommaAndSpace() {
        val expected = floatArrayOf(0.01f, 0.02f, 0.03f)
        for (text in listOf("0.01/0.02/0.03", "0.01,0.02,0.03", "0.01，0.02，0.03", "0.01 0.02 0.03", "0.01 / 0.02 / 0.03")) {
            val r = SensorNoise.parseItem(gyroSigma, text)
            assertTrue("「$text」应可解析: ${r.exceptionOrNull()?.message}", r.isSuccess)
            assertArrayEquals("「$text」解析结果不符", expected, r.getOrThrow(), 0f)
        }
    }

    @Test
    fun parseItem_wrongCountFails() {
        for (text in listOf("0.01/0.02", "0.01/0.02/0.03/0.04")) {
            val r = SensorNoise.parseItem(gyroSigma, text)
            assertTrue("「$text」个数不符应失败", r.isFailure)
            assertTrue(r.exceptionOrNull() is IllegalArgumentException)
            assertNotNull(r.exceptionOrNull()?.message)
        }
        // 标量行也不能接受两个数
        assertTrue(SensorNoise.parseItem(rotVec, "0.01/0.02").isFailure)
        assertTrue(SensorNoise.parseItem(rotVec, "0.01").isSuccess)
    }

    @Test
    fun parseItem_negativeSigmaFails() {
        assertTrue(SensorNoise.parseItem(gyroSigma, "-0.01").isFailure)
        assertTrue("行内任一轴为负都要拒绝", SensorNoise.parseItem(gyroSigma, "0.01/-0.01/0.01").isFailure)
        val msg = SensorNoise.parseItem(gyroSigma, "-0.01").exceptionOrNull()?.message ?: ""
        assertTrue("失败原因应指出 σ 不能为负，实际：$msg", msg.contains("负"))
    }

    @Test
    fun parseItem_negativeBiasSucceeds() {
        val r = SensorNoise.parseItem(gyroBias, "-0.2")
        assertTrue("零偏行必须接受负数: ${r.exceptionOrNull()?.message}", r.isSuccess)
        assertArrayEquals(floatArrayOf(-0.2f, -0.2f, -0.2f), r.getOrThrow(), 0f)

        val multi = SensorNoise.parseItem(gyroBias, "-0.1/-0.2/-0.3")
        assertTrue(multi.isSuccess)
        assertArrayEquals(floatArrayOf(-0.1f, -0.2f, -0.3f), multi.getOrThrow(), 0f)

        assertTrue("零偏下限为 −max", SensorNoise.parseItem(gyroBias, "-0.5").isSuccess)
        assertTrue("超出 −max 应失败", SensorNoise.parseItem(gyroBias, "-0.51").isFailure)
        assertTrue("零偏正向上限同样是 max", SensorNoise.parseItem(gyroBias, "0.51").isFailure)
    }

    @Test
    fun parseItem_overRowMaxFails_atMaxSucceeds() {
        assertTrue(SensorNoise.parseItem(gyroSigma, "0.05").isSuccess)
        assertTrue(SensorNoise.parseItem(gyroSigma, "0.051").isFailure)
        assertTrue("磁场行 max=5", SensorNoise.parseItem(SensorNoise.ITEMS[5], "5.1").isFailure)
        assertTrue(SensorNoise.parseItem(SensorNoise.ITEMS[5], "5").isSuccess)
        assertTrue("旋转矢量 max=0.05", SensorNoise.parseItem(rotVec, "0.06").isFailure)
    }

    @Test
    fun parseItem_nonNumericOrEmptyFails() {
        for (text in listOf("abc", "0.01/abc/0.02", "", "   ", "///", "NaN")) {
            val r = SensorNoise.parseItem(gyroSigma, text)
            assertTrue("「$text」应失败", r.isFailure)
            assertNotNull(r.exceptionOrNull()?.message)
        }
    }

    @Test
    fun parseItem_trimsAndIgnoresEmptyTokens() {
        val r = SensorNoise.parseItem(gyroSigma, "  0.01 , 0.02 , 0.03  ")
        assertTrue(r.isSuccess)
        assertArrayEquals(floatArrayOf(0.01f, 0.02f, 0.03f), r.getOrThrow(), 0f)
    }

    // ---------------------------------------------------------------- withItem

    @Test
    fun withItem_returnsNewArrayAndLeavesSourceIntact() {
        val base = SensorNoise.DEFAULTS.copyOf()
        val snapshot = base.copyOf()
        val accel = SensorNoise.ITEMS[2]                       // 6..8, max 0.5
        val out = SensorNoise.withItem(base, accel, floatArrayOf(0.11f, 0.22f, 0.33f))

        assertNotSame(base, out)
        assertEquals(SensorNoise.COUNT, out.size)
        assertArrayEquals("原数组被就地修改", snapshot, base, 0f)
        assertEquals(0.11f, out[6], 0f)
        assertEquals(0.22f, out[7], 0f)
        assertEquals(0.33f, out[8], 0f)
        // 逐槽对照：除 6..8 外必须与 sanitize(base) 完全一致
        val expected = SensorNoise.sanitize(base)
        expected[6] = 0.11f
        expected[7] = 0.22f
        expected[8] = 0.33f
        assertArrayEquals("只有该行区间可被改写", expected, out, 0f)
    }

    @Test
    fun withItem_writesOnlyTargetRowRange() {
        val base = SensorNoise.DEFAULTS.copyOf()
        val rot = SensorNoise.ITEMS[7]                         // start 19, size 1
        val out = SensorNoise.withItem(base, rot, floatArrayOf(0.04f))
        assertEquals(0.04f, out[19], 0f)
        assertEquals(SensorNoise.DEFAULTS[18], out[18], 0f)
        assertEquals(SensorNoise.DEFAULTS[0], out[0], 0f)

        // 反向验证：改 6..8 不得碰到 5 与 9
        val out2 = SensorNoise.withItem(base, SensorNoise.ITEMS[2], floatArrayOf(0.4f, 0.4f, 0.4f))
        assertEquals(SensorNoise.DEFAULTS[5], out2[5], 0f)
        assertEquals(SensorNoise.DEFAULTS[9], out2[9], 0f)
        assertEquals(0.4f, out2[6], 0f)
        assertEquals(0.4f, out2[8], 0f)
    }

    @Test
    fun withItem_sanitizesBeforeWriting() {
        val dirty = SensorNoise.DEFAULTS.copyOf()
        dirty[0] = Float.NaN
        dirty[15] = 999f                                        // 磁场超上限
        val out = SensorNoise.withItem(dirty, SensorNoise.ITEMS[7], floatArrayOf(0.03f))
        assertEquals("行外 NaN 应退回默认", SensorNoise.DEFAULTS[0], out[0], 0f)
        assertEquals("行外超限应被钳位", 5f, out[15], 0f)
        assertEquals(0.03f, out[19], 0f)
    }

    // ---------------------------------------------------------------- encode / decode

    @Test
    fun encode_hasOneTokenPerSlot() {
        val tokens = SensorNoise.encode(SensorNoise.DEFAULTS).split(',')
        assertEquals(SensorNoise.COUNT, tokens.size)
        for (t in tokens) assertNotNull("编码里出现了不可解析的项「$t」", t.toFloatOrNull())
    }

    @Test
    fun encodeDecode_roundTripsBitExactly() {
        val p = SensorNoise.DEFAULTS.copyOf()
        p[0] = 0.0123f
        p[3] = -0.2f
        p[5] = -0.5f
        p[15] = 3.5f
        p[18] = 1.25f
        p[19] = 0.04f

        val back = SensorNoise.decode(SensorNoise.encode(p))
        assertNotSame(p, back)
        assertArrayEquals("往返不一致", p, back, 0f)

        // 二次往返仍稳定
        assertArrayEquals(back, SensorNoise.decode(SensorNoise.encode(back)), 0f)
    }

    @Test
    fun decode_nullBlankOrCorrupt_returnsDefaultsSemantics() {
        for (text in listOf(null, "", "   ", "abc", ",,,", "not,a,number")) {
            val out = SensorNoise.decode(text)
            assertArrayEquals("decode(${text ?: "null"}) 应为全默认", SensorNoise.DEFAULTS, out, 0f)
            assertNotSame("不能交出 DEFAULTS 本体", SensorNoise.DEFAULTS, out)
        }
    }

    @Test
    fun decode_corruptToken_invalidatesWholeString() {
        // 损坏 token ⇒ **整串作废回默认**，绝不允许"丢掉坏 token 让后面的值前移"：
        // 前移会得到「每个数都合法、但槽位全错」的档，噪声按错误的传感器注入而页面看不出来。
        val out = SensorNoise.decode("0.01,abc,0.02")
        for (i in 0 until SensorNoise.COUNT) {
            assertEquals("槽 $i 应回默认", SensorNoise.DEFAULTS[i], out[i], 0f)
        }
        // 坏 token 在 COUNT 之后（本就不会被使用）⇒ 不影响前 20 个槽
        val tail = SensorNoise.decode(
            (0 until SensorNoise.COUNT).joinToString(",") { "0.001" } + ",oops"
        )
        assertEquals(0.001f, tail[0], 0f)
    }

    @Test
    fun decode_nanToken_fallsBackToDefault() {
        val out = SensorNoise.decode("NaN,0.02")
        assertEquals(SensorNoise.DEFAULTS[0], out[0], 0f)
        assertEquals(0.02f, out[1], 0f)
    }

    @Test
    fun decode_appliesSanitizeRules() {
        // 零偏位负数保留、σ 位负数归零、超限钳到该行 max
        val out = SensorNoise.decode("0.001,0.001,0.001,-0.3,0,0,-1,0.9,0.001,0,0,0,0,0,0,999")
        assertEquals(-0.3f, out[3], 0f)
        assertEquals(0f, out[4], 0f)
        assertEquals("σ 负数归零", 0f, out[6], 0f)
        assertEquals("加速度 σ max=0.5", 0.5f, out[7], 0f)
        assertEquals("磁场 σ max=5", 5f, out[15], 0f)
        assertEquals("短串其余补默认", SensorNoise.DEFAULTS[16], out[16], 0f)
    }

    @Test
    fun decode_tooManyTokens_isTruncated() {
        val text = (0 until SensorNoise.COUNT + 4).joinToString(",") { "0.001" }
        val out = SensorNoise.decode(text)
        assertEquals(SensorNoise.COUNT, out.size)
        for (v in out) assertEquals(0.001f, v, 0f)
    }

    // ---------------------------------------------------------------- format / formatItem

    @Test
    fun format_digitsFollowOwningRow() {
        assertEquals("1.000", SensorNoise.format(SensorNoise.ORIENT, 1f))   // 方向角行 3 位
        assertEquals("1.0000", SensorNoise.format(SensorNoise.ROTVEC, 1f))  // 旋转矢量行 4 位
        assertEquals("1.000", SensorNoise.format(SensorNoise.MAG, 1f))      // 磁场行 3 位
        assertEquals("-0.0123", SensorNoise.format(SensorNoise.GYRO_BIAS, -0.0123f))
    }

    @Test
    fun format_indexIsCoercedIntoRange() {
        // 越界下标必须钳进 0..COUNT-1，而不是抛异常
        assertEquals(SensorNoise.format(0, 1f), SensorNoise.format(-1, 1f))
        assertEquals(SensorNoise.format(SensorNoise.COUNT - 1, 1f), SensorNoise.format(999, 1f))
        assertEquals("1.0000", SensorNoise.format(-1, 1f))
        assertEquals("1.0000", SensorNoise.format(999, 1f))
    }

    @Test
    fun formatItem_joinsThreeAxesWithSlashUsingRowDigits() {
        val gyro = SensorNoise.ITEMS[0]                                     // 4 位
        val values = SensorNoise.DEFAULTS.copyOf()
        values[0] = 0.001f; values[1] = 0.002f; values[2] = 0.003f
        assertEquals("0.0010/0.0020/0.0030", SensorNoise.formatItem(gyro, values))
    }

    @Test
    fun formatItem_usesRowDigitsPerRow() {
        val mag = SensorNoise.ITEMS[5]                                      // 3 位
        val bias = SensorNoise.ITEMS[1]                                     // 4 位
        val values = SensorNoise.DEFAULTS.copyOf()
        values[15] = 0.2078f; values[16] = 0.1212f; values[17] = 0.3233f
        values[3] = -0.0123f
        assertEquals("0.208/0.121/0.323", SensorNoise.formatItem(mag, values))
        assertEquals("-0.0123/0.0000/0.0000", SensorNoise.formatItem(bias, values))
    }

    @Test
    fun formatItem_scalarRowHasNoSeparator() {
        val rot = SensorNoise.ITEMS[7]
        val values = SensorNoise.DEFAULTS.copyOf()
        assertEquals("0.0009", SensorNoise.formatItem(rot, values))         // 0.000866 → 4 位
        assertFalse(SensorNoise.formatItem(rot, values).contains('/'))
    }

    @Test
    fun formatItem_missingValuesFallBackToZero() {
        val gyro = SensorNoise.ITEMS[0]
        assertEquals("0.0000/0.0000/0.0000", SensorNoise.formatItem(gyro, FloatArray(0)))
        assertEquals("0.0000/0.0000/0.0000", SensorNoise.formatItem(gyro, floatArrayOf(0f)))
    }

    @Test
    fun withItem_rejectsWrongLength() {
        val item = SensorNoise.ITEMS[SensorNoise.ITEMS.indexOfFirst { it.start == SensorNoise.GYRO }]
        val base = SensorNoise.DEFAULTS.copyOf()
        // 旧实现会 ArrayIndexOutOfBoundsException（越界写），现在必须是明确的参数错误
        assertThrows(IllegalArgumentException::class.java) {
            SensorNoise.withItem(base, item, floatArrayOf(0.001f, 0.002f))
        }
    }

    @Test
    fun withItem_clampsAndRejectsNonFinite() {
        val item = SensorNoise.ITEMS.first { it.start == SensorNoise.GYRO }
        val base = SensorNoise.DEFAULTS.copyOf()
        val out = SensorNoise.withItem(base, item, floatArrayOf(Float.NaN, 999f, -1f))
        assertEquals("NaN ⇒ 该槽默认", SensorNoise.DEFAULTS[SensorNoise.GYRO], out[SensorNoise.GYRO], 0f)
        assertEquals("超上限 ⇒ 钳到行上限", item.max, out[SensorNoise.GYRO + 1], 0f)
        assertEquals("σ 负值 ⇒ 0", 0f, out[SensorNoise.GYRO + 2], 0f)
        // 原数组不许被改
        assertEquals(SensorNoise.DEFAULTS[SensorNoise.GYRO + 1], base[SensorNoise.GYRO + 1], 0f)
    }

    @Test
    fun format_isLocaleIndependent() {
        // 逗号小数点的区域设置下也必须用 '.'，否则 UI 显示 0,0010、用户照抄回输入框解析不了
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val text = SensorNoise.format(SensorNoise.GYRO, 0.001f)
            assertEquals("0.0010", text)
            assertFalse("不该出现逗号小数点", text.contains(','))
        } finally {
            java.util.Locale.setDefault(old)
        }
    }
}
