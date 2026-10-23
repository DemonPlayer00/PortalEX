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
            File(dir, "install.sh").isFile
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
                    if (name == "module.prop" || name == "install.sh") hasModule = true
                }
            }
        } ?: error("无法读取 zip")
        if (!hasManifest) error("zip 必须包含 cammock.json")
        if (!hasProvider && !hasModule) error("zip 必须包含 provider 或 module.prop/install.sh")
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

    /** 运行实现自己的 installer；没有 installer 的 provider 实现走旧的释放路径。 */
    fun releaseRuntime(context: Context): Result<String> = runCatching {
        if (!isInstalled(context)) error("尚未安装摄像头实现")
        val dir = currentDir(context).absolutePath
        val installer = File(currentDir(context), "install.sh")
        if (installer.isFile) {
            val p = ProcessBuilder(context.suPath, "-c", "cd '$dir' && sh '$dir/install.sh'")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly(); error("实现 installer 超时")
            }
            if (p.exitValue() != 0) error("实现 installer 失败：$out")
            return@runCatching out.trim().ifBlank { "实现 installer 执行完成" }
        }
        if (isModule(context)) {
            error("magisk-module 实现缺少 install.sh，不能由 PortalEX 直接对接 root 管理器")
        }
        val cmd = "mkdir -p $RUNTIME; " +
            "cp -f '$dir/provider' $PROVIDER; " +
            (if (File(currentDir(context), "meta.bin").exists()) "cp -f '$dir/meta.bin' $META; " else "") +
            (if (File(currentDir(context), "tags.bin").exists()) "cp -f '$dir/tags.bin' $TAGS; " else "") +
            "chmod 755 $PROVIDER"
        val p = ProcessBuilder(context.suPath, "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly(); error("释放实现超时")
        }
        if (p.exitValue() != 0) error("释放实现失败：$out")
        "已在 $RUNTIME 释放实现"
    }

    /** 卸载私有实现；实现自己的 uninstall.sh 负责 root 模块清理。 */
    fun uninstall(context: Context): Result<String> = runCatching {
        if (!isInstalled(context)) return@runCatching "没有已安装实现"
        val dir = currentDir(context)
        val uninstaller = File(dir, "uninstall.sh")
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
