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
                }
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
