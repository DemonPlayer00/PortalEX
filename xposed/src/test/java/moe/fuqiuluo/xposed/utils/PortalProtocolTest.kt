package moe.fuqiuluo.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 跨进程协议的**值契约**。
 *
 * 这条通道的错误表现是"命令发出去了、系统侧什么都没发生"（静默失效），所以任何
 * 改名/删值都必须是一次**显式**的改动：改这些常量 → 本测试失败 → 你确认升级协议。
 * 断言的是"值本身"，不是"引用能编译" —— 后者抓不到"值被改错"。
 */
class PortalProtocolTest {

    private fun consts(cls: Class<*>): Map<String, String> =
        cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to (it.get(null) as String) }

    private val cmd = consts(PortalProtocol.Cmd::class.java)
    private val key = consts(PortalProtocol.Key::class.java)

    @Test
    fun providerAndPrefsName_arePinned() {
        // 这两个值一旦变动就是"与已安装版本不兼容"，必须是有意识的决定
        assertEquals("portal", PortalProtocol.PROVIDER)
        assertEquals("portal", PortalProtocol.PREFS_NAME)
    }

    @Test
    fun commandCount_isPinned() {
        // 新增/删除命令 ⇒ 改这条断言（顺带提醒你两侧都要接上）
        // 30 → 36：统一架构迁移（推进搬到系统侧）新增 set_rocker / set_route / route_control
        //          / get_motion / get_stamina / reset_stamina
        assertEquals(36, cmd.size)
    }

    @Test
    fun keyCount_isPinned() {
        // 新增/删除键 ⇒ 改这条断言
        // 40 → 41：`stamina_config`（体力参数下发）
        // 41 → 63：统一架构迁移 —— 推进/体力搬到系统侧后新增
        //          report_duration、route_lat/lon/travelled/distance/points、
        //          motion_mode/playing/completed，以及 stamina_* 状态回读 12 项
        // 63 → 62：删掉 `hide_developer_mode`（"隐藏开发者模式"整条功能移除）
        //          （断言当时没跟着改，实际仍是 63 —— 本轮顺手对齐）
        // 63 → 67：按组波动两条参数 × 两组（波动强度 / 随机区间，默认 15%）
        assertEquals(67, key.size)
    }

    @Test
    fun namesAreLowerSnakeCase() {
        (cmd.values + key.values).forEach { v ->
            assertTrue("协议名应是小写蛇形：$v", Regex("^[a-z][a-z_0-9]*$").matches(v))
            assertFalse("不该有双下划线：$v", v.contains("__"))
        }
    }

    @Test
    fun commandValuesAreUnique() {
        assertEquals(cmd.size, cmd.values.toSet().size)
    }

    @Test
    fun keyValuesAreUnique() {
        assertEquals(key.size, key.values.toSet().size)
    }

    @Test
    fun sameNameAcrossNamespaces_isOnlyTheKnownThree() {
        // 同名（既是命令又是回包键）是允许的，但必须**是这三个**：
        // 多出来说明有人把一个键误登记成了命令（或反之），那是协议污染。
        assertEquals(
            setOf("is_start", "is_gnss_start", "is_wifi_mock_start"),
            cmd.values.toSet() intersect key.values.toSet()
        )
    }

    @Test
    fun criticalNames_matchTheWireFormat() {
        // 挑几个"改错了会静默失效"的钉死
        assertEquals("command_id", PortalProtocol.Key.COMMAND_ID)
        assertEquals("exchange_key", PortalProtocol.Cmd.EXCHANGE_KEY)
        assertEquals("put_config", PortalProtocol.Cmd.PUT_CONFIG)
        assertEquals("set_sensor_mock", PortalProtocol.Cmd.SET_SENSOR_MOCK)
        assertEquals("get_sensor_status", PortalProtocol.Cmd.GET_SENSOR_STATUS)
    }
}
