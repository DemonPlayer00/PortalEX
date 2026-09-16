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

    fun load(context: Context): StaminaConfig {
        val sp = context.sharedPrefs
        val d = StaminaConfig()
        return StaminaConfig(
            enabled = sp.getBoolean(P + "enabled", d.enabled),
            decayPerMinute = sp.getFloat(P + "decay", d.decayPerMinute.toFloat()).toDouble(),
            restAtPercent = sp.getFloat(P + "rest_at", d.restAtPercent.toFloat()).toDouble(),
            restSeconds = sp.getFloat(P + "rest_sec", d.restSeconds.toFloat()).toDouble(),
            recoverPerSecond = sp.getFloat(P + "recover", d.recoverPerSecond.toFloat()).toDouble(),
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
            putFloat(P + "rest_sec", c.restSeconds.toFloat())
            putFloat(P + "recover", c.recoverPerSecond.toFloat())
            putFloat(P + "walk", c.walkSpeed.toFloat())
            putFloat(P + "floor", c.minSpeedFactor.toFloat())
            putFloat(P + "random", c.randomPercent.toFloat())
        }
    }
}
