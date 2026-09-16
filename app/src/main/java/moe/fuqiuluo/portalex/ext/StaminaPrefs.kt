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
     * 为什么需要它：这套键在 2026-09-16 换过一次语义 ——
     * `stamina_recover` 从"点/秒"变成"点/分钟"、`stamina_rest_sec` 从"休息秒数"变成
     * "冷却时间系数"。老设备上存的值会被**当成新语义读进来**：实测 `rest_sec=35` 被读成
     * "冷却 35 倍"，恢复慢到几乎看不见（体力只掉不回的假象）。
     * 键名没变，所以只能靠版本号识别"这是老数据"，一次性重置成新默认。
     */
    private const val VERSION_KEY = "stamina_version"
    private const val VERSION = 1

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
        return StaminaConfig(
            enabled = sp.getBoolean(P + "enabled", d.enabled),
            decayPerMinute = sp.getFloat(P + "decay", d.decayPerMinute.toFloat()).toDouble(),
            restAtPercent = sp.getFloat(P + "rest_at", d.restAtPercent.toFloat()).toDouble(),
            restSpeedFactor = sp.getFloat(P + "rest_factor", d.restSpeedFactor.toFloat()).toDouble(),
            restSecondsCoefficient = sp.getFloat(P + "rest_sec", d.restSecondsCoefficient.toFloat()).toDouble(),
            recoverCoefficient = sp.getFloat(P + "recover", d.recoverCoefficient.toFloat()).toDouble(),
            moveIgnoreWindowSec = sp.getFloat(P + "ignore_window", d.moveIgnoreWindowSec.toFloat()).toDouble(),
            moveIgnoreSpeed = sp.getFloat(P + "ignore_speed", d.moveIgnoreSpeed.toFloat()).toDouble(),
            walkSpeed = sp.getFloat(P + "walk", d.walkSpeed.toFloat()).toDouble(),
            minSpeedFactor = sp.getFloat(P + "floor", d.minSpeedFactor.toFloat()).toDouble(),
            randomPercent = sp.getFloat(P + "random", d.randomPercent.toFloat()).toDouble(),
        ).sanitized()
    }

    fun save(context: Context, config: StaminaConfig) {
        val c = config.sanitized()
        context.sharedPrefs.edit {
            putBoolean(P + "enabled", c.enabled)
            putFloat(P + "decay", c.decayPerMinute.toFloat())
            putFloat(P + "rest_at", c.restAtPercent.toFloat())
            putFloat(P + "rest_factor", c.restSpeedFactor.toFloat())
            putFloat(P + "rest_sec", c.restSecondsCoefficient.toFloat())
            putFloat(P + "recover", c.recoverCoefficient.toFloat())
            putFloat(P + "ignore_window", c.moveIgnoreWindowSec.toFloat())
            putFloat(P + "ignore_speed", c.moveIgnoreSpeed.toFloat())
            putFloat(P + "walk", c.walkSpeed.toFloat())
            putFloat(P + "floor", c.minSpeedFactor.toFloat())
            putFloat(P + "random", c.randomPercent.toFloat())
        }
    }
}
