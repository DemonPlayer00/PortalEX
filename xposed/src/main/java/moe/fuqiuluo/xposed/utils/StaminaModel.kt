package moe.fuqiuluo.xposed.utils

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * 体力参数（App 侧可配、系统侧用来复现同一模型）。
 *
 * ## 模型一眼看全
 *
 * ```
 * 每一拍（App 运动循环的 tick）：
 *   表象速度 = 本拍位移 / 本拍时长              ← 绑定"表象移动"，不是"会话开着"
 *   若 表象速度 > 忽略阈值（或窗口内平均 > 阈值）⇒ 本拍只走恢复、不计消耗
 *   恢复: 体力 += 恢复速度系数 × 恢复倍率(体力) ÷ 冷却系数 × Δt   ← 一直在发生
 *   消耗: 体力 -= 衰减系数 × (表象速度/基础速度) × Δt/60
 *   低于休息体力值 ⇒ 速度倍率再 × 休息系数（回到阈值以上自动解除）
 * ```
 *
 * ## 三条由用户定的关键口径
 *
 * 1. **恢复是连续的背景过程**，不是"只有休息段才回血"：
 *    `恢复速度 = 恢复速度系数 × 恢复倍率(体力) ÷ 冷却时间系数`，其中
 *    `恢复倍率(体力) = 2.0 - 体力% / 100` ⇒ 满体力 1.0、半血 1.5、**空血 2.0（最快）**。
 * 2. **休息不是状态机，而是倍率惩罚**：低于 [StaminaConfig.restAtPercent] 后，
 *    速度倍率 = 疲劳倍率 × [StaminaConfig.restSpeedFactor]，直到体力回到阈值以上。
 *    [StaminaConfig.restSecondsCoefficient] 不再是"休息多少秒"，而是**冷却时间系数**
 *    （乘在恢复上：越大冷却越久）。
 * 3. **忽略"过快"的表象移动**：单拍速度或窗口内平均速度超过
 *    [StaminaConfig.moveIgnoreSpeed] 时，本拍**只走恢复、不计消耗** —— 这是**异常帧防护**
 *    （瞬移/位置跳变/补帧会算出几百 m/s，不该一瞬把体力抽干）。
 *    ⚠️ 忽略的是**快**，不是慢；慢速照样消耗（只是按比例变小）。
 *
 * ## 默认值怎么推出来的
 *
 * 基础速度默认 3.05 m/s（≈5:28/km）。要让"恢复−消耗"的净效果像个人：
 * - 满速跑动消耗 = [StaminaConfig.decayPerMinute] × 1.0 = 11 点/分；
 * - 满体力恢复 = [StaminaConfig.recoverCoefficient] × 1.0 = 4 点/分 ⇒ 净掉 7 点/分，
 *   100→20 约 11.4 分钟歇一次；
 * - 体力 20% 时恢复 = 4 × 1.8 = 7.2 点/分，仍低于满速消耗 ⇒ 一直跑一定会累（不会自愈）；
 * - 降到走路（倍率 0.36 ⇒ 1.1 m/s）时消耗 = 11 × 0.36 ≈ 4 点/分 < 恢复 ⇒ **走着就在回血**。
 */
data class StaminaConfig(
    var enabled: Boolean = false,
    /** 消耗系数：满速跑动时每分钟消耗多少点 */
    var decayPerMinute: Double = 11.0,
    /** 恢复速度系数：实际恢复 = 本值 × 恢复倍率(体力) ÷ 冷却时间系数 */
    var recoverCoefficient: Double = 4.0,
    /** 休息体力值：低于它就对速度倍率再乘 [restSpeedFactor] */
    var restAtPercent: Double = 20.0,
    /** 休息降速系数：休息期间在疲劳倍率上再乘它 */
    var restSpeedFactor: Double = 0.25,
    /** 冷却时间系数：乘在恢复上（越大则回到阈值以上越慢） */
    var restSecondsCoefficient: Double = 1.0,
    /** 休息时速度（m/s，走路低值，同时是速度地板） */
    var walkSpeed: Double = 1.10,
    /** 疲劳降速下限：体力见底前最低降到基础速度的该比例 */
    var minSpeedFactor: Double = 0.75,
    /** 随机化幅度（%）：施加在衰减速率上 */
    var randomPercent: Double = 15.0,
    /** 忽略窗口（秒）：窗口内平均表象速度超过阈值也判为异常帧 */
    var moveIgnoreWindowSec: Double = 3.0,
    /** 忽略阈值（m/s）：快于它的表象移动不计消耗 */
    var moveIgnoreSpeed: Double = 12.0,
) {
    /** 夹取到有意义的范围：防界面输入 0/负数/离谱值把模拟弄成静止或瞬移 */
    fun sanitized(): StaminaConfig = copy(
        decayPerMinute = decayPerMinute.coerceIn(0.1, 600.0),
        recoverCoefficient = recoverCoefficient.coerceIn(0.01, 600.0),
        restAtPercent = restAtPercent.coerceIn(0.0, 99.0),
        restSpeedFactor = restSpeedFactor.coerceIn(0.01, 1.0),
        restSecondsCoefficient = restSecondsCoefficient.coerceIn(0.05, 10.0),
        walkSpeed = walkSpeed.coerceIn(0.1, 10.0),
        minSpeedFactor = minSpeedFactor.coerceIn(0.05, 1.0),
        randomPercent = randomPercent.coerceIn(0.0, 60.0),
        moveIgnoreWindowSec = moveIgnoreWindowSec.coerceIn(0.0, 60.0),
        moveIgnoreSpeed = moveIgnoreSpeed.coerceIn(0.5, 200.0),
    )
}

