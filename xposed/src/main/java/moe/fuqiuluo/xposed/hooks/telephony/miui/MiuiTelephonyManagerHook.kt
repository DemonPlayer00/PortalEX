package moe.fuqiuluo.xposed.hooks.telephony.miui

import moe.fuqiuluo.xposed.hooks.telephony.BaseTelephonyHook
import moe.fuqiuluo.xposed.utils.Hooks

object MiuiTelephonyManagerHook: BaseTelephonyHook() {
    operator fun invoke(classLoader: ClassLoader) {
//        val cMiuiTelephonyManager = XposedHelpers.findClassIfExists("com.miui.internal.telephony.BaseTelephonyManagerAndroidImpl", classLoader)
//        val cTelephonyManagerEx = XposedHelpers.findClassIfExists("miui.telephony.TelephonyManagerEx", classLoader)
//
//        if (FakeLocationConfig.DEBUG) {
//            println("[Portal] MiuiTelephonyManager: $cMiuiTelephonyManager")
//            println("[Portal] MiuiTelephonyManagerEx: $cTelephonyManagerEx")
//        }
//
//        cMiuiTelephonyManager?.let { clazz ->
//            println("[Portal] found " + clazz.declaredMethods.mapNotNull {
//                if (it.returnType == CellLocation::class.java) {
//                    Hooks.hookMethod(it, hookGetCellLocation)
//                } else null
//            }.size + " methods to hook in MiuiTelephonyManager")
//        }
//
//        cTelephonyManagerEx?.let { clazz ->
//            println("[Portal] found " + clazz.declaredMethods.mapNotNull {
//                if (it.returnType == CellLocation::class.java) {
//                    Hooks.hookMethod(it, hookGetCellLocation)
//                } else null
//            }.size + " methods to hook in MiuiTelephonyManagerEx")
//
//            var sizeGetNeighboringCellInfoMethod = Hooks.hookAllMethods(clazz, "getNeighboringCellInfo", hookGetNeighboringCellInfoList).size
//            sizeGetNeighboringCellInfoMethod += Hooks.hookAllMethods(clazz, "getNeighboringCellInfoForSlot", hookGetNeighboringCellInfoList).size
//            sizeGetNeighboringCellInfoMethod += Hooks.hookAllMethods(clazz, "getNeighboringCellInfoForSubscription", hookGetNeighboringCellInfoList).size
//            println("[Portal] found $sizeGetNeighboringCellInfoMethod methods to hook in MiuiTelephonyManagerEx")
//        }
    }
}