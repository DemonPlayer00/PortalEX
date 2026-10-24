package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.net.Uri
import moe.fuqiuluo.xposed.camera.CameraTags
import moe.fuqiuluo.xposed.camera.CameraVideo
import moe.fuqiuluo.xposed.camera.CameraVideoPoint
import moe.fuqiuluo.xposed.camera.CameraVideoTrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 摄像头视频包的导入/导出格式：
 *  - zip 根目录：视频文件 + `track.json`；
 *  - `track.json` 携带视频元数据、路径时间轴和（新格式）所属点位信息；
 *  - 导入按视频文件 SHA-256 去重，重复包不再新增视频。
 *
 * 旧版导出的 track.json 没有 pointId/pointLat/pointLon，导入时回退到轨迹首点；
 * 完全无轨迹的旧包回退到 (0,0)，导入后可在点编辑页调整坐标。
 */
object CameraMockArchive {

    data class ImportResult(
        val ok: Boolean,
        val duplicate: Boolean = false,
        val videoName: String = "",
        val pointName: String = "",
        val message: String = "",
    )

    private val hashCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun importZip(context: Context, uri: Uri): ImportResult {
        var videoEntryName: String? = null
        var trackJson: String? = null
        val temp = File(context.cacheDir, "camera-import-${System.currentTimeMillis()}.mp4")
        var hash = ""
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        when {
                            entry.name == "track.json" -> {
                                trackJson = zip.readBytes().toString(Charsets.UTF_8)
                            }
                            entry.name.endsWith(".mp4", ignoreCase = true) && videoEntryName == null -> {
                                videoEntryName = entry.name
                                temp.outputStream().use { out ->
                                    val md = MessageDigest.getInstance("SHA-256")
                                    val buf = ByteArray(1024 * 1024)
                                    while (true) {
                                        val n = zip.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        md.update(buf, 0, n)
                                    }
                                    hash = md.digest().joinToString("") { "%02x".format(it) }
                                }
                            }
                        }
                    }
                }
            } ?: return ImportResult(false, message = "无法读取压缩包")
        } catch (t: Throwable) {
            temp.delete()
            return ImportResult(false, message = "压缩包读取失败：${t.message}")
        }

        if (videoEntryName == null || !temp.isFile || temp.length() == 0L) {
            temp.delete()
            return ImportResult(false, message = "压缩包内没有 mp4 视频")
        }
        if (hash.isBlank()) {
            temp.delete()
            return ImportResult(false, message = "无法计算视频哈希")
        }
        if (!trackJson.isNullOrBlank()) {
            runCatching { JSONObject(trackJson!!) }.onFailure {
                temp.delete()
                return ImportResult(false, message = "track.json 格式错误：${it.message}")
            }
        }

        val meta = runCatching { JSONObject(trackJson.orEmpty()) }.getOrNull()
        val duplicate = CameraMockStore.routes(context)
            .asSequence()
            .flatMap { it.videos.asSequence() }
            .firstOrNull { video ->
                val f = CameraMockStore.videoFile(context, video.file)
                f.isFile && sha256(f) == hash
            }
        if (duplicate != null) {
            temp.delete()
            return ImportResult(
                ok = true, duplicate = true,
                videoName = duplicate.name, pointName = "",
                message = "视频已存在（SHA-256 相同）：${duplicate.name}",
            )
        }

        val videoId = UUID.randomUUID().toString()
        val fileName = "$videoId.mp4"
        val dest = File(CameraMockStore.dir(context), fileName)
        if (!temp.renameTo(dest)) {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }
        val name = meta?.optString("videoName")?.takeIf { it.isNotBlank() }
            ?: videoEntryName!!.substringBeforeLast('.')
        val tags = meta?.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        } ?: emptyList()
        val track = meta?.optJSONArray("track")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optLong("t", -1L)
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (t >= 0 && lat.isFinite() && lon.isFinite()) CameraVideoTrackPoint(t, lat, lon) else null
            }
        } ?: emptyList()
        val recordedAt = meta?.optLong("recordedAt", 0L) ?: 0L
        val durationMs = meta?.optLong("durationMs", 0L)?.coerceAtLeast(0L) ?: 0L

        val pointId = meta?.optString("pointId")?.takeIf { it.isNotBlank() }
        val pointName = meta?.optString("pointName")?.takeIf { it.isNotBlank() } ?: "导入 ${name}"
        val firstTrack = track.firstOrNull()
        val pointLat = meta?.optDouble("pointLat", firstTrack?.lat ?: 0.0) ?: (firstTrack?.lat ?: 0.0)
        val pointLon = meta?.optDouble("pointLon", firstTrack?.lon ?: 0.0) ?: (firstTrack?.lon ?: 0.0)

        val video = CameraVideo(
            id = videoId,
            name = name,
            file = fileName,
            durationMs = durationMs,
            tags = CameraTags.sanitize(tags),
            addedAt = System.currentTimeMillis(),
            recordedAt = recordedAt,
            track = CameraTags.sanitizeTrack(track),
        )
        val points = CameraMockStore.points(context).toMutableList()
        val existingPoint = pointId?.let { pid -> points.firstOrNull { it.id == pid } }
        if (existingPoint != null) {
            val updated = existingPoint.copy(videos = existingPoint.videos + video)
            points[points.indexOf(existingPoint)] = updated
            CameraMockStore.upsert(context, updated)
        } else {
            val newPoint = CameraVideoPoint(
                id = pointId ?: UUID.randomUUID().toString(),
                name = pointName,
                lat = pointLat,
                lon = pointLon,
                videos = listOf(video),
            )
            CameraMockStore.upsert(context, newPoint)
        }
        return ImportResult(true, videoName = name, pointName = pointName, message = "已导入视频：$name")
    }

    private fun sha256(file: File): String {
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        hashCache[key]?.let { return it }
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val out = md.digest().joinToString("") { "%02x".format(it) }
        hashCache[key] = out
        return out
    }
}
