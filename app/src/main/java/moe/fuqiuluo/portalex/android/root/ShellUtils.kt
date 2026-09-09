package moe.fuqiuluo.portalex.android.root

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ShellUtils {
    private const val COMMAND_TIMEOUT_MS = 10_000L

    fun hasRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            try {
                process.outputStream.write("exit\n".toByteArray())
                process.outputStream.flush()
                process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                !process.isAlive && process.exitValue() == 0
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun setEnforceMode(enabled: Boolean) {
        try {
            executeCommand("setenforce ${if (enabled) "1" else "0"}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 执行 su 命令并返回完整输出。
     * - 先消费输出流再 waitFor，避免子进程输出超过管道缓冲区（约 64KB）时写阻塞导致死锁；
     * - waitFor 带超时，避免 su 授权弹窗未响应时永久阻塞调用线程（UI 线程会 ANR）。
     */
    private fun execWithTimeout(command: String): CommandResult {
        val process = Runtime.getRuntime().exec("su")
        try {
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()

            // 子线程并行消费输出流并保存结果，防止管道写满死锁
            val stdoutRef = AtomicReference<ByteArray>()
            val stderrRef = AtomicReference<ByteArray>()
            val stdoutThread = Thread {
                stdoutRef.set(process.inputStream.use { it.readBytes() })
            }.apply { start() }
            val stderrThread = Thread {
                stderrRef.set(process.errorStream.use { it.readBytes() })
            }.apply { start() }

            val finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(-1, ByteArray(0), ByteArray(0), timedOut = true)
            }
            stdoutThread.join(1000)
            stderrThread.join(1000)
            return CommandResult(
                process.exitValue(),
                stdoutRef.get() ?: ByteArray(0),
                stderrRef.get() ?: ByteArray(0),
                timedOut = false
            )
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        } finally {
            try {
                if (process.isAlive) process.destroy()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
        val timedOut: Boolean
    )

    fun executeCommand(command: String): String {
        return try {
            val result = execWithTimeout(command)
            if (result.timedOut) {
                ""
            } else if (result.exitCode != 0) {
                String(result.stderr)
            } else {
                String(result.stdout)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun executeCommandToBytes(command: String): ByteArray {
        return try {
            val result = execWithTimeout(command)
            if (result.timedOut) {
                ByteArray(0)
            } else if (result.exitCode != 0) {
                result.stderr
            } else {
                result.stdout
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ByteArray(0)
        }
    }
}
