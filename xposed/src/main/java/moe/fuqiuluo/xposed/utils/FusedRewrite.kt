package moe.fuqiuluo.xposed.utils

import android.location.Location
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * 融合定位输出改写器。
 *
 * 融合引擎继续使用真实 GNSS / Wi-Fi / 基站计算，PortalEX 只在结果出口改写最终
 * `Location`。这里集中放“只对 fused 结果生效”的可调项，避免把 fused 专属策略
 * 混进 [BaseLocationHook.injectLocation] 的通用坐标/速度生成逻辑。
 *
 * 约定：
 *  · 0 / NaN = 不覆盖，沿用虚拟世界生成值；
 *  · 默认 provider 保持 `fused`，不把融合结果伪装成 gps/network；
 *  · 不写任何模块私有 extras，不改变真实 fused 的 extras（除非显式关闭）；
 *  · 所有改写只作用在已经由 [BaseLocationHook.injectLocation] 生成的虚拟帧上。
 */
data class FusedOverrides(
    /** 输出 provider；空字符串 = 沿用原 provider。默认保持 fused。 */
    var provider: String = "fused",
    /** 水平精度（米）；0 = 沿用虚拟世界精度。 */
    var accuracy: Float = 0f,
    /** 垂直精度（米）；0 = 沿用虚拟世界生成值。 */
    var verticalAccuracy: Float = 0f,
    /** 速度（m/s）；NaN = 沿用虚拟世界速度。 */
    var speed: Float = Float.NaN,
    /** 速度精度（m/s）；NaN = 沿用虚拟世界生成值。 */
    var speedAccuracy: Float = Float.NaN,
    /** 航向（度）；NaN = 沿用虚拟世界航向。 */
    var bearing: Float = Float.NaN,
    /** 航向精度（度）；NaN = 沿用虚拟世界生成值。 */
    var bearingAccuracy: Float = Float.NaN,
    /** 海拔（米）；NaN = 沿用虚拟世界高度。 */
    var altitude: Double = Double.NaN,
    /**
     * 融合结果年龄（毫秒）。0 = 用注入时刻；
     * >0 = time/elapsedRealtimeNanos 回拨，模拟一份稍旧的融合 fix。
     */
    var locationAgeMs: Long = 0L,
    /**
     * 是否保留 fused 原始 extras；默认 false。
     * false 时只保留系统标准 GNSS 字段（satellites/maxCn0/meanCn0），删除所有真实
     * Wi-Fi/基站/厂商私有键，避免真实环境与虚拟坐标冲突。
     */
    var keepExtras: Boolean = false,
)

object FusedRewrite {
    /** 是否启用 fused 专属改写。关闭时完全走原来的通用注入路径。 */
    @Volatile
    var enabled: Boolean = true

    /** 当前 fused 改写真值。可由设置/命令通道整体替换。 */
    @Volatile
    var overrides: FusedOverrides = FusedOverrides()

    private val rewriteCount = AtomicLong()

    /** 上一帧时间，保证改写后的 time/elapsedRealtimeNanos 单调，不出现时间倒流。 */
    private var lastTimeMs = 0L
    private var lastElapsedNanos = 0L

    /** 本次进程内已改写的 fused 帧数（诊断用）。 */
    val rewritten: Long get() = rewriteCount.get()

    fun isFused(location: Location?): Boolean =
        location?.provider == "fused"

    /**
     * 融合改写只在**位置模拟会话**内生效。
     *
     * 未启动位置模拟时，框架的 fused 结果必须原样透传；否则会出现
     * “没开模拟却改了融合定位”的幽灵行为，也会让关闭模拟后残留一次改写。
     */
    fun isActive(): Boolean = enabled && FakeLoc.enable

    /**
     * 对已经生成的虚拟 fused Location 应用 fused 专属覆盖。
     *
     * 只应在 [BaseLocationHook.injectLocation] 完成通用字段生成后调用；
     * 传入 [origin] 仅用于判断来源和保留 extras。
     */
    fun apply(location: Location, origin: Location): Location {
        if (!isActive() || !isFused(origin)) return location
        val cfg = overrides

        if (cfg.provider.isNotBlank()) location.provider = cfg.provider
        val accuracy = if (cfg.accuracy > 0f) cfg.accuracy else FakeLoc.fusedAccuracy
        if (accuracy > 0f) location.accuracy = accuracy
        if (cfg.altitude.isFinite()) location.altitude = cfg.altitude
        if (cfg.speed.isFinite()) location.speed = cfg.speed.coerceAtLeast(0f)
        if (cfg.bearing.isFinite()) {
            var b = cfg.bearing % 360f
            if (b < 0f) b += 360f
            location.bearing = b
        }

        // 融合结果的“现实时间”：默认不是 0 延迟，而是一次几十到一百多毫秒的融合/投递延迟；
        // time 与 elapsedRealtimeNanos 必须使用同一个年龄，并保证单调不回退。
        val ageMs = when {
            cfg.locationAgeMs > 0L -> cfg.locationAgeMs
            FakeLoc.fusedDelayMs > 0L -> FakeLoc.fusedDelayMs
            else -> realisticAgeMs()
        }
        val nowMs = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val ageNanos = ageMs * 1_000_000L
        synchronized(this) {
            val t = (nowMs - ageMs).coerceAtLeast(lastTimeMs + 1L)
            val e = (nowElapsed - ageNanos).coerceAtLeast(lastElapsedNanos + 1_000_000L)
            location.time = t
            location.elapsedRealtimeNanos = e
            lastTimeMs = t
            lastElapsedNanos = e
        }

        // 精度字段必须与当前 API 支持情况一致；低版本没有这些 setter。
        if (cfg.speedAccuracy.isFinite() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            location.speedAccuracyMetersPerSecond = cfg.speedAccuracy.coerceAtLeast(0f)
        }
        if (cfg.bearingAccuracy.isFinite() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            location.bearingAccuracyDegrees = cfg.bearingAccuracy.coerceIn(0f, 180f)
        }
        if (cfg.verticalAccuracy > 0f && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters = cfg.verticalAccuracy
        }
        // fused 永远不携带真实环境的 extras：默认只保留系统标准卫星三键。
        // 真实 Wi-Fi/基站/厂商私有键全部删除，避免和虚拟坐标形成交叉指纹。
        location.extras = if (cfg.keepExtras) {
            FakeLoc.sanitizeGnssExtras(location.extras)
        } else {
            FakeLoc.sanitizeGnssExtras(android.os.Bundle())
        }

        forceNotMock(location)
        rewriteCount.incrementAndGet()
        return location
    }

    fun resetCounters() {
        rewriteCount.set(0L)
    }

    /**
     * 模拟帧必须是“非 mock”。新建的 [Location] 本就为 false；这里再兜底一次，
     * 防止某些 ROM 的 Location 子类/复用路径带来 true。
     */
    private fun forceNotMock(location: Location) {
        if (!runCatching { location.isMock }.getOrDefault(false)) return
        runCatching {
            val setter = location.javaClass.methods.firstOrNull {
                it.name == "setMock" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == java.lang.Boolean.TYPE
            }
            setter?.isAccessible = true
            setter?.invoke(location, false)
        }
    }

    /** 真实融合/投递延迟的近似范围（毫秒）；每次取样，避免固定 age 成为指纹。 */
    private fun realisticAgeMs(): Long = kotlin.random.Random.nextLong(40L, 181L)
}
