package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * **静止图片素材**：把一张图片变成"一帧视频"。
 *
 * 为什么要编码而不是直接存 jpg：
 *  · 下游（目标应用进程里的 [moe.fuqiuluo.xposed.hooks.camera.CameraVideoPlayer]、
 *    外部 cammock 的 provider）只认 mp4。让它们"认图片"意味着协议、provider、
 *    选片服务、上传/导出全都要多一条分支，且外部实现同样得跟着改；
 *  · 而把图片编码成**只有一帧（重复两帧）的 mp4**，对下游来说它就是一段普通视频 ——
 *    这正是"图片在逻辑上等价于一帧视频 + 一个点位"的落地方式。
 *
 * 编码策略（`MediaCodec` 的 YUV 输入 + `MediaMuxer`，全部是公开 API）：
 *  · 两帧**同一画面**，PTS 间隔 [HOLD_GAP_US]：相当于"这一帧保持 3 秒"。
 *    单帧会在解码器里立刻 EOS，循环时每几十毫秒重建一次解码器；给一个保持间隔
 *    能让"循环播放这张图"的下游只按正常帧率重播，不会把 CPU 烧在 seek 上；
 *  · 走 `getInputImage`（`COLOR_FormatYUV420Flexible`）：不依赖 GL/EGL，
 *    在 app 进程里跑，编码一次约几十到几百毫秒。
 *
 * 失败语义：任何一步失败都返回 null/false，**不留下半截文件**（库里出现一个
 * 打不开的 mp4，比"这次没加进去"难查得多）。
 */
object CameraStillMedia {

    private const val TAG = "CameraStill"

    /** 图片长边的上限：够看清、又不至于让"一帧"变成几 MB */
    const val MAX_SIDE = 1280

    /** 两帧（同一画面）之间的间隔；也是"这张图保持多久" */
    const val HOLD_GAP_US = 3_000_000L

    /** 时长探取失败时的兜底时长（与 [HOLD_GAP_US] 对齐） */
    const val HOLD_MS = HOLD_GAP_US / 1000L

    private const val FRAME_RATE = 30
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val ENCODE_TIMEOUT_MS = 20_000L

    /** 一张待入库的图片：位图 + 拍摄时间（EXIF 优先，取不到为 0）+ 名字提示 */
    class Source(val bitmap: Bitmap, val recordedAt: Long, val nameHint: String?)

    // ---------------------------------------------------------------- 读取

