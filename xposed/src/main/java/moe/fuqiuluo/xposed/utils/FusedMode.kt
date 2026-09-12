package moe.fuqiuluo.xposed.utils

/**
 * **融合定位（fused provider）的处置方式** —— 三态互斥，取代原来的布尔开关
 * `disableFusedLocation`（那个开关只能表达"拒绝 / 不拒绝"，说不出"放行但不干预"与
 * "让它跑、但结果换成我们的"这两种完全不同的语义）。
 *
 * 三态的含义（设置页是互斥滑块）：
 *  · [REJECT]  拒绝：把 fused 报成系统不可用（`isProviderEnabled("fused") == false`）+
 *              拦掉它的 `sendExtraCommand`/批量注册。应用会退回 gps/network 这条我们能喂的路。
 *  · [ALLOW]   放行（**不推荐**）：完全不碰融合结果。融合引擎用自己的 WiFi/基站/GNSS 输入
 *              算出来的位置会**原样交给应用** —— 就是历史上的"位置被拉回"。
 *  · [DISGUISE] 伪装（**推荐/默认**）：让融合照常跑，但它的结果在出口被改写成模拟位置
 *              —— 即「拦截-修改-转发」，不凭空生成（见 `BaseLocationHook.injectLocation`）。
 *
 * 默认值取 [DISGUISE]：在**检测到融合定位**的设备上，"让它跑、改写它的结果"比"把系统能力
 * 报成不可用"更自然（真机上 fused 通常是启用的，"报不可用"本身就是一种可观察差异）。
 */
object FusedMode {
    /** 拒绝 */
    const val REJECT = 0

    /** 放行（不推荐） */
    const val ALLOW = 1

    /** 伪装（推荐） */
    const val DISGUISE = 2

    const val DEFAULT = DISGUISE

    /** 非法值一律回到默认，绝不把脏值传进决策 */
    fun sanitize(mode: Int): Int = if (mode in REJECT..DISGUISE) mode else DEFAULT

    fun label(mode: Int): String = when (sanitize(mode)) {
        REJECT -> "拒绝"
        ALLOW -> "放行（不推荐）"
        else -> "伪装"
    }
}
