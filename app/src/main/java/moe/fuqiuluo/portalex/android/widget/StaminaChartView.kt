package moe.fuqiuluo.portalex.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.google.android.material.color.MaterialColors
import moe.fuqiuluo.xposed.utils.StaminaConfig
import moe.fuqiuluo.xposed.utils.StaminaCurve
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * **速度倍率 × 距离**预览图（体力页用）。
 *
 * ## 用户口径
 *
 *  · 横向窗口 **2km**，可左右滑动看更远的距离（全表 [StaminaCurve.DEFAULT_MAX_DISTANCE_M]）；
 *  · 一条**默认颜色**实线 = **无随机**状态下的速度倍率；
 *  · 两条**半透明**同色线 = **最高正随机 / 最高负随机**的边界（上下包络）。
 *
 * ## 三个设计取舍
 *
 *  · **横轴是距离而不是时间**：用户要问的是"跑多远会掉到什么速度"。倍率会反过来改变
 *    单位时间走过的距离，两个轴互相耦合 ⇒ 数据由 [StaminaCurve] 积分得到，不在本类里算。
 *  · **纵轴固定在 0~1.0**：倍率是有绝对含义的量（1.0 = 不调制），固定量程才能让
 *    "改了参数之后图变了"一眼看出来；自动量程会把任何曲线都拉满，反而看不出变化。
 *  · **自己处理横向滚动，不套 HorizontalScrollView**：套一层滚动容器的话，纵轴刻度会跟着
 *    滑出屏幕（那些数字是读图的前提）。这里只把**绘图区**裁剪后按 scrollMeters 平移，
 *    刻度栏钉在原地。与上层纵向 ScrollView 的冲突靠"横向起手即 disallowIntercept"解决。
 *
 * ## 线程约定
 *
 * [submit] 与绘制都只在主线程；曲线在 [submit] 里一次算完（默认参数约 1.6 万个采样点/条），
 * **不要**放进每秒刷新的状态循环里 —— 那会每秒重算三遍同样的东西。
 */
class StaminaChartView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attributeSet, defStyleAttr) {

    private var config: StaminaConfig? = null
    private var baseSpeed: Double = 3.05

    /** 可见窗口（米）与全表长度（米） */
    private var windowMeters: Double = StaminaCurve.DEFAULT_WINDOW_M
    private var totalMeters: Double = StaminaCurve.DEFAULT_MAX_DISTANCE_M

    /** 左侧滚动位置（米）。单位用米而不是像素：窗口/全表换了之后像素刻度会变 */
    private var scrollMeters: Double = 0.0

    private var baseCurve: StaminaCurve.Curve? = null

    /** 速度**下界**：衰减取最大 + 过渡取最快（两件随机互相独立，这个组合同样可达） */
    private var lowerBoundCurve: StaminaCurve.Curve? = null