    /** 从相册/文件选择器拿到的 `content://`：先落到缓存再按文件处理（EXIF 需要可重复读取） */
    fun prepareFromUri(context: Context, uri: Uri): Source? {
        val temp = File(context.cacheDir, "camera-still-${UUID.randomUUID()}.img")
        return try {
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp.isFile && temp.length() > 0L
            }.getOrDefault(false)
            if (!copied) return null
            prepareFromFile(temp, nameHint = displayName(context, uri)?.substringBeforeLast('.'))
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 现场拍摄落下的 JPEG（或任何本地图片文件）。 */
    fun prepareFromFile(file: File, nameHint: String? = null): Source? {
        val bitmap = decode(file) ?: return null
        return Source(bitmap, exifTime(file), nameHint)
    }

    /**
     * 解码 + EXIF 方向 + 缩放到 [MAX_SIDE] 内（宽高取偶数，编码器要求）。
     * 任何一步失败返回 null（图片坏了就当作没选）。
     */
    fun decode(file: File, maxSide: Int = MAX_SIDE): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // 大图先降采样再解码：4000x3000 直接解要用 ~48MB，手机上很容易被系统回收
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide &&
                bounds.outHeight / (sample * 2) >= maxSide
            ) {
                sample *= 2
            }
            val raw = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val upright = applyExifRotation(raw, exifOrientation(file))
            val size = fitInside(upright.width, upright.height, maxSide)
            if (size.first == upright.width && size.second == upright.height) {
                upright
            } else {
                val scaled = Bitmap.createScaledBitmap(upright, size.first, size.second, true)
                if (scaled !== upright) upright.recycle()
                scaled
            }
        } catch (t: Throwable) {
            Log.w(TAG, "图片解码失败：${file.name}", t)
            null
        }
    }

    /**
     * 等比缩放到长边不超过 [maxSide]，并把宽高各自**向下取偶**。
     *
     * 为什么单独抽出来：编码器对奇数宽高会在 configure 阶段直接抛
     * `IllegalArgumentException`，而这句话只会在真机上出现一次 ——
     * 让它成为可单测的纯函数，比事后在日志里猜便宜得多。
     */
    fun fitInside(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 2 to 2
        val longest = maxOf(width, height)
        val scale = if (longest > maxSide) maxSide.toDouble() / longest else 1.0
        fun even(value: Int): Int {
            val scaled = Math.round(value * scale).toInt().coerceIn(2, maxSide)
            return scaled - (scaled and 1)
        }
        return even(width) to even(height)
    }

    // ---------------------------------------------------------------- 编码

    /**
     * 把 [bitmap] 编码成"一帧 mp4"写到 [dest]。
     *
     * @return true = 文件可用；false = 失败（已删除半截文件）
     */
    fun encode(bitmap: Bitmap, dest: File): Boolean {
        val width = bitmap.width and 1.inv()
        val height = bitmap.height and 1.inv()
        if (width < 2 || height < 2) return false

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var succeeded = false
        try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate(width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val encoder = MediaCodec.createEncoderByType(MIME)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val mux = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var trackIndex = -1

            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val presentationTimes = longArrayOf(0L, HOLD_GAP_US)

            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + ENCODE_TIMEOUT_MS
            var queued = 0
            var eos = false

            while (!eos) {
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "图片编码超时（${width}x$height）")
                    return false
                }
                if (queued < presentationTimes.size) {
                    val inIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val image = encoder.getInputImage(inIndex)
                        if (image == null) {
                            Log.w(TAG, "编码器不支持 YUV 图像输入：${encoder.name}")
                            return false
                        }
                        fillYuv(image, pixels, width, height)
                        // 用图像填数据时，尺寸取输入缓冲的容量（各实现给 stride 留的余量不同）
                        val size = encoder.getInputBuffer(inIndex)?.capacity()
                            ?: (width * height * 3 / 2)
                        encoder.queueInputBuffer(inIndex, 0, size, presentationTimes[queued], 0)
                        queued++
                    }
                } else if (queued == presentationTimes.size) {
                    val inIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        encoder.queueInputBuffer(
                            inIndex, 0, 0, presentationTimes.last(),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        queued++
                    }
                }

                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = mux.addTrack(encoder.outputFormat)
                            mux.start()
                            muxerStarted = true
                        }
                        continue
                    }
                    if (outIndex < 0) continue
                    val buffer = encoder.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        mux.writeSampleData(trackIndex, buffer, info)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true
                }
            }
            succeeded = muxerStarted
        } catch (t: Throwable) {
            Log.w(TAG, "图片编码失败：${width}x$height", t)
            succeeded = false
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            muxer?.let { m ->
                runCatching { if (muxerStarted) m.stop() }
                runCatching { m.release() }
            }
            if (!succeeded || !dest.isFile || dest.length() == 0L) {
                runCatching { dest.delete() }
                succeeded = false
            }
        }
        return succeeded
    }

    private fun bitrate(width: Int, height: Int): Int =
        (width * height / 2).coerceIn(1_000_000, 8_000_000)

    /**
     * 把 ARGB 像素按 BT.601 有限范围填进编码器的 YUV 平面。
     * 平面步长（rowStride / pixelStride）按编码器实现给出的值走 —— 假设紧排会花屏。
     */
    private fun fillYuv(image: Image, pixels: IntArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        // 绝对写入的基准：个别实现把 U/V 放在同一块 buffer 里（V 的 position=1）。
        // 直接用 index 0 写入会把 U 覆盖成 V —— 这类花屏不会抛异常，只在真机上看得见。
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()

        for (row in 0 until height) {
            val rowBase = row * yPlane.rowStride
            val pixelBase = row * width
            for (col in 0 until width) {
                val argb = pixels[pixelBase + col]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(yBase + rowBase + col * yPlane.pixelStride, y.coerceIn(0, 255).toByte())
            }
        }

        var uvRow = 0
        var row = 0
        while (row < height) {
            var uvCol = 0
            var col = 0
            while (col < width) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var samples = 0
                for (dy in 0 until 2) {
                    val y = row + dy
                    if (y >= height) continue
                    for (dx in 0 until 2) {
                        val x = col + dx
                        if (x >= width) continue
                        val argb = pixels[y * width + x]
                        rSum += (argb shr 16) and 0xFF
                        gSum += (argb shr 8) and 0xFF
                        bSum += argb and 0xFF
                        samples++
                    }
                }
                if (samples > 0) {
                    val r = rSum / samples
                    val g = gSum / samples
                    val b = bSum / samples
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uIndex = uBase + uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride
                    val vIndex = vBase + uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride
                    uBuffer.put(uIndex, u.coerceIn(0, 255).toByte())
                    vBuffer.put(vIndex, v.coerceIn(0, 255).toByte())
                }
                uvCol++
                col += 2
            }
            uvRow++
            row += 2
        }
    }

    // ---------------------------------------------------------------- EXIF

    private fun exifOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 照片的拍摄时间（EXIF）；取不到返回 0，由调用方决定兜底 */
    fun exifTime(file: File): Long {
        val text = runCatching {
            ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME)
        }.getOrNull() ?: return 0L
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text.trim())?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }.getOrElse { bitmap }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        val cursor: Cursor? = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) it.getString(index) else null
        }
    }.getOrNull()
}
