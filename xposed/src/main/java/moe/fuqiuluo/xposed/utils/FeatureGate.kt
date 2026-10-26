package moe.fuqiuluo.xposed.utils

/**
 * system_server 侧的二类功能总闸。
 *
 * 统一只认「路线/位置模拟是否运行」这一个事实源；App 侧的开关只表达意图，
 * 真正的 hook 是否生效必须经过这里，避免脱离开关后残留注入。
 * cammock 不走该闸门，cammock 由 App 侧严格绑定路线。
 */
object FeatureGate {
    fun isRouteActive(): Boolean = FakeLoc.enable

    fun isClass2Enabled(): Boolean = isRouteActive()

    fun isGnssHookActive(): Boolean = isRouteActive() && FakeLoc.enableMockGnss

    fun isWifiHookActive(): Boolean = isRouteActive()
}
