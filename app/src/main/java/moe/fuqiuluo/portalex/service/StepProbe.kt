package moe.fuqiuluo.portalex.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * "普通应用视角"的步数探针 —— 用**真订阅**复现目标应用看到的东西。
 *
 * 为什么需要它：不少计步类应用（用户给的口径：usevector 那套）不是看传感器事件流，而是
 * **固定间隔读一次"系统开机以来的总步数"再求差**：
 *
 * ```
 * 每次轮询:  cadence = (steps_now - steps_last) / 间隔秒 × 60
 * ```
 *
 * 这种算法对"计数器是否连续、一次轮询里涨了几步"极其敏感。本探针就用同样的方式订阅
 * [Sensor.TYPE_STEP_COUNTER]（顺带 [Sensor.TYPE_STEP_DETECTOR]），按固定间隔采样并打印：
 * 本轮涨了几步、折合多少步/分、事件到达率与事件间隔 —— 这样 Test 页上的数就能和目标
 * 应用一次"+N 步"直接对起来，而不是拿两种不同的量互相猜。
 *
 * 注意：**必须在目标应用没有被本模块应用侧 hook 插手的进程里测**才有意义。PortalEX 自身
 * 一般不在 LSPosed 作用域内，所以这里看到的是**纯框架投递**的结果；若把 PortalEX 也加进
 * 作用域，这一栏就会被应用侧 hook 改写（那时它反映的是 hook，而不是框架）。
 */
object StepProbe {

    /** 轮询间隔：与普通应用常见的"间歇读总步数"一致 */
    private const val POLL_INTERVAL_MS = 5_000L

    @Volatile private var started = false
    @Volatile private var unavailable: String? = null
    @Volatile private var poller: Thread? = null
    private var sensorManager: SensorManager? = null

    private var counterSensor: Sensor? = null
    private var detectorSensor: Sensor? = null

    // ---- 计数器 ----
    @Volatile private var counterFirst = -1L
    @Volatile private var counterLast = -1L
    @Volatile private var counterEvents = 0L
    @Volatile private var counterLastArrivalMs = 0L
    @Volatile private var counterIntervalEmaMs = 0.0
    @Volatile private var counterHandle = -1

    // ---- 检测器 ----
    @Volatile private var detectorEvents = 0L
    @Volatile private var detectorHandle = -1

    // ---- 回调视角（onSensorChanged 逐事件）----
    /* 相邻两次**回调**之间计数器值的差分布：Δ=1 才是"一步一次回调"。
     * Δ=0 → 同一个值被投递了两次（双份投递的签名）；Δ=2 → 一次回调跨了两步。 */
    private val histDelta = IntArray(6)          // 索引 0..4 = Δ0..Δ4；索引 5 = Δ≥5
    private val recentCb = ArrayDeque<LongArray>() // 每项 [Δ值, 间隔ms]，最多保留 8 条
    /* 原始值观测：只记"客户端真收到的值"，用来区分"探针记账错"与"框架投递/缓存问题" */
    private val rawValues = ArrayDeque<Long>()     // 最近 10 个原始值
    @Volatile private var rawMin = Long.MAX_VALUE
    @Volatile private var rawMax = Long.MIN_VALUE
    @Volatile private var rawMinAtMs = 0L
    @Volatile private var rawMaxAtMs = 0L
    @Volatile private var prevCbValue = -1L
    @Volatile private var prevCbAtMs = 0L
    @Volatile private var cbCount = 0L
    @Volatile private var cbCadence = -1
    @Volatile private var detectorEmaMs = 0.0
    @Volatile private var detectorLastArrivalMs = 0L

