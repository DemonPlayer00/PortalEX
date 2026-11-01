package moe.fuqiuluo.portalex.ui.camera

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PortalEX app → system_server 选片服务的**库同步**通道。
 *
 * 只用于把 app 侧刚录制的库推给 system_server 并触发“落库成功才删本地”；
 * 视频本体不经过这里，由 system_server 通过 CameraMockProvider 的 fd 读取。
 * 对端会校验 uid（模块 owner uid 在白名单内）。
 */
object CameraMockSyncClient {
    private const val SOCKET = "portalex_camera_mock"

    fun importLibrary(timeoutMs: Int = 300_000): Boolean = runCatching {
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

    /**
     * 从 system_server 的系统库导出视频到 app 缓存。
     *
     * 视频本体不再经过 cammock 的 io/export 目录，而是由 system_server 直接
     * 通过 SCM_RIGHTS 传 fd；app 只负责把 fd 复制到临时文件供 ZIP 导出。
     */
    fun exportVideo(context: android.content.Context, videoId: String, timeoutMs: Int = 300_000): Result<File> =
        runCatching {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = timeoutMs
                socket.outputStream.write("EXPORT_VIDEO\t$videoId\n".toByteArray())
                socket.outputStream.flush()
                val reply = socket.inputStream.bufferedReader().readLine().orEmpty()
                if (!reply.startsWith("OK")) error("system_server 导出失败：$reply")
                val fd = socket.ancillaryFileDescriptors?.firstOrNull()
                    ?: error("system_server 未返回视频 fd")
                val pfd = ParcelFileDescriptor.dup(fd)
                val dest = File(context.cacheDir, "camera-export-$videoId.mp4")
                FileInputStream(pfd.fileDescriptor).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                pfd.close()
                if (!dest.isFile || dest.length() == 0L) {
                    dest.delete()
                    error("system_server 返回的视频为空")
                }
                dest
            } finally {
                runCatching { socket.close() }
            }
        }
}
