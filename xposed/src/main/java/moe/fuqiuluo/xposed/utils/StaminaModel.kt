package moe.fuqiuluo.xposed.utils

import kotlin.random.Random

/**
 * 体力参数（App 侧可配、系统侧用来复现同一模型）。
 *
 * ## 为什么这些值是"推导"出来的而不是随手填的
 *
 * 基础速度默认 3.05 m/s（≈5:28/km，慢跑）。模型要满足三条可观测约束：
 *
 *  1. **不停歇太频繁**：满体力跑到休息阈值的时间要够长。100→20（80 点）在
 *     [decayPerMinute] 下约 7.3 分钟 ⇒ 约每 7 分钟歇一次；
 *  2. **配速损失可接受**：休息时间短、休息速度是"快走"量级 ⇒ 全程均速仍在
 *     2.5 m/s 上下（≈6:30/km），比恒速慢约 17%，符合"跑者会累"的直觉；
 *  3. **休息能回得上来**：[recoverPerSecond] × [restSeconds] 要明显高于
 *     `阈值→满` 的缺口，否则每次休息完刚跑几步又得歇（实测 2.0 × 35 = 70 点，够）。
 *
 * 默认这一组在 30 分钟实算：4.60 km、均速 2.55 m/s、配速 6:31/km、休息 3 次共 1.8 分钟（6%）。
 *
 * ## 真机实测（2026-09-16，OnePlus ACE5 / ColorOS 16）
 *
 * 在 Location Mock 页开启模拟并按住摇杆 25 秒（真实位移）后，体力页读数：
 * `当前体力 78.6 %`、`跑动中`、`基础 3.05 m/s ⇒ 当前 2.85 m/s`、`休息 0 次`。
 * ⇒ **"体力随跑动衰减 + 降速"这条闭环已在真机上成立**（系数 0.934 × 3.05 = 2.85，与公式一致）。
 * 休息/恢复那一段目前只有单测覆盖（`StaminaModelTest`），尚未在真机上观察到休息窗口。
 *
 * ## 字段一览（界面上一条对应一行）
 *
 * | 字段 | 默认 | 含义 |
 * | --- | --- | --- |
 * | [decayPerMinute] | 11 | 体力衰减（点/分钟，**满速跑动时**；低速时按比例变慢） |
 * | [restAtPercent] | 20 | 休息体力值：体力降到它以下就进休息 |
 * | [restSeconds] | 35 | 休息时间（秒） |
 * | [recoverPerSecond] | 2.0 | 恢复速度（点/秒，休息期间） |
 * | [walkSpeed] | 1.10 | 休息时速度（m/s，走路低值） |
 * | [minSpeedFactor] | 0.75 | 疲劳降速下限：体力见底前最低降到基础速度的该比例 |
 * | [randomPercent] | 15 | 随机化幅度（%）：施加在衰减速率与每次休息时长上 |
 */
data class StaminaConfig(
    var enabled: Boolean = false,
    var decayPerMinute: Double = 11.0,
    var restAtPercent: Double = 20.0,
    var restSeconds: Double = 35.0,
    var recoverPerSecond: Double = 2.0,
    var walkSpeed: Double = 1.10,
    var minSpeedFactor: Double = 0.75,
    var randomPercent: Double = 15.0,
) {
    /** 夹取到有意义的范围：防界面输入 0/负数/离谱值把模拟弄成静止或瞬移 */
    fun sanitized(): StaminaConfig = copy(
        decayPerMinute = decayPerMinute.coerceIn(0.1, 600.0),
        restAtPercent = restAtPercent.coerceIn(0.0, 99.0),
        restSeconds = restSeconds.coerceIn(1.0, 3600.0),
        recoverPerSecond = recoverPerSecond.coerceIn(0.01, 100.0),
        walkSpeed = walkSpeed.coerceIn(0.1, 10.0),
        minSpeedFactor = minSpeedFactor.coerceIn(0.05, 1.0),
        randomPercent = randomPercent.coerceIn(0.0, 60.0),
    )
}

/**
 * **体力模拟**：把"人会长跑累"这件事做成一个可复现的状态机。
 *
 * ## 模型
 *
 * ```
 * 体力 0..100
 *   跑动中:  体力 -= 衰减/分 × (当前速度/基础速度) × Δt   ← 慢跑消耗慢，符合常识
 *            速度系数 = minFactor + (1-minFactor) × (体力-阈值)/(100-阈值)
 *   到阈值:  进入休息，时长 = 休息秒数 × 随机(1±随机幅度)
 *   休息中:  体力 += 恢复/秒 × Δt（封顶 100）；速度 = 走路低值
 *   休息结束: 回到跑动
 * ```
 *
 * ## 为什么"速度系数"是连续衰减而不是阶梯
 *
 * 阶梯式（比如体力<50 就砍半）会在配速曲线上留下**可被检测的台阶**：
 * 相同速度维持很久后突然跳变。连续系数让速度随体力平滑下滑，
 * 与真实跑者的配速漂移同构。
 *
 * ## 为什么随机化放在"衰减速率"和"每次休息时长"上
 *
 * 这两处是人最容易出现个体差异的地方（有人掉体力快、有人歇得久）。
 * 若把随机化放到**每一拍的速度**上，会与已有的 `speedAmplitude` 抖动叠加成过度噪声；
 * 施加在"节奏"上则表现为"每次休息不一样长"，更像人而不是节拍器。
 *
 * ## 线程纪律
 *
 * [tick] 由 App 的运动循环单线程驱动；[snapshot] 供 UI 读取。
 * 两者都走 `synchronized`，状态字段不单独暴露 —— 避免"读到一半"的体力值。
 */
