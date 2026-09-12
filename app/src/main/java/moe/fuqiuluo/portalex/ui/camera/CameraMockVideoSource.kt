package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import moe.fuqiuluo.portalex.service.MockKeepAliveService
import moe.fuqiuluo.xposed.camera.CameraMockSelector
import java.io.File

/**
 * **摄像头模拟的产帧端**（app 侧）：把选中的视频解码出来，写进 HAL 读得到的共享帧文件。
 *
 * 职责边界：
 *  · **这里**负责"播哪一段、从第几秒起、什么时候换画面"——选片规则复用纯函数
 *    [CameraMockSelector]，位置取自 [CameraMockRuntime]（与模块侧同一套语义）；
 *  · **HAL** 只负责"把共享文件里的最新一帧读出来、缩放到相机流几何、memcpy 进相机 buffer"
 *    （见 `camera-hal/src/camshim.c` 的 `portal_copy_external`）。
 *
 * 四个必须守住的点（**每条都是真机踩出来的**，别按直觉改回去）：
 *
 *  1. **解码器走 ByteBuffer 模式（不给 Surface）**：给 Surface 时解码器把画面交给 GPU/UBWC
 *     布局的图形缓冲，用 ImageReader 从 CPU 读回来是**平铺数据** —— 实测 Y 平面单独解出来
 *     也是一片规则噪声。不给 Surface，`getOutputImage` 才会给 CPU 可读的 YUV 平面。
 *  2. **几何按视频自身**：MediaCodec 会把 Surface 的 buffer 尺寸设回视频尺寸，
 *     "建成相机流尺寸让解码器顺手缩放"行不通（实测图像仍是 1920x1080）⇒ 缩放放在 HAL。
 *  3. **每趟绕回文件头前必须 `flush()`**：EOS 之后解码器停在终止态，直接再喂样本它不产出，
 *     `dequeueOutputBuffer` 会一直阻塞（实测线程栈就卡在那里，发布 142 帧后无声无息）。
 *  4. **必须让进程别被冻结**：本进程退到后台就是 cached，会被 Cached Apps Freezer 冻结；
 *     冻结后产帧停止，HAL 的心跳兜底会把画面切回真实相机。
 *     所以复用 [MockKeepAliveService]（前台服务）—— 它只冻 cached 进程。
 *
 * 节流放在**输入侧**（按 `sampleTime` 对齐墙钟）：不加节流解码器会用最快速度把整段视频灌进
 * 共享内存，画面变成"快进"。
 */
object CameraMockVideoSource {

    private const val TAG = "CameraMockVideoSource"

    /** 位置变化判据（米）：小于它不重选，避免定位抖动导致频繁换片 */
    private const val RESELECT_METERS = 1.0

    /**
     * 照片帧（JPEG）的重编码间隔。
     *
     * 为什么不等"拍照请求"到来再编码：HAL 是在 `process_capture_result` 里把照片 buffer 换掉的，
     * 那一刻必须**已经有**可搬的 JPEG（同步等 app 编码会把 HAL 的结果线程堵住）。
     * 所以这里维持一张"当前帧的 JPEG"，按固定节拍刷新 —— 照片最多比预览旧 1.5 秒，
     * 对"模拟"完全够用（视频内容本来就在慢慢走）。
     */
    private const val PHOTO_INTERVAL_MS = 1500L

    /** 照片体积软上限：超过就降质量重编（真 HAL 那张实测 ~930 KB，我们留足余量） */
    private const val PHOTO_SOFT_MAX = 400 * 1024

    private val lock = Any()
    private var thread: Thread? = null

    /** 跨趟累计发布帧数（诊断用） */
    @Volatile
    private var publishedTotal = 0L

    /** 取不到解码帧的累计次数（诊断用） */
    @Volatile
    private var nullImages = 0L

    /** 发布失败的累计次数（诊断用） */
    @Volatile
    private var failedPublish = 0L

    /** 照片帧（JPEG）相关状态 */
    private var lastPhotoMs = 0L
    private var photoCount = 0L
    private var nv21: ByteArray = ByteArray(0)
    private var photoRow: ByteArray = ByteArray(0)

    /** 供 [stop] 撤保活用（[start] 时记下 application context） */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** 按总开关状态同步启停。开关变化、页面回到前台时调用它即可。 */
    fun sync(context: Context) {
        if (context.cameraMockEnabled) start(context.applicationContext) else stop()
    }

