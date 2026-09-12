package moe.fuqiuluo.xposed.hooks.fused

import android.location.Location
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.hooks.blindhook.BlindHookLocation
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.hookMethodAfter
import moe.fuqiuluo.xposed.utils.toClass

object AndroidFusedLocationProviderHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        val cFusedLocationProvider = "com.android.location.fused.FusedLocationProvider".toClass(classLoader)
        if (cFusedLocationProvider == null) {
            // 缺类是**正常情况**（无融合定位的 ROM）：静默跳过，调试模式才留痕
            if (FakeLoc.enableDebugLog) Logger.debug("本机没有 FusedLocationProvider，跳过融合层")
            return
        }

        if(!initDivineService("AndroidFusedLocationProvider")) {
            Logger.error("Failed to init DivineService in AndroidFusedLocationProvider")
            return
        }

        Logger.info("AndroidFusedLocationProvider: 已挂 chooseBestLocation（拦截-修改-转发）")
        cFusedLocationProvider.hookMethodAfter("chooseBestLocation", Location::class.java, Location::class.java) {
            if (result == null) return@hookMethodAfter

            if (FakeLoc.enable) {
                result = injectLocation(result as Location)
            }
        }

//        cFusedLocationProvider.hookMethodBefore("reportBestLocationLocked") {
//
//        }

        val cChildLocationListener = "com.android.location.fused.FusedLocationProvider\$ChildLocationListener".toClass(classLoader)
        if (cChildLocationListener == null) {
            if (FakeLoc.enableDebugLog) Logger.debug("本机没有 ChildLocationListener，跳过")
            return
        }

        BlindHookLocation(cChildLocationListener, classLoader)
    }
}