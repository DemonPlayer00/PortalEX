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
 *  · 两条曲线 = 加速度模长、陀螺模长，**各自独立自动量程** —— 两者单位不同
 *    （m/s² vs rad/s），共用一根 Y 轴只会得到一条压成直线的曲线；各自量程并把区间
 *    标在左上角，读数才是诚实的。
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

    private var accel: FloatArray = EMPTY
    private var gyro: FloatArray = EMPTY

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val colorAccel = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val colorGyro = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
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

    /** 提交两条模长序列（自旧到新）。空数组表示"该路暂无数据"。 */
    fun submit(accelNorm: FloatArray, gyroNorm: FloatArray) {
        accel = accelNorm
        gyro = gyroNorm
        invalidate()
    }

    fun clear() = submit(EMPTY, EMPTY)

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

        if (accel.size < 2 && gyro.size < 2) {
            label.color = colorLabel
            label.textSize = dp(11f)
            canvas.drawText(
                "等待传感器数据…",
                left + dp(4f),
                (top + bottom) / 2f,
                label
            )
            return
        }

        drawSeries(canvas, accel, colorAccel, left, top, right, bottom, withFill = true)
        drawSeries(canvas, gyro, colorGyro, left, top, right, bottom, withFill = false)

        // 左上角量程标注（每路一条，颜色与曲线一致）
        label.textSize = dp(10f)
        var y = top + dp(10f)
        if (accel.size >= 2) {
            label.color = colorAccel
            canvas.drawText(rangeText("加速度模长", accel), left + dp(2f), y, label)
            y += dp(12f)
        }
        if (gyro.size >= 2) {
            label.color = colorGyro
            canvas.drawText(rangeText("陀螺模长", gyro), left + dp(2f), y, label)
        }
    }

    private fun rangeText(name: String, data: FloatArray): String {
        val lo = data.minOrNull() ?: 0f
        val hi = data.maxOrNull() ?: 0f
        return "%s %.4f ~ %.4f".format(name, lo, hi)
    }

    /**
     * 画一条序列：按自身 [min,max] 自动量程映射到绘图区。
     * 常数序列（max≈min）画在中线，避免除零变成贴边直线（那会被误读成"没有噪声"）。
     */
    private fun drawSeries(
        canvas: Canvas,
        data: FloatArray,
        color: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        withFill: Boolean,
    ) {
        if (data.size < 2) return
        val lo = data.minOrNull() ?: 0f
        val hi = data.maxOrNull() ?: 0f
        val span = hi - lo
        val path = Path()
        var lastY = 0f
        data.forEachIndexed { i, v ->
            val x = left + (right - left) * i / (data.size - 1).toFloat()
            val y = if (span < 1e-6f) {
                (top + bottom) / 2f
            } else {
                bottom - (bottom - top) * ((v - lo) / span)
            }
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastY = y
        }
        if (withFill) {
            val area = Path(path)
            area.lineTo(right, bottom)
            area.lineTo(left, bottom)
            area.close()
            fill.color = withAlpha(color, 0x33)
            canvas.drawPath(area, fill)
        }
        stroke.color = color
        stroke.strokeWidth = dp(2f)
        canvas.drawPath(path, stroke)
        // 末点高亮：一眼看出"现在"在哪
        fill.color = color
        canvas.drawCircle(right, lastY, dp(2.5f), fill)
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
