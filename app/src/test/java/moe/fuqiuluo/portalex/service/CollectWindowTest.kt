package moe.fuqiuluo.portalex.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SensorNoiseCalibrator.CollectWindow] 的时间口径：**等待传感器的时间不算采集**。
 *
 * 这里钉的就是真机上暴露的那个 bug：旧实现从"注册监听"时刻开始计时，于是传感器还没吐第一个
 * 样本时，进度条已经在跑"已采集 x 秒"；等到 8 秒走完，真正采到的样本可能还不够（真机上表现为
 * "进度先跑完、然后说样本太少"）。
 */
class CollectWindowTest {

    private fun window(warmup: Long = 600L, duration: Long = 8000L, noData: Long = 3000L) =
        SensorNoiseCalibrator.CollectWindow(warmup, duration, noData)

    @Test
    fun elapsed_是0_直到第一个有效样本到达() {
        val w = window()
        // 注册后 2.5 秒才有第一个样本：这段时间"已采集"必须仍是 0
        assertEquals(0L, w.elapsedMs(1_000L))
        assertEquals(0L, w.elapsedMs(2_500L))
        // 第一个样本到达（进入建立期）——窗口仍未开始
        assertFalse("建立期内的样本不该进入统计", w.accept(2_500L))
        assertEquals("建立期内仍显示 0", 0L, w.elapsedMs(2_600L))
        // 建立期结束后的第一个样本 ⇒ 窗口开始
        assertTrue(w.accept(3_100L))
        assertTrue(w.started)
        assertEquals(0L, w.elapsedMs(3_100L))
        assertEquals(500L, w.elapsedMs(3_600L))
    }

    @Test
    fun 采集窗口从窗口开始时刻起算满duration() {
        val w = window()
        w.accept(0L)          // 第一个样本
        w.accept(600L)        // 通过建立期 ⇒ 窗口起点
        assertFalse(w.finished(600L + 7_999L))
        assertTrue(w.finished(600L + 8_000L))
    }

    @Test
    fun 建立期内的样本一律丢弃_第一个样本本身也算建立期() {
        val w = window(warmup = 600L)
        assertFalse(w.accept(100L))   // 第一个样本：只是把建立期起点定下来
        assertFalse(w.accept(699L))   // 仍在建立期
        assertTrue(w.accept(700L))    // 600ms 已过 ⇒ 收下
    }

    @Test
    fun 时刻为0时也认得出已经采到样本() {
        // 哨兵值用 -1：0 是合法时间戳（单调时钟起点/相对时刻），不能当"未设置"
        val w = window()
        assertFalse(w.accept(0L))
        assertTrue(w.accept(700L))
        assertTrue(w.started)
    }

    @Test
    fun 一直没有数据才算noData_有样本就不算() {
        val w = window(noData = 3000L)
        assertFalse("还没到超时", w.noDataArrived(registeredAtMs = 0L, nowMs = 2_999L))
        assertTrue("超时且一个样本都没有", w.noDataArrived(registeredAtMs = 0L, nowMs = 3_000L))
        // 只要来过样本，就不该再判"没有数据"（哪怕窗口还没开始）
        val w2 = window(noData = 3000L)
        w2.accept(2_900L)
        assertFalse(w2.noDataArrived(registeredAtMs = 0L, nowMs = 10_000L))
    }
}
