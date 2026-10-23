package moe.fuqiuluo.portalex.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * **IMU 平滑度探针**（Test 页展示）—— 把"是否平滑"变成可读的数。
 *
 * ## 为什么需要它
 *
 * "指南针/角度计不平滑"这种判断，靠肉眼看图表是主观的，也分不清是
 * **注入侧在跳**、还是**目标应用在画**。这里直接真订阅五个量，逐事件量三件事：
 *
 * 1. **逐事件 |Δ|**（每轴）：中位数反映"稳不稳"，最大值反映"有没有尖峰"；
 * 2. **罗盘角抖动**：`atan2` 从磁场方向（或直接用朝向值）算出角度，量它的 |Δ|（度）；
 * 3. **恒等式残差**：真机上 `加速度计 = 重力 + 线性加速度`，注入侧必须保持这条关系 ——
 *    残差大就说明各传感器的抖动是**各自独立**抽的（那会让应用算出物理上不可能的数据）。
 *
 * 判读阈值（参考量取各传感器的典型量级）：|Δ| 中位数 < 0.5% 参考量 ⇒ 平滑；
 * 最大 |Δ| > 5% 参考量 ⇒ 有尖峰。参考量：加速度/重力/线性 9.8、磁场 50、罗盘角 180。
 *
 * 维护约定：诊断探针，不参与产品逻辑。
 */
object ImuProbe {

    private const val KEEP = 64          /* 每个量保留最近多少条 |Δ| 用于算中位数 */

    private class Stat(val label: String, val ref: Double) {
        val prev = DoubleArray(4)
        var hasPrev = false
        val ring = Array(4) { DoubleArray(KEEP) }
        val ringN = IntArray(4)
        var maxDelta = 0.0
        var latest = DoubleArray(4)
        var events = 0L

        fun feed(v: FloatArray) {
            val n = minOf(4, v.size)
            for (i in 0 until n) latest[i] = v[i].toDouble()
            if (hasPrev) {
                for (i in 0 until n) {
                    val d = abs(latest[i] - prev[i])
                    ring[i][ringN[i] % KEEP] = d
                    if (ringN[i] < KEEP) ringN[i]++
                    if (d > maxDelta) maxDelta = d
                }
            }
            for (i in 0 until n) prev[i] = latest[i]
            hasPrev = true
            events++
        }

        fun median(i: Int): Double {
            val n = ringN[i]
            if (n == 0) return 0.0
            val a = ring[i].copyOf(n)
            Arrays.sort(a)
            return a[n / 2]
        }

        fun line(): String {
            val axes = minOf(3, latest.size)
            val vals = (0 until axes).joinToString("/") { "%.3f".format(latest[it]) }
            val med = (0 until axes).maxOf { median(it) }
            val ratio = if (ref > 0) maxDelta / ref else 0.0
            val verdict = when {
                events < 10 -> "样本少"
                med <= ref * 0.005 && maxDelta <= ref * 0.05 -> "平滑 ✓"
                med <= ref * 0.02 -> "可接受"
                else -> "毛糙 ✗"
            }
            return "%-8s %-26s |Δ|中位 %.4f 最大 %.4f（占参考量 %.2f%%）→ %s".format(
                label, vals, med, maxDelta, ratio * 100, verdict
            )
        }
    }

    private val accel = Stat("加速度计", 9.8)
    private val gravity = Stat("重力", 9.8)
    private val linear = Stat("线性加速", 9.8)
    private val mag = Stat("磁场", 50.0)
    private val orient = Stat("朝向", 180.0)

    /** 罗盘角（度）的逐事件 |Δ| */
    private var prevHeading = Double.NaN
    private val headingRing = DoubleArray(KEEP)
    private var headingN = 0
    private var headingMax = 0.0

