package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 体力模型的语义单测。
 *
 * 模型每拍被 App 的运动循环调用，出错的样子是"配速怪"而不是崩溃 ——
 * 真机上很难靠肉眼发现（人只会觉得"今天数据有点怪"），所以关键口径必须在这里钉死。
 *
 * 当前口径（2026-09-16 由用户定）：
 *  1. **恢复是持续的背景过程**：恢复速度 = 系数 × 倍率(体力)，倍率 100%→1.0、0%→2.0；
 *  2. **消耗与表象位移挂钩**：位移由参数 [moved] 直接给出（不依赖真实时钟）；
 *  3. **休息是倍率惩罚而非状态机**：低于阈值后倍率再乘休息系数，回到阈值以上自动解除；
 *  4. **忽略"过快"的表象移动**：超过阈值（单拍或窗口平均）时本拍只走恢复、不计消耗。
 *
 * ⚠️ 写测试的两个坑（都踩过）：
 *  - `recoverCoefficient = 0` 会被 [StaminaConfig.sanitized] 夹到 0.01，**不能当"关恢复"用**；
 *    要隔离消耗就设一个很小的恢复系数，并把断言写成区间而非精确值。
 *  - 衰减设得过大时体力会**掉到 0 并长时间贴在 0**，之后"恢复越快"之类比较会失真；
 *    比较恢复速度时衰减要温和。
 */
class StaminaModelTest {

    private val base = 3.05
    private val rnd = Random(20260916)

    private fun cfg(
        decay: Double = 11.0,
        recover: Double = 4.0,
        restAt: Double = 20.0,
        restFactor: Double = 0.25,
        cooling: Double = 1.0,
        walk: Double = 1.10,
        floor: Double = 0.75,
        randomPct: Double = 0.0,
        ignoreWindow: Double = 3.0,
        ignoreSpeed: Double = 12.0,
    ) = StaminaConfig(
        enabled = true, decayPerMinute = decay, recoverCoefficient = recover,
        restAtPercent = restAt, restSpeedFactor = restFactor, restSecondsCoefficient = cooling,
        walkSpeed = walk, minSpeedFactor = floor, randomPercent = randomPct,
        moveIgnoreWindowSec = ignoreWindow, moveIgnoreSpeed = ignoreSpeed,
    )

    /** 跑 [sec] 秒、每拍 [dt] 秒、表象速度 [speed] m/s（位移 = speed × dt） */
    private fun StaminaModel.runAt(c: StaminaConfig, sec: Double, speed: Double, dt: Double = 0.5) {
        repeat(kotlin.math.round(sec / dt).toInt()) { tick(c, dt, base, speed * dt, rnd) }
    }

    /** 静止 [sec] 秒（本拍无位移） */
    private fun StaminaModel.hold(c: StaminaConfig, sec: Double, dt: Double = 0.5) {
        repeat(kotlin.math.round(sec / dt).toInt()) { tick(c, dt, base, 0.0, rnd) }
    }

    /** 跑到"进入休息"为止，返回跑了多少秒；超时返回 -1 */
    private fun StaminaModel.runUntilRest(c: StaminaConfig, speed: Double, dt: Double = 0.5): Double {
        var t = 0.0
        while (!snapshot().resting && t < 3600.0) {
            tick(c, dt, base, speed * dt, rnd); t += dt
        }
        return if (snapshot().resting) t else -1.0
    }

    /** 静止到"解除休息"为止，返回花了多少秒；超时返回 -1 */
    private fun StaminaModel.holdUntilRestOver(c: StaminaConfig, dt: Double = 0.5): Double {
        var t = 0.0
        while (snapshot().resting && t < 3600.0) {
            tick(c, dt, base, 0.0, rnd); t += dt
        }
        return if (snapshot().resting) -1.0 else t
    }

    // ------------------------------------------------------------------ 基础

    /**
     * 起手不调制 —— 但注意"第一拍"的倍率**不是精确的 1.0**：
     * 那一拍自己扣掉的体力会立刻反映到该拍的倍率上（倍率取自本拍结算之后的体力）。
     * 旧实现把 fatigue 算在消耗之前，于是第一拍返回精确 1.0 —— 那正是
     * "预览曲线与实跑对不上"的根源（见 StaminaCurveTest 的逐拍一致性用例）。
     */
    @Test
    fun `满体力开始跑动 倍率贴着 1 但已扣掉本拍的消耗`() {
        val m = StaminaModel().apply { reset() }
        val first = m.tick(cfg(), 0.5, base, base * 0.5, rnd)
        assertTrue("起手倍率应贴着 1（实得 $first）", first <= 1.0 && first > 0.999)
        // 静止那一拍没有任何消耗 ⇒ 倍率必须仍是精确的 1.0
        val still = StaminaModel().apply { reset() }
        assertEquals(1.0, still.tick(cfg(), 0.5, base, 0.0, rnd), 1e-12)
    }

