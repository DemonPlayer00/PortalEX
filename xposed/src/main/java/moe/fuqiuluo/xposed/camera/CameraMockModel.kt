package moe.fuqiuluo.xposed.camera

/**
 * 摄像头模拟的**领域模型**（纯数据，无 Android 依赖）。
 *
 * 为什么放在 `:xposed`：这份数据要被两侧同时理解 ——
 *  · **app 侧**（PortalEX 自己的进程）负责录制/导入/编辑/落盘，是唯一写者；
 *  · **模块侧**（被注入的目标应用进程）只读清单，自己算"该播哪一段"。
 * 两侧若各写一份结构，字段一改就会出现"app 存了但模块读不到"的静默失效 ——
 * 与 [moe.fuqiuluo.xposed.utils.PortalProtocol] 同一个理由：**只留一处事实来源**。
 *
 * 坐标系约定：`lat/lon` 一律 **wgs84**（与 [moe.fuqiuluo.portalex.ui.mock.HistoricalRoute]
 * 的路线点一致）。地图点选的 gcj02 由 app 侧在入库前转换，模块侧不做任何坐标假设。
 */
data class CameraVideo(
    /** 稳定 id（app 侧生成 uuid）；模块侧只用它向 provider 要文件 fd */
    val id: String,
    /** 展示名（默认取文件名，可改） */
    val name: String,
    /** 库目录内的文件名（app 私有目录；模块侧不直接读文件，走 provider） */
    val file: String,
    /** 时长（毫秒）。0 = 未知（探取失败）：起播偏移按 0 处理，循环交给解码器 EOS */
    val durationMs: Long,
    /** 标签：预设（晴/阴/白天/黑夜）+ 任意自定义，见 [CameraTags] */
    val tags: List<String>,
    val addedAt: Long,
    /** 录制开始的墙钟时间（毫秒）；0 = 导入/未知 */
    val recordedAt: Long = 0L,
    /** 录制期间记录的位置轨迹（wgs84，按时间排序）；导入视频为空 */
    val track: List<CameraVideoTrackPoint> = emptyList()
)

/** 录制期间的一个路径采样点；tMs 是相对录制开始的毫秒偏移。 */
data class CameraVideoTrackPoint(
    val tMs: Long,
    val lat: Double,
    val lon: Double
)

data class CameraVideoPoint(
    /** 稳定 id */
    val id: String,
    val name: String,
    /** wgs84 */
    val lat: Double,
    val lon: Double,
    /** 同一坐标可挂多个视频 */
    val videos: List<CameraVideo>
)

/**
 * 下发给模块的**清单**：开关 + 标签筛选 + 全部视频点 + 目标应用。
 *
 * 模块侧每次要"取一段视频"时读它（带 TTL 缓存），因此这里必须**自洽可离线**：
 * 不含任何需要回调 app 才能补齐的字段。
 */
data class CameraMockManifest(
    /** 摄像头模拟总开关。false = 模块侧一律不拦截（hook 即使装上也不生效） */
    val enabled: Boolean,
    /** 标签筛选：**全含**语义（见 [CameraMockSelector.matches]）。空 = 不筛选 */
    val tagFilter: List<String>,
    val points: List<CameraVideoPoint>,
    /** 允许拦截的应用包名；空 = 不限制（凡被注入且开关打开的进程都生效） */
    val targets: List<String>,
    val updatedAt: Long
) {
    companion object {
        /** 关闭态清单：模块侧拉不到配置时的兜底（fail-inert：不拦截） */
        val DISABLED = CameraMockManifest(
            enabled = false,
            tagFilter = emptyList(),
            points = emptyList(),
            targets = emptyList(),
            updatedAt = 0L
        )
    }
}

/**
 * 一次选片结果：**在哪播、播哪个文件、从第几毫秒起**。
 *
 * `startMs` 在选取时确定一次（随机整秒），之后整段播放期间不变 ——
 * 循环回到开头是"播到末尾就回文件头继续"，不是重新掷随机数（否则每次循环都跳帧）。
 */
data class CameraMockSelection(
    val pointId: String,
    val pointName: String,
    val videoId: String,
    val videoName: String,
    val fileName: String,
    val durationMs: Long,
    val startMs: Long,
    /** 选取时该点距"当前表象位置"的球面距离（米），仅用于诊断展示 */
    val distanceM: Double
)

/** 预设标签。用户可在此基础上自由添加自定义标签（不做白名单限制）。 */
object CameraTags {
    const val SUNNY = "晴"
    const val CLOUDY = "阴"
    const val RAIN = "雨"
    const val SNOW = "雪"
    const val WIND = "风"
    const val FOG = "雾"
    const val DAY = "白天"
    const val NIGHT = "黑夜"

    val PRESETS = listOf(SUNNY, CLOUDY, RAIN, SNOW, WIND, FOG, DAY, NIGHT)

    /** 单个标签的最大长度：防手滑粘贴整段文本把清单撑爆（跨进程 Bundle 有 1MB 上限） */
    const val MAX_LEN = 24

    /** 单个视频的标签数上限 */
    const val MAX_COUNT = 16

    /** 归一化 + 去重 + 截断（保序）。空白标签丢弃，比较用大小写不敏感。 */
    /** 轨迹点上限：约 2 小时 × 1 秒；防止异常录制把清单撑爆 */
    const val MAX_TRACK_POINTS = 7200

    fun sanitizeTrack(points: Collection<CameraVideoTrackPoint>?): List<CameraVideoTrackPoint> {
        if (points == null) return emptyList()
        return points.asSequence()
            .filter { it.tMs >= 0 && it.lat.isFinite() && it.lon.isFinite() &&
                      it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            .sortedBy { it.tMs }
            .take(MAX_TRACK_POINTS)
            .toList()
    }

    fun sanitize(tags: Collection<String>?): List<String> {
        if (tags == null) return emptyList()
        val out = ArrayList<String>(tags.size)
        for (raw in tags) {
            val t = raw.trim().take(MAX_LEN)
            if (t.isEmpty()) continue
            if (out.any { it.equals(t, ignoreCase = true) }) continue
            out.add(t)
            if (out.size >= MAX_COUNT) break
        }
        return out
    }
}
