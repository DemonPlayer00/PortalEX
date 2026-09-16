package moe.fuqiuluo.portalex.service

import android.content.Context
import android.location.LocationManager
import moe.fuqiuluo.portalex.ext.StaminaPrefs
import moe.fuqiuluo.xposed.utils.PortalProtocol.Key
import moe.fuqiuluo.xposed.utils.StaminaConfig
import moe.fuqiuluo.xposed.utils.StaminaModel

/**
 * 体力模拟的 App 侧接口 —— **迁移后是瘦客户端**（统一架构：状态机在 system_server）。
 *
 * ## 职责（这一层只有两件事）
 *
 * | 方向 | 做什么 |
 * | --- | --- |
 * | 写 | 参数：落库 + 下发（`put_config` 的 `stamina_config`）；"重置体力"→ `reset_stamina` |
 * | 读 | 状态：`get_stamina` 回读（百分比/阶段/倍率/统计），失败就说"读不到"，不编数 |
 *
 * ## 为什么不再在 App 里跑模型
 *
 * 位置流只能在生成它的地方被缩放（见 `docs/sensor-architecture.md`）：推进搬到系统侧之后，
 * 体力必须与推进**同侧同拍**结算，否则两套积分器会越差越远（位移与速度自相矛盾）。
 * 顺带修掉一个老毛病：**杀掉 App 再开，体力不再回满** —— 状态不再挂在 App 的生命周期上。
 *
 * ## 读不到时说读不到
 *
 * [snapshot] 只在 [refresh] 成功之后才有值（[hasState]）。模块未装载（无 LSPosed）或
 * 命令通道未握手时读不到，页面显示"未生效"，而不是拿本进程的默认值假装在跑。
 */
object StaminaController {

    /** 参数（App 是参数的编辑者与持久化者；状态机在模块侧） */
    @Volatile
    private var config: StaminaConfig = StaminaConfig()

    /** 最近一次回读的模块状态；null = 还没读到过 */
    @Volatile
    private var state: StaminaModel.Snapshot? = null

    @Volatile
    private var movedAgoSec: Double = Double.MAX_VALUE

    @Volatile
    private var recoverPerMinute: Double = 0.0

    /** 模块侧是否已收到过参数（false = 模块还在用内置默认，页面与实跑会对不上） */
    @Volatile
    private var wireApplied: Boolean = false

    fun config(): StaminaConfig = config

    /** 是否有可读的模块状态（页面据此显示"未生效"而不是假数据） */
    fun hasState(): Boolean = state != null

    /** 模块侧参数是否已同步 */
    fun isWireApplied(): Boolean = wireApplied

    /** 会话/循环启动时读一次库（参数来源仍是 App 偏好；**不重置体力**） */
    fun load(context: Context) {
        config = StaminaPrefs.load(context)
    }

    /**
     * 改参数：落库 + **立即下发**。
     *
     * 为什么必须当场下发：参数在系统侧生效，只写本地偏好会造出"页面显示新参数、
     * 实跑还是旧参数"的错位 —— 那正是这一轮要消灭的那类不一致。
     */
    fun applyConfig(context: Context, newConfig: StaminaConfig) {
        config = newConfig.sanitized()
        StaminaPrefs.save(context, config)
        push(context)
    }

    /** 参数下发（整份 `put_config`，幂等）。没握手/没有定位服务时什么都不做 */
    fun push(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        ConfigSync.push(context, lm)
    }

    /** 手动的"重置体力"：状态在系统侧，所以必须下发命令（本地清一下缓存显示） */
    fun resetStamina(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) MockServiceHelper.resetStamina(lm)
        state = null
        movedAgoSec = Double.MAX_VALUE
    }

    /**
     * 状态回读（页面每秒调一次）。读不到返回 false，并**保留**上一次的值
     * （短暂失败不该让界面跳成 0）。
     */
    fun refresh(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val rely = MockServiceHelper.getStamina(lm) ?: return false
        wireApplied = rely.getBoolean(Key.STAMINA_WIRE, false)
        movedAgoSec = rely.getDouble(Key.STAMINA_MOVED_AGO_SEC, Double.MAX_VALUE)
        recoverPerMinute = rely.getDouble(Key.STAMINA_RECOVER_PER_MIN, 0.0)
        state = StaminaModel.Snapshot(
            staminaPercent = rely.getDouble(Key.STAMINA_PERCENT, 100.0),
            resting = rely.getBoolean(Key.STAMINA_RESTING, false),
            fatigueRemainingSec = rely.getDouble(Key.STAMINA_REMAINING_SEC, 0.0),
            blend = rely.getDouble(Key.STAMINA_BLEND, 0.0),
            speedScale = rely.getDouble(Key.STAMINA_MULTIPLIER, 1.0),
            restCount = rely.getInt(Key.STAMINA_REST_COUNT, 0),
            restTotalSec = rely.getDouble(Key.STAMINA_REST_TOTAL_SEC, 0.0),
            apparentSpeed = rely.getDouble(Key.STAMINA_APPARENT_SPEED, 0.0),
            ignoredTicks = rely.getLong(Key.STAMINA_IGNORED_TICKS, 0L),
        )
        return true
    }

    /** 最近一次回读的状态；从未读到过时给一份"空"快照（调用方应先看 [hasState]） */
    fun snapshot(): StaminaModel.Snapshot = state ?: StaminaModel.Snapshot(
        staminaPercent = 0.0,
        resting = false,
        fatigueRemainingSec = 0.0,
        blend = 0.0,
        speedScale = 1.0,
        restCount = 0,
        restTotalSec = 0.0,
        apparentSpeed = 0.0,
        ignoredTicks = 0L,
    )

    /** 现在是否处于"跑动"（空闲与疲劳都不算）。判据是模块侧的**真实位移时刻** */
    fun isRunning(): Boolean =
        config.enabled && state != null && movedAgoSec < 1.0 && state?.resting != true

    /** 现在是否处于"疲劳" */
    fun isResting(): Boolean = config.enabled && state?.resting == true

    /** 当前恢复速率（点/分钟）—— 模块侧算好回传（公式只有一处实现） */
    fun recoveringPerMinute(): Double = recoverPerMinute

    /** 当前实际速度（基础速度 × 模块回传的倍率） */
    fun effectiveSpeed(baseSpeed: Double): Double = baseSpeed * snapshot().speedScale
}
