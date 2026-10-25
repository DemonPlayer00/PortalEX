package moe.fuqiuluo.portalex.service

import android.content.Context
import android.location.LocationManager
import moe.fuqiuluo.portalex.ext.StaminaPrefs
import moe.fuqiuluo.portalex.ext.cadenceMock
import moe.fuqiuluo.portalex.ext.orientationMock
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.ui.camera.CammockManager
import moe.fuqiuluo.portalex.ui.camera.cameraMockEnabled

/**
 * 功能依赖：路线（位置）是二类功能的唯一依赖；GNSS 只服务自身/直接对接 GNSS 的功能。
 * 二类（基带/摄像头/体力/步频/朝向）只保存"想开"的意图，路线启用后统一落地。
 */
object FeatureDependency {
    fun isRouteActive(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { MockServiceHelper.isMockStart(lm) }.getOrDefault(false)
    }

    fun isGnssActive(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { MockServiceHelper.isGnssMockStart(lm) }.getOrDefault(false)
    }

    fun isMasterActive(ctx: Context): Boolean = isRouteActive(ctx) || isGnssActive(ctx)

    /** 一类停止后：二类意图保留，但实际注入先停掉，避免脱离总开关继续运行。 */
    fun suspendClass2(ctx: Context, stopCamera: Boolean = false) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { MockServiceHelper.setSimConfig(lm, "cell", false, "", "") }
        runCatching { MockServiceHelper.setSimConfig(lm, "wifi", false, "", "") }
        runCatching { ConfigSync.setSensorMock(ctx, lm, false, false) }
        if (stopCamera) {
            runCatching { moe.fuqiuluo.portalex.ui.camera.CameraMockLifecycle.stopForLocationMockOff(ctx) }
        }
    }

    /** 一类启动后，把二类里"预先打开"的意图一次性落地。 */
    fun applyPending(ctx: Context): Boolean {
        if (!isRouteActive(ctx)) return false
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        if (!MockServiceHelper.isServiceInit()) return false
        val sp = ctx.sharedPrefs
        if (sp.getBoolean("sim_cell_enabled", false)) {
            MockServiceHelper.setSimConfig(
                lm, "cell", true,
                sp.getString("sim_cell_v1", "460/00").orEmpty(),
                sp.getString("sim_cell_v2", "123456789/1234").orEmpty(),
            )
        }
        if (sp.getBoolean("sim_wifi_enabled", false)) {
            MockServiceHelper.setSimConfig(
                lm, "wifi", true,
                sp.getString("sim_wifi_v1", "CMCC-EDU").orEmpty(),
                sp.getString("sim_wifi_v2", "02:00:00:00:00:00").orEmpty(),
            )
        }
        runCatching { ConfigSync.setSensorMock(ctx, lm, ctx.cadenceMock, ctx.orientationMock) }
        runCatching {
            val stamina = StaminaPrefs.load(ctx)
            if (stamina.enabled) ConfigSync.push(ctx, lm)
        }
        return true
    }
}
