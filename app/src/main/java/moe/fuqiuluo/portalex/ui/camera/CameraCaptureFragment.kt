package moe.fuqiuluo.portalex.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.widget.FabBarView
import moe.fuqiuluo.portalex.databinding.FragmentCameraCaptureBinding
import moe.fuqiuluo.xposed.camera.CameraTags
import moe.fuqiuluo.xposed.camera.CameraVideoTrackPoint
import java.io.File
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * **录制页**（Fragment 版）：与其它功能页共用同一套架构 —— 主页面栈里的一个目的地、
 * 顶栏标题、悬浮胶囊放"校准人脸框 / 手动校准"，不再单独开一个全屏 Activity。
 *
 * 交互口径（与产品要求一致）：
 *  · 圆形录制键：播放 → 点击开始（先等第一个定位点，位置是录制硬依赖）→ 变停止 → 再点结束并处理视频；
 *  · **没有取消按钮**：系统返回就是离开；
 *  · 录制中按返回 → 弹窗「保存 / 不保存 / 继续录制」；
 *  · 保存后：调用方给了 `pointId` 就追加到那个点位，否则按 80m 内最近点位合并 / 新建路线；
 *  · 结果直接落库（[CameraMockStore]），返回上一页由它在 onResume 刷新列表，不依赖 Activity result。
 */
class CameraCaptureFragment : Fragment() {

    companion object {
        /** 从点位详情页进入时携带；为空表示"从素材页进入，自动归并/新建点位" */
        const val ARG_POINT_ID = "pointId"
        private const val TAG = "CameraCapture"
        private const val MAX_DURATION_MS = 2 * 60 * 60 * 1000L
        private const val OCCUPANCY_TIMEOUT_MS = MAX_DURATION_MS + 5 * 60_000L
        private const val FACE_SAMPLE_INTERVAL = 5
    }

    private var _binding: FragmentCameraCaptureBinding? = null
    private val binding get() = _binding!!
    private val uiExecutor: Executor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var cameraSelector: CameraSelector? = null
    private var analysisBound = true
    private var recording: Recording? = null

    private var recordingActive = false
    private var preparing = false
    /** 最终处理时是否保存（录制键停止 / 返回选"保存" = true；返回选"不保存" = false） */
    private var saveOnFinalize = true
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
    private var manualCalibrating = false
    private var faceFrameCount = 0
    private var analyzedWidth = 0
    private var analyzedHeight = 0
    private var faceCalibrationDeadline = 0L
    /** 分析线程用：不直接 requireContext()，避免视图销毁后回调命中分离的 Fragment */
    private lateinit var appContext: android.content.Context

