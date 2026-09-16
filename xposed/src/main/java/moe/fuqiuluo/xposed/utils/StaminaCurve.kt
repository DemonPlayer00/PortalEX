package moe.fuqiuluo.xposed.utils

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 倍率数学的**唯一实现**（模型与曲线预览共用）。
 *
 * 为什么单独抽出来：[StaminaModel] 每拍算一次倍率，「按距离预览倍率曲线」又要算同一组公式。
 * 两处各写一份的话，预览图与实际跑出来的节奏迟早对不上 —— 而这种漂移没有任何测试会自己暴露，
 * 只有用户发现"图上说到 2km 才累、实际 1.5km 就掉速"才会被当成 bug 报上来。
 */
internal object StaminaMath {

    /** 恢复倍率：体力 100% → 1.0，50% → 1.5，**0% → 2.0（最快）** */
    fun recoveryFactor(staminaPercent: Double): Double =
        2.0 - staminaPercent.coerceIn(0.0, 100.0) / 100.0

    /** 疲劳速度倍率：体力从 100 降到休息阈值时，从 1.0 平滑降到 [StaminaConfig.minSpeedFactor] */
    fun fatigueFactor(c: StaminaConfig, staminaPercent: Double): Double {
        val span = (100.0 - c.restAtPercent).coerceAtLeast(1e-6)
        val t = ((staminaPercent - c.restAtPercent) / span).coerceIn(0.0, 1.0)
        return c.minSpeedFactor + (1.0 - c.minSpeedFactor) * t
    }

    /**
     * 最终倍率 = **跑动档与疲劳档之间的混合**。
     *
     * ```
     * 跑动档 = min(疲劳倍率(体力), 1.0)
     * 疲劳档 = 走路速度 / 基础速度
     * 最终   = 跑动档 + (疲劳档 − 跑动档) × blend          ← blend ∈ [0,1]
     * ```
     *
     * [blend] 是**状态**（谁在推进谁维护：模型里逐拍收敛，曲线里同一步长积分），
     * 不是一次性插值 —— 否则"过渡途中又反向"会算出不属于任何一档的速度。
     * `blend = 0/1` 时结果与"没有过渡"逐位相同。
     *
     * ⚠️ 疲劳档**只有走路速度一个来源**（2026-09-17 重设计）：原先还有
     * `疲劳倍率 × 休息降速系数`，于是"休息时速度"与"休息降速系数"抢同一个结果 ——
     * 默认参数下系数赢（实际 0.57 m/s），系数一调大就换成走路值赢，**总有一个是死的**。
     * 用户口径：一个旋钮一个结果。
     */
    fun multiplierFor(
        c: StaminaConfig,
        baseSpeed: Double,
        fatigue: Double,
        blend: Double,
    ): Double {
        val run = min(fatigue, 1.0)
        val rest = c.walkSpeed / baseSpeed
        val b = blend.coerceIn(0.0, 1.0)
        return (run + (rest - run) * b).coerceIn(0.02, 1.0)
    }

    /**
     * 疲劳倒计时的**速率**（用户口径：越远越快，方向在开跑阈值处翻转）。
     *
     * ```
     * d = (体力 − 开跑阈值) / (开跑阈值 − 疲劳体力值)    ← 刚进疲劳 d = −1，到阈值 d = 0
     * 速率 = 1 + d ，夹在 [FLOOR, CAP]
     * ```
     *
     *  · 体力低于开跑阈值 ⇒ 速率 < 1（"反向"：倒计时几乎停滞，越深越慢）；
     *  · 达到/超过 ⇒ 速率 > 1（"正向"：烧得更快，越远越快）。
     *
     * **为什么要有 FLOOR**：地板保证倒计时**一定会走完**，于是"疲劳永不结束"这一整类
     * 参数悬崖在结构上消失了（旧口径下冷却系数越过边界就会永不结束，实测 3 小时窗口里
     * 一次疲劳持续 10072 秒）。代价是"反向"表现为 0.25× 而不是负速率。
     */
    fun fatigueTickRate(c: StaminaConfig, staminaPercent: Double): Double {
        val span = (c.resumeAtPercent - c.restAtPercent).coerceAtLeast(1.0)
        val d = (staminaPercent - c.resumeAtPercent) / span
        return (1.0 + d).coerceIn(FATIGUE_RATE_FLOOR, FATIGUE_RATE_CAP)
    }