    /*
     * 恒等式残差：`accel − (gravity + linear)`。
     * ⚠️ 必须**按事件时间戳对齐**再算 —— 三个量是三条独立的流，各自"最近一条"可能差几十毫秒，
     * 而走路时线性加速度在这段时间里能摆 ±1.5 m/s²，那样算出来的"残差"是我们的采样错位，不是注入的不一致。
     * 所以各自留一小段带时间戳的历史，用与 accel 时间戳最接近的那条（±25ms 内）配对。
     */
    private val residual = DoubleArray(3)
    private var residualEvents = 0L
    private var matchedPairs = 0L
    private var unmatched = 0L
    private val nearestGap = DoubleArray(64)     /* accel↔gravity 最近时间差（ms），诊断对齐用 */
    private var nearestN = 0
    private val histG = ArrayDeque<Pair<Long, DoubleArray>>()
    private val histL = ArrayDeque<Pair<Long, DoubleArray>>()
    private const val HIST_KEEP = 48
    private const val MATCH_TOL_NS = 60_000_000L  /* 60ms：各通道的排定时刻本就不同 */

    @Volatile private var started = false
    @Volatile private var unavailable: String? = null
    private var sm: SensorManager? = null

    /** 在历史里找与 [ts] 时间戳最接近的一条（超出容差返回 null） */
    private fun closest(hist: ArrayDeque<Pair<Long, DoubleArray>>, ts: Long): DoubleArray? {
        var best: DoubleArray? = null
        var bestD = Long.MAX_VALUE
        for ((t, v) in hist) {
            val d = kotlin.math.abs(t - ts)
            if (d < bestD) { bestD = d; best = v }
        }
        return if (best != null && bestD <= MATCH_TOL_NS) best else null
    }

    /** 最近时间差（ms），用于诊断"为什么配对不上" */
    private fun nearest(hist: ArrayDeque<Pair<Long, DoubleArray>>, ts: Long): Double? {
        var best = Long.MAX_VALUE
        for ((t, _) in hist) {
            val d = kotlin.math.abs(t - ts)
            if (d < best) best = d
        }
        return if (best == Long.MAX_VALUE) null else best / 1_000_000.0
    }

