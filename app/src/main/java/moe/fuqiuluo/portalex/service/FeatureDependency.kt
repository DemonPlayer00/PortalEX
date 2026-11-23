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

    /**
     * 二类功能（GNSS / 基带 / 体力 / 外周传感器）是否**实际在生效**：
     * 用户开关打开了，**且**路线模拟正在运行。
     *
     * 与系统侧 [moe.fuqiuluo.xposed.utils.FeatureGate] 同一事实源：这些功能允许"假开启"
     * （开关先记住意图），真正注入要等路线模拟启动。高亮卡片必须用这个结果着色，
     * 否则会出现"卡片亮着、其实什么都没注入"的误导。
     */
    fun isClass2Running(ctx: Context, enabled: Boolean): Boolean =
        enabled && isRouteActive(ctx)
}
