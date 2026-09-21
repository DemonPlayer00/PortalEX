package moe.fuqiuluo.portalex.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * **期望「1 秒更新一次」的监听器测试**（Test 页展示）。
 *
 * ## 它想回答什么问题
 *
 * 用户报的现象：**大约每 20 秒出现一次步数尖峰**，怀疑"同一时刻被下发了两条步频事件"。
 * 判这个问题需要三样东西，而它们都不能靠"每 2.5 秒 dump 一次页面"拿到：
 * ① 每条回调的**事件时间戳**（HAL 侧）与**送达时刻**（客户端侧），用来区分
 *    "同一个事件被投递两次" 与 "两个事件被排到同一时刻"；
 * ② 每条回调相对上一条的 **Δ计数值**与 **Δt**；
 * ③ **异常发生的时刻表** —— 有了它，"每约 20 秒一次"这种周期性可以用一次读数离线算出来。
 *
 * 所以本探针按 1 秒的期望间隔订阅（`samplingPeriodUs = 1_000_000`），
 * 对每条回调做四项判读，并把每次异常**连同时刻**记进环形缓冲 + logcat（tag `StepTick`）：
 *
 * | 判读 | 条件 | 含义 |
 * | --- | --- | --- |
 * | `同刻` | HAL 时间戳与上一条**完全相同** | 同一事件被投递两次（真正的"同时"） |
 * | `同值` | 计数值未变 | 重复投递 / 框架缓存重放 |
 * | `跳步` | Δ计数值 ≥ 2 | 一步的窗口里跨了多步（步数尖峰的直接签名） |
 * | `空窗` | Δ送达时刻 > 2× 期望（>2s） | 事件被攒住后成批放出 |
 *
 * 它与 [StepProbe] 的分工：那个是"普通应用视角"（SENSOR_DELAY_UI、5s 轮询总步数），
 * 这个是**期望 1 秒间隔的逐事件监听器**，专门给"同时刻/周期性尖峰"定责用。
 *
 * 维护约定：本文件是**诊断探针**，不参与任何产品逻辑；改它不影响注入行为。
 */
object StepTickProbe {

    /** 期望的更新间隔：1 秒（用户口径）。判读里的"空窗"阈值 = 2× 该值。 */
    private const val EXPECT_INTERVAL_US = 1_000_000
    private const val EXPECT_INTERVAL_MS = 1000.0

    /** 环形缓冲保留多少条异常 */
    private const val ANOMALY_KEEP = 12

    private var sensorManager: SensorManager? = null
    private var counter: Sensor? = null
    private var detector: Sensor? = null

    @Volatile private var started = false
    @Volatile private var unavailable: String? = null
    @Volatile private var startedAtMs = 0L

    // ---- 逐事件统计 ----
    private var prevHalTs = 0L
    private var prevNowMs = 0L
    private var prevValue = -1L

    @Volatile private var callbacks = 0L
    @Volatile private var sameTs = 0L          // 同刻（HAL 时间戳相同）
    @Volatile private var sameValue = 0L       // 同值（计数未变）
    @Volatile private var multiStep = 0L       // 跳步（Δ ≥ 2）
    @Volatile private var tsNearSimultaneous = 0L // ★ 真异常：两条事件 HAL 时间戳差 ≤5ms
    @Volatile private var batchedTogether = 0L     // 正常：同批投递（送达同时、时间戳不同）
    @Volatile private var gapOver2s = 0L       // 空窗
    @Volatile private var detectorCallbacks = 0L

    /** Δt（送达时刻，毫秒）的分桶：0–2 / 2–50 / 50–200 / 200–900 / 0.9–1.1s / 1.1–2s / >2s */
    private val histDtMs = LongArray(7)

    /** 每项 [异常时刻(相对启动,ms), Δ值, Δt(ms), 类型标签] */
    private val anomalies = ArrayDeque<LongArray>()
    private val anomalyTags = ArrayDeque<String>()

    /** 相邻两次"跳步/同刻"之间的间隔（毫秒）—— 直接回答"是不是每 ~20 秒一次" */
    private val spikeGapsMs = ArrayDeque<Long>()

    @Volatile private var minDtMs = Double.MAX_VALUE
    @Volatile private var maxDtMs = 0.0
    @Volatile private var sumDtMs = 0.0
    @Volatile private var dtSamples = 0L

    private fun bucketOf(dt: Double): Int = when {
        dt <= 2.0 -> 0
        dt <= 50.0 -> 1
        dt <= 200.0 -> 2
        dt <= 900.0 -> 3
        dt <= 1100.0 -> 4
        dt <= 2000.0 -> 5
        else -> 6
    }

