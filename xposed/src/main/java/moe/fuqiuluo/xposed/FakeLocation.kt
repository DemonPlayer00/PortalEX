@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package moe.fuqiuluo.xposed

import moe.fuqiuluo.xposed.utils.XposedHelpers
import moe.fuqiuluo.xposed.hooks.LocationManagerHook
import moe.fuqiuluo.xposed.hooks.LocationServiceHook
import moe.fuqiuluo.xposed.hooks.fused.AndroidFusedLocationProviderHook
import moe.fuqiuluo.xposed.hooks.fused.ThirdPartyLocationHook
import moe.fuqiuluo.xposed.hooks.oplus.OplusLocationHook
import moe.fuqiuluo.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import moe.fuqiuluo.xposed.hooks.sensor.BinderSensorMock
import moe.fuqiuluo.xposed.hooks.sensor.SystemRuntimeChannel
import moe.fuqiuluo.xposed.hooks.telephony.TelephonyHook
import moe.fuqiuluo.xposed.hooks.wlan.WlanHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger

/**
 * **安装器**：按进程类型决定装哪些 hook —— 模块里唯一一份"装什么"的实现。
 *
 * 入口只有一个：libxposed 的 [PortalExModule]（登记在 `META-INF/xposed/java_init.list`）。
 * 本类不再是入口，只被入口调用；入口负责把"框架给的进程信息"翻译成 [ProcessInfo]，
 * 于是"换框架 API"与"装什么 hook"彻底分开，后者不受入口形态变化影响。
 *
 * （历史上这里同时是旧 API 的入口类，类名沿用至今；职责已经只剩 [install]。）
 */
class FakeLocation {

    /**
     * **进程描述**：入口交给安装器的输入。
     *
     * libxposed 侧从 `PackageReadyParam` / `SystemServerStartingParam` 取，
     * 没有的字段由入口补齐（例如 `onSystemServerStarting` 只给类加载器，
     * 入口补 `packageName = "android"`、`isSystemApp = true`）。
     *
     * 注意它挂在 [FakeLocation] 类上而不是 companion 里：companion 的嵌套类
     * 只能写 `FakeLocation.Companion.ProcessInfo`，两条引用都难读。
     */
    internal class ProcessInfo(
        val packageName: String,
        val processName: String?,
        val classLoader: ClassLoader,
        val isSystemApp: Boolean
    )

