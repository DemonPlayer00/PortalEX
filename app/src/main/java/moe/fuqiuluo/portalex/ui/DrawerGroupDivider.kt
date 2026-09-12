package moe.fuqiuluo.portalex.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 在抽屉菜单里**标题为 [targetTitle] 的那一项上方**画一条细分隔线（1dp），并把它的上下留白
 * 一起做出来（[marginTopPx] / [marginBottomPx]）——用来把"日常入口"和"工具页"两组分开。
 *
 * 为什么用 ItemDecoration 而不是别的写法：
 *  · 菜单 XML 没有"组间距/组分隔"这种属性；
 *  · 用 subheader 占位 ⇒ 只能得到整整一个菜单项（48dp）的高度，太大；
 *  · 拆成两个 NavigationView ⇒ 抽屉里出现**两个各自滚动的列表**（实测反馈，必须避免）；
 *  · 画在 item 边界上则保持单列表；留白由 `getItemOffsets` 让出"线 + 上下 margin"的高度。
 *
 * 为什么按**标题**而不是 adapter 下标定位：presenter 的 adapter 里额外占了一个 header 槽位
 * （NavigationView 总会塞一个 header 容器），按 `菜单下标 + 1` 这类推算错一位就会把线画到
 * 上一项上（实测发生过）。按标题匹配与槽位无关。
 */
class DrawerGroupDivider(
    private val targetTitle: CharSequence,
    private val insetPx: Int,
    private val marginTopPx: Int,
    private val marginBottomPx: Int,
    private val thicknessPx: Int,
    color: Int,
) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply { this.color = color }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        if (isTarget(view)) {
            // 为该"线 + 上下 margin"腾出高度：线之后画在 marginBottom 之上
            outRect.top = marginTopPx + thicknessPx + marginBottomPx
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val child = (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .firstOrNull { isTarget(it) } ?: return
        val bottom = (child.top - marginBottomPx).toFloat()
        val top = bottom - thicknessPx
        c.drawRect(
            insetPx.toFloat(),
            top,
            (parent.width - insetPx).toFloat(),
            bottom,
            paint,
        )
    }

    /** 该项（含子视图）的文本是否等于 [targetTitle] */
    private fun isTarget(view: View): Boolean = findText(view) == targetTitle

    private fun findText(view: View): CharSequence? = when (view) {
        is TextView -> view.text
        is ViewGroup -> (0 until view.childCount)
            .mapNotNull { findText(view.getChildAt(it)) }
            .firstOrNull { it.isNotBlank() }
        else -> null
    }
}
