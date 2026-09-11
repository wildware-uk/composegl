package dev.wildware.composegl.ui.skin.json

import dev.wildware.composegl.ui.skin.SkinFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Enough JSON to read a skin file, and a complaint that names the line when it is not. */
class JsonReaderTest {

    private fun read(text: String) = JsonReader.read(text)

    private fun fields(text: String) = (read(text) as JsonObject).fields

    @Test
    fun `an object of the ordinary kinds of value`() {
        val json = fields(
            """
            { "name": "button", "size": 12.5, "on": true, "off": false, "nothing": null }
            """.trimIndent(),
        )

        assertEquals("button", (json["name"] as JsonText).value)
        assertEquals(12.5, (json["size"] as JsonNumber).value)
        assertEquals(true, (json["on"] as JsonBool).value)
        assertEquals(false, (json["off"] as JsonBool).value)
        assertTrue(json["nothing"] is JsonNull)
    }

    @Test
    fun `nesting and lists and negative numbers`() {
        val json = fields("""{ "style": { "offset": [0, -1.5] } }""")
        val offset = ((json["style"] as JsonObject)["offset"] as JsonArray).items

        assertEquals(0.0, (offset[0] as JsonNumber).value)
        assertEquals(-1.5, (offset[1] as JsonNumber).value)
    }

    @Test
    fun `every value remembers the line it was written on`() {
        val json = fields(
            """
            {
              "first": 1,

              "third": 3
            }
            """.trimIndent(),
        )

        assertEquals(2, json.getValue("first").line)
        assertEquals(4, json.getValue("third").line, "the blank line counts, because an editor shows it")
    }

    @Test
    fun `comments and a trailing comma because a person is typing this`() {
        val json = fields(
            """
            {
              // the normal state
              "colour": "#FFFFFF", /* and a note
                                      that runs on */
              "size": 12,
            }
            """.trimIndent(),
        )

        assertEquals(setOf("colour", "size"), json.keys)
    }

    @Test
    fun `escapes inside a piece of text`() {
        val json = fields("""{ "text": "a \"quoted\" word\nand a tab\there, é" }""")

        assertEquals("a \"quoted\" word\nand a tab\there, é", (json["text"] as JsonText).value)
    }

    @Test
    fun `an empty object and an empty list`() {
        val json = fields("""{ "styles": {}, "names": [] }""")

        assertEquals(emptyMap<String, Json>(), (json["styles"] as JsonObject).fields)
        assertEquals(emptyList<Json>(), (json["names"] as JsonArray).items)
    }

    @Test
    fun `a missing brace says which line the object started on`() {
        val problem = assertFailsWith<SkinFormatException> {
            read(
                """
                {
                  "styles": {
                    "button": { "textColour": "#FFFFFF" }
                }
                """.trimIndent(),
            )
        }

        assertTrue("line 1" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a missing comma is reported where it is missing`() {
        val problem = assertFailsWith<SkinFormatException> {
            read(
                """
                {
                  "a": 1
                  "b": 2
                }
                """.trimIndent(),
            )
        }

        assertTrue("line 3" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a name written twice is a mistake rather than a silent winner`() {
        val problem = assertFailsWith<SkinFormatException> {
            read("""{ "colour": "#FFFFFF", "colour": "#000000" }""")
        }

        assertTrue("twice" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `anything after the end of the value is a mistake`() {
        assertFailsWith<SkinFormatException> { read("""{ "a": 1 } { "b": 2 }""") }
    }

    @Test
    fun `text cannot run across two lines`() {
        val problem = assertFailsWith<SkinFormatException> { read("{ \"a\": \"one\ntwo\" }") }

        assertTrue("\\n" in problem.message.orEmpty(), problem.message.orEmpty())
    }
}
