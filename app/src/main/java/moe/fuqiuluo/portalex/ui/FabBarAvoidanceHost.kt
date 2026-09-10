package moe.fuqiuluo.portalex.ui

/**
 * 页面内容需要给悬浮胶囊（`FabBarView`）让位时实现。
 *
 * 胶囊是 Activity 级单实例、钉在左下角；而 MainActivity 声明了
 * `configChanges="orientation|screenSize"`，旋转时页面布局不会重新 inflate ——
 * 让位用的边距只能停在首次 inflate 那一刻的取值。所以由 Activity 在
 * onConfigurationChanged 里通知当前页面重算一次。
 */
interface FabBarAvoidanceHost {

    /** 按当前屏幕方向重算让位边距（竖屏让底部，横屏让左侧） */
    fun avoidFabBar()
}
