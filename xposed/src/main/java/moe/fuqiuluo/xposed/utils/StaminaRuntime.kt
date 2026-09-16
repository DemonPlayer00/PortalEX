package moe.fuqiuluo.xposed.utils

import android.os.Bundle
import android.os.SystemClock
import moe.fuqiuluo.xposed.utils.PortalProtocol.Key
import kotlin.math.abs

/**
 * 体力状态机的**系统侧持有者**（统一架构迁移步骤①，与推进引擎同侧）。
 *
 * ## 为什么必须在这一侧
 *
 * 位置流只能在**生成它的地方**被缩放（见 `docs/sensor-architecture.md` 第三节那条硬约束）：
 * 交付给应用的坐标是绝对量，模块侧"事后缩放"只能做出一个永远落后的滞后积分器，
 * 路线永远走不到终点。所以推进引擎搬进 system_server 之后，体力也必须在这一侧 ——
 * 每拍用 [multiplier] 缩放**本拍推进量**，位移与速度自然一致（注入速度由实际位移反推）。
 *
 * ## 与 App 的关系
 *
 * | 谁 | 做什么 |
 * | --- | --- |
 * | App | 只下发参数（`stamina_config`）、点"重置"、读回状态显示 |
 * | 本对象 | 持有 [StaminaModel]、每拍推进、给出倍率 |
 *
 * 于是**杀掉 App 再开，体力不回满**：状态不再挂在 App 的生命周期上（验收判据 3）。
 *
 * ## 推进纪律
 *
 * `tick` 由模块时钟（[moe.fuqiuluo.xposed.hooks.MotionClock]）与推进同一拍调用，
 * `movedMeters` 必须是**本拍真实交付的位移**（不是名义值）——与旧实现同一口径：
 * "路线播完/摇杆门关闭/位置未初始化"这些没真的动的情况为 0，体力也就不会凭"会话开着"掉血。
 */
object StaminaRuntime {

    private val model = StaminaModel()

    /** 参数（App 下发的定序数组解析而来）。未下发时用 [StaminaConfig] 的内置默认 */
    @Volatile
    private var config: StaminaConfig = StaminaConfig()

    /** 上一拍结算出的速度倍率。推进引擎每拍只读它（关掉体力时对外恒 1.0） */
    @Volatile
    private var multiplier: Double = StaminaModel.NO_MODULATION

    /** App 是否已经下发过参数（诊断：false = 还在用内置默认，页面与模块可能对不上） */
    @Volatile
    var wireApplied: Boolean = false
        private set

    /** 上一次"真的有位移"的时刻（界面据此区分"跑动中"与"空闲"，不靠猜） */
    @Volatile
    private var lastMovedNanos: Long = 0L

    /** 上一次记账的时刻（[tick] / [reconcileNow] 都更新它）：空闲段靠它补账 */
    @Volatile
    private var lastTickNanos: Long = 0L

    /** 单次补账的上限（秒）：进程被冻几小时不该一口气补出几小时的恢复 */
    private const val MAX_RECONCILE_SEC = 30.0 * 60.0

    fun config(): StaminaConfig = config

    /** 参数下发（`put_config` 里的 `stamina_config`）。长度不符一律保持旧值，不读半个配置 */
    fun applyWire(wire: FloatArray?) {
        val parsed = StaminaConfig.fromWire(wire) ?: return
        config = parsed
        wireApplied = true
        if (!parsed.enabled) multiplier = StaminaModel.NO_MODULATION
    }

    /** 当前速度倍率（1.0 = 不调制）。推进引擎唯一该读的东西 */
    fun multiplier(): Double = if (config.enabled) multiplier else StaminaModel.NO_MODULATION

    /** 界面上的"重置体力"：回到满体力、清空休息计时与统计（不改参数、不动会话） */
    fun reset() {
        model.reset()
        multiplier = StaminaModel.NO_MODULATION
        lastMovedNanos = 0L
        lastTickNanos = 0L
    }

    /**
     * 推进一拍。**只在 [config] 启用时改变倍率**；关掉体力时对外恒 1.0
     * （于是"关掉体力模拟"与"没在动"在位移计算上完全等价，调用方不需要分支）。
     */
    fun tick(baseSpeed: Double, movedMeters: Double) {
        val now = SystemClock.elapsedRealtimeNanos()
        val last = lastTickNanos
        lastTickNanos = now
        val c = config
        if (!c.enabled) {
            multiplier = StaminaModel.NO_MODULATION
            return
        }
        // 上限 5s：长时间冻结（进程被挂起）后不补出一个巨大的 Δt
        if (movedMeters > 0.0) lastMovedNanos = SystemClock.elapsedRealtimeNanos()
        // dt 由本对象自己算（见下方 KDoc）：账只能记一次 —— 若由调用方给 dt，
        // 而 reconcileNow（读取时补账）又动过 lastTickNanos，同一段时间会被记两遍
        val dt = (if (last == 0L) 0.0 else (now - last) / 1e9).coerceIn(0.0, 5.0)
        val before = multiplier
        multiplier = model.tick(c, dt, baseSpeed, movedMeters)
        // 可观测性：倍率**变化时**打一条（调试开关下）。没有这条日志，"体力有没有真的生效"
        // 就只能靠肉眼看配速 —— 那是无法在测试里复现的证据形式。
        if (FakeLoc.enableDebugLog && movedMeters > 0.0 && abs(multiplier - before) > 5e-3) {
            Logger.info("StaminaRuntime: ${statusLine()}")
        }
    }

