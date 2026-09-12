package moe.fuqiuluo.portalex.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.text.TextUtils
import android.util.Log
import moe.fuqiuluo.portalex.ui.camera.CameraMockRuntime
import moe.fuqiuluo.portalex.ui.camera.cameraMockEnabled
import moe.fuqiuluo.portalex.ui.camera.cameraMockTargets
import moe.fuqiuluo.portalex.ui.camera.CameraMockStore
import moe.fuqiuluo.xposed.camera.CameraMockCodec
import moe.fuqiuluo.xposed.camera.CameraMockProtocol

import java.io.FileNotFoundException

/**
 * 摄像头模拟的**跨进程通道**（app 侧服务端）。
 *
 * 为什么必须是 provider：摄像头 hook 跑在**目标应用自己的进程**里，它需要两样东西 ——
 * 配置清单与**视频文件本体**。前者是结构化数据，后者在我们私有目录里（对方既没有路径
 * 权限也没有存储权限）。`call()` + `openFile()` 一次解决，且不需要对方持有任何权限。
 *
 * 代价（写清楚，免得以后当成 bug）：provider 是被动唤醒的 —— 目标应用一申请摄像头，
 * 系统就会把 PortalEX 进程拉起来。因此模块侧的拉取频率必须保守（见 Xposed 侧
 * `CameraMockClient` 的 TTL 缓存），不能变成"每次开摄像头都冷启一次 app"。
 *
 * 门禁（[gate]）：不设 `android:permission`（目标应用不可能持有我们的签名权限），
 * 改为按 `Binder.getCallingUid()` 判定；**开关关闭时不清空应答**，而是照样回一份
 * `enabled=false` 的清单 —— 否则"关掉开关"这条指令永远传不到已经注入的进程里。
 */
class CameraMockProvider : ContentProvider() {

    companion object {
        private const val TAG = "CameraMockProvider"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return denied("no-context")
        val uid = Binder.getCallingUid()
        val claimed = extras?.getString(CameraMockProtocol.Key.PACKAGE)
        val claimedUid = extras?.getInt("uid", -1) ?: -1
        val deny = gate(uid, claimed, claimedUid)
        if (deny != null) {
            if (ctx.cameraMockEnabled) {
                Log.w(TAG, "拒绝 $method：uid=$uid pkg=$claimed 原因=$deny")
            }
            return denied(deny)
        }
        CameraMockRuntime.noteAccess("${stamp()} $method uid=$uid pkg=${claimed ?: "-"}")

        return when (method) {
            CameraMockProtocol.Method.MANIFEST -> Bundle().apply {
                putString(
                    CameraMockProtocol.Key.STATUS, "ok"
                )
                putString(
                    CameraMockProtocol.Key.MANIFEST_JSON,
                    CameraMockCodec.encode(CameraMockStore.manifest(ctx))
                )
            }

            CameraMockProtocol.Method.POSITION -> {
                val pos = CameraMockRuntime.position()
                Bundle().apply {
                    putString(CameraMockProtocol.Key.STATUS, "ok")
                    putBoolean(CameraMockProtocol.Key.HAS_POSITION, pos != null)
                    if (pos != null) {
                        putDouble(CameraMockProtocol.Key.LAT, pos.first)
                        putDouble(CameraMockProtocol.Key.LON, pos.second)
                    }
                }
            }

            // 模块侧回传"这次拦到了什么"，供 Test 页观测（真机上日志常常拿不到）
            CameraMockProtocol.Method.REPORT -> {
                val text = buildString {
                    append(stamp())
                    append(" pkg=").append(claimed ?: "-")
                    append(" point=").append(extras?.getString(CameraMockProtocol.Key.POINT_NAME) ?: "-")
                    append(" video=").append(extras?.getString(CameraMockProtocol.Key.VIDEO_NAME) ?: "-")
                    append(" start=").append((extras?.getLong(CameraMockProtocol.Key.START_MS, 0L) ?: 0L) / 1000)
                    append("s dist=").append(extras?.getDouble(CameraMockProtocol.Key.DISTANCE_M, -1.0)?.toInt())
                    append("m status=").append(extras?.getString(CameraMockProtocol.Key.STATUS) ?: "-")
                }
                CameraMockRuntime.noteReport(text)
                Bundle().apply { putString(CameraMockProtocol.Key.STATUS, "ok") }
            }

            else -> denied("unknown-method")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("no context")
        val uid = Binder.getCallingUid()
        // openFile 拿不到 extras：包名声明只能靠调用方在 uri 上带 query 参数（模块侧会带 uid，
        // 这里只做 uid 自洽校验 —— 见 gate 的说明）
        val deny = gate(uid, uri.getQueryParameter("pkg"), uri.getQueryParameter("uid")?.toIntOrNull() ?: -1)
        if (deny != null) throw FileNotFoundException("denied: $deny")

        val id = uri.lastPathSegment ?: throw FileNotFoundException("no video id")
        val video = CameraMockStore.points(ctx)
            .asSequence()
            .flatMap { it.videos.asSequence() }
            .firstOrNull { it.id == id }
            ?: throw FileNotFoundException("unknown video $id")

        val file = CameraMockStore.videoFile(ctx, video.file)
        if (!file.exists()) throw FileNotFoundException("missing file ${video.file}")
        CameraMockRuntime.noteAccess("${stamp()} openFile $id uid=$uid")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * 门禁：返回 null = 放行，否则返回拒绝原因（写进回包与日志）。
     *
     * 判定顺序：
     *  1. 自己（Test 页自测）与系统/root：放行；
     *  2. 目标应用清单为空：放行 —— 这是首装的默认状态，"未指定目标"意味着用户还没做限制，
     *     此时**只应答清单不泄露文件**（文件仍需按 id 取，且清单里只有我们自己的视频元数据）；
     *  3. 清单非空：要求调用方在请求里声明包名 ∈ 清单，且**声明的 uid 与内核给出的
     *     `Binder.getCallingUid()` 一致**（uid 无法伪造，包名可以 —— 这是本门禁的已知边界：
     *     它挡的是"随手扫到我们的应用"，不是恶意应用）。
     */
    private fun gate(uid: Int, claimedPackage: String?, claimedUid: Int): String? {
        val ctx = context ?: return "no-context"
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0) return null
        val targets = ctx.cameraMockTargets
        if (targets.isEmpty()) return null
        if (claimedPackage.isNullOrBlank()) return "target-list-set-but-no-pkg-claim"
        if (claimedUid != uid) return "uid-claim-mismatch"
        if (targets.none { TextUtils.equals(it, claimedPackage) }) return "not-a-target"
        return null
    }

    private fun denied(reason: String): Bundle = Bundle().apply {
        putString(CameraMockProtocol.Key.STATUS, "denied:$reason")
    }

    private fun stamp(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format(
            "%02d:%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }

    // 本 provider 只提供"命令 + 文件"，不支持表结构访问
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
