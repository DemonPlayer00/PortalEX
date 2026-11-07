package moe.fuqiuluo.xposed.utils

import kotlin.math.min

/**
 * 纯 D′ 余额曲线。
 *
 * 纵轴存的是 **D′ 余额比例（0..1）**，不是速度倍率：纯公式不改变速度。
 */
object StaminaCurve {
    const val DEFAULT_MAX_DISTANCE_M = 10_000.0
    const val DEFAULT_WINDOW_M = 2_000.0
    const val DEFAULT_DT_SEC = 0.25
    const val SAMPLE_DT_SEC = 0.1
    const val MAX_SECONDS = 3.0 * 3600.0

    class Curve(
        val distanceM: FloatArray,
        /** D′ 余额比例：1.0 = 满，0.0 = 耗尽 */
        val multiplier: FloatArray,
        val metrics: Metrics,
        val restCount: Int = 0,
        val restTotalSec: Double = 0.0,
        val elapsedSec: Double = 0.0,
        val staminaEndPercent: Double = 100.0,
    ) {
        val size: Int get() = minOf(distanceM.size, multiplier.size)
    }

    /** 兼容旧页面的指标容器；纯公式不再产生疲劳次数，相关字段恒为“无”。 */
    class Metrics(
        val firstFatigueDistanceM: Double = -1.0,
        val firstFatigueStartSec: Double = -1.0,
        val firstFatigueSec: Double = -1.0,
        val firstFatigueEntryPercent: Double = 0.0,
        val firstFatigueExitPercent: Double = 0.0,
        val firstCycleSec: Double = -1.0,
        val fatigueSpeedMps: Double = 0.0,
        val averageSpeedMps: Double = 0.0,
        val fatigueStartDistancesM: FloatArray = FloatArray(0),
    ) {
        val hasFatigue: Boolean get() = firstFatigueDistanceM >= 0.0
        fun fatigueCountWithin(meters: Double): Int = 0
    }

    fun simulate(
        config: StaminaConfig,
        baseSpeed: Double,
        maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE_M,
        dtSec: Double = DEFAULT_DT_SEC,
    ): Curve {
        val c = config.sanitized()
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0
        val step = dtSec.coerceIn(0.02, 1.0)
        val limit = if (maxDistanceMeters > 0.0) maxDistanceMeters else DEFAULT_MAX_DISTANCE_M
        var stamina = 100.0
        var distance = 0.0
        var elapsed = 0.0
        var exhaustedAt = -1.0
        val ds = ArrayList<Float>(4096)
        val bs = ArrayList<Float>(4096)
        ds.add(0f)
        bs.add(1f)
        while (distance < limit && elapsed < MAX_SECONDS) {
            val progress = if (limit > 0.0) (distance / limit).coerceIn(0.0, 1.0) else 0.0
            val mult = (paceMultiplier(stamina, base, c.criticalSpeedMps) *
                    ordinaryPaceProfile(progress)).coerceIn(0.02, 1.0)
            val pace = base * mult
            stamina += if (pace > c.criticalSpeedMps) {
                -100.0 * (pace - c.criticalSpeedMps) / c.dPrimeMeters * step
            } else {
                (100.0 - stamina) / c.recoveryTauSec * step
            }
            stamina = stamina.coerceIn(0.0, 100.0)
            if (exhaustedAt < 0.0 && stamina <= 0.0) exhaustedAt = distance
            distance += pace * step
            elapsed += step
            ds.add(distance.toFloat())
            bs.add(mult.toFloat())
        }
        return Curve(
            distanceM = ds.toFloatArray(),
            multiplier = bs.toFloatArray(),
            metrics = Metrics(
                firstFatigueDistanceM = exhaustedAt,
                firstFatigueStartSec = if (exhaustedAt >= 0.0) exhaustedAt / base else -1.0,
                averageSpeedMps = if (elapsed > 0) distance / elapsed else 0.0,
            ),
            elapsedSec = elapsed,
            staminaEndPercent = stamina,
        )
    }

    fun sample(
        config: StaminaConfig,
        baseSpeed: Double,
        maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE_M,
        dtSec: Double = SAMPLE_DT_SEC,
        @Suppress("UNUSED_PARAMETER") random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Curve = simulate(config, baseSpeed, maxDistanceMeters, dtSec)
}
