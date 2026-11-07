package moe.fuqiuluo.portalex.ext

import android.content.Context
import androidx.core.content.edit
import moe.fuqiuluo.xposed.utils.StaminaConfig

/** 体力参数持久化：只存 CS / D′ / τD′。 */
object StaminaPrefs {
    private const val P = "stamina_"
    private const val VERSION_KEY = "stamina_version"
    /** 2026-11-07：纯 CS/D′ 公式，移除疲劳行为参数。 */
    private const val VERSION = 4

    fun load(context: Context): StaminaConfig {
        val sp = context.sharedPrefs
        if (sp.getInt(VERSION_KEY, 0) < VERSION) {
            val fresh = StaminaConfig()
            save(context, fresh)
            sp.edit { putInt(VERSION_KEY, VERSION) }
            clearObsolete(context)
            return fresh
        }
        val d = StaminaConfig()
        val loaded = StaminaConfig(
            enabled = sp.getBoolean(P + "enabled", d.enabled),
            criticalSpeedMps = sp.getFloat(P + "critical_speed", d.criticalSpeedMps.toFloat()).toDouble(),
            dPrimeMeters = sp.getFloat(P + "d_prime", d.dPrimeMeters.toFloat()).toDouble(),
            recoveryTauSec = sp.getFloat(P + "recovery_tau", d.recoveryTauSec.toFloat()).toDouble(),
        ).sanitized()
        // 旧疲劳/点率键不再参与模型，读取时顺手清理。
        sp.edit {
            for (key in listOf(
                "decay", "recover", "rest_at", "resume", "fatigue_sec",
                "transition", "walk", "floor", "random", "ignore_window",
                "ignore_speed", "rest_sec", "rest_factor",
            )) remove(P + key)
        }
        return loaded
    }

    fun clearObsolete(context: Context) {
        context.sharedPrefs.edit {
            for (key in listOf(
                "decay", "recover", "rest_at", "resume", "fatigue_sec",
                "transition", "walk", "floor", "random", "ignore_window",
                "ignore_speed", "rest_sec", "rest_factor",
            )) remove(P + key)
        }
    }

    fun save(context: Context, config: StaminaConfig) {
        val c = config.sanitized()
        context.sharedPrefs.edit {
            putBoolean(P + "enabled", c.enabled)
            putFloat(P + "critical_speed", c.criticalSpeedMps.toFloat())
            putFloat(P + "d_prime", c.dPrimeMeters.toFloat())
            putFloat(P + "recovery_tau", c.recoveryTauSec.toFloat())
        }
    }
}
