package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.graphics.Colour
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The high-contrast skin a game gets for free, checked against what it promises.
 *
 * Three promises. It names everything the default names, so no widget falls back to plain in it.
 * It lays out exactly like the default, so switching from an options screen does not move the
 * control the player is standing on. And it is actually high contrast, measured the way the web's
 * accessibility guidelines measure it rather than judged by eye.
 */
class HighContrastSkinTest {

    private val skin = Skin.HighContrast

    private val states = listOf(emptySet<WidgetState>()) + WidgetState.entries.map { setOf(it) }

    @Test
    fun `it names every style the default skin names`() {
        assertEquals(Skin.Default.styles.keys, skin.styles.keys)
    }

    @Test
    fun `every style is the same size and shape as the default's in every state`() {
        val moved = Skin.Default.styles.keys.flatMap { name ->
            states.mapNotNull { state ->
                val standard = Skin.Default.resolve(name, state)
                val contrast = skin.resolve(name, state)
                val same = standard.padding == contrast.padding &&
                    standard.background.padding == contrast.background.padding &&
                    standard.textStyle == contrast.textStyle &&
                    standard.contentOffset == contrast.contentOffset
                if (same) null else "$name $state"
            }
        }

        assertEquals(emptyList(), moved, "styles that would move something when the skin is switched")
    }

    /** The styles a widget writes words in, each with the style its words sit on when that is another. */
    private val text = mapOf(
        "label" to null, "label.title" to null, "label.heading" to null, "label.dim" to null,
        "label.danger" to null, "label.good" to null,
        "button" to null, "button.primary" to null, "button.danger" to null, "button.quiet" to null,
        "button.listening" to null, "button.key" to null, "button.key.on" to null, "dropdown" to null,
        "collapsingheader" to null, "collapsingheader.open" to null,
        "tooltip" to null, "notification" to null, "notification.detail" to "notification",
        "notification.more" to "notification", "minimap.compass" to "minimap", "prompt" to null,
        "stepper.arrow" to "stepper", "stepper.value" to "stepper",
        "field" to null, "field.placeholder" to "field",
        "item" to null, "item.selected" to null, "tab" to null, "tab.selected" to null,
        "table.header.cell" to "table.header", "table.header.cell.sorted" to "table.header",
        "table.row" to "table", "table.row.alt" to "table", "table.row.selected" to "table", "table.empty" to "table",
        "tree.row" to null, "tree.row.selected" to null,
        "hotbar.prompt" to "hotbar.slot", "hotbar.charges" to "hotbar.slot",
        "damage" to null, "damage.critical" to null,
    )

    @Test
    fun `every piece of text stands out from what it is drawn on`() {
        val screen = fillOf(skin.resolve("screen"))!!
        val faint = text.flatMap { (name, on) ->
            // Disabled text is meant to recede, and is held to the lower bar below.
            states.filter { WidgetState.Disabled !in it }.mapNotNull { state ->
                val style = skin.resolve(name, state)
                val behind = fillOf(style)?.takeIf { it.alpha == 0xFF }
                    ?: on?.let { fillOf(skin.resolve(it, state)) }
                    ?: screen
                val ratio = contrast(style.textColour, behind)
                if (ratio >= 4.5f) null else "$name $state: ${ratio.format()}"
            }
        }

        assertEquals(emptyList(), faint, "text under the 4.5:1 the accessibility guidelines ask for")
    }

    @Test
    fun `and the text most of the screen is written in is as far from its background as it can be`() {
        val screen = fillOf(skin.resolve("screen"))!!
        val body = skin.resolve("label").textColour

        assertEquals(21f, contrast(body, screen), 0.01f, "white on black")
        assertTrue(
            contrast(body, screen) > contrast(Skin.Default.resolve("label").textColour, fillOf(Skin.Default.resolve("screen"))!!),
            "and further than the standard skin, or it is not the high-contrast one",
        )
    }