    @Test
    fun `跑动掉体力 且倍率随体力平滑下滑`() {
        val m = StaminaModel().apply { reset() }
        m.runAt(cfg(), 360.0, base)
        val s = m.snapshot()
        assertTrue("应已掉体力，实得 ${s.staminaPercent}", s.staminaPercent < 95.0)
        assertFalse("6 分钟还不该到阈值", s.resting)
        assertTrue("倍率应在 [下限,1]，实得 ${s.speedScale}", s.speedScale in 0.75..1.0)
    }

    @Test
    fun `消耗与表象速度挂钩 —— 跑得慢消耗也慢`() {
        val fast = StaminaModel().apply { reset() }
        val slow = StaminaModel().apply { reset() }
        val c = cfg(recover = 1.0)
        fast.runAt(c, 600.0, base)
        slow.runAt(c, 600.0, base * 0.5)
        assertTrue(
            "慢速应掉得更少：fast=${fast.snapshot().staminaPercent} slow=${slow.snapshot().staminaPercent}",
            slow.snapshot().staminaPercent > fast.snapshot().staminaPercent,
        )
    }

    // ------------------------------------------------------------------ 持续恢复

    @Test
    fun `静止时持续恢复 —— 不是只有休息段才回血`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(decay = 60.0, recover = 6.0)
        m.runAt(c, 120.0, base)
        val afterRun = m.snapshot().staminaPercent
        assertTrue("跑动后应掉体力，实得 $afterRun", afterRun < 95.0)
        m.hold(c, 60.0)
        val afterHold = m.snapshot().staminaPercent
        assertTrue("静止应持续回体力：$afterRun → $afterHold", afterHold > afterRun + 0.5)
    }

    @Test
    fun `空闲（完全不动）也持续恢复 —— 直到回满`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(decay = 60.0, recover = 30.0)
        // 先跑一段把体力压下来
        m.runAt(c, 120.0, base)
        val from = m.snapshot().staminaPercent
        assertTrue("应先掉到明显低于满值，实得 $from", from < 90.0)
        // 然后**完全不产生位移**，一直挂着：体力应一路回满
        m.hold(c, 600.0)
        val to = m.snapshot().staminaPercent
        assertTrue("空闲应持续恢复：$from → $to", to > from)
        assertTrue("空闲够久应回满，实得 $to", to >= 99.9)
        assertEquals("空闲期间不该有忽略记录", 0L, m.snapshot().ignoredTicks)
    }

    @Test
    fun `体力越低 恢复越快（倍率 100%为1 0%为2）`() {
        val c = cfg(decay = 60.0, recover = 12.0)
        // 满体力侧的增量
        val high = StaminaModel().apply { reset() }
        val highFrom = high.snapshot().staminaPercent
        high.hold(c, 60.0)
        val highGain = high.snapshot().staminaPercent - highFrom
        // 低体力侧的增量（先跑到接近空）
        val low = StaminaModel().apply { reset() }
        low.runAt(c, 300.0, base)
        val lowFrom = low.snapshot().staminaPercent
        low.hold(c, 60.0)
        val lowGain = low.snapshot().staminaPercent - lowFrom
        assertTrue(
            "低体力恢复应更快：满体力 +$highGain（自 $highFrom） vs 低体力 +$lowGain（自 $lowFrom）",
            lowGain > highGain,
        )
    }

    @Test
    fun `多次静止恢复的增量随体力下降而变大`() {
        val c = cfg(decay = 60.0, recover = 12.0)
        val m = StaminaModel().apply { reset() }
        val gains = ArrayList<Double>()
        repeat(4) {
            m.runAt(c, 90.0, base)          // 跑一段，把体力压下去
            val from = m.snapshot().staminaPercent
            m.hold(c, 20.0)                  // 静止同样时长，看回多少
            gains += m.snapshot().staminaPercent - from
        }
        val first = gains.first()
        val last = gains.last()
        assertTrue("恢复增量应随体力降低而增大：$gains", last > first)
    }

    // ------------------------------------------------------------------ 休息（倍率惩罚）

    @Test
    fun `低于休息体力值时 倍率再乘休息系数`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(decay = 60.0, recover = 1.0, restFactor = 0.25, walk = 0.01)
        val t = m.runUntilRest(c, base)
        assertTrue("应在合理时间内进入休息（实得 $t 秒）", t > 0)
        val s = m.snapshot()
        assertTrue("应记录休息次数", s.restCount >= 1)
        assertTrue("休息时倍率应显著低于疲劳倍率，实得 ${s.speedScale}", s.speedScale < 0.75)
    }

    @Test
    fun `体力回到阈值以上 自动解除休息`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(decay = 60.0, recover = 30.0, restAt = 20.0)
        // 记录 resting 的状态转移序列：应为 false → true（掉到阈值）→ false（恢复越过阈值）
        // 直接断言"某一刻不是 resting"是不可靠的：体力会在阈值附近来回穿越，
        // 断言到哪一侧取决于恰好停在哪一拍（第一版就是这么写错的）。
        var transitions = 0
        var last = m.snapshot().resting
        var secs = 0.0
        while (secs < 3600.0 && transitions < 2) {
            m.tick(c, 0.5, base, if (last) 0.0 else base * 0.5, rnd)   // 休息时静止、跑动时前进
            val now = m.snapshot().resting
            if (now != last) { transitions++; last = now }
            secs += 0.5
        }
        assertEquals("应完成 false→true→false 两次转移（实得 $transitions 次，用时 ${secs}s）", 2, transitions)
        assertFalse("最终应回到非休息", m.snapshot().resting)
    }

    @Test
    fun `冷却时间系数越大 回到阈值以上越慢`() {
        val cQuick = cfg(decay = 60.0, recover = 20.0, cooling = 1.0)
        val cSlow = cfg(decay = 60.0, recover = 20.0, cooling = 4.0)
        val quick = StaminaModel().apply { reset() }.let { it.runUntilRest(cQuick, base); it.holdUntilRestOver(cQuick) }
        val slow = StaminaModel().apply { reset() }.let { it.runUntilRest(cSlow, base); it.holdUntilRestOver(cSlow) }
        assertTrue("两者都应成功解除（quick=$quick slow=$slow）", quick > 0 && slow > 0)
        assertTrue("冷却系数大应更慢：quick=${quick}s slow=${slow}s", slow > quick * 1.5)
    }

    // ------------------------------------------------------------------ 忽略"过快"

    @Test
    fun `单拍速度过快 ⇒ 本拍只走恢复 不计消耗`() {
        val m = StaminaModel().apply { reset() }
        // 一次瞬移：0.5 秒走 100 米 = 200 m/s（远超阈值）
        val c = cfg(decay = 600.0, recover = 1.0, ignoreSpeed = 12.0, ignoreWindow = 0.0)
        m.tick(c, 0.5, base, 100.0, rnd)
        assertEquals("异常快的拍不该扣体力", 100.0, m.snapshot().staminaPercent, 1e-6)
        assertEquals("应记一次忽略", 1L, m.snapshot().ignoredTicks)
    }

    @Test
    fun `正常速度不会被忽略`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(decay = 600.0, recover = 1.0, ignoreSpeed = 12.0)
        m.runAt(c, 10.0, base)      // 3.05 m/s，远低于阈值
        assertTrue("正常跑动应照常消耗，实得 ${m.snapshot().staminaPercent}", m.snapshot().staminaPercent < 100.0)
        assertEquals("不该有忽略", 0L, m.snapshot().ignoredTicks)
    }

    @Test
    fun `窗口内的平均速度也参与异常判定`() {
        val m = StaminaModel().apply { reset() }
        // 阈值 2 m/s：单拍 1.5 m/s 本身不超，但窗口平均（含之前的快拍）可能超
        val c = cfg(decay = 600.0, recover = 1.0, ignoreSpeed = 2.0, ignoreWindow = 3.0)
        m.tick(c, 0.5, base, 3.0 * 0.5, rnd)   // 3 m/s，超阈值 ⇒ 忽略
        assertEquals(1L, m.snapshot().ignoredTicks)
        assertEquals("被忽略的拍不该扣体力", 100.0, m.snapshot().staminaPercent, 1e-6)
    }

    // ------------------------------------------------------------------ 边界

    @Test
    fun `参数被夹取 极端输入不会把模拟弄成静止或瞬移`() {
        val wild = StaminaConfig(
            enabled = true, decayPerMinute = -5.0, recoverCoefficient = -1.0,
            restAtPercent = 500.0, restSpeedFactor = 9.0, restSecondsCoefficient = 0.0,
            walkSpeed = 0.0, minSpeedFactor = 9.0, randomPercent = 999.0,
            moveIgnoreWindowSec = -3.0, moveIgnoreSpeed = -1.0,
        ).sanitized()
        assertTrue(wild.decayPerMinute > 0)
        assertTrue(wild.recoverCoefficient > 0)
        assertTrue(wild.restAtPercent in 0.0..99.0)
        assertTrue(wild.restSpeedFactor in 0.01..1.0)
        assertTrue(wild.restSecondsCoefficient >= 0.05)
        assertTrue(wild.walkSpeed > 0)
        assertTrue(wild.minSpeedFactor in 0.05..1.0)
        assertTrue(wild.randomPercent <= 60.0)
        assertTrue(wild.moveIgnoreWindowSec >= 0.0)
        assertTrue(wild.moveIgnoreSpeed > 0)
    }

    @Test
    fun `dtSec 非正时不推进模型`() {
        val m = StaminaModel().apply { reset() }
        val before = m.snapshot().staminaPercent
        m.tick(cfg(), 0.0, base, 5.0, rnd)
        m.tick(cfg(), -1.0, base, 5.0, rnd)
        assertEquals(before, m.snapshot().staminaPercent, 1e-9)
        assertEquals(0L, m.snapshot().ignoredTicks)
    }

    @Test
    fun `体力不会越界`() {
        val m = StaminaModel().apply { reset() }
        val c = cfg(decay = 600.0, recover = 600.0)
        m.runAt(c, 600.0, base)
        assertTrue("不应超过 100", m.snapshot().staminaPercent <= 100.0)
        assertTrue("不应低于 0", m.snapshot().staminaPercent >= 0.0)
    }
}
