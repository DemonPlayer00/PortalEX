package moe.fuqiuluo.portalex.ui.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import android.graphics.RectF
import android.util.Size
import android.widget.FrameLayout
import java.util.Collections
import java.util.concurrent.Executors
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.ActivityCameraRecordBinding
import moe.fuqiuluo.xposed.camera.CameraTags
import moe.fuqiuluo.xposed.camera.CameraVideoTrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

/**
 * 现场录制页：CameraX 负责预览方向、比例、镜像与录制编码。
 *
 * 旧实现手写 Camera2 + TextureView 矩阵，方向、比例、布局三者互相打架；
 * 这里改用 PreviewView（FIT_CENTER）和 VideoCapture，预览/视频由 CameraX
 * 统一按传感器方向和屏幕旋转处理，不再自行交换宽高。
 */
class CameraRecordActivity : AppCompatActivity() {

    companion object {
        const val RESULT_FILE_NAME = "file_name"
        const val RESULT_TRACK_JSON = "track_json"
        const val RESULT_RECORDED_AT = "recorded_at"
        private const val TAG = "CameraRecord"
        // 允许长视频：2 小时；实际停止仍由用户或存储/相机异常决定。
        private const val MAX_DURATION_MS = 2 * 60 * 60 * 1000L
        private const val OCCUPANCY_TIMEOUT_MS = MAX_DURATION_MS + 5 * 60_000L
    }

