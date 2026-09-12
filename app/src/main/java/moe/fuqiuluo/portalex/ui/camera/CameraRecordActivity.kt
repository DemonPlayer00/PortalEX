package moe.fuqiuluo.portalex.ui.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.ActivityCameraRecordBinding
import java.io.File

/**
 * **应用内录制**（刻意不走系统相机 Intent）：录出来的画面要能挂到地图坐标上，
 * 由我们自己控制落盘位置与参数，也避免"系统相机应用被我们的摄像头 hook 拦成假画面"这种自噬。
 *
 * 只录视频轨（不录音频）：摄像头模拟要的是画面，环境音没有意义，也省掉录音权限。
 * 时长上限 [MAX_DURATION_MS]：素材是给"视频点"用的，几十秒一段就够，超长的文件
 * 只会拖慢模块侧的解码与循环。
 */
class CameraRecordActivity : AppCompatActivity() {

    companion object {
        const val RESULT_FILE_NAME = "file_name"
        private const val TAG = "CameraRecord"
        private const val MAX_DURATION_MS = 60_000L
    }

    private lateinit var binding: ActivityCameraRecordBinding

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null

    /** 录制尺寸（**传感器方向**下的尺寸，交给 MediaRecorder，方向由 setOrientationHint 表达） */
    private var videoSize: Size = Size(1280, 720)

    /**
     * 预览尺寸（**显示方向**下的尺寸）。
     *
     * 与 [videoSize] 刻意分开：传感器方向 90/270 时（前置通常是 270），
     * 帧在缓冲区里就是"躺着"的 —— 预览的 SurfaceTexture 必须按**转置后**的尺寸取缓冲，
     * 屏幕上的画面才是正的。录制面不受影响（录制文件靠 `setOrientationHint` 表达方向）。
     */
    private var previewSize: Size = Size(720, 1280)

    private var sensorOrientation = 270

    /** 是否使用前置摄像头（用户口径：录制走前置） */
    private var usingFrontCamera = true
    private var outputFile: File? = null
    private var recording = false
    private var recordStartedAt = 0L

