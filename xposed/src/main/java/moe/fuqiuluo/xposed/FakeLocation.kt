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
            // Binder 外周传感器模拟（实验性）开启时，**应用进程不再安装传感器 hook**：
            // 该模式下模拟完全由系统框架侧的原生注入层完成（对目标应用零 hook，
            // 且不依赖真实回调驱动）。开关读不到（旧版 LSPosed / prefs 不可读）时
            // 一律按"未开启"处理 → 安装旧 hook，行为与从前完全一致。
            if (ModulePrefs.binderSensorMockEnabled() == true) {
                Logger.info(
                    "Binder 外周传感器模拟已开启：${lpparam.packageName} 不安装应用侧传感 hook，" +
                            "改由系统框架侧注入"
                )
            } else {
                SystemSensorManagerHook(lpparam.classLoader)
            }
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