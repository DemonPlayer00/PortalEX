package moe.fuqiuluo.portalex.ui.camera

import android.media.Image
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 「帧文件」写入端：把解码出来的帧放进 HAL 读得到的那块共享文件。
 *
 * 协议（**权威定义在 `camera-hal/src/camshim.c`**，说明见 `docs/camera-mock.md` §12.4）：
 * 偏移 0 起是 80 字节小端头，偏移 [HDR_BYTES] 起是 `slots` 个槽位、每槽 `slot_bytes`。
 * 发布顺序必须是 **写像素 → 写 write_idx → 写 seq**：HAL 只在 `seq` 变化时才换槽，
 * 于是它读到的永远是"已经写完"的那一槽（配合多槽位，实际上不会撕裂）。
 *
 * 为什么"照抄源平面布局 + 把 stride 写进头"而不是重排成紧凑 NV12：
 * 目的是让每一行都是一次 `ByteBuffer.put(byte[])`（JNI 级整块拷贝），
 * 而不是逐像素的 Java 循环 —— 后者在 1600×1200 上会把解码线程整个吃掉。
 * 布局差异交给 HAL 按 stride 处理，它那边本来就是逐行 memcpy。
 *
 * **打不开就整条链路静默降级**（文件名不存在/没有写权限 = 这台设备没装模块）：
 * 这是只有受控设备才成立的通道，普通安装下不能因此崩、也不该弹错。
 */
object CameraFrameSink {

    private const val TAG = "CameraFrameSink"

    /**
     * 共享帧文件路径。由模块/root 侧**预建**（Magisk 的 `post-fs-data.sh` 负责建目录、
     * 打 `portalex_frame_file` 标签、预分配文件），app 只负责打开和写 ——
     * 因为 app 私有目录带 MLS 类别，vendor HAL 结构性读不到（见 docs §12.2）。
     */
    const val PATH = "/data/local/tmp/portalex/cammock.frames"

    private const val MAGIC = 0x46584550 // 'PEXF'
    private const val VERSION = 1
    private const val HDR_BYTES = 4096
    private const val SLOTS = 3

    /**
     * 亮度槽位大小：**固定 4 MiB，不再从文件长度反推**。
     *
     * 为什么要固定：槽位之后要留出**照片区**（拍照的 JPEG 由 app 编码、HAL 搬运，
     * 见 docs/camera-mock.md §15）。从文件长度反推会把整份文件吃干，photo 区长度为 0。
     * 1080p 的一帧 NV12 是 2.6 MB，4 MiB 有余量。
     */
    private const val SLOT_BYTES = 4 * 1024 * 1024

    /** 照片区最小可用长度：小于它就没法放一张像样的 JPEG */
    private const val PHOTO_MIN = 256 * 1024

    /* 头部字段偏移（与 camshim.c 的 struct portal_frame_hdr 一一对应） */
    private const val OFF_MAGIC = 0
    private const val OFF_VERSION = 4
    private const val OFF_WIDTH = 8
    private const val OFF_HEIGHT = 12
    private const val OFF_Y_STRIDE = 16
    private const val OFF_UV_STRIDE = 20
    private const val OFF_FORMAT = 24
    private const val OFF_SLOT_BYTES = 28
    private const val OFF_SLOTS = 32
    private const val OFF_ENABLED = 36
    private const val OFF_SEQ = 40
    private const val OFF_WRITE_IDX = 44
    private const val OFF_FLAGS = 48
    private const val OFF_TIMESTAMP = 56
    private const val OFF_CONSUMER_W = 64
    private const val OFF_CONSUMER_H = 68
    private const val OFF_PRODUCER_STATE = 72
    /** HAL 回写：照片变换标志（bit0 = 编码前水平镜像，用于抵消相机 app 写的 EXIF 镜像） */
    private const val OFF_PHOTO_FLAGS = 76
    private const val OFF_PHOTO_W = 80
    private const val OFF_PHOTO_H = 84
    private const val OFF_PHOTO_BYTES = 88
    private const val OFF_PHOTO_SEQ = 92
    /** HAL 回写：静态流（拍照）请求的几何 */
    private const val OFF_PHOTO_REQ_W = 96
    private const val OFF_PHOTO_REQ_H = 100
    /** HAL 回写：拍照请求序号（HAL 在请求阶段就 +1，通知我们"现在就编一张"） */
    private const val OFF_PHOTO_REQ_SEQ = 104
    /** 后端写入：相机请求序号（OnePlus 实现使用；0 = 后端未实现） */
    private const val OFF_CONSUMER_REQ_SEQ = 108

