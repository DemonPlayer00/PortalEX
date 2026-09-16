package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 体力参数的**可调性**钉桩（2026-09-17 重设计后）。
 *
 * 把"参数难以调节"的四类病灶逐条变成断言，避免它们悄悄回来：
 *  1. **人造死参数**：某个旋钮在默认参数下怎么改都不影响曲线（旧版里"休息时速度"与
 *     "休息降速系数"就是一对互相打死的旋钮）。
 *  2. **悬崖**：某组参数让疲劳永不结束、曲线变平线（旧版"冷却时间系数"越过边界即如此）。
 *     现在疲劳是**倒计时预算 + 速率地板**，结构上不可能永不结束。
 *  3. **反推成本**：想知道"多久累一次、歇多久"要联立多个参数 ⇒ 曲线自带 [StaminaCurve.Metrics]，
 *     本文件验证它量得准（页面直接显示，量错就是骗人）。
 *  4. **台阶**：进出疲劳的速度突变 ⇒ 过渡把它摊成斜坡。
 */
class StaminaCycleAnalysisTest {

    private val base = 3.05
    private val dt = 0.25

    private fun cfg(
        decay: Double = 11.0,
        recover: Double = 4.0,
        restAt: Double = 20.0,
        resumeAt: Double = 30.0,
        fatigueSec: Double = 120.0,
        walk: Double = 1.10,
        floor: Double = 0.75,
        transition: Double = 3.0,
        randomPct: Double = 0.0,
    ) = StaminaConfig(
        enabled = true, decayPerMinute = decay, recoverCoefficient = recover,
        restAtPercent = restAt, resumeAtPercent = resumeAt, fatigueSec = fatigueSec,
        transitionSec = transition, walkSpeed = walk, minSpeedFactor = floor,
        randomPercent = randomPct, moveIgnoreWindowSec = 3.0, moveIgnoreSpeed = 12.0,
    )

    // ── 1. 人手写的参数里不该有死旋钮 ────────────────────────────────────────

    /**
     * **每个"形状旋钮"都必须真的改变曲线**（把值改一档，理论曲线的最大偏差要能看出来）。
     *
     * 这是"便于调节"的机械保证：哪个旋钮怎么调曲线都不动，它就是在骗人 ——
     * 旧版的「休息时速度」在默认参数下正是如此（被 0.25 的系数压死）。
     */
    @Test
    fun `每个形状旋钮都必须真的改变曲线`() {
        val baseline = StaminaCurve.simulate(cfg(), base, maxDistanceMeters = 6_000.0)
        val knobs = linkedMapOf<String, StaminaConfig>(
            "体力衰减" to cfg(decay = 11.0 * 1.3),
            "疲劳体力值" to cfg(restAt = 26.0),
            "开跑阈值" to cfg(resumeAt = 34.0),
            "疲劳时长" to cfg(fatigueSec = 120.0 * 1.3),
            "疲劳时速度" to cfg(walk = 1.10 * 1.3),
            "跑动降速下限" to cfg(floor = 0.75 * 0.7),
            "恢复速度" to cfg(recover = 4.0 * 1.3),
            "过渡时间" to cfg(transition = 3.0 * 5.0),
        )
        val rows = ArrayList<String>()
        for ((name, c) in knobs) {
            val other = StaminaCurve.simulate(c, base, maxDistanceMeters = 6_000.0)
            val diff = maxDiff(baseline, other)
            rows.add("%s %.4f".format(name, diff))
            assertTrue("旋钮「$name」改了值却不影响曲线（最大偏差 %.5f）".format(diff), diff > 1e-3)
        }
        println("各旋钮对曲线的最大影响：" + rows.joinToString("｜"))
    }

