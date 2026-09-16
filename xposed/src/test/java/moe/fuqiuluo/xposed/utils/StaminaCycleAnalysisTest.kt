package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 疲劳状态的**迟滞**（开跑阈值）单测 + 反例对照。
 *
 * 为什么需要这个文件：加入"开跑阈值"之前，进出疲劳用的是**同一个体力阈值**，
 * 而阈值两侧的净速率方向相反（跑侧净消耗、歇侧净恢复）⇒ 系统必然退化成
 * **继电器振荡**：实测默认参数 45 分钟进疲劳 795 次、**每次都只有一拍**、
 * 速度每 1.5 秒在 0.57 ↔ 2.29 m/s 之间跳。
 *
 * 这里做两件事：
 *  1. 把"无迟滞必然抖动"做成**可执行的对照**（[chatterCounterExample] 里保留旧口径），
 *     这样"为什么要有开跑阈值"不靠注释、靠一条会红的断言；
 *  2. 把新口径的**疲劳段长度**钉成区间（真的一段，而不是一拍）。
 */
class StaminaCycleAnalysisTest {

    private val base = 3.05
    private val dt = 0.25

    private fun cfg(resumeAt: Double = 30.0) = StaminaConfig(
        enabled = true, decayPerMinute = 11.0, recoverCoefficient = 4.0,
        restAtPercent = 20.0, resumeAtPercent = resumeAt, restSpeedFactor = 0.25,
        restSecondsCoefficient = 1.0, walkSpeed = 1.10, minSpeedFactor = 0.75,
        randomPercent = 0.0, moveIgnoreWindowSec = 3.0, moveIgnoreSpeed = 12.0,
    )

