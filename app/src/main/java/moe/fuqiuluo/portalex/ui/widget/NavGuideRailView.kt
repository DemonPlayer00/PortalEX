package moe.fuqiuluo.portalex.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView

/**
 * 抽屉里的一条引导线（蚂蚁线）：从「路线模拟」竖直拉到「角度与指南针模拟」，
 * 再向这条路径上的每个菜单项各伸出一小段支线。
 *
 * 语义：路线模拟是**总开关** —— 打开它，中间的模拟项才会跟着启动。
 * 所以关闭时画灰色，启用时画蓝色，用颜色本身提示这层依赖关系。
 *
 * 画在 NavigationView 之上、不接收触摸；菜单滚动或条目高度变化时按当前布局重算。
 */
class NavGuideRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    /** 起点/终点菜单项标题；null 表示还没绑定菜单。 */
    private var startTitle: String? = null
    private var endTitle: String? = null
    private var navView: NavigationView? = null
    /** 二类功能（随路线模拟启动的那几项）：它们的支线末端画三角而不是圆点。 */
    private var class2Titles: Set<String> = emptySet()

    /** 路线模拟是否已启用；决定线的颜色。 */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun bind(nav: NavigationView, startItemId: Int, endItemId: Int, class2ItemIds: List<Int> = emptyList()) {
        navView = nav
        startTitle = nav.menu.findItem(startItemId)?.title?.toString()
        endTitle = nav.menu.findItem(endItemId)?.title?.toString()
        class2Titles = class2ItemIds.mapNotNull { nav.menu.findItem(it)?.title?.toString() }.toSet()
        invalidate()
    }

    /** 从 NavigationView 里找菜单列表（NavigationMenuView 本身是 RecyclerView）。 */
    private fun menuList(root: ViewGroup): RecyclerView? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is RecyclerView) return child
            if (child is ViewGroup) menuList(child)?.let { return it }
        }
        return null
    }

    private fun titleOf(item: View): String? {
        if (item is TextView) return item.text?.toString()
        if (item is ViewGroup) {
            for (i in 0 until item.childCount) {
                titleOf(item.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** 收集从起点到终点的菜单项在**本 View 坐标系**里的位置。 */
    private fun collectAnchors(): List<RectF> {
        val nav = navView ?: return emptyList()
        val list = menuList(nav) ?: return emptyList()
        val start = startTitle ?: return emptyList()
        val end = endTitle ?: return emptyList()
        val rows = ArrayList<Pair<String, RectF>>()
        val origin = IntArray(2)
        getLocationInWindow(origin)
        for (i in 0 until list.childCount) {
            val item = list.getChildAt(i)
            val title = titleOf(item) ?: continue
            val pos = IntArray(2)
            item.getLocationInWindow(pos)
            rows += title to RectF(
                (pos[0] - origin[0]).toFloat(),
                (pos[1] - origin[1]).toFloat(),
                (pos[0] - origin[0]).toFloat(),
                (pos[1] - origin[1]).toFloat() + item.height
            )
        }
        val from = rows.indexOfFirst { it.first == start }
        val to = rows.indexOfFirst { it.first == end }
        if (from < 0 || to < 0 || to < from) return emptyList()
        anchorTitlesCache = rows.subList(from, to + 1).map { it.first }
        return rows.subList(from, to + 1).map { it.second }
    }

    /** 朝右的等边三角形：底边贴着支线末端，顶点在右侧。 */
    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float) {
        val side = 4.6f * density
        val half = side / 2f
        val height = side * kotlin.math.sqrt(3f) / 2f
        val path = android.graphics.Path()
        path.moveTo(x, y - half)
        path.lineTo(x, y + half)
        path.lineTo(x + height, y)
        path.close()
        canvas.drawPath(path, dotPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val anchors = collectAnchors()
        if (anchors.isEmpty()) return

        val color = if (active) COLOR_ACTIVE else COLOR_IDLE
        val railX = 6f * density
        val stubEnd = 13f * density
        val top = anchors.first().centerY()
        val bottom = anchors.last().centerY()

        railPaint.color = color
        dotPaint.color = color
        // 《传送门》式圆点：等距排开，同一时刻只隐藏一个点，缺口从下往上走
        val step = 9f * density
        val radius = 1.7f * density
        val count = (((bottom - top) / step).toInt() + 1).coerceAtLeast(1)
        val tick = SystemClock.uptimeMillis() / 140L
        // 缺口从上往下走（与上一版相反）
        val hidden = (tick % count).toInt()
        for (i in 0 until count) {
            if (i == hidden) continue
            canvas.drawCircle(railX, top + i * step, radius, dotPaint)
        }
        if (isShown) postInvalidateOnAnimation()

        // 每个菜单项一条短支线；端点：起点圆点，二类功能用朝右的等边三角形
        val titles = anchorTitles()
        anchors.forEachIndexed { index, anchor ->
            val itemY = anchor.centerY()
            canvas.drawLine(railX, itemY, stubEnd, itemY, railPaint)
            val title = titles.getOrNull(index)
            if (title != null && title in class2Titles) {
                drawArrowHead(canvas, stubEnd, itemY)
            } else {
                canvas.drawCircle(stubEnd, itemY, 2.2f * density, dotPaint)
            }
        }
    }

    private var anchorTitlesCache: List<String> = emptyList()

    private fun anchorTitles(): List<String> = anchorTitlesCache

    private companion object {
        val COLOR_IDLE = Color.parseColor("#8A8A8A")
        val COLOR_ACTIVE = Color.parseColor("#8CBAF2")
    }
}