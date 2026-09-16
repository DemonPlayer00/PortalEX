package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 体力模型的语义单测：把"衰减 / 休息 / 恢复 / 降速 / 随机化边界"钉死。
 *
 * 为什么值得写：这套模型会被 App 的运动循环**每拍调用**，而它错起来是"配速怪"
 * 而不是崩溃 —— 真机上很难靠肉眼看出来（人只会觉得"今天数据有点怪"）。
 */
class StaminaModelTest {

    private val base = 3.05
    private fun cfg(
        decay: Double = 11.0,
        restAt: Double = 20.0,
        restSec: Double = 35.0,
        recover: Double = 2.0,
        walk: Double = 1.10,
        minFactor: Double = 0.75,
        randomPct: Double = 15.0,
    ) = StaminaConfig(
        enabled = true,
        decayPerMinute = decay,
        restAtPercent = restAt,
        restSeconds = restSec,
        recoverPerSecond = recover,
        walkSpeed = walk,
        minSpeedFactor = minFactor,
        randomPercent = randomPct,
    )

    /** 固定种子：随机化仍然是随机的，但测试要可复现 */
    private val rnd = Random(20260916)

    @Test
    fun `初始满体力 系数为 1`() {
        val m = StaminaModel().apply { reset() }
        val f = m.tick(cfg(), dtSec = 0.2, baseSpeed = base, random = rnd)
        // 满体力起步 ⇒ 本拍不做任何降速
        assertEquals(1.0, f, 1e-6)
        // 但走了 0.2 秒就必然掉一点体力（掉多少由随机化的衰减速率决定，故只断言"几乎满"）
        val st = m.snapshot().staminaPercent
        assertTrue("刚起步应接近满体力，实得 $st", st > 99.9 && st <= 100.0)
    }

