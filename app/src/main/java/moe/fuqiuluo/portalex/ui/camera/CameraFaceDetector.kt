package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.objdetect.CascadeClassifier
import java.io.File

/** OpenCV Haar 人脸检测：输入 CameraX YUV 帧，返回 0..1 归一化的人脸框。 */
object CameraFaceDetector {
    private const val TAG = "CameraFace"
    @Volatile private var cascade: CascadeClassifier? = null
    @Volatile private var initTried = false

    @Synchronized
    private fun ensureInit(context: Context): CascadeClassifier? {
        cascade?.let { return it }
        if (initTried) return cascade
        initTried = true
        runCatching {
            if (!OpenCVLoader.initLocal()) {
                Log.e(TAG, "OpenCVLoader.initLocal failed")
                return null
            }
            val file = File(context.filesDir, "haarcascade_frontalface_default.xml")
            if (!file.exists()) {
                context.assets.open("haarcascade_frontalface_default.xml").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
            cascade = CascadeClassifier(file.absolutePath).also {
                if (it.empty()) {
                    Log.e(TAG, "cascade empty")
                    cascade = null
                } else {
                    Log.i(TAG, "cascade loaded")
                }
            }
        }.onFailure { Log.e(TAG, "init failed", it) }
        return cascade
    }

    fun detect(context: Context, image: ImageProxy): RectF? {
        val c = ensureInit(context) ?: return null
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
        val rotated = when (image.imageInfo.rotationDegrees) {
            90 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_90_CLOCKWISE) }
            180 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_180) }
            270 -> Mat().also { Core.rotate(mat, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
            else -> mat
        }
        val faces = MatOfRect()
        return try {
            c.detectMultiScale(rotated, faces, 1.1, 3, 0, Size(60.0, 60.0))
            val list = faces.toArray()
            if (list.isEmpty()) return null
            val r = list.maxByOrNull { it.width.toLong() * it.height } ?: return null
            val rw = rotated.cols().toFloat()
            val rh = rotated.rows().toFloat()
            RectF(r.x / rw, r.y / rh, (r.x + r.width) / rw, (r.y + r.height) / rh)
        } catch (t: Throwable) {
            Log.w(TAG, "detect failed", t)
            null
        } finally {
            faces.release()
            if (rotated !== mat) rotated.release()
            mat.release()
        }
    }
}
