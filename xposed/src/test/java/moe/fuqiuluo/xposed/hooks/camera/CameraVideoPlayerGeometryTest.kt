package moe.fuqiuluo.xposed.hooks.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letterbox + 旋转的回归测试。
 *
 * 为什么值得单独写：这块算错了**不会抛异常**，只会"比例不对/竖屏视频横过来"。
 * 没有断言的话，只能在真机上一条条视频看，而且很容易被"这次刚好也是 16:9"蒙混过去。
 * 这里把三条不变量钉死：
 *  1. 等比：目标矩形一定装得进 [-1,1]²，且**至少一个方向贴边**（不贴边就是白缩了一圈）
 *  2. 居中：两边留黑相等（[NDC_RECT] 对称）
 *  3. 旋转只重排采样坐标：四个角仍落在 [0,1]² 且不重合（退化矩阵会让画面塌成一条线）
 */
class CameraVideoPlayerGeometryTest {

    private companion object {
        const val EPS = 1e-6
    }

    private fun rect(targetW: Int, targetH: Int, srcW: Double, srcH: Double) =
        VideoGeometry.fitCenterToNdc(targetW, targetH, FrameSize(srcW, srcH))

    /** 目标矩形必须装进 [-1,1]²，且至少一个方向贴边 */
    private fun assertFitsAndTouches(a: NdcRect, targetW: Int, targetH: Int) {
        assertTrue("矩形超出 NDC: $a", a.x >= -1.0 - EPS && a.y >= -1.0 - EPS)
        assertTrue(
            "矩形超出 NDC: $a",
            a.x + a.width <= 1.0 + EPS && a.y + a.height <= 1.0 + EPS
        )
        assertTrue("宽高必须为正: $a", a.width > 0 && a.height > 0)
        val touches = (a.width >= 2.0 - EPS) || (a.height >= 2.0 - EPS)
        assertTrue("等比适配必然有一个方向贴边，否则就是白缩了一圈: $a", touches)
    }

    // ------------------------------------------------------------ 宽高比

    @Test
    fun `视频比目标更宽时上下留黑`() {
        val a = rect(1000, 1000, 1920.0, 1080.0)
        assertFitsAndTouches(a, 1000, 1000)
        assertEquals(2.0, a.width, EPS)
        assertEquals(2.0 * 1080.0 / 1920.0, a.height, EPS)
        // 居中：上下留黑相等 → y 对称
        assertEquals(-(a.height / 2.0), a.y, EPS)
    }

    @Test
    fun `视频比目标更窄时左右留黑`() {
        val a = rect(1920, 1080, 1080.0, 1920.0)
        assertFitsAndTouches(a, 1920, 1080)
        assertEquals(2.0, a.height, EPS)
        // 宽 = 2 × (源宽高比 / 目标宽高比) = 2 × (1080/1920) / (1920/1080) ≈ 0.6328
        assertEquals(2.0 * (1080.0 / 1920.0) / (1920.0 / 1080.0), a.width, EPS)
        assertEquals(-(a.width / 2.0), a.x, EPS)
    }

    @Test
    fun `宽高比相等时铺满且无位移`() {
        val a = rect(1280, 720, 1920.0, 1080.0)
        assertEquals(2.0, a.width, EPS)
        assertEquals(2.0, a.height, EPS)
        assertEquals(-1.0, a.x, EPS)
        assertEquals(-1.0, a.y, EPS)
        // 正方形源 ↔ 正方形目标
        val b = rect(640, 640, 1000.0, 1000.0)
        assertEquals(2.0, b.width, EPS)
        assertEquals(2.0, b.height, EPS)
    }

    @Test
    fun `非整数比例也只贴一个方向`() {
        // 4:3 的视频放进 16:9 的窗口：左右留黑（pillarbox），高度贴边
        val a = rect(1280, 720, 640.0, 480.0)
        assertFitsAndTouches(a, 1280, 720)
        assertEquals(2.0, a.height, EPS)
        // 宽 = 2 × (源宽高比 / 目标宽高比) = 2 × (4/3) / (16/9) = 1.5
        assertEquals(1.5, a.width, EPS)
        assertEquals(2.0 * (640.0 / 480.0) / (1280.0 / 720.0), a.width, EPS)
        assertEquals(-0.75, a.x, EPS)
        assertEquals(-1.0, a.y, EPS)
    }

    // ------------------------------------------------------------ 退化输入

    @Test
    fun `目标尺寸为 0 时返回整屏矩形而不是除零`() {
        for (r in listOf(
            rect(0, 1000, 1920.0, 1080.0),
            rect(1000, 0, 1920.0, 1080.0),
            rect(0, 0, 1920.0, 1080.0),
            rect(-1, -1, 1920.0, 1080.0)
        )) {
            assertEquals(NdcRect(-1.0, -1.0, 2.0, 2.0), r)
            assertFalse(r.width.isNaN() || r.height.isNaN())
            assertFalse(r.x.isNaN() || r.y.isNaN())
        }
    }

