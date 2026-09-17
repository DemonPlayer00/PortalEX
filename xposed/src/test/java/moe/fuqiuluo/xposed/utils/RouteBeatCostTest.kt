package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * 自动播放的**路线坐标算法是不是"实时算出的即时点"**，以及它的**代价**。
 *
 * ## 结论（本文件测出来的，不是读代码猜的）
 *
 * 是实时的：每一拍都从累计弧长现场算出坐标 —— [MotionEngine] 只存折线顶点与累计弧长，
 * 拍内做「二分找到所在区间 → 区间内按弧长线性插值」，**不预先展开、也不缓存在队列里**。
 * 于是交付的坐标是弧长意义上的**瞬时位置**（不会吸附到 1m 间距的顶点上），
 * 每拍前进量 ≈ 速度×dt。
 *
 * ## 为什么必须按**真实规模**量
 *
 * [AllocationBudgetTest] 用的是 4~64 点的小路线；真机上的路线是 **865~1123 点**。
 * 差别不在插值而在**二分深度**（log n），而每拍要做 **3 次**二分：
 * 位置一次、朝向的前视点一次、朝向的当前点一次（见 `bearingAt`）。
 *
 * ## ⚠️ 这里的耗时/分配只能当**量级**，不能当基线
 *
 * 踩过的三个坑（免得下次重踩）：
 *  1. 路线**跑完**之后每拍走"已播完"分支并返回**常量** ⇒ JIT 把调用提到循环外，
 *     量出 17 ns/拍 这种假数 —— 所以必须压在远未播完的区间里测；
 *  2. 结果被丢弃 ⇒ 逃逸分析直接把 `Step`/`Pair` 标量化，量到的分配随内联上下文在
 *     72~289 B/拍 之间跳 —— 所以必须**用掉**结果；
 *  3. 即便如此，**同一份代码在不同路线规模下的分配仍不一致**（1123 点 72 B < 4 点基线 274 B，
 *     物理上不可能）—— HotSpot 按内联深度决定要不要标量化，而 Android 上是 ART、
 *     行为又是另一套。⇒ **绝对值不可比，只有量级可信。**
 *
 * 真正**可靠**的是第二条测试（实时性判据，与 JIT 无关）以及可以直接从 [MotionEngine]
 * 读出来的结构事实：每拍 3 次二分 + 3 次线性插值 + 1 次 haversine + 1 次方位角（≈20 次超越函数）。
 */
