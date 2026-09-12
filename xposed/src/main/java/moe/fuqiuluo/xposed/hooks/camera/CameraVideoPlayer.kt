package moe.fuqiuluo.xposed.hooks.camera

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * 把一段 mp4 的画面**渲染进宿主应用自己的 [Surface]** —— 虚拟摄像头的最后一段管线。
 *
 * 使用场景决定了这份实现的三条硬约束，改动前请先读完：
 *
 *  · **零依赖、只用公开 SDK**。这个类连同 `:xposed` 的 dex 一起进目标应用进程，
 *    宿主里没有我们的依赖图，所以不能用 ExoPlayer/Media3，也不能用隐藏 API
 *    （隐藏 API 在宿主进程里会因 targetSdk/灰名单而 `NoSuchMethodError`）。
 *    MediaExtractor + MediaCodec + EGL14/GLES20 是 API 24 起就稳定可用的最小组合。
 *  · **绝不把异常抛给宿主**。宿主应用的选择是"看到真实摄像头"而不是"因为我们崩掉"，
 *    因此 `start()` 只负责起线程，任何失败（文件打不开、没有解码器、EGL 初始化失败、
 *    目标 Surface 已失效）都收敛到 [Listener.onError]，渲染线程随后安静退出。
 *  · **线程单一**。MediaCodec 的实例方法并非全部可跨线程调用，EGL 的 current context
 *    更是线程绑定的；所以解码、GL 提交、释放全部发生在同一条 [HandlerThread] 上，
 *    其余线程只通过 [running] 这个 volatile 开关与它通信。
 *
 * 渲染管线（每帧）：
 *
 * ```
 * mp4 --MediaExtractor--> MediaCodec(解码到 SurfaceTexture 的 Surface)
 *                              | onFrameAvailable
 *                              v
 *                        updateTexImage()  --> 外部纹理 (GL_TEXTURE_EXTERNAL_OES)
 *                              | 采样 + getTransformMatrix * 旋转 * letterbox
 *                              v
 *                        eglCreateWindowSurface(display, config, target)
 * ```
 *
 * 为什么不把 MediaCodec 直接配置到目标 Surface、省掉 GL 这一段：
 * 解码器只认自己的尺寸，会把画面**拉伸**铺满目标 Surface（宿主 SurfaceView 常是窄条或
 * 16:9 全屏），且不会替我们处理 `KEY_ROTATION`。GL 这一层是所有几何修正的落点。
 *
 * @param source   视频数据源（可能是宿主给的管道/FD，也可能是内存缓冲）。
 *                 **所有权归调用方，本类不 close**：宿主可能还要用它。
 * @param target   宿主应用提供的 Surface。**我们不持有所有权、不 release**，
 *                 宿主随时可能把它变成 invalid，那时按 [Listener.onError] 上报即可。
 * @param startMs  起播偏移（毫秒）。调用方给的是随机整秒，因此这里按
 *                 [MediaExtractor.SEEK_TO_PREVIOUS_SYNC] 落到同步帧再丢弃前置帧。
 * @param loop     播到文件末尾后是否回到**文件开头**（offset 0，不是 [startMs]）继续。
 *                 `false` 时播完回调 [Listener.onFinished] 并停止。
 * @param listener 全部回调都在渲染线程上执行；实现里不能阻塞（见 [Listener]）。
 */
