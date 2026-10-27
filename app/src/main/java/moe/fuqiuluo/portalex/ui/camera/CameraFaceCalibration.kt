package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.graphics.RectF

/** 人脸框标定结果：按左右对称原则取检测框的中位中心与中位尺寸。 */
object CameraFaceCalibration {
    private const val PREFS = "portal"
    private const val KEY = "camera_face_box_v1"
    private const val KEY_MANUAL = "camera_face_box_manual_v1"

    fun load(context: Context): RectF? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val a = s.split(',').map { it.toFloat() }
            if (a.size == 4) RectF(a[0], a[1], a[2], a[3]) else null
        }.getOrNull()
    }

    fun save(context: Context, r: RectF, manual: Boolean = false) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, r.left.toString() + "," + r.top + "," + r.right + "," + r.bottom)
            .putBoolean(KEY_MANUAL, manual)
            .apply()
    }

    /**
     * 这个框是不是手动拖出来的。
     * 手动框是"固定取景参考"，不跟随实时检测 —— 叠加层据此隐藏实时框并改用黄色。
     */
    fun isManual(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MANUAL, false)

    /** 标准框允许的偏移比例：实时框略偏一点不算问题，超出容差才提示。 */
    private const val MIN_AREA_RATIO = 0.38f
    private const val MAX_AREA_RATIO = 2.4f
    private const val MAX_CENTER_SHIFT = 0.20f

    /** 中心偏离超过标准框尺寸的这个比例，才认为头已经离开取景范围。 */
    private const val HEAD_OUT_SHIFT = 0.35f

    /**
     * 用校准阶段保存的标准人脸框，对录像阶段做校对。
     *
     * 「头部超出画面」以**标准框**为基准：实时框的中心相对标准框中心的偏移，按标准框尺寸归一化，
     * 小于 20% 完全不管，20%~35% 提示回到位置，超过 35% 才认为头要出画面。
     * 这样实时框轻微偏离标准框不会误报。没有校准数据时退回画面边缘判定。
     * 至多给一条提示，按严重程度取最先命中的那个。
     */
    fun framingWarnings(context: Context, actual: RectF?): List<String> {
        if (actual == null) return emptyList()
        val expected = load(context) ?: return frameEdgeWarnings(actual)
        val expectedArea = expected.width() * expected.height()
        val actualArea = actual.width() * actual.height()
        if (expectedArea > 0f) {
            val ratio = actualArea / expectedArea
            if (ratio < MIN_AREA_RATIO) return listOf("离镜头太远，请靠近一些")
            if (ratio > MAX_AREA_RATIO) return listOf("离镜头太近，请退后一些")
        }
        // 位置偏差按标准框尺寸归一化：只认「头离开了标准位置」，不因脸稍微变大变小而误判。
        val dx = kotlin.math.abs(actual.centerX() - expected.centerX()) / expected.width()
        val dy = kotlin.math.abs(actual.centerY() - expected.centerY()) / expected.height()
        if (dx > HEAD_OUT_SHIFT || dy > HEAD_OUT_SHIFT) return listOf("头部可能超出画面，请退后一点")
        if (dx > MAX_CENTER_SHIFT || dy > MAX_CENTER_SHIFT) return listOf("请回到校准时的位置")
        return emptyList()
    }

    /** 未校准时的兜底：人脸框贴到画面边缘才算越界。 */
    private fun frameEdgeWarnings(actual: RectF): List<String> {
        if (actual.left < 0.02f || actual.top < 0.02f ||
            actual.right > 0.98f || actual.bottom > 0.98f
        ) {
            return listOf("头部可能超出画面，请退后一点")
        }
        return emptyList()
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
