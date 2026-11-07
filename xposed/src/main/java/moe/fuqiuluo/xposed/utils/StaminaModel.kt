package moe.fuqiuluo.xposed.utils

/**
 * 体力 / D′ 余额配置。
 *
 * 只保留 CS 公式本身的三个参数；疲劳状态、走路速度、疲劳倒计时等行为参数已经移除。
 *
 * ```
 * v > CS:  dS/dt = -100 × (v - CS) / D′
 * v ≤ CS:  dS/dt = (100 - S) / τD′
 * ```
 *
 * `S` 是归一化的 D′ 余额（100 = 满余额，0 = 耗尽）。
 */
data class StaminaConfig(
    var enabled: Boolean = false,
    /** 临界速度 CS（m/s） */
    var criticalSpeedMps: Double = 2.90,
    /** D′ 距离能力（米） */
    var dPrimeMeters: Double = 150.0,
    /** D′ 恢复时间常数 τD′（秒） */
    var recoveryTauSec: Double = 376.0,
) {
    fun toWire(): FloatArray = floatArrayOf(
        if (enabled) 1f else 0f,
        criticalSpeedMps.toFloat(),
        dPrimeMeters.toFloat(),
        recoveryTauSec.toFloat(),
    )

    companion object {
        const val WIRE_SIZE = 4

        fun fromWire(w: FloatArray?): StaminaConfig? {
            if (w == null || w.size != WIRE_SIZE) return null
            return StaminaConfig(
                enabled = w[0] != 0f,
                criticalSpeedMps = w[1].toDouble(),
                dPrimeMeters = w[2].toDouble(),
                recoveryTauSec = w[3].toDouble(),
            ).sanitized()
        }
    }

    fun sanitized(): StaminaConfig = copy(
        criticalSpeedMps = criticalSpeedMps.coerceIn(0.5, 10.0),
        dPrimeMeters = dPrimeMeters.coerceIn(10.0, 5000.0),
        recoveryTauSec = recoveryTauSec.coerceIn(10.0, 3600.0),
    )
}

/**
 * 纯 D′ 余额模型。
 *
 * **不改变速度**：速度是推进引擎的输入；本模型只记录 D′ 消耗/恢复。
 * 因此没有疲劳状态、没有走跑交替、没有速度倍率。
 */
class StaminaModel {
    private val lock = Any()
    private var staminaPercent: Double = 100.0
    private var lastApparent = 0.0

    data class Snapshot(
        val staminaPercent: Double,
        /** 兼容旧协议：纯公式没有疲劳状态，恒 false */
        val resting: Boolean = false,
        val fatigueRemainingSec: Double = 0.0,
        val blend: Double = 0.0,
        /** 纯公式不调制速度，恒 1.0 */
        val speedScale: Double = 1.0,
        val restCount: Int = 0,
        val restTotalSec: Double = 0.0,
        val apparentSpeed: Double = 0.0,
        val ignoredTicks: Long = 0L,
    )

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(staminaPercent = staminaPercent, apparentSpeed = lastApparent)
    }

    fun reset() = synchronized(lock) {
        staminaPercent = 100.0
        lastApparent = 0.0
    }

    /**
     * 推进一拍。
     *
     * @param baseSpeed 本拍名义速度（m/s），用于判断是否高于 CS
     * @param movedMeters 本拍实际位移（米）；0 = 空闲，只恢复
     */
    fun tick(
        config: StaminaConfig,
        dtSec: Double,
        baseSpeed: Double,
        movedMeters: Double,
        @Suppress("UNUSED_PARAMETER") random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Double {
        if (dtSec <= 0.0) return synchronized(lock) { staminaPercent }
        val c = config.sanitized()
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0
        val moved = if (movedMeters.isNaN() || movedMeters < 0.0) 0.0 else movedMeters
        val apparent = if (dtSec > 0.0) moved / dtSec else 0.0

        synchronized(lock) {
            lastApparent = apparent
            if (moved > 0.0 && base > c.criticalSpeedMps) {
                staminaPercent -= 100.0 * (base - c.criticalSpeedMps) / c.dPrimeMeters * dtSec
            } else {
                // 低于 CS 或空闲：D′ 指数恢复
                staminaPercent += (100.0 - staminaPercent) / c.recoveryTauSec * dtSec
            }
            staminaPercent = staminaPercent.coerceIn(0.0, 100.0)
            return staminaPercent
        }
    }
}

/** 兼容旧代码的常量；纯公式不再使用速度调制。 */
const val NO_STAMINA_MODULATION: Double = 1.0