    /**
     * 两个**非形状**旋钮，明确钉住它们"不该"改变名义曲线：
     *  · 随机化幅度：只改变 ±极值包络的**宽度**（这是设计，不是死参数）；
     *  · 忽略窗口/阈值：只对异常帧（瞬移/补帧）生效，正常配速下不该动曲线。
     */
    @Test
    fun `随机化幅度只改包络宽度 忽略项不改曲线`() {
        val nominal = StaminaCurve.simulate(cfg(randomPct = 15.0), base, maxDistanceMeters = 3_000.0)
        val nominalAgain = StaminaCurve.simulate(
            cfg(randomPct = 15.0), base, decayScale = 1.0, maxDistanceMeters = 3_000.0
        )
        assertEquals("名义曲线不该受随机化幅度影响", 0.0, maxDiff(nominal, nominalAgain), 1e-9)

        val p = 0.15
        val wideLow = StaminaCurve.simulate(
            cfg(randomPct = 15.0), base, decayScale = 1.0 + p, maxDistanceMeters = 3_000.0
        )
        val narrowLow = StaminaCurve.simulate(
            cfg(randomPct = 15.0), base, decayScale = 1.0 + 0.02, maxDistanceMeters = 3_000.0
        )
        assertTrue(
            "随机化幅度应让下界更低（宽 %.4f vs 窄 %.4f）".format(
                maxDiff(nominal, wideLow), maxDiff(nominal, narrowLow)
            ),
            maxDiff(nominal, wideLow) > maxDiff(nominal, narrowLow) * 2
        )

        val protection = StaminaCurve.simulate(
            cfg().copy(moveIgnoreWindowSec = 40.0, moveIgnoreSpeed = 1.0),
            base, maxDistanceMeters = 3_000.0,
        )
        assertEquals(
            "忽略项不该改变正常配速下的曲线", 0.0,
            maxDiff(StaminaCurve.simulate(cfg(), base, maxDistanceMeters = 3_000.0), protection), 1e-9
        )
    }

    private fun maxDiff(a: StaminaCurve.Curve, b: StaminaCurve.Curve): Double {
        val n = minOf(a.size, b.size)
        var worst = 0.0
        for (i in 0 until n) worst = maxOf(worst, abs(a.multiplier[i] - b.multiplier[i]).toDouble())
        return worst
    }

    // ── 2. 悬崖：疲劳一定会结束 ─────────────────────────────────────────────

    /**
     * 极端参数下疲劳**仍然会结束** —— 倒计时速率有地板（[StaminaMath.FATIGUE_RATE_FLOOR]）。
     *
     * 旧口径（冷却积分）在这里会永不结束：实测冷却系数 2.0 时 3 小时窗口里一次疲劳持续
     * 10072 秒，曲线上就是从首次触阈开始的一条平线。
     */
    @Test
    fun `极端参数下疲劳仍然会结束`() {
        val cases = linkedMapOf(
            "衰减拉满" to cfg(decay = 600.0, recover = 0.01),
            "疲劳速度极低" to cfg(walk = 0.10, decay = 200.0),
            "开跑阈值贴顶" to cfg(resumeAt = 99.0, restAt = 0.0, decay = 300.0),
        )
        val rows = ArrayList<String>()
        for ((name, c) in cases) {
            val curve = StaminaCurve.simulate(c, base, maxDistanceMeters = 10_000.0, dtSec = 0.25)
            val m = curve.metrics
            rows.add("%s 疲劳 %d 次/首次 %.0f 秒".format(name, curve.restCount, m.firstFatigueSec))
            if (m.hasFatigue) {
                assertTrue(
                    "「$name」的首次疲劳必须结束（实得 %.1f 秒）".format(m.firstFatigueSec),
                    m.firstFatigueSec > 0.0
                )
                // 速率地板 0.25 ⇒ 最长约为预算的 4 倍（这里预算 120 秒）
                assertTrue(
                    "「$name」首次疲劳不该超过预算的 5 倍（实得 %.1f 秒）".format(m.firstFatigueSec),
                    m.firstFatigueSec <= 120.0 * 5.0
                )
            }
        }
        println("极端参数下的疲劳：" + rows.joinToString("｜"))
    }

    // ── 3. 结果指标量得准（页面直接显示它）────────────────────────────────

