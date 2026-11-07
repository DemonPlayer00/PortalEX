package moe.fuqiuluo.portalex.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import moe.fuqiuluo.portalex.service.StaminaController
import kotlin.math.ceil

/** 实际运行状态图：D′ 余额 + 逐拍配速（最近 2 分钟）。 */
class StaminaChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var samples: List<StaminaController.LiveSample> = emptyList()
    private var baseSpeed = 3.05

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaled

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val colorBalance = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimary
    )
    private val colorPace = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorTertiary
    )
    private val colorGrid = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOutlineVariant
    )
    private val colorText = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    private val windowSec = 120.0
    private val paceFullScale = 6.0

    fun submit(samples: List<StaminaController.LiveSample>, baseSpeed: Double) {
        this.samples = samples
        this.baseSpeed = if (baseSpeed > 0.05) baseSpeed else 1.0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + dp(38f)
        val right = width - paddingRight - dp(12f)
        val top = paddingTop + dp(24f)
        val bottom = height - paddingBottom - dp(24f)
        if (right <= left || bottom <= top) return

        grid.color = colorGrid
        grid.strokeWidth = dp(1f)
        label.color = colorText
        label.textSize = sp(10f)

        // Y 轴 0..1（D′ 余额比例；配速映射到同一高度）
        for (i in 0..4) {
            val v = i / 4.0
            val y = (bottom - (bottom - top) * v).toFloat()
            canvas.drawLine(left, y, right, y, grid)
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText("%.0f%%".format(v * 100), left - dp(4f), y + dp(3f), label)
        }

        if (samples.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无运行数据", (left + right) / 2f, (top + bottom) / 2f, label)
            return
        }

        val end = samples.last().elapsedSec
        val start = (end - windowSec).coerceAtLeast(0.0)
        val span = (end - start).coerceAtLeast(1.0)
        fun x(t: Double) = (left + (right - left) * ((t - start) / span)).toFloat()
        fun y(v: Double) = (bottom - (bottom - top) * v.coerceIn(0.0, 1.0)).toFloat()

        // X 轴时间刻度
        label.textAlign = Paint.Align.CENTER
        var t = ceil(start / 20.0) * 20.0
        while (t <= end) {
            val xx = x(t)
            canvas.drawLine(xx, top, xx, bottom, grid)
            canvas.drawText("%.0fs".format(t), xx, bottom + dp(13f), label)
            t += 20.0
        }

        fun drawSeries(color: Int, dash: Boolean, value: (StaminaController.LiveSample) -> Double) {
            val p = Path()
            var first = true
            samples.forEach { s ->
                if (s.elapsedSec < start) return@forEach
                val xx = x(s.elapsedSec)
                val yy = y(value(s))
                if (first) { p.moveTo(xx, yy); first = false } else p.lineTo(xx, yy)
            }
            line.color = color
            line.strokeWidth = dp(2f)
            line.pathEffect = if (dash) android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f) else null
            canvas.drawPath(p, line)
            line.pathEffect = null
        }

        // 配速：0..6 m/s 映射到 0..1；D′：0..100% 映射到 0..1
        drawSeries(colorPace, true) { (it.paceMps / paceFullScale).coerceIn(0.0, 1.0) }
        drawSeries(colorBalance, false) { (it.staminaPercent / 100.0).coerceIn(0.0, 1.0) }

        // 图例
        label.textAlign = Paint.Align.LEFT
        var lx = left
        val ly = paddingTop + dp(10f)
        line.color = colorBalance; line.strokeWidth = dp(2f)
        canvas.drawLine(lx, ly, lx + dp(14f), ly, line); lx += dp(18f)
        canvas.drawText("D′ 余额", lx, ly + dp(3f), label)
        lx += label.measureText("D′ 余额") + dp(12f)
        line.color = colorPace; line.strokeWidth = dp(2f)
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(2f)), 0f)
        canvas.drawLine(lx, ly, lx + dp(14f), ly, line)
        line.pathEffect = null
        canvas.drawText("配速（满格 %.1f m/s）".format(paceFullScale), lx + dp(18f), ly + dp(3f), label)
    }
}
