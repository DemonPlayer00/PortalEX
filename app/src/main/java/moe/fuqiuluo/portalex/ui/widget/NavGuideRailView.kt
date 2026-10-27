package moe.fuqiuluo.portalex.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
    private val line = Path()

    fun bind(nav: NavigationView, startItemId: Int, endItemId: Int) {
        navView = nav
        startTitle = nav.menu.findItem(startItemId)?.title?.toString()
        endTitle = nav.menu.findItem(endItemId)?.title?.toString()
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
        return rows.subList(from, to + 1).map { it.second }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val anchors = collectAnchors()
        if (anchors.isEmpty()) return

        val color = if (active) COLOR_ACTIVE else COLOR_IDLE
        val railX = 6f * density
        val stubEnd = 20f * density
        val top = anchors.first().centerY()
        val bottom = anchors.last().centerY()

        railPaint.color = color
        dotPaint.color = color
        // 蚂蚁线：虚线相位随时间推移，视觉上在“流动”
        val period = 10f * density
        // 相位递减 ⇒ 虚线从下往上流动（从「角度模拟」流向「路线模拟」）
        val cycle = period * 2f
        val phase = if (isShown) {
            cycle - (SystemClock.uptimeMillis() % 1000L) / 1000f * cycle
        } else {
            0f
        }
        railPaint.pathEffect = DashPathEffect(floatArrayOf(period, period), phase)

        line.reset()
        line.moveTo(railX, top)
        line.lineTo(railX, bottom)
        canvas.drawPath(line, railPaint)

        // 每个菜单项一条短支线 + 端点圆点
        railPaint.pathEffect = null
        for (anchor in anchors) {
            val y = anchor.centerY()
            canvas.drawLine(railX, y, stubEnd, y, railPaint)
            canvas.drawCircle(stubEnd, y, 2.2f * density, dotPaint)
        }

        if (isShown) postInvalidateOnAnimation()
    }

    private companion object {
        val COLOR_IDLE = Color.parseColor("#8A8A8A")
        val COLOR_ACTIVE = Color.parseColor("#2F80ED")
    }
}