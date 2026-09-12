package moe.fuqiuluo.xposed.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 摄像头活动事件的种类（控制面只关心"谁在用相机"，与画面无关） */
enum class CameraActivityKind(val label: String) {
    /** 一次相机会话开始（AppOps `startOperation`） */
    OPEN("开"),

    /** 相机会话结束（AppOps `finishOperation`） */
    CLOSE("关"),

    /** 只是被"记了一笔"（AppOps `noteOperation`，可能按请求反复来）——去抖后偶尔出现 */
    ACTIVE("用"),

    /** cameraserver 通知的状态翻转（`ICameraServiceProxy`）：拿到的是相机与朝向，不带 uid */
    STATE("态"),
}

/**
 * 一条摄像头活动记录。
 *
 * `uid < 0` 表示这条来自 cameraserver 的状态通知（那条路径不带调用者身份），
 * 此时 [packageName] 为空、[detail] 里带相机 id 与状态。
 */
data class CameraActivity(
    val timeMs: Long,
    val uid: Int,
    val packageName: String?,
    val kind: CameraActivityKind,
    val facing: String?,
    val detail: String? = null
)

/**
 * **摄像头控制面的纯逻辑核**（零 Android 依赖，可在 JVM 上直接测）。
 *
 * 为什么要有这一层：`system_server` 里的钩子要处理的是 **AppOps 的高频回调**——
 * CameraService 会按捕获请求反复"记账"（可能逐帧），如果每来一次就写一条日志，
 * 既刷爆观测界面，也让热路径变重。这里把"什么才算一次值得记录的事件"抽成
 * 纯函数：活跃期内的重复记账只算一次（[repeatWindowMs] 去抖），进出各记一条。
 *
 * 数据来源两个（互补）：
 *  · [onAppOp] —— AppOps 的 `startOperation`/`finishOperation`/`noteOperation`：
 *    **带 uid 与包名**，回答"哪个应用在何时开了相机"；
 *  · [onCameraState] —— cameraserver 的 `ICameraServiceProxy` 通知：
 *    **带相机 id 与朝向**，回答"开的是前置还是后置"。
 * 两者都不在视频通路上——system_server 里拿不到画面，这条链只负责"知道"。
 */