    companion object {

        /** 已注入 hook 的包（防止同一进程内重复注入）。
         *  不用 System.setProperty：系统属性可被枚举/读取，属于模块痕迹。 */
        private val injectedPackages: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private lateinit var cServiceManager: Class<*> // android.os.ServiceManager

        private val mServiceManagerCache by lazy {
            kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onSuccess {
                it.isAccessible = true
            }.getOrNull()
            // the field is not guaranteed to exist
        }

        /**
         * 按进程类型安装 hook。**唯一的调用点是入口 `PortalExModule`。**
         *
         * 传感器模拟：仅对**用户应用**（非系统应用）进程安装（SystemSensorManager 是 SDK
         * 客户端类，跑在 app 进程内）。系统框架（system_server/phone）与所有系统应用
         * （systemui/settings/fused 供应商等）一律**不装**——避免拦截系统自身的传感器注册
         * （自动旋转、计步统计等）导致行为污染；用户应用装完即 return，不触碰系统侧 hook；
         * 系统应用继续走下方 when 分支安装各自系统侧 hook。
         * 注入与否完全由 LSPosed 作用域决定（勾选 = 注入；不勾 = 本模块代码都不加载）。
         * 系统侧进程判定：appInfo 在部分 ROM/LSPosed 组合下对 system_server（包名 "android"）
         * 为 null（实测 ColorOS），不能据此当成用户应用——否则框架侧 hook（LocationServiceHook
         * 等）全部不装，app 端 exchange_key 无人应答 → 「系统服务注入失败」。
         * 因此除 appInfo 系统标志外，显式识别已知系统包/进程。
         */
        internal fun install(info: ProcessInfo) {
            val isSystemProcess = info.isSystemApp ||
                    info.packageName == "android" ||
                    info.processName == "system" ||
                    info.packageName == "com.android.phone" ||
                    info.packageName == "com.android.location.fused" ||
                    info.packageName == "com.xiaomi.location.fused" ||
                    info.packageName == "com.oplus.location"

            if (!isSystemProcess) {
                /*
                 * 【临时停用 · 2026-09-11 裁决】应用侧传感器 hook **一律不安装**。
                 *
                 * 为什么必须停：应用侧 hook 会把框架注入的步数**无条件改写**成本进程自己的
                 * `globalSteps`（初值 `Random.nextInt(3000, 12000)`，不走路不涨）。后果：
                 * - 目标应用读到随机/冻结值（用户实测"点自动播放变 7000+"就是它）；
                 * - 连排查用的探针都被污染 —— 六轮"Java 客户端恒定陈旧值"全是被它骗的。
                 *
                 * 现在模拟只由**系统框架侧**（Binder 外周传感器模拟 → 原生注入层 + 运行时投递通道）
                 * 完成，对目标应用零 hook。完整原因、证据与恢复步骤见
                 * `hooks/sensor/frozen/SystemSensorManagerHook.kt` 顶部的 FREEZE 说明
                 * （该文件的应用侧实现已整体注释冻结，仅留 `stepTraceText()` 存根）。
                 */
                if (!injectedPackages.add(info.packageName)) {
                    // libxposed 的 onPackageReady「could be invoked multiple times for the same
                    // process on each package」——重复安装会让每个方法上挂两遍钩子。
                    return
                }
                Logger.info("应用侧传感 hook 已临时停用（模拟由系统框架侧接管）：${info.packageName}")
                return
            }

            val systemClassLoader = (kotlin.runCatching {
                info.classLoader.loadClass("android.app.ActivityThread")
                    ?: Class.forName("android.app.ActivityThread")
            }.onFailure {
                Logger.error("Failed to find ActivityThread", it)
            }.getOrNull() ?: return)
                .getMethod("currentActivityThread")
                .invoke(null)
                .javaClass
                .getClassLoader()

            if (systemClassLoader == null) {
                Logger.error("Failed to get system class loader")
                return
            }

            if (!injectedPackages.add(info.packageName)) {
                return
            }

            when (info.packageName) {
                "com.android.phone" -> {
                    Logger.info("Found com.android.phone")
                    TelephonyHook(info.classLoader)
                    MiuiTelephonyManagerHook(info.classLoader)
                }
                "android" -> {
                    Logger.info("Debug Log Status: ${FakeLoc.enableDebugLog}")
                    FakeLoc.isSystemServerProcess = true
                    startFakeLocHook(systemClassLoader)

                    TelephonyHook.hookSubOnTransact(info.classLoader)
                    WlanHook(systemClassLoader)
                    AndroidFusedLocationProviderHook(info.classLoader)

                    ThirdPartyLocationHook(info.classLoader)

                    // Binder 外周传感器模拟：**开机阶段只登记开关，绝不装载**。
                    // 这里调 onConfigChanged() 曾在 MI6/LineageOS 15 上把开机卡死（system_server
                    // 永久等待 sensorservice）——装载只在模拟会话启动时发生，见
                    // BinderSensorMock.registerAtBoot / onSimulationChanged。
                    BinderSensorMock.registerAtBoot()

                    // 运行时投递通道：只解析框架的 JNI 入口并捕获 SensorService 实例（零副作用），
                    // 注册载体/投递由 BinderSensorMock 在开关打开后按需触发。
                    // 注意传的是 info.classLoader（系统服务的类加载器）——实测
                    // ActivityThread 的 classLoader 在 system_server 里看不到 com.android.server.*
                    SystemRuntimeChannel.attach(info.classLoader)
                }
                "com.android.location.fused" -> {
                    AndroidFusedLocationProviderHook(info.classLoader)
                }
                "com.xiaomi.location.fused" -> {
                    // 厂商 fused 进程：本机有融合定位（即使没有 AOSP 的 FusedLocationProvider 类）
                    moe.fuqiuluo.xposed.utils.FusedStatus.markAvailable(info.processName ?: info.packageName)
                    ThirdPartyLocationHook(info.classLoader)
                    moe.fuqiuluo.xposed.utils.FusedStatus.logIfDebug()
                }
                "com.oplus.location" -> {
                    moe.fuqiuluo.xposed.utils.FusedStatus.markAvailable(info.processName ?: info.packageName)
                    OplusLocationHook(info.classLoader)
                    moe.fuqiuluo.xposed.utils.FusedStatus.logIfDebug()
                }
            }
        }

        /**
         * 装框架侧 hook，**每一步各自兜底**。
         *
         * 为什么：这里任一环抛异常（缺类/反射差异/某个 ROM 的类被裁掉）都会连带后面全部不装，
         * 表现是"模块看起来加载了、其实只剩一半 hook"且无日志。分成四步后，坏的那步单独记账，
         * 其余照常安装 —— 在 LineageOS 这类裁剪过的系统上尤其重要。
         */
        private fun startFakeLocHook(classLoader: ClassLoader) {
            step("ServiceManager") {
                cServiceManager = XposedHelpers.findClass("android.os.ServiceManager", classLoader)
            }

            step("TelephonyRegistry") {
                XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)?.let {
                    TelephonyHook.hookTelephonyRegistry(it)
                } // for MUMU emulator
            }

            step("LocationServiceHook") {
                LocationServiceHook(classLoader)
            }

            // intrusive hooks：客户端侧 LocationManager
            step("LocationManagerHook") {
                val cLocationManager =
                    XposedHelpers.findClass("android.location.LocationManager", classLoader)
                LocationManagerHook(cLocationManager)
            }
        }

        /** 单个安装步骤：失败只记账 + 一条日志，不拖垮其它步骤 */
        private inline fun step(name: String, block: () -> Unit) {
            kotlin.runCatching(block).onFailure {
                moe.fuqiuluo.xposed.utils.PortalDiag.fail(moe.fuqiuluo.xposed.utils.PortalDiag.Area.HOOK_INSTALL, it)
                Logger.error("$name 安装失败（已跳过，其余继续）：${it.message}", it)
            }
        }
    }
}
