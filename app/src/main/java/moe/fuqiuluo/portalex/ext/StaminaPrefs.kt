package moe.fuqiuluo.portalex.ext

import android.content.Context
import androidx.core.content.edit
import moe.fuqiuluo.xposed.utils.StaminaConfig

/**
 * 体力模拟的参数持久化。
 *
 * 为什么单独一个键前缀（`stamina_`）而不是塞进 `sharedPrefs` 的散键：
 * 这些参数是一组**同生命周期**的配置（一起改、一起重置），前缀让"清空体力配置"这种操作
 * 有一个明确边界，也让排查时一眼能看出哪些键属于这个功能。
 *
 * 默认值直接取自 [StaminaConfig] 的声明 —— **不在两处各写一份默认**，
 * 否则界面显示的默认与模型实际用的默认会漂移（这种漂移只有用户抱怨"数值对不上"时才被发现）。
 */
object StaminaPrefs {

    private const val P = "stamina_"

    /**
     * 语义版本：**换语义就要 +1**。
     *
     * 为什么需要它：体力模型换过语义（旧版点率 → CS/D′/τD′）。老设备上存的
     * `stamina_decay` / `stamina_recover` 没有对应关系，必须靠版本号识别并整组重置；
     * 否则会出现"界面显示新默认、模块仍用旧参数"的静默错位。
     *
     * **什么时候不用 +1**：只是**新增/停用**键时不必动它 ——
     * 老数据里没有这个键，取默认值即可；停用的键（`rest_sec` = 旧"冷却时间系数"、
     * `rest_factor` = 旧"休息降速系数"）只是不再读，留在盘上无害。
     * 反之，只要有任何**旧键换了含义**（例如把 `rest_sec` 从"系数"改读成"秒"），就**必须 +1**，
     * 否则老值会被当成新语义读进来，而且完全静默 —— 这正是新键叫 `fatigue_sec` 而不是复用
     * `rest_sec` 的原因。
     */
    private const val VERSION_KEY = "stamina_version"
    /**
     * 2026-11-07：配置从"衰减/恢复点率"切到 CS / D′ / τD′ 公式参数。
     * 旧版本存下来的衰减/恢复点率没有对应关系，必须整组重置。
     */
    private const val VERSION = 3

    fun load(context: Context): StaminaConfig {
        val sp = context.sharedPrefs
        if (sp.getInt(VERSION_KEY, 0) < VERSION) {
            // 老语义数据：整组重置为当前默认，避免"读成新语义"的静默错误
            val fresh = StaminaConfig()
            save(context, fresh)
            sp.edit { putInt(VERSION_KEY, VERSION) }
            return fresh
        }
        val d = StaminaConfig()
        val loaded = StaminaConfig(
            enabled = sp.getBoolean(P + "enabled", d.enabled),
            criticalSpeedMps = sp.getFloat(P + "critical_speed", d.criticalSpeedMps.toFloat()).toDouble(),
            dPrimeMeters = sp.getFloat(P + "d_prime", d.dPrimeMeters.toFloat()).toDouble(),
            recoveryTauSec = sp.getFloat(P + "recovery_tau", d.recoveryTauSec.toFloat()).toDouble(),
            restAtPercent = sp.getFloat(P + "rest_at", d.restAtPercent.toFloat()).toDouble(),
            resumeAtPercent = sp.getFloat(P + "resume", d.resumeAtPercent.toFloat()).toDouble(),
            fatigueSec = sp.getFloat(P + "fatigue_sec", d.fatigueSec.toFloat()).toDouble(),
            transitionSec = sp.getFloat(P + "transition", d.transitionSec.toFloat()).toDouble(),
            moveIgnoreWindowSec = sp.getFloat(P + "ignore_window", d.moveIgnoreWindowSec.toFloat()).toDouble(),
            moveIgnoreSpeed = sp.getFloat(P + "ignore_speed", d.moveIgnoreSpeed.toFloat()).toDouble(),
            walkSpeed = sp.getFloat(P + "walk", d.walkSpeed.toFloat()).toDouble(),
            minSpeedFactor = sp.getFloat(P + "floor", d.minSpeedFactor.toFloat()).toDouble(),
            randomPercent = sp.getFloat(P + "random", d.randomPercent.toFloat()).toDouble(),
        ).sanitized()
        // 旧版衰减/恢复点率不再参与模型，读到新版本时就清掉，避免留下死键。
        sp.edit {
            remove(P + "decay")
            remove(P + "recover")
        }
        return loaded
    }

    /**
     * 删掉**已废弃**的旧键（`rest_sec` = 旧"冷却时间系数"、`rest_factor` = 旧"休息降速系数"）。
     *
     * 只在用户点「重置数据」时调用：平时留着无害（不再读），但一个"重置"要是还留下一堆
     * 看不懂的旧键，下次排查的人会以为它们还在生效 —— 顺手清干净。
     */
    fun clearObsolete(context: Context) {
        context.sharedPrefs.edit {
            remove(P + "rest_sec")
            remove(P + "rest_factor")
            remove(P + "decay")
            remove(P + "recover")
        }
    }

    fun save(context: Context, config: StaminaConfig) {
        val c = config.sanitized()
        context.sharedPrefs.edit {
            putBoolean(P + "enabled", c.enabled)
            putFloat(P + "critical_speed", c.criticalSpeedMps.toFloat())
            putFloat(P + "d_prime", c.dPrimeMeters.toFloat())
            putFloat(P + "recovery_tau", c.recoveryTauSec.toFloat())
            putFloat(P + "rest_at", c.restAtPercent.toFloat())
            putFloat(P + "resume", c.resumeAtPercent.toFloat())
            putFloat(P + "fatigue_sec", c.fatigueSec.toFloat())
            putFloat(P + "transition", c.transitionSec.toFloat())
            putFloat(P + "ignore_window", c.moveIgnoreWindowSec.toFloat())
            putFloat(P + "ignore_speed", c.moveIgnoreSpeed.toFloat())
            putFloat(P + "walk", c.walkSpeed.toFloat())
            putFloat(P + "floor", c.minSpeedFactor.toFloat())
            putFloat(P + "random", c.randomPercent.toFloat())
            // 旧版点率旋钮已从模型移除；即使没有走整组重置，也不能把死键留在盘上。
            remove(P + "decay")
            remove(P + "recover")
        }
    }
}