    /** 倒计时速率下限：保证疲劳一定会结束（见 [fatigueTickRate]） */
    const val FATIGUE_RATE_FLOOR = 0.25

    /** 倒计时速率上限：防止体力远高于阈值时把预算瞬间烧完 */
    const val FATIGUE_RATE_CAP = 3.0
}

/**
 * **按距离预览速度倍率**（体力页那张图的数据源）。
 *
 * ## 它算的是什么
 *
 * 与 [StaminaModel.tick] **同一组公式、同一运算顺序**，只是把驱动量从"真实时钟"换成
 * "固定步长 Δt 推着走"，于是可以离线把未来几十公里一次算完：
 *
 * ```
 * 每一步（Δt 固定）：
 *   表象位移 = 基础速度 × 当前倍率 × Δt      ← 倍率也在降速，所以位移要跟着缩
 *   恢复/消耗/夹取/休息判定/倍率 —— 与 StaminaModel 完全一致
 * ```
 *
 * ## 为什么横轴是距离而不是时间
 *
 * 用户要看的是"跑多远会掉到什么速度"。而倍率又会反过来改变单位时间走过的距离，
 * 所以两个轴互相耦合 —— 只能**积分**，不能拿"时间 × 速度"硬乘。
 * 这里按固定 Δt 积分，再用累计位移当横轴，耦合关系自然成立。
 *
 * ## 三条线
 *
 * 调用方按 [decayScale] 各算一遍即得"无随机 / 最大正随机 / 最大负随机"三条曲线：
 * 运行时的随机是"每段跑动抽一次衰减速率"（见 `StaminaModel.tick` 里的 `jitter`），
 * 所以极值线就是把衰减系数整体乘 `(1 ± randomPercent/100)`——那正是单段随机能达到的上下界。
 *
 * ## 终止条件
 *
 * 距离到位、或**模拟时长**超过 [MAX_SECONDS] 就停。后者是必要的保护：
 * 倍率有 0.02 的地板，参数极端时 10km 可能要几十小时，没有这个上限就会把主线程算穿。
 */
object StaminaCurve {

    /** 预览表的总长（米）：用户口径"窗口 2km、可左右滑动看更远"，这里给 10km（5 屏） */
    const val DEFAULT_MAX_DISTANCE_M = 10_000.0

    /** 预览的可见窗口（米）：用户口径 2km */
    const val DEFAULT_WINDOW_M = 2_000.0

    /**
     * **体检**：疲劳期"能不能回血"的衰减上限（点/分）。
     *
     * 疲劳期以 [StaminaConfig.walkSpeed] 前进，消耗 = `衰减 × 走路/基础`；恢复 =
     * `恢复系数 × 恢复倍率(体力)`。要能回血，两者在**疲劳体力值**处就得交叉：
     * 超过本值 ⇒ 疲劳期体力一路下滑、最终卡在 0%（曲线变成"永远很累"）。
     */
    fun decayLimitForRecovery(c: StaminaConfig, baseSpeed: Double): Double {
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0
        val walkRatio = c.walkSpeed / base
        return if (walkRatio <= 0.0 || c.restAtPercent >= 100.0) Double.MAX_VALUE
        else c.recoverCoefficient * StaminaMath.recoveryFactor(c.restAtPercent) / walkRatio
    }

    /** **体检**：疲劳时速度是否真的比跑动慢（否则"疲劳"等于没降速） */
    fun fatigueSlowsDown(c: StaminaConfig, baseSpeed: Double): Boolean {
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0
        return c.walkSpeed < base * c.minSpeedFactor
    }

    /** 模拟时长上限（秒）：3 小时。超了就直接截断，见类注释 */
    const val MAX_SECONDS = 3.0 * 3600.0

    /** 默认积分步长（秒）：比运行时的一拍（~0.1s）粗，图上肉眼无差，但点数少一个量级 */
    const val DEFAULT_DT_SEC = 0.25

