package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.graphics.BorderSide
import dev.wildware.composegl.ui.graphics.BorderStyle
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One-sided and broken borders on real composed screens, driven the way a player drives them.
 *
 * Every border here is written in a screen's own modifier, moves because of a click, a hover or a
 * key, and is checked in what the draw pass handed the canvas that frame. The accent colour is
 * used for nothing but the border, so a call in it is the border and nothing else.
 */
class BorderModifierTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)
    private var clock = 0L

    private val accent = Colour.rgb(0x13579B)
    private val divider = Colour.rgb(0x2468AC)

    @AfterEach
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frame()
        frame()
    }

    /** One turn of a game loop: recompose, lay out, settle focus, draw. */
    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
        frame()
    }

    private fun key(key: Key) {
        keys.onKey(KeyEvent(key, KeyEventType.Down))
        keys.onKey(KeyEvent(key, KeyEventType.Up))
        frame()
    }

    private fun inAccent() = canvas.only<DrawCall.Rectangle>().filter { it.colour == accent }.map { it.rect }

    private fun Rect.within(outer: Rect) =
        left >= outer.left - 0.01f && top >= outer.top - 0.01f && right <= outer.right + 0.01f && bottom <= outer.bottom + 0.01f

    @Composable
    private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
        Row {
            listOf("MAP", "GEAR", "QUESTS").forEachIndexed { index, name ->
                val underline = if (index == selected) Modifier.border(bottom = BorderSide(3f, accent)) else Modifier
                Button(name, onClick = { onSelect(index) }, modifier = Modifier.testTag("tab-$index").then(underline))
            }
        }
    }

    @Test
    fun `clicking a tab moves the underline to the bottom edge of that tab`() {
        show {
            var selected by remember { mutableStateOf(0) }
            Tabs(selected) { selected = it }
        }
        val first = host.root.find("tab-0").boundsInRoot
        val third = host.root.find("tab-2").boundsInRoot

        val before = inAccent().single()
        assertEquals(first.bottom, before.bottom, 0.01f, "the underline sits on the first tab's bottom edge")
        assertEquals(3f, before.height, 0.01f)
        assertEquals(first.left, before.left, 0.01f)
        assertEquals(first.right, before.right, 0.01f, "and runs its full width")

        click(third.centre)

        val after = inAccent().single()
        assertEquals(Rect.of(third.left, third.bottom - 3f, third.width, 3f), after, "one underline, under the tab that was clicked")
    }

    @Test
    fun `the arrow keys carry a focus underline from button to button`() {
        show {
            Row {
                listOf("NEW", "LOAD", "QUIT").forEachIndexed { index, name ->
                    val state = remember { InteractionState() }
                    val underline = if (state.isFocused) Modifier.border(bottom = BorderSide(2f, accent)) else Modifier
                    Button(
                        name,
                        onClick = {},
                        initialFocus = index == 0,
                        interaction = state,
                        modifier = Modifier.testTag("menu-$index").then(underline),
                    )
                }
            }
        }
        fun underlined() = inAccent().single()

        assertEquals(host.root.find("menu-0").boundsInRoot.bottom, underlined().bottom, 0.01f, "the focused button is underlined")

        key(Key.Right)
        frame()
        val second = host.root.find("menu-1").boundsInRoot
        assertTrue(underlined().within(second), "right moved the underline onto the next button: ${underlined()} in $second")
        assertEquals(second.bottom, underlined().bottom, 0.01f)
        assertEquals(2f, underlined().height, 0.01f)
    }

    @Test
    fun `hovering a drop zone turns its outline dashed and leaving turns it back`() {
        show {
            val hover = remember { InteractionState() }
            Column {
                Text("DRAG HERE")
                val style = if (hover.isHovered) BorderStyle.Dashed(on = 6f, off = 4f) else BorderStyle.Solid
                dev.wildware.composegl.ui.layout.Box(
                    Modifier.testTag("drop").size(120f, 60f).interaction(hover).clickable {}
                        .border(accent, width = 2f, style = style),
                ) {}
            }
        }
        val drop = host.root.find("drop").boundsInRoot

        val solid = canvas.only<DrawCall.Border>().single { it.colour == accent }
        assertEquals(drop, solid.rect, "at rest the zone has a plain outline round it")
        assertTrue(inAccent().isEmpty(), "and no dashes")

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, drop.centre))
        frame()

        assertTrue(canvas.only<DrawCall.Border>().none { it.colour == accent }, "hovered, the solid outline is gone")
        val dashes = inAccent()
        assertTrue(dashes.size > 8, "and in its place a broken one, got ${dashes.size} pieces")
        dashes.forEach { assertTrue(it.within(drop), "every dash is inside the zone: $it in $drop") }
        assertTrue(dashes.any { it.top == drop.top } && dashes.any { it.bottom == drop.bottom }, "top and bottom are dashed")
        assertTrue(dashes.any { it.left == drop.left } && dashes.any { it.right == drop.right }, "and the sides")

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(390f, 390f)))
        frame()

        assertEquals(drop, canvas.only<DrawCall.Border>().single { it.colour == accent }.rect, "leaving puts the plain outline back")
        assertTrue(inAccent().isEmpty())
    }

    @Test
    fun `a divider under a header is drawn under it and nowhere else on the screen`() {
        show {
            Column {
                Text("INVENTORY", modifier = Modifier.testTag("header").border(bottom = BorderSide(1f, divider)).padding(bottom = 6f))
                Text("Sword")
            }
        }
        val header = host.root.find("header").boundsInRoot

        val line = canvas.only<DrawCall.Rectangle>().single { it.colour == divider }.rect
        assertEquals(Rect.of(header.left, header.bottom - 1f, header.width, 1f), line)
    }

    @Test
    fun `on a pad the d-pad and South move a tab's underline and a dotted focus outline`() {
        uiTest(size = Size(400f, 200f)) {
            var selected by remember { mutableStateOf(0) }
            Row {
                listOf("MAP", "GEAR", "QUESTS").forEachIndexed { index, name ->
                    val state = remember { InteractionState() }
                    var look: Modifier = Modifier
                    if (index == selected) look = look.border(bottom = BorderSide(3f, accent))
                    if (state.isFocused) look = look.border(divider, width = 2f, style = BorderStyle.Dotted)
                    Button(
                        name,
                        onClick = { selected = index },
                        initialFocus = index == 0,
                        interaction = state,
                        modifier = Modifier.testTag("pad-tab-$index").then(look),
                    )
                }
            }
        }.use { ui ->
            fun drawn(): RecordingCanvas = RecordingCanvas().also { DrawPass(it).draw(ui.root) }
            fun underline() = drawn().only<DrawCall.Rectangle>().single { it.colour == accent }.rect
            fun dots() = drawn().only<DrawCall.Rectangle>().filter { it.colour == divider }.map { it.rect }

            val first = ui.node("pad-tab-0").boundsInRoot
            assertEquals(Rect.of(first.left, first.bottom - 3f, first.width, 3f), underline())

            ui.pad(GamepadButton.DpadRight)
            ui.pad(GamepadButton.DpadRight)
            ui.assertFocused("pad-tab-2")
            val third = ui.node("pad-tab-2").boundsInRoot
            assertEquals(first.bottom, underline().bottom, 0.01f, "moving focus alone leaves the underline where it was")
            assertTrue(underline().within(first))
            assertTrue(dots().size > 8, "the focused tab has a dotted outline, got ${dots().size} dots")
            dots().forEach { assertTrue(it.within(third), "every dot is round the focused tab: $it in $third") }

            ui.pad(GamepadButton.South)
            assertEquals(Rect.of(third.left, third.bottom - 3f, third.width, 3f), underline(), "South selected the third tab")
        }
    }

    @Test
    fun `clicking a tab gives it a dashed outline rounded along its top only`() {
        show {
            var selected by remember { mutableStateOf(false) }
            val outline = if (selected) {
                Modifier.border(accent, width = 2f, corners = Corners.top(12f), style = BorderStyle.Dashed(on = 6f, off = 4f))
            } else {
                Modifier
            }
            dev.wildware.composegl.ui.layout.Box(
                Modifier.testTag("tab").size(120f, 60f).clickable { selected = true }.then(outline),
            ) {}
        }
        val tab = host.root.find("tab").boundsInRoot
        fun ring() = canvas.only<DrawCall.Fan>().filter { it.colour == accent }.flatMap { it.points }

        assertTrue(ring().isEmpty(), "unselected, no outline")

        click(tab.centre)

        val points = ring()
        assertTrue(points.size > 16, "selected, a dashed ring is drawn, got ${points.size} points")
        points.forEach { assertTrue(it.x >= tab.left - 0.01f && it.x <= tab.right + 0.01f && it.y >= tab.top - 0.01f && it.y <= tab.bottom + 0.01f, "inside the tab: $it") }
        assertTrue(points.none { it.x < tab.left + 3f && it.y < tab.top + 3f }, "the top-left corner is round")
        assertTrue(points.any { it.x < tab.left + 3f && it.y > tab.bottom - 3f }, "the bottom-left corner is square")
    }

    @Test
    fun `recomposing with the same borders costs a still screen nothing`() {
        var ticks by mutableStateOf(0)
        uiTest(size = Size(400f, 200f)) {
            // Read here so this scope runs again and hands the box freshly built sides and styles.
            ticks.let {
                Column {
                    dev.wildware.composegl.ui.layout.Box(
                        Modifier.size(120f, 40f).border(bottom = BorderSide(2f, accent, BorderStyle.Dashed(on = 6f, off = 4f))),
                    ) {}
                    dev.wildware.composegl.ui.layout.Box(
                        Modifier.size(120f, 40f).border(divider, width = 2f, corner = 8f, style = BorderStyle.Dashed(on = 6f, off = 4f)),
                    ) {}
                }
            }
        }.use { ui ->
            ui.render()
            assertFalse(ui.render(), "nothing moved")

            ticks++
            assertFalse(ui.render(), "the recomposition wrote equal borders, so nothing needs drawing")
        }
    }

    @Test
    fun `the per-side border is a painting element and compares by value`() {
        val side = BorderSide(1f, divider)
        val resolved = Modifier.background(accent).border(bottom = side).resolve()

        assertEquals(
            listOf(BackgroundElement(accent), BorderSidesElement(bottom = side)),
            resolved.behind.map { it.element },
            "drawn over the background, in chain order",
        )
        assertEquals(Modifier.border(bottom = BorderSide(1f, divider)), Modifier.border(bottom = side), "a still screen stays free")
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { Modifier.border(accent, width = Float.POSITIVE_INFINITY) }
        assertEquals(
            Modifier.border(accent, style = BorderStyle.Dashed(6f, 4f)),
            Modifier.border(accent, style = BorderStyle.Dashed(6f, 4f)),
        )
    }
}
