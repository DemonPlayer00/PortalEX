package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运动期速度噪声的**形状**回归测试。
 *
 * 旧实现是逐帧独立的 `U(-a, +a)`，后果在真机上被看见了：客户端只要画"瞬时速度"，
 * 曲线必然是一片锯齿（步道乐跑配速曲线上那些 ±20% 的 excursion 正好落在这个包络里）。
 * 现在改成两尺度：**一次会话一个固定偏置 + 秒级快分量**（见 [VirtualWorld.speedOffsetSample]）。
 *
 * 盯三件事：
 *  1. **帧间平滑**：连续两次采样（~100ms）之差必须远小于幅度；
 *  2. **包络不超标**：|偏移| ≤ amplitude（设置项的语义不能变）；
 *  3. **会话之间有差异**：每次会话重抽偏置 ⇒ 仍然不是"速度恒等于设定值"这种硬指纹。
 */
class SpeedNoiseShapeTest {

    private val a = 0.3

    @Test
    fun `帧间平滑且不超包络，会话间会重抽偏置`() {
        val saved = LocConfig.enable
        try {
            // ---- 会话 1 ----
            LocConfig.enable = false
            Thread.sleep(20)
            LocConfig.enable = true                 // false→true 沿 ⇒ 抽新偏置
            val samples = ArrayList<Double>()
            repeat(30) {
                samples.add(VirtualWorld.speedOffsetSample(a))
                Thread.sleep(100)
            }
            // 包络
            samples.forEach {
                assertTrue("偏移 $it 超出包络 ±$a", kotlin.math.abs(it) <= a + 1e-9)
            }
            // 帧间平滑。阈值按"与旧模型对比"定：
            //   旧 U(-a,+a)：逐帧 |Δ| 的中位 ≈ 0.57a、最大 = 2a；
            //   新两尺度：中位 ≈ 0.03a（快分量 100ms 步长 σ=0.06a 的典型值）。
            // 所以"中位 < 0.05a"是新模型轻松通过、旧模型差 10 倍的判据。
            val steps = samples.drop(1).zipWithNext { x, y -> kotlin.math.abs(y - x) }.sorted()
            val medianStep = steps[steps.size / 2]
            val maxStep = steps.last()
            assertTrue(
                "帧间跳变中位 %.4f（= %.3fa）过大 —— 旧白噪声是 0.57a".format(medianStep, medianStep / a),
                medianStep < 0.05 * a
            )
            assertTrue("帧间跳变最大 %.4f（= %.3fa）过大".format(maxStep, maxStep / a), maxStep < 0.25 * a)
            // 3 秒窗口内的漂移：旧模型必然打满 ±a（跨度 2a），新模型只由快分量决定
            val spread = samples.max() - samples.min()
            assertTrue(
                "3 秒内偏移跨度 %.4f（= %.3fa）过大 —— 旧白噪声会打满 2a".format(spread, spread / a),
                spread < 0.6 * a
            )

            // ---- 会话 2：偏置必须重抽 ----
            LocConfig.enable = false
            Thread.sleep(20)
            LocConfig.enable = true
            val first2 = VirtualWorld.speedOffsetSample(a)
            val changed = kotlin.math.abs(first2 - samples.first()) > 1e-6
            assertTrue(
                "两次会话的首个偏移完全相同（%.6f）——偏置没有重抽，会退化成'速度恒等于设定值'".format(first2),
                changed
            )
        } finally {
            LocConfig.enable = saved
        }
    }

    @Test
    fun `幅度为 0 时不产生偏移`() {
        val saved = LocConfig.enable
        try {
            LocConfig.enable = false
            Thread.sleep(10)
            LocConfig.enable = true
            repeat(5) { assertEquals(0.0, VirtualWorld.speedOffsetSample(0.0), 0.0) }
        } finally {
            LocConfig.enable = saved
        }
    }
}
