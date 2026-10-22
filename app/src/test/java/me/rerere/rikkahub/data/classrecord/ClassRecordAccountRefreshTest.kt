package me.rerere.rikkahub.data.classrecord

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassRecordAccountRefreshTest {
    private val user = JsonObject(mapOf("id" to JsonPrimitive("teacher")))
    private val tool = JsonObject(mapOf("name" to JsonPrimitive("list_courses")))
    private val operation = JsonObject(mapOf("id" to JsonPrimitive("lesson.list")))

    @Test
    fun `validated account is restored before tool network failure without requiring another login`() = runTest {
        val events = mutableListOf<String>()
        val failure = IOException("offline")
        try {
            refreshClassRecordAccount(
                request = { path -> events += path; if (path.endsWith("/me")) user else throw failure },
                onAuthenticated = { assertEquals(user, it); events += "authenticated" },
                onTools = { error("tools request failed") },
                onOperations = { error("catalog must not be requested") },
            )
            error("expected failure")
        } catch (actual: IOException) { assertSame(failure, actual) }
        assertEquals(listOf("/api/v1/auth/me", "authenticated", "$CLASS_RECORD_PREFIX/tools"), events)
    }

    @Test
    fun `catalog failure retains both the restored user and successfully loaded tools`() = runTest {
        val events = mutableListOf<String>()
        try {
            refreshClassRecordAccount(
                request = { path -> when {
                    path.endsWith("/me") -> user
                    path.endsWith("/tools") -> JsonObject(mapOf("tools" to JsonArray(listOf(tool))))
                    else -> throw IOException("catalog unavailable")
                } },
                onAuthenticated = { events += "authenticated" },
                onTools = { assertEquals(listOf(tool), it); events += "tools" },
                onOperations = { error("catalog failed") },
            )
            error("expected failure")
        } catch (_: IOException) { }
        assertEquals(listOf("authenticated", "tools"), events)
    }

    @Test
    fun `failed account validation never publishes authenticated state or requests the catalog`() = runTest {
        val requests = mutableListOf<String>()
        try {
            refreshClassRecordAccount(
                request = { requests += it; throw IOException("401") },
                onAuthenticated = { error("unauthenticated") },
                onTools = { error("unauthenticated") },
                onOperations = { error("unauthenticated") },
            )
            error("expected failure")
        } catch (_: IOException) { }
        assertEquals(listOf("/api/v1/auth/me"), requests)
    }

    @Test
    fun `refresh cancellation propagates without retrying or publishing a catalog`() = runTest {
        var validated = false
        val failure = CancellationException("account switched")
        try {
            refreshClassRecordAccount(
                request = { if (it.endsWith("/me")) user else throw failure },
                onAuthenticated = { validated = true },
                onTools = { error("cancelled") },
                onOperations = { error("cancelled") },
            )
            error("expected cancellation")
        } catch (actual: CancellationException) { assertSame(failure, actual) }
        assertTrue(validated)
    }

    @Test
    fun `successful refresh publishes account then tools then operations`() = runTest {
        val events = mutableListOf<String>()
        refreshClassRecordAccount(
            request = { when {
                it.endsWith("/me") -> user
                it.endsWith("/tools") -> JsonObject(mapOf("tools" to JsonArray(listOf(tool))))
                else -> JsonObject(mapOf("operations" to JsonArray(listOf(operation))))
            } },
            onAuthenticated = { events += "authenticated" },
            onTools = { assertEquals(listOf(tool), it); events += "tools" },
            onOperations = { assertEquals(listOf(operation), it); events += "operations" },
        )
        assertEquals(listOf("authenticated", "tools", "operations"), events)
    }
}
