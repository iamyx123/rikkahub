package me.rerere.rikkahub.data.classrecord

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.applyClassRecordDefaults
import org.junit.Assert.*
import org.junit.Test

class ClassRecordDefaultClassTest {
    private val schema = buildJsonObject { put("properties", buildJsonObject {
        put("school_id", buildJsonObject { put("type", "string") }); put("class_id", buildJsonObject { put("type", "string") })
    }) }
    private fun apply(name: String = "search", input: JsonObject = buildJsonObject {}) =
        applyClassRecordDefaults(name, input, schema, "school-a", "class-a")

    @Test fun `omitted classroom uses the account default`() {
        assertEquals("school-a", apply()["school_id"]?.jsonPrimitive?.content)
        assertEquals("class-a", apply()["class_id"]?.jsonPrimitive?.content)
    }

    @Test fun `explicit class and school override defaults without crossing schools`() {
        val other = apply(input = buildJsonObject { put("class_id", "class-b") })
        assertEquals("class-b", other["class_id"]?.jsonPrimitive?.content)
        assertNull(other["school_id"])
        val school = apply(input = buildJsonObject { put("school_id", "school-b") })
        assertNull(school["class_id"])
        assertEquals("school-b", school["school_id"]?.jsonPrimitive?.content)
    }

    @Test fun `class picker and exact document ids are never silently narrowed`() {
        assertTrue(apply("list_classes").isEmpty())
        val document = buildJsonObject { put("id", "material/id") }
        assertEquals(document, applyClassRecordDefaults("fetch_material", document,
            buildJsonObject { put("properties", buildJsonObject { put("id", buildJsonObject {}) }) }, "school-a", "class-a"))
        val lesson = buildJsonObject { put("lesson_id", "lesson-in-another-class") }
        assertEquals(lesson, apply("list_transcripts", lesson))
        assertEquals(lesson, apply("list_materials", lesson))
    }

    @Test fun `batch defaults are applied to lookup leaves and exact fetch ids stay intact`() {
        val input = buildJsonObject { putJsonArray("requests") {
            add(buildJsonObject { put("tool", "list_materials"); put("arguments", buildJsonObject { put("query", "力学") }) })
            add(buildJsonObject { put("tool", "fetch"); put("arguments", buildJsonObject { put("id", "course/id") }) })
        } }
        val result = apply("search_batch", input)["requests"]!!.jsonArray
        assertEquals("class-a", result[0].jsonObject["arguments"]!!.jsonObject["class_id"]!!.jsonPrimitive.content)
        assertNull(result[1].jsonObject["arguments"]!!.jsonObject["class_id"])
    }
}
