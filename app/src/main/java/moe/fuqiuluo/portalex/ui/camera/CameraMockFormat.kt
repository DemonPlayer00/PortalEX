package moe.fuqiuluo.portalex.ui.camera

import moe.fuqiuluo.xposed.camera.CameraVideoPoint

/** 摄像头模拟页共用的展示格式（时长、坐标、标签行），避免每处各写一遍 */ 
object CameraMockFormat {

    /** 毫秒 → `m:ss`；未知（0）显示 `未知时长` */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "未知时长"
        val totalSec = ms / 1000L
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** 坐标行：5 位小数 ≈ 1 米量级，够用于"这是我记的那个点"的辨认 */
    fun coord(lat: Double, lon: Double): String = "%.5f, %.5f".format(lat, lon)

    /** 标签行；无标签时给出明确文案（而不是空白，空白会让人以为界面坏了） */
    fun tags(tags: List<String>): String = if (tags.isEmpty()) "无标签" else tags.joinToString(" · ")

    fun pointSummary(point: CameraVideoPoint): String =
        "${coord(point.lat, point.lon)} · ${point.videos.size} 段视频"
}