    @Test
    fun `结果指标与曲线实际形态一致`() {
        val c = cfg()
        val curve = StaminaCurve.simulate(c, base, maxDistanceMeters = 10_000.0, dtSec = dt)
        val m = curve.metrics
        val walkMultiplier = c.walkSpeed / base

        val firstPlateau = (0 until curve.size).first { abs(curve.multiplier[it] - walkMultiplier) < 1e-4 }
        val plateauEnd = (firstPlateau until curve.size)
            .first { abs(curve.multiplier[it] - walkMultiplier) >= 1e-4 }

        // ⚠️ 指标记的是**状态切换**的时刻，而曲线上的"倍率到位"发生在过渡（3 秒）之后：
        //    两者相差恰为一次过渡，不能要求相等。这里就按"状态先于到位、且差值 ≈ 过渡时长"来断言。
        val plateauStartSec = firstPlateau * dt
        assertTrue(
            "首次疲劳状态应先于倍率到位（状态 %.1f 秒 vs 到位 %.1f 秒）"
                .format(m.firstFatigueStartSec, plateauStartSec),
            m.firstFatigueStartSec <= plateauStartSec + 1e-6
        )
        assertTrue(
            "两者差值应约等于过渡时长 3 秒（实得 %.1f 秒）".format(plateauStartSec - m.firstFatigueStartSec),
            plateauStartSec - m.firstFatigueStartSec in 0.0..(c.transitionSec + dt)
        )
        assertTrue(
            "首次疲劳距离应不小于状态切换处（%.1f vs %.1f m）"
                .format(curve.distanceM[firstPlateau].toDouble(), m.firstFatigueDistanceM),
            curve.distanceM[firstPlateau].toDouble() >= m.firstFatigueDistanceM - 1e-6
        )
        assertTrue("首次疲劳必须已结束（实得 %.1f 秒）".format(m.firstFatigueSec), m.firstFatigueSec > 0.0)

        val plateauSec = (plateauEnd - firstPlateau) * dt
        assertTrue(
            "指标里的疲劳时长 %.1f 秒应与曲线上的疲劳段 %.1f 秒同量级".format(m.firstFatigueSec, plateauSec),
            abs(m.firstFatigueSec - plateauSec) < 10.0
        )
        assertEquals("疲劳期实际速度", c.walkSpeed, m.fatigueSpeedMps, 0.02)
        assertTrue(
            "平均速度应在疲劳速度与基础速度之间（实得 %.2f）".format(m.averageSpeedMps),
            m.averageSpeedMps in c.walkSpeed..base
        )
        assertTrue(
            "周期应大于一次疲劳（周期 %.0f 秒 / 疲劳 %.0f 秒）".format(m.firstCycleSec, m.firstFatigueSec),
            m.firstCycleSec > m.firstFatigueSec
        )
        println(
            "默认参数结果指标：首次疲劳 %.0f 秒（%.1f km）⇒ 疲劳 %.0f 秒（%.1f%%→%.1f%%）｜周期 %.0f 秒｜疲劳时 %.2f m/s｜均速 %.2f m/s"
                .format(
                    m.firstFatigueStartSec, m.firstFatigueDistanceM / 1000.0, m.firstFatigueSec,
                    m.firstFatigueEntryPercent, m.firstFatigueExitPercent,
                    m.firstCycleSec, m.fatigueSpeedMps, m.averageSpeedMps
                )
        )
    }

    // ── 4. 过渡：台阶 → 斜坡，且每次时长不同 ────────────────────────────────

    private fun maxMultiplierStep(c: StaminaConfig, seconds: Double): Double {
        val model = StaminaModel()
        val rnd = Random(7)
        var prev = model.snapshot().speedScale
        var maxStep = 0.0
        repeat((seconds / dt).toInt()) {
            model.tick(c, dt, base, base * prev * dt, rnd)
            val now = model.snapshot().speedScale
            maxStep = maxOf(maxStep, abs(now - prev))
            prev = now
        }
        return maxStep
    }

    @Test
    fun `过渡把疲劳开始的台阶摊成斜坡`() {
        val stepMax = maxMultiplierStep(cfg(transition = 0.0), 45.0 * 60.0)
        val smoothMax = maxMultiplierStep(cfg(transition = 3.0), 45.0 * 60.0)
        println("每拍最大倍率变化：无过渡 %.4f；过渡 3 秒 %.4f".format(stepMax, smoothMax))
        assertTrue("无过渡时必须是一条大台阶（实际 %.4f）".format(stepMax), stepMax > 0.3)
        assertTrue("有过渡时每拍只能走一小步（实际 %.4f）".format(smoothMax), smoothMax < 0.05)
    }