    /**
     * **把"没记账"的那段时间按空闲补上**（恢复是连续的背景过程，不该因为没人推进就停表）。
     *
     * 为什么需要它：推进时钟在没有推进意图时会停摆（见 [moe.fuqiuluo.xposed.hooks.MotionClock]），
     * 于是"静止的这 10 分钟"没有任何一拍去推进体力。两类时刻要补账：
     *  · 停摆前（时钟自己调一次，保证停摆期间的值不再变化）；
     *  · 任何一次读取/下发之前（`get_stamina`、`get_motion`、以及重新开始推进的第一拍）。
     *
     * 分块积分（≤1s/块）而不是一次大 dt：模型的恢复速率随体力变化（`2 - S/100`），
     * 一次跨小时的欧拉步会有可观误差；分块后与逐拍推进的结果一致。
     * 空闲段只走"恢复 + 疲劳倒计时"（`moved = 0`），消耗仍只在真有位移时发生。
     */
    fun reconcileNow() {
        val now = SystemClock.elapsedRealtimeNanos()
        val last = lastTickNanos
        lastTickNanos = now
        val c = config
        if (!c.enabled || last == 0L || now <= last) return
        if (lastMovedNanos > last) return   // 这段时间里其实"跑过"（由正常的 tick 记过账）
        var remain = ((now - last) / 1e9).coerceAtMost(MAX_RECONCILE_SEC)
        while (remain > 1e-3) {
            val step = if (remain > 1.0) 1.0 else remain
            // baseSpeed 传 1.0：moved = 0 时它不参与任何计算（消耗是唯一用到它的地方）
            multiplier = model.tick(c, step, 1.0, 0.0)
            remain -= step
        }
    }

    fun snapshot(): StaminaModel.Snapshot = model.snapshot()

    /**
     * 当前**恢复速率**（点/分钟）—— 供界面显示"空闲时也在回体力"。
     * 与模型内部同一口径（公式只有一处实现，见 [StaminaMath.recoveryFactor]）。
     */
    fun recoveringPerMinute(): Double {
        val c = config
        if (!c.enabled) return 0.0
        return c.recoverCoefficient * StaminaMath.recoveryFactor(model.snapshot().staminaPercent)
    }

    /** 状态回传（`get_stamina` / `get_sensor_status` 的回包） */
    fun writeStatus(rely: Bundle) {
        // 读之前先把"没记账"的空闲段补上：页面看到的数就该是此刻的数
        // （推进时钟空闲时是停摆的，没人替它记账）
        reconcileNow()
        val s = model.snapshot()
        rely.putBoolean(Key.STAMINA_ENABLED, config.enabled)
        rely.putBoolean(Key.STAMINA_WIRE, wireApplied)
        rely.putDouble(Key.STAMINA_PERCENT, s.staminaPercent)
        rely.putDouble(Key.STAMINA_MULTIPLIER, multiplier())
        rely.putBoolean(Key.STAMINA_RESTING, s.resting)
        rely.putDouble(Key.STAMINA_REMAINING_SEC, s.fatigueRemainingSec)
        rely.putDouble(Key.STAMINA_BLEND, s.blend)
        rely.putInt(Key.STAMINA_REST_COUNT, s.restCount)
        rely.putDouble(Key.STAMINA_REST_TOTAL_SEC, s.restTotalSec)
        rely.putDouble(Key.STAMINA_APPARENT_SPEED, s.apparentSpeed)
        rely.putLong(Key.STAMINA_IGNORED_TICKS, s.ignoredTicks)
        rely.putDouble(Key.STAMINA_RECOVER_PER_MIN, recoveringPerMinute())
        val ago = if (lastMovedNanos == 0L) Double.MAX_VALUE
        else (SystemClock.elapsedRealtimeNanos() - lastMovedNanos) / 1e9
        rely.putDouble(Key.STAMINA_MOVED_AGO_SEC, ago)
    }

    fun statusLine(): String {
        val s = model.snapshot()
        return "体力 %.1f%% 阶段=%s 倍率 %.3f 休息%d次(共%.0fs) 忽略%d拍".format(
            s.staminaPercent,
            if (s.resting) "疲劳(剩%.0fs)".format(s.fatigueRemainingSec) else "跑动",
            multiplier(), s.restCount, s.restTotalSec, s.ignoredTicks,
        )
    }
}
