package moe.fuqiuluo.portalex.ui.widget

import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.util.WeakHashMap

/**
 * 功能页"高亮卡片"的**着色强度**：用颜色一眼区分"这个功能到底开没开"。
 *
 *  · 未启用（轻着色）：主色 12% 罩在页面底色上 —— 看得出这是主控区，但不会和运行态混为一谈；
 *  · 已启用（重着色）：`colorPrimaryContainer` —— 整块色块进入"强调"档。
 *
 * 两种颜色都来自主题角色 ⇒ 莫奈取色（Android 12+）下会一起跟着壁纸变；
 * 文字沿用 `colorOnPrimaryContainer`，在"浅底 + 深字 / 深底 + 浅字"两种状态都成立。
 *
 * 状态切换用 [ValueAnimator] 做**颜色渐变**（不是硬切）：色块"亮起来/暗下去"本身
 * 就是一次状态变化提示。动画细节：
 *  · 首次绑定直接落值，不让页面一进来就表演一次淡入；
 *  · 连续切换时先取消上一段，从当前帧颜色接着走，不会闪回起点；
 *  · 同一目标正在动画时不重启动画（否则反复刷新会把渐变拖成"慢动作"）；
 *  · 系统关闭动画（开发者选项/无障碍）时直接落值。
 *
 * 各页面必须在**同一处状态同步函数**里调用它（例如摄像头页的 updateSwitch、
 * 路线页的 refreshMockSwitch），否则会出现"开关开了但卡片没亮"的状态分叉。
 */
object HeroCardState {

    /** 未启用时的透明度：够看出色相，又不至于像"已经开着了" */
    private const val IDLE_ALPHA = 0.12f

    /** 与 M3 强调动画同一量级：短、快到、不拖沓 */
    private const val DURATION_MS = 240L

    /** 首次绑定过的卡片（首次落值不做动画） */
    private val initialized = WeakHashMap<MaterialCardView, Boolean>()

    /** 正在跑的渐变：卡片 → 动画 + 目标色（用于去重与接力） */
    private val running = WeakHashMap<MaterialCardView, Running>()

    private class Running(val animator: ValueAnimator, val target: Int)

    fun apply(card: MaterialCardView, active: Boolean) {
        val target = colorFor(card, active)
        val first = initialized.put(card, true) == null
        val current = card.cardBackgroundColor?.defaultColor ?: target

        if (first || current == target || !ValueAnimator.areAnimatorsEnabled()) {
            running.remove(card)?.animator?.cancel()
            card.setCardBackgroundColor(target)
            return
        }
        // 同一目标已经在渐变中：不要再起一段（重复刷新会不断重置时长）
        running[card]?.let { if (it.target == target && it.animator.isRunning) return }

        // 接力：取消上一段后从**当前帧颜色**继续，避免闪回起点
        running.remove(card)?.animator?.cancel()
        val from = card.cardBackgroundColor?.defaultColor ?: target
        val animator = ValueAnimator.ofArgb(from, target).apply {
            duration = DURATION_MS
            // M3 standard easing（fast-out-slow-in）
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { card.setCardBackgroundColor(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (running[card]?.animator === animation) running.remove(card)
                }
            })
        }
        running[card] = Running(animator, target)
        animator.start()
    }

    /**
     * 两种状态都返回**不透明**颜色再交给动画。
     *
     * 为什么不直接给一个带 alpha 的色：`ofArgb` 会同时插值 RGB 与 alpha，
     * 从"不透明 primaryContainer"过渡到"12% 主色"时，中途会出现比两端都亮的一帧
     * （半透明浅粉罩在深底上），看起来像闪一下。这里先把 12% 主色**合成到页面底色**上，
     * 两端都是不透明色 ⇒ RGB 单调过渡，视觉上就是"慢慢暗下去/亮起来"。
     */
    private fun colorFor(card: MaterialCardView, active: Boolean): Int =
        if (active) {
            MaterialColors.getColor(
                card, com.google.android.material.R.attr.colorPrimaryContainer
            )
        } else {
            val primary = MaterialColors.getColor(
                card, com.google.android.material.R.attr.colorPrimary
            )
            val surface = MaterialColors.getColor(
                card, com.google.android.material.R.attr.colorSurface
            )
            val overlay = ColorUtils.setAlphaComponent(primary, (IDLE_ALPHA * 255f).toInt())
            ColorUtils.compositeColors(overlay, surface)
        }
}