    /**
     * 疲劳时长与过渡时长**每次重新随机**。
     *
     * 疲劳预算直接从快照里读（进疲劳那一拍按下的数）；过渡时长由"过渡第一拍的位移反解"
     * —— 按"走完用了几拍"去量会被 Δt 量化成 0.25 的整数倍，量不出真实值（踩过）。
     */
    @Test
    fun `疲劳时长与过渡时长都每次重新随机`() {
        // 过渡频繁的参数：衰减远大于恢复 ⇒ 一分钟能进出好几次
        val c = cfg(
            decay = 200.0, recover = 100.0, restAt = 80.0, resumeAt = 85.0,
            fatigueSec = 20.0, transition = 2.0, randomPct = 30.0,
        )
        val model = StaminaModel()
        // 随机源必须**跨拍复用**：每拍 Random(11) 会把随机钉成常数（也踩过）
        val rnd = Random(11)
        val budgets = ArrayList<Double>()
        val transitionSeconds = ArrayList<Double>()
        var prevResting = false

        repeat((20.0 * 60.0 / dt).toInt()) {
            val before = model.snapshot()
            model.tick(c, dt, base, base * before.speedScale * dt, rnd)
            val now = model.snapshot()
            if (now.resting && !prevResting) budgets.add(now.fatigueRemainingSec)
            if (now.resting != prevResting) {
                val stepOfBlend = abs(now.blend - before.blend)
                if (stepOfBlend > 0.0) transitionSeconds.add(dt / stepOfBlend)
            }
            prevResting = now.resting
        }

        println(
            "抽到的疲劳预算（秒）=${budgets.take(10).map { "%.2f".format(it) }} ｜ " +
                    "过渡时长（秒）=${transitionSeconds.take(8).map { "%.2f".format(it) }}"
        )
        assertTrue("样本不足（疲劳 ${budgets.size} 次）", budgets.size >= 5)
        assertTrue("疲劳时长必须每次不同（${budgets.distinct().size} 个取值）", budgets.distinct().size >= 3)
        assertTrue(
            "疲劳预算应落在 ±30%% 内（名义 20 秒，实测 %.2f~%.2f）".format(budgets.min(), budgets.max()),
            budgets.min() >= 20.0 * 0.7 - 1e-6 && budgets.max() <= 20.0 * 1.3 + 1e-6
        )
        assertTrue(
            "过渡时长也必须每次不同（${transitionSeconds.distinct().size} 个取值）",
            transitionSeconds.distinct().size >= 3
        )
    }

    // ── 5. 旧口径反例：无迟滞必然抖动（"为什么要倒计时"的证据）────────────

