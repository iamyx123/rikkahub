package me.rerere.rikkahub.data.intelligentui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveUiChartDataTest {
    private fun props(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `fixed axes make a parameter change visibly move the parabola`() {
        val first=props("""{"data":[{"x":0,"y":0},{"x":1,"y":1},{"x":2,"y":4}],"series":["y"],"yMin":-16,"yMax":16}""")
        val next=props("""{"data":[{"x":0,"y":0},{"x":1,"y":2},{"x":2,"y":8}],"series":["y"],"yMin":-16,"yMax":16}""")
        val before=interactiveChartDomain(first)
        val after=interactiveChartDomain(next)
        assertEquals(before,after)
        assertNotEquals(before.fraction(4.0),after.fraction(8.0))
        assertEquals(.625f,before.fraction(4.0),0.00001f)
        assertEquals(.75f,after.fraction(8.0),0.00001f)
    }

    @Test
    fun `legacy auto domains and constant data stay finite`() {
        val auto=interactiveChartDomain(props("""{"data":[{"y":-2},{"y":5}],"series":["y"]}"""))
        assertEquals(-2.0,auto.minimum,0.0)
        assertEquals(5.0,auto.maximum,0.0)
        val zero=interactiveChartDomain(props("""{"data":[{"y":0}],"series":["y"]}"""))
        assertTrue(zero.fraction(0.0).isFinite())
        assertTrue(zero.maximum>zero.minimum)
    }
}
