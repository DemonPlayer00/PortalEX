package moe.fuqiuluo.xposed.camera

/**
 * 摄像头模拟的**跨进程协议**：provider 授权名、方法名、Bundle 键、偏好键。
 *
 * 通道选型（为什么不走已有的 `sendExtraCommand("portal", …)` 命令通道）：
 *  · 那条通道被刻意限死在 **PortalEX 自己的 uid + SYSTEM_UID** 上，普通应用进程
 *    （= 摄像头 hook 所在的进程）拿不到 key，也就读不到配置、更取不到视频文件；
 *  · 而这里要跨的正是"app 进程 → 任意被注入的目标应用进程"，且要**传文件描述符**
 *    （视频本体在我们自己的私有目录里，对方既没有路径权限也没有存储权限）。
 *  · `ContentProvider` 一次解决两件事：结构化配置走 [Method]，视频走 [Video] 的 fd。
 *
 * 安全边界：provider 在 [moe.fuqiuluo.portalex.service.CameraMockProvider] 里
 * 用 `Binder.getCallingUid()` 做门禁 —— 只有"开关打开 + 调用方在目标应用清单里
 * （清单为空时放行任意调用方，便于首装自测）"才应答；不设 `android:permission`，
 * 因为目标应用不可能持有我们的签名权限。
 */
object CameraMockProtocol {

    /** provider 授权名（AndroidManifest 必须一致） */
    const val AUTHORITY = "moe.fuqiuluo.portalex.cameramock"

    /**
     * 本应用自己的包名。
     *
     * **用途**：摄像头模拟的模块侧钩子**绝不能装进本应用自己的进程**。
     * 实测踩过：本应用在 LSPosed 作用域内，钩子会先把应用的输出面换成排水面、
     * 再去起 GL 播放器；GL 一旦失败（本机就是 shader 编译失败），应用自己的相机就**一张帧都收不到** ——
     * 表现是"探针/录制页什么都抓不到"，而日志里只有一句 GL 警告。
     * 而且对本应用做内容替换本身也没有意义：调试探针要读的正是**真实相机 + HAL 注入**的结果。
     */
    const val OWN_PACKAGE = "moe.fuqiuluo.portalex"

    /** 清单 URI：`content://<AUTHORITY>/manifest` */
    const val PATH_MANIFEST = "manifest"

    /** 视频 URI 前缀：`content://<AUTHORITY>/video/<视频id>`，配合 `openFile` 取 fd */
    const val PATH_VIDEO_PREFIX = "video/"

    object Method {
        /** app → 模块：拉取清单（回包键 [Key.MANIFEST_JSON]） */
        const val MANIFEST = "manifest"

        /** app → 模块：当前表象位置（回包键 [Key.HAS_POSITION]/[Key.LAT]/[Key.LON]） */
        const val POSITION = "position"

        /** 模块 → app：上报本次拦截结果（谁、选中了哪段、从第几毫秒起播），供 Test 页观测 */
        const val REPORT = "report"
    }

    object Key {
        const val MANIFEST_JSON = "manifest_json"
        const val HAS_POSITION = "has_position"
        const val LAT = "lat"
        const val LON = "lon"
        const val PACKAGE = "package"
        const val VIDEO_ID = "video_id"
        const val VIDEO_NAME = "video_name"
        const val POINT_NAME = "point_name"
        const val START_MS = "start_ms"
        const val DISTANCE_M = "distance_m"
        const val STATUS = "status"
    }

    /** 偏好键（app 侧 `portal` 偏好文件；模块侧不经 XSharedPreferences 读，仅作记录） */
    object Pref {
        const val ENABLED = "cameraMockEnabled"
        const val TAG_FILTER = "cameraMockTagFilter"
        const val TARGETS = "cameraMockTargets"
        const val LIBRARY = "cameraMockLibrary"
    }
}