/**
 * **体力模型**：把"人跑久了会累、歇着会回"做成可复现、可单测的过程。
 *
 * 输入是**表象移动**（每拍位移、时长）与基础速度，输出是**速度倍率**。
 * 模型不做时间管理（拍长由调用方给），因此可被单测穷举而不依赖真实时钟。
 *
 * ## 三个设计选择及其理由
 *
 * - **恢复倍率随体力反向变化**（空血最快）：恒定恢复会让"快空时几乎回不动"，
 *   模拟会话长时间卡在极低体力；反向加权后低体力回得快，恢复段更短、节奏更像人。
 * - **休息是倍率惩罚而非状态**：省掉一套"进/出休息"的计时状态机，
 *   而且"休息多久"由"恢复多久能越过阈值"自然决定（受冷却系数调节）。
 * - **忽略过快的拍**：位置跳变会算出荒谬速度，照单全收就会"什么都没干、体力被抽干"。
 */
class StaminaModel {

    private val lock = Any()
    private var staminaPercent: Double = 100.0
    private var resting: Boolean = false
    private var currentMultiplier: Double = 1.0
    private var restCount: Int = 0
    private var restTotalSec: Double = 0.0
    private var decayThisRun: Double = Double.NaN
    private var coolingSec: Double = 0.0

    /** 忽略窗口里的速度采样（拍长秒, 表象速度 m/s） */
    private val window = ArrayDeque<Pair<Double, Double>>()
    /** 因"过快"被忽略的拍数（诊断：能一眼看出有没有异常帧） */
    private var ignoredTicks: Long = 0
    private var lastApparent = 0.0

