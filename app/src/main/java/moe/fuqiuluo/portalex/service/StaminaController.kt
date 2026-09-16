package moe.fuqiuluo.portalex.service

import android.content.Context
import moe.fuqiuluo.portalex.ext.StaminaPrefs
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.StaminaConfig
import moe.fuqiuluo.xposed.utils.StaminaModel

/**
 * 体力模拟的**唯一接口**（App 侧）。
 *
 * ## 职责边界（本轮的硬约定）
 *
 * | 谁 | 只做 | 不许做 |
 * | --- | --- | --- |
 * | 移动相关代码（运动循环、摇杆推进、路线播放） | **只读 [speedMultiplier]** | 不判断休息/体力、不碰模型、不自己换算 |
 * | 体力相关代码（本类与 [StaminaModel]） | **只判"现在是否在跑"** | 不读 `FakeLoc.speed` 做位移、不做距离计算 |
 *
 * 两边各自只有一件事可变：调速策略变了只改模型，位移策略变了只改运动循环。
 * 之前把"够不够累"的判断漏在调用方，就出过两种偏差（空闲掉血、静止回血）。
 *
 * ## 状态由"真实运动"驱动，但**恢复永远在进行**
 *
 * ```
 * 本拍有表象位移 → 消耗与恢复同时进行（净效果 = 恢复 − 消耗）
 * 本拍无位移      → 只有恢复（体力**继续回**，直到回满）
 * 位移"过快"      → 判为异常帧，本拍只走恢复、不计消耗（防瞬移抽干体力）
 * ```
 *
 * 一句话口径：**只有消耗是条件性的**（需要有位移），**恢复是无条件的背景过程**。
 * 判断"现在算不算跑动"只影响界面显示与倍率，不影响体力是否恢复。
 *
 * ## 为什么体力放在 App 侧
 *
 * 因为**运动是在 App 侧生成的**：`MockServiceViewModel.ensureMotionLoop` 用 `FakeLoc.speed`
 * 算出每拍位移再下发；而系统侧注入的位置速度是**从实际位移反推**的
 * （`VirtualWorld.averageSpeedOverWindow` → `BaseLocationHook`）。
 * 于是只要这里把每拍推进量按倍率缩放，**注入速度与步频会自动跟着降**，模块侧一行都不用改。
 * 反过来若只改报出来的速度数字，就会造出「位移与 reported speed 自相矛盾」——本仓踩过的坑。
 */
object StaminaController {

    private val model = StaminaModel()

    @Volatile private var config: StaminaConfig = StaminaConfig()

    /**
     * 当前速度倍率（1.0 = 不做任何调制）。**移动侧唯一该读的东西。**
     *
     * 未启用或空闲时对外都是 1.0 —— 于是"关掉体力模拟"与"没在动"在位移计算上完全等价，
     * 调用方不需要任何分支。
     */
    @Volatile private var multiplier: Double = 1.0

    /** 本拍是否真的在运动（由运动循环按自动播放/摇杆门给出） */
    @Volatile private var running: Boolean = false

    @Volatile private var lastTickNanos: Long = 0L

    /** 本拍累计的表象位移（由 [noteMoved] 写入、[tick] 读取后清零） */
    @Volatile private var movedThisFrame: Double = 0.0

    fun config(): StaminaConfig = config

    /** 当前速度倍率：移动相关代码**只读它** */
    fun speedMultiplier(): Double = if (config.enabled) multiplier else 1.0

    /** 现在是否处于"跑动"（空闲与休息都不算；界面显示用） */
    fun isRunning(): Boolean = config.enabled && running && !model.snapshot().resting

    /** 现在是否处于"休息" */
    fun isResting(): Boolean = config.enabled && running && model.snapshot().resting

    /** 启动/进入会话时调用：读一次库、复位体力 */
    fun load(context: Context) {
        config = StaminaPrefs.load(context)
        model.reset()
        multiplier = 1.0
        running = false
        lastTickNanos = 0L
    }

    /** 界面改动参数：落库并立即生效（不重置体力 —— 改参数不该把"跑到一半的人"重置） */
    fun applyConfig(context: Context, newConfig: StaminaConfig) {
        config = newConfig.sanitized()
        StaminaPrefs.save(context, config)
        if (!config.enabled) multiplier = 1.0
    }

