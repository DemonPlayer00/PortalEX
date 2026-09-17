package moe.fuqiuluo.portalex.service

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

/**
 * **融合定位的"普通应用视角"探针**：只用通用接口问框架，不碰模块私有通道。
 *
 * ## 为什么必须是通用接口
 *
 * 融合这条路在真机上"有没有、归谁管、开没开"这三件事，**普通应用本来就能问出来** ——
 * `LocationManager` 提供了 provider 名字、实现包名、启用状态与最近一次定位。
 * 拿模块自己的内部状态在 Test 页显示，等于换了一条只有我们看得见的通道：
 * 用户看到的就不再是"这台设备对任何应用的样子"，而是"我们以为的样子"。
 * （与 [StepProbe]、[PortalLocationClient] 同一条纪律：**用真订阅/真查询复现普通应用的所见**。）
 *
 * ## 必须知道的前提
 *
 * PortalEX **自己不在 LSPosed 作用域内**（作用域是 system / phone / fused / oplus.location），
 * 所以本探针读到的是**未经注入改写**的真实框架数据 —— "最近融合 fix"那一行因此是**真值**，
 * 正好可以和 `PortalLocationClient`（被注入改写过的帧）并排对照。
 */
object FusedProbe {

    /** 融合 provider 的规范名（API 31+ 有常量，低版本没有就退回字面量） */
    private val FUSED_NAME: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else "fused"

    private fun modeLabel(mode: Int): String = when (mode) {
        Settings.Secure.LOCATION_MODE_OFF -> "关（0）"
        Settings.Secure.LOCATION_MODE_BATTERY_SAVING -> "省电（2）"
        Settings.Secure.LOCATION_MODE_HIGH_ACCURACY -> "高精度（3）"
        else -> "未知（$mode）"
    }

    /** 多行状态（Test 页原样展示）。任何一步失败都如实写出来，不吞。 */
    fun status(context: Context): String {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "定位服务不可用（LOCATION_SERVICE 为空）"
        val sb = StringBuilder()
        val provider = runCatching { lm.getProvider(FUSED_NAME) }.getOrNull()
        sb.append("融合 provider    ").append(if (provider != null) "有（$FUSED_NAME）" else "无（$FUSED_NAME）").append('\n')
        // 注意：LocationProvider.getPackageName() 在本编译 SDK 上不可见（非公开面），
        // 所以"归谁管"只能从 provider 自身描述 + 系统里装了哪些 fused 包去推 —— 不硬猜。
        val desc = runCatching { provider?.toString() }.getOrNull()
        sb.append("provider 描述    ").append(desc ?: "（框架未提供）").append('\n')
        val enabled = runCatching { lm.isProviderEnabled(FUSED_NAME) }.getOrDefault(false)
        sb.append("provider 启用    ").append(if (enabled) "是" else "否").append('\n')
        val mode = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, -1)
        }.getOrDefault(-1)
        sb.append("位置总开关       ").append(modeLabel(mode)).append('\n')
        sb.append("全部 provider    ").append(
            runCatching { lm.allProviders.joinToString(", ") }.getOrDefault("（读取失败）")
        ).append('\n')
        // 最近一次融合 fix：这是**真实**数据（本进程不在作用域内），可与被注入的帧对照
        val fix = runCatching { lm.getLastKnownLocation(FUSED_NAME) }.getOrNull()
        sb.append("最近融合 fix     ").append(
            if (fix == null) "无（从未取到 —— 融合没工作、或被系统裁剪）"
            else "%.6f,%.6f  精度 %.1fm  %d 秒前".format(
                fix.latitude, fix.longitude, fix.accuracy,
                (android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000_000L
            )
        )
        return sb.toString()
    }
}