    fun start(context: Context) {
        synchronized(lock) {
            if (running) return
            appContext = context
            running = true
            thread = Thread({ runLoop(context) }, "camera-mock-video").apply {
                isDaemon = true
                start()
            }
            runCatching { MockKeepAliveService.startCamera(context) }
            Log.i(TAG, "产帧线程已启动")
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running && thread == null) return
            running = false
            thread?.interrupt()
            thread = null
        }
        Log.i(TAG, "stop()：关闭产帧与接管")
        /* 先让 HAL 停止接管（露出真实相机），再释放映射 */
        runCatching { CameraFrameSink.setEnabled(false) }
        runCatching { CameraFrameSink.close() }
        appContext?.let { runCatching { MockKeepAliveService.stopCamera(it) } }
    }

    private fun runLoop(context: Context) {
        var lastPos: Pair<Double, Double>? = null
        try {
            while (running) {
                val selection = selectOnce(context)
                if (selection == null) {
                    /* 没有命中素材：**不接管**（把真实摄像头留给应用），等库/开关变化 */
                    Log.i(TAG, "没有命中的素材 → 不接管")
                    CameraFrameSink.setEnabled(false)
                    sleep(1500)
                    continue
                }
                val file = CameraMockStore.videoFile(context, selection.fileName)
                if (!file.isFile) {
                    Log.w(TAG, "选中的视频文件不存在：${selection.fileName}")
                    sleep(1500)
                    continue
                }
                Log.i(TAG, "开始播放 ${selection.videoName}（${file.name}）从 ${selection.startMs}ms 起")
                lastPos = CameraMockRuntime.position()

                playLoop(file, selection.startMs) {
                    /* 绕回文件头时的回调：位置明显变了就重新选片（返回 false = 重选） */
                    val now = CameraMockRuntime.position()
                    val moved = now != null && lastPos != null &&
                            distanceM(lastPos!!.first, lastPos!!.second, now.first, now.second) >
                            RESELECT_METERS
                    if (moved) Log.i(TAG, "表象位置已变化，重新选片")
                    !moved && running
                }
            }
        } catch (t: Throwable) {
            if (running) Log.e(TAG, "产帧线程异常退出", t)
        } finally {
            runCatching { CameraFrameSink.setEnabled(false) }
            runCatching { CameraFrameSink.close() }
            appContext?.let { runCatching { MockKeepAliveService.stopCamera(it) } }
        }
    }

    private fun selectOnce(context: Context) = runCatching {
        val manifest = CameraMockStore.manifest(context)
        val pos = CameraMockRuntime.position()
        CameraMockSelector.select(manifest, pos?.first, pos?.second)
    }.getOrNull()

    /** 粗略距离（米）：只用于"位置是否变了"的判据，不需要 haversine 的精度 */
    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 111_320.0
        val dLon = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians(lat1))
        return Math.hypot(dLat, dLon)
    }

    private fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    /**
     * 播放循环：从 [startMs] 起播，播到 EOS 回**文件头**继续。
     *
     * @param onLoopBoundary 每次绕回文件头时回调；返回 false 表示立即结束本次播放
     *        （用于"位置变了要重选"或"已被停止"）
     */
    private fun playLoop(file: File, startMs: Long, onLoopBoundary: () -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: run {
                Log.w(TAG, "文件里没有视频轨：${file.name}")
                return
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (!CameraFrameSink.open(w, h)) {
                Log.w(TAG, "共享帧文件不可用（几何 ${w}x$h）")
                return
            }
            /* **必须在 open() 之后**再开接管：open() 会把头里的 enabled 清零
             * （"先关、等真的能产帧了再开"）。提前调用会被它覆盖 —— 实测踩过：
             * seq 一直涨、enabled 恒为 0，HAL 全程不接管。 */
            CameraFrameSink.setEnabled(true)

            codec = MediaCodec.createDecoderByType(mime)
            /* 不给 Surface（见类注释第 1 条） */
            codec.configure(format, null, null, 0)
            codec.start()

            var offsetUs = (startMs * 1000L).coerceAtLeast(0L)
            var pass = 1
            while (running) {
                if (!runCatching { extractor.seekTo(offsetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
                        .isSuccess) return
                if (!drain(extractor, codec)) return
                if (!running) return
                if (!onLoopBoundary()) return
                runCatching { codec.flush() } /* 见类注释第 3 条 */
                pass++
                Log.i(TAG, "第 $pass 趟开始（累计发布 $publishedTotal 帧）")
                offsetUs = 0L
            }
        } catch (t: Throwable) {
            if (running) Log.e(TAG, "播放失败：${file.name}", t)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 编码一张 JPEG，并**把体积压在安全线以内**。
     *
     * 为什么需要压：照片**长度不是我们说了算** —— 实测保存下来的文件用的是真 HAL 那张照片的
     * 字节数（~930 KB），我们只提供前 N 字节，后面是零。若我们的 JPEG 比它长，文件就会被截断
     * ⇒ 照片损坏。所以先按 85 编，超过 [PHOTO_SOFT_MAX] 就降质量重编一次（宁可糊一点，不要坏图）。
     */
    private fun encodeJpeg(nv21: ByteArray, w: Int, h: Int): ByteArray? {
        for (quality in intArrayOf(85, 65)) {
            val baos = java.io.ByteArrayOutputStream(256 * 1024)
            val ok = runCatching {
                YuvImage(nv21, ImageFormat.NV21, w, h, null)
                    .compressToJpeg(Rect(0, 0, w, h), quality, baos)
            }.isSuccess
            if (!ok) return null
            val bytes = baos.toByteArray()
            if (bytes.size <= PHOTO_SOFT_MAX || quality == 65) {
                if (quality != 85) Log.i(TAG, "照片降质量重编：q=$quality → ${bytes.size} 字节")
                return bytes
            }
        }
        return null
    }

    /**
     * 把当前解码帧编码成 JPEG 放进共享文件的**照片区**（拍照时由 HAL 搬进 BLOB buffer）。
     *
     * `YuvImage` 只吃 **NV21**（Y 平面 + VU 交织），而解码器给的是 NV12（UV 交织，U 在前）
     * ⇒ 色度成对交换。节流见 [PHOTO_INTERVAL_MS]。
     */
    private fun maybePublishPhoto(image: Image) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPhotoMs < PHOTO_INTERVAL_MS) return
        lastPhotoMs = now

        val w = CameraFrameSink.width
        val h = CameraFrameSink.height
        if (w <= 0 || h <= 0 || image.width < w || image.height < h) return
        val need = w * h * 3 / 2
        if (nv21.size < need) nv21 = ByteArray(need)
        if (photoRow.size < w) photoRow = ByteArray(w)
        val out = nv21

        /* Y：逐行拷（源可能有行填充，目标按 w 紧凑排） */
        val y = image.planes[0]
        var p = 0
        for (r in 0 until h) {
            val off = r * y.rowStride
            if (off + w > y.buffer.limit()) break
            y.buffer.position(off)
            y.buffer.get(out, p, w)
            p += w
        }
        /* 色度：NV12(U 在前) → NV21(V 在前)，成对交换 */
        val u = image.planes[1]
        for (r in 0 until h / 2) {
            val off = r * u.rowStride
            if (off + w > u.buffer.limit()) break
            u.buffer.position(off)
            u.buffer.get(photoRow, 0, w)
            var i = 0
            while (i + 1 < w) {
                out[p + i] = photoRow[i + 1]
                out[p + i + 1] = photoRow[i]
                i += 2
            }
            p += w
        }

        val bytes = encodeJpeg(out, w, h)
        if (bytes == null) {
            Log.w(TAG, "JPEG 编码失败（照片仍会是真实画面）")
            return
        }
        if (CameraFrameSink.publishPhoto(bytes, w, h)) {
            photoCount++
            if (photoCount == 1L || photoCount % 20L == 0L) {
                Log.i(TAG, "照片帧已更新：${bytes.size} 字节（${w}x$h），第 $photoCount 次")
            }
        }
    }

    /**
     * 解码一段（到 EOS 为止）并把每帧发布进共享文件；返回 true = 正常播到结尾。
     *
     * 节流在**输入侧**：输出侧一帧解码出来就立刻拷走并归还，解码速度由喂样本的速度决定。
     */
    private fun drain(extractor: MediaExtractor, codec: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var basePtsUs = -1L
        var wall0 = 0L
        var published = 0L
        while (running) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    val size = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        if (basePtsUs < 0) {
                            basePtsUs = pts
                            wall0 = SystemClock.elapsedRealtime()
                        }
                        val due = wall0 + (pts - basePtsUs) / 1000L
                        val now = SystemClock.elapsedRealtime()
                        if (due > now) SystemClock.sleep(due - now)
                        codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val cf = runCatching {
                    codec.outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                }.getOrDefault(-1)
                Log.i(TAG, "解码输出格式：colorFormat=$cf")
            } else if (outIdx >= 0) {
                val image = runCatching { codec.getOutputImage(outIdx) }.getOrNull()
                if (image == null) {
                    nullImages++
                    if (nullImages == 1L || nullImages % 60L == 0L) {
                        Log.w(TAG, "getOutputImage 返回 null（第 $nullImages 次）")
                    }
                } else {
                    image.use {
                        maybePublishPhoto(it)
                        val ok = CameraFrameSink.publish(it)
                        if (ok) {
                            published++
                            publishedTotal++
                            if (published == 1L) Log.i(TAG, "首帧已发布：${it.width}x${it.height}")
                        } else {
                            failedPublish++
                            if (failedPublish % 60L == 1L) {
                                Log.w(TAG, "发布失败：img=${it.width}x${it.height} " +
                                        "sink=${CameraFrameSink.width}x${CameraFrameSink.height}")
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.i(TAG, "本趟 EOS：发布 $published 帧（累计 $publishedTotal）")
                    return true
                }
            }
        }
        return false
    }
}
