package me.rerere.rikkahub.data.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StudyExamCountdownTest {
    @Test
    fun defaultTargetIs2027GaokaoOpeningDay() {
        assertEquals(LocalDate.of(2027, 6, 7), StudyExamCountdown.DEFAULT_TARGET_DATE)
    }

    @Test
    fun calculatesUpcomingTodayAndPassedStates() {
        val target = LocalDate.of(2027, 6, 7)

        val upcoming = StudyExamCountdown.calculate(LocalDate.of(2027, 6, 1), target)
        assertEquals(6L, upcoming.daysUntil)
        assertFalse(upcoming.isToday)
        assertFalse(upcoming.hasPassed)

        val today = StudyExamCountdown.calculate(target, target)
        assertTrue(today.isToday)
        assertFalse(today.hasPassed)

        val passed = StudyExamCountdown.calculate(LocalDate.of(2027, 6, 9), target)
        assertEquals(-2L, passed.daysUntil)
        assertTrue(passed.hasPassed)
    }
}
