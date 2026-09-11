package moe.fuqiuluo.xposed.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * **静默失败记账**：把"被 `runCatching` 吞掉、只留一条日志"的失败变成可观测的数字。
 *
 * 为什么需要它：本仓最容易出、也最难查的一类故障是"错了但不报错"——注入层没装上、
 * 速率提示没灌进去、运行时通道没就绪…… 功能**静默失效**，而 Test 页一切正常。
 * 日志在真机上还常常拿不到（本机 logcat 过滤了应用日志）。所以：**每次失败都记一笔**，
 * 由 `get_sensor_status` 带出来、Test 页显示 —— 静默失败变成"看得见的失败"。
 *
 * 用法约定：
 *  · 只在**本该静默降级**的路径上记账（fail-inert 是设计，不是 bug；但次数必须可见）；
 *  · [Area] 是闭集：新增区域必须改枚举，避免手打字符串拼出一堆同义项；
 *  · [dump] 只列非零项，全零时输出 `none`（Test 页一眼就能看出"这次运行干净"）。
 */
object PortalDiag {

    /** 失败区域（闭集；新增请连注释一起写清楚"失败意味着什么"） */
    enum class Area(val desc: String) {
        /** 平台符号解析失败 ⇒ 注入层整个装不上，功能完全不生效 */
        LIB_RESOLVE("符号解析"),
        /** .so 装载失败（dlopen/SELinux）⇒ 同上 */
        NATIVE_LOAD("原生库装载"),
        /** vtable patch 失败 ⇒ 挂不上出口 */
        NATIVE_INSTALL("注入层安装"),
        /** 运行时通道反射/载具失败 ⇒ 回退 poll 路径（功能仍在，投递变差） */
        RT_CHANNEL("运行时通道"),
        /** 运行时投递单条失败 */
        RT_SEND("运行时投递"),
        /** 传感器类型表取不到 ⇒ 速率提示灌不进去（会退回"恒出力"） */
        HANDLE_MAP("类型表"),
        /** 框架 dump 解析/灌速率失败 */
        RATE_HINTS("速率提示"),
        /** 命令通道拒绝（门禁/未知命令）—— 可能是被别人探测，也可能是版本不匹配 */
        COMMAND_REJECT("命令拒绝"),
        /** 配置下发时系统侧报错（配置未生效） */
        CONFIG_APPLY("配置应用"),
    }

    private val counters = ConcurrentHashMap<Area, AtomicLong>()

    /** 记账（幂等、线程安全；[t] 只用于日志，不进计数器） */
    fun fail(area: Area, t: Throwable? = null) {
        counters.computeIfAbsent(area) { AtomicLong() }.incrementAndGet()
        Logger.warn("静默失败记账 ${area.name}(${area.desc})${t?.let { "：${it.message}" } ?: ""}")
    }

    fun count(area: Area): Long = counters[area]?.get() ?: 0L

    /** 全部归零（Test 页"重置计数"或排查前后对照用） */
    fun reset() = counters.clear()

    /** 稳定输出："LIB_RESOLVE=1 RT_CHANNEL=3"；全零时 "none" */
    fun dump(): String {
        val parts = Area.entries
            .mapNotNull { a -> counters[a]?.get()?.takeIf { it > 0 }?.let { "$a=$it" } }
        return if (parts.isEmpty()) "none" else parts.joinToString(" ")
    }
}