    private var raf: RandomAccessFile? = null
    private var map: ByteBuffer? = null
    private var slotBytes = 0
    private var photoOffset = 0
    private var photoSeq = 0
    private var yStride = 0
    private var uvStride = 0
    private var nextSlot = 0
    private var seq = 0

    /** 复用行缓冲：每帧上千次分配会变成 GC 压力 */
    private var rowBuf = ByteArray(0)

    /** 一次性诊断（只在第一帧打，避免刷日志） */
    private var metaLogged = false

    /** 当前约定的帧几何（等于「HAL 实际流尺寸」，由 [open] 决定） */
    var width = 0
        private set
    var height = 0
        private set

    val isOpen: Boolean get() = map != null

    /** 生产者自认为的开关状态（HAL 侧看头里的 enabled） */
    @Volatile
    var isEnabled = false
        private set

    /**
     * 打开共享文件并按 [w]×[h] 约定几何。
     *
     * 槽大小**由文件实际长度反推**（`(len - HDR_BYTES) / SLOTS`），而不是各自算一遍 ——
     * 否则文件建小了会在写第二槽时越界，而那种越界在 mmap 上是静默的。
     */
    @Synchronized
    fun open(w: Int, h: Int): Boolean {
        if (isOpen && width == w && height == h) return true
        close()
        if (w <= 0 || h <= 0) return false

        val file = File(PATH)
        if (!file.isFile || !file.canRead() || !file.canWrite()) {
            Log.w(TAG, "帧文件不可用（不存在或不可读写）：$PATH")
            return false
        }
        val handle = runCatching { RandomAccessFile(file, "rw") }.getOrNull() ?: run {
            Log.w(TAG, "帧文件打不开：$PATH")
            return false
        }
        val total = runCatching { handle.length() }.getOrDefault(0L)
        val per = SLOT_BYTES
        val need = HDR_BYTES.toLong() + SLOTS.toLong() * per + PHOTO_MIN
        if (total < need) {
            Log.w(TAG, "帧文件太小：$total 字节 < 需要 $need（3×4MiB 槽 + 照片区）")
            runCatching { handle.close() }
            return false
        }
        if (per < w * h * 3 / 2) {
            Log.w(TAG, "槽位 $per 放不下 ${w}x$h 的一帧（需要 ${w * h * 3 / 2}）")
            runCatching { handle.close() }
            return false
        }
        val buffer = runCatching {
            handle.channel.map(FileChannel.MapMode.READ_WRITE, 0, total)
        }.getOrNull() ?: run {
            runCatching { handle.close() }
            return false
        }
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        raf = handle
        map = buffer
        slotBytes = per
        photoOffset = HDR_BYTES + SLOTS * per
        photoSeq = 0
        width = w
        height = h
        /* 目标布局与源一致：Y 行 stride 留 16 对齐余量，UV 行 stride 同 Y（半平面） */
        yStride = align(w, 16)
        uvStride = align(w, 16)
        nextSlot = 0
        seq = 0
        if (rowBuf.size < yStride) rowBuf = ByteArray(yStride)

        buffer.putInt(OFF_MAGIC, MAGIC)
        buffer.putInt(OFF_VERSION, VERSION)
        buffer.putInt(OFF_WIDTH, w)
        buffer.putInt(OFF_HEIGHT, h)
        buffer.putInt(OFF_Y_STRIDE, yStride)
        buffer.putInt(OFF_UV_STRIDE, uvStride)
        buffer.putInt(OFF_FORMAT, 0) // NV12
        buffer.putInt(OFF_SLOT_BYTES, slotBytes)
        buffer.putInt(OFF_SLOTS, SLOTS)
        buffer.putInt(OFF_ENABLED, 0) // 先关：等真的能产帧了再开
        buffer.putInt(OFF_SEQ, 0)
        buffer.putInt(OFF_WRITE_IDX, 0)
        buffer.putInt(OFF_FLAGS, 0) // 非轮转：由 seq 驱动
        buffer.putLong(OFF_TIMESTAMP, 0L)
        buffer.putInt(OFF_CONSUMER_W, 0)
        buffer.putInt(OFF_CONSUMER_H, 0)
        buffer.putInt(OFF_PRODUCER_STATE, 1)
        buffer.putInt(OFF_PHOTO_W, 0)
        buffer.putInt(OFF_PHOTO_H, 0)
        buffer.putInt(OFF_PHOTO_BYTES, 0)
        buffer.putInt(OFF_PHOTO_SEQ, 0)
        isEnabled = false
        Log.i(TAG, "帧文件已打开：$PATH ${w}x$h stride=$yStride/$uvStride 槽=$SLOTS/${per}B")
        return true
    }

    private fun align(v: Int, a: Int): Int = (v + a - 1) / a * a