    private val ticker = object : Runnable {
        override fun run() {
            if (!recordingActive) return
            val elapsed = System.currentTimeMillis() - recordStartedAt
            sampleTrack()
            _binding?.cameraRecordStatus?.text = statusText(elapsed)
            if (elapsed >= MAX_DURATION_MS) {
                stopRecording(save = true)
                return
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (!isAdded) return@registerForActivityResult
        if (grants.values.all { it }) {
            bindCamera()
        } else {
            Toast.makeText(requireContext(), R.string.camera_need_location_permission, Toast.LENGTH_LONG).show()
            popBack()
        }
    }

    /** 系统返回：录制中先问是否保存；否则直接退栈。 */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                recordingActive -> askSaveBeforeLeave()
                preparing -> {
                    cancelPreparing()
                    popBack()
                }
                else -> popBack()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.keepScreenOn = true
        appContext = requireContext().applicationContext
        binding.cameraRecordButton.isEnabled = false
        val overlay = FaceOverlayView(requireContext())
        binding.cameraPreviewContainer.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        overlay.isClickable = false
        overlay.isFocusable = false
        overlay.expected = CameraFaceCalibration.load(requireContext())
        overlay.hideLive = CameraFaceCalibration.isManual(requireContext())
        faceOverlay = overlay
        alignWarningsBelowStatus()

        binding.cameraRecordStatus.text = statusText(0L)
        binding.cameraPreview.scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
        binding.cameraPreview.implementationMode =
            androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        binding.cameraRecordButton.setOnClickListener {
            when {
                recordingActive -> stopRecording(save = true)
                preparing -> Unit
                else -> prepareRecording()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            binding.cameraRecordStatus.text = getString(R.string.camera_need_location_permission)
            binding.cameraRecordButton.isEnabled = false
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            bindCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        // 录制页不提供"取消"按钮：隐藏顶栏返回箭头，统一走系统返回（由 backCallback 接管）。
        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        updateFabActions()
        if (faceCalibrationPending) {
            faceCalibrationPending = false
            faceCalibrating = true
            faceSamples.clear()
            faceCalibrationDeadline = System.currentTimeMillis() + 2000L
            binding.cameraRecordStatus.text = "校准中：保持手机不动，正在采集人脸位置…"
        }
    }

    override fun onStop() {
        super.onStop()
        // 被切到后台 / 离开页面：未完成的录制直接丢弃（返回键路径会先弹窗，不会走到这里）
        if (recordingActive || preparing) {
            saveOnFinalize = false
            if (recordingActive) stopRecording(save = false) else cancelPreparing()
        }
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(ticker)
        locationSampler?.stop()
        locationSampler = null
        runCatching { recording?.close() }
        recording = null
        runCatching { cameraProvider?.unbindAll() }
        faceExecutor.shutdownNow()
        CameraOccupancyGuard.stop()
        super.onDestroyView()
        _binding = null
    }

    // ---------------------------------------------------------------- 权限与相机

    private fun missingPermissions(): List<String> {
        val missing = ArrayList<String>(3)
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.CAMERA
        }
        val fine = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return missing
    }

    private fun bindCamera() {
        if (!isAdded || _binding == null) return
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            if (!isAdded || _binding == null) return@addListener
            runCatching {
                val provider = providerFuture.get()
                cameraProvider = provider
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    usingFrontCamera = true
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    usingFrontCamera = false
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                val cameraInfo = provider.getCameraInfo(selector)

                val supported = runCatching {
                    Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR)
                }.getOrNull().orEmpty()
                val chosenQuality = listOf(Quality.FHD, Quality.HD, Quality.SD)
                    .firstOrNull { it in supported } ?: supported.firstOrNull() ?: Quality.HD
                val sixteenByNine = chosenQuality != Quality.SD
                Log.i(TAG, "video qualities=$supported chosen=$chosenQuality")

                val fpsRange = runCatching {
                    val chars = Camera2CameraInfo.from(cameraInfo)
                    @Suppress("UNCHECKED_CAST")
                    val ranges = (chars.getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                    ) as? Array<Range<Int>>)?.toList().orEmpty()
                    ranges.filter { it.upper <= 24 }.maxByOrNull { it.upper }
                        ?: ranges.minByOrNull { it.upper }
                }.getOrNull()

                val aspect = if (sixteenByNine) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                val previewBuilder = Preview.Builder().setTargetAspectRatio(aspect)
                if (fpsRange != null) {
                    runCatching {
                        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange
                        )
                    }.onFailure { Log.w(TAG, "set fps range failed", it) }
                }
                val preview = previewBuilder.build()
                preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)

