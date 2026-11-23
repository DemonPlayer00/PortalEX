package moe.fuqiuluo.portalex.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单帧素材的**几何契约**：编码器只接受偶数宽高，且"一帧图片"不该比屏幕还大。
 *
 * 这些数字错了不会抛异常 —— 只会在某台机器上 `MediaCodec.configure` 抛
 * `IllegalArgumentException`，或者存下一张糊成马赛克的图片。所以钉在单测里。
 */
class CameraStillMediaTest {

    @Test
    fun `横图按长边缩到上限并保持比例`() {
        assertEquals(1280 to 720, CameraStillMedia.fitInside(1920, 1080, 1280))
        assertEquals(1280 to 960, CameraStillMedia.fitInside(4000, 3000, 1280))
    }

    @Test
    fun `竖图不会把宽高写反`() {
        assertEquals(720 to 1280, CameraStillMedia.fitInside(1080, 1920, 1280))
        assertEquals(960 to 1280, CameraStillMedia.fitInside(3000, 4000, 1280))
    }

    @Test
    fun `小于上限的图片不放大`() {
        assertEquals(640 to 480, CameraStillMedia.fitInside(640, 480, 1280))
        assertEquals(1280 to 720, CameraStillMedia.fitInside(1280, 720, 1280))
    }

    @Test
    fun `宽高一律取偶数`() {
        // 1023x769 缩到 1000 长边：精确值 1000x751.7 → 都向下取偶
        val (w, h) = CameraStillMedia.fitInside(1023, 769, 1000)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        assertEquals(1000, w)
        // 极小图也不产生 0（0 宽高无法配置编码器）
        val tiny = CameraStillMedia.fitInside(3, 1, 1280)
        assertEquals(2 to 2, tiny)
    }

    @Test
    fun `非法输入退回可编码的最小尺寸`() {
        assertEquals(2 to 2, CameraStillMedia.fitInside(0, 0, 1280))
        assertEquals(2 to 2, CameraStillMedia.fitInside(-100, 50, 1280))
        assertEquals(2 to 2, CameraStillMedia.fitInside(100, 100, 0))
    }

    @Test
    fun `默认上限与素材常量一致`() {
        // 上限是"图片素材"的对外契约：改它等于让库里所有旧图的尺寸基准变化
        assertEquals(1280, CameraStillMedia.MAX_SIDE)
        assertEquals(3_000L, CameraStillMedia.HOLD_MS)
    }
}