class CameraWatchState(
    private val capacity: Int = 12,
    private val repeatWindowMs: Long = 2_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private data class Active(val packageName: String?, val since: Long, var lastNote: Long)

    private val events = ArrayDeque<CameraActivity>()
    private val active = HashMap<Int, Active>()

    /** 最近一次从 cameraserver 侧看到的相机/朝向（AppOps 那条路拿不到朝向） */
    @Volatile
    var lastFacing: String? = null
        private set

    @Volatile
    var lastCameraId: String? = null
        private set

    var openCount: Long = 0L
        private set
    var closeCount: Long = 0L
        private set

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 当前正在占用相机的应用（uid → 摘要），供 UI 显示"谁在用" */
    fun activeClients(): List<String> = synchronized(this) {
        val now = clock()
        active.entries
            .sortedBy { it.key }
            .map { (uid, info) -> "${info.packageName ?: "uid$uid"}(${uid}) 已 ${(now - info.since) / 1000}s" }
    }

    /**
     * AppOps 侧回调。[starting] 区分 `startOperation`（相机会话开始）与
     * `finishOperation`（结束）；[noteOnly] 为真表示这是 `noteOperation`（去抖处理）。
     * @return 是否真的记了一条（false = 被去抖/被忽略，调用方不必做任何事）
     */
    fun onAppOp(
        uid: Int,
        packageName: String?,
        starting: Boolean,
        noteOnly: Boolean = false,
        facing: String? = null,
        timeMs: Long = clock()
    ): Boolean = synchronized(this) {
        val pkg = packageName ?: active[uid]?.packageName
        if (noteOnly) {
            val current = active[uid]
            if (current != null && timeMs - current.lastNote < repeatWindowMs) {
                // 活跃期内的重复记账：只更新水位，不产生事件（这就是"逐帧回调不刷屏"的实现）
                current.lastNote = timeMs
                return false
            }
            active[uid] = Active(pkg, active[uid]?.since ?: timeMs, timeMs)
            push(CameraActivity(timeMs, uid, pkg, CameraActivityKind.ACTIVE, facing ?: lastFacing))
            return true
        }
        return if (starting) {
            val existed = active.containsKey(uid)
            active[uid] = Active(pkg, active[uid]?.since ?: timeMs, timeMs)
            // 同一 uid 重复 start（前台切换、重开预览）只记一次，避免把列表刷满
            if (existed && events.firstOrNull()?.let { it.uid == uid && it.kind == CameraActivityKind.OPEN } == true) {
                return false
            }
            openCount++
            push(CameraActivity(timeMs, uid, pkg, CameraActivityKind.OPEN, facing ?: lastFacing))
            true
        } else {
            val info = active.remove(uid)
            if (info == null && events.none { it.uid == uid }) return false
            closeCount++
            push(CameraActivity(timeMs, uid, pkg ?: info?.packageName, CameraActivityKind.CLOSE, facing ?: lastFacing))
            true
        }
    }

    /**
     * cameraserver 的状态通知（`notifyCameraState(cameraId, facing, newState)`）。
     * 状态值沿用 AOSP：0=关闭 1=打开 2=活动中 3=空闲。
     */
    fun onCameraState(
        cameraId: String?,
        facing: Int,
        state: Int,
        timeMs: Long = clock()
    ): Boolean = synchronized(this) {
        val facingName = facingName(facing)
        lastFacing = facingName
        lastCameraId = cameraId
        push(
            CameraActivity(
                timeMs = timeMs,
                uid = -1,
                packageName = null,
                kind = CameraActivityKind.STATE,
                facing = facingName,
                detail = "camera=$cameraId ${stateName(state)}"
            )
        )
        true
    }

    private fun push(event: CameraActivity) {
        events.addFirst(event)
        while (events.size > capacity) events.removeLast()
    }

    fun recent(count: Int = 6): List<CameraActivity> = synchronized(this) {
        events.take(count)
    }

    fun clear() = synchronized(this) {
        events.clear()
        active.clear()
        openCount = 0
        closeCount = 0
    }

    /**
     * 回读文本（命令通道回传给 app，Test 页/摄像头模拟页直接显示）。
     * 顺序：摘要 → 当前占用者 → 最近 N 条（新→旧）。
     */
    fun dump(recentCount: Int = 6): String = synchronized(this) {
        val head = buildString {
            append("计数=开 ").append(openCount).append(" / 关 ").append(closeCount)
            append("；朝向=").append(lastFacing ?: "未知")
            if (lastCameraId != null) append("（").append(lastCameraId).append("）")
        }
        val clients = activeClients()
        val body = if (events.isEmpty()) {
            "（无活动）"
        } else {
            events.take(recentCount).joinToString("\n") { e ->
                val who = when {
                    e.uid < 0 -> e.detail ?: "cameraserver"
                    else -> "${e.packageName ?: "uid${e.uid}"}(${e.uid})"
                }
                "${timeFormat.format(Date(e.timeMs))} ${e.kind.label} $who${e.facing?.let { " $it" } ?: ""}"
            }
        }
        buildString {
            append(head).append('\n')
            append("占用中=").append(if (clients.isEmpty()) "无" else clients.joinToString("；")).append('\n')
            append(body)
        }
    }

    companion object {
        /** AOSP `CameraMetadata.LENS_FACING_*` */
        fun facingName(facing: Int): String = when (facing) {
            0 -> "后置"
            1 -> "前置"
            2 -> "外接"
            else -> "未知"
        }

        /** AOSP `ICameraServiceProxy` 的相机状态值 */
        fun stateName(state: Int): String = when (state) {
            0 -> "关闭"
            1 -> "打开"
            2 -> "活动中"
            3 -> "空闲"
            else -> "状态$state"
        }
    }
}
