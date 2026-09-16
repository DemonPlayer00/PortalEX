package moe.fuqiuluo.xposed.utils

import kotlin.math.abs
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
 *   体力 ≤ 休息体力值 ⇒ 进入**疲劳状态**：速度倍率再 × 休息降速系数（以走路值为下限）
 *   疲劳状态里：冷却进度 += (体力 − 开跑阈值)/100 × Δt
 *              低于开跑阈值 ⇒ 反向计数（越远越快）；达到/超过 ⇒ 正向计数（越远越快）
 *              进度回到 ≥ 0 ⇒ **开跑**（退出疲劳），**不再看体力阈值本身**
 *   过渡: 倍率 = 跑动档 + (疲劳档 − 跑动档) × 过渡量（过渡量按 1/过渡时长 逐拍收敛）
 *   ⚠️ 恢复**不受"是否有位移"影响**：空闲（完全不动）时只有恢复生效，体力照样回满
 *      —— 只有**消耗**才需要有位移。
 * ```
 *
 * ## 四条由用户定的关键口径
 *
 * 1. **恢复是连续的背景过程**，不是"只有休息段才回血"：
 *    `恢复速度 = 恢复速度系数 × 恢复倍率(体力) ÷ 冷却时间系数`，其中
 *    `恢复倍率(体力) = 2.0 - 体力% / 100` ⇒ 满体力 1.0、半血 1.5、**空血 2.0（最快）**。
 * 2. **疲劳是倍率惩罚 + 一个冷却计数器**：进入条件是体力 ≤ [StaminaConfig.restAtPercent]，
 *    速度倍率 = `max(疲劳倍率 × 休息降速系数, 走路/基础)`（走路值是**下限**）；
 *    **退出条件与体力阈值无关**，只看"冷却进度"是否回到 ≥ 0。
 *    ⚠️ 这条是必须的：进/出若用同一个体力阈值（无迟滞），两个模式的净速率方向相反
 *    ⇒ 必然退化成"进一拍、出一拍"的继电器振荡（实测 45 分钟 795 次、每次都只有一拍）。
 * 3. **开跑阈值必须高于休息体力值**：低于它进度反向计数（欠账变大），
 *    达到/超过它进度正向计数（还账），两边速率都正比于偏离量（"越远越快"）。
 *    两者相等时迟滞消失 —— 这就是 [StaminaConfig.sanitized] 里那条派生夹取的理由。
 * 4. **进出疲劳有过渡**：速度不是一步跳过去，而是按 [StaminaConfig.transitionSec] 平滑
 *    （过渡量是模型状态，不是一次性插值）；每次方向变化重抽时长（× ±随机化幅度），
 *    所以两次过渡不会一样长。过渡时间 = 0 时逐位回到"直接跳变"的旧行为。
 * 5. **忽略"过快"的表象移动**：单拍速度或窗口内平均速度超过
 *    [StaminaConfig.moveIgnoreSpeed] 时，本拍**只走恢复、不计消耗** —— 这是**异常帧防护**
 *    （瞬移/位置跳变/补帧会算出几百 m/s，不该一瞬把体力抽干）。
 *    ⚠️ 忽略的是**快**，不是慢；慢速照样消耗（只是按比例变小）。
 *
 * ## 默认值怎么推出来的（2026-09-17 按"疲劳密度"重定）
 *
 * 基础速度默认 3.05 m/s（≈5:28/km）。用户口径：**1.5 km 内出现 1 次疲劳、2 km 内出现 2 次**
 * —— 也就是默认那 2 km 窗口里就要看得见这个模式，而不用左右滑动。
 * 于是要的不是"多久累一次"的直觉值，而是**每公里约 1 次**的节奏：
 * - 衰减 22 点/分（满速）+ 恢复 6 点/分 ⇒ 首次疲劳在 **1.36 km / 8.7 分**；
 * - 疲劳体力值 15%、开跑阈值 25%（差 10 个点，迟滞带）；疲劳时长 80 秒（预算）；
 * - 一次疲劳实测 175 秒、周期 262 秒 ≈ 1.6 个/km ⇒ 1.5 km 内 1 次、2 km 内 2 次。
 *
 * 这组值由 `StaminaCycleAnalysisTest` 的 `出厂默认必须落在健康区间` 钉住（含随机采样的命中率），
 * 改默认值时那条例会红。
 *
 * ⚠️ 别用"80 点 ÷ 净速率"线性外推首次疲劳：净掉速率随体力下降而变小（恢复变快、消耗变慢），
 * 后半段掉得比前半段慢得多 —— 早期版本就是这么把 3.9 km 估成 11.4 分钟的。
 */
data class StaminaConfig(
    var enabled: Boolean = false,
    /** 消耗系数：满速跑动时每分钟消耗多少点 */
    var decayPerMinute: Double = 22.0,
    /** 恢复速度系数：实际恢复 = 本值 × 恢复倍率(体力) ÷ 冷却时间系数 */
    var recoverCoefficient: Double = 6.0,
    /** 休息体力值：低于它就对速度倍率再乘 [restSpeedFactor] */
    var restAtPercent: Double = 15.0,
    /**
     * **开跑阈值**：疲劳状态里"冷却进度"计数的**方向分界**。
     *
     * 体力低于它 ⇒ 进度反向计数（越远越快）；达到/超过它 ⇒ 正向计数（越远越快）；
     * 进度回到 ≥ 0 就开跑（退出疲劳）。它必须**高于** [restAtPercent]：
     * 两者相等时迟滞消失，退化成"进一拍、出一拍"的抖动。
     */
    var resumeAtPercent: Double = 25.0,
    /** 休息降速系数：休息期间在疲劳倍率上再乘它 */
    var restSpeedFactor: Double = 0.25,
    /**
     * **疲劳时长（秒）**：一次疲劳的倒计时预算（用户口径，2026-09-17 重设计）。
     *
     * 取代了原来的「冷却时间系数」—— 那个是"除在恢复上的除数"，名字与作用不符，
     * 而且越过某条边界后疲劳**永不结束**（悬崖）。现在它是一个直接可读的时间：
     * 进疲劳时按下这个秒数起倒计时，倒完即开跑；每拍的实际速率见
     * [StaminaMath.fatigueTickRate]（低于开跑阈值更慢、高于更快，越远越快）。
     */
    var fatigueSec: Double = 80.0,
    /**
     * **过渡时间（秒）**：进出疲劳时速度从一档平滑到另一档所用的时长。
     *
     * 0 = 立即切换（就是台阶：默认参数下 2.29 m/s 一步掉到 1.10，图上是一根竖线）。
     * 每次**方向变化**都会重新抽一次时长（乘 ±[randomPercent]，见 [StaminaConfig.randomPercent]），
     * 所以两次过渡不会是同一个秒数。
     */
    var transitionSec: Double = 3.0,
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
    /**
     * **上线顺序**（App → 模块的 `PUT_CONFIG`，见 `Key.STAMINA_CONFIG`）。
     *
     * 为什么集中在这里：这是跨进程的**协议面**，两侧各写一份顺序必然会错位，
     * 而错位不会报错、只会让参数悄悄对不上。改字段必须同时改这里，
     * `StaminaWireTest` 会用"字段数 = 元素数"把漏改钉住。
     *
     * ⚠️ 与 [sanitized] 一样：**只增不减不改序**，否则两侧要同时升级。
     */
    fun toWire(): FloatArray = floatArrayOf(
        if (enabled) 1f else 0f,
        decayPerMinute.toFloat(),
        recoverCoefficient.toFloat(),
        restAtPercent.toFloat(),
        resumeAtPercent.toFloat(),
        fatigueSec.toFloat(),
        transitionSec.toFloat(),
        walkSpeed.toFloat(),
        minSpeedFactor.toFloat(),
        randomPercent.toFloat(),
        moveIgnoreWindowSec.toFloat(),
        moveIgnoreSpeed.toFloat(),
    )

    companion object {
        /** 与 [toWire] 配对的**唯一**解析处；长度不符一律返回 null（宁可保持旧值，不要读半个配置） */
        fun fromWire(w: FloatArray?): StaminaConfig? {
            if (w == null || w.size != WIRE_SIZE) return null
            return StaminaConfig(
                enabled = w[0] != 0f,
                decayPerMinute = w[1].toDouble(),
                recoverCoefficient = w[2].toDouble(),
                restAtPercent = w[3].toDouble(),
                resumeAtPercent = w[4].toDouble(),
                fatigueSec = w[5].toDouble(),
                transitionSec = w[6].toDouble(),
                walkSpeed = w[7].toDouble(),
                minSpeedFactor = w[8].toDouble(),
                randomPercent = w[9].toDouble(),
                moveIgnoreWindowSec = w[10].toDouble(),
                moveIgnoreSpeed = w[11].toDouble(),
            ).sanitized()
        }

        const val WIRE_SIZE = 12
    }

    /**
     * 夹取到有意义的范围：防界面输入 0/负数/离谱值把模拟弄成静止或瞬移。
     *
     * [resumeAtPercent] 是**派生夹取**：它必须比休息体力值至少高 1 个百分点。
     * 这不是"顺手做的校验"，而是语义要求 —— 两者相等/倒挂时"冷却进度"在进入疲劳的
     * 当拍就会 ≥ 0，"疲劳"退化成一拍进一拍出的抖动（实测每 1.5 秒一次、45 分钟 795 次）。
     */
    fun sanitized(): StaminaConfig {
        val restAt = restAtPercent.coerceIn(0.0, 98.0)
        return copy(
            decayPerMinute = decayPerMinute.coerceIn(0.1, 600.0),
            recoverCoefficient = recoverCoefficient.coerceIn(0.01, 600.0),
            restAtPercent = restAt,
            resumeAtPercent = resumeAtPercent.coerceIn(restAt + 1.0, 99.0),
            fatigueSec = fatigueSec.coerceIn(1.0, 3600.0),
            transitionSec = transitionSec.coerceIn(0.0, 60.0),
            walkSpeed = walkSpeed.coerceIn(0.1, 10.0),
            minSpeedFactor = minSpeedFactor.coerceIn(0.05, 1.0),
            randomPercent = randomPercent.coerceIn(0.0, 60.0),
            moveIgnoreWindowSec = moveIgnoreWindowSec.coerceIn(0.0, 60.0),
            moveIgnoreSpeed = moveIgnoreSpeed.coerceIn(0.5, 200.0),
        )
    }
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
 *
 * ## 倍率公式在哪
 *
 * 三条倍率公式（恢复倍率 / 疲劳倍率 / 最终倍率）都在 [StaminaMath] —— 因为
 * [StaminaCurve] 那张"倍率 × 距离"预览图要算同一组公式。这里只负责**状态推进**。
 */
class StaminaModel {

    private val lock = Any()
    private var staminaPercent: Double = 100.0
    private var resting: Boolean = false
    private var currentMultiplier: Double = 1.0
    private var restCount: Int = 0
    private var restTotalSec: Double = 0.0
    private var decayThisRun: Double = Double.NaN

    /**
     * 疲劳倒计时**剩余预算**（秒）。进疲劳时 = [StaminaConfig.fatigueSec] ×(1±随机化幅度)，
     * 每拍按 [StaminaMath.fatigueTickRate] 扣减，扣到 ≤ 0 即开跑。
     *
     * 旧实现是一个"冷却积分"（负数欠账、回到 0 才开跑）：它的数值不可读、且参数不当就
     * 永不结束。换成预算倒计时后，"还剩多少秒"就是屏幕上那个数。
     */
    private var fatigueRemainingSec: Double = 0.0

    /** 忽略窗口里的速度采样（拍长秒, 表象速度 m/s） */
    private val window = ArrayDeque<Pair<Double, Double>>()
    /** 因"过快"被忽略的拍数（诊断：能一眼看出有没有异常帧） */
    private var ignoredTicks: Long = 0

    /**
     * 跑动档 ↔ 疲劳档之间的混合量：0 = 完全跑动档，1 = 完全疲劳档。
     * 用它把"进/出疲劳"的台阶变成斜坡；收敛速率 = 1 / 本次过渡时长。
     */
    private var blend: Double = 0.0

    /** 本次过渡的目标档（0/1）。目标一变就重抽过渡时长 ⇒ 每次过渡都不一样 */
    private var blendTarget: Double = 0.0

    /** 本次过渡抽到的时长（秒），每次方向变化重抽 */
    private var transitionThisEvent: Double = Double.NaN
    private var lastApparent = 0.0

    data class Snapshot(
        val staminaPercent: Double,
        val resting: Boolean,
        /** 疲劳倒计时剩余（秒）：> 0 = 还在疲劳里，≤ 0 = 开跑。非疲劳时为 0 */
        val fatigueRemainingSec: Double,
        /** 过渡混合量：0 = 跑动档，1 = 疲劳档，中间 = 正在过渡 */
        val blend: Double,
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
            staminaPercent, resting, fatigueRemainingSec, blend, currentMultiplier,
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
        fatigueRemainingSec = 0.0
        blend = 0.0
        blendTarget = 0.0
        transitionThisEvent = Double.NaN
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
            // 恢复 = 系数/60 × 恢复倍率(体力)：倍率 100%→1.0、0%→2.0（越低越快）
            val recoverPerSecond =
                c.recoverCoefficient / 60.0 * StaminaMath.recoveryFactor(staminaPercent)
            staminaPercent += recoverPerSecond * dtSec

            // 3) 消耗：只在"本拍确有位移"且未被忽略时
            if (!ignoring && moved > 0.0) {
                if (decayThisRun.isNaN()) decayThisRun = jitter(c.decayPerMinute, c.randomPercent, random)
                // 消耗系数同样是「点/分钟」+ 与表象速度成正比
                staminaPercent -= decayThisRun / 60.0 * (apparent / base) * dtSec
            }
            staminaPercent = staminaPercent.coerceIn(0.0, 100.0)

            // 4) 疲劳状态：进看体力阈值，**出看倒计时预算**（用户口径的"疲劳时长"）
            //    ⚠️ 出不能再使用体力阈值（无迟滞 ⇒ 两侧净速率相反 ⇒ 继电器振荡：
            //    实测旧口径 45 分钟进疲劳 1148 次、每次都只有一拍）。
            if (!resting && staminaPercent <= c.restAtPercent) {
                resting = true
                restCount += 1
                // 每次疲劳重抽预算（× ±随机化幅度）—— 两次不会一样长
                fatigueRemainingSec = jitter(c.fatigueSec, c.randomPercent, random)
                decayThisRun = Double.NaN    // 下一段跑动重新抽消耗速率
            } else if (resting) {
                // 低于开跑阈值 ⇒ 倒得慢（几乎停滞），达到/超过 ⇒ 倒得快，越远越快
                fatigueRemainingSec -= StaminaMath.fatigueTickRate(c, staminaPercent) * dtSec
                if (fatigueRemainingSec <= 0.0) {
                    resting = false
                    fatigueRemainingSec = 0.0
                }
            }
            if (resting) restTotalSec += dtSec
            // 5) 过渡：把"进/出疲劳"的台阶变成斜坡。
            //    目标档一变（刚进或刚出疲劳）就重抽本次过渡时长 —— 乘 ±随机化幅度，
            //    于是每次过渡的秒数都不同，不会看出"每次都一样慢下来"的机械感。
            val target = if (resting) 1.0 else 0.0
            if (target != blendTarget) {
                blendTarget = target
                transitionThisEvent = jitter(c.transitionSec, c.randomPercent, random)
            }
            if (blend != blendTarget) {
                // 时长为 0 ⇒ 每拍走满 ⇒ 一步到位（等价于关掉过渡的旧行为）
                val rate = if (transitionThisEvent <= 0.0) Double.MAX_VALUE else dtSec / transitionThisEvent
                blend = if (blendTarget > blend) (blend + rate).coerceAtMost(1.0)
                else (blend - rate).coerceAtLeast(0.0)
            }

            // 6) 倍率：用**本拍结算之后的体力**。
            // ⚠️ 这里原先读的是"扣消耗之前"的体力（fatigue 在消耗之前算），于是倍率慢一拍：
            // 同一拍里已经扣掉的体力不影响该拍的降速。偏差很小（单拍 <1e-4），但它是"图与实跑
            // 对不上"的根源 —— StaminaCurve 按结算后体力积分，两边差在第 1 拍就能看见。
            currentMultiplier = StaminaMath.multiplierFor(
                c, base, StaminaMath.fatigueFactor(c, staminaPercent), blend
            )
            return currentMultiplier
        }
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