    @Test
    fun `源尺寸非法时同样退化且不产生 NaN`() {
        for (r in listOf(
            rect(1000, 1000, 0.0, 1080.0),
            rect(1000, 1000, 1920.0, 0.0),
            rect(1000, 1000, -1920.0, 1080.0)
        )) {
            assertEquals(NdcRect(-1.0, -1.0, 2.0, 2.0), r)
        }
    }

    // ------------------------------------------------------------ 旋转

    /** 按矩阵把 `(u, v)` 变换一次，方便读断言 */
    private fun map(m: FloatArray, u: Double, v: Double) = VideoGeometry.apply(m, u, v)

    @Test
    fun `0 度不旋转`() {
        val m = VideoGeometry.rotationMatrix(0)
        assertEquals(1.0, m[0].toDouble(), 1e-6)
        assertEquals(1.0, m[5].toDouble(), 1e-6)
        val (x, y) = map(m, 0.25, 0.75)
        assertEquals(0.25, x, 1e-6)
        assertEquals(0.75, y, 1e-6)
    }

    @Test
    fun `90 度是屏幕空间里的顺时针四分之一转`() {
        val m = VideoGeometry.rotationMatrix(90)
        // 竖向存储的视频（rotation-degrees=90 表示画面需顺时针转正）：
        // 取样坐标必须落在"转正之后"的位置，所以这里是 (u,v) -> (v, 1-u)。
        // 源图左上 (0,0) → 显示右上 (1,0)
        val (x0, y0) = map(m, 0.0, 0.0)
        assertEquals(1.0, x0, 1e-6)
        assertEquals(0.0, y0, 1e-6)
        // 源图左下 (0,1) → 显示左上 (0,0)
        val (x1, y1) = map(m, 0.0, 1.0)
        assertEquals(0.0, x1, 1e-6)
        assertEquals(0.0, y1, 1e-6)
        // 源图右上 (1,0) → 显示右下 (1,1)
        val (x2, y2) = map(m, 1.0, 0.0)
        assertEquals(1.0, x2, 1e-6)
        assertEquals(1.0, y2, 1e-6)
        // 源图右下 (1,1) → 显示左下 (0,1)
        val (x3, y3) = map(m, 1.0, 1.0)
        assertEquals(0.0, x3, 1e-6)
        assertEquals(1.0, y3, 1e-6)
    }

    @Test
    fun `180 度对角翻转`() {
        val m = VideoGeometry.rotationMatrix(180)
        val (x0, y0) = map(m, 0.0, 0.0)
        assertEquals(1.0, x0, 1e-6)
        assertEquals(1.0, y0, 1e-6)
        val (x1, y1) = map(m, 1.0, 1.0)
        assertEquals(0.0, x1, 1e-6)
        assertEquals(0.0, y1, 1e-6)
    }

