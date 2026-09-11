package moe.fuqiuluo.xposed.utils

import de.robv.android.xposed.XposedBridge

/**
 * 模块日志。
 *
 * **铁律：日志绝不允许抛异常 / 拖垮调用方。**
 * 这条不是洁癖：`XposedBridge` 只在注入环境里可用（JVM 单测、以及任何非注入上下文都取不到），
 * 而日志点大量位于 hook 回调与状态机内部 —— 一个会抛的 Logger 会把"记一条日志"变成
 * "整个功能静默失效"，正是本仓最容易出的一类故障。因此统一走 [emit]，失败即降级到 stdout。
 *
 * 分级语义（与历史一致，不要顺手改）：
 *  · info / info(t)   —— 受 `FakeLoc.enableLog` 控制（用户可关）；
 *  · debug / debug(t) —— 始终输出（排查用，调用点自己判断是否要打）；
 *  · warn / error     —— 始终输出（异常路径必须留痕）。
 */
object Logger {
    private fun isEnableLog(): Boolean = FakeLoc.enableLog

    /** 唯一出口：任何失败都吞掉并降级到 stdout（host 上仍能看到） */
    private fun emit(tag: String, msg: String, throwable: Throwable?) {
        val text = if (throwable == null) "[Portal]$tag $msg"
        else "[Portal]$tag $msg: ${throwable.stackTraceToString()}"
        val logged = runCatching { XposedBridge.log(text) }.isSuccess
        if (!logged) runCatching { println(text) }
    }

    fun info(msg: String) {
        if (isEnableLog()) emit("", msg, null)
    }

    fun info(msg: String, throwable: Throwable) {
        if (isEnableLog()) emit("", msg, throwable)
    }

    fun debug(msg: String) = emit("[DEBUG]", msg, null)

    fun debug(msg: String, throwable: Throwable) = emit("[DEBUG]", msg, throwable)

    fun error(msg: String) = emit("[ERROR]", msg, null)

    fun error(msg: String, throwable: Throwable) = emit("[ERROR]", msg, throwable)

    fun warn(msg: String) = emit("[WARN]", msg, null)

    fun warn(msg: String, throwable: Throwable) = emit("[WARN]", msg, throwable)
}
