package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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

    /**
     * 手动校准模式：隐藏实时检测框，标准框可拖动 / 双指缩放。
     * 人脸有效性检查（闭眼、朝向、遮挡）照常运行，只是不再画实时框。
     */
    var editing: Boolean = false
        set(value) {
            field = value
            isClickable = value
            invalidate()
        }

    private var lastX = 0f
    private var lastY = 0f
    private var dragMode = DragMode.MOVE

    /** 拖角 = 改相邻两条边；拖边 = 只改那一条边；框内拖动 = 整体移动；双指 = 等比缩放。 */
    private enum class DragMode { MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val handleRadius: Float get() = 22f * density

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val box = expected ?: return false
                val factor = detector.scaleFactor.coerceIn(0.85f, 1.18f)
                val halfW = (box.width() / 2f * factor).coerceIn(0.06f, 0.55f)
                val halfH = (box.height() / 2f * factor).coerceIn(0.06f, 0.55f)
                expected = normalize(
                    RectF(
                        box.centerX() - halfW, box.centerY() - halfH,
                        box.centerX() + halfW, box.centerY() + halfH
                    )
                )
                return true
            }
        }
    )

    private fun modeFor(x: Float, y: Float, r: RectF): DragMode {
        val nearLeft = kotlin.math.abs(x - r.left) <= handleRadius
        val nearRight = kotlin.math.abs(x - r.right) <= handleRadius
        val nearTop = kotlin.math.abs(y - r.top) <= handleRadius
        val nearBottom = kotlin.math.abs(y - r.bottom) <= handleRadius
        val withinX = x >= r.left - handleRadius && x <= r.right + handleRadius
        val withinY = y >= r.top - handleRadius && y <= r.bottom + handleRadius
        return when {
            nearLeft && nearTop -> DragMode.TOP_LEFT
            nearRight && nearTop -> DragMode.TOP_RIGHT
            nearLeft && nearBottom -> DragMode.BOTTOM_LEFT
            nearRight && nearBottom -> DragMode.BOTTOM_RIGHT
            nearLeft && withinY -> DragMode.LEFT
            nearRight && withinY -> DragMode.RIGHT
            nearTop && withinX -> DragMode.TOP
            nearBottom && withinX -> DragMode.BOTTOM
            else -> DragMode.MOVE
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                val box = expected
                dragMode = if (box == null) DragMode.MOVE else modeFor(event.x, event.y, map(box))
                // 命中判定是在"屏幕坐标"上做的，而镜像下屏幕右 = 归一化左，
                // 所以左右方向要在归一化空间里对调，否则拖右边会改到左边。
                if (mirrorX) {
                    dragMode = when (dragMode) {
                        DragMode.LEFT -> DragMode.RIGHT
                        DragMode.RIGHT -> DragMode.LEFT
                        DragMode.TOP_LEFT -> DragMode.TOP_RIGHT
                        DragMode.TOP_RIGHT -> DragMode.TOP_LEFT
                        DragMode.BOTTOM_LEFT -> DragMode.BOTTOM_RIGHT
                        DragMode.BOTTOM_RIGHT -> DragMode.BOTTOM_LEFT
                        else -> dragMode
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                val box = expected ?: return true
                val c = contentRect()
                if (c.width() <= 0f || c.height() <= 0f) return true
                val dx = (event.x - lastX) / c.width() * (if (mirrorX) -1f else 1f)
                val dy = (event.y - lastY) / c.height()
                lastX = event.x
                lastY = event.y
                val b = RectF(box)
                when (dragMode) {
                    DragMode.MOVE -> b.offset(dx, dy)
                    DragMode.LEFT -> b.left += dx
                    DragMode.RIGHT -> b.right += dx
                    DragMode.TOP -> b.top += dy
                    DragMode.BOTTOM -> b.bottom += dy
                    DragMode.TOP_LEFT -> { b.left += dx; b.top += dy }
                    DragMode.TOP_RIGHT -> { b.right += dx; b.top += dy }
                    DragMode.BOTTOM_LEFT -> { b.left += dx; b.bottom += dy }
                    DragMode.BOTTOM_RIGHT -> { b.right += dx; b.bottom += dy }
                }
                expected = normalize(b)
                return true
            }
        }
        return true
    }

    /** 把任意拖拽结果整理成合法框：边可互换、最小尺寸 12%、整体不越界。 */
    private fun normalize(r: RectF): RectF {
        val left = minOf(r.left, r.right)
        val right = maxOf(r.left, r.right)
        val top = minOf(r.top, r.bottom)
        val bottom = maxOf(r.top, r.bottom)
        val w = (right - left).coerceIn(0.12f, 1f)
        val h = (bottom - top).coerceIn(0.12f, 1f)
        val l = left.coerceIn(0f, 1f - w)
        val t = top.coerceIn(0f, 1f - h)
        return RectF(l, t, l + w, t + h)
    }

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

    /**
     * 手动校准过的框是固定取景参考：只画黄色标准框，不画实时检测框。
     * 人脸有效性检查仍照常运行（提示与画框互不影响）。
     */
    var hideLive: Boolean = false
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
    private val editStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        color = Color.YELLOW
    }
    private val editFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
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
        if (editing) {
            drawEditBox(canvas)
            drawWarnings(canvas)
            return
        }
        animateBox()
        val exp = expected
        val act = drawn
        if (exp != null) {
            val r = map(exp)
            canvas.drawRect(r, outline)
            if (hideLive) {
                // 手动框 = 固定取景参考：黄色，且不再画实时框
                canvas.drawRect(r, editStroke)
            } else {
                fill.color = if (act != null && close(r, map(act))) Color.GREEN else Color.GRAY
                canvas.drawRect(r, fill)
            }
        }
        if (act != null && !hideLive) {
            val r = map(act)
            canvas.drawRect(r, outline)
            fill.color = Color.WHITE
            canvas.drawRect(r, fill)
        }
        drawWarnings(canvas)
    }

    /** 手动校准时的框：黄色，四角带手柄，附操作提示。 */
    private fun drawEditBox(canvas: Canvas) {
        val box = expected ?: return
        val r = map(box)
        canvas.drawRect(r, outline)
        canvas.drawRect(r, editStroke)
        // 四角 + 四边中点：角改两条边，边只改自己
        val handle = 9f * density
        val midX = r.centerX()
        val midY = r.centerY()
        for (x in floatArrayOf(r.left, midX, r.right)) {
            for (y in floatArrayOf(r.top, midY, r.bottom)) {
                if (x == midX && y == midY) continue
                canvas.drawRect(x - handle, y - handle, x + handle, y + handle, editFill)
            }
        }
        val hint = "拖角改两边 · 拖边改一边 · 双指缩放"
        val baseline = (r.top - 10f * density).coerceAtLeast(warningTextSize)
        canvas.drawText(hint, r.centerX(), baseline, textStroke)
        canvas.drawText(hint, r.centerX(), baseline, textFill)
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
