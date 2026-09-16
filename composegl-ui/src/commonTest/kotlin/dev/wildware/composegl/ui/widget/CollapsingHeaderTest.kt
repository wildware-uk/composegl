package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.ProvideUiSounds
import dev.wildware.composegl.ui.input.UiSounds
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.saveable.SaveableStateHolder
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.SkinFormat
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A settings page of folding sections, driven the way a player drives it: a click, Enter, the pad's
 * South, Tab. Every test reads the answer off the screen — what exists, how tall things are, where
 * focus is and what was drawn.
 *
 * The folding runs on a clock of its own, [fold], which a test stops when it wants to look at the
 * frames in between rather than at where the animation lands.
 */
class CollapsingHeaderTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 400f), content = content).also { opened += it }

    private val fold = Clock("fold")

    /** A tenth of the way every frame, near enough, so a test can catch it part-way. */
    private val linear = Tween(durationMillis = 160, easing = Easings.Linear)

    /** A physics section with two tweakables in it, and a button under the section. */
    @Composable
    private fun Physics(initiallyExpanded: Boolean = false, enabled: Boolean = true) {
        Column(Modifier.width(300f)) {
            // As wide as the section, as rows on a settings page are, so up and down go straight.
            Button("TOP", onClick = {}, modifier = Modifier.fillMaxWidth().testTag("top"), initialFocus = true)
            CollapsingHeader(
                "Physics",
                Modifier.testTag("physics"),
                initiallyExpanded = initiallyExpanded,
                enabled = enabled,
                spec = linear,
                clock = fold,
            ) {
                Column {
                    Box(Modifier.size(200f, 60f).focusable().testTag("gravity"))
                    Box(Modifier.size(200f, 60f).focusable().testTag("friction"))
                }
            }
            Button("BELOW", onClick = {}, modifier = Modifier.fillMaxWidth().testTag("below"))
        }
    }

    private fun UiTest.header(tag: String = "physics"): UiNode = node(tag).children[0]

    private fun UiTest.body(tag: String = "physics"): UiNode = node(tag).children[1]

    private fun UiTest.clickHeader(tag: String = "physics") = click(header(tag).boundsInRoot.centre)

    private fun UiTest.isOpen(): Boolean = root.findOrNull("gravity") != null

    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        DrawPass(canvas).draw(root)
        return canvas
    }

    // --- opening and closing --------------------------------------------------------------------

    @Test
    fun `a closed header has nothing under it`() {
        val ui = open { Physics() }

        ui.assertDoesNotExist("gravity")
        assertEquals(0f, ui.body().height)
        assertEquals(ui.header().layoutBoundsInRoot.bottom, ui.node("below").layoutBoundsInRoot.top)
    }

    @Test
    fun `a click on the header opens it and the contents grow in over several frames`() {
        val ui = open { Physics() }
        ui.host.clocks.stop(fold)

        ui.clickHeader()

        ui.assertExists("gravity")
        assertEquals(0f, ui.body().height, "it has not jumped to the size of its contents")
        ui.host.clocks.start(fold)
        repeat(4) { ui.render() }
        val part = ui.body().height
        assertTrue(part > 0f && part < 132f, "part-way open, at $part")
        assertEquals(ui.body().layoutBoundsInRoot.bottom, ui.node("below").layoutBoundsInRoot.top, "the button under it follows")

        ui.settle()

        val contents = ui.node("friction").layoutBoundsInRoot.bottom - ui.node("gravity").layoutBoundsInRoot.top
        assertEquals(120f, contents)
        assertTrue(ui.body().height >= 120f, "open all the way, at ${ui.body().height}")
        assertEquals(ui.body().layoutBoundsInRoot.bottom, ui.node("below").layoutBoundsInRoot.top)
    }

    @Test
    fun `a second click closes it and the contents shrink away before they go`() {
        val ui = open { Physics(initiallyExpanded = true) }
        val full = ui.body().height
        ui.host.clocks.stop(fold)

        ui.clickHeader()

        ui.assertExists("gravity")
        assertEquals(full, ui.body().height, "still open on the frame it was closed")
        ui.host.clocks.start(fold)
        repeat(4) { ui.render() }
        val part = ui.body().height
        assertTrue(part > 0f && part < full, "part-way closed, at $part")
        val cut = ui.drawn().calls.filterIsInstance<DrawCall.Text>().filter { it.text == "BELOW" }
        assertTrue(cut.isNotEmpty(), "the button under it is still drawn while it closes")

        ui.settle()

        ui.assertDoesNotExist("gravity")
        assertEquals(0f, ui.body().height)
        assertEquals(ui.header().layoutBoundsInRoot.bottom, ui.node("below").layoutBoundsInRoot.top)
    }

    @Test
    fun `initially expanded starts open without growing`() {
        val ui = open { Physics(initiallyExpanded = true) }

        ui.assertExists("friction")
        assertFalse(ui.host.clocks.isAnimating)
        assertTrue(ui.body().height >= 120f)
    }

    @Test
    fun `rows put straight in the contents stand one under another both ways round`() {
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            val ui = open {
                ProvideLayoutDirection(direction) {
                    Column(Modifier.width(300f)) {
                        CollapsingHeader("Graphics", Modifier.fillMaxWidth().testTag("graphics"), initiallyExpanded = true) {
                            Box(Modifier.size(90f, 22f).testTag("bloom"))
                            Box(Modifier.size(180f, 36f).testTag("shadows"))
                            Box(Modifier.size(120f, 20f).testTag("fog"))
                        }
                        Button("BELOW", onClick = {}, modifier = Modifier.fillMaxWidth().testTag("below"))
                    }
                }
            }
            val rows = listOf("bloom", "shadows", "fog").map { ui.node(it).boundsInRoot }
            rows.zipWithNext().forEach { (above, under) ->
                assertFalse(above.overlaps(under), "$direction: $above and $under overlap:\n" + ui.dump())
                assertEquals(above.bottom, under.top, "$direction: each row starts where the one above ends")
            }
            val start = if (direction == LayoutDirection.Ltr) rows.map { it.left } else rows.map { it.right }
            assertEquals(1, start.distinct().size, "$direction: the rows line up at the start: $rows")
            assertTrue(ui.node("below").boundsInRoot.top >= rows.last().bottom, "$direction: the section is as tall as all its rows")
        }
    }

    @Test
    fun `opened again while it is still closing it turns round and keeps its contents`() {
        val ui = open { Physics(initiallyExpanded = true) }
        val full = ui.body().height

        ui.host.clocks.stop(fold)
        ui.clickHeader()
        ui.host.clocks.start(fold)
        repeat(4) { ui.render() }
        ui.clickHeader()

        ui.assertExists("gravity")
        assertEquals(full, ui.body().height)
    }

    @Test
    fun `two presses in one go leave it closed with nothing composed under it`() {
        val ui = open { Physics() }
        ui.host.clocks.stop(fold)

        ui.clickHeader()
        ui.clickHeader()
        ui.host.clocks.start(fold)
        ui.settle()

        ui.assertDoesNotExist("gravity")
        assertEquals(0f, ui.body().height)
    }

    // --- keys and the pad ------------------------------------------------------------------------

    @Test
    fun `enter on the focused header toggles it`() {
        val ui = open { Physics() }
        ui.key(Key.Down)
        assertEquals(ui.header(), ui.focus.focused, "down from the top button lands on the header")

        ui.key(Key.Enter)
        assertTrue(ui.isOpen(), "Enter opened it")

        ui.key(Key.Enter)
        assertFalse(ui.isOpen(), "and closed it")
    }

    @Test
    fun `south on the pad toggles it`() {
        val ui = open { Physics() }
        ui.pad(GamepadButton.DpadDown)

        ui.pad(GamepadButton.South)
        assertTrue(ui.isOpen(), "A opened it")

        ui.pad(GamepadButton.South)
        assertFalse(ui.isOpen(), "and closed it")
    }

    @Test
    fun `down from a closed header skips its contents`() {
        val ui = open { Physics() }
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.DpadDown)

        ui.assertFocused("below")
    }

    @Test
    fun `down from an open header goes into its contents`() {
        val ui = open { Physics(initiallyExpanded = true) }
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.DpadDown)

        ui.assertFocused("gravity")
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("friction")
    }

    @Test
    fun `a disabled header cannot be opened or focused`() {
        val ui = open { Physics(enabled = false) }

        ui.clickHeader()
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)

        assertFalse(ui.isOpen())
        ui.assertFocused("below")
    }

    @Test
    fun `opening and closing each sound like a change`() {
        var changes = 0
        val sounds = object : UiSounds {
            override fun change() {
                changes++
            }
        }
        val ui = open { ProvideUiSounds(sounds) { Physics() } }

        ui.clickHeader()
        ui.clickHeader()

        assertEquals(2, changes)
    }

    // --- the game holding the answer ---------------------------------------------------------------

    @Test
    fun `closed from outside with focus inside focus comes back to the header`() {
        var expanded by mutableStateOf(true)
        val ui = open {
            Column(Modifier.width(300f)) {
                CollapsingHeader("Audio", expanded, { expanded = it }, Modifier.testTag("audio"), clock = fold) {
                    Box(Modifier.size(100f, 40f).focusable(initial = true).testTag("volume"))
                }
                Button("BELOW", onClick = {}, modifier = Modifier.testTag("below"))
            }
        }
        ui.assertFocused("volume")

        expanded = false
        ui.settle()

        ui.assertDoesNotExist("volume")
        assertEquals(ui.header("audio"), ui.focus.focused)
    }

    @Test
    fun `the game is told what the player asked for and decides`() {
        val asked = mutableListOf<Boolean>()
        val ui = open {
            CollapsingHeader("Locked", expanded = false, onExpandedChange = { asked += it }, Modifier.testTag("locked")) {
                Box(Modifier.size(50f).testTag("secret"))
            }
        }

        ui.clickHeader("locked")
        ui.clickHeader("locked")

        assertEquals(listOf(true, true), asked, "asked to open twice, since the game never opened it")
        ui.assertDoesNotExist("secret")
    }

    // --- saving -------------------------------------------------------------------------------------

    @Test
    fun `a section the player opened is still open after leaving the screen and coming back`() {
        var screen by mutableStateOf("settings")
        val ui = open {
            SaveableStateHolder(screen) { key ->
                if (key == "settings") Physics() else Box(Modifier.size(10f).testTag("map"))
            }
        }
        ui.clickHeader()
        assertTrue(ui.isOpen())

        screen = "map"
        ui.settle()
        ui.assertExists("map")
        screen = "settings"
        ui.settle()

        assertTrue(ui.isOpen(), "the section closed again while the screen was away")
    }

    // --- right to left --------------------------------------------------------------------------------

    @Test
    fun `right to left the triangle is at the right and a closed one points left`() {
        val ltr = open { Physics() }
        val rtl = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Physics() } }

        val ltrGlyph = ltr.header().children[0].boundsInRoot
        val rtlGlyph = rtl.header().children[0].boundsInRoot
        val rtlHeader = rtl.header().boundsInRoot
        assertTrue(ltrGlyph.centre.x < ltr.header().boundsInRoot.centre.x, "at the start left to right: $ltrGlyph")
        assertTrue(rtlGlyph.centre.x > rtlHeader.centre.x, "at the right right to left: $rtlGlyph in $rtlHeader")

        // The tip is the point furthest along the line.
        assertTrue(tipX(ltr) > centreX(ltr), "left to right it points right")
        assertTrue(tipX(rtl) < centreX(rtl), "right to left it points left")
    }

    @Test
    fun `open the triangle points down either way`() {
        val rtl = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Physics(initiallyExpanded = true) } }
        val ys = glyph(rtl).points.map { it.y }
        assertEquals(1, ys.count { it == ys.max() }, "one point at the bottom: $ys")
        assertEquals(2, ys.count { it == ys.min() }, "and two across the top")
    }

    private fun glyph(ui: UiTest): DrawCall.Fan {
        val box = ui.header().children[0].boundsInRoot
        return ui.drawn().calls.filterIsInstance<DrawCall.Fan>().single {
            it.points[0] in box
        }
    }

    private fun tipX(ui: UiTest): Float {
        val xs = glyph(ui).points.map { it.x }
        // Of three points, the tip is the one on its own; the other two share an x.
        return xs.single { x -> xs.count { it == x } == 1 }
    }

    private fun centreX(ui: UiTest): Float = ui.header().children[0].boundsInRoot.centre.x

    // --- the skin -------------------------------------------------------------------------------------

    @Test
    fun `the header bar and the triangle are drawn from the skin`() {
        val ui = open { Physics() }
        val closedFill = ui.barFill()
        val skin = Skin.Default
        assertEquals((skin.resolve("collapsingheader").background as SkinDrawable.Fill).colour, closedFill)
        assertEquals(skin.resolve("collapsingheader.glyph").textColour, glyph(ui).colour)

        ui.clickHeader()

        val focusedOpen = skin.resolve("collapsingheader.open", setOf(WidgetState.Focused, WidgetState.Hovered))
        assertEquals((focusedOpen.background as SkinDrawable.Fill).colour, ui.barFill(), "open, it wears the open style")
    }

    @Test
    fun `a game's own skin restyles it by name`() {
        val skin = Skin.Default.overriddenWith(
            SkinFormat.read(
                """{ "styles": { "collapsingheader.glyph": { "textColour": "#FF0000" } } }""",
            ),
        )
        val ui = open { ProvideSkin(skin) { Physics() } }

        assertEquals(Colour.rgb(0xFF0000), glyph(ui).colour)
    }

    @Test
    fun `both shipped skins draw every part of a header`() {
        listOf("collapsingheader", "collapsingheader.open", "collapsingheader.glyph", "collapsingheader.body").forEach {
            assertTrue(Skin.Default.has(it), "the default skin has no $it")
            assertTrue(Skin.HighContrast.has(it), "the high contrast skin has no $it")
        }
    }

    /** The colour of the box drawn across the whole header bar. */
    private fun UiTest.barFill() = drawn().calls.filterIsInstance<DrawCall.Rectangle>()
        .last { it.rect == header().boundsInRoot }.colour
}
