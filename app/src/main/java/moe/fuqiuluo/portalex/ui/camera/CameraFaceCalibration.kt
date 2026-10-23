package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.graphics.RectF

/** 人脸框标定结果：按左右对称原则取检测框的中位中心与中位尺寸。 */
object CameraFaceCalibration {
    private const val PREFS = "portal"
    private const val KEY = "camera_face_box_v1"

    fun load(context: Context): RectF? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val a = s.split(',').map { it.toFloat() }
            if (a.size == 4) RectF(a[0], a[1], a[2], a[3]) else null
        }.getOrNull()
    }

    fun save(context: Context, r: RectF) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, "${r.left},${r.top},${r.right},${r.bottom}").apply()
    }

    fun calibrate(samples: List<RectF>): RectF? {
        if (samples.size < 3) return null
        val cx = samples.map { (it.left + it.right) / 2f }.sorted()[samples.size / 2]
        val cy = samples.map { (it.top + it.bottom) / 2f }.sorted()[samples.size / 2]
        val w = samples.map { it.width() }.sorted()[samples.size / 2]
        val h = samples.map { it.height() }.sorted()[samples.size / 2]
        // 左右对称：框围绕中位中心展开，不单独偏移左右边界。
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            .clampToUnit()
    }

    private fun RectF.clampToUnit(): RectF = RectF(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
    )
}
