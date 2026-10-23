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

    data class Install(val supported: Boolean, val models: List<String>, val message: String,
                       val module: Boolean = false)

    fun installDir(context: Context) = File(context.filesDir, DIR)

    fun currentDir(context: Context) = File(installDir(context), "current")

    fun providerFile(context: Context) = File(currentDir(context), "provider")

    fun isInstalled(context: Context): Boolean {
        val dir = currentDir(context)
        if (!File(dir, "cammock.json").isFile) return false
        return providerFile(context).isFile || File(dir, "module.prop").isFile
    }

    fun isModule(context: Context): Boolean {
        val f = File(currentDir(context), "cammock.json")
        return runCatching { JSONObject(f.readText()).optString("type") == "magisk-module" }
            .getOrDefault(false)
    }

    private fun moduleId(context: Context): String {
        val f = File(currentDir(context), "cammock.json")
        return runCatching { JSONObject(f.readText()).optString("moduleId") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "portalex-cammock-sagit"
    }

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
                    if (name == "module.prop") hasModule = true
                }
            }
        } ?: error("无法读取 zip")
        if (!hasManifest) error("zip 必须包含 cammock.json")
        if (!hasProvider && !hasModule) error("zip 必须包含 provider 或 module.prop")
        // 统一 provider 文件名，安装目录只保留一份。
        if (!File(staging, "provider").exists()) {
            File(staging, "portal_camera_provider").renameTo(File(staging, "provider"))
        }
        val manifestName = if (File(staging, "cammock.json").exists()) "cammock.json" else "manifest.json"
        if (manifestName == "manifest.json") File(staging, "manifest.json").renameTo(File(staging, "cammock.json"))
        // 保留原始 zip，供 magisk-module 类型通过 root 管理器安装。
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(staging, "source.zip").outputStream().use { input.copyTo(it) }
            }
        }
        val manifest = JSONObject(File(staging, "cammock.json").readText())
        val isModule = manifest.optString("type") == "magisk-module"
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
            "已安装，但机型不在适配列表：当前 ${Build.MODEL}，支持 ${models.joinToString()}",
            isModule)
    }.getOrElse { Install(false, emptyList(), "安装失败：${it.message}") }

    /** 卸载私有实现，并清理运行时释放目录。 */
    fun uninstall(context: Context): Result<String> = runCatching {
        val module = isModule(context)
        val id = moduleId(context)
        currentDir(context).deleteRecursively()
        installDir(context).deleteRecursively()
        val p = ProcessBuilder("su", "-c", "rm -rf $RUNTIME").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("清理运行时目录超时")
        }
        if (p.exitValue() != 0) error("清理运行时目录失败：$out")
        if (module) {
            runCatching {
                ProcessBuilder("su", "-c",
                    "if [ -x /data/adb/apd ]; then /data/adb/apd module uninstall '$id'; " +
                    "elif [ -x /data/adb/magisk/magisk ]; then /data/adb/magisk/magisk --remove-module '$id'; fi"
                ).start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            }
            "摄像头实现已卸载；root 模块可能需要重启后完全移除"
        } else "摄像头实现已卸载"
    }

    /** 把私有目录里的实现释放到 root 可读的运行时目录。 */
    fun releaseRuntime(context: Context): Result<String> = runCatching {
        if (!isInstalled(context)) error("尚未安装摄像头实现")
        val src = currentDir(context).absolutePath
        if (isModule(context)) {
            val zip = File(currentDir(context), "source.zip")
            if (!zip.isFile) error("实现包缺少 source.zip，请重新导入")
            val id = moduleId(context)
            val cmd = "if [ -x /data/adb/apd ]; then /data/adb/apd module install '${zip.absolutePath}'; " +
                "elif [ -x /data/adb/magisk/magisk ]; then /data/adb/magisk/magisk --install-module '${zip.absolutePath}'; " +
                "else echo 'no root module manager'; exit 1; fi"
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly(); error("模块安装超时")
            }
            if (p.exitValue() != 0) error("模块安装失败：$out")
            return@runCatching "模块已提交安装（$id），请重启设备后生效：${out.takeLast(120)}"
        }
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
