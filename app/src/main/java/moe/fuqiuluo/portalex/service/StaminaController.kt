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
 * ## 状态只有三态，且由"真实运动"驱动
 *
 * ```
 * running=true  且 未休息 → 跑动：掉体力、倍率随体力下滑
 * running=true  且 休息中 → 休息：速度=走路低值、体力回升
 * running=false           → 空闲：**体力冻结、倍率保持**
 * ```
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
     * @param running 本拍**是否真的在运动**（自动播放中、或摇杆门未暂停）。
     *   由运动循环给出 —— 体力代码不去猜"动没动"，移动代码也不去猜"累不累"。
     * @param baseSpeed 基础速度（m/s，配置速度）。模型需要它把"走路低值"这样的绝对量
     *   换算成倍率（走路 1.1 / 基础 3.05 = 0.36）；该换算只在模型内部发生一次。
     */
    fun tick(running: Boolean, baseSpeed: Double) {
        val now = System.nanoTime()
        val dtSec = if (lastTickNanos == 0L) 0.0 else (now - lastTickNanos) / 1_000_000_000.0
        lastTickNanos = now
        this.running = running

        val c = config
        if (!c.enabled) {
            multiplier = 1.0
            return
        }
        // 上限 5s：长时间冻结后不补出一个巨大的 Δt（与运动循环的 MAX_ADVANCE_MS 同思路）
        val dt = dtSec.coerceIn(0.0, 5.0)
        val before = multiplier
        multiplier = model.tick(c, dt, baseSpeed, running)
        // 可观测性：倍率**变化时**打一条（调试开关下）。没有这条日志，"体力有没有真的生效"
        // 就只能靠肉眼看配速 —— 那是无法在测试里复现的证据形式。
        if (running && FakeLoc.enableDebugLog && kotlin.math.abs(multiplier - before) > 5e-3) {
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

    fun snapshot(): StaminaModel.Snapshot = model.snapshot()

    /** 当前实际速度（配置速度 × 倍率），供界面显示 */
    fun effectiveSpeed(baseSpeed: Double): Double = baseSpeed * speedMultiplier()
}
