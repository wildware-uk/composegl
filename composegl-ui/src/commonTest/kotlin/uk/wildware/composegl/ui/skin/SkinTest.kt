package uk.wildware.composegl.ui.skin

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.layout.Padding
import uk.wildware.composegl.ui.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Appearance as data.
 *
 * Two things are being checked, over and over. A widget must be able to draw entirely from a
 * resolved style, so every question has an answer by the time it gets one; and a skin that has not
 * been finished must still draw, because a half-written skin is the normal state of affairs for
 * weeks at a time.
 */
class SkinTest {

    private val boxStyle = Style(
        base = StateStyle(
            background = SkinDrawable.Fill(Colour.rgb(0x202020), corner = 4f),
            textColour = Colour.rgb(0xCCCCCC),
            padding = Padding.all(8f),
        ),
        hovered = StateStyle(textColour = Colour.White),
        pressed = StateStyle(contentOffset = Offset(0f, 1f)),
        disabled = StateStyle(tint = Colour.argb(0x80FFFFFF)),
    )

    private val skin = Skin(styles = mapOf("button" to boxStyle))

    @Test
    fun `a resolved style has an answer to everything`() {
        val style = skin.resolve("button")

        assertEquals(Colour.rgb(0xCCCCCC), style.textColour)
        assertEquals(Padding.all(8f), style.padding)
        assertEquals(Colour.White, style.tint, "no tint means no tint, not no answer")
        assertEquals(TextStyle.Default, style.textStyle)
        assertEquals(Offset.Zero, style.contentOffset)
    }

    @Test
    fun `a state changes what it names and nothing else`() {
        val style = skin.resolve("button", setOf(WidgetState.Hovered))

        assertEquals(Colour.White, style.textColour, "hovering said this")
        assertEquals(Padding.all(8f), style.padding, "and said nothing about this")
        assertEquals(
            SkinDrawable.Fill(Colour.rgb(0x202020), corner = 4f),
            style.background,
            "a state a skin barely mentions still draws the base",
        )
    }

    @Test
    fun `states overlap and all of them apply`() {
        val style = skin.resolve("button", setOf(WidgetState.Hovered, WidgetState.Pressed))

        assertEquals(Colour.White, style.textColour, "still hovered")
        assertEquals(Offset(0f, 1f), style.contentOffset, "and also held down")
    }

    @Test
    fun `disabled has the last word`() {
        val dimmed = Style(
            base = StateStyle(tint = Colour.White),
            hovered = StateStyle(tint = Colour.rgb(0xFF0000)),
            disabled = StateStyle(tint = Colour.argb(0x40FFFFFF)),
        )
        val style = dimmed.resolve(setOf(WidgetState.Hovered, WidgetState.Disabled))

        assertEquals(
            Colour.argb(0x40FFFFFF),
            style.tint,
            "a disabled button is not hovered, whatever the pointer is doing",
        )
    }

    @Test
    fun `a style nobody wrote still draws`() {
        val style = Skin().resolve("button", setOf(WidgetState.Pressed))

        assertEquals(SkinDrawable.Blank, style.background)
        assertEquals(Colour.White, style.textColour, "plain, readable, and obviously nobody's choice")
    }

    @Test
    fun `a variant falls back to the style it is a variant of`() {
        assertSame(boxStyle, skin.style("button.danger"), "a game may name a variant before an artist draws one")
        assertFalse(skin.has("button.danger"))
        assertTrue(skin.has("button"))
    }

    @Test
    fun `a long name gives up one dot at a time`() {
        val specific = Style(base = StateStyle(textColour = Colour.Black))
        val nested = Skin(styles = mapOf("button" to boxStyle, "button.danger" to specific))

        assertSame(specific, nested.style("button.danger.small"))
        assertSame(boxStyle, nested.style("button.quiet.small"))
        assertSame(Skin.Empty, nested.style("slider"))
    }

    @Test
    fun `the skin's defaults fill in what no style mentions`() {
        val body = TextStyle(family = "body", size = 18f)
        val dark = Skin(
            styles = mapOf("label" to Style()),
            defaults = ResolvedStyle.Plain.copy(textStyle = body, textColour = Colour.rgb(0x9AA4B2)),
        )

        val style = dark.resolve("label")
        assertEquals(body, style.textStyle, "a skin names the game's font once, not in forty styles")
        assertEquals(Colour.rgb(0x9AA4B2), style.textColour)
    }

    @Test
    fun `an override changes one field and keeps the rest`() {
        val red = Skin(
            styles = mapOf(
                "button" to Style(hovered = StateStyle(textColour = Colour.rgb(0xFF5C5C))),
            ),
        )
        val screen = skin.overriddenWith(red)

        val style = screen.resolve("button", setOf(WidgetState.Hovered))
        assertEquals(Colour.rgb(0xFF5C5C), style.textColour, "the screen said this")
        assertEquals(Padding.all(8f), style.padding, "and the skin still says the rest")
    }

    @Test
    fun `an override that says nothing is the skin itself`() {
        assertSame(skin, skin.overriddenWith(Skin()), "a subtree with no overrides costs nothing")
    }

    @Test
    fun `an override keeps the defaults underneath it unless it has its own`() {
        val base = Skin(defaults = ResolvedStyle.Plain.copy(textColour = Colour.Black))

        val quiet = base.overriddenWith(Skin(styles = mapOf("label" to Style())))
        assertEquals(Colour.Black, quiet.resolve("label").textColour)

        val loud = base.overriddenWith(Skin(defaults = ResolvedStyle.Plain.copy(textColour = Colour.White)))
        assertEquals(Colour.White, loud.resolve("label").textColour)
    }

    @Test
    fun `padding comes from the art rather than from the code`() {
        // The point of keeping padding on the drawable: change the picture and the gap between the
        // bevel and the label changes with it, without anybody editing a layout.
        val patch = SkinDrawable.Fill(Colour.White, corner = 6f, padding = Padding.all(10f))

        assertEquals(Padding.all(10f), patch.padding)
    }
}
