package moe.fuqiuluo.portalex.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 侧栏入口警告框：按菜单标题给未就绪的入口画黄色线框。
 *
 * 用标题而不是 adapter position，菜单顺序变化时不需要同步改代码。
 */
class DrawerWarningDecoration : RecyclerView.ItemDecoration() {

    @Volatile var warningTitles: Set<String> = emptySet()
        set(value) {
            field = value
            menuView?.invalidate()
        }

    private var menuView: RecyclerView? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFFFC107")
        strokeWidth = 2f
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)
        menuView = parent
        if (warningTitles.isEmpty()) return
        val density = parent.resources.displayMetrics.density
        paint.strokeWidth = 1.8f * density
        val insetX = 8f * density
        val insetY = 5f * density
        val radius = 8f * density
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (titleOf(child) !in warningTitles) continue
            val rect = RectF(
                child.left + insetX,
                child.top + insetY,
                child.right - insetX,
                child.bottom - insetY,
            )
            c.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun titleOf(view: View): String? = when (view) {
        is TextView -> view.text?.toString()?.takeIf { it.isNotBlank() }
        is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { titleOf(view.getChildAt(it)) }
        else -> null
    }
}