    private lateinit var binding: ActivityCameraRecordBinding
    private val uiExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var recordingActive = false
    private var preparing = false
    private var firstLocation: Pair<Double, Double>? = null
    private var recordStartedAt = 0L
    private val trackPoints = ArrayList<CameraVideoTrackPoint>()
    private var lastTrackSampleAt = 0L
    private var locationSampler: CameraLocationSampler? = null
    private var outputFile: File? = null
    private var usingFrontCamera = true
    private var faceOverlay: FaceOverlayView? = null
    private val faceExecutor = Executors.newSingleThreadExecutor()
    private val faceSamples = Collections.synchronizedList(ArrayList<RectF>())
    private var faceCalibrationPending = false
    private var faceCalibrating = false
    private var faceCalibrationDeadline = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!recordingActive) return
            val elapsed = System.currentTimeMillis() - recordStartedAt
            sampleTrack()
            binding.cameraRecordStatus.text = statusText(elapsed)
            if (elapsed >= MAX_DURATION_MS) {
                stopRecording()
                return
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 录制期间不锁屏；Activity 退出时窗口标志随窗口销毁。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCameraRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.keepScreenOn = true
        val overlay = FaceOverlayView(this)
        binding.cameraPreviewContainer.addView(
            overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        overlay.isClickable = false
        overlay.isFocusable = false
        overlay.expected = CameraFaceCalibration.load(this)
        faceOverlay = overlay
        binding.cameraFaceCalibrate.setOnClickListener { beginFaceCalibration() }
        binding.cameraRecordStatus.text = statusText(0L)
        binding.cameraRecordButton.setOnClickListener {
            when {
                recordingActive -> stopRecording()
                preparing -> Unit
                else -> prepareRecording()
            }
        }
        binding.cameraRecordCancel.setOnClickListener { cancelAndFinish() }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "没有摄像头权限", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.cameraPreview.scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
        binding.cameraPreview.implementationMode =
            androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        bindCamera()
    }

    fun onFaceCalibrate(view: android.view.View) {
        Log.i(TAG, "face calibrate click")
        beginFaceCalibration()
    }

    private fun beginFaceCalibration() {
        if (faceCalibrating) return
        faceCalibrationPending = true
        faceSamples.clear()
        binding.cameraRecordStatus.text = "人脸框校准：请移动到识别区，即将切到后台"
        mainHandler.postDelayed({ moveTaskToBack(true) }, 500)
    }

    override fun onResume() {
        super.onResume()
        if (!faceCalibrationPending) return
        faceCalibrationPending = false
        faceCalibrating = true
        faceSamples.clear()
        faceCalibrationDeadline = System.currentTimeMillis() + 2000L
        binding.cameraRecordStatus.text = "校准中：保持手机不动，正在采集人脸位置…"
    }

    private fun finishFaceCalibration() {
        if (!faceCalibrating) return
        faceCalibrating = false
        val box = CameraFaceCalibration.calibrate(faceSamples.toList())
        if (box == null) {
            binding.cameraRecordStatus.text = "校准失败：未采集到足够的人脸帧"
            Toast.makeText(this, "未识别到足够的人脸，请重试", Toast.LENGTH_LONG).show()
        } else {
            CameraFaceCalibration.save(this, box)
            faceOverlay?.expected = box
            binding.cameraRecordStatus.text = "人脸框校准完成"
            Toast.makeText(this, "人脸框已校准", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyzeFace(image: ImageProxy) {
        try {
            Log.i("CameraFace", "analyze frame ${image.width}x${image.height}")
            val analysis = CameraFaceDetector.detect(this, image)
            mainHandler.post {
                faceOverlay?.sourceAspect = if (image.imageInfo.rotationDegrees % 180 == 0) {
                    image.width.toFloat() / image.height
                } else {
                    image.height.toFloat() / image.width
                }
                faceOverlay?.actual = analysis.face
                faceOverlay?.warnings = analysis.warnings +
                        CameraFaceCalibration.framingWarnings(this, analysis.face)
                if (analysis.face != null && faceCalibrating) faceSamples.add(analysis.face)
                if (faceCalibrating && System.currentTimeMillis() >= faceCalibrationDeadline) {
                    finishFaceCalibration()
                }
            }
        } finally {
            image.close()
        }
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)

                val quality = QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(quality)
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    usingFrontCamera = true
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    usingFrontCamera = false
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                faceOverlay?.mirrorX = usingFrontCamera
                val faceAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .build()
                faceAnalysis.setAnalyzer(faceExecutor) { image -> analyzeFace(image) }
                provider.bindToLifecycle(this, selector, preview, capture, faceAnalysis)
                CameraOccupancyGuard.start("record", OCCUPANCY_TIMEOUT_MS) {
                    onCameraOccupancyTimeout()
                }
                Log.i(TAG, "CameraX bound: front=$usingFrontCamera")
            }.onFailure {
                Log.e(TAG, "CameraX 绑定失败", it)
                Toast.makeText(this, "相机启动失败：${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, uiExecutor)
    }

    /**
     * 点击后不立刻录：先启动融合定位，直到拿到第一个定位点才真正开始
     * VideoCapture。等待期间显示“准备中”，取消按钮仍可退出。
     */
    private fun prepareRecording() {
        if (cameraMockEnabled) {
            Toast.makeText(this, "请先关闭虚拟摄像头，再录制现场视频", Toast.LENGTH_LONG).show()
            return
        }
        if (videoCapture == null) {
            Toast.makeText(this, "相机还未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        if (recordingActive || preparing) return

        preparing = true
        firstLocation = null
        binding.cameraRecordButton.isEnabled = false
        binding.cameraRecordButton.setText("准备中…")
        binding.cameraRecordStatus.text = "准备中：等待第一个定位点…"

        locationSampler = CameraLocationSampler(this).apply {
            onLocation = { pos ->
                // 回调可能来自 GMS/LocationManager 线程，切回主线程再启动录制
                mainHandler.post {
                    if (preparing) onFirstLocation(pos)
                }
            }
        }.also { it.start() }

        // lastLocation 命中时不会触发 onLocation，主动检查一次
        mainHandler.postDelayed({
            if (preparing) {
                locationSampler?.latestPosition()?.let { onFirstLocation(it) }
            }
        }, 300)
    }

    private fun onFirstLocation(pos: Pair<Double, Double>) {
        if (!preparing) return
        preparing = false
        firstLocation = pos
        binding.cameraRecordStatus.text = "定位已就绪，开始录制…"
        beginVideoRecording()
    }

    private fun beginVideoRecording() {
        val capture = videoCapture ?: run {
            resetPreparing()
            Toast.makeText(this, "相机还未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val file = CameraMockStore.newVideoFile(this)
        outputFile = file
        val options = FileOutputOptions.Builder(file).build()
        runCatching {
            recording = capture.output.prepareRecording(this, options)
                .start(uiExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> onRecordStarted()
                        is VideoRecordEvent.Finalize -> onRecordFinalized(event)
                    }
                }
        }.onFailure {
            Log.e(TAG, "录制启动失败", it)
            Toast.makeText(this, "录制启动失败：${it.message}", Toast.LENGTH_LONG).show()
            runCatching { file.delete() }
            outputFile = null
            resetPreparing()
        }
    }

    private fun resetPreparing() {
        preparing = false
        firstLocation = null
        locationSampler?.stop()
        locationSampler = null
        binding.cameraRecordButton.isEnabled = true
        binding.cameraRecordButton.setText(R.string.camera_record_start)
    }

    private fun onRecordStarted() {
        recordingActive = true
        recordStartedAt = System.currentTimeMillis()
        lastTrackSampleAt = recordStartedAt
        trackPoints.clear()
        firstLocation?.let {
            synchronized(trackPoints) {
                trackPoints.add(CameraVideoTrackPoint(0L, it.first, it.second))
            }
        }
        sampleTrack()
        mainHandler.post(ticker)
        binding.cameraRecordButton.isEnabled = true
        binding.cameraRecordButton.setText(R.string.camera_record_stop)
    }

    private fun stopRecording() {
        if (!recordingActive) return
        recordingActive = false
        preparing = false
        mainHandler.removeCallbacks(ticker)
        locationSampler?.stop()
        locationSampler = null
        binding.cameraRecordButton.isEnabled = true
        binding.cameraRecordButton.setText(R.string.camera_record_start)
        runCatching { recording?.stop() }
    }

    private fun onRecordFinalized(event: VideoRecordEvent.Finalize) {
        recordingActive = false
        preparing = false
        mainHandler.removeCallbacks(ticker)
        locationSampler?.stop()
        locationSampler = null
        recording = null
        val file = outputFile
        if (event.hasError()) {
            Log.e(TAG, "录制失败 error=${event.error} cause=${event.cause}")
            Toast.makeText(this, "录制失败（${event.error}）", Toast.LENGTH_LONG).show()
            file?.let { runCatching { it.delete() } }
            outputFile = null
            binding.cameraRecordButton.isEnabled = true
            binding.cameraRecordButton.setText(R.string.camera_record_start)
            return
        }
        if (file == null || !file.exists()) {
            Toast.makeText(this, "录制文件丢失", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val track = synchronized(trackPoints) { trackPoints.toList() }
        val data = Intent().apply {
            putExtra(RESULT_FILE_NAME, file.name)
            putExtra(RESULT_RECORDED_AT, recordStartedAt)
            putExtra(RESULT_TRACK_JSON, encodeTrack(track))
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun sampleTrack() {
        if (!recordingActive) return
        val now = System.currentTimeMillis()
        if (now - lastTrackSampleAt < 1000L) return
        lastTrackSampleAt = now
        val pos = locationSampler?.latestPosition() ?: CameraMockRuntime.position() ?: return
        val t = (now - recordStartedAt).coerceAtLeast(0L)
        synchronized(trackPoints) {
            if (trackPoints.size < CameraTags.MAX_TRACK_POINTS) {
                trackPoints.add(CameraVideoTrackPoint(t, pos.first, pos.second))
            }
        }
    }

    private fun encodeTrack(track: List<CameraVideoTrackPoint>): String {
        val arr = JSONArray()
        track.forEach {
            arr.put(JSONObject().apply {
                put("t", it.tMs)
                put("lat", it.lat)
                put("lon", it.lon)
            })
        }
        return arr.toString()
    }

    private fun statusText(elapsedMs: Long): String = "%s · %s / 最长 %d 分钟".format(
        if (usingFrontCamera) "前置" else "后置",
        formatMs(elapsedMs),
        MAX_DURATION_MS / 60_000L
    )

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun cancelAndFinish() {
        recordingActive = false
        preparing = false
        mainHandler.removeCallbacks(ticker)
        locationSampler?.stop()
        locationSampler = null
        runCatching { recording?.close() }
        recording = null
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun onCameraOccupancyTimeout() {
        runCatching { recording?.close() }
        recording = null
        cameraProvider?.unbindAll()
        Toast.makeText(
            this,
            "相机占用已达上限（${CameraOccupancyGuard.DEFAULT_TIMEOUT_MS / 60000} 分钟），已自动释放",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (recordingActive || preparing) cancelAndFinish()
    }

    override fun onDestroy() {
        super.onDestroy()
        faceExecutor.shutdown()
        CameraOccupancyGuard.stop()
        mainHandler.removeCallbacks(ticker)
        runCatching { recording?.close() }
        recording = null
        cameraProvider?.unbindAll()
        locationSampler?.stop()
        locationSampler = null
    }
}
