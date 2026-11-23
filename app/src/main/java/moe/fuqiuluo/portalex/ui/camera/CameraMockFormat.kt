package moe.fuqiuluo.portalex.ui.camera

import moe.fuqiuluo.xposed.camera.CameraVideo
import moe.fuqiuluo.xposed.camera.CameraVideoPoint
import moe.fuqiuluo.xposed.camera.isStill

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
        "${coord(point.lat, point.lon)} · ${mediaCount(point)}"

    /** 素材数量：静止图片与视频各算一条（列表标题/统计用） */
    fun mediaCount(point: CameraVideoPoint): String {
        val stills = point.videos.count { it.isStill }
        val videos = point.videos.size - stills
        return when {
            stills > 0 && videos > 0 -> "$videos 段视频 + $stills 张图片"
            stills > 0 -> "$stills 张图片"
            else -> "$videos 段视频"
        }
    }

    /** 单条素材的时长/类型行 */
    fun mediaKind(video: CameraVideo): String = if (video.isStill) "单帧图片" else "时长 " + duration(video.durationMs)

    /** 静止图片绑定的那个点（它就是这条素材唯一的轨迹点） */
    fun boundPoint(video: CameraVideo): Pair<Double, Double>? =
        video.track.firstOrNull()?.let { it.lat to it.lon }
}
