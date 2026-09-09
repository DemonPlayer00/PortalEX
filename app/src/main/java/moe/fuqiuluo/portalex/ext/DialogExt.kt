package moe.fuqiuluo.portalex.ext

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R

/**
 * 输入法弹出时让对话框自适应上移，保证底部按钮不被键盘遮挡。
 *
 * 对话框是独立于 Activity 的悬浮窗口，Activity 的 windowSoftInputMode 对它无效；
 * 系统默认的 adjustPan 只在「焦点视图被键盘盖住」时才平移，而对话框里的输入框
 * 通常本来就位于键盘上方，于是对话框纹丝不动、底部按钮被压住。
 * 这里改为主动读取 IME 高度，按需把窗口上移，刚好露出对话框底部；键盘收起后复位。
 * 同时把自定义内容包进 ScrollView：内容超高时内部滚动，取消/保存按钮固定在底部不动。
 *
 * @param imeSource 读取 IME 高度的窗口视图，需铺满屏幕（一般为 Activity 的 decorView）：
 *                  对话框窗口不一定与键盘重叠，系统可能不向它派发 IME inset。
 * @param bottomMarginDp 对话框底部与键盘顶部之间保留的间距。
 * @param contentMaxHeightRatio 内容区最大高度占屏幕高度的比例，超过则内部滚动。
 */
fun Dialog.shiftAboveIme(
    imeSource: View,
    bottomMarginDp: Int = 16,
    contentMaxHeightRatio: Float = 0.5f
) {
    val win = window ?: return
    // 关闭系统默认的平移，避免与手动上移叠加
    win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    // 允许窗口上移到状态栏区域之上：高对话框不会因窗口顶部被钳位而压住底部按钮
    win.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

    val decor = win.decorView
    // 对话框窗口比可见卡片上下各多出一圈背景内边距，必须量卡片本体，否则会多推一截
    val card = decor.findViewById<View>(android.R.id.content) ?: decor
    // 内容包一层 ScrollView：超过上限后内容内部滚动，按钮保持在底部
    wrapContentScrollable(decor, contentMaxHeightRatio)

    val baseY = win.attributes.y
    val bottomMarginPx = (bottomMarginDp * decor.resources.displayMetrics.density).toInt()
    val location = IntArray(2)
    // 无偏移时卡片的屏幕顶部坐标，键盘弹出时测一次，避免与窗口移动的异步时序打架
    var restingTop = -1

    fun applyShift(imeBottom: Int) {
        if (card.height == 0 || imeSource.height == 0) return
        if (imeBottom == 0) {
            // 键盘已收起：复位并丢弃静止位置，下次弹出时重新测量
            restingTop = -1
            if (win.attributes.y != baseY) {
                val lp = win.attributes
                lp.y = baseY
                win.attributes = lp
            }
            return
        }
        imeSource.getLocationOnScreen(location)
        val imeTop = location[1] + imeSource.height - imeBottom
        if (restingTop < 0) {
            card.getLocationOnScreen(location)
            restingTop = location[1] - (win.attributes.y - baseY)
        }
        // 位移上限：卡片顶部不越过屏幕顶部
        val shift = (restingTop + card.height + bottomMarginPx - imeTop)
            .coerceIn(0, restingTop.coerceAtLeast(0))
        val target = baseY - shift
        if (win.attributes.y != target) {
            val lp = win.attributes
            lp.y = target
            win.attributes = lp
        }
    }

    ViewCompat.setOnApplyWindowInsetsListener(imeSource) { _, insets ->
        applyShift(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        insets
    }
    setOnDismissListener {
        ViewCompat.setOnApplyWindowInsetsListener(imeSource, null)
    }
    ViewCompat.requestApplyInsets(imeSource)
}

/**
 * 把 Dialog 的自定义内容（setView 的内容区）包进 ScrollView。
 * 内容自然高度超过上限时，ScrollView 收缩到上限、内容内部滚动；
 * 内容未超限时保持 wrap_content，外观不变。按钮面板（取消/保存）在内容区之外，
 * 始终固定在卡片底部。无自定义内容的对话框（如纯确认框）直接跳过。
 */
private fun Dialog.wrapContentScrollable(decor: View, maxHeightRatio: Float): ScrollView? {
    val custom = decor.findViewById<ViewGroup>(R.id.custom) ?: return null
    val content = custom.getChildAt(0) ?: return null
    val maxH = (custom.resources.displayMetrics.heightPixels * maxHeightRatio).toInt()

    val scroll = ScrollView(custom.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    custom.removeAllViews()
    scroll.addView(content, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    custom.addView(scroll, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    scroll.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
        val h = bottom - top
        val lp = v.layoutParams
        if (h > maxH && lp.height != maxH) {
            lp.height = maxH
            v.layoutParams = lp
        }
    }
    return scroll
}
