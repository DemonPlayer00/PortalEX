package moe.microbios.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NMEA 语句的**逐字往返**契约：解析一份真机/文献样例，再序列化回字符串，必须与原文相同。
 *
 * 这条契约比"字段能读出来"更严：它同时钉住了字段顺序、空字段的占位与数字格式
 * （`%011.6f` / `%02d` / 一位小数）。格式漂移不会抛异常，只会让下游解析器静默读错，
 * 所以只能逐字比较。
 */
class NmeaRoundTripTest {

    private val samples = listOf(
        "\$GPGSA,A,1,,,,,,,,,,,,,,,,*32",
        "\$GPVTG,,T,,M,,N,,K,N*2C",
        "\$GPDTM,,,,,,,,*4A",
        "\$GPRMC,,V,,,,,,,,,,N,V*29",
        "\$GPGNS,,,,,,N,,,,,,,V*79",
        "\$GPGGA,,,,,,0,,,,,,,,*66",
        "\$GPGSV,3,1,09,03,29,281,,04,18,319,,16,60,239,,26,73,355,,1*60",
        "\$GPGSV,3,2,09,27,18,180,,28,39,060,,29,15,049,,31,55,010,,1*6E",
        "\$GPGSV,3,3,09,32,29,137,,1*52",
        "\$GLGSV,2,1,08,14,43,011,,16,07,248,,15,46,288,,05,38,326,,1*72",
        "\$GQGSV,1,1,03,02,64,078,,03,12,142,,04,44,162,,1*5F",
    )

    @Test
    fun everySample_roundTripsByteForByte() {
        samples.forEach { original ->
            val parsed = NMEA.valueOf(original)
            assertEquals("往返后语句不同：$original", original, parsed.toNmeaString())
        }
    }

    @Test
    fun checksum_isTwoUppercaseHexDigits() {
        samples.forEach { original ->
            val encoded = original.substringAfterLast('*')
            assertEquals("校验和应保留两位大写十六进制：$original", 2, encoded.length)
            assertTrue(encoded.all { it in '0'..'9' || it in 'A'..'F' })
            assertEquals(
                "解析后重算的校验和应与原文一致：$original",
                encoded,
                NMEA.calculateNmeaChecksum(original),
            )
        }
    }

    @Test
    fun malformedSentence_withoutDollar_isRejected() {
        val error = runCatching { NMEA.valueOf("GPGGA,1") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
