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

    /**
     * The styles a widget writes words in, each with the style its words sit on when that is another.
     *
     * Null means the words sit on the style's own fill, or on the screen when it has none. A name
     * means they sit on that style instead, whatever this one is filled with — a compass pin is a
     * yellow diamond with its distance written *under* it, on the compass, not on the diamond.
     *
     * A list rather than a map because one style's words can land on two different things: a wheel's
     * label is written both in the middle of the wheel and on the slice being pointed at.
     */
    private val text = listOf(
        "label" to null, "label.title" to null, "label.heading" to null, "label.dim" to null,
        "label.danger" to null, "label.good" to null,
        "button" to null, "button.primary" to null, "button.danger" to null, "button.quiet" to null,
        "button.icon" to null,
        "button.listening" to null, "button.key" to null, "button.key.on" to null, "dropdown" to null,
        "collapsingheader" to null, "collapsingheader.open" to null, "collapsingheader.glyph" to null,
        "tooltip" to null, "notification" to null, "notification.detail" to "notification",
        "notification.more" to "notification", "minimap.compass" to "minimap", "prompt" to null,
        "compass.label" to "compass", "compass.readout" to "compass", "compass.pin" to "compass",
        "subtitle" to null, "subtitle.speaker" to "subtitle", "subtitle.caption" to "subtitle",
        "dialogue.speaker" to "dialogue", "dialogue.text" to "dialogue",
        "dialogue.choice" to null, "dialogue.choice.reason" to "dialogue.choice",
        "dialogue.control" to null, "dialogue.control.on" to null,
        "dialogue.history.speaker" to null, "dialogue.history.line" to null,
        "dialogue.history.answer" to null,
        "objective.title" to "objective", "objective.step" to "objective",
        "objective.step.done" to "objective", "objective.count" to "objective",
        "objective.more" to "objective",
        "chat.message" to "chat", "chat.system" to "chat", "chat.name" to "chat",
        "chat.channel" to "chat", "chat.tab" to null, "chat.tab.selected" to null,
        "chat.field" to "chat", "chat.field.placeholder" to "chat",
        "stepper.arrow" to "stepper", "stepper.value" to "stepper",
        "field" to null, "field.placeholder" to "field",
        "item" to null, "item.selected" to null, "tab" to null, "tab.selected" to null,
        "menubar.title" to "menubar", "menubar.title.open" to null,
        "menu.item" to "menu", "menu.item.open" to null, "menu.shortcut" to "menu",
        "table.header.cell" to "table.header", "table.header.cell.sorted" to null,
        "table.row" to "table", "table.row.alt" to null, "table.row.selected" to null, "table.empty" to "table",
        "tree.row" to null, "tree.row.selected" to null, "tree.toggle" to null, "tree.toggle.open" to null,
        "hotbar.prompt" to "hotbar.slot", "hotbar.charges" to "hotbar.slot",
        "cooldown.seconds" to null,
        "inventory.count" to "inventory.item",
        // A wheel's own words are written in the middle, on the hub, and on the slice being pointed
        // at. Both, because a skin that reads on one and not the other is half a skin.
        "wheel.label" to "wheel.hub", "wheel.label" to "wheel.slice.highlighted",
        "itemtip" to null, "itemtip.title" to "itemtip", "itemtip.subtitle" to "itemtip",
        "itemtip.label" to "itemtip", "itemtip.value" to "itemtip", "itemtip.flavour" to "itemtip",
        "itemtip.hint" to "itemtip", "itemtip.better" to "itemtip", "itemtip.worse" to "itemtip",
        "itemtip.same" to "itemtip",
        // A skill node's letters are written on its own frame; its rank is written under the node,
        // on the plane, which is why it is the one here that names what it sits on.
        "skilltree.node.locked" to null, "skilltree.node.available" to null,
        "skilltree.node.owned" to null, "skilltree.node.maxed" to null,
        "skilltree.rank" to "skilltree.plane",
        "damage" to null, "damage.critical" to null,
        "debugwindow.title" to null, "debugwindow.title.active" to null, "debugwindow.button" to "debugwindow",
        "debugwindow.label" to "debugwindow", "debugwindow.value" to "debugwindow",
        "console.title" to "console", "console.prompt" to "console",
        "console.line" to "console", "console.line.debug" to "console", "console.line.info" to "console",
        "console.line.warn" to "console", "console.line.error" to "console", "console.line.echo" to "console",
        "console.suggestion" to "console", "console.suggestion.selected" to null,
        "console.field" to "console", "console.field.placeholder" to "console",
        "plot.label" to "plot", "plot.value" to "plot",
    )

    @Test
    fun `every piece of text stands out from what it is drawn on`() {
        val screen = fillOf(skin.resolve("screen"))!!
        val faint = text.flatMap { (name, on) ->
            // Disabled text is meant to recede, and is held to the lower bar below.
            states.filter { WidgetState.Disabled !in it }.mapNotNull { state ->
                val style = skin.resolve(name, state)
                val behind = on?.let { fillOf(skin.resolve(it, state)) }
                    ?: fillOf(style)?.takeIf { it.alpha == 0xFF }
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
            "indeterminatebar.fill" to "indeterminatebar.track",
            "dialogue.timer.fill" to "dialogue.timer.track",
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
    fun `every shape the skin draws stands out from what it is drawn on`() {
        // Each of these is a shape with no words and no edge of its own, so the only thing that can
        // make it visible is being a different colour from whatever it sits on. A wheel is the case
        // that made this test worth having: it brings its own backdrop, so a black slice on a black
        // backdrop is a wheel a player cannot see at all.
        val pairs = listOf(
            "wheel.slice" to "wheel.backdrop", "wheel.slice.selected" to "wheel.backdrop",
            "wheel.ring" to "wheel.backdrop", "wheel.hub" to "wheel.backdrop",
            "wheel.slice.highlighted" to "wheel.slice", "wheel.ring.highlighted" to "wheel.ring",
            "divider" to "screen", "separator" to "screen", "splitter" to "screen",
            "tree.guide" to "screen", "marker.arrow" to "screen",
            "reticle" to "screen", "reticle.hostile" to "screen",
            "hitmarker" to "screen", "hitmarker.critical" to "screen", "hitmarker.kill" to "screen",
            "selection" to "screen",
            "field.caret" to "field", "field.selection" to "field",
            "minimap.marker" to "minimap", "objective.tick" to "objective.bullet",
            "compass.tick" to "compass", "compass.marker" to "compass", "compass.pin" to "compass",
            "menu.separator" to "menu", "menu.check" to "menu", "menu.radio" to "menu",
            "table.divider" to "table",
            "scrollbar.thumb" to "scrollbar.track",
            "debugwindow.grip" to "debugwindow",
        )

        val lost = pairs.mapNotNull { (thing, behind) ->
            val ratio = contrast(fillOf(skin.resolve(thing))!!, fillOf(skin.resolve(behind))!!)
            if (ratio >= 3f) null else "$thing on $behind: ${ratio.format()}"
        }

        assertEquals(emptyList(), lost)
    }

    @Test
    fun `an edge that says where a widget is can be seen`() {
        // An edge is drawn just inside the shape, so it is visible if it stands clear of either the
        // fill it is drawn on or the screen behind it. A dark grey edge on a black box is neither,
        // which in this skin means an unticked checkbox nobody can find.
        val screen = fillOf(skin.resolve("screen"))!!
        val lost = skin.styles.keys.flatMap { name ->
            states.mapNotNull { state ->
                val fill = skin.resolve(name, state).background as? SkinDrawable.Fill ?: return@mapNotNull null
                val border = fill.border ?: return@mapNotNull null
                val on = fill.colour.takeIf { it.alpha == 0xFF } ?: screen
                val ratio = maxOf(contrast(border, on), contrast(border, screen))
                if (ratio >= 3f) null else "$name $state: ${ratio.format()}"
            }
        }

        assertEquals(emptyList(), lost, "edges too close to what they are drawn against")
    }

    @Test
    fun `a track whose block is drawn inside it leaves room for its own edge`() {
        // An indeterminate bar draws its block within the track's padding. A track with an outline
        // and no padding is a track whose outline the block rubs out every time it slides past.
        for (from in listOf(Skin.Default, skin)) {
            val track = from.resolve("indeterminatebar.track").background as SkinDrawable.Fill
            val room = minOf(track.padding.left, track.padding.top, track.padding.right, track.padding.bottom)

            assertTrue(
                track.border == null || room >= track.borderWidth,
                "${from.name}: an edge ${track.borderWidth} wide with $room to draw it in",
            )
        }
    }

    @Test
    fun `a spinner's arc stands out from its ring and from the screen`() {
        val screen = fillOf(skin.resolve("screen"))!!
        val arc = skin.resolve("spinner").textColour
        val ring = skin.resolve("spinner.track").textColour

        assertTrue(contrast(arc, ring) >= 3f, "arc on ring at ${contrast(arc, ring).format()}")
        assertTrue(contrast(arc, screen) >= 3f, "arc on the screen at ${contrast(arc, screen).format()}")
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