    /** 速度**上界**：衰减取最小 + 过渡取最慢 */
    private var upperBoundCurve: StaminaCurve.Curve? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val colorLine = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimary
    )
    private val colorGrid = withAlpha(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant), 0x66
    )
    private val colorLabel = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaled

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTouchX = 0f
    private var dragging = false

    init {
        grid.strokeWidth = dp(1f)
        label.textSize = sp(10f)
    }

    // ── 几何：绘制与触摸必须用同一套（否则拖动手感与图不对应）────────────────

    private fun plotLeftPx() = paddingLeft + dp(GUTTER_DP)
    private fun plotRightPx() = width - paddingRight - dp(RIGHT_PAD_DP)
    private fun plotWidthPx() = (plotRightPx() - plotLeftPx()).coerceAtLeast(1f)
    private fun plotTopPx() = paddingTop + dp(LEGEND_H_DP)
    private fun plotBottomPx() = height - paddingBottom - dp(AXIS_H_DP)
    private fun metersPerPx(): Double = windowMeters / plotWidthPx()

    /**
     * 提交参数并重算三条曲线（参数没变就什么都不做 —— 调用方可以放心地反复调）。
     *
     * @param baseSpeed 基础速度（m/s）：倍率本身与它无关，但"走路低值"要换算成倍率，
     *   每一步推进多少米也要它
     */
    fun submit(config: StaminaConfig, baseSpeed: Double) {
        val base = if (baseSpeed > 0.05) baseSpeed else 1.0
        if (this.config == config && this.baseSpeed == base) return
        this.config = config
        this.baseSpeed = base

        val p = (config.randomPercent / 100.0).coerceIn(0.0, 1.0)
        baseCurve = StaminaCurve.simulate(config, base, decayScale = 1.0)
        // 运行时随机有两处：每段跑动抽一次衰减速率、每次过渡抽一次过渡时长。
        // 两者独立 ⇒ 极值组合同样可达：下界 = 衰减最大 + 过渡最快，上界反之。
        lowerBoundCurve = StaminaCurve.simulate(
            config, base, decayScale = 1.0 + p, transitionScale = (1.0 - p).coerceAtLeast(0.05)
        )
        upperBoundCurve = StaminaCurve.simulate(
            config, base, decayScale = 1.0 - p, transitionScale = 1.0 + p
        )
        clampScroll()
        invalidate()
    }

    /** 当前滚动位置（米）—— 诊断用 */
    fun scrollMetersNow(): Double = scrollMeters

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val plotLeft = plotLeftPx()
        val plotRight = plotRightPx()
        val plotTop = plotTopPx()
        val plotBottom = plotBottomPx()
        val plotW = plotWidthPx()
        val plotH = plotBottom - plotTop
        if (plotW < dp(20f) || plotH < dp(20f)) return

        val perPx = metersPerPx()
        val yOf: (Double) -> Float = { v -> plotBottom - plotH * (v / Y_MAX).toFloat() }
        val xOf: (Double) -> Float = { d -> plotLeft + ((d - scrollMeters) / perPx).toFloat() }

        // ── 绘图区：网格 + 三条曲线（裁剪后按滚动位置平移）────────────────────
        canvas.save()
        canvas.clipRect(plotLeft, plotTop, plotRight, plotBottom)

        grid.color = colorGrid
        // 横向：倍率刻度线（数字在左侧刻度栏，不随滚动）
        var mv = 0.0
        while (mv <= Y_MAX + 1e-9) {
            val y = yOf(mv)
            canvas.drawLine(plotLeft, y, plotRight, y, grid)
            mv += Y_STEP
        }
        // 纵向：每 0.5km 一条（整 km 才标字，半 km 只给线 —— 2km 窗口里塞 4 个数字就挤了）
        var d = floor(scrollMeters / X_TICK_M) * X_TICK_M
        while (d <= scrollMeters + windowMeters + X_TICK_M) {
            if (d >= 0.0) {
                val x = xOf(d)
                if (x >= plotLeft - 1f && x <= plotRight + 1f) {
                    canvas.drawLine(x, plotTop, x, plotBottom, grid)
                }
            }
            d += X_TICK_M
        }

        // 先画包络（半透明），后画主曲线：重叠处主曲线要压在上面
        lowerBoundCurve?.let {
            drawCurve(canvas, it, xOf, yOf, withAlpha(colorLine, BOUND_ALPHA), dp(1.5f), plotLeft, plotRight)
        }
        upperBoundCurve?.let {
            drawCurve(canvas, it, xOf, yOf, withAlpha(colorLine, BOUND_ALPHA), dp(1.5f), plotLeft, plotRight)
        }
        baseCurve?.let { drawCurve(canvas, it, xOf, yOf, colorLine, dp(2f), plotLeft, plotRight) }
        canvas.restore()

        // ── 刻度栏：纵轴数字（固定不动）──────────────────────────────────────
        label.color = colorLabel
        label.textSize = sp(9f)
        label.textAlign = Paint.Align.RIGHT
        var ml = 0.0
        while (ml <= Y_MAX + 1e-9) {
            canvas.drawText(tickText(ml), plotLeft - dp(4f), yOf(ml) + dp(3.5f), label)
            ml += Y_STEP
        }

        // 横轴刻度：整 km 才标字
        label.textAlign = Paint.Align.CENTER
        var dl = ceil(scrollMeters / X_LABEL_M) * X_LABEL_M
        while (dl <= scrollMeters + windowMeters) {
            if (dl > 0.0) {
                val x = xOf(dl)
                if (x >= plotLeft && x <= plotRight) {
                    canvas.drawText("%.0fkm".format(dl / 1000.0), x, plotBottom + dp(11f), label)
                }
            }
            dl += X_LABEL_M
        }
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("km", plotRight - dp(14f), plotBottom + dp(11f), label)

        // ── 图例（左上，用绘图区上方的留白）──────────────────────────────────
        drawLegend(canvas, plotLeft, paddingTop + dp(9f))

        // ── 可滚动提示：还有内容的方向画箭头 ──────────────────────────────────
        val maxScroll = maxScrollMeters()
        val midY = (plotTop + plotBottom) / 2f
        if (scrollMeters > 1.0) drawChevron(canvas, plotLeft + dp(6f), midY, pointingLeft = true)
        if (scrollMeters < maxScroll - 1.0) {
            drawChevron(canvas, plotRight - dp(6f), midY, pointingLeft = false)
        }
    }

    private fun tickText(v: Double): String =
        if (v == 0.0) "0" else "%.2f".format(v).trimEnd('0').trimEnd('.')

    /**
     * 画一条曲线：横轴按像素**抽稀**（相邻点不足 0.6px 就丢掉）。
     * 10km 的采样点全塞进 Path 会白描大量看不见的重复点。
     */
    private fun drawCurve(
        canvas: Canvas,
        curve: StaminaCurve.Curve,
        xOf: (Double) -> Float,
        yOf: (Double) -> Float,
        color: Int,
        strokeWidth: Float,
        clipLeft: Float,
        clipRight: Float,
    ) {
        val n = curve.size
        if (n < 2) return
        val path = Path()
        var lastX = Float.NaN
        for (i in 0 until n) {
            val x = xOf(curve.distanceM[i].toDouble())
            if (x < clipLeft - dp(4f)) continue     // 视窗左侧之外：跳过
            if (x > clipRight + dp(4f)) break       // 距离递增 ⇒ 出了右边可以直接停
            if (!lastX.isNaN() && x - lastX < 0.6f && i != n - 1) continue
            val y = yOf(curve.multiplier[i].toDouble())
            if (lastX.isNaN()) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
        }
        stroke.color = color
        stroke.strokeWidth = strokeWidth
        canvas.drawPath(path, stroke)
    }

    private fun drawLegend(canvas: Canvas, left: Float, baseline: Float) {
        val pct = (config?.randomPercent ?: 0.0).toInt()
        val swatch = dp(14f)
        var x = left

        stroke.color = colorLine
        stroke.strokeWidth = dp(2f)
        canvas.drawLine(x, baseline - dp(3f), x + swatch, baseline - dp(3f), stroke)
        label.color = colorLabel
        label.textSize = sp(10f)
        label.textAlign = Paint.Align.LEFT
        x += swatch + dp(4f)
        canvas.drawText(BASE_LEGEND, x, baseline, label)
        x += label.measureText(BASE_LEGEND) + dp(12f)

        stroke.color = withAlpha(colorLine, BOUND_ALPHA)
        stroke.strokeWidth = dp(1.5f)
        canvas.drawLine(x, baseline - dp(3f), x + swatch, baseline - dp(3f), stroke)
        x += swatch + dp(4f)
        canvas.drawText("±$pct% 极值", x, baseline, label)
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, pointingLeft: Boolean) {
        val s = dp(5f)
        val path = Path()
        if (pointingLeft) {
            path.moveTo(x + s, y - s)
            path.lineTo(x, y)
            path.lineTo(x + s, y + s)
        } else {
            path.moveTo(x - s, y - s)
            path.lineTo(x, y)
            path.lineTo(x - s, y + s)
        }
        path.close()
        fill.color = withAlpha(colorLabel, 0x99)
        canvas.drawPath(path, fill)
    }

    // ── 横向滚动 ────────────────────────────────────────────────────────────

    private fun maxScrollMeters(): Double = (totalMeters - windowMeters).coerceAtLeast(0.0)

    private fun clampScroll() {
        scrollMeters = scrollMeters.coerceIn(0.0, maxScrollMeters())
    }

    private fun scrollByMeters(delta: Double) {
        if (delta == 0.0 || maxScrollMeters() <= 0.0) return
        val before = scrollMeters
        scrollMeters = (scrollMeters + delta).coerceIn(0.0, maxScrollMeters())
        if (scrollMeters != before) invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                if (!dragging) {
                    if (abs(dx) <= touchSlop) return true
                    // 横向意图明确 ⇒ 抢在父 ScrollView 之前锁定手势（否则纵向拖拽判定会把手势收走）
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastTouchX = event.x
                    return true
                }
                // 手指右移 ⇒ 把内容往右拖 ⇒ 看更近的距离
                scrollByMeters(-dx.toDouble() * metersPerPx())
                lastTouchX = event.x
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** 纵轴固定量程：1.0 = 不调制，是有绝对含义的上界 */
        const val Y_MAX = 1.0
        const val Y_STEP = 0.25

        /** 横轴：每 0.5km 一条刻度线，整 km 才写字 */
        const val X_TICK_M = 500.0
        const val X_LABEL_M = 1000.0

        /** 包络线的透明度（"半透明"，压在实线下面仍要看得见） */
        const val BOUND_ALPHA = 0x59

        /** 几何（dp） */
        const val GUTTER_DP = 30f
        const val RIGHT_PAD_DP = 2f
        const val LEGEND_H_DP = 14f
        const val AXIS_H_DP = 14f

        const val BASE_LEGEND = "无随机"
    }
}
