package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **查表路径与现场计算路径必须给出同一个世界**（语义等价回归）。
 *
 * native 展开器（`vw_route.c`）只做"折线 → 按弧长等间距的坐标/朝向表"，它的正确性由
 * host 测试 `test/vw_route_test.c` 对着另写一份的朴素参考验过；这里验的是**接线**：
 * [MotionEngine.setSamplingTable] 注入表之后，`beat` 给出的坐标与朝向必须与
 * 原来的"每拍二分 + 现场算 2m 前视方位角"落在同一条线上。
 *
 * 表步长 0.25m 带来的插值误差 ≤ 步长内的曲率变化 —— 用 1e-6 度（≈0.11m）作判据绰绰有余。
 */
class RouteTableEquivalenceTest {

    /** Kotlin 参考展开器：与 `vw_route.c` 同规则（弧长等间距 + 2m 前视 + 终点退化） */
    private fun expand(lat: DoubleArray, lon: DoubleArray, step: Double): DoubleArray {
        val n = lat.size
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + WorldMath.haversine(lat[i - 1], lon[i - 1], lat[i], lon[i])
        val total = cum[n - 1]
        val out = ArrayList<Double>()
        fun pointAt(d0: Double): Pair<Double, Double> {
            var d = d0
            if (d <= 0.0) return lat[0] to lon[0]
            for (i in 1 until n) {
                if (d <= cum[i] || i == n - 1) {
                    val seg = cum[i] - cum[i - 1]
                    val f = if (seg > 1e-9) ((d - cum[i - 1]) / seg).coerceIn(0.0, 1.0) else 0.0
                    return (lat[i - 1] + (lat[i] - lat[i - 1]) * f) to (lon[i - 1] + (lon[i] - lon[i - 1]) * f)
                }
            }
            return lat[n - 1] to lon[n - 1]
        }
        var d = 0.0
        while (true) {
            val a = pointAt(d)
            val b = pointAt(minOf(d + 2.0, total))
            val brg = if (WorldMath.haversine(a.first, a.second, b.first, b.second) < 1e-6)
                WorldMath.calculateBearing(lat[n - 2], lon[n - 2], lat[n - 1], lon[n - 1])
            else WorldMath.calculateBearing(a.first, a.second, b.first, b.second)
            out.add(a.first); out.add(a.second); out.add(brg)
            if (d >= total) break
            d += step
            if (d > total) d = total
        }
        return out.toDoubleArray()
    }

    /** 带转弯的路线（正北 → 东北 → 正东 → 正南），间距 ~2m */
    private fun route(): Pair<DoubleArray, DoubleArray> {
        val pts = ArrayList<Pair<Double, Double>>()
        var la = 30.0; var lo = 120.0
        repeat(120) { la += 0.0000180; pts.add(la to lo) }
        repeat(120) { la += 0.0000127; lo += 0.0000127; pts.add(la to lo) }
        repeat(120) { lo += 0.0000180; pts.add(la to lo) }
        repeat(120) { la -= 0.0000180; pts.add(la to lo) }
        return DoubleArray(pts.size) { pts[it].first } to DoubleArray(pts.size) { pts[it].second }
    }

    @Test
    fun `注入采样表后 beat 的坐标与朝向与现场计算一致`() {
        val (lat, lon) = route()
        val step = 0.25
        val table = expand(lat, lon, step)

        fun run(useTable: Boolean): List<Triple<Double, Double, Double>> {
            MotionEngine.reset()
            MotionEngine.setRoute(lat, lon)
            if (useTable) MotionEngine.setSamplingTable(table, step) else MotionEngine.setSamplingTable(null, 0.0)
            MotionEngine.setPlaying(true)
            var s = MotionEngine.beat(0.05, 4.0, 1.0, lat[0], lon[0])   // 对齐拍
            val acc = ArrayList<Triple<Double, Double, Double>>()
            repeat(600) {
                s = MotionEngine.beat(0.05, 4.0, 1.0, s.lat, s.lon)
                if (s.bearing != null) acc.add(Triple(s.lat, s.lon, s.bearing!!))
            }
            return acc
        }

        val plain = run(false)
        val tabled = run(true)
        assertTrue("两条路径样本数不同：${plain.size} vs ${tabled.size}", plain.size == tabled.size)
        assertTrue("样本太少（${plain.size}）", plain.size > 400)

        var maxPos = 0.0
        var maxBrg = 0.0
        for (i in plain.indices) {
            val dl = kotlin.math.abs(plain[i].first - tabled[i].first)
            val dg = kotlin.math.abs(plain[i].second - tabled[i].second)
            if (dl > maxPos) maxPos = dl
            if (dg > maxPos) maxPos = dg
            var db = kotlin.math.abs(plain[i].third - tabled[i].third) % 360.0
            if (db > 180.0) db = 360.0 - db
            if (db > maxBrg) maxBrg = db
        }
        println("[查表等价] %d 拍：坐标最大偏差 %.3e 度（≈%.2f mm），朝向最大偏差 %.4f 度"
            .format(plain.size, maxPos, maxPos * 111_320.0 * 1000, maxBrg))
        assertTrue("坐标偏差过大：%.3e 度".format(maxPos), maxPos < 1e-6)
        assertTrue("朝向偏差过大：%.4f 度".format(maxBrg), maxBrg < 0.5)
        assertTrue("表已注入（hasSamplingTable 应为 true）", MotionEngine.hasSamplingTable())
    }
}
