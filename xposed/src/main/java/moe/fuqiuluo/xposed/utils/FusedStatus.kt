package moe.fuqiuluo.xposed.utils

import java.util.concurrent.atomic.AtomicInteger

/**
 * **融合定位 hook 状态**（诊断用）：本机有没有融合定位、hook 装在哪个进程、挂了哪些东西。
 *
 * 为什么单独记：融合这条路在真机上是否真的被我们接管，"装没装上"从行为上很难看出来
 * （应用照样拿到位置，只是拿到了真的那个）。所以把状态记下来，两处消费：
 *  · `get_fused_state` 回包 → 设置页据此**启用/禁用**三态滑块、以及给默认值；
 *  · 调试模式打开时打一条单行日志（见 [statusLine]）—— 用户点名要的那条 debug 日志。
 */
object FusedStatus {

    /** 本机是否存在融合定位（AOSP `com.android.location.fused` 或厂商 fused 进程） */
    @Volatile
    var available = false
        private set

    /** 是否已挂上 `chooseBestLocation`（融合"选出最优位置"的那一刻） */
    @Volatile
    var chooseBestHookInstalled = false

    /** 是否已盲挂 `ChildLocationListener`（融合给每个客户端的那条路） */
    @Volatile
    var childListenerHookInstalled = false

    /** 盲挂到的方法数（越多说明覆盖面越广） */
    private val blindHooked = AtomicInteger(0)

    /** 在哪个进程装上的（诊断："system_server" / 进程名） */
    @Volatile
    var installedIn: String = ""

    fun markAvailable(where: String) {
        available = true
        if (where.isNotEmpty()) installedIn = where
    }

    fun markChooseBest(where: String) {
        markAvailable(where)
        chooseBestHookInstalled = true
    }

    fun markChildListener(where: String, methods: Int) {
        markAvailable(where)
        childListenerHookInstalled = true
        blindHooked.addAndGet(methods)
    }

    /** 单行诊断，形如：`available=true in=system_server chooseBest=true child=true blindMethods=7 mode=伪装(2)` */
    fun statusLine(): String =
        "available=$available in=${installedIn.ifEmpty { "-" }} " +
                "chooseBest=$chooseBestHookInstalled child=$childListenerHookInstalled " +
                "blindMethods=${blindHooked.get()} mode=${FusedMode.label(FakeLoc.fusedMode)}(${FakeLoc.fusedMode})"

    /** 调试模式打开时打一条（用户点名要的那条日志）；关闭时完全安静 */
    fun logIfDebug(tag: String = "融合定位 hook 状态") {
        if (FakeLoc.enableDebugLog) Logger.debug("$tag：${statusLine()}")
    }
}
