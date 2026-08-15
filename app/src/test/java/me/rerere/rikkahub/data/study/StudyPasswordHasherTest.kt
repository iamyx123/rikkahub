package me.rerere.rikkahub.data.study

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPasswordHasherTest {
    @Test
    fun credentialAcceptsOnlyOriginalPassword() {
        val credential = StudyPasswordHasher.create("study-2026")

        assertTrue(StudyPasswordHasher.verify("study-2026", credential))
        assertFalse(StudyPasswordHasher.verify("study-2025", credential))
        assertFalse(StudyPasswordHasher.verify("study-2026", "invalid"))
    }

    @Test
    fun credentialsUseRandomSalt() {
        val first = StudyPasswordHasher.create("same-password")
        val second = StudyPasswordHasher.create("same-password")

        assertNotEquals(first, second)
        assertTrue(StudyPasswordHasher.verify("same-password", first))
        assertTrue(StudyPasswordHasher.verify("same-password", second))
    }
}
