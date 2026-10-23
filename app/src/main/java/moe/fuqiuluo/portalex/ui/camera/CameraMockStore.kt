package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.xposed.camera.CameraMockCodec
import moe.fuqiuluo.xposed.camera.CameraMockManifest
import moe.fuqiuluo.xposed.camera.CameraMockProtocol
import moe.fuqiuluo.xposed.camera.CameraTags
import moe.fuqiuluo.xposed.camera.CameraVideo
import moe.fuqiuluo.xposed.camera.CameraVideoPoint
import moe.fuqiuluo.xposed.camera.CameraVideoTrackPoint
import java.io.File
import java.util.UUID

/**
 * **视频点库**：录制/导入进来的视频与它们挂载的坐标（app 侧唯一的写者）。
 *
 * 存储分两处，各司其职：
 *  · **元数据**（点、视频、标签、时长）→ `portal` 偏好里的一个 JSON 串（[CameraMockCodec]），
 *    与路线库同一套路；开关/筛选/目标应用另存成简单类型 —— 库坏掉不该连带把开关状态也弄丢；
 *  · **视频本体** → `filesDir/camera` 目录下的 mp4 文件（应用私有目录）。模块侧**不直接读文件**
 *    （它跑在别人的 uid 里，没有权限），一律经 [moe.fuqiuluo.portalex.service.CameraMockProvider]
 *    拿 fd。
 */
object CameraMockStore {

    private const val DIR_NAME = "camera"

    /** 视频库目录（不存在则创建）。录制与导入都落在这里。 */
    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    // ---- 偏好（与 ext/Perfs.kt 同一份 `portal` 文件；属性定义见文件末尾）----

    // ---- 库读写 ----

    fun points(context: Context): List<CameraVideoPoint> =
        CameraMockCodec.decodePoints(context.cameraMockLibrary)

    fun setPoints(context: Context, points: List<CameraVideoPoint>) {
        context.cameraMockLibrary = CameraMockCodec.encodePoints(points)
        context.cameraMockUpdatedAt = System.currentTimeMillis()
    }

    fun point(context: Context, id: String): CameraVideoPoint? =
        points(context).firstOrNull { it.id == id }

    /** 新增或整条替换（按 id） */
    fun upsert(context: Context, point: CameraVideoPoint) {
        val list = points(context).toMutableList()
        val index = list.indexOfFirst { it.id == point.id }
        if (index >= 0) list[index] = point else list.add(point)
        setPoints(context, list)
    }

    /** 删除点位，并把它名下视频的本体文件一并删掉（避免留下孤儿文件占空间） */
    fun removePoint(context: Context, id: String) {
        val list = points(context)
        val target = list.firstOrNull { it.id == id } ?: return
        target.videos.forEach { deleteVideoFile(context, it.file) }
        setPoints(context, list.filterNot { it.id == id })
    }

    fun newPoint(name: String, lat: Double, lon: Double): CameraVideoPoint =
        CameraVideoPoint(UUID.randomUUID().toString(), name, lat, lon, emptyList())

    // ---- 视频文件 ----

    /** 录制目标文件：先占名字（id 即文件名），录完由 [probeDurationMs] 补时长 */
    fun newVideoFile(context: Context): File =
        File(dir(context), "${UUID.randomUUID()}.mp4")

    fun videoFile(context: Context, fileName: String): File = File(dir(context), fileName)

    fun deleteVideoFile(context: Context, fileName: String) {
        runCatching { videoFile(context, fileName).takeIf { it.exists() }?.delete() }
    }

    /**
     * 时长（毫秒）。取不到返回 0：起播偏移按 0 处理，循环交给解码器 EOS。
     *
     * 注意**不用 `use {}`**：`MediaMetadataRetriever` 直到 API 29 才实现 `AutoCloseable`，
     * 在 minSdk 26 的机器上 `use` 会在收尾时调到不存在的 `close()`（NoSuchMethodError）。
     * 这里手动 finally release。
     */
    fun probeDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 从外部文件导入（SAF 选中的 `content://`）。
     *
     * 为什么是**拷贝**而不是记住原路径：原文件可能在别的应用私有目录/可移动存储上，
     * 也可能随时被用户删掉；而模块侧只能经 provider 拿 fd，必须是**我们自己的**文件。
     * 失败（不可读/写盘失败/空文件）返回 null，并清掉半截文件。
     */
    fun importVideo(context: Context, uri: Uri): CameraVideo? {
        val id = UUID.randomUUID().toString()
        val dest = File(dir(context), "$id.mp4")
        val copied = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.exists() && dest.length() > 0L
        } catch (t: Throwable) {
            false
        }
        if (!copied) {
            runCatching { dest.delete() }
            return null
        }
        val displayName = displayName(context, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        return CameraVideo(
            id = id,
            name = displayName ?: dest.name,
            file = dest.name,
            durationMs = probeDurationMs(dest),
            tags = emptyList(),
            addedAt = System.currentTimeMillis()
        )
    }

