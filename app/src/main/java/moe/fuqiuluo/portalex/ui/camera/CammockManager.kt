package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 摄像头 hook 实现（cammock）的外置安装器。
 *
 * APK 只保留 system_server 侧逻辑；具体设备适配的 provider/meta/tags 作为单独
 * zip 导入到 app 私有目录。使用时再释放到 /data/local/tmp 给 root provider 读取。
 */
object CammockManager {
    private const val DIR = "cammock"
    private const val RUNTIME = "/data/local/tmp/portalex/cammock"
    const val PROVIDER = "$RUNTIME/provider"
    const val META = "$RUNTIME/meta.bin"
    const val TAGS = "$RUNTIME/tags.bin"

    data class Install(val supported: Boolean, val models: List<String>, val message: String)

    fun installDir(context: Context) = File(context.filesDir, DIR)

    fun currentDir(context: Context) = File(installDir(context), "current")

    fun providerFile(context: Context) = File(currentDir(context), "provider")

    fun isInstalled(context: Context): Boolean =
        providerFile(context).isFile && File(currentDir(context), "cammock.json").isFile

    fun supportedModels(context: Context): List<String> {
        val f = File(currentDir(context), "cammock.json")
        return runCatching {
            val a = JSONObject(f.readText()).optJSONArray("supportedModels")
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** 安装导入的 zip；机型不匹配只警告，不阻止安装（便于调试同系列机型）。 */
    fun importArchive(context: Context, uri: Uri): Install = runCatching {
        val staging = File(context.cacheDir, "cammock-import-${System.currentTimeMillis()}").apply {
            deleteRecursively(); mkdirs()
        }
        var hasProvider = false
        var hasManifest = false
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
                }
            }
        } ?: error("无法读取 zip")
        if (!hasProvider || !hasManifest) error("zip 必须包含 provider 和 cammock.json")
        // 统一 provider 文件名，安装目录只保留一份。
        if (!File(staging, "provider").exists()) {
            File(staging, "portal_camera_provider").renameTo(File(staging, "provider"))
        }
        val manifestName = if (File(staging, "cammock.json").exists()) "cammock.json" else "manifest.json"
        if (manifestName == "manifest.json") File(staging, "manifest.json").renameTo(File(staging, "cammock.json"))
        val models = runCatching {
            val a = JSONObject(File(staging, "cammock.json").readText()).optJSONArray("supportedModels")
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val model = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT).filter { it.isNotBlank() }
        val supported = models.isEmpty() || models.any { m ->
            model.any { actual -> actual.equals(m, true) || m == "*" }
        }
        val current = currentDir(context)
        current.deleteRecursively()
        current.parentFile?.mkdirs()
        if (!staging.renameTo(current)) error("安装目录写入失败")
        Install(supported, models, if (supported) "已安装：${models.joinToString()}" else
            "已安装，但机型不在适配列表：当前 ${Build.MODEL}，支持 ${models.joinToString()}")
    }.getOrElse { Install(false, emptyList(), "安装失败：${it.message}") }

    /** 卸载私有实现，并清理运行时释放目录。 */
    fun uninstall(context: Context): Result<String> = runCatching {
        currentDir(context).deleteRecursively()
        installDir(context).deleteRecursively()
        val p = ProcessBuilder("su", "-c", "rm -rf $RUNTIME").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("清理运行时目录超时")
        }
        if (p.exitValue() != 0) error("清理运行时目录失败：$out")
        "摄像头实现已卸载"
    }

    /** 把私有目录里的实现释放到 root 可读的运行时目录。 */
    fun releaseRuntime(context: Context): Result<String> = runCatching {
        if (!isInstalled(context)) error("尚未安装摄像头实现")
        val src = currentDir(context).absolutePath
        val cmd = "mkdir -p $RUNTIME; " +
            "cp -f '$src/provider' $PROVIDER; " +
            (if (File(currentDir(context), "meta.bin").exists()) "cp -f '$src/meta.bin' $META; " else "") +
            (if (File(currentDir(context), "tags.bin").exists()) "cp -f '$src/tags.bin' $TAGS; " else "") +
            "chmod 755 $PROVIDER"
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly(); error("释放实现超时")
        }
        if (p.exitValue() != 0) error("释放实现失败：$out")
        "已在 $RUNTIME 释放实现"
    }
}
