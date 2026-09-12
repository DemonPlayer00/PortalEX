package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VirtualWorld.averageSpeedOverWindow] / `recordCoordinateChange` 的宿主回归测试。
 *
 * 盯的是 2026-09-12 那次改动：**瞬移判定从"位移 > 50m"改成"隐含速度 > 80m/s"**。
 *
 * 为什么必须改（实测，MI6）：位置推进现在按**真实经过时间**补偿（App 侧
 * `MockServiceViewModel.ensureMotionLoop`）——系统把我们的进程节流/冻结后，恢复时必然是
 * "大位移 + 大 Δt"（冻结 20s → 70m）。旧判据只看位移，会把这种**合法**补偿判成瞬移并
 * 返回 `(0.0,false)`：该帧 speed 落 0、再被 speedFloor 抬到 ~0.3m/s，在跑步软件上
 * 就是配速曲线里那根"尖峰"。新判据用速度，手动设点（巨大位移 + 极小 Δt）照样拦得住。
 *
 * 用例之间的隔离：`moveSamples` 是对象级私有状态，这里靠"故意发一次巨大跳变"清空它
 * （`jumpSpeed > 80` ⇒ 清窗口），再开始计时，避免上一个用例的采样污染本用例。
 */
class VirtualWorldSpeedTest {

    /** 纬度方向 1e-5 度 ≈ 1.11 m */
    private fun metersNorth(m: Double) = m / 111_320.0

    /**
     * 清空采样窗口并归零世界坐标。
     *
     * 注意：`averageSpeedOverWindow` 用的是**世界坐标**（[VirtualWorld.latitude]/`longitude`）
     * 与窗口起点的距离——生产代码里 `updateCoordinate` 是"写世界坐标 + recordCoordinateChange"
     * 成对发生的，所以测试也必须成对写，否则会把样本点到 (0,0) 的距离当成位移。
     */
    private fun reset() {
        VirtualWorld.latitude = 0.0
        VirtualWorld.longitude = 0.0
        VirtualWorld.recordCoordinateChange(0.0, 0.0)
        VirtualWorld.recordCoordinateChange(0.0, 1.0)   // 巨大跳变 ⇒ 清窗口
        VirtualWorld.latitude = 0.0
        VirtualWorld.longitude = 0.0
    }

    /** 写世界坐标 + 记一次采样（与生产同序） */
    private fun moveTo(lat: Double, lon: Double) {
        VirtualWorld.latitude = lat
        VirtualWorld.longitude = lon
        VirtualWorld.recordCoordinateChange(lat, lon)
    }

    @org.junit.After
    fun cleanup() {
        VirtualWorld.latitude = 0.0
        VirtualWorld.longitude = 0.0
        VirtualWorld.recordCoordinateChange(0.0, 1.0)   // 清窗口，别污染其它用例
    }

    @Test
    fun slowWalk_isMeasuredAsMoving() {
        reset()
        var lat = 0.0
        repeat(8) {
            Thread.sleep(100)
            lat += metersNorth(0.35)          // ≈3.5 m/s：与摇杆每 tick 0.35m 同量级
            moveTo(lat, 0.0)
        }
        val (speed, moving) = VirtualWorld.averageSpeedOverWindow(1000)
        assertTrue("3.5m/s 必须判为移动中（实测 $speed）", moving)
        assertTrue("速度应落在 2.0~5.0m/s，实测 $speed", speed in 2.0..5.0)
    }

    /**
     * 被节流后按真实时间补偿的**合法**大位移：60m / ~1.2s ≈ 50m/s。
     * 旧判据（>50m 即瞬移）会返回 `(0.0,false)`；新判据必须给出速度。
     */
    @Test
    fun largeButTimelyDisplacement_isNotTeleport() {
        reset()
        moveTo(0.0, 0.0)
        Thread.sleep(1200)
        moveTo(metersNorth(60.0), 0.0)
        val (speed, moving) = VirtualWorld.averageSpeedOverWindow(1000)
        assertTrue("60m/1.2s 不应被判成瞬移（旧 50m 阈值会误判），实测 speed=$speed moving=$moving", moving)
        assertTrue("速度应约 40~70m/s，实测 $speed", speed > 20.0)
    }

    /** 手动设点：位移巨大而 Δt 极小 ⇒ 仍然是瞬移，必须清窗口、不产生荒谬速度。 */
    @Test
    fun instantaneousHugeJump_isStillTeleport() {
        reset()
        moveTo(0.0, 0.0)
        moveTo(0.0, 0.05)   // ≈5.5km，Δt≈0
        val (speed, moving) = VirtualWorld.averageSpeedOverWindow(1000)
        assertFalse("手动设点必须不算移动（speed=$speed）", moving)
        assertTrue("瞬移后不应留下虚高速度，实测 $speed", speed < 1.0)
    }
}