    /**
     * 一条曲线：横轴累计位移（米，递增），纵轴速度倍率。
     *
     * 用 [FloatArray] 而不是对象列表：10km 有上万个采样点，
     * 画图时只按像素抽稀，装箱的列表在这里纯属浪费。
     */
    class Curve(
        val distanceM: FloatArray,
        val multiplier: FloatArray,
        /** 从这条曲线量出来的结果指标（页面直接显示，见 [Metrics]） */
        val metrics: Metrics,
        /** 这一程进入疲劳的次数 */
        val restCount: Int,
        /** 这一程累计处于疲劳的时长（秒） */
        val restTotalSec: Double,
        /** 这一程花掉的模拟时间（秒） */
        val elapsedSec: Double,
        /** 终点的体力（%） */
        val staminaEndPercent: Double,
    ) {
        val size: Int get() = minOf(distanceM.size, multiplier.size)
    }

    /**
     * **结果指标**：从曲线上量出来的"用户真正关心的那几个数"。
     *
     * 为什么要它：参数的相互耦合让"我想 20 分钟累一次、歇 3 分钟"没法靠反推公式得到
     * （要联立衰减/恢复/阈值/时长/速度五项）。于是干脆**把结果算出来显示**：
     * 改一个旋钮 → 直接读这几个数变了多少，不用在脑子里解方程。
     *
     * @param firstFatigueDistanceM 第一次进疲劳的距离（米）；-1 = 全程没进疲劳
     * @param firstFatigueStartSec 第一次进疲劳的时刻（秒）
     * @param firstFatigueSec 第一次疲劳的实际时长（秒）；-1 = 没量到（没结束）
     * @param firstFatigueEntryPercent / [firstFatigueExitPercent] 进/出疲劳时的体力
     * @param firstCycleSec 首次疲劳开始 → 下一次疲劳开始（秒）；-1 = 窗口内没量到
     * @param fatigueSpeedMps 疲劳期间的平均实际速度
     * @param averageSpeedMps 全程平均实际速度
     */
    class Metrics(
        val firstFatigueDistanceM: Double,
        val firstFatigueStartSec: Double,
        val firstFatigueSec: Double,
        val firstFatigueEntryPercent: Double,
        val firstFatigueExitPercent: Double,
        val firstCycleSec: Double,
        val fatigueSpeedMps: Double,
        val averageSpeedMps: Double,
        /** 每一次疲劳**开始**时的累计距离（米），按时间顺序 */
        val fatigueStartDistancesM: FloatArray,
    ) {
        val hasFatigue: Boolean get() = firstFatigueDistanceM >= 0.0

        /**
         * 前 [meters] 米内进入了多少次疲劳。
         *
         * 这是用户直接关心的那个数（"1.5 km 内 1 次、2 km 内 2 次"），
         * 所以由曲线自己数出来显示在页面上，而不是让人去图上比划。
         */
        fun fatigueCountWithin(meters: Double): Int =
            fatigueStartDistancesM.count { it <= meters }
    }

    /** 边跑边量指标（[simulate] 与 [sample] 共用，保证两条路径的口径一致） */
    private class MetricsTracker(private val base: Double) {
        private var firstDistance = -1.0
        private var firstStartSec = -1.0
        private var firstSec = -1.0
        private var entryPercent = 0.0
        private var exitPercent = 0.0
        private var firstCycleSec = -1.0
        private var speedSum = 0.0
        private var fatigueTicks = 0L
        private var wasResting = false
        private val startsM = ArrayList<Float>(16)

        fun tick(distance: Double, elapsed: Double, stamina: Double, resting: Boolean, multiplier: Double) {
            if (resting) {
                if (!wasResting) {                      // 刚进疲劳
                    startsM.add(distance.toFloat())
                    if (firstStartSec < 0.0) {
                        firstDistance = distance
                        firstStartSec = elapsed
                        entryPercent = stamina
                    } else if (firstCycleSec < 0.0) {
                        firstCycleSec = elapsed - firstStartSec
                    }
                }
                speedSum += base * multiplier
                fatigueTicks++
            } else if (wasResting && firstSec < 0.0) {   // 刚出疲劳
                firstSec = elapsed - firstStartSec
                exitPercent = stamina
            }
            wasResting = resting
        }