    /**
     * 反例：**退出也看体力阈值**（重设计之前的旧口径）。
     * 它必然抖成一拍一次 —— 这一条是"疲劳必须由预算/迟滞结束"的理由。
     */
    @Test
    fun `对照：无迟滞的旧口径下 九成以上的疲劳段只有一拍`() {
        val c = cfg()
        var s = 100.0
        var resting = false
        var rests = 0
        var oneTick = 0
        var current = 0
        var m = StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, s), 0.0)
        repeat((45.0 * 60.0 / dt).toInt()) {
            s += c.recoverCoefficient / 60.0 * StaminaMath.recoveryFactor(s) * dt
            s -= c.decayPerMinute / 60.0 * m * dt
            s = s.coerceIn(0.0, 100.0)
            if (!resting && s <= c.restAtPercent) {
                resting = true; rests += 1
            } else if (resting && s > c.restAtPercent) {   // ← 旧口径：同一个阈值
                resting = false
            }
            m = StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, s), if (resting) 1.0 else 0.0)
            if (resting) current++ else if (current > 0) {
                if (current == 1) oneTick++
                current = 0
            }
        }
        println("旧口径（无迟滞）：45 分钟内进疲劳 $rests 次，其中 $oneTick 次只持续一拍")
        assertTrue("旧口径必须是抖的（进疲劳次数 $rests 应很多）", rests > 300)
        assertTrue("旧口径下九成以上只有一拍", oneTick >= rests * 9 / 10)
    }

    // ── 6. 疲劳速度只有"疲劳时速度"一个来源 ────────────────────────────────

    @Test
    fun `疲劳速度恒等于配置的疲劳时速度`() {
        val c = cfg()
        val speeds = (c.restAtPercent.toInt()..100 step 5).map { s ->
            StaminaMath.multiplierFor(c, base, StaminaMath.fatigueFactor(c, s.toDouble()), 1.0) * base
        }
        println("疲劳速度范围：%.4f ~ %.4f m/s（配置 %.2f）".format(speeds.min(), speeds.max(), c.walkSpeed))
        assertEquals("疲劳速度必须恒等于配置值", c.walkSpeed, speeds.min(), 1e-9)
        assertEquals("疲劳速度必须恒等于配置值", c.walkSpeed, speeds.max(), 1e-9)
    }

    // ── 7. 出厂默认必须"开箱即用" ───────────────────────────────────────────

    /**
     * **默认值必须落在健康区间**（页面上的「重置数据」就是恢复到这组值）。
     *
     * 用户口径（2026-09-17）：**1.5 km 内 1 次疲劳、2 km 内 2 次** —— 默认那 2 km 窗口里
     * 就要看得见这个模式。这条同时防"改默认值顺手把开箱体验弄坏"。
     */
    @Test
    fun `出厂默认必须落在健康区间`() {
        val c = StaminaConfig(enabled = true)
        val limit = StaminaCurve.decayLimitForRecovery(c, base)
        val curve = StaminaCurve.simulate(c, base, maxDistanceMeters = 3_000.0)
        val m = curve.metrics

        println(
            "出厂默认：衰减 %.1f（回血上限 %.1f）｜疲劳体力值 %.0f%% / 开跑阈值 %.0f%% ｜ 首次疲劳 %.2f km / %.1f 分 ⇒ 疲劳 %.0f 秒 ｜ 周期 %.0f 秒 ｜ 均速 %.2f m/s ｜ 1.5km 内 %d 次 / 2km 内 %d 次"
                .format(
                    c.decayPerMinute, limit, c.restAtPercent, c.resumeAtPercent,
                    m.firstFatigueDistanceM / 1000.0, m.firstFatigueStartSec / 60.0,
                    m.firstFatigueSec, m.firstCycleSec, m.averageSpeedMps,
                    m.fatigueCountWithin(1_500.0), m.fatigueCountWithin(2_000.0),
                )
        )
        assertTrue("默认衰减必须留下余量：%.1f < %.1f".format(c.decayPerMinute, limit),
            c.decayPerMinute < limit * 0.9)
        assertTrue("默认疲劳速度必须真的比跑动慢", StaminaCurve.fatigueSlowsDown(c, base))
        assertTrue("默认参数必须会疲劳", m.hasFatigue)
        // 目标 1：1.5 km 内 1 次、2 km 内 2 次（名义曲线必须**正好**是这样）
        assertEquals("1.5 km 内的疲劳次数", 1, m.fatigueCountWithin(1_500.0))
        assertEquals("2 km 内的疲劳次数", 2, m.fatigueCountWithin(2_000.0))
        assertTrue("首次疲劳应在 1.0~1.5 km（实得 %.2f km）".format(m.firstFatigueDistanceM / 1000.0),
            m.firstFatigueDistanceM in 1_000.0..1_500.0)
        assertTrue("单次疲劳应在 1~5 分钟（实得 %.0f 秒）".format(m.firstFatigueSec),
            m.firstFatigueSec in 60.0..300.0)

        // 目标 2：带随机时也要**大多数情况**满足，而不只是名义值满足
        var hit15 = 0
        var hit20 = 0
        val samples = 30
        for (seed in 1..samples) {
            val s = StaminaCurve.sample(
                c, base, maxDistanceMeters = 2_500.0, dtSec = 0.1, random = Random(seed.toLong())
            ).metrics
            if (s.fatigueCountWithin(1_500.0) >= 1) hit15++
            if (s.fatigueCountWithin(2_000.0) >= 2) hit20++
        }
        println("随机采样 $samples 次：1.5km 内 ≥1 次 $hit15/$samples；2km 内 ≥2 次 $hit20/$samples")
        assertTrue("带随机也应多数命中：1.5km 内 ≥1（实得 $hit15/$samples）", hit15 >= samples / 2)
        assertTrue("带随机也应多数命中：2km 内 ≥2（实得 $hit20/$samples）", hit20 >= samples / 2)
    }
}
