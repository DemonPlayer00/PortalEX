package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import moe.fuqiuluo.portalex.ext.suPath
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
    private const val FILE_SOCKET = "/data/system/portalex/camera.sock"

    /**
     * 系统侧视频目录的 root 侧自愈。
     *
     * 清空素材库时只删 camera/ 子目录，/data/system/portalex 若曾被 root 工具创建，
     * system_server 没有权限重新建 camera/videos，上传会以 ENOENT 失败。
     * 顺便补 KernelSU/APatch 的 connectto 规则，避免 SELinux 再次挡住 socket。
     */
    fun prepareStorage(context: Context): Boolean = runCatching {
        val script = """
            mkdir -p /data/system/portalex/camera/videos
            chown system:system /data/system/portalex /data/system/portalex/camera /data/system/portalex/camera/videos 2>/dev/null || true
            chmod 771 /data/system/portalex 2>/dev/null || true
            chmod 775 /data/system/portalex/camera /data/system/portalex/camera/videos 2>/dev/null || true
            if [ -x /data/adb/ksud ]; then
                /data/adb/ksud sepolicy patch "allow untrusted_app system_server unix_stream_socket connectto" >/dev/null 2>&1 || true
            elif [ -x /data/adb/apd ]; then
                /data/adb/apd sepolicy --live "allow untrusted_app system_server unix_stream_socket connectto" >/dev/null 2>&1 || true
            fi
        """.trimIndent()
        val p = ProcessBuilder(context.suPath, "-c", script)
            .redirectErrorStream(true).start()
        p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    fun importLibrary(context: Context? = null, timeoutMs: Int = 300_000): Boolean {
        if (importLibraryDirect(timeoutMs)) return true
        return context != null && importLibraryViaRoot(context, timeoutMs)
    }

    private fun importLibraryDirect(timeoutMs: Int): Boolean = runCatching {
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
     * root 侧 nc 走文件系统 socket 上传。root/ksu 域不需要 app 对
     * system_server unix_stream_socket 的 connectto 权限。
     */
    private fun importLibraryViaRoot(context: Context, timeoutMs: Int): Boolean = runCatching {
        val cmd = "printf 'IMPORT_LIBRARY\\n' | /system/bin/nc -U -q 2 '$FILE_SOCKET' 2>/dev/null"
        val p = ProcessBuilder(context.suPath, "-c", cmd)
            .redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        output.contains("OK")
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
