package dev.wildware.composegl.demo.web

import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.SkinFormat
import dev.wildware.composegl.ui.skin.StyleNames
import dev.wildware.composegl.ui.skin.WidgetState
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The web demo's skins, held to the toolkit's list of style names the way `ExampleSkinTest` and
 * `ShowcaseSkinTest` hold the other two demos'.
 *
 * The demo's blue look is laid over the standard skin, so a name the look forgets does not fail: the
 * widget quietly draws in the standard skin's grey and purple-blue instead. That is how the pages
 * added for 0.6.0 came to look like a different app. The list comes from composegl-ui's tests,
 * copied in by the build, so a widget added there has to be styled here too.
 */
class ShowcaseSkinsTest {

    private val look = SkinFormat.read(SHOWCASE_LOOK_JSON)
    private val extras = SkinFormat.read(TOUR_EXTRAS_JSON)
    private val highContrastExtras = SkinFormat.read(HIGH_CONTRAST_TOUR_EXTRAS_JSON)
    private val skins = ShowcaseSkins(art = null)

    @Test
    fun `the demo's own look styles every name a widget asks for`() {
        val missing = StyleNames.filterNot { look.has(it) }

        assertEquals(emptyList(), missing, "names the demo's look leaves to the standard skin")
    }

    @Test
    fun `and nothing that nobody asks for`() {
        val spare = look.styles.keys - StyleNames.toSet() - TourStyleNames

        assertEquals(emptySet(), spare, "names in the look that no widget and no page uses")
    }

    @Test
    fun `the tour's own names are answered in both extras and nothing more`() {
        assertEquals(TourStyleNames, extras.styles.keys, "the extras laid under the showcase and standard skins")
        assertEquals(TourStyleNames, highContrastExtras.styles.keys, "the extras laid over the high-contrast skin")
    }

    @Test
    fun `every skin the demo can wear answers everything its pages ask for`() {
        val wanted = StyleNames + TourStyleNames
        val missing = SkinChoice.entries.flatMap { choice ->
            wanted.filterNot { skins[choice].has(it) }.map { "$choice: $it" }
        }

        assertEquals(emptyList(), missing)
    }

    @Test
    fun `the tour's own styles stay legible in high contrast`() {
        val skin = skins.highContrast
        val screen = fillOf(skin.resolve("screen"))!!
        val words = listOf("code" to "card", "code" to "screen", "chat.squad" to "chat")
        val shapes = listOf(
            "bar.shield.fill" to "bar.shield.track", "bar.stamina.fill" to "bar.stamina.track",
            "bar.shield.trail" to "bar.shield.track", "bar.stamina.trail" to "bar.stamina.track",
            "minimap.objective" to "minimap",
        )

        val faint = words.mapNotNull { (name, on) ->
            val behind = fillOf(skin.resolve(on))?.takeIf { it.alpha == 0xFF } ?: screen
            val ratio = contrast(skin.resolve(name).textColour, behind)
            if (ratio >= 4.5f) null else "$name on $on: $ratio"
        } + shapes.mapNotNull { (thing, track) ->
            val ratio = contrast(fillOf(skin.resolve(thing))!!, fillOf(skin.resolve(track))!!)
            if (ratio >= 3f) null else "$thing on $track: $ratio"
        } + TourStyleNames.flatMap { name ->
            listOf(emptySet(), setOf(WidgetState.Focused)).mapNotNull { state ->
                val fill = skin.resolve(name, state).background as? SkinDrawable.Fill ?: return@mapNotNull null
                val border = fill.border ?: return@mapNotNull null
                if (fill.borderWidth >= 2f && contrast(border, screen) >= 3f) null else "$name edge $border at ${fill.borderWidth}"
            }
        }

        assertEquals(emptyList(), faint, "the tour's styles under the bar the high-contrast skin holds itself to")
    }

    private fun fillOf(style: ResolvedStyle): Colour? = (style.background as? SkinDrawable.Fill)?.colour

    /** The contrast ratio the web's accessibility guidelines use, from 1 (the same) to 21. */
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

    private companion object {
        /** The names the tour's pages invent, which the toolkit's list rightly leaves out. */
        val TourStyleNames = setOf(
            "card", "code", "minimap.objective", "chat.squad",
            "bar.shield.track", "bar.shield.fill", "bar.shield.trail", "bar.shield.segment",
            "bar.stamina.track", "bar.stamina.fill", "bar.stamina.trail", "bar.stamina.segment",
        )
    }
}
