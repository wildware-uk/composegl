package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.graphics.Colour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a skin is called, so an options menu can list skins without a second list of names.
 */
class SkinNameTest {

    @Test
    fun `a skin file can say what it is called`() {
        val skin = SkinFormat.read("""{ "name": "Colourblind", "styles": {} }""")

        assertEquals("Colourblind", skin.name)
    }

    @Test
    fun `a skin file that says nothing is unnamed`() {
        assertEquals("", SkinFormat.read("""{ "styles": {} }""").name)
    }

    @Test
    fun `a name that is not text is an error with its line`() {
        val problem = assertFailsWith<SkinFormatException> { SkinFormat.read("{\n  \"name\": 3\n}") }

        assertTrue("\"name\"" in problem.message.orEmpty(), problem.message.orEmpty())
    }

    @Test
    fun `a name is written back out`() {
        val skin = Skin(name = "Dark \"night\" mode")

        assertEquals(skin.name, SkinFormat.read(SkinFormat.write(skin)).name)
    }

    @Test
    fun `an unnamed skin writes no name`() {
        assertTrue("\"name\"" !in SkinFormat.write(Skin()))
    }

    @Test
    fun `an override that names itself renames the result and one that does not keeps the name`() {
        val red = Skin(styles = mapOf("button" to Style(base = StateStyle(textColour = Colour.rgb(0xFF0000)))))

        assertEquals("Standard", Skin.Default.overriddenWith(red).name)
        assertEquals("Large text", Skin.Default.overriddenWith(Skin(name = "Large text")).name)
    }

    @Test
    fun `a skin says its name when printed`() {
        assertTrue("High contrast" in Skin.HighContrast.toString())
        assertTrue("unnamed" in Skin().toString())
    }
}