    /**
     * HAL 回写的**静态流（拍照）请求几何** —— 照片要按这个尺寸编码。
     * 拿不到（没跑模拟 / HAL 还没配置静态流）返回 null，调用方按解码帧自己的尺寸编码。
     */
    @Synchronized
    fun photoRequestSize(): Pair<Int, Int>? {
        val m = map ?: return null
        val w = m.getInt(OFF_PHOTO_REQ_W)
        val h = m.getInt(OFF_PHOTO_REQ_H)
        return if (w > 0 && h > 0 && w <= 8192 && h <= 8192) w to h else null
    }

    /** HAL 回写的照片变换标志（bit0=水平镜像） */
    @Synchronized
    fun photoFlags(): Int = map?.getInt(OFF_PHOTO_FLAGS) ?: 0

    /** HAL 回写的拍照请求序号；0 表示还没有过拍照请求 */
    @Synchronized
    fun photoRequestSeq(): Int = map?.getInt(OFF_PHOTO_REQ_SEQ) ?: 0

    /** 后端写入的相机请求序号；app 据此只在有相机请求时产帧。 */
    @Synchronized
    fun requestSeq(): Int = map?.getInt(OFF_CONSUMER_REQ_SEQ) ?: 0

    /** HAL 回写的"它实际的流尺寸"（HAL 在取帧时填），拿不到返回 null */
    @Synchronized
    fun consumerSize(): Pair<Int, Int>? {
        val m = map ?: return null
        val w = m.getInt(OFF_CONSUMER_W)
        val h = m.getInt(OFF_CONSUMER_H)
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * 总开关：`false` 时 HAL **完全不接管**（露出真实摄像头）。
     * 这是 app 对 HAL 唯一的"停止"指令，所以退出/异常路径上必须写它。
     */
    @Synchronized
    fun setEnabled(on: Boolean) {
        Log.i(TAG, "setEnabled($on)")
        val m = map ?: return
        m.putInt(OFF_ENABLED, if (on) 1 else 0)
        isEnabled = on
    }

    /** 发布一帧。几何不符/没打开都返回 false（调用方据此跳过，不抛异常） */
    @Synchronized
    fun publish(image: Image): Boolean {
        val m = map ?: return false
        /* 解码器的图像常带 16 行对齐的**高度填充**（实测 1080 → 1088），
         * 所以判据是"至少够大"，按 sink 几何拷贝前 N 行即可（多出来的padding不读）。 */
        if (image.width < width || image.height < height) return false
        val planes = image.planes
        if (planes.size < 3) return false

        val base = HDR_BYTES + nextSlot * slotBytes
        val yRows = height
        val uvRows = height / 2
        if (base + yStride * yRows + uvStride * uvRows > m.capacity()) return false

        val y = planes[0]
        if (!metaLogged) {
            metaLogged = true
            val sb = StringBuilder("帧元数据 img=${image.width}x${image.height} sink=${width}x$height ")
            planes.forEachIndexed { i, p ->
                sb.append("[p$i row=${p.rowStride} pix=${p.pixelStride} pos=${p.buffer.position()} lim=${p.buffer.limit()}] ")
            }
            Log.i(TAG, sb.toString())
            /* 前 16 字节样本：判断拿到的是像素还是未初始化内存 */
            val probe = ByteArray(16)
            val dup = y.buffer.duplicate()
            dup.position(0)
            dup.get(probe, 0, 16)
            Log.i(TAG, "Y 前16字节=" + probe.joinToString(",") { (it.toInt() and 0xFF).toString() })
        }
        if (!copyRows(y.buffer, y.rowStride, m, base, yStride, width, yRows)) return false

        val u = planes[1]
        val v = planes[2]
        val uvBase = base + yStride * yRows
        val uvOk = if (u.pixelStride == 2 && v.pixelStride == 2) {
            /* 半平面（NV12/NV21）：U 的行里已经是 U,V,U,V… ⇒ 整行搬 */
            copyRows(u.buffer, u.rowStride, m, uvBase, uvStride, width, uvRows)
        } else {
            interleave(u, v, m, uvBase, uvStride, width, uvRows)
        }
        if (!uvOk) return false

        /* 顺序不能反：先数据（上面已写）→ write_idx → seq（HAL 的换槽信号） */
        m.putInt(OFF_WRITE_IDX, nextSlot)
        seq += 1
        m.putInt(OFF_SEQ, seq)
        m.putLong(OFF_TIMESTAMP, System.nanoTime()) // HAL 用它做"生产者心跳"
        nextSlot = (nextSlot + 1) % SLOTS
        return true
    }

    /**
     * 直接发布一帧 NV12（Y + UV）。给没有 ImageReader 的调试控制器用；
     * 与 publish(image) 写同一套槽位/序号，HAL 不需要区分来源。
     */
    @Synchronized
    fun publishNv21(nv12: ByteArray, w: Int, h: Int): Boolean {
        val m = map ?: return false
        if (w < width || h < height) return false
        if (nv12.size < w * h * 3 / 2) return false
        val base = HDR_BYTES + nextSlot * slotBytes
        val yRows = height
        val uvRows = height / 2
        if (base + yStride * yRows + uvStride * uvRows > m.capacity()) return false
        for (r in 0 until yRows) {
            val src = r * w
            m.position(base + r * yStride)
            m.put(nv12, src, width)
        }
        val uvBase = base + yStride * yRows
        val uvSrcBase = w * h
        for (r in 0 until uvRows) {
            val src = uvSrcBase + r * w
            m.position(uvBase + r * uvStride)
            m.put(nv12, src, width)
        }
        m.putInt(OFF_WRITE_IDX, nextSlot)
        seq += 1
        m.putInt(OFF_SEQ, seq)
        m.putLong(OFF_TIMESTAMP, System.nanoTime())
        nextSlot = (nextSlot + 1) % SLOTS
        return true
    }

    /**
     * 发布一张照片（JPEG 字节）到照片区，供 HAL 在拍照时搬进 BLOB buffer。
     *
     * 为什么由 app 编码：libjpeg 的 `jpeg_compress_struct` 是**版本相关的 ABI**，
     * 在 HAL 里自带头文件等于赌版本；而这里 `YuvImage.compressToJpeg` 是现成的（Skia 原生）。
     * **顺序**：先写字节 → 再写长度/尺寸 → 最后 `photo_seq++`（HAL 只认 seq 变化）。
     */
    @Synchronized
    fun publishPhoto(jpeg: ByteArray, w: Int, h: Int): Boolean {
        val m = map ?: return false
        if (jpeg.isEmpty() || w <= 0 || h <= 0) return false
        if (photoOffset + jpeg.size > m.capacity()) {
            Log.w(TAG, "照片区放不下：${jpeg.size} 字节 > 剩余 ${m.capacity() - photoOffset}")
            return false
        }
        m.position(photoOffset)
        m.put(jpeg)
        m.putInt(OFF_PHOTO_W, w)
        m.putInt(OFF_PHOTO_H, h)
        m.putInt(OFF_PHOTO_BYTES, jpeg.size)
        photoSeq += 1
        m.putInt(OFF_PHOTO_SEQ, photoSeq)
        return true
    }

    /** 整行拷贝：源与目标 stride 可以不同，逐行 `get(byte[])` + `put(byte[])` */
    private fun copyRows(
        src: ByteBuffer,
        srcStride: Int,
        dst: ByteBuffer,
        dstBase: Int,
        dstStride: Int,
        bytes: Int,
        rows: Int
    ): Boolean {
        if (bytes <= 0 || bytes > rowBuf.size) return false
        val start = src.position()
        val limit = src.limit()
        for (r in 0 until rows) {
            val off = start + r * srcStride
            if (off < 0 || off + bytes > limit) break
            src.position(off)
            src.get(rowBuf, 0, bytes)
            dst.position(dstBase + r * dstStride)
            dst.put(rowBuf, 0, bytes)
        }
        return true
    }

    /** 平面（I420）色度的退路：逐对交织成 NV12 —— 本机不走这条，但别让布局差异变成"没画面" */
    private fun interleave(
        u: Image.Plane,
        v: Image.Plane,
        dst: ByteBuffer,
        dstBase: Int,
        dstStride: Int,
        width: Int,
        rows: Int
    ): Boolean {
        val cols = width / 2
        if (cols * 2 > rowBuf.size) return false
        val ub = u.buffer
        val vb = v.buffer
        val uStart = ub.position()
        val vStart = vb.position()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                rowBuf[c * 2] = ub.get(uStart + r * u.rowStride + c * u.pixelStride)
                rowBuf[c * 2 + 1] = vb.get(vStart + r * v.rowStride + c * v.pixelStride)
            }
            dst.position(dstBase + r * dstStride)
            dst.put(rowBuf, 0, cols * 2)
        }
        return true
    }

    /** 关闭：**先写 enabled=0**（否则 HAL 会继续用最后一帧），再断开映射 */
    @Synchronized
    fun close() {
        val m = map
        if (m != null) {
            runCatching { m.putInt(OFF_ENABLED, 0) }
            runCatching { m.putInt(OFF_PRODUCER_STATE, 0) }
            runCatching { m.putInt(OFF_PHOTO_BYTES, 0) }
        }
        isEnabled = false
        map = null
        runCatching { raf?.close() }
        raf = null
        width = 0
        height = 0
    }
}
