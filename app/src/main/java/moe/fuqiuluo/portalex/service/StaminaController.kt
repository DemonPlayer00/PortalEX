package moe.fuqiuluo.portalex.service

import android.content.Context
import moe.fuqiuluo.portalex.ext.StaminaPrefs
import moe.fuqiuluo.portalex.ext.speed
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.StaminaConfig
import moe.fuqiuluo.xposed.utils.StaminaModel
import kotlin.math.abs

/**
 * 体力模拟的**运行期持有者**（App 侧）。
 *
 * ## 为什么体力放在 App 侧而不是模块侧
 *
 * 因为**运动是在 App 侧生成的**：`MockServiceViewModel.ensureMotionLoop` 用 `FakeLoc.speed`
 * 算出每拍位移，再通过 `move` 命令交给系统侧。
 * 而系统侧注入的位置速度是**从实际位移反推**的（`VirtualWorld.averageSpeedOverWindow`
 * → `BaseLocationHook`），所以只要这里把每拍的推进量按体力缩放，
 * **注入速度与步频会自动跟着降**，模块侧一行都不用改。
 *
 * 反过来若把体力放在模块侧、只去改报出来的速度数字，就会造出
 * 「位移与 reported speed 自相矛盾」——那正是这个仓历史上踩过的坑
 * （见 `BaseLocationHook` 里关于 measuredSpeed 的注释）。
 *
 * ## 它做什么
 *
 * - 持有 [StaminaModel] 与当前参数（参数改动由界面调用 [applyConfig] 落库并立即生效）；
 * - [tickAndGetScale] 由运动循环每拍调用：推进模型、返回**本拍速度系数**；
 * - [currentScale] 供别处读取（例如界面刷新时显示"当前实际速度"）。
 *
 * ## 线程纪律
 *
 * 运动循环是唯一写者（协程单线程），界面只读 —— 读走 [StaminaModel.snapshot]（内部加锁），
 * 这里额外用 `@Volatile` 暴露系数，避免界面读到半更新的值。
 */
object StaminaController {

    private val model = StaminaModel()

    @Volatile private var config: StaminaConfig = StaminaConfig()

    /** 当前速度系数（1.0 = 不做任何调制） */
    @Volatile var currentScale: Double = StaminaModel.NO_MODULATION
        private set

    /** 上一次 tick 的时间戳（纳秒）——本拍时长由两次 tick 的真实间隔决定，不用名义周期 */
    @Volatile private var lastTickNanos: Long = 0L

    fun config(): StaminaConfig = config

    /** 启动/进入会话时调用：读一次库、复位体力 */
    fun load(context: Context) {
        config = StaminaPrefs.load(context)
        model.reset()
        currentScale = if (config.enabled) 1.0 else StaminaModel.NO_MODULATION
        lastTickNanos = 0L
    }

    /** 界面改动参数：落库并立即生效（不重置体力 —— 改参数不该把"跑到一半的人"重置） */
    fun applyConfig(context: Context, newConfig: StaminaConfig) {
        config = newConfig.sanitized()
        StaminaPrefs.save(context, config)
        if (!config.enabled) {
            currentScale = StaminaModel.NO_MODULATION
        }
    }

    /** 界面上的"重置体力"：回到满体力、清空休息计时与统计 */
    fun resetStamina() {
        model.reset()
        currentScale = if (config.enabled) 1.0 else StaminaModel.NO_MODULATION
        lastTickNanos = 0L
    }

    /**
     * 运动循环每拍调用。
     *
     * @param baseSpeed 基础速度（配置速度，m/s）——休息时的系数 = 走路速度/基础速度，
     *   所以模型必须知道它（口径统一在模型内部，调用点不做二次换算）
     * @return 本拍速度系数
     */
    fun tickAndGetScale(baseSpeed: Double): Double {
        val now = System.nanoTime()
        val dtSec = if (lastTickNanos == 0L) 0.0 else (now - lastTickNanos) / 1_000_000_000.0
        lastTickNanos = now
        val c = config
        if (!c.enabled) {
            currentScale = StaminaModel.NO_MODULATION
            return currentScale
        }
        // 上限 5s：长时间冻结后不补出一个巨大的 Δt（与运动循环的 MAX_ADVANCE_MS 同思路）
        val dt = dtSec.coerceIn(0.0, 5.0)
        val before = currentScale
        currentScale = model.tick(c, dt, baseSpeed)
        // 可观测性：系数**变化时**打一条（调试开关下）。没有这条日志，"体力有没有真的生效"
        // 就只能靠肉眼看配速 —— 那是无法在测试里复现的证据形式。
        if (c.enabled && FakeLoc.enableDebugLog && kotlin.math.abs(currentScale - before) > 5e-3) {
            val s = model.snapshot()
            android.util.Log.i(
                "StaminaController",
                "体力 %.1f%% 阶段=%s 系数 %.3f ⇒ 实速 %.2f m/s（基础 %.2f）休息%d次".format(
                    s.staminaPercent,
                    if (s.resting) "休息(剩%.0fs)".format(s.restRemainingSec) else "跑动",
                    currentScale, baseSpeed * currentScale, baseSpeed, s.restCount,
                )
            )
        }
        return currentScale
    }

    fun snapshot(): StaminaModel.Snapshot = model.snapshot()

    /** 当前实际速度（配置速度 × 系数），供界面显示 */
    fun effectiveSpeed(baseSpeed: Double): Double = baseSpeed * currentScale

    /** 系数是否已偏离 1.0（界面据此决定要不要显示"降速中"） */
    fun isModulating(): Boolean = config.enabled && abs(currentScale - StaminaModel.NO_MODULATION) > 1e-3

    /** 便捷：按当前上下文读取配置速度（供界面显示用） */
    fun baseSpeedOf(context: Context): Double = context.speed
}
