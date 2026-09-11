@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package moe.fuqiuluo.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.fuqiuluo.xposed.hooks.LocationManagerHook
import moe.fuqiuluo.xposed.hooks.LocationServiceHook
import moe.fuqiuluo.xposed.hooks.fused.AndroidFusedLocationProviderHook
import moe.fuqiuluo.xposed.hooks.fused.ThirdPartyLocationHook
import moe.fuqiuluo.xposed.hooks.oplus.OplusLocationHook
import moe.fuqiuluo.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import moe.fuqiuluo.xposed.hooks.sensor.BinderSensorMock
import moe.fuqiuluo.xposed.hooks.sensor.SystemRuntimeChannel
import moe.fuqiuluo.xposed.hooks.sensor.SystemSensorManagerHook
import moe.fuqiuluo.xposed.hooks.telephony.TelephonyHook
import moe.fuqiuluo.xposed.hooks.wlan.WlanHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.ModulePrefs

class FakeLocation: IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var cServiceManager: Class<*> // android.os.ServiceManager

    companion object {
        /** 已注入 hook 的系统进程（防止 handleLoadPackage 重复调用导致重复注入）。
         *  不用 System.setProperty：系统属性可被枚举/读取，属于模块痕迹。 */
        private val injectedPackages: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    private val mServiceManagerCache by lazy {
        kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onSuccess {
            it.isAccessible = true
        }.getOrNull()
        // the field is not guaranteed to exist
    }

    /**
     * Called very early during startup of Zygote.
     * @param startupParam Details about the module itself and the started process.
     * @throws Throwable everything is caught, but will prevent further initialization of the module.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        if(startupParam == null) return
    }

    /**
     * This method is called when an app is loaded. It's called very early, even before
     * [Application.onCreate] is called.
     * Modules can set up their app-specific hooks here.
     *
     * @param lpparam Information about the app.
     * @throws Throwable Everything the callback throws is caught and logged.
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return

        // 传感器模拟：仅对**用户应用**（非系统应用）进程安装（SystemSensorManager 是 SDK
        // 客户端类，跑在 app 进程内）。系统框架（system_server/phone）与所有系统应用
        // （systemui/settings/fused 供应商等）一律**不装**——避免拦截系统自身的传感器注册
        // （自动旋转、计步统计等）导致行为污染；用户应用装完即 return，不触碰系统侧 hook；
        // 系统应用继续走下方 when 分支安装各自系统侧 hook。
        // 注入与否完全由 LSPosed 作用域决定（勾选 = 注入；不勾 = 本模块代码都不加载）。
        // 系统侧进程判定：appInfo 在部分 ROM/LSPosed 组合下对 system_server（包名 "android"）
        // 为 null（实测 ColorOS），不能据此当成用户应用——否则框架侧 hook（LocationServiceHook
        // 等）全部不装，app 端 exchange_key 无人应答 → 「系统服务注入失败」。
        // 因此除 appInfo 系统标志外，显式识别已知系统包/进程。
        val isSystemApp = lpparam.appInfo?.let {
            (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } ?: false
        val isSystemProcess = isSystemApp ||
                lpparam.packageName == "android" ||
                lpparam.processName == "system" ||
                lpparam.packageName == "com.android.phone" ||
                lpparam.packageName == "com.android.location.fused" ||
                lpparam.packageName == "com.xiaomi.location.fused" ||
                lpparam.packageName == "com.oplus.location"
        if (!isSystemProcess) {
            /*
             * 【临时停用 · 2026-09-11 裁决】应用侧传感器 hook **一律不安装**。
             *
             * 为什么必须停：本机 XSharedPreferences 不可用 ⇒ `ModulePrefs.binderSensorMockEnabled()`
             * 恒为 null ⇒ 下面旧逻辑按"开关未开启"处理 ⇒ 应用侧 hook 在每个被注入的应用进程里
             * 都装上，并把框架注入的步数**无条件改写**成本进程自己的 `globalSteps`
             * （初值 `Random.nextInt(3000, 12000)`，不走路不涨）。后果：
             * - 目标应用读到随机/冻结值（用户实测"点自动播放变 7000+"就是它）；
             * - 连排查用的探针都被污染 —— 六轮"Java 客户端恒定陈旧值"全是被它骗的。
             *
             * 现在模拟只由**系统框架侧**（Binder 外周传感器模拟 → 原生注入层 + 运行时投递通道）
             * 完成，对目标应用零 hook。等开关传播通道定下来（自动让位 / 框架侧写系统属性）
             * 再恢复"按开关安装"，见 docs 与今日备忘。
             */
            Logger.info(
                "应用侧传感 hook 已临时停用（模拟由系统框架侧接管）：${lpparam.packageName}"
            )
            return
        }

        val systemClassLoader = (kotlin.runCatching {
            lpparam.classLoader.loadClass("android.app.ActivityThread")
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

        if (!injectedPackages.add(lpparam.packageName)) {
            return
        }

        when (lpparam.packageName) {
            "com.android.phone" -> {
                Logger.info("Found com.android.phone")
                TelephonyHook(lpparam.classLoader)
                MiuiTelephonyManagerHook(lpparam.classLoader)
            }
            "android" -> {
                Logger.info("Debug Log Status: ${FakeLoc.enableDebugLog}")
                FakeLoc.isSystemServerProcess = true
                startFakeLocHook(systemClassLoader)
                TelephonyHook.hookSubOnTransact(lpparam.classLoader)
                WlanHook(systemClassLoader)
                AndroidFusedLocationProviderHook(lpparam.classLoader)

                ThirdPartyLocationHook(lpparam.classLoader)

                // 实验性：Binder 外周传感器模拟。开关打开时（put_config 到达即触发）
                // 才装载原生注入层并起调度线程；关闭时本调用不做任何事。
                BinderSensorMock.onConfigChanged()

                // 运行时投递通道：只解析框架的 JNI 入口并捕获 SensorService 实例（零副作用），
                // 注册载体/投递由 BinderSensorMock 在开关打开后按需触发。
                // 注意传的是 lpparam.classLoader（系统服务的类加载器）——实测
                // ActivityThread 的 classLoader 在 system_server 里看不到 com.android.server.*
                SystemRuntimeChannel.attach(lpparam.classLoader)
            }
            "com.android.location.fused" -> {
                AndroidFusedLocationProviderHook(lpparam.classLoader)
            }
            "com.xiaomi.location.fused" -> {
                ThirdPartyLocationHook(lpparam.classLoader)
            }
            "com.oplus.location" -> {
                OplusLocationHook(lpparam.classLoader)
            }
        }
    }

    private fun startFakeLocHook(classLoader: ClassLoader) {
        cServiceManager = XposedHelpers.findClass("android.os.ServiceManager", classLoader)

        XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)?.let {
            TelephonyHook.hookTelephonyRegistry(it)
        } // for MUMU emulator

        val cLocationManager =
            XposedHelpers.findClass("android.location.LocationManager", classLoader)

        LocationServiceHook(classLoader)
        LocationManagerHook(cLocationManager)  // intrusive hooks
    }
}