    /** 某一点体力在"该模式"下的净变化速率（点/分） */
    private fun netRatePerMinute(c: StaminaConfig, stamina: Double, resting: Boolean): Double {
        val m = StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, stamina), resting)
        val recovery = c.recoverCoefficient * StaminaMath.recoveryFactor(stamina) / c.restSecondsCoefficient
        return recovery - c.decayPerMinute * m
    }

    @Test
    fun `阈值两侧各自单向漂移 —— 这就是必须加迟滞的原因`() {
        val c = cfg()
        val runSide = netRatePerMinute(c, c.restAtPercent + 0.02, resting = false)
        val restSide = netRatePerMinute(c, c.restAtPercent + 0.02, resting = true)

        println("阈值两侧净速率：跑 %+.2f 点/分，疲劳 %+.2f 点/分".format(runSide, restSide))
        assertTrue("跑着的时候必须净消耗（实际 %+.2f）".format(runSide), runSide < 0.0)
        assertTrue("疲劳时若净消耗，出了阈值就回不去（实际 %+.2f）".format(restSide), restSide > 0.0)
        assertEquals("跑侧净速率", -1.05, runSide, 0.05)
        // 疲劳侧的速度现在是"走路下限"接管（max 口径），不再是被 0.25 系数压到 0.57 m/s 的那一版
        assertEquals("疲劳侧净速率", +3.23, restSide, 0.05)
    }

    /**
     * 反例：**退出也看体力阈值**（改造前的口径）。
     * 它必然抖 —— 这一条就是"开跑阈值"存在的理由。
     */
    private fun chatterCounterExample(c: StaminaConfig, seconds: Double): Pair<Int, Int> {
        var s = 100.0
        var resting = false
        var rests = 0
        var oneTickSegments = 0
        var current = 0
        var m = StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, s), resting)
        repeat((seconds / dt).toInt()) {
            s += c.recoverCoefficient / 60.0 * StaminaMath.recoveryFactor(s) / c.restSecondsCoefficient * dt
            s -= c.decayPerMinute / 60.0 * m * dt
            s = s.coerceIn(0.0, 100.0)
            if (!resting && s <= c.restAtPercent) {
                resting = true; rests += 1
            } else if (resting && s > c.restAtPercent) {   // ← 旧口径：同一个阈值
                resting = false
            }
            m = StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, s), resting)
            if (resting) current++ else if (current > 0) {
                if (current == 1) oneTickSegments++
                current = 0
            }
        }
        return rests to oneTickSegments
    }

    @Test
    fun `对照：无迟滞的旧口径下 九成以上的疲劳段只有一拍`() {
        val (rests, oneTick) = chatterCounterExample(cfg(), seconds = 45.0 * 60.0)
        println("旧口径（无迟滞）：45 分钟内进疲劳 $rests 次，其中 $oneTick 次只持续一拍")
        assertTrue("旧口径必须是抖的（进疲劳次数 $rests 应很多）", rests > 300)
        assertTrue("旧口径下九成以上只有一拍", oneTick >= rests * 9 / 10)
    }

    /**
     * 新口径：疲劳是**真的一段**。
     *
     * 输出逐段列出（含"未完"标记 —— 45 分钟窗口可能把最后一段截断，
     * 不标出来就会被读成"第二段只有 73 秒"这种假象）。
     */
    @Test
    fun `新口径下 疲劳段是真的一段而不是一拍`() {
        val c = cfg()
        val model = StaminaModel()
        val seconds = ArrayList<Double>()
        val unfinished = ArrayList<Double>()
        var currentTicks = 0
        var entryStamina = 0.0
        val ticks = (45.0 * 60.0 / dt).toInt()

        repeat(ticks) {
            val before = model.snapshot()
            model.tick(c, dt, base, base * before.speedScale * dt, kotlin.random.Random(1))
            val now = model.snapshot()
            if (now.resting) {
                if (currentTicks == 0) entryStamina = now.staminaPercent
                currentTicks++
            } else if (currentTicks > 0) {
                seconds.add(currentTicks * dt)
                println("  疲劳段：%.0f 秒（进入时体力 %.1f%% ⇒ 结束时 %.1f%%）"
                    .format(currentTicks * dt, entryStamina, now.staminaPercent))
                currentTicks = 0
            }
        }
        if (currentTicks > 0) unfinished.add(currentTicks * dt)

        val snapshot = model.snapshot()
        println(
            "新口径（开跑阈值 30%%）：45 分钟内进疲劳 %d 次；完整段=%s；未完段（被窗口截断）=%s；累计疲劳 %.1f 分钟"
                .format(snapshot.restCount, seconds.map { "%.0f 秒".format(it) },
                    unfinished.map { "%.0f 秒".format(it) }, snapshot.restTotalSec / 60.0)
        )
        assertTrue("45 分钟内不该一进再进（实际 ${snapshot.restCount} 次）", snapshot.restCount in 1..8)
        assertTrue("至少要有一段完整的疲劳，且达到分钟级（实际 $seconds）",
            seconds.any { it >= 180.0 })
        assertTrue("不该再出现「一拍就结束」的疲劳段", seconds.none { it <= dt })
        assertTrue("累计疲劳时间应占可观比例（实际 %.1f 分）".format(snapshot.restTotalSec / 60.0),
            snapshot.restTotalSec > 180.0)
    }

    /**
     * **参数边界**：疲劳期间以走路速度前进，消耗 = `衰减 × 走路/基础`；恢复被
     * [StaminaConfig.restSecondsCoefficient] 除。于是"歇着能不能回血"是有条件的：
     *
     * ```
     * 恢复(满体力) = 恢复速度系数 ÷ 冷却时间系数 × 1.0
     * 疲劳期消耗    = 衰减系数 × 走路速度 ÷ 基础速度
     * 必须  恢复 > 消耗  ⇒  冷却时间系数 < 恢复 ÷ (衰减 × 走路/基础)
     * 默认代入：4 ÷ (11 × 1.10/3.05) = 1.008
     * ```
     * 也就是说**默认值只有 0.8% 的余量**：冷却时间系数一旦 ≥ 1.01，体力再也回不到
     * 开跑阈值以上，疲劳状态**永远出不来**（图上就是一条从首次触阈开始的平线）。
     */
    @Test
    fun `边界：冷却时间系数过大时 疲劳永远出不来`() {
        val c = cfg()
        val boundary = c.recoverCoefficient / (c.decayPerMinute * c.walkSpeed / base)
        val okLongest = longestFatigueSeconds(c, seconds = 3 * 3600.0)
        val badCfg = c.copy(restSecondsCoefficient = 2.0)
        val badLongest = longestFatigueSeconds(badCfg, seconds = 3 * 3600.0)

        println(
            "边界系数 = %.3f（= 恢复 ÷ (衰减 × 走路/基础)）；冷却=1.0 时最长疲劳 %.0f 秒；冷却=2.0 时最长疲劳 %.0f 秒"
                .format(boundary, okLongest, badLongest)
        )
        assertEquals("默认参数下的边界", 1.008, boundary, 0.01)
        assertTrue("默认冷却下疲劳应能结束（最长 %.0f 秒）".format(okLongest), okLongest in 200.0..900.0)
        assertTrue("冷却 2.0 时疲劳应当再也不结束（最长 %.0f 秒）".format(badLongest), badLongest > 3600.0)
    }

    /** 跑 [seconds] 秒，返回**最长的一段连续疲劳**（秒） */
    private fun longestFatigueSeconds(c: StaminaConfig, seconds: Double): Double {
        val model = StaminaModel()
        var current = 0
        var longest = 0
        repeat((seconds / dt).toInt()) {
            val before = model.snapshot()
            model.tick(c, dt, base, base * before.speedScale * dt, kotlin.random.Random(1))
            if (model.snapshot().resting) {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest * dt
    }

    /** 语义守卫：开跑阈值必须高于休息体力值，否则迟滞消失（夹取兜底） */
    @Test
    fun `开跑阈值被夹在休息体力值之上`() {
        val bad = cfg(resumeAt = 5.0)          // 比休息体力值 20 还低 ⇒ 倒挂
        assertEquals("倒挂必须被夹到 restAt+1", 21.0, bad.sanitized().resumeAtPercent, 1e-9)

        val tooHigh = cfg(resumeAt = 500.0)
        assertEquals("上限 99%", 99.0, tooHigh.sanitized().resumeAtPercent, 1e-9)
    }
}
