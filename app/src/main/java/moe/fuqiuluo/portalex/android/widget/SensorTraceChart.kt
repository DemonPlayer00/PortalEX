package moe.fuqiuluo.portalex.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * 传感器波形图：把**采集期间的实时波动**画出来（校准 dialog 用）。
 *
 * 设计取舍：
 *  · 两条曲线 = 加速度模长、陀螺模长，**同色、只有线条**（无填充、无端点标记）；
 *    两者单位不同（m/s² vs rad/s），各自独立自动量程；**量程数字不画在图内**（高度压到 75dp 后
 *    图内文字会压住曲线），改由调用方的 caption 显示 σ 与区间。
 *  · **横轴固定为采集窗口**（默认 8s）：曲线从左侧开始向右生长，纵轴形状不会被"样本数"
 *    拉伸变形；每 2s 一条浅竖线作为时间刻度。
 *  · 只画模长：这正是"静止判定"用的量（见 `SensorNoiseCalibrator` 的 `normSigma()`），
 *    所以图上看到的就是决定成败的那条线，而不是另一套好看但无关的数字。
 *  · 配色全部取自主题（`colorPrimary` / `colorTertiary` / `colorOutlineVariant` /
 *    `colorOnSurfaceVariant`）⇒ 跟随 M3 明暗与动态取色，不写死颜色。
 *  · 无第三方依赖：本项目不引图表库（见仓库红线：不引额外 SDK）。
 *
 * 线程约定：[submit] 必须在主线程调用（View 只从主线程重绘）。
 */
class SensorTraceChart @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attributeSet, defStyleAttr) {

    /** 一条曲线：时间轴（ms，相对采集起点）+ 数值 */
    class Series(val timeMs: FloatArray, val value: FloatArray) {
        val size: Int get() = minOf(timeMs.size, value.size)
    }

    private var accel: Series? = null
    private var gyro: Series? = null
    private var windowMs: Float = 8000f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 两条曲线**同一个颜色**（用户口径）；不引入第二种强调色 */
    private val colorLine = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val colorGrid = withAlpha(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant), 0x66
    )
    private val colorLabel = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    init {
        grid.strokeWidth = dp(1f)
        label.textSize = dp(10f)
    }

    /**
     * 提交两条模长曲线（自旧到新，带相对时间戳）。
     * @param windowMs 横轴固定跨度（ms）：曲线的 x = t / windowMs，超出部分贴右边缘。
     */
    fun submit(accelNorm: Series, gyroNorm: Series, windowMs: Float = 8000f) {
        accel = accelNorm
        gyro = gyroNorm
        this.windowMs = windowMs.coerceAtLeast(1f)
        invalidate()
    }

    fun clear() {
        accel = null
        gyro = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft + dp(2f)
        val right = width - paddingRight - dp(2f)
        val top = paddingTop + dp(2f)
        val bottom = height - paddingBottom - dp(6f)
        if (right <= left || bottom <= top) return

        // 网格：三条等距横线（只作视觉基准，不标刻度——量程标在左上角）
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            canvas.drawLine(left, y, right, y, grid)
        }

        // 竖向时间刻度：每 2s 一条（横轴固定为采集窗口，刻度让"固定"这件事看得见）
        val stepMs = 2000f
        var t = stepMs
        while (t < windowMs) {
            val x = left + (right - left) * (t / windowMs)
            canvas.drawLine(x, top, x, bottom, grid)
            t += stepMs
        }

        val accelSeries = accel
        val gyroSeries = gyro
        if ((accelSeries?.size ?: 0) < 2 && (gyroSeries?.size ?: 0) < 2) {
            label.color = colorLabel
            label.textSize = dp(11f)
            canvas.drawText("等待传感器数据…", left + dp(4f), (top + bottom) / 2f, label)
            return
        }

        // 两条曲线**同色**（用户口径：一张图一根颜色）；区分靠左上角的量程标注
        drawSeries(canvas, accelSeries, left, top, right, bottom)
        drawSeries(canvas, gyroSeries, left, top, right, bottom)

        label.textSize = dp(10f)
        label.color = colorLabel
        var y = top + dp(10f)
        accelSeries?.let {
            if (it.size >= 2) {
                canvas.drawText(rangeText("加速度模长", it.value), left + dp(2f), y, label)
                y += dp(12f)
            }
        }
        gyroSeries?.let {
            if (it.size >= 2) canvas.drawText(rangeText("陀螺模长", it.value), left + dp(2f), y, label)
        }
    }

    private fun rangeText(name: String, data: FloatArray): String {
        val lo = data.minOrNull() ?: 0f
        val hi = data.maxOrNull() ?: 0f
        return "%s %.4f ~ %.4f".format(name, lo, hi)
    }

    /**
     * 画一条曲线：x 由**相对时间 / 固定窗口**决定（所以早段只占左侧、随采集向右生长），
     * y 按该序列自身的 [min,max] 自动量程。常数序列画在中线，避免除零变成贴边直线
     * （那会被误读成"没有噪声"）。只画线：没有填充、没有端点标记。
     */
    private fun drawSeries(
        canvas: Canvas,
        series: Series?,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        if (series == null || series.size < 2) return
        val lo = series.value.minOrNull() ?: 0f
        val hi = series.value.maxOrNull() ?: 0f
        val span = hi - lo
        val path = Path()
        for (i in 0 until series.size) {
            val x = left + (right - left) * (series.timeMs[i] / windowMs).coerceIn(0f, 1f)
            val y = if (span < 1e-6f) {
                (top + bottom) / 2f
            } else {
                bottom - (bottom - top) * ((series.value[i] - lo) / span)
            }
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke.color = colorLine
        stroke.strokeWidth = dp(2f)
        canvas.drawPath(path, stroke)
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
