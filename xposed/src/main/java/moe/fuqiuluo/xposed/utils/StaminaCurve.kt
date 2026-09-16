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
     * 跑动档 = min(疲劳倍率, 1.0)
     * 疲劳档 = max(疲劳倍率 × 休息降速系数, 走路/基础)      ← 走路值是下限（用户口径）
     * 最终   = 跑动档 + (疲劳档 − 跑动档) × blend          ← blend ∈ [0,1]
     * ```
     *
     * [blend] 是**状态**（谁在推进谁维护：模型里是逐拍收敛，曲线里是同一步长积分），
     * 不是一次性插值 —— 否则"过渡途中又反向"会算出不属于任何一档的速度。
     * `blend = 0/1` 时结果与"没有过渡"逐位相同，所以关掉过渡（过渡时间 = 0）时行为不变。
     */
    fun multiplierFor(
        c: StaminaConfig,
        baseSpeed: Double,
        fatigue: Double,
        blend: Double,
    ): Double {
        val walk = c.walkSpeed / baseSpeed
        val run = min(fatigue, 1.0)
        val rest = max(fatigue * c.restSpeedFactor, walk)
        val b = blend.coerceIn(0.0, 1.0)
        return (run + (rest - run) * b).coerceIn(0.02, 1.0)
    }
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
        distanceList.add(0f)
        multiplierList.add(multiplier.toFloat())

        while (distance < limit && elapsed < MAX_SECONDS) {
            val moved = base * multiplier * step
            multiplier = model.tick(c, step, base, moved, random)
            distance += moved
            elapsed += step
            distanceList.add(distance.toFloat())
            multiplierList.add(multiplier.toFloat())
        }

        val s = model.snapshot()
        return Curve(
            distanceM = distanceList.toFloatArray(),
            multiplier = multiplierList.toFloatArray(),
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
     *   速度下界 = 衰减取大 + 过渡取快（1+p / 1−p），上界反之
     * @param dtSec 积分步长（秒）。测试里会传 0.1 与 [StaminaModel] 对齐逐拍比对
     */
    fun simulate(
        config: StaminaConfig,
        baseSpeed: Double,
        decayScale: Double = 1.0,
        maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE_M,
        dtSec: Double = DEFAULT_DT_SEC,
        transitionScale: Double = 1.0,
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
        var cooldown = 0.0
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
        distanceList.add(0f)
        multiplierList.add(multiplier.toFloat())

        while (distance < limit && elapsed < MAX_SECONDS) {
            // 本拍位移用"当前倍率"推 —— 与 App 运动循环一致（先按倍率推进、再让体力结算）
            val moved = base * multiplier * step

            // ↓↓↓ 以下与 StaminaModel.tick 的结算顺序逐条对应，改一处必须改另一处 ↓↓↓
            stamina += c.recoverCoefficient / 60.0 *
                    StaminaMath.recoveryFactor(stamina) / c.restSecondsCoefficient * step
            stamina -= decayPerMinute / 60.0 * multiplier * step
            stamina = stamina.coerceIn(0.0, 100.0)
            if (!resting && stamina <= c.restAtPercent) {
                resting = true
                restCount += 1
                cooldown = 0.0
            } else if (resting) {
                // 疲劳状态：出看"冷却进度回到 ≥ 0"，**不看体力阈值本身**
                // （两侧都用体力阈值 = 无迟滞 ⇒ 必然抖成继电器振荡）
                cooldown += (stamina - c.resumeAtPercent) / 100.0 * step
                if (cooldown >= 0.0) resting = false
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
            distanceList.add(distance.toFloat())
            multiplierList.add(multiplier.toFloat())
        }

        return Curve(
            distanceM = distanceList.toFloatArray(),
            multiplier = multiplierList.toFloatArray(),
            restCount = restCount,
            restTotalSec = totalRestSec,
            elapsedSec = elapsed,
            staminaEndPercent = stamina,
        )
    }
}
