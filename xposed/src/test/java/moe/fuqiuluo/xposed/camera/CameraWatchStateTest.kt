package moe.fuqiuluo.xposed.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摄像头控制面纯逻辑的回归测试。
 *
 * 重点在两件事：
 *  1. **去抖**：AppOps 的 `noteOperation` 会被 CameraService 按请求反复触发（可能逐帧），
 *     活跃期内的重复必须只算一次——否则观测界面会被刷爆、热路径也被我们拖重；
 *  2. **配对**：开/关要成对，重复 start（前台切换、重开预览）不能记成两次开。
 */
class CameraWatchStateTest {

    private val app = "com.example.camera"
    private val uid = 10_196

    @Test
    fun `开与关成对记录`() {
        // 注入时钟：activeClients() 里的"已 Ns"要可断言（生产里取 System.currentTimeMillis）
        val s = CameraWatchState(clock = { 1_000 })
        assertTrue(s.onAppOp(uid, app, starting = true, timeMs = 1_000))
        assertEquals(listOf("$app($uid) 已 0s"), s.activeClients())
        assertEquals(1L, s.openCount)

        assertTrue(s.onAppOp(uid, app, starting = false, timeMs = 5_000))
        assertEquals(1L, s.closeCount)
        assertTrue(s.activeClients().isEmpty())
        assertEquals(
            listOf(CameraActivityKind.CLOSE, CameraActivityKind.OPEN),
            s.recent().map { it.kind }
        )
    }

    @Test
    fun `活跃期内重复记账只记一次`() {
        val s = CameraWatchState(repeatWindowMs = 2_000)
        s.onAppOp(uid, app, starting = true, timeMs = 0)
        // 逐帧回调：1 秒内 50 次。startOperation 已经把该 uid 置为活跃并记过 OPEN，
        // 因此这些重复记账**一条都不该产生**
        var recorded = 0
        for (t in 1..50) if (s.onAppOp(uid, app, starting = false, noteOnly = true, timeMs = t * 20L)) recorded++
        assertEquals("活跃期内的重复记账不该产生事件", 0, recorded)

        // 越过窗口：再记一条，说明"还在用"而不是卡死（此时全天只有这 1 条 ACTIVE）
        assertTrue(s.onAppOp(uid, app, starting = false, noteOnly = true, timeMs = 5_000))
        assertEquals(1, s.recent().count { it.kind == CameraActivityKind.ACTIVE })
    }

    @Test
    fun `只看到记账（没有 start）时第一条会记下来`() {
        val s = CameraWatchState(repeatWindowMs = 2_000)
        assertTrue(s.onAppOp(uid, app, starting = false, noteOnly = true, timeMs = 0))
        assertEquals(1, s.recent().count { it.kind == CameraActivityKind.ACTIVE })
        assertFalse(s.onAppOp(uid, app, starting = false, noteOnly = true, timeMs = 100))
    }

    @Test
    fun `重复 start 不记成两次开`() {
        val s = CameraWatchState()
        assertTrue(s.onAppOp(uid, app, starting = true, timeMs = 0))
        assertFalse(s.onAppOp(uid, app, starting = true, timeMs = 500))
        assertEquals(1L, s.openCount)
        assertEquals(1, s.recent().count { it.kind == CameraActivityKind.OPEN })
    }

    @Test
    fun `没见过 finish 不产生事件`() {
        val s = CameraWatchState()
        assertFalse(s.onAppOp(uid, app, starting = false, timeMs = 0))
        assertTrue(s.recent().isEmpty())
        assertEquals(0L, s.closeCount)
    }

    @Test
    fun `cameraserver 状态通知带朝向且不带 uid`() {
        val s = CameraWatchState()
        assertTrue(s.onCameraState("0", 1, 2, timeMs = 3_000))
        val e = s.recent().single()
        assertEquals(CameraActivityKind.STATE, e.kind)
        assertEquals(-1, e.uid)
        assertEquals("前置", e.facing)
        assertTrue(e.detail!!.contains("camera=0"))
        assertTrue(e.detail!!.contains("活动中"))
        assertEquals("前置", s.lastFacing)
        assertEquals("0", s.lastCameraId)
    }

    @Test
    fun `环形缓冲只保留最近若干条`() {
        val s = CameraWatchState(capacity = 3)
        for (t in 1..6) s.onAppOp(t, "pkg$t", starting = true, timeMs = t * 1_000L)
        val recent = s.recent(10)
        assertEquals(3, recent.size)
        assertEquals("pkg6", recent.first().packageName)
        assertEquals("pkg4", recent.last().packageName)
    }

    @Test
    fun `回读文本包含计数 占用者与最近事件`() {
        val s = CameraWatchState()
        s.onAppOp(uid, app, starting = true, timeMs = 0)
        s.onCameraState("1", 1, 1, timeMs = 1_000)
        val dump = s.dump()
        assertTrue(dump, dump.contains("计数=开 1 / 关 0"))
        assertTrue(dump, dump.contains("朝向=前置"))
        assertTrue(dump, dump.contains("占用中=$app($uid)"))
        // 最新的一条在最前（UI 直接自上而下读）：STATE 比 OPEN 晚，必须出现在它上面
        val stateLine = dump.lines().indexOfFirst { it.contains(" 态 ") }
        val openLine = dump.lines().indexOfFirst { it.contains(" 开 ") }
        assertTrue(dump, stateLine > 0 && openLine > 0 && stateLine < openLine)
    }

    @Test
    fun `空状态的回读文本可读`() {
        val dump = CameraWatchState().dump()
        assertTrue(dump, dump.contains("（无活动）"))
        assertTrue(dump, dump.contains("占用中=无"))
        assertTrue(dump, dump.contains("朝向=未知"))
    }

    @Test
    fun `清空后回到初始态`() {
        val s = CameraWatchState()
        s.onAppOp(uid, app, starting = true, timeMs = 0)
        s.clear()
        assertTrue(s.recent().isEmpty())
        assertTrue(s.activeClients().isEmpty())
        assertEquals(0L, s.openCount)
        assertTrue(s.dump().contains("计数=开 0 / 关 0"))
    }

    @Test
    fun `朝向与状态名映射`() {
        assertEquals("后置", CameraWatchState.facingName(0))
        assertEquals("前置", CameraWatchState.facingName(1))
        assertEquals("外接", CameraWatchState.facingName(2))
        assertEquals("未知", CameraWatchState.facingName(9))
        assertEquals("关闭", CameraWatchState.stateName(0))
        assertEquals("活动中", CameraWatchState.stateName(2))
        assertEquals("状态7", CameraWatchState.stateName(7))
    }
}
