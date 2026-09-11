package moe.fuqiuluo.portalex.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.fuqiuluo.xposed.utils.SensorNoise
import kotlin.math.sqrt

/**
 * 环境噪声一键校准：把手机**静置**采一段真实传感器数据，逐轴统计 **中位数 μ 与方差 σ²**，
 * 再按 Calibration 的口径写成注入档（使用时由原生层按 N(μ, σ²) 逐事件生成动态值）。
 *
 * 中位数只有陀螺那一路会注入（陀螺零参考物理上就是 0，实测中位数即真实零偏）；其余传感器的
 * 中位数里混着姿态与环境直流，只作参照显示 —— 详见 [SensorNoise] 的口径说明。
 *
 * 三条纪律（每一条都对应一次踩坑）：
 *  1. **必须在模拟未运行时采**：框架侧注入一旦生效就会压制真实事件，那时「校准」只会把我们
 *     自己的假数据量一遍（自我校准）。这件事由调用方在开始前把关（见 CalibrationFragment）。
 *  2. **静止要自检，不能只靠提示**：手上一点抖动都会把「手抖」写进噪声档。加速度模长 σ 与
 *     陀螺 σ 超阈值即判定「在动」，直接作废重来。
 *  3. **测不到的量不动**：方向角 / 旋转矢量是融合结果（本机可能没有该传感器），不参与校准。
 *
 * 本类是**阻塞**实现（采集循环 sleep 推进），必须在 IO 线程调用。
 */
object SensorNoiseCalibrator {

    /** 采集窗口（丢弃前 [WARMUP_MS] 的建立期样本） */
    const val DURATION_MS = 8000L
    private const val WARMUP_MS = 600L

    /** 采样周期：50Hz（σ 是「每样本」的量，与注入栅格的量级要对得上） */
    private const val SAMPLING_US = 20_000

    /** 静止判定：加速度模长的 σ（m/s²）。静止真机 ≈0.008，走起来 >1 */
    private const val MAX_ACCEL_NORM_SIGMA = 0.25
    /** 静止判定：陀螺单轴 σ（rad/s）。静止真机 ≈0.001，转动 >0.05 */
    private const val MAX_GYRO_SIGMA = 0.05
    /** 每个传感器至少要有的样本数（50Hz × 7.4s ≈ 370） */
    private const val MIN_SAMPLES = 60
    /** 单轴样本缓冲上限（8s@50Hz=400；给更快的采样留余量） */
    private const val BUF = 4096

    /** σ 下限：真机不可能 0 噪声，测出 0 只可能是采样异常，兜住不让它变成「完美常量」 */
    private const val FLOOR_ACCEL = 0.0005f
    private const val FLOOR_GYRO = 0.0001f
    private const val FLOOR_MAG = 0.01f

    private const val TAG = "PortalNoiseCalib"

    data class Result(
        /** 20 槽注入档（未测项 = 传入的 base 值） */
        val values: FloatArray,
        /** 人类可读的测量明细（中位数 + σ；页面直接显示，便于人工核对） */
        val report: String,
        val ok: Boolean,
        val message: String,
    )

    /** 原始样本缓冲（回调线程写、采集线程读，自带同步） */
    private class Buf {
        private val data = DoubleArray(BUF)
        private var n = 0

        fun add(v: Double) = synchronized(this) { if (n < BUF) data[n++] = v }

        fun count(): Int = synchronized(this) { n }

        /** 中位数（偶数取中间两个的均值） */
        fun median(): Double = synchronized(this) {
            if (n == 0) return 0.0
            val copy = data.copyOf(n)
            copy.sort()
            if (n % 2 == 1) copy[n / 2] else (copy[n / 2 - 1] + copy[n / 2]) / 2.0
        }

        /** **关于中位数的**方差（与中位数配对的口径；对称分布下与常规方差一致） */
        fun variance(): Double = synchronized(this) {
            if (n <= 1) return 0.0
            val med = median()
            var acc = 0.0
            for (i in 0 until n) {
                val d = data[i] - med
                acc += d * d
            }
            acc / n
        }
    }

    /** 一个传感器的三轴样本 + 模长（模长只用于静止判定） */
    private class SensorBuf {
        val axis = Array(3) { Buf() }
        val norm = Buf()

        fun add(values: FloatArray) {
            if (values.size < 3) return
            for (i in 0 until 3) axis[i].add(values[i].toDouble())
            norm.add(sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble())
        }

        fun count(): Int = axis[0].count()
        fun medians(): DoubleArray = DoubleArray(3) { axis[it].median() }
        fun sigmas(): DoubleArray = DoubleArray(3) { sqrt(axis[it].variance()) }
        /** 模长的标准差（静止判定用） */
        fun normSigma(): Double = sqrt(norm.variance())
    }