                val quality = QualitySelector.from(
                    chosenQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                val recorder = Recorder.Builder().setQualitySelector(quality).build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                faceOverlay?.mirrorX = usingFrontCamera
                val analysisAspect = if (sixteenByNine) {
                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                } else {
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                }
                val analysisResolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(analysisAspect)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            if (sixteenByNine) Size(1280, 720) else Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                val faceAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisResolution)
                    .build()
                faceAnalysis.setAnalyzer(faceExecutor) { image -> analyzeFace(image) }
                previewUseCase = preview
                analysisUseCase = faceAnalysis
                cameraSelector = selector
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, capture, faceAnalysis)

                CameraOccupancyGuard.start("capture", OCCUPANCY_TIMEOUT_MS) {
                    onCameraOccupancyTimeout()
                }
                binding.cameraRecordButton.isEnabled = true
                Log.i(TAG, "CameraX bound: front=$usingFrontCamera")
            }.onFailure {
                Log.e(TAG, "CameraX 绑定失败", it)
                Toast.makeText(requireContext(), "相机启动失败：${it.message}", Toast.LENGTH_LONG).show()
                popBack()
            }
        }, uiExecutor)
    }

    private fun setAnalysisBound(bound: Boolean) {
        if (analysisBound == bound) return
        val provider = cameraProvider ?: return
        val selector = cameraSelector ?: return
        val preview = previewUseCase ?: return
        val capture = videoCapture ?: return
        val analysis = analysisUseCase ?: return
        runCatching {
            if (bound) {
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, capture, analysis)
            } else {
                provider.unbind(analysis)
                faceOverlay?.actual = null
                faceOverlay?.warnings = emptyList()
            }
            analysisBound = bound
        }.onFailure { Log.w(TAG, "rebind analysis=$bound failed", it) }
    }

    // ---------------------------------------------------------------- 录制

    /**
     * 点击后不立刻录：先启动融合定位，直到拿到第一个定位点才真正开始 VideoCapture。
     *
     * **位置是录制的硬依赖（设计使然）**：视频时间轴与轨迹坐标必须来自同一次采样、
     * 靠同一时钟对齐；事后补坐标无法还原拍摄当时的时序。
     */
    private fun prepareRecording() {
        if (requireContext().cameraMockEnabled) {
            Toast.makeText(requireContext(), "请先关闭虚拟摄像头，再录制现场视频", Toast.LENGTH_LONG).show()
            return
        }
        if (videoCapture == null) {
            Toast.makeText(requireContext(), "相机还未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        if (recordingActive || preparing) return

        preparing = true
        firstLocation = null
        setAnalysisBound(false)
        binding.cameraRecordButton.isEnabled = false
        binding.cameraRecordStatus.text = "准备中：等待第一个定位点…"

        locationSampler = CameraLocationSampler(requireContext()).apply {
            onLocation = { pos ->
                mainHandler.post {
                    if (preparing) onFirstLocation(pos)
                }
            }
        }.also { it.start() }

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
            Toast.makeText(requireContext(), "相机还未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val file = CameraMockStore.newVideoFile(requireContext())
        outputFile = file
        val options = FileOutputOptions.Builder(file).build()
        runCatching {
            recording = capture.output.prepareRecording(requireContext(), options)
                .start(uiExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> onRecordStarted()
                        is VideoRecordEvent.Finalize -> onRecordFinalized(event)
                    }
                }
        }.onFailure {
            Log.e(TAG, "录制启动失败", it)
            Toast.makeText(requireContext(), "录制启动失败：${it.message}", Toast.LENGTH_LONG).show()
            runCatching { file.delete() }
            outputFile = null
            resetPreparing()
        }
    }

    private fun resetPreparing() {
        preparing = false
        setAnalysisBound(true)
        firstLocation = null
        locationSampler?.stop()
        locationSampler = null
        _binding?.cameraRecordButton?.isEnabled = true
        setRecordButton(recording = false)
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
        setRecordButton(recording = true)
        _binding?.cameraRecordButton?.isEnabled = true
    }

    /** 停止录制；[save] 决定 Finalize 之后是入库还是删文件。 */
    private fun stopRecording(save: Boolean) {
        if (!recordingActive) return
        saveOnFinalize = save
        recordingActive = false
        preparing = false
        mainHandler.removeCallbacks(ticker)
        locationSampler?.stop()
        locationSampler = null
        _binding?.cameraRecordButton?.isEnabled = false
        setRecordButton(recording = true)
        runCatching { recording?.stop() }
    }

    private fun cancelPreparing() {
        preparing = false
        firstLocation = null
        locationSampler?.stop()
        locationSampler = null
        mainHandler.removeCallbacks(ticker)
        setAnalysisBound(true)
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
    }

    private fun onRecordFinalized(event: VideoRecordEvent.Finalize) {
        recordingActive = false
        preparing = false
        setAnalysisBound(true)
        mainHandler.removeCallbacks(ticker)
        locationSampler?.stop()
        locationSampler = null
        recording = null
        val file = outputFile
        outputFile = null
        if (!isAdded) return

        if (event.hasError()) {
            Log.e(TAG, "录制失败 error=${event.error} cause=${event.cause}")
            Toast.makeText(requireContext(), "录制失败（${event.error}）", Toast.LENGTH_LONG).show()
            file?.let { runCatching { it.delete() } }
            popBack()
            return
        }
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), "录制文件丢失", Toast.LENGTH_SHORT).show()
            popBack()
            return
        }
        if (!saveOnFinalize) {
            runCatching { file.delete() }
            Toast.makeText(requireContext(), "已丢弃这段录制", Toast.LENGTH_SHORT).show()
            popBack()
            return
        }
        val context = requireContext()
        val track = synchronized(trackPoints) { trackPoints.toList() }
        val pointName = persistRecording(file, track)
        Toast.makeText(
            context,
            if (pointName != null) "已保存到「$pointName」" else "已保存录制素材",
            Toast.LENGTH_SHORT
        ).show()
        popBack()
    }

    /** 落库：有 pointId 追加到该点位，否则按 80m 最近点合并 / 新建路线。 */
    private fun persistRecording(file: File, track: List<CameraVideoTrackPoint>): String? {
        val context = requireContext().applicationContext
        return runCatching {
            val video = CameraMockStore.registerRecorded(
                context, file, null, recordedAt = recordStartedAt, track = track
            )
            val pointId = arguments?.getString(ARG_POINT_ID)
            val point = pointId?.let { CameraMockStore.point(context, it) }
            if (point != null) {
                CameraMockStore.upsert(
                    context,
                    point.copy(videos = (point.videos + video).sortedBy { it.recordedAt })
                )
                point.name
            } else {
                val start = track.firstOrNull()?.let { it.lat to it.lon }
                    ?: firstLocation ?: CameraMockRuntime.position()
                    ?: return@runCatching null
                CameraMockStore.attachRecorded(context, video, start.first, start.second)
            }
        }.onFailure {
            Log.w(TAG, "保存录制失败", it)
        }.getOrNull()
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

    private fun setRecordButton(recording: Boolean) {
        val button = _binding?.cameraRecordButton ?: return
        button.setImageResource(
            if (recording) R.drawable.baseline_stop_24 else R.drawable.rounded_play_arrow_24
        )
        button.contentDescription = getString(
            if (recording) R.string.camera_record_stop else R.string.camera_record_start
        )
        val bgAttr = if (recording) {
            com.google.android.material.R.attr.colorError
        } else {
            com.google.android.material.R.attr.colorPrimary
        }
        val fgAttr = if (recording) {
            com.google.android.material.R.attr.colorOnError
        } else {
            com.google.android.material.R.attr.colorOnPrimary
        }
        button.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(button, bgAttr))
        button.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(button, fgAttr))
    }

    // ---------------------------------------------------------------- 返回确认

    private fun askSaveBeforeLeave() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.camera_back_recording_title)
            .setMessage(R.string.camera_back_recording_msg)
            .setPositiveButton(R.string.camera_back_save) { _, _ -> stopRecording(save = true) }
            .setNegativeButton(R.string.camera_back_discard) { _, _ -> stopRecording(save = false) }
            .setNeutralButton(R.string.camera_back_continue, null)
            .show()
    }

    // ---------------------------------------------------------------- 人脸框校准

    private fun updateFabActions() {
        val bar = (activity as? MainActivity)?.fabBar ?: return
        bar.setActions(
            listOf(
                FabBarView.Action(
                    R.drawable.baseline_control_camera_24,
                    getString(R.string.camera_face_calibrate)
                ) { beginFaceCalibration() },
                FabBarView.Action(
                    R.drawable.baseline_adjust_24,
                    getString(
                        if (manualCalibrating) R.string.camera_face_manual_save
                        else R.string.camera_face_manual
                    )
                ) { toggleManualCalibration() }
            )
        )
    }

    private fun beginFaceCalibration() {
        if (faceCalibrating) return
        if (manualCalibrating) {
            manualCalibrating = false
            faceOverlay?.editing = false
            updateFabActions()
        }
        faceOverlay?.hideLive = false
        faceCalibrationPending = true
        faceSamples.clear()
        _binding?.cameraRecordStatus?.text = "人脸框校准：请移动到识别区，即将切到后台"
        mainHandler.postDelayed({ requireActivity().moveTaskToBack(true) }, 500)
    }

    private fun toggleManualCalibration() {
        val overlay = faceOverlay ?: return
        if (recordingActive || preparing) {
            Toast.makeText(requireContext(), "录制中不能校准", Toast.LENGTH_SHORT).show()
            return
        }
        if (!manualCalibrating) {
            if (overlay.expected == null) {
                overlay.expected = overlay.actual?.let { RectF(it) }
                    ?: RectF(0.3f, 0.22f, 0.7f, 0.78f)
            }
            manualCalibrating = true
            overlay.editing = true
            _binding?.cameraRecordStatus?.text = "手动校准：拖动移动，双指缩放，保存后生效"
        } else {
            manualCalibrating = false
            overlay.editing = false
            overlay.expected?.let {
                CameraFaceCalibration.save(requireContext(), it, manual = true)
                overlay.hideLive = true
            }
            _binding?.cameraRecordStatus?.text = "手动校准已保存（已隐藏实时框）"
        }
        updateFabActions()
    }

    private fun finishFaceCalibration() {
        if (!faceCalibrating) return
        faceCalibrating = false
        val box = CameraFaceCalibration.calibrate(faceSamples.toList())
        if (box == null) {
            _binding?.cameraRecordStatus?.text = "校准失败：未采集到足够的人脸帧"
            Toast.makeText(requireContext(), "未识别到足够的人脸，请重试", Toast.LENGTH_LONG).show()
        } else {
            CameraFaceCalibration.save(requireContext(), box, manual = false)
            faceOverlay?.expected = box
            faceOverlay?.hideLive = false
            _binding?.cameraRecordStatus?.text = "人脸框校准完成"
            Toast.makeText(requireContext(), "人脸框已校准", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyzeFace(image: ImageProxy) {
        if (!isAdded) return
        try {
            faceFrameCount++
            if (faceFrameCount == 1 || image.width != analyzedWidth || image.height != analyzedHeight) {
                analyzedWidth = image.width
                analyzedHeight = image.height
                Log.i(TAG, "analysis frame ${image.width}x${image.height}")
            }
            if (faceFrameCount % FACE_SAMPLE_INTERVAL != 0) return
            val analysis = CameraFaceDetector.detect(appContext, image)
            mainHandler.post {
                if (!isAdded) return@post
                faceOverlay?.sourceAspect = if (image.imageInfo.rotationDegrees % 180 == 0) {
                    image.width.toFloat() / image.height
                } else {
                    image.height.toFloat() / image.width
                }
                faceOverlay?.actual = analysis.face
                faceOverlay?.warnings = CameraFaceCalibration.framingWarnings(appContext, analysis.face) +
                        analysis.warnings
                if (analysis.face != null && faceCalibrating) faceSamples.add(analysis.face)
                if (faceCalibrating && System.currentTimeMillis() >= faceCalibrationDeadline) {
                    finishFaceCalibration()
                }
            }
        } finally {
            image.close()
        }
    }

    private fun alignWarningsBelowStatus() {
        val gap = 12 * resources.displayMetrics.density
        val statusLocation = IntArray(2)
        val overlayLocation = IntArray(2)
        binding.cameraRecordStatus.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val overlay = faceOverlay ?: return@addOnLayoutChangeListener
            view.getLocationInWindow(statusLocation)
            overlay.getLocationInWindow(overlayLocation)
            val top = statusLocation[1] + view.height - overlayLocation[1] + gap
            if (overlay.warningsTop != top) overlay.warningsTop = top
        }
    }

    // ---------------------------------------------------------------- 收尾

    private fun onCameraOccupancyTimeout() {
        runCatching { recording?.close() }
        recording = null
        cameraProvider?.unbindAll()
        Toast.makeText(
            requireContext(),
            "相机占用已达上限（${CameraOccupancyGuard.DEFAULT_TIMEOUT_MS / 60000} 分钟），已自动释放",
            Toast.LENGTH_LONG
        ).show()
        popBack()
    }

    private fun popBack() {
        if (!isAdded) return
        runCatching { findNavController().popBackStack() }
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
}