class CameraVideoPlayer(
    private val source: MediaDataSource,
    private val target: Surface,
    private val startMs: Long,
    private val loop: Boolean = true,
    private val listener: Listener? = null
) {

    /**
     * 全部回调都发生在**渲染线程**上。
     *
     * 实现方要么只做轻量的事，要么自己再转发到自己的线程 —— 在这里阻塞会直接拖慢
     * 视频节流（甚至卡住一次 EOS/seek 的收尾），我们不会替调用方兜这个底。
     * 另外：回调内部抛出的异常会被我们吞掉（见 [notify]），因为这已经是最后一次
     * 能保护宿主的机会了。
     */
    interface Listener {
        /** 解码器配置完成。时长未知（MediaFormat 没带 KEY_DURATION）给 0 */
        fun onStarted(durationMs: Long)

        /** 第一帧**真正画到目标 Surface 之后**才回调（不是"解码出第一帧"） */
        fun onFirstFrame()

        /** `loop = false` 且整段播完 */
        fun onFinished()

        /** 任何一次失败只报一次；此后再无其它回调 */
        fun onError(t: Throwable)
    }

    /** 渲染线程可读的运行开关。`@Volatile`：`stop()` 从任意线程写，渲染线程读 */
    @Volatile
    private var running = false

    /** `start()` 幂等用：保证"启动中"也算已启动，避免两次 start 起两条线程 */
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** onError 只报一次（解码线程与启动路径都可能触发），且 stop() 之后不再报 */
    private val errorReported = java.util.concurrent.atomic.AtomicBoolean(false)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /**
     * 该实例是否仍在运行。`start()` 之后、`stop()` 完成之前为 `true`。
     *
     * 注意语义：`stop()` 是"请求停止并立即返回"，渲染线程上的实际释放是**异步**完成的，
     * 因此刚 `stop()` 完的那一刻本值已经是 `false`，而 EGL/MediaCodec 可能还差几毫秒
     * 才释放干净。宿主若要在 `stop()` 后立刻复用同一个 Surface，请按自己的节奏留一拍。
     */
    val isRunning: Boolean
        get() = running

    /**
     * 启动渲染线程。幂等：重复调用不会产生第二条线程，也不会重复回调。
     * 任何初始化失败都不会抛出，只会走 [Listener.onError]。
     */
    @Synchronized
    fun start() {
        if (started.get() || running) return
        started.set(true)
        running = true
        try {
            val ht = HandlerThread("PortalEX-CameraVideoPlayer")
            ht.start()
            thread = ht
            handler = Handler(ht.looper)
            handler?.post {
                try {
                    renderLoop()
                } catch (t: Throwable) {
                    // renderLoop 内部已尽量收敛，这里是最后一道防线：
                    // 无论如何都不能让异常穿到 Looper 上（那会杀掉宿主进程）。
                    reportError(t)
                } finally {
                    running = false
                    quitThread()
                }
            }
        } catch (t: Throwable) {
            running = false
            reportError(t)
            quitThread()
        }
    }

    /**
     * 请求停止。**幂等**、可从任意线程调用、且**不阻塞**（立刻返回）。
     *
     * 这里刻意不等待渲染线程结束：宿主多半在主线程上调用它（比如
     * `onPause`/切摄像头），join 会把 EOS 收尾 + `MediaCodec.stop()` 的耗时
     * 直接算到宿主的掉帧上。真正的释放由渲染线程在 [renderLoop] 的 finally 里完成，
     * 顺序是：退出解码循环 → codec/extractor 释放 → EGL 资源释放 → 线程退出。
     *
     * `quit` 只发一个空消息把可能正在 sleep 的节流唤醒；`stop()` 之后
     * [running] 为 `false`，渲染线程的所有回调出口都会因此静默。
     */
    fun stop() {
        if (!running && !started.get()) return
        started.set(false)
        running = false
        // 唤醒可能停在节流 sleep 里的渲染线程，让它尽快走到收尾路径
        handler?.post { /* 空消息：只为了把 Looper 叫醒 */ }
    }

    // ---------------------------------------------------------------- 渲染线程

    /**
     * 渲染线程主体。整段解码 + GL 都在这一个函数的作用域里，
     * 这样"资源在哪创建、就在哪释放"是肉眼可验证的。
     */
    private fun renderLoop() {
        var extractor: MediaExtractor? = null
        var handle: CodecHandle? = null
        var st: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var gl: Gl? = null

        try {
            requireApi()

            extractor = MediaExtractor()
            extractor.setDataSource(source)
            val trackIndex = selectVideoTrack(extractor)
                ?: throw IllegalStateException("mp4 里没有 video track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val width = format.optInt(MediaFormat.KEY_WIDTH)
            val height = format.optInt(MediaFormat.KEY_HEIGHT)
            if (width <= 0 || height <= 0) throw IllegalStateException("视频尺寸非法: ${width}x$height")
            val rotation = format.optRotation()
            val durationMs = format.optLong(MediaFormat.KEY_DURATION) / 1000L
            // 显示宽高：90/270 时画面是"躺着"存的，长边才是屏幕上的高。
            // letterbox 必须按这个尺寸算，否则竖屏视频会被当成横屏塞进 16:9 的框里。
            val displaySize = if (rotation == 90 || rotation == 270) {
                FrameSize(height.toDouble(), width.toDouble())
            } else {
                FrameSize(width.toDouble(), height.toDouble())
            }

            gl = Gl(target, displaySize)
            gl.makeCurrent()
            st = SurfaceTexture(gl.textureId)
            // 解码输出不需要 CPU 访问，从创建它的 GL 上下文上摘下来；
            // 之后它的 onFrameAvailable 会回到我们创建它时所在的线程（就是渲染线程）。
            st.detachFromGLContext()
            decoderSurface = Surface(st)
            // 固定成局部非空引用：GL 实例在整轮播放期间不变，
            // 但外层变量是可空的（finally 里释放），智能转换进不了内层循环
            val renderer = gl

            val live = CodecHandle(mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)) {
                "MediaFormat 缺少 KEY_MIME"
            }, format = format, surface = decoderSurface)
            handle = live
            live.configure()

            // 解码器已就绪 —— 从这里开始才可能真的出画面，此时告知调用方最合适
            notify { it.onStarted(durationMs) }

            val info = MediaCodec.BufferInfo()
            var firstFrameSent = false

            // 一轮 = 从起播偏移（或文件头）播到输入 EOS。
            // EOS 时若还要循环，就**重建 codec 再来一轮**（而不是在同一个循环里塞状态机）：
            // 重建的代码路径和"第一次启动"完全一致，少一整类只在第二轮出现的 bug。
            while (true) {
                // 起播 seek 只在第一轮做；之后每轮都从文件头开始
                // （回归语义要求：循环回**offset 0**，不是回 startMs）。
                seekTo(extractor, startMs * 1000L)
                // 丢弃 seek 落点之前的帧：SEEK_TO_PREVIOUS_SYNC 会把我们带到目标点**之前**
                // 的同步帧，那几帧是"路过"的画面（可能差好几秒），直接吐出去会看到明显跳变。
                // 阈值取 1ms 的容差，避免 seek 落在目标点上却因为取整把首帧一并丢掉。
                var dropBeforeUs = startMs * 1000L - 1_000L
                // 节流基准。null 表示"下一帧就是新一轮的第一帧"，那时才重新对表
                var baseUs: Long? = null
                var sawEos = false

                while (running) {
                    val inIndex = dequeueInput(live)
                    if (inIndex != null) {
                        val buf = live.getInputBuffer(inIndex)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            // 输入 EOS：必须把 EOS 标志交给解码器，否则末尾几帧会卡在它内部出不来。
                            // （带 CODEC_CONFIG 的空缓冲是另一条老路径，这里不需要）
                            live.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            live.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outIndex = live.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                    if (outIndex >= 0) {
                        // 三种输出不送显：空缓冲、编解码器配置帧、以及 seek 落点之前的"路过"帧。
                        // 第三种是这版最容易漏的：不丢的话，从 7s 起播会先看到 3s 的画面再跳到 7s。
                        val render = info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            info.presentationTimeUs >= dropBeforeUs

                        if (render) {
                            // 新一轮/回绕后的第一帧重新对表：不重置的话，回绕瞬间 PTS 从 9.9s
                            // 掉回 0.2s，节流会算出超长 sleep 变成黑屏；反向则会连吐一堆帧变倍速。
                            val base = baseUs ?: info.presentationTimeUs.also { baseUs = it }
                            // 只做"追帧"用的粗节流：不 sleep 到未来（那需要精确时钟，且会被
                            // 系统调度放大），而是让解码尽量贴着 PTS 的节奏走。
                            // 副作用是首帧立刻上屏，这正是期望行为：切到虚拟摄像头就该看到画面。
                            throttle(base, info.presentationTimeUs)
                            // updateTexImage() / getTransformMatrix() 必须在 GL 上下文 current
                            // 时调用，所以整帧（含纹理更新）都交给 drawFrame 内部完成，
                            // 不让"取矩阵"和"画"分散在两处。
                            renderer.drawFrame(st, rotation, info.presentationTimeUs, base)
                            if (!firstFrameSent) {
                                firstFrameSent = true
                                notify { it.onFirstFrame() }
                            }
                            // 这一帧已经"用掉"了，下一帧按新的 PTS 重新对表
                            baseUs = null
                        }
                        live.releaseOutputBuffer(outIndex, render)

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawEos = true
                            break
                        }
                    }
                    // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_FORMAT_CHANGED / INFO_OUTPUT_BUFFERS_CHANGED
                    // 都不需要额外处理：我们按 index 出帧、按 Surface 出图，不碰 ByteBuffer 输出。
                }

                if (!sawEos || !running) break

                if (!loop) {
                    notify { it.onFinished() }
                    break
                }

                // 循环：回到**文件开头**（offset 0，不是 startMs）。这里不 flush 也不重复发 EOS ——
                // 两者都会让"上一轮末尾的解码状态"混进下一轮（花屏/时间戳错乱），
                // 直接整段重建编解码器最省心，代价是每轮一次 configure（每段视频一次）。
                live.retire()
                live.configure()
            }
        } catch (c: InterruptedException) {
            // stop() 之外的打断：安静退出，不当错误上报（宿主已经不要画面了）
        } catch (t: Throwable) {
            reportError(t)
        } finally {
            // 释放顺序与创建顺序相反；每一步都独立 try/catch ——
            // 已经失效的宿主 Surface 常常让 release 抛异常，不能因此漏掉后面的资源。
            // 注意：source 是调用方的，这里**不** close。
            closeQuietly { handle?.release() }
            closeQuietly { decoderSurface?.release() }
            closeQuietly { st?.setOnFrameAvailableListener(null) }
            closeQuietly { st?.release() }
            closeQuietly { extractor?.release() }
            closeQuietly { gl?.release() }
        }
    }

    /**
     * 解码器句柄。存在的理由只有一个：**让 MainLoop 里不再出现可空变量**。
     * 循环重启要换一个新 `MediaCodec`，如果直接用外层 `var codec: MediaCodec?`，
     * Kotlin 就不会对循环体做智能转换（可变捕获），满屏 `!!`；
     * 把"可变"关进这个类之后，调用方拿到的始终是一个非空对象。
     */
    private class CodecHandle(
        private val mime: String,
        private val format: MediaFormat,
        private val surface: Surface
    ) {
        private var codec: MediaCodec = MediaCodec.createDecoderByType(mime)

        fun configure() {
            codec.configure(format, surface, null, 0)
            codec.start()
        }

        fun codecDequeueInput(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)

        fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

        fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {
            codec.queueInputBuffer(index, offset, size, ptsUs, flags)
        }

        fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int =
            codec.dequeueOutputBuffer(info, timeoutUs)

        fun releaseOutputBuffer(index: Int, render: Boolean) {
            codec.releaseOutputBuffer(index, render)
        }

        /** 换下一个解码器之前先把这个收掉；顺序错了（先 release 再 stop）在部分 ROM 上会 native 崩 */
        fun retire() {
            codec.stop()
            codec.release()
            codec = MediaCodec.createDecoderByType(mime)
        }

        fun release() {
            try {
                codec.stop()
            } finally {
                codec.release()
            }
        }
    }

    /**
     * 取输入缓冲。用**带超时**的同步 API 而不是 `dequeueInputBuffer(-1)`：
     * 无限等待会让 `stop()` 之后渲染线程还挂在 codec 上，而我们的 stop 是
     * "立即返回、由渲染线程自己收尾"的语义，这里必须留出让 `running` 被检查到的空档。
     */
    private fun dequeueInput(handle: CodecHandle): Int? {
        val idx = try {
            handle.codecDequeueInput(DEQUEUE_TIMEOUT_US)
        } catch (t: IllegalStateException) {
            // 极端时序下（宿主提前 stop）codec 可能已不可用，交给主循环按 stop 处理
            return null
        }
        return if (idx >= 0) idx else null
    }

    /** [MediaExtractor.SEEK_TO_PREVIOUS_SYNC] 会落到目标点之前；配合主循环的丢帧阈值使用 */
    private fun seekTo(extractor: MediaExtractor, positionUs: Long) {
        extractor.seekTo(max(0L, positionUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /**
     * 取输入缓冲。用**带超时**的同步 API 而不是 `dequeueInputBuffer(-1)`：
     * 无限等待会让 `stop()` 后渲染线程还挂在 codec 上，而我们的 stop 是"立即返回、
     * 由渲染线程自己收尾"的语义，这里必须留出让 `running` 被检查到的空档。
     */
    private fun dequeueInput(codec: MediaCodec): Int? {
        val idx = try {
            codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        } catch (t: IllegalStateException) {
            // 极端时序下（宿主提前 stop）codec 可能已不可用，交给主循环按 stop 处理
            null
        }
        return if (idx != null && idx >= 0) idx else null
    }

    /**
     * 按 presentationTimeUs 做实时节流。策略见 [renderLoop] 里的注释：
     * 只在**落后于 PTS 对应的墙钟时间**时短暂等待，绝不等待到未来。
     * 等待上限 [MAX_THROTTLE_SLEEP_MS] 是安全阀：随机 seek 后 PTS 基准跳变时，
     * 不致于让画面僵住（那会被宿主用户看成"卡死"）。
     */
    private fun throttle(baseUs: Long, ptsUs: Long) {
        val targetNs = (ptsUs - baseUs) * 1_000L
        val elapsedNs = System.nanoTime() - streamStartNs
        val sleepMs = (targetNs - elapsedNs) / 1_000_000L
        if (sleepMs in 1..MAX_THROTTLE_SLEEP_MS) {
            try {
                Thread.sleep(sleepMs)
            } catch (e: InterruptedException) {
                throw e // 由 renderLoop 捕获：这是 stop()/退出路径，不是错误
            }
        }
    }

    // ---------------------------------------------------------------- 资源与回调

    private inline fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // 释放期异常只能记下来：此刻已经没有"上报给宿主"的意义了
            Log.w(TAG, "释放资源时出错", t)
        }
    }

    /**
     * 上报错误：**只报一次**，且 `stop()` 之后不再报。
     * 判断条件用 [isRunning] 而不是"是否请求过 stop"，是为了让 start 失败
     * （线程都没起来）也能报出去。
     */
    private fun reportError(t: Throwable) {
        if (!errorReported.compareAndSet(false, true)) return
        Log.w(TAG, "虚拟摄像头渲染失败", t)
        if (!isRunning) return
        try {
            listener?.onError(t)
        } catch (ignored: Throwable) {
            // 宿主回调自己抛了：再往上就是我们崩宿主进程，宁可吞掉
        }
    }

    /** 包一层：宿主回调抛出的异常绝不能沿渲染线程往上传播 */
    private inline fun notify(block: (Listener) -> Unit) {
        val l = listener ?: return
        try {
            block(l)
        } catch (t: Throwable) {
            Log.w(TAG, "listener 回调抛出异常", t)
        }
    }

    private fun quitThread() {
        try {
            thread?.quitSafely()
        } catch (ignored: Throwable) {
        }
    }

    /** 首次出帧的墙钟基准，节流用 */
    private val streamStartNs: Long = System.nanoTime()

    /**
     * 兜住"被注入到比 minSdk 更低的宿主"这种极限情况。
     * 模块自身 minSdk = 24，正常安装路径下这个判断永远为假，但它必须存在：
     * 一旦宿主是 5.x，`MediaCodec.configure(Surface)` 等调用会直接 `NoSuchMethodError`，
     * 那是一条没法 catch 到"该报给谁"的路径。
     */
    private fun requireApi() {
        if (android.os.Build.VERSION.SDK_INT < 24) {
            throw IllegalStateException("需要 API 24+，当前 ${android.os.Build.VERSION.SDK_INT}")
        }
    }

    // ---------------------------------------------------------------- 解码器选择

    /**
     * 选第一条 video track。后续 track 一律忽略（含音频）：
     * 虚拟摄像头只需要画面，音轨交给宿主的录音链路去处理，我们混进去反而会打架。
     */
    private fun selectVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    // ---------------------------------------------------------------- EGL / GL

    /**
     * 一块极小的 EGL + GLES20 渲染器：把解码输出（外部纹理）画到宿主 Surface。
     *
     * 为什么自己建 context 而不用 `EGL14.getCurrentContext()`：宿主进程里我们
     * 完全不知道当前线程有没有 GL 上下文、是什么 config，借用别人的上下文等于
     * 把我们的生命周期绑在宿主的渲染线程上 —— 而我们必须在自己的线程上可控地销毁。
     */
    private class Gl(private val target: Surface, private val displaySize: FrameSize) {

        var textureId: Int = 0
            private set

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var config: EGLConfig? = null
        private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        /** SurfaceTexture 的 4x4 变换（列主序），每帧 `updateTexImage()` 后刷新 */
        private val texMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val rotMatrix = FloatArray(16)

        private var program = 0
        private var aPosition = 0
        private var aTexCoord = 0
        private var uMvpMatrix = 0
        private var uTexture = 0

        private val quad: FloatBuffer = ByteBuffer
            .allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD)
                position(0)
            }

        /**
         * 初始化。顺序不能变：`eglInitialize` 之前 `eglChooseConfig` 一定失败，
         * 而 config 又必须与 [eglCreateWindowSurface] 用的那一份完全一致
         * （recordable 的目标 Surface 会校验 config 的 usage 位）。
         */
        fun create() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay 失败")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize 失败: 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            val chosen = chooseConfig()
            config = chosen
            context = EGL14.eglCreateContext(
                display, chosen, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
            windowSurface = EGL14.eglCreateWindowSurface(display, chosen, target, intArrayOf(EGL14.EGL_NONE), 0)
            checkEgl(windowSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface(目标)")

            // 目标 surface 可能是宿主刚创建、还没被合成过的 BufferQueue，
            // EGL 会一直等到第一帧提交后才认为当前帧"有效"；这里不主动等待，
            // 交给下面的 makeCurrent + 首次 swap 完成握手。
            buildProgram()
            textureId = createExternalTexture()
        }

        /**
         * 优先 `EGL_RECORDABLE_ANDROID`：目标 Surface 若是 MediaRecorder 的
         * persistent input surface，只有 recordable 的 config 才能被它接受
         * （系统会校验 usage flag，不匹配时 eglCreateWindowSurface 直接失败）。
         * 拿不到就退到 `EGL_WINDOW_BIT` —— TextureView/SurfaceView 两条路都能用。
         */
        private fun chooseConfig(): EGLConfig {
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val out = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (EGL14.eglChooseConfig(display, attribs, 0, out, 0, 1, num, 0) &&
                num[0] > 0 && out[0] != null
            ) {
                return out[0]!!
            }
            val fallback = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            if (EGL14.eglChooseConfig(display, fallback, 0, out, 0, 1, num, 0) &&
                num[0] > 0 && out[0] != null
            ) {
                return out[0]!!
            }
            throw IllegalStateException("找不到可用的 EGLConfig")
        }

        /**
         * 建立并切换上下文。整个生命周期里只该调用一次 —— `eglCreateWindowSurface`
         * 对目标 BufferQueue 是**消耗性**的，重复创建会把宿主的 Surface 抢过来再还回去，
         * 期间宿主自己画的帧会丢。
         */
        fun makeCurrent() {
            create()
            if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
                throw IllegalStateException("eglMakeCurrent 失败: 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
        }

        private fun buildProgram() {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            // 链接失败的日志对宿主无用，直接抛给 reportError 记进 logcat
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                program = 0
                throw IllegalStateException("GL program 链接失败: $log")
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpMatrix = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("GL shader 编译失败: $log")
            }
            return shader
        }

        private fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val id = ids[0]
            if (id == 0) throw IllegalStateException("glGenTextures 失败")
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            // 外部纹理不支持 mipmap；CLAMP 是唯一安全的 wrap 模式（视频边缘采样越界会露黑边/彩边）
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return id
        }

        /**
         * 画一帧。几何相关的三个输入都在这里汇合：
         * [SurfaceTexture.getTransformMatrix]（解码器/裁剪信息）、
         * `rotationDegrees`（容器里的旋转元数据）、目标 Surface 的实时尺寸。
         *
         * 目标尺寸**每帧重读**而不是初始化时读一次：宿主可能在播放中途
         * 旋转屏幕/改布局（SurfaceView 会重建 BufferQueue），缓存尺寸会画错比例。
         */
        fun drawFrame(st: SurfaceTexture?, rotationDegrees: Int, ptsUs: Long, baseUs: Long) {
            st ?: return
            st.updateTexImage()
            // 这一帧取样坐标的"解码器侧"变换：包含 crop/翻转等解码器私有信息，
            // 必须逐帧取，不能缓存（SurfaceTexture 会在缓冲区尺寸变化时改写它）。
            st.getTransformMatrix(texMatrix)

            val widthOut = IntArray(1)
            val heightOut = IntArray(1)
            val hasW = EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_WIDTH, widthOut, 0)
            val hasH = EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_HEIGHT, heightOut, 0)
            val tw = if (hasW) widthOut[0] else 0
            val th = if (hasH) heightOut[0] else 0

            GLES20.glViewport(0, 0, max(1, tw), max(1, th))
            // 整屏先涂黑：letterbox 的留白必须是黑的，否则会看到上一帧/宿主的残影
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)

            // 采样坐标只做旋转。等比缩放/居中由**顶点位置**表达（见下），
            // 两个方向分开，是为了让"目标矩形"与"取样窗口"各自独立可测：
            // 合成到一个矩阵里，改 letterbox 就会连带改变取样区域，极难排查。
            VideoGeometry.rotationMatrix(rotationDegrees, rotMatrix)
            // 复合顺序：texCoord 先过旋转，再过 SurfaceTexture 自己的变换
            // （后者描述"解码器输出怎么取样"，是整条链最靠近纹理的一步）。
            VideoGeometry.multiply(texMatrix, rotMatrix, mvpMatrix)

            val rect = VideoGeometry.fitCenterToNdc(tw, th, displaySize)
            val left = rect.x.toFloat()
            val bottom = rect.y.toFloat()
            val right = (rect.x + rect.width).toFloat()
            val top = (rect.y + rect.height).toFloat()
            quad.position(0)
            // v 的取值：NDC 的 y=+1 是屏幕顶部，而纹理 v=0 才是画面顶部，
            // 所以 v 也跟着顶点一起翻转（底边取 1），否则所有视频都会上下颠倒。
            quad.put(left); quad.put(bottom); quad.put(0f); quad.put(1f)
            quad.put(right); quad.put(bottom); quad.put(1f); quad.put(1f)
            quad.put(left); quad.put(top); quad.put(0f); quad.put(0f)
            quad.put(right); quad.put(top); quad.put(1f); quad.put(0f)
            quad.position(0)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, quad)
            quad.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, quad)
            quad.position(0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            setPresentationTime(ptsUs, baseUs)
            EGL14.eglSwapBuffers(display, windowSurface)
        }

        /**
         * 把这一帧"标"上时间戳，合成器据此决定何时扫描上屏；不标的话每帧都可能被
         * 立即合成，节流就白做了。这里用**已经节流过的墙钟时刻**（而不是 PTS 的绝对值），
         * 因为 PTS 的零点（容器/解码器各自定义）与系统时钟没有可比性。
         * 失败（个别实现没有这条扩展）不影响正确性，静默跳过。
         */
        private fun setPresentationTime(ptsUs: Long, baseUs: Long) {
            val scheduledNs = System.nanoTime() + (ptsUs - baseUs) * 1_000L
            try {
                EGLExt.eglPresentationTimeANDROID(display, windowSurface, scheduledNs)
            } catch (ignored: Throwable) {
            }
        }

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, windowSurface)
                    windowSurface = EGL14.EGL_NO_SURFACE
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                    context = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
        }

        private fun checkEgl(ok: Boolean, what: String) {
            if (!ok) {
                throw IllegalStateException("$what 失败: 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
        }

        private companion object {
            const val EGL_RECORDABLE_ANDROID = 0x3142
            /** 4 个顶点 × (x, y, u, v)，TRIANGLE_STRIP 顺序 */
            val QUAD = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
            const val VERTEX_STRIDE = 4 * 4
            const val VERTEX_SHADER = """
                attribute vec2 aPosition;
                attribute vec2 aTexCoord;
                uniform mat4 uMvpMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vTexCoord = (uMvpMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """
            const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """
        }
    }

    private companion object {
        const val TAG = "PortalEX-CameraVideo"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        /** 单次节流等待上限：PTS 基准跳变（seek/回绕）时的安全阀 */
        const val MAX_THROTTLE_SLEEP_MS = 40L
    }
}

/**
 * 一帧画面在几何意义上的尺寸（**已经算过旋转之后**的显示宽高）。
 *
 * 为什么不直接把 `MediaFormat` 传进来：几何计算要能在 JVM 单测里跑，
 * 而 `MediaFormat` 在单测里是 android.jar 桩（构造都不行）。生产路径从 format
 * 取两个 int、按 `rotation-degrees` 交换宽高后填进来即可。
 */
internal data class FrameSize(val width: Double, val height: Double)

/**
 * 一块放在 NDC 里的矩形（左上角 + 宽高，范围都是 -1..1）。
 * letterbox 的实际落点，[VideoGeometry.fitCenterToNdc] 的返回值。
 */
internal data class NdcRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

/**
 * letterbox + 旋转的纯几何计算，抽出来是为了**可回归**：
 * 这几行错了不会抛异常，只会"画面比例不对/竖屏视频横过来"，在真机上一天都未必发现。
 *
 * 坐标系约定（三处一致，改动时必须同时改）：
 *  · 纹理坐标 `(u, v)`：`v = 0` 是画面顶部（与 [SurfaceTexture] 的约定一致）
 *  · NDC：`y = +1` 是屏幕顶部
 *  · 矩阵一律**列主序**，用法是 `M * vec4(u, v, 0, 1)`
 *
 * 这两个旋转是**分开**的，不是一回事，别合并：
 *  · fitCenterToNdc 里的"旋转"指**显示宽高**（竖屏视频要先换成 9:16 再算 letterbox）
 *  · [rotationMatrix] 里的旋转指**采样坐标**（从解码器输出的横图里取哪一块）
 * 把它们混成一个矩阵，会在"旋转 + 非等比目标"时同时错两处，且极难调试。
 */
internal object VideoGeometry {

    /**
     * 保持 [src] 宽高比的居中适配（letterbox/pillarbox），返回 NDC 里那块矩形。
     *
     * @param src **已按旋转换算过**的显示宽高（见 [FrameSize] 注释）。
     * @return 目标矩形；[targetW]/[targetH] 为 0（宿主还没测量）或 [src] 非法时
     *         返回**整屏矩形**，让调用方退化到"铺满"而不是除以零得到 NaN
     *         （NaN 会顺着矩阵进 GL，画面直接消失且不报任何错）。
     */
    fun fitCenterToNdc(targetW: Int, targetH: Int, src: FrameSize): NdcRect {
        val full = NdcRect(-1.0, -1.0, 2.0, 2.0)
        if (targetW <= 0 || targetH <= 0) return full
        if (src.width <= 0.0 || src.height <= 0.0) return full

        val targetAspect = targetW.toDouble() / targetH.toDouble()
        val srcAspect = src.width / src.height
        // 取小值：视频比目标"更宽"时受宽度限制（上下留黑），反之受高度限制（左右留黑）
        val scale = if (srcAspect > targetAspect) targetAspect / srcAspect else srcAspect / targetAspect
        val boxW: Double
        val boxH: Double
        if (srcAspect > targetAspect) {
            boxW = 2.0
            boxH = 2.0 * scale
        } else {
            boxW = 2.0 * scale
            boxH = 2.0
        }
        // 居中：两个方向各留一半。这里刻意把"居中"和"坐标系翻转"分开 ——
        // 翻转由顶点位置（NDC 的 y 向上）负责，本函数只管尺寸，
        // 这样它才能被单测按数值直接断言。
        return NdcRect(
            x = -(boxW / 2.0),
            y = -(boxH / 2.0),
            width = boxW,
            height = boxH
        )
    }

    /**
     * [MediaFormat.KEY_ROTATION]（`"rotation-degrees"`）对应的**采样坐标**旋转矩阵。
     *
     * 语义是"画面在屏幕上需要顺时针转多少度"：90 取 `(u,v) -> (v, 1-u)`（顺时针四分之一转）。
     * 若某些设备/容器写的是反向语义，表现是竖屏视频左右颠倒 —— 那时把这里的 90 与 270
     * 两支对调即可，别去动 [fitCenterToNdc]（它只管显示宽高，不区分方向）。
     *
     * 只在 **90/180/270** 时转向；0 或任何其它值都是单位矩阵 —— 这是刻意的：
     * 旋转元数据来自容器，脏数据（比如 45）在真机上出现过，此时"不转"比"瞎转"安全。
     *
     * 注意这里只转**采样坐标**：目标矩形仍由 [fitCenterToNdc] 决定，
     * 竖屏视频因此在横屏宿主里表现为"竖直的一条"（左右留黑），这正是期望结果。
     */
    fun rotationMatrix(degrees: Int, out: FloatArray = FloatArray(16)): FloatArray {
        identity(out)
        val d = ((degrees % 360) + 360) % 360
        when (d) {
            90 -> {
                // (u,v) -> (v, 1-u)
                out[0] = 0f; out[1] = 1f
                out[4] = -1f; out[5] = 0f
                out[12] = 1f; out[13] = 0f
            }
            180 -> {
                // (u,v) -> (1-u, 1-v)
                out[0] = -1f
                out[5] = -1f
                out[12] = 1f; out[13] = 1f
            }
            270 -> {
                // (u,v) -> (1-v, u)
                out[0] = 0f; out[1] = -1f
                out[4] = 1f; out[5] = 0f
                out[12] = 0f; out[13] = 1f
            }
            else -> Unit // 0/其它：单位矩阵
        }
        return out
    }

    /** `out = a * b`（列主序，即"先作用 b 再作用 a"，与 GLSL 的 `A * B * v` 一致） */
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray): FloatArray {
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + r] * b[c * 4 + k]
                }
                out[c * 4 + r] = sum
            }
        }
        return out
    }

    /** 列主序 4x4 矩阵左乘 `(u, v, 0, 1)`，返回前两个分量。单测用它验证旋转/适配是否符合预期 */
    fun apply(m: FloatArray, u: Double, v: Double): Pair<Double, Double> {
        val x = m[0] * u + m[4] * v + m[12]
        val y = m[1] * u + m[5] * v + m[13]
        return x to y
    }

    fun identity(out: FloatArray): FloatArray {
        java.util.Arrays.fill(out, 0f)
        out[0] = 1f
        out[5] = 1f
        out[10] = 1f
        out[15] = 1f
        return out
    }
}

// ---------------------------------------------------------------- MediaFormat 取值

/**
 * 这几个取值函数存在的唯一理由：**畸形视频不能崩宿主**。
 * `MediaFormat` 的 `getInteger` 对不存在的键抛 `NullPointerException`，
 * 而 mp4 是我们控制之外的输入（用户丢进来的文件），缺 key 是常态而非异常。
 */
internal fun MediaFormat.optInt(key: String, fallback: Int = 0): Int =
    try {
        getInteger(key)
    } catch (t: Throwable) {
        fallback
    }

internal fun MediaFormat.optLong(key: String, fallback: Long = 0L): Long =
    try {
        getLong(key)
    } catch (t: Throwable) {
        fallback
    }

/**
 * 旋转角度：不同解码器/容器下 `"rotation-degrees"` 可能是 Integer，也可能被写成 String
 * （见过 `"90"` 这种），两种都收；取不到就是 0。
 */
internal fun MediaFormat.optRotation(): Int {
    // 取值方式按"哪种不抛异常"依次试：MediaFormat 的 get* 在缺 key 时抛异常而不是返回默认值，
    // 而解码器写这个 key 的类型并不统一（Integer 居多，也见过写成字符串的）。
    runCatching { getInteger(MediaFormat.KEY_ROTATION) }.getOrNull()?.let { return it }
    runCatching { getString(MediaFormat.KEY_ROTATION) }.getOrNull()
        ?.trim()?.toIntOrNull()?.let { return it }
    return 0
}
