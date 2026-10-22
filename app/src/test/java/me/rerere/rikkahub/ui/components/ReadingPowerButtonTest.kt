package me.rerere.rikkahub.ui.components

import me.rerere.rikkahub.data.power.ReadingPowerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPowerButtonTest {
    @Test
    fun `exact integer seconds accept immediate execution and one hour but reject fractional or overflow input`() {
        assertEquals(0, parseReadingPowerDelaySeconds("0"))
        assertEquals(30, parseReadingPowerDelaySeconds(" 30 "))
        assertEquals(3600, parseReadingPowerDelaySeconds("3600"))
        listOf("", " ", "-1", "+30", "1.5", "1e2", "3601", "2147483648", "30秒").forEach {
            assertNull(it, parseReadingPowerDelaySeconds(it))
        }
    }

    @Test
    fun `dialog countdown rounds up without running past zero`() {
        assertEquals(30L, readingPowerRemainingSeconds(31000, 1000))
        assertEquals(1L, readingPowerRemainingSeconds(31000, 30999))
        assertEquals(0L, readingPowerRemainingSeconds(31000, 31000))
        assertEquals(0L, readingPowerRemainingSeconds(31000, 32000))
    }

    @Test
    fun `status distinguishes confirmed airplane ownership from the users preexisting airplane mode`() {
        assertNotEquals(readingPowerStatus(ReadingPowerPhase.AIRPLANE, true), readingPowerStatus(ReadingPowerPhase.AIRPLANE, false))
        assertEquals("飞行模式控制失败", readingPowerStatus(ReadingPowerPhase.ERROR, false))
    }
}
