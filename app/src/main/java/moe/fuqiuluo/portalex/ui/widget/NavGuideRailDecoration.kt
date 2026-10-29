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
        val anchors = collectAnchors(parent)
        if (anchors.isEmpty()) return

        density = parent.resources.displayMetrics.density
        val color = if (activeProvider?.invoke() == true) COLOR_ACTIVE else COLOR_IDLE
        railPaint.strokeWidth = 2f * density
        // 左侧留出呼吸空间：蚂蚁线不贴屏幕/抽屉边缘
        val railX = 12f * density
        val stubEnd = 19f * density
        val step = 9f * parent.resources.displayMetrics.density
        val radius = 1.7f * parent.resources.displayMetrics.density

        railPaint.color = color
        dotPaint.color = color

        // 圆点按菜单项位置对齐：每个条目中心必有一个点，条目之间按步长均分补点。
        // 这样支线永远正对着一个圆点，不需要任何“接上去”的补线。
        val dotYs = ArrayList<Float>(anchors.size * 4)
        anchors.forEachIndexed { index, anchor ->
            val y = anchor.centerY()
            if (index == 0) {
                dotYs.add(y)
            } else {
                val prev = anchors[index - 1].centerY()
                val gap = y - prev
                val parts = max(1, round(gap / step).toInt())
                for (k in 1..parts) dotYs.add(prev + gap * k / parts)
            }
        }

        // 缺口只在两根支线之间的点里轮转：连接处始终可见，动画依旧一次只藏一个点
        val freeDots = dotYs.indices.filter { i ->
            anchors.none { abs(it.centerY() - dotYs[i]) < 0.5f }
        }
        val hidden = if (freeDots.isEmpty()) {
            -1
        } else {
            freeDots[((SystemClock.uptimeMillis() / 140L) % freeDots.size).toInt()]
        }
        dotYs.forEachIndexed { i, y ->
            if (i == hidden) return@forEachIndexed
            c.drawCircle(railX, y, radius, dotPaint)
        }
        if (parent.isShown) parent.postInvalidateOnAnimation()

        // 每个菜单项一条短支线；端点：起点圆点，二类功能用朝右的等边三角形
        anchors.forEachIndexed { index, anchor ->
            val itemY = anchor.centerY()
            c.drawLine(railX, itemY, stubEnd, itemY, railPaint)
            val title = titles.getOrNull(index)
            if (title != null && title in class2Titles) {
                drawArrowHead(c, stubEnd, itemY, dotPaint)
            } else {
                c.drawCircle(stubEnd, itemY, 2.2f * parent.resources.displayMetrics.density, dotPaint)
            }
        }
    }

    private var titles: List<String> = emptyList()

    /** 收集从起点到终点的菜单项在列表坐标系里的纵向范围。 */
    private fun collectAnchors(parent: RecyclerView): List<RectF> {
        val rows = ArrayList<Pair<String, RectF>>()
        for (i in 0 until parent.childCount) {
            val item = parent.getChildAt(i)
            val title = titleOf(item) ?: continue
            rows += title to RectF(0f, item.top.toFloat(), 0f, item.bottom.toFloat())
        }
        val from = rows.indexOfFirst { it.first == startTitle }
        val to = rows.indexOfFirst { it.first == endTitle }
        if (from < 0 || to < 0 || to < from) return emptyList()
        titles = rows.subList(from, to + 1).map { it.first }
        return rows.subList(from, to + 1).map { it.second }
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
