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
        val m = StaminaMath.multiplierFor(
            c, base, StaminaMath.fatigueFactor(c, stamina), if (resting) 1.0 else 0.0
        )
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
        var m = StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, s), 0.0)
        repeat((seconds / dt).toInt()) {
            s += c.recoverCoefficient / 60.0 * StaminaMath.recoveryFactor(s) / c.restSecondsCoefficient * dt
            s -= c.decayPerMinute / 60.0 * m * dt
            s = s.coerceIn(0.0, 100.0)
            if (!resting && s <= c.restAtPercent) {
                resting = true; rests += 1
            } else if (resting && s > c.restAtPercent) {   // ← 旧口径：同一个阈值
                resting = false
            }
            // 反例只看"有没有迟滞"，过渡不参与 ⇒ 直接取 0/1 档
            m = StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, s), if (resting) 1.0 else 0.0)
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
        val rnd = kotlin.random.Random(1)
        val seconds = ArrayList<Double>()
        val unfinished = ArrayList<Double>()
        var currentTicks = 0
        var entryStamina = 0.0
        val ticks = (45.0 * 60.0 / dt).toInt()

        repeat(ticks) {
            val before = model.snapshot()
            model.tick(c, dt, base, base * before.speedScale * dt, rnd)
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
     * [StaminaConfig.restSecondsCoefficient] 除。于是"疲劳能不能结束"取决于
     * **恢复曲线与消耗线有没有交点，且交点在开跑阈值之上**：
     *
     * ```
     * 恢复(体力) = 恢复速度系数 ÷ 冷却时间系数 × (2 − 体力/100)
     * 消耗       = 衰减系数 × 走路速度 ÷ 基础速度
     * 交点 体力* = 100 × (2 − 消耗 × 冷却 ÷ 恢复)
     * 疲劳能结束 ⟺ 体力* > 开跑阈值
     * ⇒ 冷却时间系数 < 恢复 × (2 − 开跑阈值/100) ÷ (衰减 × 走路/基础)
     * ```
     *
     * ⚠️ 这里纠正我先前说过的一个**更严的错数**：当时拿"体力=100%"那一点算，
     * 得出 1.008。那只是"满体力时还回得动血"的条件；**疲劳能否结束看的是交点在不在
     * 开跑阈值之上**，默认参数下真正的边界是 **1.713**（冷却在 1.008~1.713 之间时
     * 疲劳仍然会结束，只是慢得多）。
     */
    @Test
    fun `边界：冷却时间系数过大时 疲劳永远出不来`() {
        val c = cfg()
        val consumption = c.decayPerMinute * c.walkSpeed / base
        val boundaryAtFull = c.recoverCoefficient / consumption                       // 满体力还能回血
        val boundaryEnds = c.recoverCoefficient * (2.0 - c.resumeAtPercent / 100.0) / consumption // 疲劳能结束
        val okLongest = longestFatigueSeconds(c, seconds = 3 * 3600.0)
        val slowLongest = longestFatigueSeconds(c.copy(restSecondsCoefficient = 1.6), 3 * 3600.0)
        val badLongest = longestFatigueSeconds(c.copy(restSecondsCoefficient = 2.0), 3 * 3600.0)

        println(
            ("边界：满体力可回血 %.3f；疲劳能结束 %.3f（交点须高于开跑阈值 %.0f%%）｜" +
                    "冷却 1.0 最长疲劳 %.0f 秒；1.6 → %.0f 秒；2.0 → %.0f 秒")
                .format(boundaryAtFull, boundaryEnds, c.resumeAtPercent, okLongest, slowLongest, badLongest)
        )
        assertEquals("满体力可回血边界", 1.008, boundaryAtFull, 0.01)
        assertEquals("疲劳能结束的边界", 1.713, boundaryEnds, 0.01)
        assertTrue("默认冷却下疲劳应能结束（最长 %.0f 秒）".format(okLongest), okLongest in 200.0..900.0)
        assertTrue("冷却 1.6（在两条边界之间）疲劳仍能结束，但要久得多（最长 %.0f 秒）".format(slowLongest),
            slowLongest > okLongest * 1.5 && slowLongest < 3600.0)
        assertTrue("冷却 2.0 越过边界 ⇒ 疲劳再也不结束（最长 %.0f 秒）".format(badLongest), badLongest > 3600.0)
    }

    /** 跑 [seconds] 秒，返回**最长的一段连续疲劳**（秒） */
    private fun longestFatigueSeconds(c: StaminaConfig, seconds: Double): Double {
        val model = StaminaModel()
        val rnd = kotlin.random.Random(1)
        var current = 0
        var longest = 0
        repeat((seconds / dt).toInt()) {
            val before = model.snapshot()
            model.tick(c, dt, base, base * before.speedScale * dt, rnd)
            if (model.snapshot().resting) {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest * dt
    }

    /**
     * **疲劳状态下的速度到底是固定值还是跟剩余体力有关**（用户问）。
     *
     * 公式是 `max(疲劳倍率(体力) × 休息降速系数, 走路/基础)`：
     *  - 前半段**确实**与体力有关（疲劳倍率随体力线性上升）；
     *  - 后半段是**走路下限**（用户口径），一旦下限咬住，速度就被钉成一个常数。
     *
     * 咬住的边界是 `系数 > 走路/基础 ÷ 疲劳倍率(体力)`，而疲劳倍率本身随体力从
     * [minSpeedFactor] 升到 1.0 ⇒ 边界从 `走路/基础 ÷ minSpeedFactor` 一路降到 `走路/基础`。
     * 默认参数：走路/基础 = 0.3607、minSpeedFactor = 0.75 ⇒ 边界在 **0.481 ~ 0.361** 之间，
     * 而默认系数是 0.25 ⇒ **全程被下限咬住 ⇒ 疲劳速度是固定值 = 走路速度**。
     */
    @Test
    fun `疲劳速度：默认参数下是固定值 系数够大才随体力变化`() {
        val c = cfg()
        val walkSpeed = c.walkSpeed

        // 默认参数：疲劳期可能出现的整个体力区间内，实速恒定
        val speeds = (c.restAtPercent.toInt()..100 step 2).map { s ->
            StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, s.toDouble()), 1.0) * base
        }
        println("默认系数 %.2f：疲劳速度 min=%.4f max=%.4f m/s（走路值 %.2f）"
            .format(c.restSpeedFactor, speeds.min(), speeds.max(), walkSpeed))
        assertEquals("疲劳速度必须恒等于走路值", walkSpeed, speeds.min(), 1e-9)
        assertEquals("疲劳速度必须恒等于走路值", walkSpeed, speeds.max(), 1e-9)

        // 系数够大时下限松开，速度开始随体力上升
        val loose = c.copy(restSpeedFactor = 0.60)
        val atLow = StaminaMath.multiplierFor(loose, base, StaminaMath.fatigueFactor(loose, 30.0), 1.0) * base
        val atHigh = StaminaMath.multiplierFor(loose, base, StaminaMath.fatigueFactor(loose, 100.0), 1.0) * base
        println("系数 %.2f：体力 30%% 时 %.3f m/s ⇒ 体力 100%% 时 %.3f m/s（随体力上升）"
            .format(loose.restSpeedFactor, atLow, atHigh))
        assertTrue("系数够大时必须随体力上升（%.3f ⇒ %.3f）".format(atLow, atHigh), atHigh > atLow)

        // 边界：咬住/松开的临界系数
        val kLow = c.walkSpeed / base / StaminaMath.fatigueFactor(c, c.restAtPercent)   // 体力=阈值
        val kHigh = c.walkSpeed / base / StaminaMath.fatigueFactor(c, 100.0)            // 体力=100%
        println("边界系数：体力在阈值 %.1f%% 时 %.3f，体力 100%% 时 %.3f".format(c.restAtPercent, kLow, kHigh))
        assertEquals("阈值处边界", 0.481, kLow, 0.005)
        assertEquals("满体力边界", 0.361, kHigh, 0.005)
    }

    /** 跑 [seconds] 秒，返回每拍倍率的最大变化量（台阶 ⇒ 一次大跳；斜坡 ⇒ 每拍小步） */
    private fun maxMultiplierStep(c: StaminaConfig, seconds: Double): Double {
        val model = StaminaModel()
        val rnd = kotlin.random.Random(7)
        var prev = model.snapshot().speedScale
        var maxStep = 0.0
        repeat((seconds / dt).toInt()) {
            model.tick(c, dt, base, base * prev * dt, rnd)
            val now = model.snapshot().speedScale
            maxStep = maxOf(maxStep, kotlin.math.abs(now - prev))
            prev = now
        }
        return maxStep
    }

    @Test
    fun `过渡把疲劳开始的台阶摊成斜坡`() {
        val stepped = cfg().copy(transitionSec = 0.0)     // 0 = 立即切换（旧行为）
        val smoothed = cfg()                             // 默认 3 秒过渡
        val stepMax = maxMultiplierStep(stepped, 45.0 * 60.0)
        val smoothMax = maxMultiplierStep(smoothed, 45.0 * 60.0)

        println("每拍最大倍率变化：无过渡 %.4f（一步跨掉 0.75−0.3607=0.389）；过渡 3 秒 %.4f"
            .format(stepMax, smoothMax))
        assertTrue("无过渡时必须是一条大台阶（实际 %.4f）".format(stepMax), stepMax > 0.3)
        assertTrue("有过渡时每拍只能走一小步（实际 %.4f）".format(smoothMax), smoothMax < 0.05)
        // 理论值：跨幅 × dt / 过渡时长 = 0.389 × 0.25 / 3 ≈ 0.0325
        assertEquals("每拍步长应等于 跨幅×dt/过渡时长", 0.0325, smoothMax, 0.01)
    }

    /**
     * 过渡时长**每次重新随机**。
     *
     * 用一组"过渡很频繁"的参数（衰减远大于恢复 ⇒ 一分钟能进出好几次），
     * 记录每次方向变化后 blend 走完所需拍数，看它是不是同一个数。
     */
    @Test
    fun `过渡时长每次重新随机`() {
        // ⚠️ 参数不能随便挑：跑侧平衡点必须**高于**休息体力值，否则体力从上方趋近却永远碰不到阈值
        //    （第一次就踩了：restAt=50 时净速率恰好在 50% 处归零 ⇒ 一次过渡都没发生）
        val c = cfg().copy(
            decayPerMinute = 200.0, recoverCoefficient = 100.0,
            restAtPercent = 80.0, resumeAtPercent = 85.0,
            transitionSec = 2.0, randomPercent = 30.0,
        )
        val model = StaminaModel()
        // ⚠️ 随机源必须**跨拍复用**：每拍 `Random(11)` 会把随机钉成常数，
        //    测出来的"随机化"永远是同一个数（这个坑我自己先踩了一次）
        val rnd = kotlin.random.Random(11)
        val durations = ArrayList<Double>()
        var prevTarget = 0.0

        repeat((20.0 * 60.0 / dt).toInt()) {
            val before = model.snapshot()
            model.tick(c, dt, base, base * before.speedScale * dt, rnd)
            val now = model.snapshot()
            val target = if (now.resting) 1.0 else 0.0
            if (target != prevTarget) {
                prevTarget = target
                // 过渡的第一拍位移 = dt / 本次抽到的时长 ⇒ 反解出**本次过渡到底抽了多少秒**
                // （按"走完用了几拍"去量会被 dt 量化成 0.25 的整数倍，量不出真实值）
                val stepOfBlend = kotlin.math.abs(now.blend - before.blend)
                if (stepOfBlend > 0.0) durations.add(dt / stepOfBlend)
            }
        }

        val distinct = durations.map { "%.2f".format(it) }.distinct()
        println("抽到的过渡时长（秒）=${durations.take(12).map { "%.2f".format(it) }}；不同取值 ${distinct.size} 个")
        assertTrue("样本太少，说明这组参数没跑出足够的过渡（${durations.size} 次）", durations.size >= 5)
        assertTrue("过渡时长必须每次都不一样（不同取值 ${distinct.size} 个）", distinct.size >= 3)
        assertTrue(
            "随机幅度必须落在 ±30%% 内（名义 2.0 ⇒ 1.4~2.6，实测 %.2f~%.2f）"
                .format(durations.min(), durations.max()),
            durations.min() >= 2.0 * 0.7 - 1e-6 && durations.max() <= 2.0 * 1.3 + 1e-6
        )
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
