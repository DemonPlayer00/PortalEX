package moe.fuqiuluo.xposed.hooks.camera

import android.content.Context
import android.net.Uri
import android.os.Bundle
import moe.fuqiuluo.xposed.camera.CameraMockCodec
import moe.fuqiuluo.xposed.camera.CameraMockProtocol
import moe.fuqiuluo.xposed.camera.CameraTags
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



    /**
     * 读取 app 当前 manifest 里的标签筛选。
     *
     * OnePlus/provider 的 native SELECT 请求不带标签；system_server 选片时必须回源到
     * app 的 manifest，否则标签筛选只影响应用侧，选片服务会继续从全库挑片。
     */
    fun appTagFilter(): List<String> {
        val ctx = get() ?: return emptyList()
        val uri = Uri.parse("content://${CameraMockProtocol.AUTHORITY}")
        val reply = runCatching {
            ctx.contentResolver.call(uri, CameraMockProtocol.Method.MANIFEST, null, Bundle())
        }.onFailure { Logger.warn("选片服务：读取 app manifest 失败 ${it.message}") }.getOrNull()
        val json = reply?.getString(CameraMockProtocol.Key.MANIFEST_JSON) ?: return emptyList()
        val manifest = CameraMockCodec.decode(json) ?: return emptyList()
        return CameraTags.sanitize(manifest.tagFilter)
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
