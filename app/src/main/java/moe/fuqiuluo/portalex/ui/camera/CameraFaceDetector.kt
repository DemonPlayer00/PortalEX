package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.objdetect.CascadeClassifier
import java.io.File

/** OpenCV Haar 人脸检测 + 轻量有效性检查。 */
object CameraFaceDetector {
    private const val TAG = "CameraFace"
    @Volatile private var cascade: CascadeClassifier? = null
    @Volatile private var eyeCascade: CascadeClassifier? = null
    @Volatile private var initTried = false

    data class Analysis(
        val face: RectF?,
        val eyes: Int,
        val warnings: List<String>,
    )

    @Synchronized
    private fun ensureInit(context: Context): Boolean {
        if (initTried) return cascade != null
        initTried = true
        runCatching {
            if (!OpenCVLoader.initLocal()) {
                Log.e(TAG, "OpenCVLoader.initLocal failed")
                return false
            }
            cascade = loadCascade(context, "haarcascade_frontalface_default.xml")?.takeUnless { it.empty() }
            eyeCascade = loadCascade(context, "haarcascade_eye.xml")?.takeUnless { it.empty() }
            Log.i(TAG, "cascades face=${cascade != null} eyes=${eyeCascade != null}")
        }.onFailure { Log.e(TAG, "init failed", it) }
        return cascade != null
    }

    private fun loadCascade(context: Context, name: String): CascadeClassifier? {
        val file = File(context.filesDir, name)
        if (!file.exists()) {
            context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return CascadeClassifier(file.absolutePath)
    }

    fun detect(context: Context, image: ImageProxy): Analysis {
        if (!ensureInit(context)) return Analysis(null, 0, listOf("人脸检测未就绪"))
        val mat = yuvToGray(image) ?: return Analysis(null, 0, listOf("无法读取画面"))
        val rotated = when (image.imageInfo.rotationDegrees) {
            90 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_90_CLOCKWISE) }
            180 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_180) }
            270 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
            else -> mat
        }
        val faces = MatOfRect()
        return try {
            cascade?.detectMultiScale(rotated, faces, 1.1, 3, 0, Size(60.0, 60.0))
            val list = faces.toArray()
            if (list.isEmpty()) {
                return Analysis(null, 0, listOf("未检测到人脸，请正对镜头"))
            }
            val r = list.maxByOrNull { it.width.toLong() * it.height }!!
            val rw = rotated.cols().toFloat()
            val rh = rotated.rows().toFloat()
            val face = RectF(r.x / rw, r.y / rh, (r.x + r.width) / rw, (r.y + r.height) / rh)
            val eyes = countEyes(rotated, r)
            val warnings = buildList {
                if (r.x < rw * 0.02f || r.y < rh * 0.02f ||
                    r.x + r.width > rw * 0.98f || r.y + r.height > rh * 0.98f
                ) add("头部可能超出画面，请退后一点")
                if (eyes == 0) add("闭眼或眼部遮挡，请睁眼并保持正脸")
                else if (eyes == 1) add("单侧眼睛被遮挡，请检查")
                if (eyes == 0 && lowerFaceMean(rotated, r) < 45.0) {
                    add("下半脸可能被遮挡，请检查口罩/手部")
                }
            }
            Analysis(face, eyes, warnings)
        } catch (t: Throwable) {
            Log.w(TAG, "detect failed", t)
            Analysis(null, 0, listOf("人脸分析失败"))
        } finally {
            faces.release()
            if (rotated !== mat) rotated.release()
            mat.release()
        }
    }

    private fun yuvToGray(image: ImageProxy): Mat? = runCatching {
        val y = image.planes[0]
        val w = image.width
        val h = image.height
        val mat = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
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

    private fun lowerFaceMean(rotated: Mat, face: Rect): Double {
        val h = (face.height * 0.45).toInt().coerceAtLeast(1)
        val x = (face.x + face.width * 0.12).toInt().coerceAtLeast(0)
        val y = (face.y + face.height * 0.55).toInt().coerceAtLeast(0)
        val w = (face.width * 0.76).toInt().coerceAtLeast(1)
        if (x + w > rotated.cols() || y + h > rotated.rows()) return 255.0
        val roi = rotated.submat(Rect(x, y, w, h))
        return try { Core.mean(roi).`val`[0] } finally { roi.release() }
    }

    private fun countEyes(rotated: Mat, face: Rect): Int {
        val eye = eyeCascade ?: return -1
        val upper = Rect(
            face.x + (face.width * 0.08).toInt(),
            face.y + (face.height * 0.08).toInt(),
            face.width - (face.width * 0.16).toInt(),
            (face.height * 0.52).toInt().coerceAtLeast(1),
        )
        if (upper.x < 0 || upper.y < 0 || upper.x + upper.width > rotated.cols() ||
            upper.y + upper.height > rotated.rows()
        ) return -1
        val roi = rotated.submat(upper)
        val found = MatOfRect()
        return try {
            eye.detectMultiScale(roi, found, 1.1, 4, 0, Size(18.0, 18.0))
            found.toArray().size.coerceAtMost(2)
        } finally {
            found.release()
            roi.release()
        }
    }
}
