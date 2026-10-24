package me.rerere.rikkahub.data.classrecord

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.isClassRecordMcpUrl
import me.rerere.rikkahub.data.ai.tools.isPagedClassRecordTool
import org.junit.Assert.*
import org.junit.Test

class ClassRecordReadSessionTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun `parallel identical queries execute once regardless of property ordering`() = runBlocking {
        val session = ClassRecordReadSession()
        var calls = 0
        val first = async { session.read("list_courses", obj("""{"class_id":"15","limit":5}""")) {
            calls++; delay(20); obj("""{"results":[{"id":"lesson/one"}]}""")
        } }
        val second = async { session.read("list_courses", obj("""{"limit":5,"class_id":"15"}""")) {
            calls++; error("duplicate network call")
        } }
        assertTrue(first.await().jsonObject.containsKey("results"))
        assertTrue(second.await().jsonObject["already_read"]!!.jsonPrimitive.boolean)
        assertEquals(1, calls)
    }

    @Test fun `different filters and new generations can read new evidence`() = runBlocking {
        val session = ClassRecordReadSession()
        var calls = 0
        suspend fun read(s: ClassRecordReadSession, id: String) = s.read("fetch", obj("""{"id":"$id"}""")) { calls++; obj("{}") }
        read(session, "one"); read(session, "two"); read(ClassRecordReadSession(), "one")
        assertEquals(3, calls)
    }

    @Test fun `management changes invalidate old reads before verifying persisted results`() = runBlocking {
        val session = ClassRecordReadSession()
        var calls = 0
        suspend fun read() = session.read("fetch", obj("""{"id":"same-lesson"}""")) { calls++; obj("{}") }
        read(); session.invalidate(); read()
        assertEquals(2, calls)
    }

    @Test fun `invalid query is reported once instead of repeatedly requesting a default catalog`() = runBlocking {
        val session = ClassRecordReadSession()
        var calls = 0
        val args = obj("""{"lessonId":"invalid"}""")
        val first = session.read("list_materials", args) { calls++; error("unknown lessonId; use lesson_id") }
        val second = session.read("list_materials", args) { calls++; obj("{}") }
        assertTrue(first.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertTrue(second.jsonObject["already_read"]!!.jsonPrimitive.boolean)
        assertEquals(1, calls)
    }

    @Test fun `reader assembles complete unicode JSON pages in a single model tool call`() = runBlocking {
        val text = """{"transcripts":[{"text":"中文😀原话"}],"readiness":{"evidence_complete":false}}"""
        val pages = listOf(text.take(18), text.drop(18))
        var calls = 0
        val result = readClassRecordPages("get_lesson_context", obj("""{"id":"lesson/one"}"""), { _, args ->
            val index = if (args.crString("id") == "lesson/one") 0 else 1
            calls++
            buildJsonObject {
                put("id", "lesson/one"); put("text", pages[index])
                putJsonObject("metadata") {
                    put("content_sha256", "stable"); put("offset", if (index == 0) 0 else 18)
                    put("complete", index == 1); put("next_id", if (index == 0) JsonPrimitive("next") else JsonNull)
                }
            }
        }).jsonObject
        assertEquals(Json.parseToJsonElement(text), result["content"])
        assertEquals(2, calls)
        assertEquals(2, result["metadata"]!!.jsonObject["pages_read"]!!.jsonPrimitive.int)
        assertFalse(result.containsKey("text"))
    }

    @Test fun `bounded reader keeps a continuation when the lesson exceeds its output budget`() = runBlocking {
        var calls = 0
        val result = readClassRecordPages("fetch", obj("""{"id":"one"}"""), { _, _ ->
            calls++
            obj("""{"id":"one","text":"long source","metadata":{"content_sha256":"x","offset":0,"complete":false,"next_id":"next"}}""")
        }, maxCharacters = 5).jsonObject
        assertEquals(1, calls)
        assertEquals("next", result["metadata"]!!.jsonObject.crString("next_id"))
        assertEquals("long source", result.crString("text"))
    }

    @Test fun `changed hashes never mix source revisions`() = runBlocking {
        var calls = 0
        try {
            readClassRecordPages("fetch_material", obj("""{"id":"one"}"""), { _, _ ->
                calls++
                obj("""{"text":"abc","metadata":{"content_sha256":"$calls","offset":${if (calls == 1) 0 else 3},"complete":${calls > 1},"next_id":${if (calls == 1) "\"two\"" else "null"}}}""")
            })
            fail("mixed revisions were accepted")
        } catch (_: IllegalStateException) { assertEquals(2, calls) }
    }

    @Test fun `efficient defaults preserve explicit full archives and continuation modes`() {
        val schema = obj("""{"properties":{"mode":{},"view":{},"limit":{}}}""")
        assertEquals("body", efficientClassRecordArguments("fetch", obj("""{"id":"lesson/id/revision/1"}"""), schema).crString("mode"))
        assertEquals("full", efficientClassRecordArguments("fetch", obj("""{"id":"lesson/id/revision/1","mode":"full"}"""), schema).crString("mode"))
        assertFalse(efficientClassRecordArguments("fetch", obj("""{"id":"lesson/id/revision/1/content/hash/offset/10"}"""), schema).containsKey("mode"))
        assertEquals("compact", efficientClassRecordArguments("list_transcripts", obj("{}"), schema).crString("view"))
        assertEquals(10, efficientClassRecordArguments("list_materials", obj("{}"), schema)["limit"]!!.jsonPrimitive.int)
    }

    @Test fun `paged classroom sources bypass shell truncation and unrelated MCP tools do not`() {
        assertTrue(isPagedClassRecordTool("classrecord__get_lesson_context"))
        assertTrue(isPagedClassRecordTool("classrecord__fetch_material"))
        assertFalse(isPagedClassRecordTool("classrecord_console"))
        assertFalse(isPagedClassRecordTool("unrelated__fetch"))
        assertTrue(isClassRecordMcpUrl("https://classroom.iamyx.co/mcp"))
        assertFalse(isClassRecordMcpUrl("https://classroom.iamyx.co.evil.test/mcp"))
        assertFalse(isClassRecordMcpUrl("https://classroom.iamyx.co/other"))
    }
}