    private fun sensorName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "加速度计"
        Sensor.TYPE_GYROSCOPE -> "陀螺仪"
        Sensor.TYPE_MAGNETIC_FIELD -> "磁场"
        Sensor.TYPE_GRAVITY -> "重力"
        Sensor.TYPE_LINEAR_ACCELERATION -> "线性加速度"
        else -> "type=$type"
    }

    /**
     * 采样、统计并换算成注入档。
     *
     * @param base 当前注入档（未测项沿用它的值）
     * @param onProgress 进度回调（参数为已采集毫秒数；在采集线程上调用，UI 更新请自行 post）
     */
    fun collect(context: Context, base: FloatArray, onProgress: (Long) -> Unit = {}): Result {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return Result(base, "", false, "取不到 SensorManager")
        val main = Handler(Looper.getMainLooper())
        val bufs = LinkedHashMap<Int, SensorBuf>()
        val missing = mutableListOf<String>()
        for (type in listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION,
        )) {
            if (sm.getDefaultSensor(type) == null) missing.add(sensorName(type)) else bufs[type] = SensorBuf()
        }
        if (bufs.isEmpty()) return Result(base, "", false, "本机没有可用的加速度/陀螺/磁场传感器")

        val warmupEnd = System.currentTimeMillis() + WARMUP_MS
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val e = event ?: return
                // 建立期样本丢弃：注册瞬间常带上一帧的陈旧值
                if (System.currentTimeMillis() < warmupEnd) return
                bufs[e.sensor.type]?.add(e.values)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        var registered = 0
        try {
            for (type in bufs.keys) {
                val sensor = sm.getDefaultSensor(type) ?: continue
                // 5 参形式（maxLatencyUs=0 逐条上报 + 显式主线程 handler）：
                // 本函数跑在 IO 线程，不带 handler 的重载会把回调绑到调用线程的 Looper 上
                if (sm.registerListener(listener, sensor, SAMPLING_US, 0, main)) registered++
            }
            if (registered == 0) return Result(base, "", false, "传感器注册失败")

            val start = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                onProgress(elapsed)
                if (elapsed >= DURATION_MS) break
                Thread.sleep(100)
            }
        } finally {
            runCatching { sm.unregisterListener(listener) }
        }

        // ---- 静止自检 ----
        val accel = bufs[Sensor.TYPE_ACCELEROMETER]
        val gyro = bufs[Sensor.TYPE_GYROSCOPE]
        val accelNormSigma = accel?.normSigma() ?: 0.0
        val gyroSigma = gyro?.sigmas()?.max() ?: 0.0
        if (accelNormSigma > MAX_ACCEL_NORM_SIGMA || gyroSigma > MAX_GYRO_SIGMA) {
            val why = buildString {
                if (accelNormSigma > MAX_ACCEL_NORM_SIGMA) {
                    append("加速度波动 σ=%.3f m/s²".format(accelNormSigma))
                }
                if (gyroSigma > MAX_GYRO_SIGMA) {
                    if (isNotEmpty()) append("、")
                    append("陀螺波动 σ=%.3f rad/s".format(gyroSigma))
                }
            }
            return Result(base, "", false, "检测到移动（$why），请把手机放稳后重试")
        }

        // ---- 中位数 + 方差 → 注入档 ----
        val out = SensorNoise.sanitize(base)
        val report = StringBuilder()
        var measured = 0

        fun maxOf(start: Int): Float =
            SensorNoise.ITEMS.firstOrNull { start in it.start..it.end }?.max ?: 50f

        fun fill(start: Int, sigmas: DoubleArray, floor: Float) {
            val max = maxOf(start)
            for (i in 0 until 3) {
                out[start + i] = sigmas[i].toFloat().coerceAtLeast(floor).coerceAtMost(max)
                measured++
            }
        }

        fun line(name: String, b: SensorBuf, sigmas: DoubleArray, injected: Boolean) {
            val med = b.medians()
            report.append(
                "%s μ=%s  σ=%s  n=%d%s\n".format(
                    name,
                    med.joinToString("/") { "%.4f".format(it) },
                    sigmas.joinToString("/") { "%.4f".format(it) },
                    b.count(),
                    if (injected) "  ← μ 注入为零偏" else "",
                )
            )
        }

        gyro?.let { b ->
            if (b.count() >= MIN_SAMPLES) {
                val sg = b.sigmas()
                fill(SensorNoise.GYRO, sg, FLOOR_GYRO)
                // 陀螺零偏：唯一被注入的中位数（零参考物理上是 0）
                val med = b.medians()
                for (i in 0 until 3) {
                    out[SensorNoise.GYRO_BIAS + i] = med[i].toFloat().coerceIn(-0.5f, 0.5f)
                }
                line("陀螺仪", b, sg, injected = true)
            }
        }
        accel?.let { b ->
            if (b.count() >= MIN_SAMPLES) {
                val sg = b.sigmas()
                fill(SensorNoise.ACCEL, sg, FLOOR_ACCEL)
                line("加速度计", b, sg, injected = false)
            }
        }
        bufs[Sensor.TYPE_GRAVITY]?.let { b ->
            if (b.count() >= MIN_SAMPLES) {
                val sg = b.sigmas()
                fill(SensorNoise.GRAVITY, sg, FLOOR_ACCEL)
                line("重力", b, sg, injected = false)
            }
        }
        bufs[Sensor.TYPE_LINEAR_ACCELERATION]?.let { b ->
            if (b.count() >= MIN_SAMPLES) {
                val sg = b.sigmas()
                fill(SensorNoise.LINEAR, sg, FLOOR_ACCEL)
                line("线性加", b, sg, injected = false)
            }
        }
        bufs[Sensor.TYPE_MAGNETIC_FIELD]?.let { b ->
            if (b.count() >= MIN_SAMPLES) {
                val sg = b.sigmas()
                fill(SensorNoise.MAG, sg, FLOOR_MAG)
                line("磁场", b, sg, injected = false)
            }
        }

        if (measured == 0) return Result(base, report.toString(), false, "样本太少，未采到有效数据（请重试）")
        if (missing.isNotEmpty()) report.append("本机无：${missing.joinToString("、")}\n")
        report.append(
            "采样 %.1fs @50Hz，%d 个传感器；中位数只有陀螺那一路注入（零偏）".format(
                DURATION_MS / 1000.0, registered,
            )
        )
        Log.i(TAG, "calibrated: ${SensorNoise.encode(out)}")
        return Result(out, report.toString(), true, "校准完成（更新 $measured 个噪声项）")
    }
}
