package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FusedMode] 的三态契约：取值闭环、非法值回落默认、标签与设置页滑块一一对应。
 *
 * 为什么值得钉：这个三态取代了原来的布尔开关，而**设置页滑块的下标**（0/1/2）就是模式值
 * —— 下标与语义错位会让"伪装"变成"放行"，那是最坏的一种静默错误（真位置会被交给应用）。
 */
class FusedModeTest {

    @Test
    fun modeValues_matchSliderIndices() {
        assertEquals(0, FusedMode.REJECT)
        assertEquals(1, FusedMode.ALLOW)
        assertEquals(2, FusedMode.DISGUISE)
        assertEquals("滑块 0..2 与三态一一对应", 2, FusedMode.DISGUISE)
    }

    @Test
    fun defaultIsDisguise() {
        assertEquals(FusedMode.DISGUISE, FusedMode.DEFAULT)
    }

    @Test
    fun sanitize_passesKnownValues_andFallsBackToDefault() {
        assertEquals(FusedMode.REJECT, FusedMode.sanitize(0))
        assertEquals(FusedMode.ALLOW, FusedMode.sanitize(1))
        assertEquals(FusedMode.DISGUISE, FusedMode.sanitize(2))
        // 非法/越界/负数（例如旧版本传了布尔值 1/0 之外的数）一律回默认，绝不把脏值传进决策
        assertEquals(FusedMode.DEFAULT, FusedMode.sanitize(-1))
        assertEquals(FusedMode.DEFAULT, FusedMode.sanitize(3))
        assertEquals(FusedMode.DEFAULT, FusedMode.sanitize(99))
    }

    @Test
    fun labels_areDistinctAndMarkAllowAsNotRecommended() {
        val labels = listOf(FusedMode.REJECT, FusedMode.ALLOW, FusedMode.DISGUISE).map(FusedMode::label)
        assertEquals("三个标签必须互不相同", 3, labels.toSet().size)
        assert(labels[1].contains("不推荐")) { "放行必须标注不推荐：${labels[1]}" }
        assertEquals("伪装", FusedMode.label(FusedMode.DISGUISE))
    }
}
