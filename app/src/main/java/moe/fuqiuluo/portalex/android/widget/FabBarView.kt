package moe.fuqiuluo.portalex.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import moe.fuqiuluo.portalex.R

/**
 * 悬浮胶囊条：全应用单实例（挂在 Activity 布局中，跨 Fragment 存续）。
 *
 * 设计目标：不同界面共用一个实例，页面切换时仅统一切换功能集
 * （[setActions]），展开/收起状态由本实例唯一持有——彻底消灭
 * 跨页面状态不统一（假打开/半开假象/位置错乱）问题。
 *
 * 形态：收起 = 60dp 正圆（仅展开主按钮），展开 = 全宽胶囊；由
 * outline 圆角矩形裁剪实现（右端始终圆角），内容零拉伸。
 */
class FabBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** 单个功能按钮定义；[active] 控制选中态着色（绿色），可在点击后变更并刷新 */
    class Action(
        val iconRes: Int,
        val contentDescription: String?,
        var active: Boolean = false,
        val onClick: () -> Unit
    )

    private val density = resources.displayMetrics.density

    /** 展开主按钮（旋转 90° 表示展开态） */
    private val expandButton: ImageButton

    /** 功能按钮容器（切换功能集时动态重建） */
    private val actionsContainer: LinearLayout

    private var mOpened = false

    /** outline 裁剪宽度（收起 = 60dp 正圆，展开 = 容器全宽） */
    private var clipWidth = 0

    init {
        orientation = HORIZONTAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = ContextCompat.getDrawable(context, R.drawable.fab_expand_bar_bg)

        expandButton = ImageButton(context).apply {
            layoutParams = LayoutParams(dp(48), dp(48))
            contentDescription = context.getString(R.string.fab_expand)
            background = rippleBackground()
            setImageResource(R.drawable.baseline_keyboard_arrow_up_24)
            imageTintList = iconTintList()
            setOnClickListener { toggle() }
        }
        addView(expandButton)

        actionsContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        addView(actionsContainer)

        // outline 圆角矩形裁剪：右端始终圆角，收起态即正圆；
        // elevation 阴影跟随 outline 形状（圆形/胶囊）突出悬浮存在感
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, clipWidth.coerceIn(0, v.width), v.height, dpF(30f))
            }
        }
        clipToOutline = true
        elevation = dpF(6f)
        resetClosed()
    }

    /** 当前展开状态（单实例唯一来源） */
    val isOpened: Boolean get() = mOpened

    /** 收起态可见外廓的边长（60dp 正方形）。页面做避让时用它算按钮占多大 */
    val collapsedSize: Int get() = collapsedWidth()

    /**
     * 统一切换功能集：空集 = 隐藏胶囊；非空 = 重建功能按钮并重置为
     * 干净收起态（新页面一律从收起开始，不残留上一页面的展开状态）。
     */
    fun setActions(actions: List<Action>) {
        actionsContainer.removeAllViews()
        actions.forEach { action ->
            actionsContainer.addView(
                ImageButton(context).apply {
                    layoutParams = LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) }
                    contentDescription = action.contentDescription
                    background = rippleBackground()
                    setImageResource(action.iconRes)
                    imageTintList = actionTintList(action.active)
                    setOnClickListener { action.onClick() }
                }
            )
        }
        visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        resetClosed()
        requestLayout()
    }

    /**
     * 按 [actions] 的序号刷新按钮着色（不改变展开/收起态）——用于开关型功能按钮
     * （如路线平滑绘制）点击后立即反映选中态，避免重建功能集导致胶囊意外收起。
     */
    fun refreshActionTints(actions: List<Action>) {
        for (i in 0 until actionsContainer.childCount) {
            val action = actions.getOrNull(i) ?: continue
            (actionsContainer.getChildAt(i) as? ImageButton)?.imageTintList =
                actionTintList(action.active)
        }
    }

    /**
     * 旋转后重新摆一次位置：胶囊与「地图控件（内容区）左下角」的距离固定为
     * [R.dimen.fab_corner_margin]（无限定符，本机 560dpi 下 = 56px）。
     *
     * 为何需要：Activity 声明了 configChanges，旋转时不会重建也不会重新 inflate
     * 布局 —— XML 里写下的 layout_margin* 只能停在首次 inflate 那一刻的值，
     * 旋转后不会自己重算。这里每次旋转都显式重设一次，把位置钉住。
     */
    fun recalibrate() {
        val margin = resources.getDimensionPixelSize(R.dimen.fab_corner_margin)
        val lp = layoutParams as? MarginLayoutParams ?: return
        if (lp.marginStart == margin && lp.bottomMargin == margin) return
        lp.marginStart = margin
        lp.bottomMargin = margin
        layoutParams = lp
    }

    /** 展开：主按钮旋转 90° + outline 裁剪窗口向右生长 */
    fun open() {
        if (mOpened) return
        mOpened = true
        expandButton.isClickable = false
        expandButton.animate()
            .rotation(90f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
        forEachAction {
            it.visibility = View.VISIBLE
            it.isEnabled = true
        }
        animateClip(width, 220, DecelerateInterpolator())
        postDelayed({ expandButton.isClickable = true }, 240)
    }

    /** 收起：主按钮旋转复位 + outline 向左收回；功能按钮 INVISIBLE（占位、点击透视） */
    fun close() {
        if (!mOpened) return
        mOpened = false
        expandButton.isClickable = false
        expandButton.animate()
            .rotation(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
        forEachAction {
            it.visibility = View.INVISIBLE
            it.isEnabled = false
        }
        animateClip(collapsedWidth(), 200, AccelerateInterpolator())
        postDelayed({ expandButton.isClickable = true }, 220)
    }

    private fun toggle() {
        if (mOpened) close() else open()
    }

    /** 重置为干净收起态（切换功能集 / 初次创建时） */
    private fun resetClosed() {
        mOpened = false
        expandButton.clearAnimation()
        expandButton.rotation = 0f
        expandButton.isClickable = true
        clipWidth = collapsedWidth()
        invalidateOutline()
        forEachAction {
            it.visibility = View.INVISIBLE
            it.isEnabled = false
        }
    }

    private fun forEachAction(block: (View) -> Unit) {
        for (i in 0 until actionsContainer.childCount) {
            block(actionsContainer.getChildAt(i))
        }
    }

    private fun animateClip(toWidth: Int, duration: Long, interpolator: Interpolator) {
        val from = clipWidth
        val animator = ValueAnimator.ofInt(from, toWidth)
        animator.duration = duration
        animator.interpolator = interpolator
        animator.addUpdateListener { a ->
            clipWidth = a.animatedValue as Int
            invalidateOutline()
        }
        animator.start()
    }

    private fun collapsedWidth(): Int = dp(60)

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun dpF(v: Float): Float = v * density

    /** 涟漪背景（?selectableItemBackgroundBorderless） */
    private fun rippleBackground(): Drawable {
        val tv = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, tv, true
        )
        return ContextCompat.getDrawable(context, tv.resourceId)
            ?: ColorDrawable(Color.TRANSPARENT)
    }

    /** 图标颜色（?portalFabIconTint：浅/深主题各设 colorOnPrimaryContainer 对应值） */
    private fun iconTintList(): ColorStateList {
        // MaterialColors.getColor 同处理「直接色值（TYPE_INT_COLOR）」与
        // 「颜色资源引用（TYPE_REFERENCE）」——主题里配的是直接色值，
        // resourceId 为 0，直接 getColor(0) 会崩，不能那样取
        val color = MaterialColors.getColor(context, R.attr.portalFabIconTint, Color.LTGRAY)
        return ColorStateList.valueOf(color)
    }

    /** 按钮着色：选中态 = 绿色，普通 = 主题图标色 */
    private fun actionTintList(active: Boolean): ColorStateList =
        if (active) ColorStateList.valueOf(ACTIVE_TINT) else iconTintList()

    companion object {
        /** 选中态着色（平滑绘制开关 / 平滑线段颜色同源） */
        val ACTIVE_TINT: Int = Color.rgb(0, 200, 83)
    }
}