        fun build(elapsed: Double, distance: Double) = Metrics(
            firstFatigueDistanceM = firstDistance,
            firstFatigueStartSec = if (firstStartSec < 0.0) -1.0 else firstStartSec,
            firstFatigueSec = firstSec,
            firstFatigueEntryPercent = entryPercent,
            firstFatigueExitPercent = exitPercent,
            firstCycleSec = firstCycleSec,
            fatigueSpeedMps = if (fatigueTicks > 0) speedSum / fatigueTicks else 0.0,
            averageSpeedMps = if (elapsed > 0.0) distance / elapsed else 0.0,
            fatigueStartDistancesM = startsM.toFloatArray(),
        )
    }

    /** 生成页用的积分步长：**取 App 的报点间隔**（`reportDuration` 默认 100ms），保证同一口径 */
    const val SAMPLE_DT_SEC = 0.1

    /**
     * **用真实引擎跑一遍**（生成页的数据源）。
     *
     * 与 [simulate] 的关键区别：这里**不重写任何公式**，而是直接驱动 [StaminaModel] ——
     * 也就是 App 运动循环每拍调用的那个对象，调用顺序、参数、随机抽取全都一样：
     *
     * ```
     * 每拍：本拍位移 = 基础速度 × 当前倍率 × Δt   ← 与 MockServiceViewModel 的推进一致
     *       model.tick(配置, Δt, 基础速度, 位移, 随机源)
     * ```
     *
     * 所以"与实际运行逻辑一致"是**构造上成立**的，不是靠两处公式对齐去维护。
     * 代价是每次要真的跑几万拍（10km ≈ 4 万拍 ≈ 几毫秒），而且结果**带随机**
     * —— 同一组参数每次生成都不一样，这正是"生成"和"理论"的区别。
     *
     * @param random 随机源。测试里传固定种子即可复现；App 里用默认源，于是每次点"生成"都是新的一条
     */
    fun sample(
        config: StaminaConfig,
        baseSpeed: Double,
        maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE_M,
        dtSec: Double = SAMPLE_DT_SEC,
        random: Random = Random.Default,
    ): Curve {
        val c = config.sanitized()
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0
        val step = dtSec.coerceIn(0.02, 1.0)
        val limit = if (maxDistanceMeters > 0.0) maxDistanceMeters else DEFAULT_MAX_DISTANCE_M

        val model = StaminaModel()
        var multiplier = model.snapshot().speedScale
        var distance = 0.0
        var elapsed = 0.0
        val distanceList = ArrayList<Float>(4096)
        val multiplierList = ArrayList<Float>(4096)
        val tracker = MetricsTracker(base)
        distanceList.add(0f)
        multiplierList.add(multiplier.toFloat())

        while (distance < limit && elapsed < MAX_SECONDS) {
            val moved = base * multiplier * step
            multiplier = model.tick(c, step, base, moved, random)
            distance += moved
            elapsed += step
            val snap = model.snapshot()
            tracker.tick(distance, elapsed, snap.staminaPercent, snap.resting, multiplier)
            distanceList.add(distance.toFloat())
            multiplierList.add(multiplier.toFloat())
        }

        val s = model.snapshot()
        return Curve(
            distanceM = distanceList.toFloatArray(),
            multiplier = multiplierList.toFloatArray(),
            metrics = tracker.build(elapsed, distance),
            restCount = s.restCount,
            restTotalSec = s.restTotalSec,
            elapsedSec = elapsed,
            staminaEndPercent = s.staminaPercent,
        )
    }

