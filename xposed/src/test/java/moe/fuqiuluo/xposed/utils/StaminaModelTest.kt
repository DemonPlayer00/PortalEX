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
        val f = m.tick(cfg(), dtSec = 0.2, baseSpeed = base, running = true, random = rnd)
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
        repeat((6 * 60 / 0.5).toInt()) { m.tick(c, 0.5, base, running = true, random = rnd) }
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
            val f = m.tick(c, 0.5, base, running = true, random = rnd)
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
        repeat((10 * 60 * 2).toInt()) { m.tick(c, 0.5, base, running = true, random = rnd) }
        val atRest = m.snapshot()
        assertTrue("应已进入休息", atRest.resting)
        val staminaAtRest = atRest.staminaPercent
        // 休息 10 秒（+ 余量），期间体力必须单调上升
        var prev = staminaAtRest
        var increased = false
        repeat(20) {
            m.tick(c, 0.5, base, running = true, random = rnd)
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
            m.tick(c, 0.5, base, running = true, random = rnd)
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
            m.tick(c, 0.5, base, running = true, random = rnd)
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
        m.tick(cfg(), 0.0, base, running = true, random = rnd)
        m.tick(cfg(), -1.0, base, running = true, random = rnd)
        val after = m.snapshot()
        assertEquals(before.staminaPercent, after.staminaPercent, 1e-9)
        assertEquals(before.restCount, after.restCount)
    }

    @Test
    fun `空闲时体力冻结 —— 既不衰减也不恢复`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg()
        // 先跑一会儿，让体力掉下去
        repeat(120) { m.tick(c, 0.5, base, running = true, random = rnd) }
        val afterRun = m.snapshot().staminaPercent
        assertTrue("跑动后应已掉体力", afterRun < 100.0)
        // 空闲 10 分钟（running=false）：体力必须一动不动
        repeat(1200) { m.tick(c, 0.5, base, running = false, random = rnd) }
        assertEquals("空闲不该衰减", afterRun, m.snapshot().staminaPercent, 1e-9)
        // 空闲期间也不该推进休息计时/次数
        assertEquals(0, m.snapshot().restCount)
    }

    @Test
    fun `休息中若停止运动 则不再恢复`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(decay = 120.0, restSec = 600.0, randomPct = 0.0)
        // 一直跑到**进入休息的那一刻**就停（不能用固定拍数：跑完一段它会自己回到跑动，
        // 那样断言到的可能是"下一段跑动"——第一版测试就是这么写错的）
        var ticks = 0
        while (!m.snapshot().resting && ticks < 10_000) {
            m.tick(c, 0.5, base, running = true, random = rnd)
            ticks++
        }
        assertTrue("应在合理时间内进入休息（已跑 $ticks 拍）", m.snapshot().resting)
        val atPause = m.snapshot().staminaPercent
        // 空闲 30 秒：休息期间"没人动"，体力不该回升（这是本轮修掉的那个偏差）
        repeat(60) { m.tick(c, 0.5, base, running = false, random = rnd) }
        assertEquals("空闲时休息不该回血", atPause, m.snapshot().staminaPercent, 1e-9)
    }

    @Test
    fun `衰减与真实速度挂钩 —— 慢速时消耗更慢`() {
        // 同一段时间，基础速度越低（= 同倍率下实际速度越低）体力掉得越少
        val fast = StaminaModel().apply { reset() }
        val slow = StaminaModel().apply { reset() }
        val c = cfg(randomPct = 0.0)
        // fast: 基础 3.05；slow: 基础 1.5（同样倍率下真实速度更低 ⇒ 单位时间消耗更少）
        repeat(240) {
            fast.tick(c, 0.5, 3.05, running = true, random = rnd)
            slow.tick(c, 0.5, 1.5, running = true, random = rnd)
        }
        // 注意：倍率由"体力/阈值"决定，与基础速度无关；差别应体现在**休息来得更晚**
        assertTrue(
            "基础速度低的那次应掉得更少，实得 fast=${fast.snapshot().staminaPercent} slow=${slow.snapshot().staminaPercent}",
            slow.snapshot().staminaPercent >= fast.snapshot().staminaPercent,
        )
    }
}