    private fun noteAnomaly(tag: String, deltaValue: Long, dtMs: Double, atMs: Long) {
        synchronized(anomalies) {
            anomalies.addLast(longArrayOf(atMs, deltaValue, dtMs.toLong()))
            anomalyTags.addLast(tag)
            while (anomalies.size > ANOMALY_KEEP) {
                anomalies.removeFirst()
                anomalyTags.removeFirst()
            }
            // 尖峰间隔：只统计"跳步/同刻"这两类相邻间隔
            if (tag == "跳步" || tag == "同刻" || tag == "时间戳紧邻") {
                val prev = lastSpikeAtMs
                if (prev > 0 && atMs > prev) {
                    spikeGapsMs.addLast(atMs - prev)
                    while (spikeGapsMs.size > ANOMALY_KEEP) spikeGapsMs.removeFirst()
                }
                lastSpikeAtMs = atMs
            }
        }
        // logcat 同步留痕：dump 采样会漏掉瞬时事件，日志不会（tag=StepTick）
        Log.i("StepTick", "异常[$tag] 时刻=重${atMs}ms Δ值=$deltaValue Δt=${dtMs.toInt()}ms")
    }

    @Volatile private var lastSpikeAtMs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) {
                detectorCallbacks++
                return
            }
            val halTs = event.timestamp                      // HAL 事件时间戳（ns）
            val nowMs = SystemClock.elapsedRealtime()
            val v = event.values[0].toLong()
            callbacks++

            if (prevHalTs != 0L) {
                val dt = (nowMs - prevNowMs).toDouble()
                val deltaValue = v - prevValue
                synchronized(histDtMs) { histDtMs[bucketOf(dt)]++ }
                dtSamples++
                sumDtMs += dt
                if (dt < minDtMs) minDtMs = dt
                if (dt > maxDtMs) maxDtMs = dt

                /*
                 * 送达同时 ≠ 事件同时：框架会把攒下的事件**一批交给应用**，那时两条回调的
                 * 送达时刻只差 0~2ms，但它们的事件时间戳可能差几百毫秒 —— 那是正常批量投递。
                 * 真正的"两条步事件同一时刻"要看**HAL 时间戳**：差 ≤5ms 才算。
                 * （第一版只看送达时刻，于是把正常批量也报成异常 —— 判读必须落在时间戳上。）
                 */
                val tsGapMs = (halTs - prevHalTs) / 1_000_000.0
                if (dt <= 2.0 && deltaValue >= 1L) {
                    if (tsGapMs <= 5.0) {
                        tsNearSimultaneous++      // ★ 真异常：两条事件时间戳几乎相同
                        noteAnomaly("时间戳紧邻", deltaValue, tsGapMs, nowMs - startedAtMs)
                    } else {
                        batchedTogether++          // 正常：同批投递
                    }
                }
                if (halTs == prevHalTs) {
                    sameTs++
                    noteAnomaly("同刻", deltaValue, dt, nowMs - startedAtMs)
                } else if (deltaValue == 0L) {
                    sameValue++
                    noteAnomaly("同值", deltaValue, dt, nowMs - startedAtMs)
                }
                if (deltaValue >= 2L) {
                    multiStep++
                    noteAnomaly("跳步", deltaValue, dt, nowMs - startedAtMs)
                }
                if (dt > 2 * EXPECT_INTERVAL_MS) {
                    gapOver2s++
                    noteAnomaly("空窗", deltaValue, dt, nowMs - startedAtMs)
                }
            }
            prevHalTs = halTs
            prevNowMs = nowMs
            prevValue = v
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun reset() {
        prevHalTs = 0L
        prevNowMs = 0L
        prevValue = -1L
        callbacks = 0L
        sameTs = 0L
        sameValue = 0L
        multiStep = 0L
        tsNearSimultaneous = 0L
        batchedTogether = 0L
        gapOver2s = 0L
        detectorCallbacks = 0L
        dtSamples = 0L
        sumDtMs = 0.0
        minDtMs = Double.MAX_VALUE
        maxDtMs = 0.0
        lastSpikeAtMs = 0L
        synchronized(histDtMs) { histDtMs.fill(0) }
        synchronized(anomalies) { anomalies.clear(); anomalyTags.clear() }
        synchronized(spikeGapsMs) { spikeGapsMs.clear() }
    }

    /** 幂等启动（Test 页 onResume 调） */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            unavailable = null
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
            val c = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (c == null) {
                unavailable = "框架里没有 TYPE_STEP_COUNTER"
                return
            }
            sensorManager = sm
            counter = c
            detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            reset()
            startedAtMs = SystemClock.elapsedRealtime()
            // ★ 期望 1 秒一次更新：samplingPeriodUs = 1_000_000
            sm.registerListener(listener, c, EXPECT_INTERVAL_US)
            detector?.let { sm.registerListener(listener, it, EXPECT_INTERVAL_US) }
            started = true
            Log.i("StepTick", "启动：期望间隔 ${EXPECT_INTERVAL_US / 1000}ms，handle=${runCatching {
                c.javaClass.getMethod("getHandle").invoke(c) as Int
            }.getOrDefault(-1)}")
        }
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
            runCatching { sensorManager?.unregisterListener(listener) }
            sensorManager = null
            counter = null
            detector = null
        }
    }

    /** 多行状态（Test 页原样展示） */
    fun status(): String {
        val err = unavailable
        if (err != null) return "1 秒期望探针   $err"
        if (!started) return "1 秒期望探针   未启动（进入本页即自动启动）"
        val sec = (SystemClock.elapsedRealtime() - startedAtMs) / 1000.0
        val sb = StringBuilder()
        sb.append("期望间隔        ").append(EXPECT_INTERVAL_US / 1000).append(" ms（")
            .append("%.0f".format(sec)).append(" 秒内 ").append(callbacks).append(" 条回调")
        if (sec > 1) sb.append("，平均 ").append("%.2f".format(callbacks / sec)).append(" 条/秒")
        sb.append("）").append('\n')
        val mean = if (dtSamples > 0) sumDtMs / dtSamples else 0.0
        sb.append("实测 Δt         均值 ").append("%.0f".format(mean)).append("ms / 最小 ")
            .append(if (dtSamples > 0) "%.0f".format(minDtMs) else "-").append(" / 最大 ")
            .append("%.0f".format(maxDtMs)).append(" ms").append('\n')
        val h = synchronized(histDtMs) { histDtMs.copyOf() }
        sb.append("Δt 分布         0–2ms:").append(h[0]).append("  2–50:").append(h[1])
            .append("  50–200:").append(h[2]).append("  200–900:").append(h[3])
            .append("  0.9–1.1s:").append(h[4]).append("  1.1–2s:").append(h[5])
            .append("  >2s:").append(h[6]).append('\n')
        sb.append("异常计数        时间戳紧邻(≤5ms) ").append(tsNearSimultaneous)
            .append(" / 同批投递(正常) ").append(batchedTogether)
            .append(" / 同刻(HAL ts 相同) ").append(sameTs).append(" / 同值 ")
            .append(sameValue).append(" / 跳步(Δ≥2) ").append(multiStep)
            .append(" / 空窗(>2s) ").append(gapOver2s).append('\n')
        val gaps = synchronized(spikeGapsMs) { spikeGapsMs.toList() }
        sb.append("尖峰间隔        ").append(
            if (gaps.isEmpty()) "（还没出现跳步/同刻）"
            else gaps.joinToString("  ") { "%.1fs".format(it / 1000.0) }
        ).append('\n')
        val list = synchronized(anomalies) { anomalies.toList() }
        val tags = synchronized(anomalyTags) { anomalyTags.toList() }
        sb.append("最近异常        ").append(
            if (list.isEmpty()) "（无）"
            else list.indices.joinToString("  ") { i ->
                val a = list[i]
                "${tags.getOrElse(i) { "?" }}@${"%.1f".format(a[0] / 1000.0)}s(Δ${a[1]}/${a[2]}ms)"
            }
        ).append('\n')
        sb.append("检测器回调      ").append(detectorCallbacks).append(" 次").append('\n')
        sb.append("判读            ").append(verdict())
        return sb.toString()
    }

    /** 一句话结论：把"有没有同刻/跳步"和"它们是否等间隔"分开说，避免把两件事混成一件 */
    private fun verdict(): String = when {
        callbacks < 3 -> "样本太少（走起来再看）"
        sameTs > 0 || multiStep > 0 || tsNearSimultaneous > 0 -> {
            val gaps = synchronized(spikeGapsMs) { spikeGapsMs.toList() }
            if (gaps.size >= 2) {
                val lo = gaps.min() / 1000.0
                val hi = gaps.max() / 1000.0
                "出现紧邻/同刻/跳步，且间隔落在 %.1f~%.1fs（等间隔 ⇒ 周期性，可定责）".format(lo, hi)
            } else {
                "出现紧邻/同刻/跳步（样本还不足以判周期）"
            }
        }
        sameValue > 0 -> "只出现同值（重复投递）——不是同刻双份"
        else -> "未出现同刻/跳值：这一段的投递是逐条连续的"
    }
}