    /** 界面上的"重置体力"：回到满体力、清空休息计时与统计 */
    fun resetStamina() {
        model.reset()
        multiplier = 1.0
        lastTickNanos = 0L
    }

    /**
     * 运动循环每拍调用一次。**体力只在 [tick] 里推进。**
     *
     * @param movedMeters 上一拍**表象位移**（米）——体力绑定的就是它，而不是"会话开着"。
     *   0 表示那一拍位置没动（空闲/暂停）。由运动循环在推进时记账（见 [noteMoved]）。
     * @param baseSpeed 基础速度（m/s，配置速度）。模型用它把"走路低值"这类绝对量换算成倍率
     *   （走路 1.1 / 基础 3.05 = 0.36）；换算只在模型内部发生一次。
     */
    fun tick(movedMeters: Double, baseSpeed: Double) {
        val now = System.nanoTime()
        val dtSec = if (lastTickNanos == 0L) 0.0 else (now - lastTickNanos) / 1_000_000_000.0
        lastTickNanos = now
        val moved = movedMeters
        this.running = moved > 0.0

        val c = config
        if (!c.enabled) {
            multiplier = 1.0
            return
        }
        // 上限 5s：长时间冻结后不补出一个巨大的 Δt（与运动循环的 MAX_ADVANCE_MS 同思路）
        val dt = dtSec.coerceIn(0.0, 5.0)
        val before = multiplier
        multiplier = model.tick(c, dt, baseSpeed, moved)
        // 可观测性：倍率**变化时**打一条（调试开关下）。没有这条日志，"体力有没有真的生效"
        // 就只能靠肉眼看配速 —— 那是无法在测试里复现的证据形式。
        if (moved > 0.0 && FakeLoc.enableDebugLog && kotlin.math.abs(multiplier - before) > 5e-3) {
            val s = model.snapshot()
            android.util.Log.i(
                "StaminaController",
                "体力 %.1f%% 阶段=%s 倍率 %.3f ⇒ 实速 %.2f m/s（基础 %.2f）休息%d次".format(
                    s.staminaPercent,
                    if (s.resting) "休息(剩%.0fs)".format(s.restRemainingSec) else "跑动",
                    multiplier, baseSpeed * multiplier, baseSpeed, s.restCount,
                )
            )
        }
    }

    /**
     * **取走**本拍累计的表象位移并清零。运动循环在每一步推进之后调用它，
     * 再把结果喂给 [tick] —— "取并清零"必须是显式操作，
     * 不能靠外部字段读取（否则 tick 里的清理会把它抹掉，这类错很隐蔽）。
     */
    fun takeMovedMeters(): Double {
        val m = movedThisFrame
        movedThisFrame = 0.0
        return m
    }

    /**
     * 运动循环在**推进位置之后**记账：本拍实际推进了多少米。
     *
     * 为什么要记账而不是让 tick 直接参数化：位移是"推进的结果"（路线播完、摇杆门关闭、
     * 位置未初始化都可能使实际推进小于名义值），只有推进方自己知道真实值。
     * 记账制让两边都不必互相知道对方算到了哪一步。
     */
    fun noteMoved(meters: Double) {
        if (meters.isNaN() || meters <= 0.0) return
        movedThisFrame += meters
    }

    fun snapshot(): StaminaModel.Snapshot = model.snapshot()

    /**
     * 当前**恢复速率**（点/分钟）—— 供界面显示"空闲时也在回体力"这件事。
     *
     * 与模型内部完全同一口径：`系数 × 恢复倍率(体力) ÷ 冷却系数`（模型里再 /60 成每秒）。
     * 放在这里而不是让界面自己算：倍率公式只该有一处实现，否则显示值与实际值迟早漂移。
     */
    fun recoveringPerMinute(): Double {
        val c = config
        if (!c.enabled) return 0.0
        val s = model.snapshot()
        val factor = 2.0 - s.staminaPercent.coerceIn(0.0, 100.0) / 100.0
        return c.recoverCoefficient * factor / c.restSecondsCoefficient
    }

    /** 当前实际速度（配置速度 × 倍率），供界面显示 */
    fun effectiveSpeed(baseSpeed: Double): Double = baseSpeed * speedMultiplier()
}