    data class Snapshot(
        val staminaPercent: Double,
        val resting: Boolean,
        val restRemainingSec: Double,
        val speedScale: Double,
        val restCount: Int,
        val restTotalSec: Double,
        /** 最近一拍判定的表象速度（m/s） */
        val apparentSpeed: Double,
        /** 因"过快"被忽略的拍数 */
        val ignoredTicks: Long,
    )

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            staminaPercent, resting, coolingSec, currentMultiplier,
            restCount, restTotalSec, lastApparent, ignoredTicks,
        )
    }

    /** 复位到满体力、清空窗口与统计（会话开始 / 用户点"重置"） */
    fun reset() = synchronized(lock) {
        staminaPercent = 100.0
        resting = false
        currentMultiplier = 1.0
        restCount = 0
        restTotalSec = 0.0
        decayThisRun = Double.NaN
        coolingSec = 0.0
        window.clear()
        ignoredTicks = 0
        lastApparent = 0.0
    }

    /**
     * 推进一拍。
     *
     * @param dtSec 本拍时长（秒）。≤0 视为无效，直接返回上一拍倍率
     * @param baseSpeed 基础速度（m/s）：绝对量（走路速度）换算成倍率的口径
     * @param movedMeters 本拍**表象位移**（米）。0 = 这一拍位置没动
     */
    fun tick(
        config: StaminaConfig,
        dtSec: Double,
        baseSpeed: Double,
        movedMeters: Double,
        random: Random = Random.Default,
    ): Double {
        val c = config.sanitized()
        if (dtSec <= 0.0) return synchronized(lock) { currentMultiplier }
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0
        val moved = if (movedMeters.isNaN() || movedMeters < 0.0) 0.0 else movedMeters
        val apparent = moved / dtSec

        synchronized(lock) {
            // 1) 忽略窗口：单拍或窗口平均"过快" ⇒ 本拍只走恢复
            lastApparent = apparent
            rememberSample(c, dtSec, apparent)
            val avg = windowAverage()
            val ignoring = apparent > c.moveIgnoreSpeed || avg > c.moveIgnoreSpeed
            if (ignoring) ignoredTicks += 1

            // 2) 连续恢复（一直在发生，与消耗并存）
            // ⚠️ 系数单位是「点/分钟」，必须 /60 换成每秒再乘 Δt。漏这一步恢复会快 60 倍，
            // 表现是「体力永远满」（实测踩过：恢复 2.0/拍 压过消耗 0.09/拍，8 条单测同时红）。
            val recoverPerSecond =
                c.recoverCoefficient / 60.0 * recoveryFactor(staminaPercent) / c.restSecondsCoefficient
            staminaPercent += recoverPerSecond * dtSec

            // 3) 消耗：只在"本拍确有位移"且未被忽略时
            val fatigue = fatigueFactor(c)
            if (!ignoring && moved > 0.0) {
                if (decayThisRun.isNaN()) decayThisRun = jitter(c.decayPerMinute, c.randomPercent, random)
                // 消耗系数同样是「点/分钟」+ 与表象速度成正比
                staminaPercent -= decayThisRun / 60.0 * (apparent / base) * dtSec
            }
            staminaPercent = staminaPercent.coerceIn(0.0, 100.0)

            // 4) 休息：低于阈值施加额外降速，回到阈值以上自动解除
            if (!resting && staminaPercent <= c.restAtPercent) {
                resting = true
                restCount += 1
                decayThisRun = Double.NaN    // 下一段跑动重新抽消耗速率
            } else if (resting && staminaPercent > c.restAtPercent) {
                resting = false
            }
            if (resting) {
                restTotalSec += dtSec
                coolingSec += dtSec
            } else {
                coolingSec = 0.0
            }

            currentMultiplier = multiplierFor(c, base, fatigue)
            return currentMultiplier
        }
    }

    /**
     * 恢复倍率：体力 100% → 1.0，50% → 1.5，**0% → 2.0（最快）**。
     * 线性反向加权，乘在 [StaminaConfig.recoverCoefficient] 上。
     */
    private fun recoveryFactor(stamina: Double): Double = 2.0 - stamina.coerceIn(0.0, 100.0) / 100.0

    /** 疲劳速度倍率：体力从 100 降到阈值时，从 1.0 平滑降到 [StaminaConfig.minSpeedFactor] */
    private fun fatigueFactor(c: StaminaConfig): Double {
        val span = (100.0 - c.restAtPercent).coerceAtLeast(1e-6)
        val t = ((staminaPercent - c.restAtPercent) / span).coerceIn(0.0, 1.0)
        return c.minSpeedFactor + (1.0 - c.minSpeedFactor) * t
    }

    /**
     * 最终倍率 = 疲劳倍率（休息时再 × [StaminaConfig.restSpeedFactor]）。
     *
     * 休息时**不直接跳到走路速度**：它是"当前疲劳倍率再乘系数"（用户口径）。
     * 但与走路速度取小 —— 走路低值是速度地板，不是目标值；不取小的话
     * "低体力 + 小休息系数"会算出比走路还慢得离谱的速度。
     */
    private fun multiplierFor(c: StaminaConfig, baseSpeed: Double, fatigue: Double): Double {
        val walk = c.walkSpeed / baseSpeed
        val target = if (resting) fatigue * c.restSpeedFactor else fatigue
        val capped = if (resting) min(target, walk) else min(target, 1.0)
        return capped.coerceIn(0.02, 1.0)
    }

    /** 维护忽略窗口：保留最近 [StaminaConfig.moveIgnoreWindowSec] 秒内的采样 */
    private fun rememberSample(c: StaminaConfig, dtSec: Double, speed: Double) {
        if (c.moveIgnoreWindowSec <= 0.0) {
            window.clear()
            window.addLast(dtSec to speed)
            return
        }
        window.addLast(dtSec to speed)
        while (window.size > 1 && window.sumOf { it.first } - window.first().first >= c.moveIgnoreWindowSec) {
            window.removeFirst()
        }
    }

    /** 窗口内的时间加权平均速度（按拍长加权：短拍不会因为条数多而主导） */
    private fun windowAverage(): Double {
        if (window.isEmpty()) return 0.0
        var t = 0.0
        var s = 0.0
        window.forEach { (dt, v) ->
            t += dt
            s += dt * v
        }
        return if (t <= 0.0) 0.0 else s / t
    }

    private fun jitter(value: Double, percent: Double, random: Random): Double {
        if (percent <= 0.0) return value
        val p = percent / 100.0
        return value * (1.0 + random.nextDouble(-p, p))
    }

    companion object {
        /** 关掉体力模拟时统一用这个倍率（= 不做任何调制，行为逐位回到旧实现） */
        const val NO_MODULATION = 1.0

        /** 诊断：倍率与 1.0 的差异是否值得记一笔 */
        fun isModulating(multiplier: Double): Boolean = abs(multiplier - NO_MODULATION) > 1e-3
    }
}
