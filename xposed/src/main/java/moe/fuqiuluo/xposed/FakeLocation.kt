@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package moe.fuqiuluo.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import moe.fuqiuluo.xposed.utils.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
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
 * **旧入口**（登记在 `assets/xposed_init`）。
 *
 * 迁移期与 libxposed 入口 [PortalExModule] 并存，两条入口最终都汇到 [install] ——
 * 于是"装什么 hook"只有一份实现，框架爱走哪条就走哪条：
 *
 *  · 框架只认旧 API（大量现役版本）→ 本类被实例化；
 *  · 框架支持 libxposed（本机 LSPosed v2.2.0）→ [PortalExModule] 被实例化，本类可能压根不被调用；
 *  · 两条都被调用（过渡期的未知情况）→ [injectedPackages] 去重，第二遍直接返回。
 *
 * 等 libxposed 入口在真机上验证通过，`assets/xposed_init` 与本类的框架接口一起摘掉。
 */
class FakeLocation : IXposedHookLoadPackage, IXposedHookZygoteInit {

    /**
     * Called very early during startup of Zygote.
     * @param startupParam Details about the module itself and the started process.
     * @throws Throwable everything is caught, but will prevent further initialization of the module.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        if (startupParam == null) return
    }

    /**
     * **进程描述**：两条入口共用的输入。
     *
     * 旧入口从 `XC_LoadPackage.LoadPackageParam` 取，libxposed 入口从
     * `PackageLoadedParam` / `SystemServerStartingParam` 取（没有的字段由入口补齐）。
     * 把"框架给的东西"与"要装什么"分开，是为了让迁移只动前者。
     *
     * 注意它挂在 [FakeLocation] 类上而不是 companion 里：companion 的嵌套类
     * 只能写 `FakeLocation.Companion.ProcessInfo`（Kotlin 不把 companion 的类成员
     * 提到外层名下），那样两条入口的调用点都难读。
     */
    internal class ProcessInfo(
        val packageName: String,
        val processName: String?,
        val classLoader: ClassLoader,
        val isSystemApp: Boolean
    )

    /**
     * This method is called when an app is loaded. It's called very early, even before
     * [android.app.Application.onCreate] is called.
     *
     * @param lpparam Information about the app.
     * @throws Throwable Everything the callback throws is caught and logged.
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return
        install(
            ProcessInfo(
                packageName = lpparam.packageName,
                processName = lpparam.processName,
                classLoader = lpparam.classLoader,
                isSystemApp = lpparam.appInfo?.let {
                    (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                } ?: false
            )
        )
    }

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
         * 按进程类型安装 hook。**两条入口唯一的汇合点。**
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
                 * 为什么必须停：本机偏好通道不可用 ⇒ `ModulePrefs.binderSensorMockEnabled()`
                 * 恒为 null ⇒ 下面旧逻辑按"开关未开启"处理 ⇒ 应用侧 hook 在每个被注入的应用进程里
                 * 都装上，并把框架注入的步数**无条件改写**成本进程自己的 `globalSteps`
                 * （初值 `Random.nextInt(3000, 12000)`，不走路不涨）。后果：
                 * - 目标应用读到随机/冻结值（用户实测"点自动播放变 7000+"就是它）；
                 * - 连排查用的探针都被污染 —— 六轮"Java 客户端恒定陈旧值"全是被它骗的。
                 *
                 * 现在模拟只由**系统框架侧**（Binder 外周传感器模拟 → 原生注入层 + 运行时投递通道）
                 * 完成，对目标应用零 hook。完整原因、证据与恢复步骤见
                 * `hooks/sensor/frozen/SystemSensorManagerHook.kt` 顶部的 FREEZE 说明
                 * （该文件的应用侧实现已整体注释冻结，仅留 `stepTraceText()` 存根）。
                 *
                 * 注：S5 起 libxposed 远程偏好通道可用，本注释里"通道不可用"这一条前提
                 * 已不再成立；但**恢复应用侧 hook 是另一件事**，不在本次迁移范围内。
                 */
                if (!injectedPackages.add(info.packageName)) {
                    // libxposed 的 onPackageReady「could be invoked multiple times for the same
                    // process on each package」——重复安装会让每个方法上挂两遍钩子，
                    // 旧入口只触发一次所以历史上不需要这道闸。
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
