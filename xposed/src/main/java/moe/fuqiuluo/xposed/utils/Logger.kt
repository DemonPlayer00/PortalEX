package moe.fuqiuluo.xposed.utils

import android.util.Log

/**
 * 模块日志。
 *
 * **铁律：日志绝不允许抛异常 / 拖垮调用方。**
 * 这条不是洁癖：日志点大量位于 hook 回调与状态机内部 —— 一个会抛的 Logger
 * 会把"记一条日志"变成"整个功能静默失效"，正是本仓最容易出的一类故障。
 * 因此统一走 [emit]：出口 [Hooks.log] 自身吞异常，失败再降级到 stdout。
 *
 * 分级语义（与历史一致，不要顺手改）：
 *  · info / info(t)   —— 受 `FakeLoc.enableLog` 控制（用户可关）；
 *  · debug / debug(t) —— 始终输出（排查用，调用点自己判断是否要打）；
 *  · warn / error     —— 始终输出（异常路径必须留痕）。
 *
 * 级别同时决定框架日志的 **priority**：模块日志现在带 `PortalEX` tag 与级别，
 * 于是 `adb logcat -s PortalEX:E` 能只看错误。
 */
object Logger {
    private fun isEnableLog(): Boolean = FakeLoc.enableLog

    /** 唯一出口：日志文案带 `[Portal]` 前缀（历史格式，便于与旧日志对照） */
    private fun emit(priority: Int, tag: String, msg: String, throwable: Throwable?) {
        val text = if (throwable == null) "[Portal]$tag $msg"
        else "[Portal]$tag $msg: ${throwable.stackTraceToString()}"
        Hooks.log(priority, text)
    }

    fun info(msg: String) {
        if (isEnableLog()) emit(Log.INFO, "", msg, null)
    }

    fun info(msg: String, throwable: Throwable) {
        if (isEnableLog()) emit(Log.INFO, "", msg, throwable)
    }

    fun debug(msg: String) = emit(Log.DEBUG, "[DEBUG]", msg, null)

    fun debug(msg: String, throwable: Throwable) = emit(Log.DEBUG, "[DEBUG]", msg, throwable)

    fun error(msg: String) = emit(Log.ERROR, "[ERROR]", msg, null)

    fun error(msg: String, throwable: Throwable?) = emit(Log.ERROR, "[ERROR]", msg, throwable)

    fun warn(msg: String) = emit(Log.WARN, "[WARN]", msg, null)

    fun warn(msg: String, throwable: Throwable) = emit(Log.WARN, "[WARN]", msg, throwable)
}
