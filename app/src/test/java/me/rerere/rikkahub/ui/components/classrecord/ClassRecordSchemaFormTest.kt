package me.rerere.rikkahub.ui.components.classrecord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassRecordSchemaFormTest {
    private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `invalid nested draft stays visible and prevents sending its old valid value`() {
        val initial = schema("""{"classId":"class-a"}""")
        val draft = ClassRecordJsonDraft.fromForm(initial).edit("{\"classId\":")
        assertEquals("{\"classId\":", draft.text)
        assertEquals(initial, draft.value)
        assertNotNull(draft.error)
        val validation = ClassRecordFormValidationState()
        validation.update("nested field", draft.error)
        assertNotNull(classRecordRequestDraftError(null, validation.error, draft, editingRawBody = false))
        val fixed = draft.edit("""{"classId":"class-b"}""")
        validation.update("nested field", fixed.error)
        assertNull(classRecordRequestDraftError(null, validation.error, fixed, editingRawBody = false))
        assertEquals(schema("""{"classId":"class-b"}"""), fixed.value)
    }

    @Test
    fun `clearing one nested error cannot clear another invalid editor`() {
        val validation = ClassRecordFormValidationState()
        validation.update("first", "first is invalid")
        validation.update("second", "second is invalid")
        validation.update("first", null)
        assertEquals("second is invalid", validation.error)
        validation.update("second", null)
        assertNull(validation.error)
    }

    @Test
    fun `native form edit replaces a stale raw error without losing the active draft early`() {
        val invalid = ClassRecordJsonDraft.fromForm(schema("""{"enabled":true}""")).edit("{broken")
        assertNotNull(classRecordRequestDraftError(null, null, invalid, editingRawBody = true))
        assertNull(classRecordRequestDraftError(null, null, invalid, editingRawBody = false))
        assertEquals("{broken", invalid.text)
        val formEdit = ClassRecordJsonDraft.fromForm(schema("""{"enabled":false}"""))
        assertNull(formEdit.error)
        assertEquals(schema("""{"enabled":false}"""), formEdit.value)
        assertNull(classRecordRequestDraftError(null, null, formEdit, editingRawBody = true))
        assertEquals("parameter draft is invalid", classRecordRequestDraftError("parameter draft is invalid", null, formEdit, false))
    }

    @Test
    fun `blank optional JSON clears its value but required JSON remains invalid`() {
        val initial = ClassRecordJsonDraft.fromForm(schema("{}"))
        assertNull(initial.edit("", required = false).value)
        assertNull(initial.edit("", required = false).error)
        assertNotNull(initial.edit("", required = true).error)
    }

    @Test
    fun `references and nullable nested arrays retain concrete input types`() {
        val result = resolveClassRecordSchema(schema("""
            {
              "type":"object",
              "properties":{"classes":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/Class"}}},
              "${'$'}defs":{"Class":{"type":"object","required":["limit"],"properties":{
                "limit":{"type":["integer","null"],"minimum":1,"maximum":100},
                "enabled":{"type":"boolean"}
              }}}
            }
        """))
        val item = result["properties"].objectOrEmpty()["classes"].objectOrEmpty()["items"].objectOrEmpty()
        assertEquals("integer", item["properties"].objectOrEmpty()["limit"].objectOrEmpty().string("type"))
        assertNull(validateClassRecordSchema(result, schema("""{"classes":[{"limit":null,"enabled":true}]}""")))
        assertNotNull(validateClassRecordSchema(result, schema("""{"classes":[{"limit":"20"}]}""")))
        assertNotNull(validateClassRecordSchema(result, schema("""{"classes":[{"limit":101}]}""")))
    }

    @Test
    fun `reference resolution preserves literal defaults enums and property names`() {
        val raw = schema("""
            {"type":"object","properties":{
              "default":{"${'$'}ref":"#/${'$'}defs/Value"},
              "value":{"type":"object","default":{"${'$'}ref":"literal"},"enum":[{"${'$'}ref":"literal"}]}
            },"${'$'}defs":{"Value":{"type":"string","enum":["one","two"]}}}
        """)
        val resolved = resolveClassRecordSchema(raw)["properties"].objectOrEmpty()
        assertEquals("string", resolved["default"].objectOrEmpty().string("type"))
        assertEquals(raw["properties"].objectOrEmpty()["value"], resolved["value"])
    }

    @Test
    fun `cyclic references terminate with editable object schema`() {
        val result = resolveClassRecordSchema(schema("""
            {"type":"object","properties":{"node":{"${'$'}ref":"#/${'$'}defs/Node"}},
             "${'$'}defs":{"Node":{"type":"object","properties":{"next":{"${'$'}ref":"#/${'$'}defs/Node"}}}}}
        """))
        val nested = result["properties"].objectOrEmpty()["node"].objectOrEmpty()["properties"].objectOrEmpty()["next"].objectOrEmpty()
        assertEquals("object", nested.string("type"))
        assertTrue(nested["properties"].objectOrEmpty().isEmpty())
    }

    @Test
    fun `required nested properties and explicit null are validated independently`() {
        val inputSchema = schema("""
            {"type":"object","required":["scope"],"properties":{"scope":{
              "type":"object","required":["classId"],"additionalProperties":false,
              "properties":{"classId":{"type":"string","minLength":1}}
            }}}
        """)
        assertNotNull(validateClassRecordSchema(inputSchema, schema("{}")))
        assertNotNull(validateClassRecordSchema(inputSchema, schema("""{"scope":{}}""")))
        assertNotNull(validateClassRecordSchema(inputSchema, schema("""{"scope":{"classId":null}}""")))
        assertNotNull(validateClassRecordSchema(inputSchema, schema("""{"scope":{"classId":"x","typo":true}}""")))
        assertNull(validateClassRecordSchema(inputSchema, schema("""{"scope":{"classId":"x"}}""")))
    }

    @Test
    fun `nullable variants cannot accidentally accept wrong primitive types`() {
        val inputSchema = schema("""{"anyOf":[{"type":"integer","minimum":1},{"type":"null"}]}""")
        assertNull(validateClassRecordSchema(inputSchema, JsonNull))
        assertNull(validateClassRecordSchema(inputSchema, JsonPrimitive(2)))
        assertNotNull(validateClassRecordSchema(inputSchema, JsonPrimitive("two")))
        assertNotNull(validateClassRecordSchema(inputSchema, JsonPrimitive(true)))
    }

    @Test
    fun `array item bounds enums and boolean types are checked`() {
        val inputSchema = schema("""
            {"type":"array","minItems":1,"maxItems":2,"items":{
              "type":"object","required":["role","enabled"],"properties":{
                "role":{"type":"string","enum":["teacher","student"]},"enabled":{"type":"boolean"}
              }
            }}
        """)
        assertNotNull(validateClassRecordSchema(inputSchema, Json.parseToJsonElement("[]")))
        assertNotNull(validateClassRecordSchema(inputSchema, Json.parseToJsonElement("""[{"role":"admin","enabled":true}]""")))
        assertNotNull(validateClassRecordSchema(inputSchema, Json.parseToJsonElement("""[{"role":"teacher","enabled":"true"}]""")))
        assertNull(validateClassRecordSchema(inputSchema, Json.parseToJsonElement("""[{"role":"teacher","enabled":true}]""")))
    }
}
