package me.rerere.rikkahub.ui.components.classrecord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassRecordPaginationTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `empty result pages still follow cursor and preserve scope`() {
        val arguments = json("""{"school_id":"school","class_id":"class","query":"物理","limit":30}""")
        val next = classRecordContinuation("search", arguments, json("""{"results":[],"next_cursor":"opaque","has_more":true}"""))!!
        assertEquals("search", next.name)
        assertEquals("opaque", next.marker)
        assertEquals(arguments.withValue("cursor", JsonPrimitive("opaque")), next.arguments)
    }

    @Test
    fun `exact content continuation takes precedence and preserves selected mode`() {
        val arguments = json("""{"id":"lesson/original/revision/1","mode":"body"}""")
        val next = classRecordContinuation("fetch", arguments, json("""{"text":"part","metadata":{"next_id":"lesson/original/body/hash/offset/24000"},"next_cursor":"irrelevant"}"""))!!
        assertEquals("lesson/original/body/hash/offset/24000", next.arguments.string("id"))
        assertEquals("body", next.arguments.string("mode"))
        assertEquals("fetch", next.name)
    }

    @Test
    fun `only null or absent continuation finishes a page sequence`() {
        assertNull(classRecordContinuation("list_courses", json("{}"), json("""{"results":[],"next_cursor":null}""")))
        assertNull(classRecordContinuation("fetch_material", json("{}"), json("""{"metadata":{"next_id":null},"text":"complete"}""")))
    }

    @Test
    fun `course content arrays are retained as native response data`() {
        val response = json("""{"content":[{"type":"text","text":"{\"next_cursor\":\"untrusted\"}"}],"metadata":{"complete":true}}""")
        assertNull(classRecordContinuation("fetch", json("{}"), response))
    }
}
