package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import moe.fuqiuluo.portalex.ext.suPath
import java.io.File
import java.security.SecureRandom
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
        val tokenFile = File(current, "auth.token")
        // 必须每次导入重新生成，不能信任 zip 内自带的 auth.token。
        tokenFile.delete()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        tokenFile.writeText(bytes.joinToString("") { "%02x".format(it) })
        tokenFile.setReadable(false, false)
        tokenFile.setReadable(true, true)
        File(current, "auth.nonce").delete()
        Install(
            supported, models,
            if (supported) "已安装：${models.joinToString()}" else
                "已安装，但机型不在适配列表：当前 ${Build.MODEL}，支持 ${models.joinToString()}",
            j.optString("type") == "magisk-module"
        )
    }.getOrElse { Install(false, emptyList(), "安装失败：${it.message}") }

    /** PortalEX 只调用实现自己的 start.sh；provider/HAL 细节由实现负责。 */
    fun start(context: Context): Result<String> = runScript(
        context, "start.sh", timeoutMs = 30_000L,
        successMarkers = listOf("cammock provider started", "MI6 cammock started")
    )

    /** 停止也由实现自己的 stop.sh 负责。 */
    fun stop(context: Context): Result<String> = runScript(context, "stop.sh")

    private fun runScript(
        context: Context,
        name: String,
        timeoutMs: Long = 30_000L,
        successMarkers: List<String> = emptyList(),
    ): Result<String> = runCatching {
        if (!isInstalled(context)) error("尚未安装摄像头实现")
        val dir = currentDir(context)
        val script = File(dir, name)
        if (!script.isFile) error("实现缺少 $name")
        val tokenFile = File(dir, "auth.token")
        if (!tokenFile.isFile) error("实现缺少 auth.token，请重新导入")
        val token = tokenFile.readText().trim()
        if (token.length < 16) error("实现 auth.token 无效")
        val nonceFile = File(dir, "auth.nonce")
        // 上次 root 脚本可能留下 root:root 600 的 nonce；app 自己删不掉/写不了。
        // 先让 root 清理，再写入本次一次性 nonce。
        runCatching {
            ProcessBuilder(context.suPath, "-c", "rm -f '${nonceFile.absolutePath}'")
                .start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        nonceFile.writeText(nonce)
        val cmd = "cd '${dir.absolutePath}' && " +
            "PORTALEX_CAMMOCK_TOKEN='$token' PORTALEX_CAMMOCK_NONCE='$nonce' " +
            "sh '${script.absolutePath}'"
        val startedAt = System.currentTimeMillis()
        val p = ProcessBuilder(context.suPath, "-c", cmd)
            .redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) { out.append(buf, 0, n) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        // 启停脚本会把 provider 留在后台。部分 KernelSU/su supervisor 会一直等
        // 这个后台子进程，导致脚本已经打印完成标记、Process.waitFor 却永远不返回。
        // 因此先轮询完成标记：看到标记就立即返回，不再把 30s 超时当成启动失败。
        val deadline = startedAt + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (p.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) break
            val snapshot = synchronized(out) { out.toString() }
            if (successMarkers.any { snapshot.contains(it) }) {
                android.util.Log.i(
                    "CammockManager",
                    "$name reached success marker after ${System.currentTimeMillis() - startedAt}ms"
                )
                // 停止读取输出，避免后台 provider 继续占着管道；不 destroy 进程，
                // 它可能已经是 provider 的唯一 supervisor。
                runCatching { p.inputStream.close() }
                return@runCatching snapshot.trim()
            }
        }
        if (p.isAlive) {
            p.destroyForcibly()
            reader.join(2_000)
            error("$name 执行超时（${timeoutMs / 1000}s，长视频上传请重试）")
        }
        reader.join(5_000)
        val elapsed = System.currentTimeMillis() - startedAt
        android.util.Log.i("CammockManager", "$name finished in ${elapsed}ms exit=${p.exitValue()}")
        val text = synchronized(out) { out.toString() }
        if (p.exitValue() != 0) error("$name 失败：${text.trim()}")
        text.trim().ifBlank { "$name 执行完成" }
    }

    /**
     * 卸载私有实现；实现自己的 uninstall.sh 负责 root 模块清理。
     * **只清实现目录**：`CameraMockStore.dir(context)`（filesDir/camera）里的录制视频、
     * 路线元数据和标签不属于实现，绝不在这里删除。
     */
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
