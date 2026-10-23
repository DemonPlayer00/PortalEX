package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import moe.fuqiuluo.portalex.ext.suPath
import java.io.File
import java.util.zip.ZipInputStream

/** 摄像头 hook 实现的外置安装器；只管理 app 私有目录中的实现。 */
object CammockManager {
    private const val DIR = "cammock"
    private const val RUNTIME = "/data/local/tmp/portalex/cammock"
    const val PROVIDER = "$RUNTIME/provider"
    const val META = "$RUNTIME/meta.bin"
    const val TAGS = "$RUNTIME/tags.bin"

    data class Install(val supported: Boolean, val models: List<String>, val message: String,
                       val module: Boolean = false)

    fun installDir(context: Context) = File(context.filesDir, DIR)
    fun currentDir(context: Context) = File(installDir(context), "current")
    fun providerFile(context: Context) = File(currentDir(context), "provider")

    private fun manifest(context: Context) = File(currentDir(context), "cammock.json")

    private fun manifestJson(context: Context): JSONObject? =
        runCatching { JSONObject(manifest(context).readText()) }.getOrNull()

    fun isInstalled(context: Context): Boolean {
        val dir = currentDir(context)
        if (!File(dir, "cammock.json").isFile) return false
        return providerFile(context).isFile ||
            File(dir, "module.prop").isFile ||
            File(dir, "install.sh").isFile || File(dir, "cammock_install.sh").isFile
    }

    fun isModule(context: Context): Boolean =
        manifestJson(context)?.optString("type") == "magisk-module"

    private fun moduleId(context: Context): String =
        manifestJson(context)?.optString("moduleId")?.takeIf { it.isNotBlank() }
            ?: "portalex-cammock-sagit"

