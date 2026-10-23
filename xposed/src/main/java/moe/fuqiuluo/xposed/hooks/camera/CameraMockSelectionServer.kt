package moe.fuqiuluo.xposed.hooks.camera

import android.net.LocalServerSocket
import android.net.LocalSocket
import moe.fuqiuluo.xposed.camera.CameraMockSelection
import moe.fuqiuluo.xposed.camera.CameraMockServerStore
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.File
import moe.fuqiuluo.xposed.camera.CameraMockCodec
import moe.fuqiuluo.xposed.camera.CameraRoute
import android.net.Uri
import android.os.Bundle

/**
 * system_server 侧的选片服务。
 *
 * 使用 Android LocalServerSocket（抽象命名空间，不落 /dev 文件）。provider 通过
 * AF_UNIX 连接后发送：
 *
 *   SELECT\t<package>\t<lat>\t<lon>\t<nowMs>\t<tag1,tag2>
 *
 * 返回：
 *
 *   OK\t<pointId>\t<videoId>\t<file>\t<startMs>\t<durationMs>\t<path>
 *   ERR\t<reason>
 *
 * 当前只返回视频路径，不传 fd；OnePlus provider 以 root/ksu 身份运行时可读
 * /data/system/portalex/camera/videos。后续可换成 SCM_RIGHTS。
 */
internal object CameraMockSelectionServer {