    /**
     * 录制结束后登记一个新视频（文件已由录制页写在库目录里）。
     *
     * 默认名取**拍摄时间**而不是文件名：文件名是 uuid，列表里看到一串十六进制
     * 既认不出是哪一段，也没法在选素材时判断先后。
     */
    fun registerRecorded(
        context: Context,
        file: File,
        name: String?,
        tags: List<String> = emptyList(),
        recordedAt: Long = 0L,
        track: List<CameraVideoTrackPoint> = emptyList()
    ): CameraVideo =
        CameraVideo(
            id = file.nameWithoutExtension,
            name = name?.takeIf { it.isNotBlank() } ?: defaultRecordName(),
            file = file.name,
            durationMs = probeDurationMs(file),
            tags = CameraTags.sanitize(tags),
            addedAt = System.currentTimeMillis(),
            recordedAt = recordedAt,
            track = CameraTags.sanitizeTrack(track)
        )

    /** 默认录制名：`录制 09-12 17:34`（月-日 时:分，够用且不跨年歧义） */
    private fun defaultRecordName(): String =
        java.text.SimpleDateFormat("录制 MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        val cursor: Cursor? = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) it.getString(index) else null
        }
    }.getOrNull()

    // ---- 清单（下发给模块侧的唯一形态）----

    /** 库内出现过的全部标签（预设在前，自定义按首次出现顺序），供筛选与打标签界面用 */
    fun allTags(context: Context): List<String> {
        val custom = LinkedHashSet<String>()
        points(context).forEach { p -> p.videos.forEach { v -> v.tags.forEach { custom.add(it) } } }
        return CameraTags.PRESETS + custom.filterNot { tag -> CameraTags.PRESETS.any { it.equals(tag, true) } }
    }

    fun manifest(context: Context): CameraMockManifest = CameraMockManifest(
        enabled = context.cameraMockEnabled,
        tagFilter = context.cameraMockTagFilter.toList(),
        points = points(context),
        targets = context.cameraMockTargets.toList(),
        updatedAt = context.cameraMockUpdatedAt
    )
}

// ---- 偏好属性（**顶层扩展**而不是 object 的成员扩展）----
//
// 为什么必须是顶层：成员扩展属性只能"带着 object 前缀"使用，跨包引用还得靠
// `import CameraMockStore.cameraMockEnabled` 这种别扭写法；顶层扩展与项目其它偏好
// （ext/Perfs.kt 的 `Context.speed` 等）完全同构，谁都能直接 `context.cameraMockEnabled`。

/** 摄像头模拟总开关（默认关：这是"开启后才拦摄像头"的功能，静默生效不合适） */
var Context.cameraMockEnabled: Boolean
    get() = sharedPrefs.getBoolean(CameraMockProtocol.Pref.ENABLED, false)
    set(value) = sharedPrefs.edit { putBoolean(CameraMockProtocol.Pref.ENABLED, value) }

/** 标签筛选（全含语义，见 CameraMockSelector.matches） */
var Context.cameraMockTagFilter: Set<String>
    get() = sharedPrefs.getStringSet(CameraMockProtocol.Pref.TAG_FILTER, emptySet()) ?: emptySet()
    set(value) = sharedPrefs.edit {
        putStringSet(CameraMockProtocol.Pref.TAG_FILTER, CameraTags.sanitize(value).toSet())
    }

/** 允许拦截的目标应用包名；空 = 不限制（见 CameraMockProvider.gate） */
var Context.cameraMockTargets: Set<String>
    get() = sharedPrefs.getStringSet(CameraMockProtocol.Pref.TARGETS, emptySet()) ?: emptySet()
    set(value) = sharedPrefs.edit {
        putStringSet(
            CameraMockProtocol.Pref.TARGETS,
            value.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        )
    }

/** 视频库的原始 JSON（读写在 [CameraMockStore] 里；这里私有，避免别处绕过编码器写坏它） */
private var Context.cameraMockLibrary: String
    get() = sharedPrefs.getString(CameraMockProtocol.Pref.LIBRARY, null) ?: ""
    // commit=true：库是"用户一条条录进来的素材索引"，丢了就得重录一遍
    set(value) = sharedPrefs.edit(commit = true) { putString(CameraMockProtocol.Pref.LIBRARY, value) }

private var Context.cameraMockUpdatedAt: Long
    get() = sharedPrefs.getLong("cameraMockUpdatedAt", 0L)
    set(value) = sharedPrefs.edit { putLong("cameraMockUpdatedAt", value) }
