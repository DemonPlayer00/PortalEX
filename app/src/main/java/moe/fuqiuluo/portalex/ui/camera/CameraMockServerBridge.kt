package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import moe.fuqiuluo.xposed.camera.CameraMockCodec
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * App → system_server 的上传/下载文件桥。
 *
 * 过渡阶段先用共享目录（/data/local/tmp/portalex）传大文件；system_server 的
 * CameraMockSelectionServer 在下次相机请求前导入。
 *
 * 正式版应换成 system_server Binder + ParcelFileDescriptor；这里保持接口语义，
 * 方便后续替换实现而不改 UI。
 */
object CameraMockServerBridge {

    const val UPLOAD_DIR = "/data/local/tmp/portalex/upload"
    const val DOWNLOAD_DIR = "/data/local/tmp/portalex/download"

    /** 主动上传：通过 selection socket 让 system_server 从 app provider 拉库。 */
    fun upload(context: Context): Result<Int> = runCatching {
        val routes = CameraMockStore.routes(context)
        if (routes.isEmpty()) {
            android.util.Log.w("CameraMockServerBridge", "upload skipped: app routes empty")
            return@runCatching 0
        }
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(
                    CameraMockSelectionSocket.NAME,
                    LocalSocketAddress.Namespace.ABSTRACT
                )
            )
            socket.outputStream.write("IMPORT_LIBRARY\n".toByteArray())
            socket.outputStream.flush()
            val reply = socket.inputStream.bufferedReader().readLine()
            android.util.Log.i("CameraMockServerBridge", "upload reply=$reply")
            if (reply?.startsWith("OK\t") != true) error(reply ?: "no reply")
            val count = reply.split('\t').getOrNull(2)?.toIntOrNull()
                ?: CameraMockStore.routes(context).sumOf { it.videos.size }
            count
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 从 system_server 取视频 fd；返回的流关闭后 fd 释放。 */
    fun openVideo(videoId: String): Result<InputStream> = runCatching {
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(
                    CameraMockSelectionSocket.NAME,
                    LocalSocketAddress.Namespace.ABSTRACT
                )
            )
            socket.outputStream.write("EXPORT_VIDEO\t$videoId\n".toByteArray())
            socket.outputStream.flush()
            val reply = socket.inputStream.bufferedReader().readLine()
            if (reply?.startsWith("OK\t") != true) error(reply ?: "no reply")
            val fds = socket.ancillaryFileDescriptors
            val fd = fds?.firstOrNull() ?: error("没有收到视频 fd")
            FileInputStream(fd)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 通过 system_server 的 selection socket 下载当前库；不再依赖共享目录。 */
    fun downloadSnapshot(): String? {
        android.util.Log.i("CameraMockServerBridge", "download start")
        val socket = LocalSocket()
        return runCatching {
            socket.connect(
                LocalSocketAddress(
                    CameraMockSelectionSocket.NAME,
                    LocalSocketAddress.Namespace.ABSTRACT
                )
            )
            socket.outputStream.write("GET_LIBRARY\n".toByteArray())
            socket.outputStream.flush()
            socket.inputStream.bufferedReader().readLine().also {
                android.util.Log.i("CameraMockServerBridge", "download reply=${it?.take(40)}")
            }
        }.getOrNull()?.takeIf { it.startsWith("LIB	") }?.substringAfter("LIB	")
            .also { runCatching { socket.close() } }
    }

    private object CameraMockSelectionSocket {
        const val NAME = "portalex_camera_mock"
    }
}
