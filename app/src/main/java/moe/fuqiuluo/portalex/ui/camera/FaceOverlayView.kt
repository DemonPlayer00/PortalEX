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
    var actual: RectF? = null
        set(value) { field = value; invalidate() }

    /** 分析帧的宽高比；归一化框必须按同一宽高比映射到 View，否则会被拉长。 */
    var sourceAspect: Float = 1f
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 14f; color = Color.BLACK
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
        return RectF(
            c.left + r.left * c.width(),
            c.top + r.top * c.height(),
            c.left + r.right * c.width(),
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val exp = expected
        val act = actual
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
    }
}
