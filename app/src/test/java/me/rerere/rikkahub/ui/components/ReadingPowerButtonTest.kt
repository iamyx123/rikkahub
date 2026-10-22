package me.rerere.rikkahub.ui.components

import me.rerere.rikkahub.data.power.ReadingPowerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPowerButtonTest {
    @Test
    fun `automatic preference cannot claim airplane state and a manual system airplane remains filled when automation is off`() {
        val waiting = readingPowerButtonVisual(true, false, ReadingPowerPhase.COUNTDOWN)
        assertEquals("自动开", waiting.automaticLabel)
        assertEquals("飞行未开", waiting.flightLabel)
        assertEquals(ReadingPowerFlightIcon.OUTLINE, waiting.flightIcon)
        assertEquals("等待", waiting.phaseLabel)
        val manual = readingPowerButtonVisual(false, true, ReadingPowerPhase.DISABLED)
        assertEquals("自动关", manual.automaticLabel)
        assertEquals("飞行已开", manual.flightLabel)
        assertEquals(ReadingPowerFlightIcon.FILLED, manual.flightIcon)
    }

    @Test
    fun `unverified system state stays visibly unknown even when the last phase claimed airplane`() {
        val unknown = readingPowerButtonVisual(true, null, ReadingPowerPhase.AIRPLANE)
        assertEquals("飞行未知", unknown.flightLabel)
        assertEquals(ReadingPowerFlightIcon.UNKNOWN, unknown.flightIcon)
        val readBackOff = readingPowerButtonVisual(true, false, ReadingPowerPhase.AIRPLANE)
        assertEquals("飞行未开", readBackOff.flightLabel)
        assertEquals(ReadingPowerFlightIcon.OUTLINE, readBackOff.flightIcon)
        assertEquals("系统飞行模式未开启", readingPowerStatus(ReadingPowerPhase.AIRPLANE, true, false))
        assertEquals("尚未读到系统飞行模式状态", readingPowerStatus(ReadingPowerPhase.AIRPLANE, true, null))
    }

    @Test
    fun `restoration and errors keep the actual silhouette while showing a separate visible phase label`() {
        val restoring = readingPowerButtonVisual(false, true, ReadingPowerPhase.RESTORING)
        assertEquals("自动关", restoring.automaticLabel)
        assertEquals("飞行已开", restoring.flightLabel)
        assertEquals(ReadingPowerFlightIcon.FILLED, restoring.flightIcon)
        assertEquals("恢复", restoring.phaseLabel)
        val restored = readingPowerButtonVisual(false, false, ReadingPowerPhase.DISABLED)
        assertEquals(ReadingPowerFlightIcon.OUTLINE, restored.flightIcon)
        assertEquals("飞行未开", restored.flightLabel)
        val error = readingPowerButtonVisual(true, true, ReadingPowerPhase.ERROR)
        assertEquals("错误", error.phaseLabel)
        assertEquals(ReadingPowerFlightIcon.FILLED, error.flightIcon)
        assertEquals("错误", readingPowerButtonVisual(false, null, ReadingPowerPhase.DISABLED, hasError = true).phaseLabel)
    }

    @Test
    fun `every controller phase preserves the same confirmed system flight mapping`() {
        ReadingPowerPhase.entries.forEach { phase ->
            assertEquals(ReadingPowerFlightIcon.FILLED, readingPowerButtonVisual(false, true, phase).flightIcon)
            assertEquals(ReadingPowerFlightIcon.OUTLINE, readingPowerButtonVisual(true, false, phase).flightIcon)
            assertEquals("飞行未知", readingPowerButtonVisual(true, null, phase).flightLabel)
        }
    }

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
        assertNotEquals(readingPowerStatus(ReadingPowerPhase.AIRPLANE, true, true), readingPowerStatus(ReadingPowerPhase.AIRPLANE, false, true))
        assertEquals("飞行模式控制失败", readingPowerStatus(ReadingPowerPhase.ERROR, false))
    }
}
