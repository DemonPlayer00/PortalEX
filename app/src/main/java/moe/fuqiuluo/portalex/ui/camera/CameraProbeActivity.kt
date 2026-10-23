package moe.fuqiuluo.portalex.ui.camera

import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * **诊断用探针**：以 `YUV_420_888` 为**唯一输出**打开相机，抓一帧存成 JPEG 到 `filesDir/probe/`。
 *
 * 为什么需要它：摄像头模拟要覆盖"图片等数据"，就得验证 **YUV 分析流**
 * （`ImageAnalysis` / `YUV_420_888`）这条路径 —— 它和预览走同一条注入代码，但输出格式不同，
 * 必须单独验。App 里原本没有"只开 YUV 流"的入口，所以补这个隐藏 Activity：
 *
 * ```
 * adb shell su -c 'am start -n moe.fuqiuluo.portalex/.ui.camera.CameraProbeActivity \
 *     --ei cameraId 0 --ei width 640 --ei height 480'
 * ```
 * 抓完一帧即 `finish()`，结果落在 `filesDir/probe/`，并在 logcat 打 `CameraProbe` 标签。
 *
 * 顺带它也是"**多应用并发取流**"验证的第二个会话来源（例如 Aperture 占一个摄像头、
 * 探针占另一个，两边都应看到虚拟帧）。
 */
class CameraProbeActivity : Activity() {

