package me.rerere.rikkahub.data.study

import org.junit.Assert.assertEquals
import org.junit.Test

class StudyDesktopSystemPolicyTest {
    @Test
    fun `only active study desktop holding HOME may enforce system policy`() {
        StudyDesktopMode.entries.forEach { mode ->
            listOf(false, true).forEach { isDefaultHome ->
                val expected =
                    if (mode == StudyDesktopMode.ACTIVE && isDefaultHome) {
                        StudyDesktopSystemPolicyDirective.ENFORCE
                    } else {
                        StudyDesktopSystemPolicyDirective.RESTORE
                    }
                assertEquals(
                    "$mode with defaultHome=$isDefaultHome",
                    expected,
                    studyDesktopSystemPolicyDirective(mode, isDefaultHome),
                )
            }
        }
    }

    @Test
    fun `losing HOME immediately changes an active policy to restore`() {
        assertEquals(
            StudyDesktopSystemPolicyDirective.ENFORCE,
            studyDesktopSystemPolicyDirective(
                mode = StudyDesktopMode.ACTIVE,
                isDefaultHome = true,
            ),
        )
        assertEquals(
            StudyDesktopSystemPolicyDirective.RESTORE,
            studyDesktopSystemPolicyDirective(
                mode = StudyDesktopMode.ACTIVE,
                isDefaultHome = false,
            ),
        )
    }

    @Test
    fun `exit and recovery never retain restrictions even while still HOME`() {
        listOf(
            StudyDesktopMode.EXITING,
            StudyDesktopMode.RECOVERY,
            StudyDesktopMode.DISABLED,
            StudyDesktopMode.ENABLING,
        ).forEach { mode ->
            assertEquals(
                StudyDesktopSystemPolicyDirective.RESTORE,
                studyDesktopSystemPolicyDirective(mode, isDefaultHome = true),
            )
        }
    }
}
