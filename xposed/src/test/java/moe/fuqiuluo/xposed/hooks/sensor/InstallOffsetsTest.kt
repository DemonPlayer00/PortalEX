package moe.fuqiuluo.xposed.hooks.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `install` 偏移数组的**索引契约**。
 *
 * 这套数组以前是"两边各自记顺序"（Kotlin 平铺 7 个值、native 读 o[0]..o[6]、注释写 6 项），
 * 顺序错了不崩、只是功能静默失效 —— 最难查的一类。这里把顺序钉成断言。
 */
class InstallOffsetsTest {

    @Test
    fun indices_arePinnedInOrder() {
        assertEquals(0, InstallOffsets.RELRO_ADDR)
        assertEquals(1, InstallOffsets.RELRO_SIZE)
        assertEquals(2, InstallOffsets.POLL_AIDL)
        assertEquals(3, InstallOffsets.POLL_FMQ_AIDL)
        assertEquals(4, InstallOffsets.POLL_HIDL)
        assertEquals(5, InstallOffsets.POLL_FMQ_HIDL)
        assertEquals(6, InstallOffsets.ENABLE_DISABLE)
        assertEquals(7, InstallOffsets.COUNT)
    }

    @Test
    fun toOffsets_placesEveryFieldAtItsDocumentedIndex() {
        val resolved = LibSymbols.Resolved(
            libPath = "/apex/com.android.hardware.sensors/lib64/libsensorservice.so",
            pollAidl = 0xA1,
            fmqAidl = 0xA2,
            pollHidl = 0xB1,
            fmqHidl = 0xB2,
            relroAddr = 0xC0,
            relroSize = 0xC1,
            enableDisable = 0xD0
        )
        val o = resolved.toOffsets()
        assertEquals(InstallOffsets.COUNT, o.size)
        assertEquals(0xC0L, o[InstallOffsets.RELRO_ADDR])
        assertEquals(0xC1L, o[InstallOffsets.RELRO_SIZE])
        assertEquals(0xA1L, o[InstallOffsets.POLL_AIDL])
        assertEquals(0xA2L, o[InstallOffsets.POLL_FMQ_AIDL])
        assertEquals(0xB1L, o[InstallOffsets.POLL_HIDL])
        assertEquals(0xB2L, o[InstallOffsets.POLL_FMQ_HIDL])
        assertEquals(0xD0L, o[InstallOffsets.ENABLE_DISABLE])
    }

    @Test
    fun toOffsets_defaultsEnableDisableToZero() {
        val o = LibSymbols.Resolved(
            libPath = "x", pollAidl = 1, fmqAidl = 2, pollHidl = 3, fmqHidl = 4,
            relroAddr = 5, relroSize = 6
        ).toOffsets()
        assertEquals(
            "观测槽缺省必须是 0（= 本 ROM 没有该入口，不影响注入）",
            0L, o[InstallOffsets.ENABLE_DISABLE]
        )
    }

    @Test
    fun installChecked_rejectsWrongLengthBeforeTouchingNative() {
        // 长度不符必须在**进 native 之前**被拒（fail-closed）：这条路径不依赖 JNI，
        // 所以能在 JVM 上直接测；长度正确时的路径必然要调 native，不在单测范围。
        assertFalse(BinderSensorNative.installChecked(LongArray(InstallOffsets.COUNT - 1)))
        assertFalse(BinderSensorNative.installChecked(LongArray(0)))
        assertFalse(BinderSensorNative.installChecked(LongArray(InstallOffsets.COUNT + 3)))
    }
}
