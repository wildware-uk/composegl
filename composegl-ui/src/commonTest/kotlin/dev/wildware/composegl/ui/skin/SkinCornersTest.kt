package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.Colour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The three ways a skin file writes a corner, and what it says when one is wrong. */
class SkinCornersTest {

    private fun fillOf(corner: String): SkinDrawable.Fill {
        val skin = SkinFormat.read("""{ "styles": { "tab": { "background": { "fill": "#202830", "corner": $corner } } } }""")
        return skin.resolve("tab", emptySet()).background as SkinDrawable.Fill
    }

    @Test
    fun `one number rounds all four corners the same`() {
        assertEquals(Corners.all(6f), fillOf("6").corners)
    }

    @Test
    fun `four numbers go clockwise from the top-left`() {
        assertEquals(Corners(topLeft = 8f, topRight = 7f, bottomRight = 1f, bottomLeft = 0f), fillOf("[8, 7, 1, 0]").corners)
    }

    @Test
    fun `an object names only the corners that are rounded`() {
        assertEquals(Corners.top(8f), fillOf("""{ "topLeft": 8, "topRight": 8 }""").corners)
    }

    @Test
    fun `a fill with no corner is square`() {
        val skin = SkinFormat.read("""{ "styles": { "tab": { "background": { "fill": "#202830" } } } }""")

        assertEquals(Corners.None, (skin.resolve("tab", emptySet()).background as SkinDrawable.Fill).corners)
    }

    @Test
    fun `a gradient takes a corner the same three ways a fill does`() {
        val skin = SkinFormat.read(
            """{ "styles": { "tab": { "background": { "gradient": { "vertical": ["#202830", "#101418"] }, "corner": { "topLeft": 8, "topRight": 8 } } } } }""",
        )
        val gradient = skin.resolve("tab", emptySet()).background as SkinDrawable.Gradient

        assertEquals(Corners.top(8f), gradient.corners)

        val written = SkinFormat.write(skin)
        assertTrue("\"corner\": [8, 8, 0, 0]" in written, written)
        assertEquals(skin.styles, SkinFormat.read(written).styles)
    }

    @Test
    fun `two numbers are refused rather than guessed at`() {
        val problem = assertFailsWith<SkinFormatException> { fillOf("[8, 0]") }

        assertTrue("line 1" in problem.message.orEmpty(), problem.message)
        assertTrue("[topLeft, topRight, bottomRight, bottomLeft]" in problem.message.orEmpty(), problem.message)
    }

    @Test
    fun `a corner the format does not have suggests the one it meant`() {
        val problem = assertFailsWith<SkinFormatException> { fillOf("""{ "topleft": 8 }""") }

        assertTrue("\"topLeft\"" in problem.message.orEmpty(), problem.message)
    }

    @Test
    fun `a negative radius stops the load on its line`() {
        val problem = assertFailsWith<SkinFormatException> { fillOf("[8, 8, -1, 0]") }

        assertTrue(problem.message.orEmpty().startsWith("line 1:"), problem.message)
        assertTrue("negative" in problem.message.orEmpty(), problem.message)
    }

    @Test
    fun `a text corner says what a corner looks like`() {
        val problem = assertFailsWith<SkinFormatException> { fillOf("\"round\"") }

        assertTrue("a corner is a number" in problem.message.orEmpty(), problem.message)
    }

    @Test
    fun `corners that differ survive a trip out to text and back`() {
        val skin = Skin(
            styles = mapOf(
                "tab" to Style(
                    base = StateStyle(
                        background = SkinDrawable.Fill(Colour.rgb(0x202830), Corners.top(8f), border = Colour.White),
                    ),
                    hovered = StateStyle(background = SkinDrawable.Fill(Colour.rgb(0x303840), corner = 5f)),
                ),
            ),
        )

        val written = SkinFormat.write(skin)
        val again = SkinFormat.read(written)

        assertEquals(skin.styles, again.styles)
        assertTrue("\"corner\": [8, 8, 0, 0]" in written, written)
        assertTrue("\"corner\": 5" in written, "one radius is still written as one number: $written")
    }
}