class StaminaModel {

    private val lock = Any()
    private var staminaPercent: Double = 100.0
    private var resting: Boolean = false
    private var restRemainingSec: Double = 0.0
    private var currentScale: Double = 1.0
    private var restCount: Int = 0
    private var restTotalSec: Double = 0.0
    /**
     * 本次"跑动段"的衰减速率（进入跑动段时按随机化抽一次，整段不变）。
     * `NaN` = 还没抽过 —— 首段必须在**第一次 tick** 时抽，
     * 否则会漏掉随机化、表现为"每次开局都恰好一样"（单测逮到过）。
     */
    private var decayThisRun: Double = Double.NaN

    /** 供 UI/日志读取的一致快照 */
    data class Snapshot(
        val staminaPercent: Double,
        val resting: Boolean,
        val restRemainingSec: Double,
        val speedScale: Double,
        val restCount: Int,
        val restTotalSec: Double,
    )

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(staminaPercent, resting, restRemainingSec, currentScale, restCount, restTotalSec)
    }

    /** 复位到满体力、非休息（会话开始/用户点"重置"时调用） */
    fun reset() = synchronized(lock) {
        staminaPercent = 100.0
        resting = false
        restRemainingSec = 0.0
        currentScale = 1.0
        restCount = 0
        restTotalSec = 0.0
        decayThisRun = Double.NaN
    }

    /**
     * 推进模型并返回**本拍的速度系数**（乘以基础速度 = 本拍实际速度）。
     *
     * @param config 当前参数（内部会 [StaminaConfig.sanitized]）
     * @param dtSec 本拍时长（秒）。≤0 视为无效，直接返回上一拍的系数
     * @param baseSpeed 基础速度（m/s）—— 休息时的系数 = 走路速度/基础速度，
     *   所以模型**必须知道基础速度**才能给出正确系数（否则只能给个近似常量，
     *   那会与调用点自己换算的结果不一致，等于同一件事两套口径）。
     * @param random 随机源（单测可注入固定种子；生产用 [Random.Default]）
     */
    fun tick(
        config: StaminaConfig,
        dtSec: Double,
        baseSpeed: Double,
        random: Random = Random.Default,
    ): Double {
        if (dtSec <= 0.0) return synchronized(lock) { currentScale }
        val c = config.sanitized()
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0

        synchronized(lock) {
            if (resting) {
                restRemainingSec -= dtSec
                restTotalSec += dtSec
                staminaPercent = (staminaPercent + c.recoverPerSecond * dtSec).coerceAtMost(100.0)
                currentScale = walkFactor(c, base)
                if (restRemainingSec <= 0.0) {
                    resting = false
                    // 新的一段跑动：在这里抽一次本段衰减速率（随机化落在"节奏"上）
                    decayThisRun = jitter(c.decayPerMinute, c.randomPercent, random)
                }
            } else {
                if (decayThisRun.isNaN()) {
                    // 首段：在这里抽（reset() 后第一次 tick）
                    decayThisRun = jitter(c.decayPerMinute, c.randomPercent, random)
                }
                val f = runFactor(c)
                currentScale = f
                staminaPercent -= decayThisRun * f * (dtSec / 60.0)
                if (staminaPercent <= c.restAtPercent) {
                    staminaPercent = c.restAtPercent
                    resting = true
                    restCount += 1
                    restRemainingSec = jitter(c.restSeconds, c.randomPercent, random)
                    currentScale = walkFactor(c, base)
                }
            }
            return currentScale
        }
    }

    /** 跑动中的速度系数：体力从 100 降到阈值时，系数从 1.0 平滑降到 [StaminaConfig.minSpeedFactor] */
    private fun runFactor(c: StaminaConfig): Double {
        val span = (100.0 - c.restAtPercent).coerceAtLeast(1e-6)
        val t = ((staminaPercent - c.restAtPercent) / span).coerceIn(0.0, 1.0)
        return c.minSpeedFactor + (1.0 - c.minSpeedFactor) * t
    }

    /** 休息时的速度系数 = 走路低值 / 基础速度（绝对量换算成系数，与调用点同一口径） */
    private fun walkFactor(c: StaminaConfig, baseSpeed: Double): Double =
        (c.walkSpeed / baseSpeed).coerceIn(0.02, 1.0)

    private fun jitter(value: Double, percent: Double, random: Random): Double {
        if (percent <= 0.0) return value
        val p = percent / 100.0
        return value * (1.0 + random.nextDouble(-p, p))
    }

    companion object {
        /** 关掉体力模拟时统一用这个系数（= 不做任何调制，行为逐位回到旧实现） */
        const val NO_MODULATION = 1.0
    }
}
