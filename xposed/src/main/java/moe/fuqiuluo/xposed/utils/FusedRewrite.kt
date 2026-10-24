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
    /** 是否保留 fused 原始 extras；false 时清空，避免真实环境字段与虚拟坐标冲突。 */
    var keepExtras: Boolean = true,
)

object FusedRewrite {
    /** 是否启用 fused 专属改写。关闭时完全走原来的通用注入路径。 */
    @Volatile
    var enabled: Boolean = true

    /** 当前 fused 改写真值。可由设置/命令通道整体替换。 */
    @Volatile
    var overrides: FusedOverrides = FusedOverrides()

    private val rewriteCount = AtomicLong()

    /** 本次进程内已改写的 fused 帧数（诊断用）。 */
    val rewritten: Long get() = rewriteCount.get()

    fun isFused(location: Location?): Boolean =
        location?.provider == "fused"

    /**
     * 对已经生成的虚拟 fused Location 应用 fused 专属覆盖。
     *
     * 只应在 [BaseLocationHook.injectLocation] 完成通用字段生成后调用；
     * 传入 [origin] 仅用于判断来源和保留 extras。
     */
    fun apply(location: Location, origin: Location): Location {
        if (!enabled || !isFused(origin)) return location
        val cfg = overrides

        if (cfg.provider.isNotBlank()) location.provider = cfg.provider
        if (cfg.accuracy > 0f) location.accuracy = cfg.accuracy
        if (cfg.altitude.isFinite()) location.altitude = cfg.altitude
        if (cfg.speed.isFinite()) location.speed = cfg.speed.coerceAtLeast(0f)
        if (cfg.bearing.isFinite()) {
            var b = cfg.bearing % 360f
            if (b < 0f) b += 360f
            location.bearing = b
        }

        if (cfg.locationAgeMs > 0L) {
            val ageNanos = cfg.locationAgeMs * 1_000_000L
            location.time = System.currentTimeMillis() - cfg.locationAgeMs
            location.elapsedRealtimeNanos =
                (SystemClock.elapsedRealtimeNanos() - ageNanos).coerceAtLeast(0L)
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
        if (!cfg.keepExtras) {
            location.extras = android.os.Bundle()
        }

        rewriteCount.incrementAndGet()
        return location
    }

    fun resetCounters() {
        rewriteCount.set(0L)
    }
}