    @Test
    fun `unavailable text is dimmer but still readable`() {
        val screen = fillOf(skin.resolve("screen"))!!
        val disabled = skin.resolve("label", setOf(WidgetState.Disabled)).textColour

        assertTrue(contrast(disabled, screen) >= 4.5f, contrast(disabled, screen).format())
        assertTrue(contrast(disabled, screen) < contrast(skin.resolve("label").textColour, screen))

        // A stepper's arrow at an end it cannot pass is still an arrow the player has to see is there.
        val arrow = skin.resolve("stepper.arrow", setOf(WidgetState.Disabled)).textColour
        assertTrue(contrast(arrow, screen) >= 3f, "a dead-end arrow at ${contrast(arrow, screen).format()}")
    }

    @Test
    fun `focus is yellow and at least two pixels wherever a style draws it`() {
        val weak = skin.styles.keys.mapNotNull { name ->
            val focused = skin.style(name).focused?.background as? SkinDrawable.Fill ?: return@mapNotNull null
            val border = focused.border ?: return@mapNotNull null
            // The lighter yellow is focus on something already edged in yellow, like a chosen tab.
            if (border in yellows && focused.borderWidth >= 2f) null
            else "$name: $border at ${focused.borderWidth}"
        }

        assertEquals(emptyList(), weak)
        val ring = skin.resolve("focusRing").background as SkinDrawable.Fill
        assertTrue(ring.borderWidth > (Skin.Default.resolve("focusRing").background as SkinDrawable.Fill).borderWidth)
    }

    @Test
    fun `every edge is at least two pixels`() {
        val thin = skin.styles.keys.flatMap { name ->
            states.mapNotNull { state ->
                val fill = skin.resolve(name, state).background as? SkinDrawable.Fill ?: return@mapNotNull null
                if (fill.border == null || fill.borderWidth >= 2f) null else "$name $state"
            }
        }

        assertEquals(emptyList(), thin)
    }

    @Test
    fun `a fill or a knob stands out from the track it moves along`() {
        val pairs = listOf(
            "slider.fill" to "slider.track", "slider.knob" to "slider.track",
            "progress.fill" to "progress.track", "progress.fill.danger" to "progress.track",
            "progress.fill.good" to "progress.track",
            "bar.fill" to "bar.track", "bar.fill.low" to "bar.track", "bar.fill.critical" to "bar.track",
            "checkbox.tick" to "checkbox", "radio.dot" to "radio",
            "toggle.knob" to "toggle", "toggle.knob" to "toggle.on",
        )

        val lost = pairs.mapNotNull { (thing, track) ->
            val ratio = contrast(fillOf(skin.resolve(thing))!!, fillOf(skin.resolve(track))!!)
            // 3:1 is the guidelines' bar for a shape rather than for words.
            if (ratio >= 3f) null else "$thing on $track: ${ratio.format()}"
        }

        assertEquals(emptyList(), lost)
    }

    @Test
    fun `it round-trips like any other skin file`() {
        val again = SkinFormat.read(SkinFormat.write(skin))

        assertEquals(skin.styles, again.styles)
        assertEquals(skin.defaults, again.defaults)
        assertEquals("High contrast", again.name)
    }

    private val yellows = setOf(Colour.rgb(0xFFD600), Colour.rgb(0xFFE866))

    private fun fillOf(style: ResolvedStyle): Colour? = (style.background as? SkinDrawable.Fill)?.colour

    /** The contrast ratio between two colours, from 1 (the same) to 21 (black and white). */
    private fun contrast(a: Colour, b: Colour): Float {
        val lighter = maxOf(luminance(a), luminance(b))
        val darker = minOf(luminance(a), luminance(b))
        return ((lighter + 0.05) / (darker + 0.05)).toFloat()
    }

    private fun luminance(colour: Colour): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(colour.red) + 0.7152 * channel(colour.green) + 0.0722 * channel(colour.blue)
    }

    private fun Float.format() = (kotlin.math.round(this * 100f) / 100f).toString()
}