    /**
     * 从满体力开始，跑到 [maxDistanceMeters] 为止。
     *
     * @param decayScale 衰减系数的整体倍数：1.0 = 无随机，1±p = 随机极值
     * @param transitionScale 过渡时长的整体倍数：极值线要和衰减**同向取极**才有意义 ——
     *   速度下界 = 衰减取大 + 过渡取快 + 疲劳取长，上界反之（三个随机源互相独立）
     * @param fatigueScale 疲劳时长的整体倍数（同上）
     * @param dtSec 积分步长（秒）。测试里会传 0.1 与 [StaminaModel] 对齐逐拍比对
     */
    fun simulate(
        config: StaminaConfig,
        baseSpeed: Double,
        decayScale: Double = 1.0,
        maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE_M,
        dtSec: Double = DEFAULT_DT_SEC,
        transitionScale: Double = 1.0,
        fatigueScale: Double = 1.0,
    ): Curve {
        val c = config.sanitized()
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0
        val step = dtSec.coerceIn(0.02, 1.0)
        val limit = if (maxDistanceMeters > 0.0) maxDistanceMeters else DEFAULT_MAX_DISTANCE_M
        val scale = if (decayScale.isFinite() && decayScale > 0.0) decayScale else 1.0
        val decayPerMinute = c.decayPerMinute * scale

        var stamina = 100.0
        var resting = false
        var restCount = 0
        var totalRestSec = 0.0
        var fatigueRemaining = 0.0
        val fatigueBudget = (c.fatigueSec *
                (if (fatigueScale.isFinite() && fatigueScale > 0.0) fatigueScale else 1.0))
            .coerceAtLeast(0.0)
        var blend = 0.0
        var blendTarget = 0.0
        val transitionSec = (c.transitionSec *
                (if (transitionScale.isFinite() && transitionScale > 0.0) transitionScale else 1.0))
            .coerceAtLeast(0.0)
        var elapsed = 0.0
        var distance = 0.0
        var multiplier = StaminaMath.multiplierFor(
            c, base, StaminaMath.fatigueFactor(c, stamina), blend
        )

        val distanceList = ArrayList<Float>(4096)
        val multiplierList = ArrayList<Float>(4096)
        val tracker = MetricsTracker(base)
        distanceList.add(0f)
        multiplierList.add(multiplier.toFloat())

        while (distance < limit && elapsed < MAX_SECONDS) {
            // 本拍位移用"当前倍率"推 —— 与 App 运动循环一致（先按倍率推进、再让体力结算）
            val moved = base * multiplier * step

            // ↓↓↓ 以下与 StaminaModel.tick 的结算顺序逐条对应，改一处必须改另一处 ↓↓↓
            stamina += c.recoverCoefficient / 60.0 * StaminaMath.recoveryFactor(stamina) * step
            stamina -= decayPerMinute / 60.0 * multiplier * step
            stamina = stamina.coerceIn(0.0, 100.0)
            if (!resting && stamina <= c.restAtPercent) {
                resting = true
                restCount += 1
                fatigueRemaining = fatigueBudget          // 进疲劳：按下预算起倒计时
            } else if (resting) {
                // 出看**倒计时预算**，不看体力阈值本身（两侧都用阈值 ⇒ 无迟滞 ⇒ 继电器振荡）
                fatigueRemaining -= StaminaMath.fatigueTickRate(c, stamina) * step
                if (fatigueRemaining <= 0.0) {
                    resting = false
                    fatigueRemaining = 0.0
                }
            }
            // 过渡：与 StaminaModel 同一套（目标档变化即"本次过渡"，时长 = 名义时长 × 本次倍数）
            val target = if (resting) 1.0 else 0.0
            if (target != blendTarget) blendTarget = target
            if (blend != blendTarget) {
                val rate = if (transitionSec <= 0.0) Double.MAX_VALUE else step / transitionSec
                blend = if (blendTarget > blend) (blend + rate).coerceAtMost(1.0)
                else (blend - rate).coerceAtLeast(0.0)
            }
            multiplier = StaminaMath.multiplierFor(
                c, base, StaminaMath.fatigueFactor(c, stamina), blend
            )
            // ↑↑↑ 结算结束 ↑↑↑
            if (resting) totalRestSec += step

            distance += moved
            elapsed += step
            tracker.tick(distance, elapsed, stamina, resting, multiplier)
            distanceList.add(distance.toFloat())
            multiplierList.add(multiplier.toFloat())
        }

        return Curve(
            distanceM = distanceList.toFloatArray(),
            multiplier = multiplierList.toFloatArray(),
            metrics = tracker.build(elapsed, distance),
            restCount = restCount,
            restTotalSec = totalRestSec,
            elapsedSec = elapsed,
            staminaEndPercent = stamina,
        )
    }
}
