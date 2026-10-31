package moe.fuqiuluo.portalex.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 抽屉菜单里的引导线（蚂蚁线）：从「路线模拟」竖直拉到「角度与指南针模拟」，
 * 每个菜单项再伸出一小段支线。
 *
 * 关键变化：它现在是菜单 RecyclerView 的 [RecyclerView.ItemDecoration]，
 * **属于入口列表本身**，随菜单项一起滚动、一起被列表边界裁剪；
 * 不再是叠在侧栏表面的固定 View。
 *
 * 语义：路线模拟是总开关——打开它，中间的模拟项才会跟着启动。
 * 关闭时灰色，启用时蓝色；颜色由 [activeProvider] 每次绘制时回读。
 */
class NavGuideRailDecoration(
    private val startTitle: String,
    private val endTitle: String,
    /** 二类功能（随路线模拟启动的那几项）：支线末端画三角而不是圆点。 */
    private val class2Titles: Set<String>,
) : RecyclerView.ItemDecoration() {

    private var density = 1f

    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 每次绘制都问一次当前是否激活；真实状态在 system_server。 */
    var activeProvider: (() -> Boolean)? = null

    private var menuView: RecyclerView? = null

    fun invalidate() {
        menuView?.invalidate()
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)
        menuView = parent
        val density = parent.resources.displayMetrics.density
        this.density = density
        val rows = collectRows(parent)
        val scroll = parent.computeVerticalScrollOffset().toFloat()

        // 端点可能在滚动后离开屏幕：用内容坐标缓存，之后按当前滚动量还原。
        rows.firstOrNull { it.first == startTitle }?.let {
            startContentY = it.second.centerY() + scroll
        }
        rows.firstOrNull { it.first == endTitle }?.let {
            endContentY = it.second.centerY() + scroll
        }
        val startY = rows.firstOrNull { it.first == startTitle }?.second?.centerY()
            ?: if (startContentY.isFinite()) startContentY - scroll else Float.NaN
        val endY = rows.firstOrNull { it.first == endTitle }?.second?.centerY()
            ?: if (endContentY.isFinite()) endContentY - scroll else Float.NaN
        if (!startY.isFinite() || !endY.isFinite() || endY < startY) return

        val color = if (activeProvider?.invoke() == true) COLOR_ACTIVE else COLOR_IDLE
        railPaint.strokeWidth = 2f * density
        railPaint.color = color
        dotPaint.color = color
        val railX = 12f * density
        val stubEnd = 19f * density
        val step = 9f * density
        val radius = 1.7f * density

        // 主线一次画完，不依赖端点是否还在可见区域。
        c.drawLine(railX, startY, railX, endY, railPaint)

        // 圆点按固定步长铺满整条线，并额外对齐可见菜单项中心。
        val dotYs = ArrayList<Float>()
        var y = startY
        while (y <= endY) {
            dotYs.add(y)
            y += step
        }
        rows.map { it.second.centerY() }.filter { it in startY..endY }.forEach { center ->
            if (dotYs.none { abs(it - center) < 0.5f }) dotYs.add(center)
        }
        dotYs.sort()
        val hidden = if (dotYs.isEmpty()) -1 else
            ((SystemClock.uptimeMillis() / 140L) % dotYs.size).toInt()
        dotYs.forEachIndexed { i, dotY ->
            if (i != hidden) c.drawCircle(railX, dotY, radius, dotPaint)
        }
        if (parent.isShown) parent.postInvalidateOnAnimation()

        // 支线只画当前可见的菜单项；标题决定是否用箭头。
        rows.forEach { (title, rect) ->
            val itemY = rect.centerY()
            if (itemY !in startY..endY) return@forEach
            c.drawLine(railX, itemY, stubEnd, itemY, railPaint)
            if (title in class2Titles) {
                drawArrowHead(c, stubEnd, itemY, dotPaint)
            } else {
                c.drawCircle(stubEnd, itemY, 2.2f * density, dotPaint)
            }
        }
    }

    private var startContentY = Float.NaN
    private var endContentY = Float.NaN

    /** 当前可见的菜单项（标题 + 屏幕坐标）。 */
    private fun collectRows(parent: RecyclerView): List<Pair<String, RectF>> {
        val rows = ArrayList<Pair<String, RectF>>()
        for (i in 0 until parent.childCount) {
            val item = parent.getChildAt(i)
            val title = titleOf(item) ?: continue
            rows += title to RectF(0f, item.top.toFloat(), 0f, item.bottom.toFloat())
        }
        return rows
    }

    private fun titleOf(view: View): String? = when (view) {
        is TextView -> view.text?.toString()?.takeIf { it.isNotBlank() }
        is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { titleOf(view.getChildAt(it)) }
        else -> null
    }

    /** 朝右的等边三角形：底边贴着支线末端，顶点在右侧。 */
    private fun drawArrowHead(c: Canvas, x: Float, y: Float, paint: Paint) {
        val side = 4.6f * density
        val half = side / 2f
        val height = side * sqrt(3f) / 2f
        val path = Path()
        path.moveTo(x, y - half)
        path.lineTo(x, y + half)
        path.lineTo(x + height, y)
        path.close()
        c.drawPath(path, paint)
    }

    private companion object {
        val COLOR_IDLE = Color.parseColor("#8A8A8A")
        val COLOR_ACTIVE = Color.parseColor("#8CBAF2")
    }
}
