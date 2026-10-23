package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：**注入的 `speed` 不允许有系统性高报**。
 *
 * ## 盯的是什么
 *
 * [VirtualWorld.averageSpeedOverWindow] 的窗口起点是"≤ now−win 的最近一次采样"，
 * 它总比 win **更早**一个采样周期 ⇒ 被除的距离覆盖了 ≥ win 的弧。
 * 早期实现把分母**钳在窗口长度**上（`minOf(now - startNanos, winNanos)`），于是速度被
 * **单边**放大（采样周期/窗口 的比例）。
 *
 * 真机实测到的证据（步道乐跑配对抓帧）：注入 speed 的中位数 **4.104 m/s**，
 * 而世界真实速度 = 名义 4.0 × 体力倍率 ≤ 4.0 —— 高报 +2.6%，与 50ms/1s 推出的 ~+2.5% 吻合。
 * 客户端只要拿自己的位移差分和 `location.getSpeed()` 交叉一比，就会看到这个**恒定偏移**
 * （真机 GPS 的两者只该差在噪声里）—— 是可被识别的指纹，所以必须钉住。
 *
 * ## 为什么这个测试是确定性的
 *
 * 位置推进量由**真实经过时间**算出（`v × (now − last)`），于是"距离 ÷ 覆盖时长"在任何
 * 调度抖动下都恒等于 v —— 断言不必依赖 sleep 的精度。旧实现会把速度放大到 ≥ v×(1+周期/窗口)。
 */
class SpeedWindowBiasTest {

    private val lat0 = 23.525168
    private val lon0 = 113.606462
    private val v = 4.0                      // m/s，名义速度，体力倍率取 1.0

    private fun reset() {
        VirtualWorld.latitude = lat0
        VirtualWorld.longitude = lon0
        VirtualWorld.recordCoordinateChange(lat0, lon0)
    }

    /** 走 [durationMs] 毫秒，采样周期 [periodMs]，返回每拍调用一次窗口速度的结果 */
    private fun walk(durationMs: Long, periodMs: Long): List<Double> {
        reset()
        val out = ArrayList<Double>()
        var last = System.nanoTime()
        val deadline = last + durationMs * 1_000_000L
        var next = last
        while (System.nanoTime() < deadline) {
            val now = System.nanoTime()
            val dt = (now - last) / 1e9
            last = now
            // 沿正北推进 v×dt（等距近似：1° 纬度 ≈ 111320m，与世界推进同一口径）
            VirtualWorld.latitude += v * dt / 111_320.0
            VirtualWorld.recordCoordinateChange(VirtualWorld.latitude, VirtualWorld.longitude)
            if (now >= next) {
                next += periodMs * 1_000_000L
                out.add(VirtualWorld.averageSpeedOverWindow(1000).first)
            }
            Thread.sleep(periodMs)
        }
        return out
    }

    @Test
    fun `注入速度不得系统性高于真实速度`() {
        val speeds = walk(durationMs = 1_600, periodMs = 50)
        assertTrue("采样过少（${speeds.size}）", speeds.size >= 10)
        // 丢掉最早的几拍：那时采样历史还短于窗口，走的是另一条分支
        val steady = speeds.drop(4)
        val maxSpeed = steady.max()
        val mean = steady.average()
        assertTrue(
            "注入速度高报：max=${"%.4f".format(maxSpeed)} 名义=${v}（应 ≤ ${v * 1.01}）",
            maxSpeed <= v * 1.01
        )
        assertTrue(
            "注入速度均值偏离：mean=${"%.4f".format(mean)} 名义=${v}（应在 ±1.5% 内）",
            kotlin.math.abs(mean - v) <= v * 0.015
        )
    }
}
