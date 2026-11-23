package moe.fuqiuluo.portalex.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import moe.fuqiuluo.portalex.databinding.ActivityCameraStillBinding
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 现场拍摄**单帧素材**：拍一张照片，自动绑定到当前定位位置。
 *
 * 与录像页（[CameraRecordActivity]）的分工：
 *  · 录像 = 一段画面 + 一条轨迹，一次拍摄会形成"路线"；
 *  · 单帧 = 一张图片 + **一个点位**，适合"站在一个地方拍一张"的素材。
 *    落盘时它被编码成一帧 mp4（见 [CameraStillMedia]），因此后续播放/选片
 *    与视频完全同构，只有"起播偏移恒为 0、只播一帧"两点差别。
 *
 * 位置来源与录像页一致（[CameraLocationSampler]：GMS 融合 / 系统 provider / 百度），
 * 定位未就绪时**不允许拍摄** —— 一张没有位置的图片对"按位置选片"毫无意义。
 */
class CameraStillCaptureActivity : AppCompatActivity() {

    companion object {
        const val RESULT_FILE_NAME = "file_name"
        const val RESULT_LAT = "lat"
        const val RESULT_LON = "lon"
        const val RESULT_RECORDED_AT = "recorded_at"

        private const val TAG = "CameraStillCapture"
        private const val OCCUPANCY_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private lateinit var binding: ActivityCameraStillBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var imageCapture: ImageCapture? = null
    private var sampler: CameraLocationSampler? = null
    private var latestPosition: Pair<Double, Double>? = null
    private var busy = false

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCameraStillBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cameraStillPreview.scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
        binding.cameraStillPreview.implementationMode =
            androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        binding.cameraStillShutter.setOnClickListener { capture() }
        binding.cameraStillCancel.setOnClickListener { finish() }
        binding.cameraStillShutter.isEnabled = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "没有摄像头权限", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        updateStatus()
        startLocationSampler()
        bindCamera()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { sampler?.stop() }
        sampler = null
        runCatching { CameraOccupancyGuard.stop() }
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 定位

    /**
     * 定位一进来就更新读数，但不主动结束界面：用户在预览里构图往往要几秒，
     * 那几秒里定位大概率已经就绪。真正拍摄时才要求"有位置"。
     */
    private fun startLocationSampler() {
        val created = CameraLocationSampler(this)
        created.onLocation = { position ->
            mainHandler.post {
                latestPosition = position
                updateStatus()
            }
        }
        runCatching { created.start() }
        sampler = created
        // lastLocation 命中时不会再回调，主动取一次
        mainHandler.postDelayed({
            created.latestPosition()?.let {
                latestPosition = it
                updateStatus()
            }
        }, 500L)
        mainHandler.post(statusTicker)
    }

    /** 当前用于绑定的位置：融合定位优先，退回模块镜像的当前坐标 */
    private fun currentPosition(): Pair<Double, Double>? =
        latestPosition ?: sampler?.latestPosition() ?: CameraMockRuntime.position()

    private fun updateStatus() {
        if (!::binding.isInitialized) return
        val position = currentPosition()
        binding.cameraStillStatus.text = when {
            busy -> "正在生成单帧素材…"
            position == null -> "正在定位…（没有位置的图片无法参与选片）"
            else -> "已定位 ${CameraMockFormat.coord(position.first, position.second)} · 拍摄后会绑定此位置"
        }
        binding.cameraStillShutter.isEnabled = !busy && imageCapture != null
    }

    // ---------------------------------------------------------------- 相机

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                // 拍"所在地点"用后摄；没有后摄才用它有的那个（单摄机型/模拟器）
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                provider.unbindAll()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.cameraStillPreview.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(binding.root.display?.rotation ?: android.view.Surface.ROTATION_0)
                    .build()
                imageCapture = capture
                provider.bindToLifecycle(this, selector, preview, capture)
                CameraOccupancyGuard.start("still", OCCUPANCY_TIMEOUT_MS) {
                    Toast.makeText(this, "拍摄超时，已释放相机", Toast.LENGTH_SHORT).show()
                    finish()
                }
                updateStatus()
            }.onFailure {
                Log.e(TAG, "相机绑定失败", it)
                Toast.makeText(this, "相机启动失败：${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, uiExecutor)
    }

    // ---------------------------------------------------------------- 拍摄

    private fun capture() {
        if (busy) return
        val capture = imageCapture ?: run {
            Toast.makeText(this, "相机还未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val position = currentPosition()
        if (position == null) {
            Toast.makeText(this, "还没有定位结果，请稍候再拍", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        updateStatus()
        val jpeg = File(cacheDir, "camera-still-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(jpeg).build()
        capture.takePicture(options, ioExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val recordedAt = System.currentTimeMillis()
                val fileName = encode(jpeg)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (fileName == null) {
                        busy = false
                        updateStatus()
                        Toast.makeText(
                            this@CameraStillCaptureActivity,
                            "图片保存失败（无法生成单帧素材）",
                            Toast.LENGTH_LONG
                        ).show()
                        return@post
                    }
                    setResult(
                        RESULT_OK,
                        android.content.Intent().apply {
                            putExtra(RESULT_FILE_NAME, fileName)
                            putExtra(RESULT_LAT, position.first)
                            putExtra(RESULT_LON, position.second)
                            putExtra(RESULT_RECORDED_AT, recordedAt)
                        }
                    )
                    finish()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "拍照失败", exception)
                jpeg.delete()
                mainHandler.post {
                    busy = false
                    updateStatus()
                    if (isFinishing || isDestroyed) return@post
                    Toast.makeText(
                        this@CameraStillCaptureActivity,
                        "拍照失败：${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }

    /**
     * 把刚拍下的 JPEG 编码成库里的一帧 mp4。**在后台线程执行**，
     * 返回库目录内的文件名；失败返回 null（不留半截文件）。
     */
    private fun encode(jpeg: File): String? {
        var bitmap: Bitmap? = null
        return try {
            val source = CameraStillMedia.prepareFromFile(jpeg)
            if (source == null) {
                Log.w(TAG, "图片解码失败")
                null
            } else {
                bitmap = source.bitmap
                val file = CameraMockStore.encodeStillFile(this, source.bitmap)
                // 这里只是"先落盘"；真正的库条目由调用方用 registerStill 建立，
                // 否则拍摄页会替用户决定"加进哪个点位"。
                file?.name
            }
        } finally {
            bitmap?.recycle()
            jpeg.delete()
        }
    }
}
