package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import org.opencv.objdetect.FaceDetectorYN
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * YuNet 人脸检测（含 5 点关键点）+ 有效性启发式：朝向、睁眼、五官遮挡、取景。
 *
 * 全部结果只用于提示文字，**不参与任何录制判定** —— 检测失败、提示异常都不影响
 * 开始/继续录制。所有检查都是启发式：光照、肤色、眼镜、胡须都会引起误报，
 * 因此措辞一律用「可能 / 请检查」。
 */
object CameraFaceDetector {
    private const val TAG = "CameraFace"
    private const val MODEL_ASSET = "face_detection_yunet_2023mar.onnx"

    @Volatile private var faceDetector: FaceDetectorYN? = null
    @Volatile private var detectorWidth = 0
    @Volatile private var detectorHeight = 0
    @Volatile private var eyeCascade: CascadeClassifier? = null
    @Volatile private var initTried = false

    /** 去抖：Haar 眼睛级联在睁眼时也会偶尔漏检，必须连续多帧一致才提示。 */
    private class Latch(private val required: Int) {
        private var count = 0
        fun update(value: Boolean): Boolean {
            if (value) count++ else count = 0
            return count >= required
        }
        fun reset() { count = 0 }
    }
    // 睁闭眼只靠 Haar 眼睛级联，证据必须足够硬才提示：连续 12 次分析（约 2 秒）一致。
    private val closedLatch = Latch(required = 12)
    private val oneEyeLatch = Latch(required = 12)
    private val occlusionLatch = Latch(required = 3)
    private val glareLatch = Latch(required = 3)

    data class Analysis(
        val face: RectF?,
        val eyes: Int,
        val warnings: List<String>,
    )

    @Synchronized
    private fun ensureInit(context: Context): Boolean {
        if (initTried) return faceDetector != null
        initTried = true
        runCatching {
            if (!OpenCVLoader.initLocal()) {
                Log.e(TAG, "OpenCVLoader.initLocal failed")
                return false
            }
            eyeCascade = loadCascade(context, "haarcascade_eye.xml")?.takeUnless { it.empty() }
            val model = copyAsset(context, MODEL_ASSET)
            faceDetector = FaceDetectorYN.create(model.absolutePath, "", Size(640.0, 480.0), 0.7f, 0.3f, 5000)
            Log.i(TAG, "yunet ready=${faceDetector != null} eyes=${eyeCascade != null}")
        }.onFailure { Log.e(TAG, "init failed", it) }
        return faceDetector != null
    }

