package moe.fuqiuluo.xposed.hooks.camera

import android.content.Context
import android.net.Uri
import android.os.Bundle
import moe.fuqiuluo.xposed.camera.CameraMockProtocol
import moe.fuqiuluo.xposed.utils.Logger

/** system_server 里的 Context/ContentResolver 入口（不依赖 Activity）。 */
internal object CameraMockServerContext {
    @Volatile private var cached: Context? = null

    fun get(): Context? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val c = runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                val current = activityThread.getMethod("currentActivityThread").invoke(null)
                current?.javaClass?.getMethod("getSystemContext")?.invoke(current) as? Context
            }.getOrNull()
            cached = c
            return c
        }
    }



    fun appRoutes(): String? {
        val ctx = get() ?: return null
        val uri = Uri.parse("content://${CameraMockProtocol.AUTHORITY}")
        val reply = runCatching {
            ctx.contentResolver.call(uri, CameraMockProtocol.Method.ROUTES, null, Bundle())
        }.onFailure { Logger.warn("选片服务：调用 app provider 失败 ${it.message}") }.getOrNull()
        return reply?.getString(CameraMockProtocol.Key.ROUTES_JSON)
    }

    fun deleteLocalVideos(): Int {
        val ctx = get() ?: return 0
        val uri = Uri.parse("content://${CameraMockProtocol.AUTHORITY}")
        val reply = runCatching {
            ctx.contentResolver.call(
                uri, CameraMockProtocol.Method.DELETE_LOCAL_VIDEOS, null, Bundle()
            )
        }.getOrNull()
        return reply?.getInt(CameraMockProtocol.Key.DELETED, 0) ?: 0
    }
}
