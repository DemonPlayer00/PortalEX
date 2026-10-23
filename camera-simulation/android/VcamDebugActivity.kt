package moe.fuqiuluo.portalex.vcam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import moe.fuqiuluo.portalex.ui.camera.CameraFrameSink
import kotlin.math.max

/**
 * 临时隐藏控制器：只用于真机实测标准接口。
 * adb: am start -n moe.fuqiuluo.portalex/.vcam.VcamDebugActivity
 */
class VcamDebugActivity : Activity() {

    private val player = VcamFramePlayer()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var fps: EditText
    private lateinit var index: EditText
    private var sinkOpen = false
    private var lastReqSeq = -1
    private var running = false
    private var lastPublished: VcamFramePlayer.Frame? = null
    private var lastPublishedBlack = false
    private lateinit var modeButton: Button
    private var fakeMode = true
    private var reloading = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private lateinit var preview: SurfaceView

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val seq = CameraFrameSink.requestSeq()
            if (seq != lastReqSeq) {
                lastReqSeq = seq
                val frame = runCatching { player.nextFrame() }.getOrNull()
                var ok = true
                if (frame == null) {
                    if (player.isEmpty) {
                        // 没有帧序列时不发布空黑帧；等 load 后第一帧再启用接管。
                    } else if (!lastPublishedBlack) {
                        ok = CameraFrameSink.publishNv21(blackNv21, W, H)
                        lastPublishedBlack = true
                        lastPublished = null
                    }
                } else if (frame !== lastPublished) {
                    ok = CameraFrameSink.publishNv21(frame.nv21, frame.width, frame.height)
                    lastPublished = frame
                    lastPublishedBlack = false
                }
                if (ok && !CameraFrameSink.isEnabled && (lastPublished != null || lastPublishedBlack)) {
                    CameraFrameSink.setEnabled(true)
                }
                status.text = buildString {
                    append(player.status())
                    append("\nreq=").append(seq)
                    append(" publish=").append(ok)
                }
            }
            main.postDelayed(this, 8)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "idle" }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        root.addView(Button(this).apply {
            text = "加载内置人脸 (640x480)"
            setOnClickListener { loadAssetFace() }
        }, lp())

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fps = EditText(this).apply { hint = "fps limit"; setText("30") }
        index = EditText(this).apply { hint = "frame index"; setText("0") }
        row1.addView(fps, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(index, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row1, lp())

        root.addView(Button(this).apply {
            text = "应用 limit"
            setOnClickListener {
                runCatching { player.limit(fps.text.toString().toIntOrNull() ?: 0) }
                    .onFailure { status.text = it.message }
                refresh()
            }
        }, lp())

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(Button(this).apply {
            text = "Pause"
            setOnClickListener {
                runCatching { player.pause(index.text.toString().toIntOrNull() ?: 0) }
                    .onFailure { status.text = it.message }
                refresh()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(Button(this).apply {
            text = "Resume"
            setOnClickListener {
                runCatching { player.resume(index.text.toString().toIntOrNull() ?: 0) }
                    .onFailure { status.text = it.message }
                refresh()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row2, lp())

        root.addView(Switch(this).apply {
            text = "loop"
            isChecked = true
            player.loop(true)
            setOnCheckedChangeListener { _, v -> player.loop(v); refresh() }
        }, lp())
        root.addView(Switch(this).apply {
            text = "stay (末帧保留)"
            isChecked = true
            player.stay(true)
            setOnCheckedChangeListener { _, v -> player.stay(v); refresh() }
        }, lp())

        modeButton = Button(this).apply {
            text = "模式：虚拟（点击切真机）"
            setOnClickListener {
                val target = !fakeMode
                isEnabled = false
                text = if (target) "切换为虚拟…" else "切换为真机…"
                status.text = if (target) "正在释放原相机，切回虚拟…" else "正在恢复原相机…"
                Executors.newSingleThreadExecutor().execute {
                    runCatching { CameraRealFake.setFake(this@VcamDebugActivity, target) }
                        .onSuccess { out ->
                            fakeMode = target
                            main.post {
                                text = if (target) "模式：虚拟（点击切真机）" else "模式：真机（点击切虚拟）"
                                status.text = "切换完成: " + if (target) "虚拟" else "真机"
                                isEnabled = true
                                Log.i("VcamDebug", "switch ok: $out")
                                autoReloadAllCameras()
                            }
                        }
                        .onFailure { err ->
                            main.post {
                                text = if (fakeMode) "模式：虚拟（点击切真机）" else "模式：真机（点击切虚拟）"
                                status.text = "切换失败: ${err.message}"
                                isEnabled = true
                            }
                        }
                }
            }
        }
        root.addView(modeButton, lp())

        root.addView(Button(this).apply {
            text = "强制重载所有相机"
            setOnClickListener {
                isEnabled = false
                autoReloadAllCameras()
                main.postDelayed({ isEnabled = true }, 1200)
            }
        }, lp())

        root.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                player.clear()
                val ok = CameraFrameSink.publishNv21(blackNv21, W, H)
                lastPublished = null
                lastPublishedBlack = true
                if (ok) CameraFrameSink.setEnabled(true)
                refresh()
            }
        }, lp())
        root.addView(Button(this).apply {
            text = "重新打开共享帧文件"
            setOnClickListener { openSink() }
        }, lp())

        preview = SurfaceView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, 900))
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { openPreview(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { closeCamera() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        openSink()
        running = true
        main.post(tick)
        if (intent?.getBooleanExtra("auto", false) == true) {
            main.postDelayed({ loadAssetFace() }, 300)
        }
        if (intent?.getBooleanExtra("toggle", false) == true) {
            main.postDelayed({ modeButton.performClick() }, 800)
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(tick)
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        super.onDestroy()
    }

    private fun lp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 }

    private fun openSink() {
        sinkOpen = CameraFrameSink.open(W, H)
        if (sinkOpen) {
            // 等第一帧真正写完再 enabled；否则 HAL 会读到空槽位（黑/绿）。
            lastReqSeq = CameraFrameSink.requestSeq()
            lastPublished = null
            lastPublishedBlack = false
        }
        refresh()
    }

    private fun refresh() {
        status.text = player.status() + "\nsink=" + sinkOpen +
                " req=" + CameraFrameSink.requestSeq()
    }

    private fun loadAssetFace() {
        try {
            val bmp = assets.open("vcam_face.jpg").use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("decode failed")
            val rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(-90f) }, true
            )
            val nv21 = toNv21(cover(rotated, W, H))
            player.load(listOf(VcamFramePlayer.Frame(W, H, nv21)), clear = true)
            player.resume(1)
            lastPublished = null
            lastPublishedBlack = false
            if (!sinkOpen) openSink()
            refresh()
        } catch (t: Throwable) {
            status.text = "load failed: ${t.message}"
        }
    }

    private fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(w)
        val sh = (src.height * scale).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val x = (sw - w) / 2
        val y = (sh - h) / 2
        return Bitmap.createBitmap(scaled, x, y, w, h)
    }

    private fun toNv21(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3 / 2)
        var yIndex = 0
        var uvIndex = w * h
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    /**
     * 模式切换后的统一收尾：cameraserver 重新枚举，再重开预览。
     * 只在切换成功/手动重载时调用，避免 onCreate 阶段无条件重启打断系统相机。
     */
    private fun autoReloadAllCameras() {
        if (reloading) return
        reloading = true
        closeCamera()
        status.text = "已就绪，自动重载所有相机…"
        Executors.newSingleThreadExecutor().execute {
            runCatching { CameraRealFake.reloadAllCameras(this@VcamDebugActivity) }
                .onSuccess { out ->
                    main.post {
                        status.text = "自动重载完成: $out"
                        reloading = false
                        if (::preview.isInitialized && preview.holder.surface.isValid) {
                            main.postDelayed({ openPreview(preview.holder.surface) }, 500)
                        }
                    }
                }
                .onFailure { err ->
                    main.post {
                        status.text = "自动重载失败: ${err.message}"
                        reloading = false
                    }
                }
        }
    }

    private fun openPreview(surface: Surface) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
            return
        }
        if (camera != null) return
        if (cameraThread == null) {
            cameraThread = HandlerThread("vcam-preview").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        val manager = getSystemService(CameraManager::class.java)
        try {
            manager.openCamera("1", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    camera = c
                    c.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (camera == null) return
                            session = s
                            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            req.addTarget(surface)
                            s.setRepeatingRequest(req.build(), null, cameraHandler)
                            main.post { status.text = (status.text.toString() + "\npreview camera 1") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            main.post { status.text = "preview configure failed" }
                        }
                    }, cameraHandler)
                }
                override fun onDisconnected(c: CameraDevice) { closeCamera() }
                override fun onError(c: CameraDevice, error: Int) {
                    main.post { status.text = "preview error=" + error }
                    closeCamera()
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            main.post { status.text = "open preview failed: " + t.message }
        }
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        session = null
        camera = null
    }

    companion object {
        private const val W = 640
        private const val H = 480
        private val blackNv21 = ByteArray(W * H * 3 / 2).apply {
            for (i in 0 until W * H) this[i] = 16
            for (i in W * H until size) this[i] = -128
        }.also { Log.i("VcamDebug", "black frame ${it.size}") }
    }
}