    // ---- 轮询结果（普通应用真正用来算步频的量） ----
    @Volatile private var lastPollDelta = -1L
    @Volatile private var lastPollSpanMs = 0L
    @Volatile private var lastPollCadence = -1
    @Volatile private var lastPollAtMs = 0L
    @Volatile private var baselineAtStart = -1L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val v = event.values[0].toLong()
                    if (counterFirst < 0) counterFirst = v
                    counterLast = v
                    counterHandle = event.sensor.handleCompat()
                    counterEvents++
                    val now = SystemClock.elapsedRealtime()
                    /* 回调视角：这一条回调相对上一条回调，值涨了几步、隔了多久 */
                    // 原始值观测（这一段不改动任何逻辑，只记录）
                    if (v < rawMin) { rawMin = v; rawMinAtMs = now }
                    if (v > rawMax) { rawMax = v; rawMaxAtMs = now }
                    synchronized(rawValues) {
                        rawValues.addLast(v)
                        while (rawValues.size > 10) rawValues.removeFirst()
                    }
                    if (prevCbValue >= 0) {
                        val delta = v - prevCbValue
                        val idx = when {
                            delta <= 0L -> 0
                            delta >= 5L -> 5
                            else -> delta.toInt()
                        }
                        synchronized(histDelta) { histDelta[idx]++ }
                        val span = (now - prevCbAtMs).coerceAtLeast(0L)
                        synchronized(recentCb) {
                            recentCb.addLast(longArrayOf(delta, span))
                            while (recentCb.size > 8) recentCb.removeFirst()
                        }
                        if (span > 0) {
                            cbCadence = (60_000.0 / span).toInt()
                        }
                    }
                    prevCbValue = v
                    prevCbAtMs = now
                    cbCount++
                    if (counterLastArrivalMs != 0L) {
                        val dt = (now - counterLastArrivalMs).toDouble()
                        counterIntervalEmaMs =
                            if (counterIntervalEmaMs == 0.0) dt else counterIntervalEmaMs * 0.8 + dt * 0.2
                    }
                    counterLastArrivalMs = now
                }

                Sensor.TYPE_STEP_DETECTOR -> {
                    detectorEvents++
                    detectorHandle = event.sensor.handleCompat()
                    val now = SystemClock.elapsedRealtime()
                    if (detectorLastArrivalMs != 0L) {
                        val dt = (now - detectorLastArrivalMs).toDouble()
                        detectorEmaMs = if (detectorEmaMs == 0.0) dt else detectorEmaMs * 0.8 + dt * 0.2
                    }
                    detectorLastArrivalMs = now
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 对照 listener：只打日志（前若干条），用于判定"探针记错"还是"框架投递"。 */
    private var logCount = 0
    private val logListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val n = ++logCount
            if (n <= 12 || n % 20 == 0) {
                android.util.Log.i(
                    "StepProbe",
                    "cb#$n type=${event.sensor.type} handle=${event.sensor.handleCompat()} " +
                            "values=[${event.values.joinToString(",")}]"
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 幂等启动；失败原因写进 [unavailable]，由 Test 页原样展示。 */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                unavailable = "缺少 ACTIVITY_RECOGNITION 权限（Android 10+ 读步数传感器必需）"
                return
            }
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sm == null) {
                unavailable = "SENSOR_SERVICE 不可用"
                return
            }
            // 绑定核查：Java 侧到底把哪些"步数相关"条目摆在了表里、getDefaultSensor 选中的是哪个
            runCatching {
                val all = sm.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
                val dflt = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                val dfltH = dflt?.let { runCatching { it.javaClass.getMethod("getHandle").invoke(it) as Int }.getOrDefault(-1) } ?: -1
                android.util.Log.i("StepProbe", "default(TYPE_STEP_COUNTER) -> " +
                        "handle=0x${dfltH.toString(16)} name=${dflt?.name} type=${dflt?.type}")
                for (s2 in all) {
                    val n = (s2.name ?: "").lowercase()
                    val interesting = s2.type == 18 || s2.type == 19 ||
                            s2.type >= 0x10000 && (n.contains("pedometer") || n.contains("step") ||
                                    n.contains("activity") || n.contains("motion"))
                    if (!interesting) continue
                    val h = runCatching { s2.javaClass.getMethod("getHandle").invoke(s2) as Int }.getOrDefault(-1)
                    android.util.Log.i("StepProbe", "sensor type=${s2.type} handle=0x${h.toString(16)} " +
                            "name='${s2.name}' vendor='${s2.vendor}' flags=${runCatching { s2.javaClass.getDeclaredField("mFlags").also { it.isAccessible = true }.getInt(s2) }.getOrDefault(-1)}")
                }
            }.onFailure { android.util.Log.w("StepProbe", "binding dump failed", it) }
            val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (counter == null) {
                // 与目标应用同样的退化路径：没有步数传感器就只能回去算加速度
                unavailable = "框架里没有 TYPE_STEP_COUNTER（应用会退化为加速度推算）"
                return
            }
            sensorManager = sm
            counterSensor = counter
            detectorSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            reset()
            // 普通应用一般用 SENSOR_DELAY_NORMAL 或 UI；这里取 UI（≈16Hz）以便观察到达率
            sm.registerListener(listener, counter, SensorManager.SENSOR_DELAY_UI)
            /* 对照用：**独立 listener 对象**订阅同一个传感器。
             * 若它看到的值在涨而主 listener 恒定 ⇒ 主 listener/共享队列那边有问题；
             * 若两者都恒定 ⇒ 问题在框架投递，不在探针。 */
            sm.registerListener(logListener, counter, SensorManager.SENSOR_DELAY_UI)
            detectorSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
            started = true
            unavailable = null
            poller = Thread({ pollLoop() }, "PortalStepProbe").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
            runCatching { sensorManager?.unregisterListener(listener) }
            runCatching { sensorManager?.unregisterListener(logListener) }
            poller?.interrupt()
            poller = null
        }
    }

    private fun reset() {
        counterFirst = -1L
        counterLast = -1L
        counterEvents = 0L
        counterLastArrivalMs = 0L
        counterIntervalEmaMs = 0.0
        detectorEvents = 0L
        detectorEmaMs = 0.0
        detectorLastArrivalMs = 0L
        prevCbValue = -1L
        prevCbAtMs = 0L
        cbCount = 0L
        cbCadence = -1
        synchronized(histDelta) { histDelta.fill(0) }
        synchronized(recentCb) { recentCb.clear() }
        synchronized(rawValues) { rawValues.clear() }
        rawMin = Long.MAX_VALUE
        rawMax = Long.MIN_VALUE
        rawMinAtMs = 0L
        rawMaxAtMs = 0L
        lastPollDelta = -1L
        lastPollSpanMs = 0L
        lastPollCadence = -1
        lastPollAtMs = 0L
        baselineAtStart = -1L
        startedAtMs = SystemClock.elapsedRealtime()
    }

    @Volatile private var startedAtMs = SystemClock.elapsedRealtime()

    /** 固定间隔采样：模拟"普通应用每隔 N 秒读一次总步数" */
    private fun pollLoop() {
        var lastValue = -1L
        var lastAt = 0L
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val cur = counterLast
            if (cur < 0) continue
            val now = SystemClock.elapsedRealtime()
            if (baselineAtStart < 0) baselineAtStart = cur
            if (lastValue >= 0 && now > lastAt) {
                val delta = cur - lastValue
                val spanMs = now - lastAt
                lastPollDelta = delta
                lastPollSpanMs = spanMs
                lastPollCadence = if (spanMs > 0) (delta * 60_000.0 / spanMs).toInt() else -1
                lastPollAtMs = now
            }
            lastValue = cur
            lastAt = now
        }
    }

    /** `Sensor.getHandle()` 是隐藏 API；普通应用拿不到，这里只用于对照，拿不到就算了。 */
    private fun Sensor.handleCompat(): Int =
        runCatching { javaClass.getMethod("getHandle").invoke(this) as Int }.getOrDefault(-1)

    /** 多行状态（Test 页原样展示） */
    fun status(): String {
        val err = unavailable
        if (err != null) return "不可用          $err"
        if (!started) return "未启动          进入本页即自动启动"
        val elapsedSec = (SystemClock.elapsedRealtime() - startedAtMs) / 1000.0
        val rate = if (elapsedSec > 1) counterEvents / elapsedSec * 60.0 else 0.0
        val sb = StringBuilder()
        sb.append("计数器 handle   ").append(if (counterHandle >= 0) "0x${counterHandle.toString(16)}" else "?").append('\n')
        sb.append("检测器 handle   ").append(if (detectorHandle >= 0) "0x${detectorHandle.toString(16)}" else "无").append('\n')
        sb.append("计数器值        ").append(counterFirst).append(" → ").append(counterLast)
            .append("（本次订阅涨 ").append(if (counterFirst >= 0) counterLast - counterFirst else 0).append(" 步）").append('\n')
        sb.append("累计事件        计数器 ").append(counterEvents).append(" 条 / 检测器 ").append(detectorEvents)
            .append(" 条（计数器到达率 ").append("%.1f".format(rate)).append("/分）").append('\n')
        sb.append("事件间隔        最近 ").append(
            if (counterLastArrivalMs == 0L) "?" else (SystemClock.elapsedRealtime() - counterLastArrivalMs).toString() + "ms 前"
        ).append(" / 均值 ").append("%.0f".format(counterIntervalEmaMs)).append("ms").append('\n')
        // ---- 回调视角（逐事件）----
        val hist = synchronized(histDelta) { histDelta.copyOf() }
        val recent = synchronized(recentCb) { recentCb.toList() }
        sb.append("── 回调视角（onSensorChanged 逐事件）──").append('\n')
        sb.append("回调次数        ").append(cbCount).append(" 次")
        if (cbCadence > 0) sb.append("（最近一次间隔折合 ").append(cbCadence).append(" 步/分）")
        sb.append('\n')
        sb.append("Δ值分布        Δ=0:").append(hist[0]).append("  Δ=1:").append(hist[1])
            .append("  Δ=2:").append(hist[2]).append("  Δ=3:").append(hist[3])
            .append("  Δ=4:").append(hist[4]).append("  Δ≥5:").append(hist[5]).append('\n')
        val raws = synchronized(rawValues) { rawValues.toList() }
        sb.append("原始值(最近)    ").append(if (raws.isEmpty()) "（无）" else raws.joinToString(" ")).append('\n')
        sb.append("原始值 min/max  ").append(if (rawMax == Long.MIN_VALUE) "（无）" else "$rawMin / $rawMax").append('\n')
        sb.append("最近回调        ")
        if (recent.isEmpty()) {
            sb.append("（还没有第二条回调）")
        } else {
            sb.append(recent.joinToString("  ") { "+${it[0]}步/${it[1]}ms" })
        }
        sb.append('\n')
        sb.append("检测器回调      ").append(detectorEvents).append(" 次")
        if (detectorEmaMs > 0) sb.append("（间隔均值 ").append("%.0f".format(detectorEmaMs)).append("ms → ")
            .append((60_000.0 / detectorEmaMs).toInt()).append(" 步/分）")
        sb.append('\n')
        if (lastPollDelta >= 0) {
            sb.append("最近一次轮询    +").append(lastPollDelta).append(" 步 / ")
                .append("%.1f".format(lastPollSpanMs / 1000.0)).append("s → 折合 ")
                .append(lastPollCadence).append(" 步/分")
        } else {
            sb.append("最近一次轮询    等第一次间隔（").append(POLL_INTERVAL_MS / 1000).append("s）")
        }
        return sb.toString()
    }
}