    /**
     * 计时器跑在相机线程上，**任何 View 操作都必须回到主线程**。
     *
     * 实测踩过（MI6/LineageOS 15，点「开始录制」即崩）：
     * `CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views`
     * —— 直接 `binding.cameraRecordStatus.text = …` 会抛在 HandlerThread 上。
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = System.currentTimeMillis() - recordStartedAt
            runOnUiThread {
                if (recording) binding.cameraRecordStatus.text = statusText(elapsed)
            }
            if (elapsed >= MAX_DURATION_MS) {
                // 到点自动停：stopRecording 只在主线程执行（见它的注释）
                runOnUiThread { stopRecording() }
                return
            }
            handler?.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ht = HandlerThread("camera-record").also { it.start() }
        thread = ht
        handler = Handler(ht.looper)

        binding.cameraRecordStatus.text = statusText(0L)
        binding.cameraRecordButton.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }
        binding.cameraRecordCancel.setOnClickListener { cancelAndFinish() }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "没有摄像头权限", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                configureTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    // ---- 相机 ----

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // 前置优先：素材是"我在这个位置看到的画面"，用前置更容易对着要拍的方向取景。
        // 没有前置（部分设备/被占用）才退回后置 —— 绝不因为"要前置"直接不给录。
        val front = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        val cameraId = front ?: manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()

        if (cameraId == null) {
            Toast.makeText(this, "没有可用摄像头", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        usingFrontCamera = front != null
        // 镜头标识写进状态栏：录出来的素材是"前置还是后置"对素材挑选有实际意义
        runOnUiThread { binding.cameraRecordStatus.text = statusText(0L) }

        val characteristics = manager.getCameraCharacteristics(cameraId)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        val map: StreamConfigurationMap? =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        videoSize = chooseSize(map?.getOutputSizes(MediaRecorder::class.java))
        previewSize = choosePreviewSize(map?.getOutputSizes(SurfaceTexture::class.java), videoSize)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startPreview()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "相机打开失败：$error")
                    device.close()
                    cameraDevice = null
                    runOnUiThread {
                        Toast.makeText(this@CameraRecordActivity, "相机打开失败（$error）", Toast.LENGTH_SHORT).show()
                    }
                }
            }, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera 异常", t)
            Toast.makeText(this, "相机不可用：${t.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 取一个不超过 1080p 的尺寸：素材够清晰，又不至于让解码端吃力 */
    private fun chooseSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        val maxPixels = 1920 * 1080
        return sizes
            .filter { it.width * it.height <= maxPixels }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }!!
    }

    /**
     * 选预览尺寸：与录制尺寸同比例、且**取显示方向**（传感器 90/270 时把宽高换过来）。
     *
     * 为什么必须换：SurfaceTexture 的默认缓冲尺寸决定了它在屏幕上的朝向 ——
     * 前置传感器方向 270°，不换的话预览会横躺 90°（录制文件反而是正的，
     * 因为方向由 `setOrientationHint` 表达）。换过之后本页在竖屏下**不需要任何旋转矩阵**。
     */
    private fun choosePreviewSize(sizes: Array<Size>?, record: Size): Size {
        val swapNeeded = sensorOrientation == 90 || sensorOrientation == 270
        val wantWidth = if (swapNeeded) record.height else record.width
        val wantHeight = if (swapNeeded) record.width else record.height
        if (sizes.isNullOrEmpty()) return Size(wantWidth, wantHeight)
        val targetRatio = wantWidth.toDouble() / wantHeight
        return sizes
            .filter { kotlin.math.abs(it.width.toDouble() / it.height - targetRatio) < 0.02 }
            .minByOrNull { kotlin.math.abs(it.width - wantWidth) + kotlin.math.abs(it.height - wantHeight) }
            ?: Size(wantWidth, wantHeight)
    }

    private fun previewSurface(): Surface? {
        val st = binding.cameraPreview.surfaceTexture ?: return null
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        return Surface(st)
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val surface = previewSurface() ?: return
        // 本方法跑在相机线程上：setTransform 是 View 操作，必须回主线程
        val viewWidth = binding.cameraPreview.width
        val viewHeight = binding.cameraPreview.height
        runOnUiThread { configureTransform(viewWidth, viewHeight) }
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        runCatching { s.setRepeatingRequest(request.build(), null, handler) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "预览会话配置失败")
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startPreview 异常", t)
        }
    }

    private fun startRecording() {
        val device = cameraDevice ?: return
        val preview = previewSurface() ?: return
        // 每次录制一个新文件：文件名即视频 id（provider 按 id 找文件）
        val file = CameraMockStore.newVideoFile(this)
        outputFile = file

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        recorder = rec
        try {
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(videoSize.width, videoSize.height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(8_000_000)
            rec.setOrientationHint(sensorOrientation)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
        } catch (t: Throwable) {
            Log.e(TAG, "MediaRecorder 准备失败", t)
            Toast.makeText(this, "录制初始化失败：${t.message}", Toast.LENGTH_SHORT).show()
            releaseRecorder()
            runCatching { file.delete() }
            return
        }

        val recorderSurface = rec.surface
        session?.close()
        session = null
        try {
            device.createCaptureSession(
                listOf(preview, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(preview)
                                addTarget(recorderSurface)
                            }
                            s.setRepeatingRequest(request.build(), null, handler)
                            rec.start()
                            recording = true
                            recordStartedAt = System.currentTimeMillis()
                            runOnUiThread {
                                binding.cameraRecordButton.setText(R.string.camera_record_stop)
                                binding.cameraRecordStatus.text = statusText(0L)
                            }
                            handler?.post(ticker)
                        } catch (t: Throwable) {
                            Log.e(TAG, "开始录制失败", t)
                            runOnUiThread { Toast.makeText(this@CameraRecordActivity, "开始录制失败", Toast.LENGTH_SHORT).show() }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "录制会话配置失败")
                        runOnUiThread { Toast.makeText(this@CameraRecordActivity, "录制会话配置失败", Toast.LENGTH_SHORT).show() }
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createCaptureSession(录制) 异常", t)
            releaseRecorder()
        }
    }

    /**
     * 停止并保存。**只在主线程调用**：它要改按钮文案、弹 Toast、finish ——
     * 相机线程上直接做这些会崩（`CalledFromWrongThreadException` / 无 Looper 的 Toast）。
     */
    private fun stopRecording() {
        if (!recording) return
        recording = false
        handler?.removeCallbacks(ticker)
        val file = outputFile
        val saved = try {
            recorder?.stop()
            true
        } catch (t: Throwable) {
            // MediaRecorder.stop 抛异常 = 文件不可用（时长过短/编码器异常），必须删掉半截文件
            Log.e(TAG, "停止录制失败", t)
            false
        }
        releaseRecorder()
        recordStartedAt = 0L
        binding.cameraRecordButton.setText(R.string.camera_record_start)

        if (!saved || file == null || !file.exists() || file.length() == 0L) {
            file?.let { runCatching { it.delete() } }
            Toast.makeText(this, "录制失败，未保存", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_FILE_NAME, file.name))
        finish()
    }

    /** 取消并退出（主线程） */
    private fun cancelAndFinish() {
        if (recording) {
            recording = false
            handler?.removeCallbacks(ticker)
            runCatching { recorder?.stop() }
            releaseRecorder()
            outputFile?.let { runCatching { it.delete() } }
        }
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    /**
     * 预览居中适配：TextureView 的尺寸由布局决定，而缓冲是 [previewSize] ——
     * 两者比例不一致时按"填满并居中裁剪"处理，避免画面被拉伸变形。
     *
     * 这里**不做旋转**：本页锁定竖屏（AndroidManifest 的 screenOrientation），
     * 且 [previewSize] 已经是显示方向的尺寸，所以竖屏下唯一要处理的就只剩比例。
     * 也**不做镜像**：录制出来的文件会原样喂给别的应用当摄像头画面，前后不一致的镜像
     * 会让"预览看到的"与"录下来的"不是同一张图（文字会反）——这里以所见即所得为准。
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val viewRatio = viewWidth.toFloat() / viewHeight
        val bufferRatio = previewSize.width.toFloat() / previewSize.height
        val matrix = Matrix()
        if (viewRatio > bufferRatio) {
            val scale = viewRatio / bufferRatio
            matrix.setScale(1f, scale, viewWidth / 2f, viewHeight / 2f)
        } else {
            val scale = bufferRatio / viewRatio
            matrix.setScale(scale, 1f, viewWidth / 2f, viewHeight / 2f)
        }
        binding.cameraPreview.setTransform(matrix)
    }

    /** 状态栏文案：镜头 + 已录时长 + 上限（上限是硬停，必须让用户看见） */
    private fun statusText(elapsedMs: Long): String = "%s · %s / 最长 %d 秒".format(
        if (usingFrontCamera) "前置" else "后置",
        formatMs(elapsedMs),
        MAX_DURATION_MS / 1000
    )

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    override fun onStop() {
        super.onStop()
        // 退到后台一律停：录制中被打断会留下坏文件，且相机必须让出来
        if (recording) cancelAndFinish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler?.removeCallbacks(ticker)
        runCatching { session?.close() }
        session = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        releaseRecorder()
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
