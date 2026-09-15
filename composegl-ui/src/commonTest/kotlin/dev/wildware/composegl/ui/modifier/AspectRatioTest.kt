package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.ScrollArea
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `aspectRatio` in composed screens driven through [uiTest]: a portrait laid out in windows of
 * different sizes, a card a button reshapes, a strip of thumbnails a pad walks across, and clicks
 * that land where the shapes say they are.
 */
class AspectRatioTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(
        size: Size,
        backend: HeadlessBackend = HeadlessBackend(Rect.of(0f, 0f, size.width, size.height)),
        content: @Composable () -> Unit,
    ): UiTest = uiTest(size, backend, content = content).also { opened += it }

    /** A 3:4 portrait, so a box of the same shape draws it edge to edge. */
    private val portrait = FakeTexture(300, 400)

    private fun UiTest.bounds(tag: String): Rect = node(tag).boundsInRoot

    @Test
    fun `a full width portrait keeps its shape as the window changes width`() {
        for (width in listOf(300f, 150f)) {
            val backend = HeadlessBackend(Rect.of(0f, 0f, width, 800f))
            val ui = open(Size(width, 800f), backend) {
                Image(portrait, Modifier.fillMaxWidth().aspectRatio(3f / 4f).testTag("portrait"))
            }
            ui.render()

            assertEquals(Rect.of(0f, 0f, width, width * 4f / 3f), ui.bounds("portrait"), "at $width wide")
            val drawn = backend.canvas.calls.filterIsInstance<DrawCall.Image>().single()
            assertEquals(width, drawn.destination.width, "drawn edge to edge at $width wide")
            assertEquals(width * 4f / 3f, drawn.destination.height)
        }
    }

    @Test
    fun `a portrait frame in a slot too short for it is cut down rather than spilling out`() {
        // A plain box rather than an image: an image already keeps its picture's shape by itself.
        val ui = open(Size(300f, 200f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).testTag("portrait"))
        }

        assertEquals(Rect.of(0f, 0f, 300f, 200f), ui.bounds("portrait"), "clamped to the window")
    }

    @Test
    fun `a weighted wide frame in a narrow column keeps its height and a click below it misses it`() {
        val clicks = mutableListOf<String>()
        val ui = open(Size(120f, 300f)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).aspectRatio(16f / 9f).clickable { clicks += "frame" }.testTag("frame"))
                Box(Modifier.weight(1f).fillMaxWidth().clickable { clicks += "below" }.testTag("below"))
            }
        }

        // Each weight is 150 tall; 150 at 16:9 wants 266 wide and the column is 120, so the width
        // gives way and the height the weight asked for stays.
        assertEquals(Rect.of(0f, 0f, 120f, 150f), ui.bounds("frame"))
        assertEquals(150f, ui.bounds("below").top)
        ui.click(Offset(60f, 140f))
        ui.click(Offset(60f, 160f))
        assertEquals(listOf("frame", "below"), clicks)
    }

    @Test
    fun `a button that changes the ratio reshapes the card and moves what is under it`() {
        val clicks = mutableListOf<String>()
        val ui = open(Size(320f, 600f)) {
            var wide by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                Button("WIDEN", onClick = { wide = true }, modifier = Modifier.testTag("widen"))
                Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f).testTag("card"))
                Box(Modifier.fillMaxWidth().height(40f).clickable { clicks += "below" }.testTag("below"))
            }
        }
        val top = ui.bounds("card").top
        assertEquals(320f, ui.bounds("card").height, "square to begin with")

        ui.click("widen")

        assertEquals(180f, ui.bounds("card").height, "320 wide at 16:9")
        assertEquals(top + 180f, ui.bounds("below").top, "the box under it moved up with it")
        assertTrue(ui.click(Offset(160f, top + 190f)), "where the card used to be is now the box below")
        assertEquals(listOf("below"), clicks)
    }

    @Test
    fun `the pad walks across square thumbnails and a click picks one`() {
        var picked = -1
        val ui = open(Size(400f, 300f)) {
            Row(Modifier.fillMaxWidth()) {
                repeat(4) { index ->
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .focusable(initial = index == 0)
                            .clickable { picked = index }
                            .testTag("thumb$index"),
                    )
                }
            }
        }
        for (index in 0 until 4) {
            assertEquals(Rect.of(index * 100f, 0f, 100f, 100f), ui.bounds("thumb$index"))
        }

        ui.assertFocused("thumb0")
        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("thumb2")

        // Without the ratio every thumbnail is nothing tall and there is nothing here to click.
        ui.click(Offset(350f, 90f))
        assertEquals(3, picked)
    }

    @Test
    fun `a click lands on the card its shape covers and not on the one below`() {
        val clicks = mutableListOf<String>()
        val ui = open(Size(400f, 800f)) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f).clickable { clicks += "card" })
                Box(Modifier.fillMaxWidth().height(50f).clickable { clicks += "below" })
            }
        }

        // 400 wide at 2:1 is 200 tall, so y = 190 is the card and y = 210 is the box under it.
        ui.click(Offset(200f, 190f))
        ui.click(Offset(200f, 210f))

        assertEquals(listOf("card", "below"), clicks)
    }

    @Test
    fun `a sideways gallery takes each width from the height it is given`() {
        val ui = open(Size(300f, 120f)) {
            ScrollArea(Modifier.fillMaxSize(), horizontal = true, vertical = false, bars = false) {
                Row(Modifier.fillMaxHeight()) {
                    repeat(3) { Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).testTag("shot$it")) }
                }
            }
        }

        // The width is unbounded, so the height is the settled axis: 120 tall at 16:9 is 213.33 wide.
        assertEquals(120f, ui.bounds("shot0").height)
        assertEquals(120f * 16f / 9f, ui.bounds("shot0").width, 0.01f)
        assertEquals(2 * 120f * 16f / 9f, ui.node("shot2").layoutBoundsInRoot.left, 0.01f)
    }

    @Test
    fun `a window with no room gives a shaped node no room and nothing breaks`() {
        val ui = open(Size(0f, 0f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f).testTag("frame")) {
                Box(Modifier.aspectRatio(1f).testTag("inner"))
            }
        }
        ui.render()

        assertEquals(0f, ui.node("frame").width)
        assertEquals(0f, ui.node("frame").height)
        assertEquals(0f, ui.node("inner").height)
    }

    @Test
    fun `a shape inside a shape takes its room from the outer one`() {
        val ui = open(Size(400f, 800f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f).testTag("frame")) {
                Box(Modifier.aspectRatio(1f).testTag("inner"))
            }
        }

        assertEquals(Rect.of(0f, 0f, 400f, 200f), ui.bounds("frame"))
        assertEquals(Rect.of(0f, 0f, 200f, 200f), ui.bounds("inner"), "the biggest square in 400 by 200")
    }

    @Test
    fun `a still screen with a shaped node does not redraw`() {
        val ui = open(Size(400f, 300f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Colour.White))
        }
        ui.render()

        assertFalse(ui.render(), "nothing changed, so nothing is drawn again")
    }
}
