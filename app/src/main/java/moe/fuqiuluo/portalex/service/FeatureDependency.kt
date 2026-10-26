package moe.fuqiuluo.portalex.service

import android.content.Context
import android.location.LocationManager

/**
 * App 侧只保留一个事实查询：路线/位置模拟是否运行。
 *
 * 二类功能的实际 hook 由 system_server 的 FeatureGate 统一按同一事实源判断；
 * 这里不再保存“预启用”状态，也不再代替系统侧执行依赖。
 * cammock 是唯一仍需 App 侧严格前置检查的特例。
 */
object FeatureDependency {
    fun isRouteActive(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { MockServiceHelper.isMockStart(lm) }.getOrDefault(false)
    }
}