    private companion object {
        const val TAG = "CameraProbe"
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var saved = false
    private var holdMs = 0L
    private var savedCount = 0
    private var maxFrames = 1
    private var jpegMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread = HandlerThread("camera-probe").apply { start() }
        handler = Handler(thread.looper)

        val wantId = intent.getIntExtra("cameraId", 0)
        val wantW = intent.getIntExtra("width", 640)
        val wantH = intent.getIntExtra("height", 480)
        /* 保持会话多久（毫秒）：0 = 抓到第一帧就结束；>0 时抓完继续 hold，
         * 用于观察"多应用并发取流"（另一个应用占着另一个摄像头）。 */
        holdMs = intent.getIntExtra("holdMs", 0).toLong()
        maxFrames = intent.getIntExtra("frames", 1).coerceIn(1, 120)
        jpegMode = intent.getBooleanExtra("jpeg", false)

        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        Log.i(TAG, "可用相机: $ids；本次请求 id=$wantId ${wantW}x$wantH")
        /* 这台设备支不支持"两个相机同时开"——决定了"多应用并发取流"这个场景是否成立 */
        val concurrent = runCatching { cm.concurrentCameraIds }.getOrDefault(emptySet())
        Log.i(TAG, "并发相机组合（getConcurrentCameraIds）: $concurrent")
        ids.forEach { id ->
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull()
            Log.i(
                TAG,
                "  相机 $id: facing=${ch?.get(CameraCharacteristics.LENS_FACING)} " +
                        "sensor=${ch?.get(CameraCharacteristics.SENSOR_ORIENTATION)} " +
                        "video=${ch?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(android.media.MediaRecorder::class.java)?.maxByOrNull { it.width.toLong() * it.height }} " +
                        "jpeg=${ch?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }} "
            )
        }
        val id = if (ids.contains(wantId.toString())) wantId.toString() else ids.firstOrNull()
        if (id == null) {
            Log.e(TAG, "没有任何相机")
            finishProbe()
            return
        }

        @Suppress("MissingPermission") // 探针只在已授权 CAMERA 的调试设备上跑
        val ok = runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    startSession(camera, wantW, wantH)
                    // 同 CameraRecordActivity：未显式指定超时的相机会话一律走默认上限
                    CameraOccupancyGuard.start("probe") { finishProbe() }
                    if (holdMs > 0) {
                        Log.i(TAG, "保持会话 ${holdMs}ms（用于并发观测）")
                        handler.postDelayed({ Log.i(TAG, "hold 结束，收工"); finishProbe() }, holdMs)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "相机断开")
                    camera.close()
                    finishProbe()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "打开相机失败 error=$error")
                    camera.close()
                    finishProbe()
                }
            }, handler)
        }.isSuccess
        if (!ok) {
            Log.e(TAG, "openCamera 调用失败（权限？）")
            finishProbe()
        }
    }

    private fun startSession(camera: CameraDevice, w: Int, h: Int) {
        if (jpegMode) {
            val r = ImageReader.newInstance(w, h, ImageFormat.JPEG, 2)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                val image = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    val buf = it.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    // 框架已按 camera3_jpeg_blob 的 jpeg_size 把 buffer 裁成纯 JPEG，
                    // ImageReader 拿到的就是有效 JPEG 字节（末尾不再有 blob 头）。
                    val dir = java.io.File(filesDir, "probe").apply { if (!exists()) mkdirs() }
                    val out = java.io.File(dir, "jpeg-${System.currentTimeMillis()}.jpg")
                    out.writeBytes(bytes)
                    Log.i(TAG, "JPEG BLOB: ${bytes.size} 字节，头=${bytes.take(8).joinToString(" ") { b -> "%02x".format(b) }}")
                    Log.i(TAG, "已保存: ${out.absolutePath}（${out.length()} 字节）")
                }
                savedCount++
                if (savedCount >= maxFrames) finishProbe()
            }, handler)
            camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(r.surface)
                    }.build()
                    runCatching { s.capture(req, null, handler) }
                        .onFailure { Log.e(TAG, "JPEG capture 失败", it); finishProbe() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "JPEG createCaptureSession 失败")
                    finishProbe()
                }
            }, handler)
            return
        }
        val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val image = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (savedCount < maxFrames) {
                image.use { saveFrame(it) }
                savedCount++
                if (savedCount >= maxFrames && holdMs <= 0) finishProbe()
            } else {
                image.close()
            }
        }, handler)

        /* **只挂 YUV 流**：这是"分析流"路径本身（没有预览、没有 JPEG），正好单测它 */
        camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(r.surface)
                }.build()
                runCatching { s.setRepeatingRequest(req, null, handler) }
                    .onFailure { Log.e(TAG, "setRepeatingRequest 失败", it); finishProbe() }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "createCaptureSession 失败（该尺寸可能不被支持）")
                finishProbe()
            }
        }, handler)
    }

    /** YUV_420_888 → NV21 → JPEG，落到 `filesDir/probe/probe-<相机>-<时间>.jpg` */
    private fun saveFrame(image: Image) {
        val w = image.width
        val h = image.height
        val planes = image.planes
        Log.i(
            TAG,
            "帧: ${w}x$h planes=${planes.size} " +
                    planes.joinToString(" ") { "row=${it.rowStride} pix=${it.pixelStride}" }
        )
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = planes[0]
        var p = 0
        for (row in 0 until h) {
            val off = row * y.rowStride
            if (off + w > y.buffer.limit()) break
            y.buffer.position(off)
            y.buffer.get(nv21, p, w)
            p += w
        }
        val u = planes[1]
        val v = planes[2]
        val rowBuf = ByteArray(w)
        for (row in 0 until h / 2) {
            val off = row * u.rowStride
            if (off + w > u.buffer.limit()) break
            u.buffer.position(off)
            u.buffer.get(rowBuf, 0, w)
            var i = 0
            if (u.pixelStride == 2 && v.pixelStride == 2) {
                while (i + 1 < w) { // NV12(U 前) → NV21(V 前)
                    nv21[p + i] = rowBuf[i + 1]
                    nv21[p + i + 1] = rowBuf[i]
                    i += 2
                }
            } else {
                val vBuf = ByteArray(w)
                v.buffer.position(off)
                v.buffer.get(vBuf, 0, w)
                while (i < w) { // 平面格式：逐分量交织
                    nv21[p + i] = vBuf[i * v.pixelStride]
                    if (i + 1 < w) nv21[p + i + 1] = rowBuf[i * u.pixelStride]
                    i += 2
                }
            }
            p += w
        }
        val baos = ByteArrayOutputStream(256 * 1024)
        val encoded = runCatching {
            YuvImage(nv21, ImageFormat.NV21, w, h, null)
                .compressToJpeg(Rect(0, 0, w, h), 90, baos)
        }.isSuccess
        if (!encoded) {
            Log.e(TAG, "JPEG 编码失败")
            return
        }
        val dir = File(filesDir, "probe").apply { if (!exists()) mkdirs() }
        val out = File(dir, "probe-${System.currentTimeMillis()}.jpg")
        runCatching { out.writeBytes(baos.toByteArray()) }
            .onSuccess {
                saved = true
                Log.i(TAG, "已保存: ${out.absolutePath}（${out.length()} 字节）")
            }
            .onFailure { Log.e(TAG, "写文件失败", it) }
    }

    private fun finishProbe() {
        handler.postDelayed({
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            runCatching { thread.quitSafely() }
            finish()
        }, 300)
    }

    override fun onDestroy() {
        super.onDestroy()
        CameraOccupancyGuard.stop()
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { thread.quitSafely() }
    }
}
