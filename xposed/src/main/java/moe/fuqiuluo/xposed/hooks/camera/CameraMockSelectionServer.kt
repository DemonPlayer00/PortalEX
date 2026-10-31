package moe.fuqiuluo.xposed.hooks.camera

import android.net.LocalServerSocket
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.hooks.RoutePositionSampler
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
        val ids = incoming.flatMap { it.videos }.map { it.id }.toSet()
        // app 是数据源：metadata 已一致且系统侧文件齐全时，只清掉 app 本地副本（幂等）。
        if (CameraMockCodec.encodeRoutes(incoming) == CameraMockCodec.encodeRoutes(current) &&
            allVideosPresent(ids)
        ) {
            CameraMockServerContext.deleteLocalVideos(ids)
            return
        }
        importPendingUpload(json)
    }

    /** 系统侧是否已经有这些视频的完整副本。 */
    private fun allVideosPresent(ids: Set<String>): Boolean = ids.all { id ->
        val f = CameraMockServerStore.videoFile(id)
        f.isFile && f.length() > 0L
    }

    @Synchronized
    private fun importPendingUpload(json: String? = null): Boolean {
        val payload = json ?: CameraMockServerContext.appRoutes() ?: return false
        return runCatching {
            val routes = CameraMockCodec.decodeRoutes(payload)
            if (routes.isEmpty()) return@runCatching false
            val resolver = CameraMockServerContext.get()?.contentResolver
                ?: return@runCatching false
            var copiedAll = true
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
                        if (!dest.isFile || dest.length() == 0L) error("空文件")
                    }.onFailure {
                        copiedAll = false
                        Logger.warn("选片服务：拉取视频 ${video.id} 失败 ${it.message}")
                    }
                }
            }
            if (!copiedAll) {
                // 任何一条没落到系统库都不能删 app 本地副本；下次有变化再重试。
                Logger.warn("选片服务：app 库导入不完整，保留本地副本，等待重试")
                return@runCatching false
            }
            CameraMockServerStore.save(routes)
            val keep = routes.flatMap { it.videos }.map { it.id }.toSet()
            val removed = CameraMockServerStore.pruneOrphanVideos(keep)
            Logger.info("选片服务：已通过 provider 导入 app 库 ${routes.size} 条路线，清理孤立视频 $removed 个")
            // 只有在全部视频都确认落库后才删除 app 本地副本。
            val deleted = CameraMockServerContext.deleteLocalVideos(keep)
            Logger.info("选片服务：已请求 app 删除本地视频 $deleted 个")
            true
        }.onFailure {
            Logger.warn("选片服务：provider 导入失败 ${it.message}")
            false
        }.getOrDefault(false)
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

    /**
     * 对端 uid 白名单。抽象命名空间的 LocalServerSocket 是**全机可见**的，
     * 不校验对端就等于把"库清单 / 视频 fd / 选片结果"开放给任何本机应用。
     *
     * 允许：root、system、模块自身、media 与 cameraserver（native cammock 跑在相机 provider 进程里）。
     */
    /** AID_MEDIA：camera provider 进程；AID_CAMERASERVER：原生相机服务 */
    private const val AID_MEDIA = 1013
    private const val AID_CAMERASERVER = 1047

    private fun peerUidOf(client: LocalSocket): Int =
        runCatching { client.peerCredentials?.uid ?: -1 }.getOrDefault(-1)

    /** MI6 HAL-overlay 实现的独立 frame producer（可选安装）。 */
    private val producerUid: Int by lazy {
        runCatching {
            CameraMockServerContext.get()?.packageManager
                ?.getPackageUid("moe.fuqiuluo.cammockproducer", 0)
        }.getOrNull() ?: -1
    }

    private fun peerAllowed(uid: Int): Boolean {
        if (uid < 0) return false
        if (uid == 0 || uid == android.os.Process.SYSTEM_UID) return true
        if (uid == BinderUtils.moduleOwnerUid()) return true
        if (uid == producerUid) return true
        return uid == AID_MEDIA || uid == AID_CAMERASERVER
    }

    private fun handle(client: LocalSocket) {
        val peerUid = peerUidOf(client)
        if (!peerAllowed(peerUid)) {
            Logger.warn("选片服务：拒绝 uid=$peerUid 的连接（不在白名单内）")
            runCatching { reply(client, "ERR\tdenied") }
            return
        }
        Logger.info("选片服务：handle start (uid=$peerUid)")
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
            val ok = importPendingUpload()
            val count = CameraMockServerStore.load().sumOf { it.videos.size }
            reply(
                client,
                if (ok) "OK\timported\t$count" else "ERR\timport-failed\t$count"
            )
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
        val wireTags = parts.getOrNull(5)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        // native provider 目前发送空标签；为空时回源 app 的当前标签筛选。
        val tags = if (wireTags.isEmpty()) CameraMockServerContext.appTagFilter() else wireTags
        val session = parts.getOrNull(6).orEmpty()
        // 只在位置模拟运行时提供虚拟相机；没有模拟就用真实相机。
        if (!FakeLoc.enable) {
            reply(client, "ERR\tlocation-mock-off")
            return
        }
        // 当前表象位置：优先**立即向路线采样一次**（只计算、不写回 FakeLoc，
        // 也不改变 MotionClock 的投递节奏）；路线未播放时退回当前镜像坐标。
        // 旧实现用 FakeLoc.lastLocation，路线播放期间不会跟着每一帧更新，
        // 选片会用到旧坐标（表现为"选片变了但位置对不上"）。
        val last = FakeLoc.lastLocation
        val sample = RoutePositionSampler.sample()
        val sampled = if (sample.sampledAhead) sample.lat to sample.lon else null
        val currentValid = FakeLoc.latitude != 0.0 || FakeLoc.longitude != 0.0
        val lat = when {
            sampled != null -> sampled.first
            currentValid -> FakeLoc.latitude
            reqLat != null && reqLon != null -> reqLat
            last != null -> last.latitude
            else -> null
        }
        val lon = when {
            sampled != null -> sampled.second
            currentValid -> FakeLoc.longitude
            reqLat != null && reqLon != null -> reqLon
            last != null -> last.longitude
            else -> null
        }
        if (lat == null || lon == null) {
            reply(client, "ERR\tno-simulated-position")
            return
        }
        val source = when {
            sampled != null -> "motion-sample"
            currentValid -> "current"
            reqLat != null && reqLon != null -> "request"
            last != null -> "lastLocation"
            else -> "none"
        }
        Logger.debug(
            "选片探针：source=$source request=($reqLat,$reqLon) " +
                "current=(${FakeLoc.latitude},${FakeLoc.longitude}) " +
                "lastLocation=(${last?.latitude},${last?.longitude}) used=($lat,$lon) tags=$tags"
        )
        val selected = CameraMockServerStore.select(lat, lon, now, tags, session)
        Logger.info("选片服务：select result=${selected?.videoId}")
        if (selected != null) {
            val nearest = selected.nearestDistanceM
            val delta = if (nearest.isNaN()) Double.NaN else selected.distanceM - nearest
            Logger.debug(
                "选片探针：selected=${selected.pointName}/${selected.videoName} " +
                    "selPos=(${selected.selectedLat},${selected.selectedLon}) " +
                    "recordedAt=${selected.recordedAt} startMs=${selected.startMs} " +
                    "timeDelta=${selected.timeDistanceMs} dist=${selected.distanceM} " +
                    "nearest=$nearest delta=$delta candidates=${selected.candidateCount}"
            )
            if (!delta.isNaN() && delta > 0.5) {
                Logger.debug("选片探针：更近的候选未选中，距离差 ${"%.1f".format(delta)}m")
            }
        }
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