    private fun noteHeading(deg: Double) {
        if (!prevHeading.isNaN()) {
            var d = abs(deg - prevHeading)
            if (d > 180.0) d = 360.0 - d          /* 跨 0/360 的角度差 */
            headingRing[headingN % KEEP] = d
            if (headingN < KEEP) headingN++
            if (d > headingMax) headingMax = d
        }
        prevHeading = deg
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accel.feed(event.values)
                    val g = closest(histG, event.timestamp)
                    val l = closest(histL, event.timestamp)
                    nearest(histG, event.timestamp)?.let {
                        nearestGap[nearestN % 64] = it
                        if (nearestN < 64) nearestN++
                    }
                    if (g != null && l != null) {
                        for (i in 0 until 3) {
                            residual[i] = event.values[i] - (g[i] + l[i])
                        }
                        residualEvents++
                        matchedPairs++
                    } else {
                        unmatched++
                    }
                }
                Sensor.TYPE_GRAVITY -> {
                    gravity.feed(event.values)
                    histG.addLast(event.timestamp to DoubleArray(3) { event.values[it].toDouble() })
                    while (histG.size > HIST_KEEP) histG.removeFirst()
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    linear.feed(event.values)
                    histL.addLast(event.timestamp to DoubleArray(3) { event.values[it].toDouble() })
                    while (histL.size > HIST_KEEP) histL.removeFirst()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    mag.feed(event.values)
                    /* 与 native 的合成口径一致：mag = (-H·sinθ, H·cosθ, …) ⇒ θ = atan2(-x, y) */
                    val h = Math.toDegrees(atan2(-event.values[0].toDouble(), event.values[1].toDouble()))
                    val hn = if (h < 0) h + 360.0 else h
                    noteHeading(hn)
                }
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_ORIENTATION -> {
                    /*
                     * ⚠️ 这里**只喂自己的 |Δ| 统计，绝不参与"罗盘角"**：
                     * 旋转矢量的 values[0] 是四元数的 **x 分量**（本工程注入恒为 ~0），不是朝向。
                     * 第一版把它也当朝向喂进去 ⇒ 246° 与 ~0° 交替 ⇒ 量出"罗盘角 |Δ| 中位 60°"的
                     * 假异常（真实磁场序列实测只有 ±0.2°）。又一次"观察通道 ≠ 被测系统"。
                     */
                    orient.feed(event.values)
                }
            }
            if (accel.hasPrev && gravity.hasPrev && linear.hasPrev) {
                for (i in 0 until 3) {
                    residual[i] = accel.latest[i] - (gravity.latest[i] + linear.latest[i])
                }
                residualEvents++
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun reset() {
        listOf(accel, gravity, linear, mag, orient).forEach {
            it.hasPrev = false; it.events = 0; it.maxDelta = 0.0
            it.ringN.fill(0)
            it.ring.forEach { r -> r.fill(0.0) }
        }
        prevHeading = Double.NaN
        headingN = 0
        headingMax = 0.0
        residualEvents = 0
        matchedPairs = 0
        unmatched = 0
        histG.clear()
        histL.clear()
    }

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            unavailable = null
            val m = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (m == null) {
                unavailable = "SENSOR_SERVICE 不可用"
                return
            }
            sm = m
            reset()
            var n = 0
            fun reg(type: Int) {
                val s = m.getDefaultSensor(type) ?: return
                m.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI)
                n++
            }
            reg(Sensor.TYPE_ACCELEROMETER)
            reg(Sensor.TYPE_GRAVITY)
            reg(Sensor.TYPE_LINEAR_ACCELERATION)
            reg(Sensor.TYPE_MAGNETIC_FIELD)
            if (m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null) {
                reg(Sensor.TYPE_ROTATION_VECTOR)
            } else {
                reg(Sensor.TYPE_ORIENTATION)
            }
            if (n == 0) {
                unavailable = "框架里没有这些传感器"
                return
            }
            started = true
            Log.i("ImuProbe", "启动：订阅 $n 个量（|Δ| 中位/最大 + 恒等式残差）")
        }
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
            runCatching { sm?.unregisterListener(listener) }
            sm = null
        }
    }

    fun status(): String {
        unavailable?.let { return "IMU 平滑度      $it" }
        if (!started) return "IMU 平滑度      未启动（进入本页即自动启动）"
        val sb = StringBuilder()
        sb.append("参考量          加速度/重力/线性 9.8 m/s²、磁场 50µT、罗盘角 180°").append('\n')
        listOf(accel, gravity, linear, mag, orient).forEach { sb.append(it.line()).append('\n') }
        val hMed = if (headingN == 0) 0.0 else {
            val a = headingRing.copyOf(headingN); Arrays.sort(a); a[headingN / 2]
        }
        sb.append("%-8s %-26s |Δ|中位 %.3f 最大 %.3f（度）→ %s".format(
            "罗盘角", "（**仅**由磁场方向 atan2 反算）", hMed, headingMax,
            when {
                headingN < 10 -> "样本少"
                hMed <= 0.9 && headingMax <= 9.0 -> "平滑 ✓"
                hMed <= 3.0 -> "可接受"
                else -> "毛糙 ✗"
            }
        )).append('\n')
        val rMax = residual.maxOf { abs(it) }
        sb.append("%-8s %.3f / %.3f / %.3f（各轴，最大 %.3f；对齐配对 %d 次/超差 %d）→ %s".format(
            "恒等式", residual[0], residual[1], residual[2], rMax, matchedPairs, unmatched,
            when {
                residualEvents < 10 -> "样本少"
                rMax <= 0.05 -> "accel = gravity + linear 成立 ✓"
                rMax <= 0.5 -> "残差偏大（各传感器抖动未对齐）"
                else -> "残差过大 ⇒ 抖动是各自独立抽的 ✗"
            }
        )).append('\n')
        val gaps = if (nearestN == 0) Double.NaN else {
            val a = nearestGap.copyOf(nearestN); Arrays.sort(a); a[nearestN / 2]
        }
        sb.append("对齐诊断        accel↔gravity 最近时间差中位 %.1fms（容差 %.0fms）".format(
            gaps, MATCH_TOL_NS / 1_000_000.0)).append('\n')
        sb.append("采样            ").append("加速度 ").append(accel.events)
            .append(" 条 / 重力 ").append(gravity.events)
            .append(" / 线性 ").append(linear.events)
            .append(" / 磁场 ").append(mag.events)
            .append(" / 朝向 ").append(orient.events)
        return sb.toString()
    }
}
