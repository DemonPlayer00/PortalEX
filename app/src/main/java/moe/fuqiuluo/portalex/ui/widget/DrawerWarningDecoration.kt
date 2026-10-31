package moe.fuqiuluo.portalex.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.MaterialShapeDrawable

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
        strokeWidth = 1f
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)
        menuView = parent
        if (warningTitles.isEmpty()) return
        val density = parent.resources.displayMetrics.density
        paint.strokeWidth = 1f * density
        // 菜单项自身有左右留白；警告框比 item 更窄、略向外扩高，避免“过宽、过矮”。
        val insetX = 8f * density
        val outsetY = 4f * density
        val fallbackRadius = 24f * density
        val outline = android.graphics.Outline()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (titleOf(child) !in warningTitles) continue
            // 直接复用菜单项背景的 MaterialShapeDrawable 形状（含胶囊圆角）；
            // 只改描边，不自己猜圆角半径。
            val shape = materialShapeOf(child.background)
            if (shape != null) {
                val border = MaterialShapeDrawable(shape.shapeAppearanceModel).apply {
                    setStroke(1f * density, Color.parseColor("#FFFFC107"))
                    fillColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                    bounds = Rect(
                        insetX.toInt(),
                        -outsetY.toInt(),
                        child.width - insetX.toInt(),
                        child.height + outsetY.toInt(),
                    )
                }
                c.save()
                c.translate(child.left.toFloat(), child.top.toFloat())
                border.draw(c)
                c.restore()
            } else {
                child.background?.getOutline(outline)
                val radius = outline.radius.takeIf { it > 0f } ?: fallbackRadius
                val rect = RectF(
                    child.left + insetX,
                    child.top - outsetY,
                    child.right - insetX,
                    child.bottom + outsetY,
                )
                c.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private fun materialShapeOf(drawable: Drawable?): MaterialShapeDrawable? = when (drawable) {
        is MaterialShapeDrawable -> drawable
        is RippleDrawable -> (0 until drawable.numberOfLayers)
            .firstNotNullOfOrNull { materialShapeOf(drawable.getDrawable(it)) }
        is LayerDrawable -> (0 until drawable.numberOfLayers)
            .firstNotNullOfOrNull { materialShapeOf(drawable.getDrawable(it)) }
        else -> null
    }

    private fun titleOf(view: View): String? = when (view) {
        is TextView -> view.text?.toString()?.takeIf { it.isNotBlank() }
        is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { titleOf(view.getChildAt(it)) }
        else -> null
    }
}
