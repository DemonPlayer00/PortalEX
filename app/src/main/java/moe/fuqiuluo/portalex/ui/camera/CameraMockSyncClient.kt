package moe.fuqiuluo.portalex.ui.camera

import android.net.LocalSocket
import android.net.LocalSocketAddress

/**
 * PortalEX app → system_server 选片服务的**库同步**通道。
 *
 * 只用于把 app 侧刚录制的库推给 system_server 并触发“落库成功才删本地”；
 * 视频本体不经过这里，由 system_server 通过 CameraMockProvider 的 fd 读取。
 * 对端会校验 uid（模块 owner uid 在白名单内）。
 */
object CameraMockSyncClient {
    private const val SOCKET = "portalex_camera_mock"

    fun importLibrary(timeoutMs: Int = 5_000): Boolean = runCatching {
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT)
            )
            socket.soTimeout = timeoutMs
            socket.outputStream.write("IMPORT_LIBRARY\n".toByteArray())
            socket.outputStream.flush()
            val reply = socket.inputStream.bufferedReader().readLine().orEmpty()
            reply.startsWith("OK")
        } finally {
            runCatching { socket.close() }
        }
    }.getOrDefault(false)
}