    const val SOCKET_NAME = "portalex_camera_mock"

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        runCatching {
            Thread({
                LocalServerSocket(SOCKET_NAME).use { server ->
                    Logger.info("选片服务：监听 $SOCKET_NAME")
                    while (true) {
                        val client = server.accept()
                        runCatching { handle(client) }.onFailure {
                            Logger.warn("选片服务：处理失败 ${it.message}")
                        }
                        runCatching { client.close() }
                    }
                }
            }, "PortalEX-SelectionServer").apply {
                isDaemon = true
                start()
            }
        }.onFailure {
            started = false
            Logger.error("选片服务启动失败", it)
        }
    }

    private val uploadDir = File("/data/local/tmp/portalex/upload")


    /**
     * 正式导入通道：通过 app 的 CameraMockProvider 拉 routes 和视频 fd。
     * 不再读 /data/local/tmp（system_server 对 shell_data_file 无 SELinux 权限）。
     */
    @Synchronized
    private fun syncAppLibrary() {
        val json = CameraMockServerContext.appRoutes() ?: return
        val incoming = runCatching { CameraMockCodec.decodeRoutes(json) }.getOrNull() ?: return
        if (incoming.isEmpty()) return
        val current = CameraMockServerStore.load()
        // app 是数据源：按当前 app 库同步。已一致时不做重复 fd 拷贝。
        if (CameraMockCodec.encodeRoutes(incoming) == CameraMockCodec.encodeRoutes(current)) return
        importPendingUpload(json)
    }

    @Synchronized
    private fun importPendingUpload(json: String? = null) {
        val payload = json ?: CameraMockServerContext.appRoutes() ?: return
        runCatching {
            val routes = CameraMockCodec.decodeRoutes(payload)
            if (routes.isEmpty()) return@runCatching
            val resolver = CameraMockServerContext.get()?.contentResolver
                ?: return@runCatching
            routes.forEach { route ->
                route.videos.forEach { video ->
                    val uri = Uri.parse(
                        "content://${moe.fuqiuluo.xposed.camera.CameraMockProtocol.AUTHORITY}/video/${video.id}" +
                            "?pkg=android&uid=1000"
                    )
                    runCatching {
                        val dest = CameraMockServerStore.videoFile(video.id)
                        if (!dest.exists() || dest.length() == 0L) {
                            resolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { out -> input.copyTo(out) }
                            }
                        }
                    }.onFailure {
                        Logger.warn("选片服务：拉取视频 ${video.id} 失败 ${it.message}")
                    }
                }
            }
            CameraMockServerStore.save(routes)
            val keep = routes.flatMap { it.videos }.map { it.id }.toSet()
            val removed = CameraMockServerStore.pruneOrphanVideos(keep)
            Logger.info("选片服务：已通过 provider 导入 app 库 ${routes.size} 条路线，清理孤立视频 $removed 个")
            val deleted = CameraMockServerContext.deleteLocalVideos()
            Logger.info("选片服务：已请求 app 删除本地视频 $deleted 个")
        }.onFailure {
            Logger.warn("选片服务：provider 导入失败 ${it.message}")
        }
    }


    /** 选片结果落盘给 root/ksu provider 兜底读取（socket 异常时）。 */
    @Synchronized
    private fun writeSelectionFile(videoId: String, startMs: Long, path: String) {
        runCatching {
            val f = File("/data/system/portalex/camera/selection.txt")
            f.parentFile?.mkdirs()
            val tmp = File(f.absolutePath + ".tmp")
            tmp.writeText("$videoId\t$startMs\t$path\n")
            if (!tmp.renameTo(f)) {
                f.delete(); tmp.renameTo(f)
            }
        }.onFailure { Logger.warn("选片服务：写 selection 文件失败 ${it.message}") }
    }

    private fun handle(client: LocalSocket) {
        Logger.info("选片服务：handle start")
        // app 是唯一数据源：每次选片前比对 app 库，只有变化时才导入/拷贝。
        syncAppLibrary()
        Logger.info("选片服务：sync done")
        val reader = BufferedReader(InputStreamReader(client.inputStream))
        val line = reader.readLine() ?: run { Logger.info("选片服务：empty request"); return }
        if (line == "GET_LIBRARY") {
            val json = CameraMockCodec.encodeRoutes(CameraMockServerStore.load())
            reply(client, "LIB\t$json")
            return
        }
        if (line.startsWith("EXPORT_VIDEO\t")) {
            val videoId = line.substringAfter('\t').trim()
            val file = CameraMockServerStore.videoFile(videoId)
            if (!file.isFile) {
                reply(client, "ERR\tvideofile-missing")
                return
            }
            val pfd = runCatching {
                android.os.ParcelFileDescriptor.open(
                    file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
            }.getOrNull()
            if (pfd == null) {
                reply(client, "ERR\topen-failed")
                return
            }
            runCatching { client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor)) }
            reply(client, "OK\t${file.length()}")
            runCatching { pfd.close() }
            return
        }
        if (line == "IMPORT_LIBRARY") {
            // app 主动上传：不经 /data/local/tmp 共享目录，直接通过 ContentProvider
            // 拉 routes/video fd，避免 app 对 shell_data_file 无写权限。
            importPendingUpload()
            val count = CameraMockServerStore.load().sumOf { it.videos.size }
            reply(client, "OK\timported\t$count")
            return
        }
        val parts = line.split('\t')
        if (parts.size < 2 || parts[0] != "SELECT") {
            reply(client, "ERR\tbad-request")
            return
        }
        val packageName = parts.getOrNull(1).orEmpty()
        val reqLat = parts.getOrNull(2)?.toDoubleOrNull()
        val reqLon = parts.getOrNull(3)?.toDoubleOrNull()
        val now = parts.getOrNull(4)?.toLongOrNull() ?: System.currentTimeMillis()
        val tags = parts.getOrNull(5)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        val session = parts.getOrNull(6).orEmpty()
        // 只在位置模拟运行时提供虚拟相机；没有模拟就用真实相机。
        if (!FakeLoc.enable) {
            reply(client, "ERR\tlocation-mock-off")
            return
        }
        // 按需读取位置生成器最后一次更新的坐标，不做轮询。
        val simulated = FakeLoc.lastLocation
        if (simulated == null && reqLat == null && reqLon == null) {
            reply(client, "ERR\tno-simulated-position")
            return
        }
        val lat = reqLat ?: simulated?.latitude
        val lon = reqLon ?: simulated?.longitude
        Logger.info("选片服务：select lat=$lat lon=$lon tags=$tags")
        val selected = CameraMockServerStore.select(lat, lon, now, tags, session)
        Logger.info("选片服务：select result=${selected?.videoId}")
        if (selected == null) {
            reply(client, "ERR\tno-match package=$packageName")
            return
        }
        val videoFile = CameraMockServerStore.videoFile(selected.videoId)
        val pfd = runCatching {
            android.os.ParcelFileDescriptor.open(
                videoFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
        }.getOrNull()
        runCatching {
            if (pfd != null) client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
        }
        writeSelectionFile(selected.videoId, selected.startMs, videoFile.absolutePath)
        Logger.info("选片服务：reply video=${videoFile.absolutePath}")
        reply(
            client,
            "OK\t${selected.pointId}\t${selected.videoId}\t${selected.fileName}\t" +
                "${selected.startMs}\t${selected.durationMs}\t" + videoFile.absolutePath
        )
        runCatching { pfd?.close() }
        Logger.info("选片服务：reply done fd=${pfd != null}")
        Logger.info("选片服务：$packageName → ${selected.pointName}/${selected.videoName} @${selected.startMs}ms")
    }

    private fun reply(client: LocalSocket, text: String) {
        OutputStreamWriter(client.outputStream).apply {
            write(text)
            write("\n")
            flush()
        }
    }
}
