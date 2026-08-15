package me.rerere.rikkahub.data.study

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class StudyExamCountdownState(
    val daysUntil: Long,
) {
    val isToday: Boolean
        get() = daysUntil == 0L

    val hasPassed: Boolean
        get() = daysUntil < 0L
}

object StudyExamCountdown {
    val DEFAULT_TARGET_DATE: LocalDate = LocalDate.of(2027, 6, 7)

    fun calculate(
        today: LocalDate,
        targetDate: LocalDate,
    ): StudyExamCountdownState = StudyExamCountdownState(
        daysUntil = ChronoUnit.DAYS.between(today, targetDate),
    )
}