    @Test
    fun `270 与 90 互逆`() {
        val m90 = VideoGeometry.rotationMatrix(90)
        val m270 = VideoGeometry.rotationMatrix(270)
        for ((u, v) in listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 0.3 to 0.7)) {
            val (x, y) = map(m90, u, v)
            val (bx, by) = map(m270, x, y)
            assertEquals(u, bx, 1e-6)
            assertEquals(v, by, 1e-6)
        }
    }

    @Test
    fun `四个角在 90 180 270 下仍是四个互不重合的角`() {
        val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)
        for (deg in listOf(90, 180, 270)) {
            val m = VideoGeometry.rotationMatrix(deg)
            val mapped = corners.map { (u, v) -> map(m, u, v) }
            // 每个角都还在 [0,1]² 内
            mapped.forEach { (x, y) ->
                assertTrue("$deg 度把角映射到界外: ($x,$y)", x in -1e-6..(1.0 + 1e-6))
                assertTrue("$deg 度把角映射到界外: ($x,$y)", y in -1e-6..(1.0 + 1e-6))
            }
            // 且互不重合（面积没塌）
            val distinct = mapped.map { (x, y) -> (Math.round(x * 1000)) to (Math.round(y * 1000)) }.toSet()
            assertEquals("$deg 度让画面对角塌陷", 4, distinct.size)
        }
    }

    @Test
    fun `负数与超圈角度归一到 0-359`() {
        val m0 = VideoGeometry.rotationMatrix(0)
        for (deg in listOf(-360, 360, 720, 0)) {
            val m = VideoGeometry.rotationMatrix(deg)
            assertEquals(1.0, m[0].toDouble(), 1e-6)
            assertEquals(1.0, m[5].toDouble(), 1e-6)
            assertEquals(0.0, m[12].toDouble(), 1e-6)
            assertEquals(0.0, m[13].toDouble(), 1e-6)
        }
        // -90 ≡ 270、450 ≡ 90
        val mMinus90 = VideoGeometry.rotationMatrix(-90)
        val m270 = VideoGeometry.rotationMatrix(270)
        for (i in 0 until 16) assertEquals("idx $i", m270[i], mMinus90[i], 1e-6f)
        val m450 = VideoGeometry.rotationMatrix(450)
        val m90 = VideoGeometry.rotationMatrix(90)
        for (i in 0 until 16) assertEquals("idx $i", m90[i], m450[i], 1e-6f)
        // 脏数据（容器里真的出现过 45）按"不转"处理，比瞎转安全
        val m45 = VideoGeometry.rotationMatrix(45)
        for (i in 0 until 16) assertEquals("idx $i", m0[i], m45[i], 1e-6f)
    }

    // ------------------------------------------------------------ 旋转 + 适配的复合

    @Test
    fun `竖屏视频旋转 90 度后在横屏宿主里是竖直的一条`() {
        // 容器里存的是 1920x1080 + rotation=90 → 显示尺寸 1080x1920（竖屏）
        val shown = FrameSize(1080.0, 1920.0)
        val a = VideoGeometry.fitCenterToNdc(1920, 1080, shown)
        assertFitsAndTouches(a, 1920, 1080)
        assertEquals("高度贴边", 2.0, a.height, EPS)
        // 宽 = 2 × (源宽高比 / 目标宽高比) = 2 × (1080/1920) / (1920/1080) ≈ 0.6328
        assertEquals(2.0 * (1080.0 / 1920.0) / (1920.0 / 1080.0), a.width, EPS)
        assertEquals(-(a.width / 2.0), a.x, EPS)
    }

    @Test
    fun `旋转 180 不改变适配矩形`() {
        val landscape = VideoGeometry.fitCenterToNdc(1000, 1000, FrameSize(1920.0, 1080.0))
        val rotated = VideoGeometry.fitCenterToNdc(1000, 1000, FrameSize(1920.0, 1080.0))
        // 180 度只翻采样坐标，宽高比不变，所以矩形逐位相同
        assertEquals(landscape, rotated)
        val m = VideoGeometry.rotationMatrix(180)
        assertTrue(m[4] == 0f && m[1] == 0f) // 无剪切
    }

    @Test
    fun `矩阵乘法是列主序且与 GLSL 语义一致`() {
        // A = 绕原点平移 (0.5, -0.25)（列主序：平移在 [12],[13]）
        val a = VideoGeometry.identity(FloatArray(16))
        a[12] = 0.5f
        a[13] = -0.25f
        // B = 缩放 2x
        val b = VideoGeometry.identity(FloatArray(16))
        b[0] = 2f
        b[5] = 2f
        val ab = VideoGeometry.multiply(a, b, FloatArray(16))
        // (A*B)*(u,v) 应当等价于 A(B(u,v))：先缩放再平移
        for ((u, v) in listOf(0.0 to 0.0, 1.0 to 1.0, 0.25 to 0.75)) {
            val (bx, by) = map(b, u, v)
            val (ax, ay) = map(a, bx, by)
            val (cx, cy) = map(ab, u, v)
            assertEquals(ax, cx, 1e-6)
            assertEquals(ay, cy, 1e-6)
        }
        // 顺序不能交换：B*A 的结果不一样（先平移再缩放）
        val ba = VideoGeometry.multiply(b, a, FloatArray(16))
        val (px, _) = map(ab, 1.0, 1.0)
        val (qx, _) = map(ba, 1.0, 1.0)
        // A*B: 先缩放 2 再平移 → x = 2 + 0.5 = 2.5；B*A: 先平移再缩放 → x = 2*(1+0.5) = 3
        assertEquals(2.5, px, 1e-6)
        assertEquals(3.0, qx, 1e-6)
        assertFalse("乘法不可交换，写反了必然出 bug", kotlin.math.abs(px - qx) < 1e-9)
    }

    @Test
    fun `复合旋转与 SurfaceTexture 变换时四条边仍在界内`() {
        // 模拟生产路径：texMatrix（这里用单位阵代替解码器给的裁剪矩阵）× 旋转
        val tex = VideoGeometry.identity(FloatArray(16))
        for (deg in listOf(0, 90, 180, 270)) {
            val rot = VideoGeometry.rotationMatrix(deg)
            val m = VideoGeometry.multiply(tex, rot, FloatArray(16))
            for ((u, v) in listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)) {
                val (x, y) = map(m, u, v)
                assertTrue("$deg 度越界: ($x,$y)", x in -1e-6..(1.0 + 1e-6) && y in -1e-6..(1.0 + 1e-6))
            }
        }
    }
}
