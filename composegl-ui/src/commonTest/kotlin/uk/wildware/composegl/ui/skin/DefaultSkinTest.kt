package uk.wildware.composegl.ui.skin

import uk.wildware.composegl.ui.graphics.Colour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The skin a game gets for free.
 *
 * It is a file, not code, so this is the thing that catches a typo in it: a skin file that will not
 * parse is a toolkit that will not start, and finding that out in a test beats finding it out in
 * somebody's game.
 */
class DefaultSkinTest {

    private val skin = Skin.Default

    @Test
    fun `it is a file that reads`() {
        assertTrue(skin.styles.size > 20, "a skin for a whole interface, not a sample")
    }

    @Test
    fun `everything a widget set will ask for is in it`() {
        val expected = listOf(
            "screen", "panel", "separator", "tooltip",
            "label", "button", "button.primary", "button.danger", "button.quiet",
            "checkbox", "radio", "slider.track", "slider.knob", "progress.track", "progress.fill",
            "field", "item", "scrollbar.thumb", "focusRing",
        )

        val missing = expected.filterNot { skin.has(it) }

        assertEquals(emptyList(), missing, "styles the shipped skin does not name")
    }

    @Test
    fun `a button says something different in each of its states`() {
        val normal = skin.resolve("button")
        val hovered = skin.resolve("button", setOf(WidgetState.Hovered))
        val pressed = skin.resolve("button", setOf(WidgetState.Pressed))
        val disabled = skin.resolve("button", setOf(WidgetState.Disabled))

        assertNotEquals(normal.background, hovered.background, "a pointer over it does something")
        assertNotEquals(normal.contentOffset, pressed.contentOffset, "and holding it moves the label")
        assertNotEquals(normal.textColour, disabled.textColour, "and an unavailable button looks it")
    }

    @Test
    fun `focus is visible because on a console it is the only cursor there is`() {
        val focused = skin.resolve("button", setOf(WidgetState.Focused)).background as SkinDrawable.Fill

        assertNotEquals(null, focused.border, "a focused button is outlined")
        assertTrue(focused.borderWidth > 0f)
    }

    @Test
    fun `it needs no art and no atlas`() {
        val art = skin.styles.values
            .flatMap { listOf(it.base, it.hovered, it.focused, it.pressed, it.disabled) }
            .mapNotNull { it?.background }
            .filter { it is SkinDrawable.Patch || it is SkinDrawable.Image }

        assertEquals(emptyList(), art, "a game with no artist yet still has an interface")
    }

    @Test
    fun `text is readable against the screen it is drawn on`() {
        val screen = (skin.resolve("screen").background as SkinDrawable.Fill).colour
        val text = skin.resolve("label").textColour

        assertTrue(brightness(text) - brightness(screen) > 0.5f, "light text on a dark screen")
    }

    @Test
    fun `a widget nobody wrote a style for still draws`() {
        val style = skin.resolve("inventory.slot.rare")

        assertEquals(SkinDrawable.Blank, style.background)
        assertEquals(Colour.rgb(0xE8ECF2), style.textColour, "the skin's own text colour, not white")
    }

    @Test
    fun `the shipped skin round-trips like any other`() {
        val again = SkinFormat.read(SkinFormat.write(skin))

        assertEquals(skin.styles, again.styles)
        assertEquals(skin.defaults, again.defaults)
    }

    private fun brightness(colour: Colour) =
        (colour.red * 0.299f + colour.green * 0.587f + colour.blue * 0.114f) / 255f
}
