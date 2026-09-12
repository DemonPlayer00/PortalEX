package moe.fuqiuluo.xposed.hooks.camera

import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 让解码器直接读 provider 发来的视频（`content://…/video/<id>` → fd）。
 *
 * 为什么需要这一层：视频存在 PortalEX 的私有目录里，被注入的进程**没有路径权限**，
 * 只能拿 fd；而 `MediaCodec`/`MediaExtractor` 的 `setDataSource(MediaDataSource)` 需要
 * 随机读取（`readAt`）—— 用 [FileChannel] 实现按偏移读取即可，且 fd 是内核给的，
 * 不存在"文件被删/改名"这类竞态。
 *
 * 生命周期：[close] 会一并关闭 `ParcelFileDescriptor` 与底层 channel；播放器停止时必须调用，
 * 否则每开一次摄像头就泄漏一个 fd（系统对 fd 数量有硬上限）。
 */
internal class ProviderVideoSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val input: FileInputStream
) : MediaDataSource() {

    private val channel: FileChannel = input.channel
    private val size: Long = runCatching { channel.size() }.getOrDefault(-1L)
    private var closed = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) return -1
        if (this.size >= 0 && position >= this.size) return -1
        val bb: ByteBuffer = ByteBuffer.wrap(buffer, offset, size)
        var total = 0
        while (bb.hasRemaining()) {
            val read = channel.read(bb, position + total)
            if (read < 0) break
            total += read
        }
        return if (total == 0 && bb.hasRemaining()) -1 else total
    }

    override fun getSize(): Long = size

    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        runCatching { pfd.close() }
    }

    companion object {
        /** 打开失败返回 null（调用方按"不拦截"处理，绝不因为取不到素材去崩目标应用） */
        fun open(uri: Uri): ProviderVideoSource? = runCatching {
            val ctx = moe.fuqiuluo.xposed.hooks.camera.appContextOf() ?: return null
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            ProviderVideoSource(pfd, FileInputStream(pfd.fileDescriptor))
        }.getOrNull()
    }
}
