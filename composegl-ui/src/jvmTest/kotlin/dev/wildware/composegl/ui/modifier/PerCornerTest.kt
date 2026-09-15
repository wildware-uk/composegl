package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.SkinFormat
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A radius for each corner, in a real composed interface driven the way a player drives it.
 *
 * Tabs are the case the feature exists for, so they are what is built here: a row of tabs rounded
 * along the top, sitting square on the panel they open, and a click moving the highlighted one.
 * What is asserted is what gets drawn — where each box landed and which corners it was given.
 */
class PerCornerTest {

    private val backend = HeadlessBackend()
    private val canvas: RecordingCanvas get() = backend.canvas
    private var ui: UiTest? = null

    private val accent = Colour.rgb(0x4CC2FF)
    private val steel = Colour.rgb(0x2C3545)

    @AfterEach
    fun tearDown() {
        ui?.close()
    }

    private fun show(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 400f), backend, content = content).also { ui = it }

    /** Draws one frame through the harness, the way a game's renderer would, and keeps only that frame. */
    private fun UiTest.drawn(): RecordingCanvas {
        canvas.clear()
        render()
        canvas.assertBalanced()
        return canvas
    }

    @Composable
    private fun Tabs(selected: MutableState<Int>) {
        Column {
            Row {
                repeat(3) { index ->
                    Box(
                        Modifier.size(60f, 24f)
                            .background(if (selected.value == index) accent else steel, Corners.top(8f))
                            .clickable { selected.value = index }
                            .testTag("tab$index"),
                    ) {}
                }
            }
            Box(
                Modifier.size(180f, 60f)
                    .shadow(Colour.Black, 10f, Corners.bottom(8f))
                    .background(steel, Corners.bottom(8f))
                    .border(accent, width = 2f, corners = Corners.bottom(8f)),
            ) {}
        }
    }

    @Test
    fun `tabs are rounded along the top and the panel under them along the bottom`() {
        val drawn = show { Tabs(mutableStateOf(0)) }.drawn()

        val boxes = drawn.only<DrawCall.CorneredRectangle>()
        assertEquals(
            listOf(Rect.of(0f, 0f, 60f, 24f), Rect.of(60f, 0f, 60f, 24f), Rect.of(120f, 0f, 60f, 24f)),
            boxes.take(3).map { it.rect },
            "three tabs side by side",
        )
        assertTrue(boxes.take(3).all { it.corners == Corners.top(8f) }, "each round only along its top: $drawn")

        val panel = boxes.last()
        assertEquals(Rect.of(0f, 24f, 180f, 60f), panel.rect, "the panel sits flush under them")
        assertEquals(Corners.bottom(8f), panel.corners)
        assertEquals(Corners.bottom(8f), drawn.only<DrawCall.CorneredBorder>().single().corners)
        assertEquals(Corners.bottom(8f), drawn.only<DrawCall.CorneredShadow>().single().corners)
        assertTrue(drawn.only<DrawCall.Rectangle>().isEmpty(), "nothing fell back to one radius: $drawn")
    }

    @Test
    fun `clicking a tab moves the highlighted top-rounded box to it`() {
        val selected = mutableStateOf(0)
        val ui = show { Tabs(selected) }
        assertEquals(Rect.of(0f, 0f, 60f, 24f), highlighted(ui.drawn()).rect)

        ui.click("tab2")

        assertEquals(2, selected.value, "the third tab took the click")
        val tab = highlighted(ui.drawn())
        assertEquals(Rect.of(120f, 0f, 60f, 24f), tab.rect, "and it is the one drawn in the accent")
        assertEquals(Corners.top(8f), tab.corners, "still round only along its top")
    }

    private fun highlighted(drawn: RecordingCanvas) =
        drawn.only<DrawCall.CorneredRectangle>().single { it.colour == accent }

    @Test
    fun `a skin can round a hovered button differently from its idle one`() {
        val skin = SkinFormat.read(
            """
            {
              "styles": {
                "button": {
                  "background": { "fill": "#232A35", "corner": 4 },
                  "padding": 8,
                  "hovered": { "background": { "fill": "#39445A", "corner": { "topLeft": 10, "bottomRight": 10 } } }
                }
              }
            }
            """.trimIndent(),
        )
        val ui = show { ProvideSkin(skin) { Button("PLAY", onClick = {}, modifier = Modifier.testTag("play")) } }

        val idle = ui.drawn().only<DrawCall.Rectangle>().first()
        assertEquals(4f, idle.corner, "one radius from the file is drawn as one radius")
        assertTrue(canvas.only<DrawCall.CorneredRectangle>().isEmpty(), "and nothing idle has four")

        ui.moveTo("play")

        val hovered = ui.drawn().only<DrawCall.CorneredRectangle>().single()
        assertEquals(Colour.rgb(0x39445A), hovered.colour)
        assertEquals(Corners(topLeft = 10f, bottomRight = 10f), hovered.corners)
        assertEquals(idle.rect, hovered.rect, "the same button, only its corners changed")
    }

    @Test
    fun `corners changed by state are drawn on the next frame`() {
        val docked = mutableStateOf(false)
        val ui = show {
            Box(
                Modifier.size(80f, 40f)
                    .background(accent, if (docked.value) Corners.left(12f) else Corners.top(12f))
                    .clickable { docked.value = !docked.value }
                    .testTag("panel"),
            ) {}
        }
        assertEquals(Corners.top(12f), ui.drawn().only<DrawCall.CorneredRectangle>().single().corners)

        ui.click("panel")

        assertEquals(Corners.left(12f), ui.drawn().only<DrawCall.CorneredRectangle>().single().corners)
    }

    @Test
    fun `a still screen stays still when its corners are made afresh on every composition`() {
        // A Corners built inline is a new object each time the content runs. If two equal ones did
        // not compare equal, the modifier would look changed and a screen with nothing moving
        // would be laid out and redrawn every frame.
        val unrelated = mutableStateOf(0)
        val ui = show {
            Box(
                Modifier.size(80f, 40f)
                    .background(accent, Corners(topLeft = 6f + unrelated.value * 0f, bottomRight = 6f))
                    .testTag("box"),
            ) {}
        }
        val before = ui.node("box").resolved

        unrelated.value = 1
        ui.settle()

        assertSame(before, ui.node("box").resolved, "an equal set of corners is not a new modifier")
        assertEquals(Corners(topLeft = 6f, bottomRight = 6f), ui.drawn().only<DrawCall.CorneredRectangle>().single().corners)
    }

    @Test
    fun `four equal corners reach a wrapping canvas through the call it already overrides`() {
        // A canvas written before four radii existed wraps another and counts boxes by overriding
        // the single-radius call. A background given Corners.all has to keep reaching it.
        class Counting(private val inner: RecordingCanvas) : UiCanvas by inner {
            var boxes = 0
            override fun rect(rect: Rect, colour: Colour, corner: Float) {
                boxes++
                inner.rect(rect, colour, corner)
            }
        }
        val ui = show { Box(Modifier.size(40f).background(accent, Corners.all(6f))) {} }
        val counting = Counting(canvas)

        MeasurePass().run(ui.root, Constraints.atMost(400f, 400f))
        DrawPass(counting).draw(ui.root)

        assertEquals(1, counting.boxes, "the wrapper saw the box")
        assertEquals(6f, canvas.only<DrawCall.Rectangle>().single().corner)
    }

    @Test
    fun `a single radius that springs below zero draws square instead of throwing`() {
        // Before four radii a negative corner was never checked: the shader held it at zero. A
        // corner animated with an overshooting spring can pass through one, and has to keep drawing.
        val corner = mutableStateOf(6f)
        val ui = show {
            Box(
                Modifier.size(80f, 40f)
                    .shadow(Colour.Black, 4f, corner = corner.value)
                    .background(accent, corner = corner.value)
                    .border(steel, width = 2f, corner = corner.value)
                    .clip(corner.value),
            ) {}
        }
        assertEquals(6f, ui.drawn().only<DrawCall.Rectangle>().single().corner)

        corner.value = -1.5f
        ui.settle()

        val drawn = ui.drawn()
        assertEquals(0f, drawn.only<DrawCall.Rectangle>().single().corner, "square, and still drawn: $drawn")
        assertEquals(0f, drawn.only<DrawCall.Border>().single().corner)
        assertEquals(0f, drawn.only<DrawCall.Shadow>().single().corner)
    }

    @Test
    fun `a clip written with corners is a clip`() {
        val ui = show { Box(Modifier.size(40f).clip(Corners.top(6f)).testTag("clipped")) {} }

        assertEquals(Corners.top(6f), ui.node("clipped").resolved.clip?.corners)
    }
}