    @Test
    fun `跑动随时间掉体力 且速度系数平滑下滑`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(randomPct = 0.0)   // 关随机化，便于断言精确值
        // 跑 6 分钟
        repeat((6 * 60 / 0.5).toInt()) { m.tick(c, 0.5, base, rnd) }
        val s = m.snapshot()
        assertTrue("应已掉体力，实得 ${s.staminaPercent}", s.staminaPercent < 100.0)
        assertFalse("6 分钟还不该进入休息", s.resting)
        // 系数应 <= 1 且 >= minFactor
        assertTrue("系数应在 [minFactor,1]，实得 ${s.speedScale}", s.speedScale in 0.75..1.0)
        // 体力按 decay×系数 的速度下降：6 分钟应落在合理区间（系数≈0.9 ⇒ 约 55~70 之间）
        assertTrue("6 分钟后体力应在 40~75，实得 ${s.staminaPercent}", s.staminaPercent in 40.0..75.0)
    }

    @Test
    fun `体力降到阈值即进入休息 且速度切到走路低值`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(randomPct = 0.0)
        var entered = false
        repeat((30 * 60 / 0.5).toInt()) {
            val f = m.tick(c, 0.5, base, rnd)
            if (!entered && m.snapshot().resting) {
                entered = true
                // 刚进休息：系数应等于 走路速度/基础速度
                assertEquals("休息时系数应 = walk/base", 1.10 / base, f, 1e-6)
            }
        }
        assertTrue("30 分钟内应至少休息一次", entered)
        assertTrue("应记录休息次数", m.snapshot().restCount >= 1)
    }

    @Test
    fun `休息期间体力恢复 且时长到点后回到跑动`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(decay = 60.0, restSec = 10.0, randomPct = 0.0)  // 快速掉、短休息
        // 跑到进入休息
        repeat((10 * 60 * 2).toInt()) { m.tick(c, 0.5, base, rnd) }
        val atRest = m.snapshot()
        assertTrue("应已进入休息", atRest.resting)
        val staminaAtRest = atRest.staminaPercent
        // 休息 10 秒（+ 余量），期间体力必须单调上升
        var prev = staminaAtRest
        var increased = false
        repeat(20) {
            m.tick(c, 0.5, base, rnd)
            val now = m.snapshot().staminaPercent
            if (now > prev + 1e-9) increased = true
            prev = now
        }
        assertTrue("休息期间体力应上升", increased)
        assertFalse("休息 10 秒后应已回到跑动（本用例 restSec=10）", m.snapshot().resting)
    }

    @Test
    fun `随机化幅度为零时 休息时长恒定`() {
        val c = cfg(decay = 60.0, restSec = 12.0, randomPct = 0.0)
        val m = StaminaModel().apply { reset() }
        val restDurations = ArrayList<Double>()
        var lastRestStartTick = -1
        var ticks = 0
        var inRest = false
        var startTick = 0
        repeat((40 * 60 * 2).toInt()) {
            m.tick(c, 0.5, base, rnd)
            ticks++
            val resting = m.snapshot().resting
            if (resting && !inRest) { inRest = true; startTick = ticks }
            if (!resting && inRest) { inRest = false; restDurations += (ticks - startTick) * 0.5 }
        }
        assertTrue("应观察到多次休息（实得 ${restDurations.size}）", restDurations.size >= 2)
        restDurations.forEach {
            assertEquals("randomPct=0 时每次休息都应是 12 秒", 12.0, it, 0.6)
        }
    }

    @Test
    fun `随机化幅度生效时 休息时长出现差异但仍在界内`() {
        val c = cfg(decay = 60.0, restSec = 12.0, randomPct = 30.0)
        val m = StaminaModel().apply { reset() }
        val durations = ArrayList<Double>()
        var inRest = false; var startTick = 0; var ticks = 0
        repeat((40 * 60 * 2).toInt()) {
            m.tick(c, 0.5, base, rnd)
            ticks++
            val resting = m.snapshot().resting
            if (resting && !inRest) { inRest = true; startTick = ticks }
            if (!resting && inRest) { inRest = false; durations += (ticks - startTick) * 0.5 }
        }
        assertTrue("应观察到多次休息", durations.size >= 2)
        val lo = 12.0 * 0.7 - 0.6
        val hi = 12.0 * 1.3 + 0.6
        durations.forEach { assertTrue("休息时长 $it 应落在 ±30% 内", it in lo..hi) }
        assertTrue("应出现不等于 12 秒的样本（随机化确实生效）",
            durations.any { kotlin.math.abs(it - 12.0) > 0.5 })
    }

    @Test
    fun `参数被夹取 极端输入不会把模拟弄成静止或瞬移`() {
        val wild = StaminaConfig(
            enabled = true,
            decayPerMinute = -5.0,
            restAtPercent = 500.0,
            restSeconds = 0.0,
            recoverPerSecond = -1.0,
            walkSpeed = 0.0,
            minSpeedFactor = 9.0,
            randomPercent = 999.0,
        ).sanitized()
        assertTrue("衰减应被夹到正数", wild.decayPerMinute > 0)
        assertTrue("阈值应在 0..99", wild.restAtPercent in 0.0..99.0)
        assertTrue("休息时长应 >= 1 秒", wild.restSeconds >= 1.0)
        assertTrue("恢复速度应为正", wild.recoverPerSecond > 0)
        assertTrue("走路速度应为正", wild.walkSpeed > 0)
        assertTrue("系数下限应在 (0,1]", wild.minSpeedFactor in 0.05..1.0)
        assertTrue("随机化幅度应有上限", wild.randomPercent <= 60.0)
    }

    @Test
    fun `dtSec 非正时不推进模型`() {
        val m = StaminaModel().apply { reset() }
        val before = m.snapshot()
        m.tick(cfg(), 0.0, base, rnd)
        m.tick(cfg(), -1.0, base, rnd)
        val after = m.snapshot()
        assertEquals(before.staminaPercent, after.staminaPercent, 1e-9)
        assertEquals(before.restCount, after.restCount)
    }
}
