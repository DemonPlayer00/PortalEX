package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** 标定框 + 实际检测框；标定框接近时绿色，否则灰色，所有框黑色描边。 */
class FaceOverlayView(context: Context) : View(context) {
    var expected: RectF? = null
        set(value) { field = value; invalidate() }
    /**
     * 检测结果（目标框）。检测只有 6 次/秒，直接把每帧结果画出来就是"跳"；
     * 这里只当作目标，绘制时按屏幕刷新率向它收敛。
     */
    var actual: RectF? = null
        set(value) {
            field = value
            val target = value
            if (target == null) {
                drawn = null
                animating = false
            } else {
                val current = drawn
                // 首次出现或位置突变量大：视为重新捕获，直接对齐，不做慢速追赶
                if (current == null || !sameSpot(current, target)) {
                    drawn = RectF(target)
                }
                if (!animating) {
                    animating = true
                    postInvalidateOnAnimation()
                }
            }
            invalidate()
        }

    /** 实际画到屏幕上的框（向 [actual] 收敛的中间值）。 */
    private var drawn: RectF? = null
    private var animating = false

    /** 人脸有效性提示（闭眼/遮挡/头部出框）。透明底、黑描边、白字，居中绘制。 */
    var warnings: List<String> = emptyList()
        set(value) { field = value; invalidate() }

    /** 提示文字首行顶部（px，相对本 View）；由 Activity 按时长显示器底边计算。 */
    var warningsTop: Float = 0f
        set(value) { field = value; invalidate() }

    /** 分析帧的宽高比；归一化框必须按同一宽高比映射到 View，否则会被拉长。 */
    var sourceAspect: Float = 1f
        set(value) { field = value; invalidate() }

    /** 前置摄像头预览会被 CameraX 水平镜像；检测框来自未镜像的分析帧，必须同步翻转。 */
    var mirrorX: Boolean = false
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 14f; color = Color.BLACK
    }
    private val density = context.resources.displayMetrics.density
    private val warningTextSize = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics
    )

    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = Color.BLACK
        textSize = warningTextSize
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = warningTextSize
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private fun contentRect(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || sourceAspect <= 0f) return RectF(0f, 0f, vw, vh)
        val viewAspect = vw / vh
        return if (sourceAspect > viewAspect) {
            val h = vw / sourceAspect
            RectF(0f, (vh - h) / 2f, vw, (vh + h) / 2f)
        } else {
            val w = vh * sourceAspect
            RectF((vw - w) / 2f, 0f, (vw + w) / 2f, vh)
        }
    }

    private fun map(r: RectF): RectF {
        val c = contentRect()
        val left = if (mirrorX) 1f - r.right else r.left
        val right = if (mirrorX) 1f - r.left else r.right
        return RectF(
            c.left + left * c.width(),
            c.top + r.top * c.height(),
            c.left + right * c.width(),
            c.top + r.bottom * c.height()
        )
    }

    private fun close(a: RectF, b: RectF): Boolean {
        val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return union > 0f && inter / union >= 0.25f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val c = contentRect()
        android.util.Log.i(
            "CameraFace",
            "overlay size=" + w + "x" + h + " at=" + loc[0] + "," + loc[1] +
                " aspect=" + sourceAspect + " content=" + c.left + "," + c.top + "," + c.right + "," + c.bottom
        )
    }

    private fun sameSpot(a: RectF, b: RectF): Boolean {
        val jitter = maxOf(a.width(), b.width()) * 0.25f
        return kotlin.math.abs(a.centerX() - b.centerX()) < jitter &&
            kotlin.math.abs(a.centerY() - b.centerY()) < jitter
    }

    /** 向目标框插值；剩下不到千分之五就不再重绘，避免空转。 */
    private fun animateBox() {
        val target = actual ?: return
        val current = drawn ?: return
        val k = 0.35f
        current.left += (target.left - current.left) * k
        current.top += (target.top - current.top) * k
        current.right += (target.right - current.right) * k
        current.bottom += (target.bottom - current.bottom) * k
        val settled = kotlin.math.abs(target.left - current.left) < 0.0005f &&
            kotlin.math.abs(target.top - current.top) < 0.0005f &&
            kotlin.math.abs(target.right - current.right) < 0.0005f &&
            kotlin.math.abs(target.bottom - current.bottom) < 0.0005f
        if (settled) {
            current.set(target)
            animating = false
        } else {
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        animateBox()
        val exp = expected
        val act = drawn
        if (exp != null) {
            val r = map(exp)
            canvas.drawRect(r, outline)
            fill.color = if (act != null && close(r, map(act))) Color.GREEN else Color.GRAY
            canvas.drawRect(r, fill)
        }
        if (act != null) {
            val r = map(act)
            canvas.drawRect(r, outline)
            fill.color = Color.WHITE
            canvas.drawRect(r, fill)
        }
        drawWarnings(canvas)
    }

    /** 提示位于时长显示器下方，水平居中；过宽时整体缩小以留在可选区宽度内。 */
    private fun drawWarnings(canvas: Canvas) {
        val lines = warnings.take(4)
        if (lines.isEmpty()) return
        val maxWidth = width - density * 24f
        if (maxWidth <= 0f) return

        textFill.textSize = warningTextSize
        var widest = 0f
        lines.forEach { widest = maxOf(widest, textFill.measureText(it)) }
        val scale = if (widest > maxWidth) (maxWidth / widest).coerceAtLeast(0.6f) else 1f
        val size = warningTextSize * scale
        textFill.textSize = size
        textStroke.textSize = size
        textStroke.strokeWidth = density * 2f * scale

        val centerX = width / 2f
        var baseline = (if (warningsTop > 0f) warningsTop else density * 64f) + size
        val lineHeight = size * 1.35f
        lines.forEach { text ->
            canvas.drawText(text, centerX, baseline, textStroke)
            canvas.drawText(text, centerX, baseline, textFill)
            baseline += lineHeight
        }
    }
}
