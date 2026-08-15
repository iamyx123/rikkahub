package me.rerere.rikkahub.data.study

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyInterceptionTrackerTest {
    @Test
    fun `repeated events from one blocked app count once`() {
        val tracker = StudyInterceptionTracker()

        assertTrue(tracker.onBlockedForeground("com.example.blocked"))
        assertFalse(tracker.onBlockedForeground("com.example.blocked"))
        assertFalse(tracker.onBlockedForeground("com.example.blocked"))
    }

    @Test
    fun `a new blocked package starts a new interception`() {
        val tracker = StudyInterceptionTracker()

        assertTrue(tracker.onBlockedForeground("com.example.first"))
        assertTrue(tracker.onBlockedForeground("com.example.second"))
    }

    @Test
    fun `returning to an allowed app resets the next attempt`() {
        val tracker = StudyInterceptionTracker()

        assertTrue(tracker.onBlockedForeground("com.example.blocked"))
        tracker.onAllowedForeground()
        assertTrue(tracker.onBlockedForeground("com.example.blocked"))
    }
}
