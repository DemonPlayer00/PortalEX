package moe.fuqiuluo.portalex.service

import android.content.Context
import moe.fuqiuluo.portalex.ext.suPath
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App 侧负责把 `libportalsensor.so` 释放到 system_server 可加载的位置。
 *
 * 固定约定：
 * - 源：当前 APK 解压出的 nativeLibraryDir/libportalsensor.so
 * - 目标：/data/local/tmp/portalex/libportalsensor.so
 * - 上下文：u:object_r:system_lib_file:s0
 *
 * 只在传感器模拟启动前调用；目标已存在且内容一致时不触发 root。
 */
object SensorNativeLibrary {

    const val DST = "/data/local/tmp/portalex/libportalsensor.so"
    private const val DST_TMP = "$DST.tmp"

    data class Status(
        val present: Boolean,
        val same: Boolean,
        val detail: String,
    )

    fun source(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libportalsensor.so")

    /** 通过设置页配置的 su 检查目标是否已存在且与 APK 内库一致。 */
    fun check(context: Context): Result<Status> = runCatching {
        val src = source(context)
        require(src.isFile) { "APK 内未找到 libportalsensor.so" }
        val out = runSu(
            context,
            "if [ -f '$DST' ]; then " +
                "if cmp -s '${src.absolutePath}' '$DST'; then echo SAME; else echo DIFF; fi; " +
                "else echo MISSING; fi"
        )
        val state = out.trim().lineSequence().lastOrNull { it == "SAME" || it == "DIFF" || it == "MISSING" }
            ?: error("su 检查输出无法识别：${out.trim()}")
        Status(
            present = state == "SAME" || state == "DIFF",
            same = state == "SAME",
            detail = state,
        )
    }

    /**
     * 复制 + chmod + chcon + 原子 rename。
     * 必须在 IO 线程调用；su 失败会返回带 stderr/stdout 的异常。
     */
    fun install(context: Context): Result<Unit> = runCatching {
        val src = source(context)
        require(src.isFile) { "APK 内未找到 libportalsensor.so" }
        runSu(
            context,
            "set -e; " +
                "mkdir -p /data/local/tmp/portalex; " +
                "cp -f '${src.absolutePath}' '$DST_TMP'; " +
                "chmod 755 '$DST_TMP'; " +
                "chcon u:object_r:system_lib_file:s0 '$DST_TMP'; " +
                "mv -f '$DST_TMP' '$DST'; " +
                "test -f '$DST'"
        )
    }

    /** 重启 zygote，使新的 system_server 重新加载固定路径。 */
    fun restartSystemServer(context: Context): Result<Unit> = runCatching {
        runSu(context, "setprop ctl.restart zygote")
    }

    fun errorText(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t.toString()

    private fun runSu(context: Context, command: String): String {
        val p = ProcessBuilder(context.suPath, "-c", command)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("su 执行超时：${context.suPath}")
        }
        if (p.exitValue() != 0) {
            error("su 失败（exit=${p.exitValue()}）：${out.trim().ifBlank { "无输出" }}")
        }
        return out
    }
}