    fun supportedModels(context: Context): List<String> {
        val a = manifestJson(context)?.optJSONArray("supportedModels") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    /** 导入 zip；实现包可以含 provider，也可以含 module.prop + install.sh。 */
    fun importArchive(context: Context, uri: Uri): Install = runCatching {
        val staging = File(context.cacheDir, "cammock-import-${System.currentTimeMillis()}").apply {
            deleteRecursively(); mkdirs()
        }
        var hasProvider = false
        var hasManifest = false
        var hasModule = false
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("/") || name.contains("..")) continue
                    val out = File(staging, name)
                    if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                    if (entry.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { out -> zip.copyTo(out) }
                    if (name == "provider" || name == "portal_camera_provider") hasProvider = true
                    if (name == "cammock.json" || name == "manifest.json") hasManifest = true
                    if (name == "module.prop" || name == "install.sh" || name == "cammock_install.sh") hasModule = true
                }
            }
        } ?: error("无法读取 zip")
        if (!hasManifest) error("zip 必须包含 cammock.json")
        if (!hasProvider && !hasModule) error("zip 必须包含 provider 或 module.prop/install script")
        if (!File(staging, "provider").exists() && File(staging, "portal_camera_provider").exists()) {
            File(staging, "portal_camera_provider").renameTo(File(staging, "provider"))
        }
        if (!File(staging, "cammock.json").exists() && File(staging, "manifest.json").exists()) {
            File(staging, "manifest.json").renameTo(File(staging, "cammock.json"))
        }
        // 保留原始 zip：MI6 等实现会在自己的 install.sh 中交给 APatch/Magisk。
        context.contentResolver.openInputStream(uri)?.use { input ->
            File(staging, "source.zip").outputStream().use { input.copyTo(it) }
        }
        val j = JSONObject(File(staging, "cammock.json").readText())
        val models = j.optJSONArray("supportedModels")?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val actual = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT).filter { it.isNotBlank() }
        val supported = models.isEmpty() || models.any { m ->
            actual.any { it.equals(m, true) || m == "*" }
        }
        val current = currentDir(context)
        current.deleteRecursively()
        current.parentFile?.mkdirs()
        if (!staging.renameTo(current)) error("安装目录写入失败")
        Install(
            supported, models,
            if (supported) "已安装：${models.joinToString()}" else
                "已安装，但机型不在适配列表：当前 ${Build.MODEL}，支持 ${models.joinToString()}",
            j.optString("type") == "magisk-module"
        )
    }.getOrElse { Install(false, emptyList(), "安装失败：${it.message}") }

    /** 标准视频导出：实现写入 io/export/<videoId>.mp4。 */
    fun exportVideo(context: Context, videoId: String): Result<File> = runCatching {
        if (!isInstalled(context)) error("尚未安装摄像头实现")
        val box = File(currentDir(context), "io/export")
        box.deleteRecursively(); box.mkdirs()
        File(box, "request.txt").writeText(videoId)
        runScript(context, "export.sh").getOrThrow()
        val file = File(box, "$videoId.mp4")
        if (!file.isFile) error("实现未生成导出视频：$videoId")
        file
    }

    /** 标准上传：app 生成 io/upload 快照，由实现自己的 upload.sh 接收。 */
    fun upload(context: Context): Result<Int> = runCatching {
        if (!isInstalled(context)) error("尚未安装摄像头实现")
        val box = File(currentDir(context), "io/upload")
        box.deleteRecursively(); box.mkdirs()
        val routes = CameraMockStore.routes(context)
        File(box, "routes.json").writeText(
            moe.fuqiuluo.xposed.camera.CameraMockCodec.encodeRoutes(routes)
        )
        routes.forEach { route ->
            route.videos.forEach { video ->
                val src = CameraMockStore.videoFile(context, video.file)
                if (src.isFile) src.copyTo(File(box, video.file), overwrite = true)
            }
        }
        runScript(context, "upload.sh").getOrThrow()
        routes.sumOf { it.videos.size }
    }

    /** 标准下载：实现写入 io/download，app 再把它导入本地库。 */
    fun download(context: Context): Result<Int> = runCatching {
        if (!isInstalled(context)) error("尚未安装摄像头实现")
        val box = File(currentDir(context), "io/download")
        box.deleteRecursively(); box.mkdirs()
        runScript(context, "download.sh").getOrThrow()
        val json = File(box, "routes.json")
        if (!json.isFile) error("实现未生成 io/download/routes.json")
        val routes = moe.fuqiuluo.xposed.camera.CameraMockCodec.decodeRoutes(json.readText())
        val videoDir = CameraMockStore.dir(context)
        videoDir.mkdirs()
        routes.forEach { route ->
            route.videos.forEach { video ->
                val src = File(box, video.file)
                if (src.isFile) src.copyTo(File(videoDir, video.file), overwrite = true)
            }
        }
        CameraMockStore.setRoutes(context, routes)
        routes.sumOf { it.videos.size }
    }

    /** PortalEX 只调用实现自己的 start.sh；provider/HAL 细节由实现负责。 */
    fun start(context: Context): Result<String> = runScript(context, "start.sh")

    /** 停止也由实现自己的 stop.sh 负责。 */
    fun stop(context: Context): Result<String> = runScript(context, "stop.sh")

    private fun runScript(context: Context, name: String): Result<String> = runCatching {
        if (!isInstalled(context)) error("尚未安装摄像头实现")
        val script = File(currentDir(context), name)
        if (!script.isFile) error("实现缺少 $name")
        val p = ProcessBuilder(context.suPath, "-c",
            "cd '${currentDir(context).absolutePath}' && sh '${script.absolutePath}'")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly(); error("$name 执行超时")
        }
        if (p.exitValue() != 0) error("$name 失败：${out.trim()}")
        out.trim().ifBlank { "$name 执行完成" }
    }

    /** 卸载私有实现；实现自己的 uninstall.sh 负责 root 模块清理。 */
    fun uninstall(context: Context): Result<String> = runCatching {
        if (!isInstalled(context)) return@runCatching "没有已安装实现"
        val dir = currentDir(context)
        val uninstallerName = manifestJson(context)?.optString("uninstaller")?.takeIf { it.isNotBlank() } ?: "uninstall.sh"
        val uninstaller = File(dir, uninstallerName)
        if (uninstaller.isFile) {
            val p = ProcessBuilder(context.suPath, "-c", "cd '${dir.absolutePath}' && sh '${uninstaller.absolutePath}'")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            if (p.exitValue() != 0) error("实现 uninstaller 失败：$out")
        }
        currentDir(context).deleteRecursively()
        installDir(context).deleteRecursively()
        runCatching {
            ProcessBuilder(context.suPath, "-c", "rm -rf $RUNTIME").start()
                .waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
        }
        "摄像头实现已卸载"
    }
}
