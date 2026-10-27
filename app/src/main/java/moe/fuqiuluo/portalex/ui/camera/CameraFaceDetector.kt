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

    /**
     * 滑动窗口去抖：窗口内命中够多次才提示。
     *
     * 早先用的是「连续 N 次」计数器，一旦 YuNet 漏检一帧就清零 —— 实际使用中漏检很常见，
     * 闭眼提示因此几乎永远攒不满，表现为「闭眼没反应」。窗口容忍个别漏帧。
     */
    private class Latch(private val window: Int, private val required: Int) {
        private val recent = ArrayDeque<Boolean>()
        fun update(value: Boolean): Boolean {
            recent.addLast(value)
            while (recent.size > window) recent.removeFirst()
            return recent.count { it } >= required
        }
        fun hits(): Int = recent.count { it }
    }
    private val occlusionLatch = Latch(window = 6, required = 4)
    private val glareLatch = Latch(window = 6, required = 4)

    /**
     * 睁闭眼不做去抖：本帧看到闭眼就提示。
     * 只保留 2 帧的「尾巴」，避免提示在相邻帧之间闪烁，不会带来可感知的延迟。
     */
    private var closedHold = 0
    private var oneEyeHold = 0
    private var analysisCount = 0

    /**
     * 闭眼时 YuNet 常常直接给不出人脸，界面就会「框消失 + 未检测到人脸」，
     * 于是闭眼反而永远等不到闭眼提示。这里保留最近一次成功检测的框：
     * 短时间内继续在该区域判睁眼，画面明显变了（人走了/转身）就立刻放弃。
     */
    private var heldBox: Rect? = null
    private var heldMean = 0.0
    private var heldStd = 0.0
    private var heldFrames = 0
    // 缓存框最多撑 ~1.3 秒；再久就说明人走了，继续画旧框只会误导。
    private const val HELD_LIMIT = 8

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
            // 0.55 而不是 0.7：闭眼时 YuNet 的置信度会明显下降，阈值太高会导致「闭眼即丢脸」。
            faceDetector = FaceDetectorYN.create(model.absolutePath, "", Size(640.0, 480.0), 0.55f, 0.3f, 5000)
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
            val best = bestFace(faces)
            if (best != null) {
                rememberFace(rotated, best)
                analyze(rotated, best)
            } else {
                val held = heldBox
                if (held != null && heldFrames < HELD_LIMIT && stillSameFace(rotated, held)) {
                    heldFrames++
                    analyzeHeld(rotated, held)
                } else {
                    heldBox = null
                    Analysis(null, 0, listOf("未检测到人脸，请正对镜头")).also { decayLatches() }
                }
            }
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

    private fun analyze(frame: Mat, best: FloatArray): Analysis {
        val fw = frame.cols().toFloat()
        val fh = frame.rows().toFloat()
        val box = Rect(best[0].toInt(), best[1].toInt(), best[2].toInt(), best[3].toInt())
        val rawFace = RectF(box.x / fw, box.y / fh, (box.x + box.width) / fw, (box.y + box.height) / fh)
        val face = smoothFace(rawFace)

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

        // YuNet 的框允许越界，裁到画面内再算 ROI，否则密度/眼睛检测会整体失效。
        val safeBox = clampBox(frame, box)
        // 只要上半张脸（眼睛所在的那一带）完整在画面里，就足以判睁闭眼；
        // 要求整张脸框都在画面内会把大量正常构图排除掉，等于关掉检查。
        val eyeBand = Rect(safeBox.x, safeBox.y, safeBox.width, (safeBox.height * 0.6f).toInt().coerceAtLeast(8))
        val eyeCheckable = eyeDistance >= 26f && inside(frame, eyeBand) && safeBox.width == box.width
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
        val closed = holdEye(eyes == 0)
        val oneEye = !closed && holdOneEye(eyes == 1)
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
        // 诊断日志：有问题、眼睛没数够、或每 30 次分析（约 5 秒）留一行。
        analysisCount++
        if (warnings.isNotEmpty() || eyes < 2 || analysisCount % 30 == 0) {
            Log.i(
                TAG,
                "eyes=%d checkable=%b ".format(eyes, eyeCheckable) + eyeStats(frame, rightEye, leftEye, eyeDistance)
            )
        }
        return Analysis(face, eyes, warnings)
    }

    /**
     * 眼部像素指标（不依赖级联）：暗像素占比 d、单行最大暗像素占比 mr。
     * 睁眼时虹膜是一块紧凑暗区（mr 高），闭眼时只剩睫毛线（mr 低）。
     */
    private fun eyeStats(frame: Mat, rightEye: FloatArray, leftEye: FloatArray, eyeDistance: Float): String {
        return " L[" + eyeStatsOne(frame, leftEye, eyeDistance) + "] R[" +
            eyeStatsOne(frame, rightEye, eyeDistance) + "]"
    }

    private fun eyeStatsOne(frame: Mat, center: FloatArray, eyeDistance: Float): String {
        val w = (eyeDistance * 0.55f).toInt().coerceAtLeast(8)
        val h = (eyeDistance * 0.38f).toInt().coerceAtLeast(6)
        val r = rectAround(center[0], center[1], w.toFloat(), h.toFloat())
        if (!inside(frame, r)) return "na"
        val roi = frame.submat(r)
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        return try {
            Core.meanStdDev(roi, mean, std)
            val m = mean.toArray()[0]
            val thresh = m - std.toArray()[0] * 0.5
            var dark = 0
            var maxRow = 0
            val line = ByteArray(r.width)
            for (y in 0 until r.height) {
                roi.get(y, 0, line)
                var rowDark = 0
                for (x in 0 until r.width) {
                    if ((line[x].toInt() and 0xFF) < thresh) rowDark++
                }
                dark += rowDark
                if (rowDark > maxRow) maxRow = rowDark
            }
            val area = (r.width * r.height).toFloat()
            "d=%.2f mr=%.2f m=%.0f".format(dark / area, maxRow / r.width.toFloat(), m)
        } catch (t: Throwable) {
            "err:" + (t.message ?: t.javaClass.simpleName).take(30)
        } finally {
            std.release()
            mean.release()
            roi.release()
        }
    }

    private var smoothedFace: RectF? = null

    /**
     * 一阶平滑：压掉连续帧之间的小幅抖动。
     * 位置或面积突变视为"重新捕获"（人移动了、换人了），直接采纳，避免框慢慢爬过去。
     */
    private fun smoothFace(raw: RectF): RectF {
        val prev = smoothedFace
        if (prev == null) {
            smoothedFace = RectF(raw)
            return raw
        }
        val dx = kotlin.math.abs(raw.centerX() - prev.centerX())
        val dy = kotlin.math.abs(raw.centerY() - prev.centerY())
        val prevArea = (prev.width() * prev.height()).coerceAtLeast(1e-6f)
        val areaRatio = (raw.width() * raw.height()) / prevArea
        if (dx > 0.12f || dy > 0.12f || areaRatio > 2.0f || areaRatio < 0.5f) {
            prev.set(raw)
            return raw
        }
        val k = 0.5f
        prev.set(
            prev.left + (raw.left - prev.left) * k,
            prev.top + (raw.top - prev.top) * k,
            prev.right + (raw.right - prev.right) * k,
            prev.bottom + (raw.bottom - prev.bottom) * k
        )
        return RectF(prev)
    }

    private fun holdEye(now: Boolean): Boolean {
        closedHold = if (now) 2 else (closedHold - 1).coerceAtLeast(0)
        return now || closedHold > 0
    }

    private fun holdOneEye(now: Boolean): Boolean {
        oneEyeHold = if (now) 2 else (oneEyeHold - 1).coerceAtLeast(0)
        return now || oneEyeHold > 0
    }

    private fun clampBox(frame: Mat, box: Rect): Rect {
        val x = box.x.coerceIn(0, frame.cols() - 1)
        val y = box.y.coerceIn(0, frame.rows() - 1)
        val right = (box.x + box.width).coerceAtMost(frame.cols())
        val bottom = (box.y + box.height).coerceAtMost(frame.rows())
        return Rect(x, y, (right - x).coerceAtLeast(1), (bottom - y).coerceAtLeast(1))
    }

    /** 记下最近一次成功检测的框，以及这块区域的亮度/对比度基线。 */
    private fun rememberFace(frame: Mat, best: FloatArray) {
        val box = clampBox(frame, Rect(best[0].toInt(), best[1].toInt(), best[2].toInt(), best[3].toInt()))
        heldBox = box
        heldFrames = 0
        readStats(frame, box)?.let { (mean, std) ->
            heldMean = mean
            heldStd = std
        }
    }

    /**
     * 缓存框是否还对得上：亮度或对比度大幅变化说明人已经走了/画面换了，
     * 这时继续在旧位置判睁眼只会给出错误提示。
     */
    private fun stillSameFace(frame: Mat, box: Rect): Boolean {
        val safe = clampBox(frame, box)
        val (mean, std) = readStats(frame, safe) ?: return false
        if (kotlin.math.abs(mean - heldMean) > 28.0) return false
        if (heldStd > 1.0 && std < heldStd * 0.55) return false
        return true
    }

    private fun readStats(frame: Mat, r: Rect): Pair<Double, Double>? {
        if (!inside(frame, r)) return null
        val roi = frame.submat(r)
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        return try {
            Core.meanStdDev(roi, mean, std)
            mean.toArray()[0] to std.toArray()[0]
        } catch (t: Throwable) {
            null
        } finally {
            std.release()
            mean.release()
            roi.release()
        }
    }

    /**
     * 人脸检测暂时失败时，用缓存框继续判睁眼：闭眼时级联同样数不到眼睛，
     * 但至少不会因为「没人脸」而把整条检查链丢掉。
     */
    private fun analyzeHeld(frame: Mat, box: Rect): Analysis {
        val safeBox = clampBox(frame, box)
        val band = Rect(safeBox.x, safeBox.y, safeBox.width, (safeBox.height * 0.6f).toInt().coerceAtLeast(8))
        val eyeDistanceEstimate = box.width * 0.32f
        val eyes = if (eyeDistanceEstimate >= 26f && inside(frame, band)) countEyes(frame, safeBox) else -1
        val closed = holdEye(eyes == 0)
        val oneEye = !closed && holdOneEye(eyes == 1)
        val warnings = buildList {
            if (closed) add("闭眼或眼部遮挡，请睁眼并保持正脸")
            else if (oneEye) add("单侧眼睛被遮挡，请检查")
        }
        Log.i(TAG, "held eyes=" + eyes + " frames=" + heldFrames + " closed=" + closed)
        return Analysis(
            RectF(
                box.x / frame.cols().toFloat(), box.y / frame.rows().toFloat(),
                (box.x + box.width) / frame.cols().toFloat(),
                (box.y + box.height) / frame.rows().toFloat()
            ),
            eyes,
            warnings
        )
    }

    /** 没有脸时按「未命中」喂一帧，让窗口自然衰减，而不是整段清空。 */
    private fun decayLatches() {
        smoothedFace = null
        closedHold = 0
        oneEyeHold = 0
        occlusionLatch.update(false)
        glareLatch.update(false)
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
