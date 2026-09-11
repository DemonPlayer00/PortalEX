@file:Suppress("LocalVariableName", "PrivateApi")
package moe.fuqiuluo.xposed.utils

import android.content.Context
import android.os.Binder
import android.os.Build


object BinderUtils {
    private fun getActivityContext(): Context? {
        // public static ActivityManagerService self()
        // frameworks/base/services/java/com/android/server/am/ActivityManagerService.java
        try {
            val cam = Class.forName("com.android.server.am.ActivityManagerService")
            val am = cam.getMethod("self").invoke(null) ?: return null
            val mContext = cam.getDeclaredField("mContext")
            mContext.isAccessible = true
            return mContext.get(am) as? Context
        } catch (e: Throwable) {
            return null
        }
    }

    fun getSystemContext(): Context? {
        try {
            val cActivityThread = Class.forName("android.app.ActivityThread")
            val activityThread = cActivityThread.getMethod("currentActivityThread")
                .invoke(null) ?: return null
            return (cActivityThread.getMethod("getSystemContext").invoke(activityThread) as? Context) ?: getActivityContext()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return null
    }

    fun getUidPackageNames(context: Context? = getSystemContext(), uid: Int = getCallerUid()): Array<String>? {
        if (context == null) {
            return null
        }
        val packageManager = context.packageManager
        return packageManager.getPackagesForUid(uid)
    }

    fun getCallerUid(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            kotlin.runCatching { Binder.getCallingUidOrThrow() }.getOrNull() ?: -1
        } else {
            Binder.getCallingUid()
        }
    }

    /**
     * **只允许模块自身**调用命令通道（含 `exchange_key` 换钥匙）。
     *
     * 收紧前的实现有两个洞（都在真机上可利用）：
     *  1. `return uid < 10000` —— 任何**系统 uid** 都放行：shell(2000)、system(1000)、以及任何
     *     系统应用都能换到钥匙并驱动模拟（`adb shell cmd location send-extra-command` 就是一个）；
     *  2. 包名判断用的是 `contains("moe.fuqiuluo.portalex")` —— 装一个
     *     `com.evil.moe.fuqiuluo.portalex` 就能骗过去。
     *
     * 现在的规则（**fail-closed**）：
     *  · uid 恰好等于模块自身包的 uid（PortalEX 与其 `:remote` 同 uid）⇒ 放行；
     *  · 否则按**包名全等**再判一次（`getPackageUid` 取不到时的兜底，fork 改名也适用）；
     *  · 都不匹配（含 uid 未知、包名读不到）⇒ **拒绝**，并留下告警日志。
     */
    fun isLocationProviderEnabled(uid: Int): Boolean {
        if (uid < 0) return false
        if (gateAllows(uid)) return true
        Logger.warn(
            "Someone try to find Portal: uid = $uid, packageName = ${getUidPackageNames(uid = uid)?.joinToString()}," +
                    " ownerUid = ${moduleOwnerUid() ?: "unknown"}（已拒绝）"
        )
        return false
    }

    /** 门禁判定本体（不写日志，便于自检反复调用） */
    private fun gateAllows(uid: Int): Boolean {
        if (uid < 0) return false
        val ownerUid = moduleOwnerUid()
        if (ownerUid != null && uid == ownerUid) return true
        return getUidPackageNames(uid = uid)?.any { it == ModulePrefs.modulePackage } == true
    }

    /**
     * 握手门禁**自检**（Test 页展示）——把"哪些 uid 能换钥匙/发指令"直接摊开：
     * 期望只有模块自身为 ALLOW，shell/system/root/media 与 uid 未知一律 deny。
     * 系统 uid 不再默认放行、包名用全等（旧实现的两个洞见 [isLocationProviderEnabled]）。
     */
    internal fun gateSelfTest(): String {
        val owner = moduleOwnerUid() ?: -1
        val rows = listOf(
            "owner($owner)" to gateAllows(owner),
            "shell(2000)" to gateAllows(2000),
            "system(1000)" to gateAllows(1000),
            "root(0)" to gateAllows(0),
            "media(1013)" to gateAllows(1013),
            "unknown(-1)" to gateAllows(-1)
        )
        return rows.joinToString(" ") { "${it.first}=${if (it.second) "ALLOW" else "deny"}" }
    }

    /** 模块自身包的 uid（读不到返回 null —— 调用方按 fail-closed 处理） */
    internal fun moduleOwnerUid(): Int? {
        cachedOwnerUid?.let { return it }
        val resolved = runCatching {
            val ctx = getSystemContext() ?: return@runCatching null
            ctx.packageManager.getPackageUid(ModulePrefs.modulePackage, 0)
        }.getOrNull()
        cachedOwnerUid = resolved
        return resolved
    }

    @Volatile private var cachedOwnerUid: Int? = null

    fun isSystemPackages(packageNames: String): Boolean {
        if (packageNames.contains("com.xiaomi.location.fused") ||
            packageNames.contains("com.xiaomi.metoknlp") ||
            //packageNames.contains("com.android.phone") ||
            packageNames.contains("com.android.location.fused")
            ) {
            return false
        }
        return packageNames.contains("com.android") ||
                packageNames.contains("com.miui") ||
                packageNames.contains("com.xiaomi") ||
                packageNames.contains("com.oplus") ||
                packageNames.contains("com.coloros") ||
                packageNames.contains("com.heytap") ||
                packageNames.contains("android.framework") ||
                packageNames.contains("com.qualcomm") ||
                packageNames.contains("com.google.android.permissioncontroller")
    }

    fun isSystemAppsCall(uid: Int = getCallerUid()): Boolean {
        if (uid > 10000) {
            val packageNames = kotlin.runCatching { getUidPackageNames(uid = uid)?.joinToString() }
                .getOrNull() ?: return true
            return isSystemPackages(packageNames)
        }
        return true
    }
}