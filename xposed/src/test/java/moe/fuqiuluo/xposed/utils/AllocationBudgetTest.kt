package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * **自动播放这条链上"每拍分配多少字节"的预算**（离线可测，不需要真机）。
 *
 * ## 为什么量这个
 *
 * 现象：自动播放时配速**大约每 20 秒出现一次尖峰**。一个必须排除的嫌疑是 GC：
 * 如果每拍都在堆上丢对象，系统就会周期性触发 GC，而 system_server 里的 GC 停顿会推迟
 * 推进时钟的一拍 —— 迟到的那一拍会在下一帧里**补回整段位移**，于是应用侧按帧算配速时
 * 看到一根尖峰（机制见 [moe.fuqiuluo.xposed.hooks.MotionClock] 的 dt 处理）。
 *
 * 判定相关性需要两个数：① 每拍分配多少字节（本文）；② 真机 GC 的实际周期与停顿
 * （需要设备端对时，本文件不负责）。只有 ①×20Hz 能在 ~20 秒内填满一次新生代 GC，
 * "GC ⇒ 尖峰"才**在量级上成立**。
 *
 * ## 这两个数当前是什么（2026-09-17 实测，HotSpot 上的 `getThreadAllocatedBytes`）
 *
 * 见测试输出。**它们是回归基线**：任何"顺手加的分配"都会让这两条断言变红 ——
 * 这正是想要的，因为这条链每秒跑 20 次。
 */
class AllocationBudgetTest {

    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocatedBytes(block: () -> Unit): Long {
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    /** 造一条 ~200m 的直线路线（4 个点，正北） */
    private fun straightRoute(points: Int = 4): Pair<DoubleArray, DoubleArray> =
        Pair(
            DoubleArray(points) { 30.0 + it * 0.000899 },
            DoubleArray(points) { 120.0 },
        )

    @Test
    fun autoPlayBeat_allocationPerBeat() {
        val (lat, lon) = straightRoute(points = 64)   // 让 indexAt 的二分有点深度
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        MotionEngine.beat(0.05, 3.05, 1.0, lat[0], lon[0])   // 对齐拍

        val beats = 20_000
        // 预热：让 JIT 与首用分配的类都就位（否则测到的是"第一次"的成本）
        repeat(2_000) { MotionEngine.beat(0.05, 3.05, 1.0, lat[0], lon[0]) }

        val bytes = allocatedBytes {
            repeat(beats) { MotionEngine.beat(0.05, 3.05, 1.0, lat[0], lon[0]) }
        }
        val perBeat = bytes.toDouble() / beats
        println("[分配预算] MotionEngine.beat（自动播放）: %.1f B/拍（%d 拍共 %d B）".format(perBeat, beats, bytes))
        println("[分配预算] ⇒ 20Hz 下 ≈ %.1f KB/s".format(perBeat * 20 / 1024.0))

        // 预算：这条链每秒 20 次，就该是"几乎不分配"。数值取当前实测的宽松上界。
        assertTrue("每拍分配 %.1f B 超出预算".format(perBeat), perBeat < 400.0)
    }

    @Test
    fun staminaTick_allocationPerBeat() {
        val cfg = StaminaConfig().sanitized()
        val model = StaminaModel()
        repeat(2_000) { model.tick(cfg, 0.05, 3.05, 0.15) }

        val beats = 20_000
        val bytes = allocatedBytes {
            repeat(beats) { model.tick(cfg, 0.05, 3.05, 0.15) }
        }
        val perBeat = bytes.toDouble() / beats
        println("[分配预算] StaminaModel.tick（移动中）: %.1f B/拍（%d 拍共 %d B）".format(perBeat, beats, bytes))
        println("[分配预算] ⇒ 20Hz 下 ≈ %.1f KB/s".format(perBeat * 20 / 1024.0))

        assertTrue("每拍分配 %.1f B 超出预算".format(perBeat), perBeat < 600.0)
    }

    /**
     * 合起来估"多久填满一次新生代"。
     *
     * Android 的新生代（nursery）默认量级 2~4MB（ART 按 TLAB 分配、并发 GC 触发阈值）。
     * 这个测试**不做断言**，只把推算打出来 —— 与真机 GC 周期（设备端对时）比对，
     * 才是"GC 与 20 秒尖峰有没有相关性"的证据。
     */
    @Test
    fun impliedGcPeriod_estimate() {
        val (lat, lon) = straightRoute(points = 64)
        MotionEngine.setRoute(lat, lon)
        MotionEngine.setPlaying(true)
        MotionEngine.beat(0.05, 3.05, 1.0, lat[0], lon[0])
        val cfg = StaminaConfig().sanitized()
        val model = StaminaModel()

        val beats = 20_000
        repeat(2_000) { model.tick(cfg, 0.05, 3.05, 0.15); MotionEngine.beat(0.05, 3.05, 1.0, lat[0], lon[0]) }
        val bytes = allocatedBytes {
            repeat(beats) {
                model.tick(cfg, 0.05, 3.05, 0.15)
                MotionEngine.beat(0.05, 3.05, 1.0, lat[0], lon[0])
            }
        }
        val perBeat = bytes.toDouble() / beats
        val perSecond = perBeat * 20
        val nursery2mb = 2.0 * 1024 * 1024
        println(
            "[分配预算] 每拍合计 %.1f B ⇒ %.1f KB/s（推进+体力，**不含**每帧的 Location/Bundle/交付参数）"
                .format(perBeat, perSecond / 1024)
        )
        println("[分配预算] 若新生代 2MB：约 %.0f 秒填满一次（真机 GC 周期要对这个数）".format(nursery2mb / perSecond))
    }
}