    private fun copyAsset(context: Context, name: String): File {
        val file = File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    private fun loadCascade(context: Context, name: String): CascadeClassifier? {
        val file = copyAsset(context, name)
        return CascadeClassifier(file.absolutePath)
    }

    /** YuNet 的输入尺寸必须与实际帧一致；横竖屏切换时重建输入尺寸。 */
    @Synchronized
    private fun detectorFor(width: Int, height: Int): FaceDetectorYN? {
        val current = faceDetector ?: return null
        if (width == detectorWidth && height == detectorHeight) return current
        return runCatching {
            current.setInputSize(Size(width.toDouble(), height.toDouble()))
            detectorWidth = width
            detectorHeight = height
            current
        }.onFailure { Log.w(TAG, "setInputSize failed", it) }.getOrNull()
    }

    fun detect(context: Context, image: ImageProxy): Analysis {
        if (!ensureInit(context)) return Analysis(null, 0, listOf("人脸检测未就绪"))
        val gray = yuvToGray(image) ?: return Analysis(null, 0, listOf("无法读取画面"))
        var rotated: Mat? = null
        var bgr: Mat? = null
        val faces = Mat()
        return try {
            rotated = rotate(gray, image.imageInfo.rotationDegrees)
            bgr = Mat().also { Imgproc.cvtColor(rotated, it, Imgproc.COLOR_GRAY2BGR) }
            val detector = detectorFor(rotated.cols(), rotated.rows())
                ?: return Analysis(null, 0, listOf("人脸检测未就绪"))
            detector.detect(bgr, faces)
            analyze(rotated, faces)
        } catch (t: Throwable) {
            Log.w(TAG, "detect failed", t)
            Analysis(null, 0, listOf("人脸分析失败"))
        } finally {
            faces.release()
            bgr?.release()
            if (rotated != null && rotated !== gray) rotated.release()
            gray.release()
        }
    }

    private fun analyze(frame: Mat, faces: Mat): Analysis {
        val best = bestFace(faces)
            ?: return Analysis(null, 0, listOf("未检测到人脸，请正对镜头")).also { resetLatches() }
        val fw = frame.cols().toFloat()
        val fh = frame.rows().toFloat()
        val box = Rect(best[0].toInt(), best[1].toInt(), best[2].toInt(), best[3].toInt())
        val face = RectF(box.x / fw, box.y / fh, (box.x + box.width) / fw, (box.y + box.height) / fh)

        // YuNet 输出顺序：右眼、左眼、鼻尖、右嘴角、左嘴角（均为图像坐标）
        val rightEye = floatArrayOf(best[4], best[5])
        val leftEye = floatArrayOf(best[6], best[7])
        val nose = floatArrayOf(best[8], best[9])
        val rightMouth = floatArrayOf(best[10], best[11])
        val leftMouth = floatArrayOf(best[12], best[13])

        val eyeDistance = hypot(
            (leftEye[0] - rightEye[0]).toDouble(),
            (leftEye[1] - rightEye[1]).toDouble()
        ).toFloat().coerceAtLeast(8f)
        val eyeMidX = (leftEye[0] + rightEye[0]) / 2f
        val eyeMidY = (leftEye[1] + rightEye[1]) / 2f
        val mouthMidX = (leftMouth[0] + rightMouth[0]) / 2f
        val mouthMidY = (leftMouth[1] + rightMouth[1]) / 2f
        // roll：两眼连线倾角；yaw：鼻尖相对双眼中点的水平偏移；pitch：鼻尖在眼—嘴之间的高度比例
        val roll = Math.toDegrees(
            atan2((leftEye[1] - rightEye[1]).toDouble(), (leftEye[0] - rightEye[0]).toDouble())
        ).toFloat()
        val yaw = (nose[0] - eyeMidX) / eyeDistance
        val eyeMouthGap = (mouthMidY - eyeMidY).coerceAtLeast(1f)
        val pitch = (nose[1] - eyeMidY) / eyeMouthGap

        // 脸太小时眼睛只有十几个像素，级联不可靠，直接不判睁闭眼。
        // YuNet 的框允许越界，裁到画面内再算 ROI，否则密度/眼睛检测会整体失效。
        val safeBox = Rect(
            box.x.coerceIn(0, frame.cols() - 1),
            box.y.coerceIn(0, frame.rows() - 1),
            0,
            0
        ).let {
            it.width = ((box.x + box.width).coerceAtMost(frame.cols()) - it.x).coerceAtLeast(1)
            it.height = ((box.y + box.height).coerceAtMost(frame.rows()) - it.y).coerceAtLeast(1)
            it
        }
        // 只有「整张脸都在画面里 + 够大」时才判睁闭眼；否则级联只会给出噪声。
        val eyeCheckable = eyeDistance >= 30f && safeBox.width == box.width && safeBox.height == box.height
        val eyes = if (eyeCheckable) countEyes(frame, safeBox) else -1
        val faceDensity = edgeDensity(frame, safeBox)
        val mouthDensity = edgeDensity(
            frame,
            rectAround(mouthMidX, mouthMidY, eyeDistance * 1.2f, eyeDistance * 0.55f)
        )
        val noseDensity = edgeDensity(
            frame,
            rectAround(nose[0], nose[1], eyeDistance * 0.5f, eyeDistance * 0.7f)
        )

        val mouthCovered = faceDensity > 0f && mouthDensity in 0f..(faceDensity * 0.25f)
        val noseCovered = faceDensity > 0f && noseDensity in 0f..(faceDensity * 0.18f)
        val closed = closedLatch.update(eyes == 0)
        val oneEye = oneEyeLatch.update(eyes == 1)
        val occluded = occlusionLatch.update(mouthCovered || noseCovered)

        val glareOnFace = glareRatio(frame, safeBox)
        val glareOverall = glareRatio(frame, Rect(0, 0, frame.cols(), frame.rows()))
        val sharpness = laplacianVariance(frame, safeBox)
        // 强光判定：脸被高光覆盖，或整幅画面大面积高光且已经糊到脸上。
        val glare = glareOnFace > 0.08f || (glareOnFace > 0.03f && glareOverall > 0.2f)
        val glareHit = glareLatch.update(glare)

        val warnings = buildList {
            if (glareHit) {
                if (sharpness > 0.0 && sharpness < 120.0) {
                    add("强光导致画面发糊，请避开灯光或调整角度")
                } else {
                    add("画面有强光干扰，请避开光源")
                }
            }
            if (abs(roll) > 15f) add("头是歪的，请摆正手机或头部")
            if (abs(yaw) > 0.22f) add("脸偏向一侧，请正对镜头")
            if (pitch < 0.25f || pitch > 0.85f) add("请平视镜头，不要抬头或低头")
            if (closed) add("闭眼或眼部遮挡，请睁眼并保持正脸")
            else if (oneEye) add("单侧眼睛被遮挡，请检查")
            if (occluded) add("口鼻区域疑似被遮挡，请检查口罩/手部")
        }
        // 只在「有问题」或「眼睛没数够」时留一行指标，方便真机调阈值。
        if (warnings.isNotEmpty() || eyes < 2) {
            Log.i(
                TAG,
                "roll=%.1f yaw=%.2f pitch=%.2f eyes=%d mouth=%.3f nose=%.3f face=%.3f " +
                    "glareFace=%.3f glareAll=%.3f sharp=%.0f".format(
                        roll, yaw, pitch, eyes, mouthDensity, noseDensity, faceDensity,
                        glareOnFace, glareOverall, sharpness
                    )
            )
        }
        return Analysis(face, eyes, warnings)
    }

    private fun resetLatches() {
        closedLatch.reset()
        oneEyeLatch.reset()
        occlusionLatch.reset()
        glareLatch.reset()
    }

    private fun rectAround(cx: Float, cy: Float, w: Float, h: Float): Rect = Rect(
        (cx - w / 2f).toInt(),
        (cy - h / 2f).toInt(),
        w.toInt().coerceAtLeast(1),
        h.toInt().coerceAtLeast(1)
    )

    /** 取面积最大的脸（YuNet 第 15 列是 score，这里按框面积选主体）。 */
    private fun bestFace(faces: Mat): FloatArray? {
        if (faces.rows() == 0) return null
        val row = FloatArray(15)
        var bestArea = -1f
        var best: FloatArray? = null
        for (i in 0 until faces.rows()) {
            faces.get(i, 0, row)
            val area = row[2] * row[3]
            if (area > bestArea) {
                bestArea = area
                best = row.copyOf()
            }
        }
        return best
    }

    /**
     * 在人脸框上半部跑眼睛级联：比按关键点裁小窗口稳，误检中心在嘴/眉上也更容易过滤。
     * 返回检测到眼睛的侧数（0..2），检测器不可用时返回 -1。
     */
    private fun countEyes(frame: Mat, box: Rect): Int {
        val eye = eyeCascade ?: return -1
        if (!inside(frame, box)) return -1
        val sub = frame.submat(box)
        val equalized = Mat()
        val hits = MatOfRect()
        return try {
            Imgproc.equalizeHist(sub, equalized)
            val min = (box.width * 0.09).coerceAtLeast(10.0)
            eye.detectMultiScale(equalized, hits, 1.1, 4, 0, Size(min, min))
            val upperLimit = box.height * 0.62f
            val list = hits.toArray()
            val eyes = list.count { it.y + it.height / 2f <= upperLimit }
            eyes.coerceAtMost(2)
        } catch (t: Throwable) {
            -1
        } finally {
            hits.release()
            equalized.release()
            sub.release()
        }
    }

    private fun inside(frame: Mat, r: Rect): Boolean =
        r.width > 4 && r.height > 4 && r.x >= 0 && r.y >= 0 &&
            r.x + r.width <= frame.cols() && r.y + r.height <= frame.rows()

    /** 区域边缘密度：遮挡物（口罩、手、头发）通常比五官平整得多，密度显著偏低。 */
    private fun edgeDensity(frame: Mat, r: Rect): Float {
        if (!inside(frame, r)) return -1f
        val roi = frame.submat(r)
        val edges = Mat()
        return try {
            Imgproc.Canny(roi, edges, 40.0, 110.0)
            Core.countNonZero(edges).toFloat() / (r.width * r.height).toFloat()
        } catch (t: Throwable) {
            -1f
        } finally {
            edges.release()
            roi.release()
        }
    }

    /** 高光像素占比：路灯、车灯直射时这一片会整块饱和，人脸细节被冲掉。 */
    private fun glareRatio(frame: Mat, r: Rect): Float {
        if (!inside(frame, r)) return 0f
        val roi = frame.submat(r)
        val mask = Mat()
        return try {
            Imgproc.threshold(roi, mask, 244.0, 255.0, Imgproc.THRESH_BINARY)
            Core.countNonZero(mask).toFloat() / (r.width * r.height).toFloat()
        } catch (t: Throwable) {
            0f
        } finally {
            mask.release()
            roi.release()
        }
    }

    /** 拉普拉斯方差：低于 ~100 基本就是糊了（对焦失败、强光溢出、运动模糊）。 */
    private fun laplacianVariance(frame: Mat, r: Rect): Double {
        if (!inside(frame, r) || r.width < 8 || r.height < 8) return 0.0
        val roi = frame.submat(r)
        val lap = Mat()
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        return try {
            Imgproc.Laplacian(roi, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, std)
            val v = std.toArray()[0]
            v * v
        } catch (t: Throwable) {
            0.0
        } finally {
            std.release()
            mean.release()
            lap.release()
            roi.release()
        }
    }

    private fun yuvToGray(image: ImageProxy): Mat? = runCatching {
        val y = image.planes[0]
        val w = image.width
        val h = image.height
        val mat = Mat(h, w, CvType.CV_8UC1)
        val row = ByteArray(w)
        for (r in 0 until h) {
            val off = r * y.rowStride
            if (off + w > y.buffer.limit()) break
            y.buffer.position(off)
            y.buffer.get(row, 0, w)
            mat.put(r, 0, row)
        }
        mat
    }.getOrNull()

    private fun rotate(source: Mat, degrees: Int): Mat = when (degrees) {
        90 -> Mat().also { Core.rotate(source, it, Core.ROTATE_90_CLOCKWISE) }
        180 -> Mat().also { Core.rotate(source, it, Core.ROTATE_180) }
        270 -> Mat().also { Core.rotate(source, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
        else -> source
    }
}
