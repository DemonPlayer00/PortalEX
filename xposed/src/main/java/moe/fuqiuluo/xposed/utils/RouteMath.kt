package moe.fuqiuluo.xposed.utils

/**
 * **路线几何（纯函数）**：弧长 → 位置、弧长 → 切线朝向。
 *
 * 与 App 侧同源：折线由 App 展开（平滑段的三次贝塞尔采样 ≈1m/点，走 GeographicLib）
 * 后**整条**传过来，这里只做线性插值 + 前视切线 —— 不重复实现曲线/测地线逻辑，
 * 所以两者看到的几何必然一致（曲线形状只在一个地方定义）。
 *
 * 约定：
 *  · 三个数组等长且 `cum[0] == 0`、`cum` 单调不减（[cumulative] 保证）；
 *  · 越界一律**夹取**而不是抛异常（播放结束、抖动、脏数据都不该让播放器崩）；
 *  · 点数 < 2 视为不可播放，由调用方（[moe.fuqiuluo.xposed.hooks.RouteDriver]）拒绝。
 */
internal object RouteMath {

    /** 相邻点小于该距离（米）视为重复点，求方向时跳过（避免 atan2(0,0) 给出 0 度） */
    private const val DEGENERATE_M = 0.05

    /** 累计距离（米，haversine）。返回与输入等长，首元素恒为 0 */
    fun cumulative(lat: DoubleArray, lon: DoubleArray): DoubleArray {
        val n = minOf(lat.size, lon.size)
        val out = DoubleArray(n)
        var acc = 0.0
        for (i in 1 until n) {
            acc += WorldMath.haversine(lat[i - 1], lon[i - 1], lat[i], lon[i])
            out[i] = acc
        }
        return out
    }

    /**
     * 弧长 [travelled] 处的位置（线性插值）。
     * 空输入返回 (0,0)；越界夹到首/末点。
     */
    fun pointAt(
        lat: DoubleArray,
        lon: DoubleArray,
        cum: DoubleArray,
        travelled: Double
    ): Pair<Double, Double> {
        val n = minOf(lat.size, lon.size, cum.size)
        if (n == 0) return 0.0 to 0.0
        if (n == 1) return lat[0] to lon[0]
        val total = cum[n - 1]
        val t = travelled.coerceIn(0.0, total)
        if (t <= 0.0) return lat[0] to lon[0]
        if (t >= total) return lat[n - 1] to lon[n - 1]

        // 二分找到 cum[i-1] <= t <= cum[i]
        var lo = 0
        var hi = n - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= t) lo = mid else hi = mid
        }
        val segLen = cum[hi] - cum[lo]
        if (segLen <= 0.0) return lat[hi] to lon[hi]
        val f = ((t - cum[lo]) / segLen).coerceIn(0.0, 1.0)
        return (lat[lo] + (lat[hi] - lat[lo]) * f) to (lon[lo] + (lon[hi] - lon[lo]) * f)
    }

    /**
     * 弧长 [travelled] 处的**切线朝向**（度，0=北，顺时针）。
     *
     * 取「当前点 → 前方 [lookaheadM] 米处」的方位，与 App 侧的播放朝向同一算法
     * （App 的 TANGENT_LOOKAHEAD_M = 2.0）：短前视让弯道朝向连续变化，而不是逐点跳变。
     * 前方点退化（重合/已到终点）时回退到最后一个非退化线段的方向。
     */
    fun bearingAt(
        lat: DoubleArray,
        lon: DoubleArray,
        cum: DoubleArray,
        travelled: Double,
        lookaheadM: Double = 2.0
    ): Double {
        val n = minOf(lat.size, lon.size, cum.size)
        if (n < 2) return 0.0
        val total = cum[n - 1]
        val t = travelled.coerceIn(0.0, total)
        val (aLat, aLon) = pointAt(lat, lon, cum, t)
        val (bLat, bLon) = pointAt(lat, lon, cum, (t + lookaheadM).coerceAtMost(total))
        if (WorldMath.haversine(aLat, aLon, bLat, bLon) >= DEGENERATE_M) {
            return WorldMath.calculateBearing(aLat, aLon, bLat, bLon)
        }
        // 兜底：向前找不到方向（终点附近/前视退化）→ 用最后一段有效线段的方向
        for (i in n - 1 downTo 1) {
            val d = WorldMath.haversine(lat[i - 1], lon[i - 1], lat[i], lon[i])
            if (d >= DEGENERATE_M) {
                return WorldMath.calculateBearing(lat[i - 1], lon[i - 1], lat[i], lon[i])
            }
        }
        return 0.0
    }
}