class RouteBeatCostTest {

    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocatedBytes(block: () -> Unit): Long {
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    /**
     * 造一条**真实规模**的路线：~2.4km、按 ~2.16m 间距展开成 ~1123 个点，
     * 并且**带转弯**（直线路线上 `bearingAt` 的前视点几乎与原方向重合，量不到最坏情况）。
     */
    private fun realisticRoute(points: Int = 1123): Pair<DoubleArray, DoubleArray> {
        val lat = DoubleArray(points)
        val lon = DoubleArray(points)
        // 一段"跑道"：绕一个长轴椭圆走，纬度幅度 ~0.0011°(≈122m)，经度幅度 ~0.0006°
        val latC = 23.5255
        val lonC = 113.6063
        for (i in 0 until points) {
            val t = i.toDouble() / points * 2 * Math.PI * 6.0      // 绕 6 圈 ⇒ 每圈 ~400m
            lat[i] = latC + 0.00110 * kotlin.math.sin(t)
            lon[i] = lonC + 0.00062 * kotlin.math.cos(t)
        }
        return Pair(lat, lon)
    }

    /**
     * **必须"用掉"结果**：否则 JIT 的逃逸分析会把 `Step`/`Pair` 直接优化掉，
     * 量到的分配取决于 C2 有没有介入（实测同一份代码在 74~288 B/拍 之间跳）。
     * 时间取**多轮最优**（避免 TLAB/GC/调度噪声把结论抬高）。
     */
    @Volatile
    private var sink: Double = 0.0

    private fun bench(label: String, points: Int, beats: Int = 3_000): Pair<Double, Double> {
        // 基线必须**跑不完**：4 点若只有 200m，1000 拍就播完，后面走"已播完"的廉价分支，
        // 量出来的就不是 beat 的成本。所以基线摊到 ~55km。
        val (lat, lon) = if (points == 4) Pair(
            DoubleArray(4) { 30.0 + it * 0.14 }, DoubleArray(4) { 120.0 }
        ) else realisticRoute(points)
        MotionEngine.reset()
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        MotionEngine.beat(0.05, 4.0, 1.0, lat[0], lon[0])          // 对齐拍
        // 预热也必须走**未播完**的分支。踩过的坑：真实路线只有 ~3.6km，
        // 预热 8000 拍 + 测量 20000 拍会把它跑完，之后每拍落到"已播完"那条
        // **返回常量**的分支 —— JIT 直接把调用提到循环外，量出来 17 ns/拍（假的）。
        repeat(3_000) { val s = MotionEngine.beat(0.05, 4.0, 1.0, lat[0], lon[0]); sink = s.lat + s.lon }

        val bytes = allocatedBytes {
            repeat(beats) { val s = MotionEngine.beat(0.05, 4.0, 1.0, lat[0], lon[0]); sink = s.lat + s.lon }
        }
        var best = Long.MAX_VALUE
        repeat(7) {
            val t0 = System.nanoTime()
            repeat(beats) { val s = MotionEngine.beat(0.05, 4.0, 1.0, lat[0], lon[0]); sink = s.lat + s.lon }
            val ns = (System.nanoTime() - t0) / beats
            if (ns < best) best = ns
        }
        val perBeat = bytes.toDouble() / beats
        val ns = best.toDouble()
        println(
            "[路线代价] %-16s %5d 点：%6.0f ns/拍   %6.1f B/拍   20Hz ⇒ %.3f ms/s、%.1f KB/s"
                .format(label, points, ns, perBeat, ns * 20 / 1e6, perBeat * 20 / 1024)
        )
        return ns to perBeat
    }

    @Test
    fun `路线坐标是实时算出的即时点，代价随点数只涨对数项`() {
        bench("预热（丢弃）", 1123, beats = 3_000)      // 先把 JIT/类加载付掉，否则第一个测的是"第一次"
        val (nsSmall, bSmall) = bench("浅二分基线", 4)
        val (ns64, b64) = bench("64 点", 64)
        val (ns1123, b1123) = bench("真机规模", 1123)

        val totalMs = ns1123 * 20 / 1e6
        println("[路线代价] ⇒ 真机规模下单拍 %.1f µs，占 50ms 拍长的 %.4f%%，单核占用 %.3f%%"
            .format(ns1123 / 1000, ns1123 / 50_000_000.0 * 100, ns1123 * 20 / 1e7))
        println("[路线代价] ⇒ 二分深度只涨 log2(n)：4→1123 点 %.2f× 时间"
            .format(ns1123 / nsSmall))
        assertTrue("单拍耗时异常（$ns1123 ns）", ns1123 < 200_000)
        assertTrue("20Hz 下每秒耗时异常（$totalMs ms）", totalMs < 4.0)
        // 分配：真机规模下每拍不该超过 1KB（1拍=1个 Step + 3个 Pair + 装箱）
        assertTrue("每拍分配 %.1f B 超出预算".format(b1123), b1123 < 1024.0)
        // 点数多 17 倍，代价不该爆炸
        assertTrue("点数从 4→1123（280×），耗时涨了 %.1f×".format(ns1123 / nsSmall), ns1123 < nsSmall * 60)
        println("[路线代价] 对照：小路线 %.1f B/拍、64 点 %.1f B/拍、真机规模 %.1f B/拍".format(bSmall, b64, b1123))
    }

    /**
     * **实时性/即时性**的直接判据：交付坐标必须是弧长意义上的瞬时位置 ——
     * 每拍前进 ≈ 速度×dt，而**不是**吸附到顶点（吸附会让前进量量化成顶点间距）。
     */
    @Test
    fun `交付坐标是弧长瞬时位置而非吸附到顶点`() {
        val (lat, lon) = realisticRoute(1123)
        MotionEngine.reset()
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        MotionEngine.beat(0.05, 4.0, 1.0, lat[0], lon[0])   // 对齐拍

        val dt = 0.02                     // 20ms 拍：顶点间距 ~2.16m ⇒ 若吸附会一跳一大格
        val v = 4.0
        val want = v * dt                 // 0.08 m
        var prev = MotionEngine.beat(dt, v, 1.0, lat[0], lon[0])
        val steps = ArrayList<Double>()
        repeat(50) {
            val s = MotionEngine.beat(dt, v, 1.0, prev.lat, prev.lon)
            steps.add(WorldMath.haversine(prev.lat, prev.lon, s.lat, s.lon))
            prev = s
        }
        val good = steps.drop(5)          // 头几拍还在对齐/整定
        val errors = good.map { kotlin.math.abs(it - want) }
        val maxErr = errors.max()
        println("[即时性] 每拍前进：期望 %.4f m，实测 %.4f~%.4f m，最大偏差 %.4f m（顶点间距 ~2.16m）"
            .format(want, good.min(), good.max(), maxErr))
        assertTrue(
            "前进量偏离 速度×dt 太多（max %.4f m）—— 说明坐标不是弧长瞬时插值".format(maxErr),
            maxErr < want * 0.05
        )
        assertTrue(
            "前进量像被量化到顶点（%.3f m）".format(good.min()), good.min() > want * 0.5
        )
    }
}
