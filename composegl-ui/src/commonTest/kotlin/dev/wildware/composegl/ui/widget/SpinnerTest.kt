package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.StateStyle
import dev.wildware.composegl.ui.skin.Style
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The spinner and the indeterminate bar: where they are at a given time, and on a composed screen
 * with time passed a frame at a time and buttons pressed the way a player presses them. Every check
 * reads what reached the canvas, or what the host said about the frame.
 */
class SpinnerTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    /** Everything [tag] drew, on its own. */
    private fun UiTest.drawn(tag: String): List<DrawCall> {
        val node = node(tag)
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        val bounds = node.layoutBoundsInRoot
        DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
        canvas.assertBalanced()
        return canvas.calls
    }

    /** One more frame of the host on its own, 16 ms after the last, saying whether it changed anything. */
    private fun UiTest.nextFrame(): Boolean = host.frame(host.clocks.frameNanos + 16_000_000L)

    private fun UiTest.fans(tag: String): List<DrawCall.Fan> = drawn(tag).filterIsInstance<DrawCall.Fan>()

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)
    private val white = Colour.rgb(0xFFFFFF)
    private val black = Colour.rgb(0x000000)

    // --- where the arc is -----------------------------------------------------------------------

    @Test
    fun `the arc starts at twelve o'clock at its shortest`() {
        val arc = spinnerArcAt(0L, 1000)

        assertEquals(270f, arc.startDegrees, 0.001f)
        assertEquals(SpinnerMinSweep, arc.sweepDegrees, 0.001f)
    }

    @Test
    fun `the arc stretches in the first half of a turn and shrinks in the second`() {
        val quarter = spinnerArcAt(250_000_000L, 1000)
        val half = spinnerArcAt(500_000_000L, 1000)
        val threeQuarters = spinnerArcAt(750_000_000L, 1000)

        assertTrue(quarter.sweepDegrees > SpinnerMinSweep, "growing: $quarter")
        assertEquals(SpinnerMinSweep + SpinnerStretch, half.sweepDegrees, 0.001f)
        assertTrue(threeQuarters.sweepDegrees < half.sweepDegrees, "shrinking: $threeQuarters")
    }

    @Test
    fun `one turn runs into the next without a jump`() {
        repeat(40) { turn ->
            val end = (turn + 1) * 1_000_000_000L
            val before = spinnerArcAt(end - 1_000L, 1000)
            val after = spinnerArcAt(end, 1000)
            val gap = abs(before.startDegrees - after.startDegrees).let { minOf(it, 360f - it) }
            assertTrue(gap < 0.1f, "turn $turn jumped from $before to $after")
            assertEquals(before.sweepDegrees, after.sweepDegrees, 0.1f)
        }
    }

    @Test
    fun `the arc keeps going clockwise and never grows past its stretch`() {
        var previous = spinnerArcAt(0L, 1000)
        for (millis in 10L..5_000L step 10L) {
            val arc = spinnerArcAt(millis * 1_000_000L, 1000)
            // The head, where the arc ends, only ever moves forwards.
            val head = (previous.startDegrees + previous.sweepDegrees)
            val moved = (arc.startDegrees + arc.sweepDegrees - head).mod(360f)
            assertTrue(moved in 0f..180f, "at $millis ms the head went backwards: $previous then $arc")
            assertTrue(arc.sweepDegrees in SpinnerMinSweep..(SpinnerMinSweep + SpinnerStretch + 0.01f))
            assertTrue(arc.startDegrees in 0f..360f)
            previous = arc
        }
    }

    @Test
    fun `the bar's block slides in from off one end to off the other`() {
        assertEquals(-50f, indeterminateBlockAt(0L, 1000, 200f, 50f), 0.001f)
        assertEquals(75f, indeterminateBlockAt(500_000_000L, 1000, 200f, 50f), 0.001f)
        assertTrue(indeterminateBlockAt(999_000_000L, 1000, 200f, 50f) > 199f)
        assertEquals(-50f, indeterminateBlockAt(1_000_000_000L, 1000, 200f, 50f), 0.001f, "and round again")
    }

    // --- on a screen ----------------------------------------------------------------------------

    @Test
    fun `a spinner turns as time passes and nothing around it recomposes`() {
        var screen = 0
        var column = 0
        val ui = open {
            screen++
            Column {
                column++
                Text("SAVING")
                Spinner(Modifier.testTag("spin"))
            }
        }
        val composedScreen = screen
        val composedColumn = column

        val first = ui.fans("spin")
        ui.advanceBy(130)
        val second = ui.fans("spin")
        ui.advanceBy(370)
        val third = ui.fans("spin")

        assertNotEquals(first, second, "the arc did not move")
        assertNotEquals(second, third, "the arc did not move")
        assertEquals(composedScreen, screen, "the screen recomposed for a spinner")
        assertEquals(composedColumn, column, "the column recomposed for a spinner")
    }

    @Test
    fun `each frame a spinner turns is a frame to draw but only a redraw`() {
        val ui = open { Spinner(Modifier.testTag("spin")) }

        assertTrue(ui.nextFrame(), "a turning spinner has to be drawn again")
        assertTrue(ui.host.onlyRedrawn, "and nothing but the drawing changed")
    }

    @Test
    fun `a spinner on a stopped clock asks for no frames`() {
        val ui = open { Spinner(Modifier.testTag("spin"), clock = Clock.World) }
        ui.host.clocks.stop(Clock.World)

        ui.nextFrame()

        assertFalse(ui.nextFrame(), "a spinner that is not turning should cost nothing")
    }

    @Test
    fun `a spinner taken off the screen stops asking for frames`() {
        var saving by mutableStateOf(true)
        val ui = open {
            if (saving) Spinner(Modifier.testTag("spin"))
        }
        ui.advanceBy(100)

        saving = false
        ui.settle()
        ui.assertDoesNotExist("spin")

        assertFalse(ui.nextFrame(), "a spinner that has gone is still asking to be drawn")
    }

    @Test
    fun `a spinner in an open dropdown list keeps turning`() {
        // A popup builds its contents before its node is on the screen, so this is a spinner whose
        // node only joins the tree after it has been composed.
        val ui = open {
            PopupHost {
                var server by remember { mutableStateOf("EUROPE") }
                Dropdown(
                    options = listOf("EUROPE", "ASIA"),
                    selected = server,
                    onSelect = { server = it },
                    modifier = Modifier.width(200f).testTag("server"),
                    initialFocus = true,
                ) { option ->
                    // Only the list's copy of ASIA spins: the field shows EUROPE.
                    if (option == "ASIA") Spinner(Modifier.testTag("connecting")) else Text(option)
                }
            }
        }
        ui.assertDoesNotExist("connecting")

        // Opened with the pad, the way a player on a sofa opens it.
        ui.pad(GamepadButton.South)
        ui.node("connecting")

        assertTrue(ui.nextFrame(), "a spinner in a popup has to be drawn again")
        assertTrue(ui.nextFrame(), "and again the frame after")
        val first = ui.fans("connecting")
        ui.advanceBy(130)
        assertNotEquals(first, ui.fans("connecting"), "the arc in the popup did not move")

        // Closed with the keyboard: the spinner goes with the list and stops asking for frames.
        ui.key(Key.Escape)
        ui.settle()
        ui.assertDoesNotExist("connecting")
        ui.nextFrame()
        assertFalse(ui.nextFrame(), "a spinner in a closed list is still asking to be drawn")
    }

    @Test
    fun `a spinner lifted out of the tree and put back turns again`() {
        val ui = open {
            Column {
                Text("SAVING")
                Spinner(Modifier.testTag("spin"))
            }
        }
        val spinner = ui.node("spin")
        val column = checkNotNull(spinner.parent)
        ui.nextFrame()

        // Lifted out while its composition lives on, as a node moving between trees is.
        column.removeAt(1, 1)
        ui.nextFrame()
        assertFalse(ui.nextFrame(), "a spinner out of the tree is still asking to be drawn")

        column.insertAt(1, spinner)
        ui.nextFrame()
        assertTrue(ui.nextFrame(), "a spinner put back did not start turning again")
        val first = ui.fans("spin")
        ui.advanceBy(130)
        assertNotEquals(first, ui.fans("spin"), "the arc put back did not move")
    }

    @Test
    fun `an indeterminate bar in a popup keeps moving`() {
        val ui = open {
            PopupHost {
                var server by remember { mutableStateOf("EUROPE") }
                Dropdown(
                    options = listOf("EUROPE", "ASIA"),
                    selected = server,
                    onSelect = { server = it },
                    modifier = Modifier.width(200f).testTag("server"),
                ) { option ->
                    if (option == "ASIA") {
                        ProvideSkin(barSkin) { IndeterminateBar(Modifier.width(100f).testTag("finding")) }
                    } else {
                        Text(option)
                    }
                }
            }
        }

        // Opened with the mouse.
        assertTrue(ui.click("server"))
        ui.node("finding")

        assertTrue(ui.nextFrame(), "a bar in a popup has to be drawn again")
        val moving = ui.block("finding")
        ui.advanceBy(200)
        assertNotEquals(moving, ui.block("finding"), "the block in the popup did not move")
    }

    @Test
    fun `a pause button freezes a spinner on the world clock and one on the interface clock keeps turning`() {
        val ui = open {
            val clocks = LocalClocks.current
            var paused by remember { mutableStateOf(false) }
            Column {
                Button(
                    if (paused) "RESUME" else "PAUSE",
                    onClick = {
                        paused = !paused
                        clocks.setRunning(Clock.World, !paused)
                    },
                    initialFocus = true,
                    modifier = Modifier.testTag("pause"),
                )
                Spinner(Modifier.testTag("chest"), clock = Clock.World)
                Spinner(Modifier.testTag("save"))
            }
        }
        ui.advanceBy(200)

        // Paused with the mouse.
        ui.click("pause")
        val chest = ui.fans("chest")
        val save = ui.fans("save")
        ui.advanceBy(300)
        assertEquals(chest, ui.fans("chest"), "the world's spinner kept turning through the pause")
        assertNotEquals(save, ui.fans("save"), "the menu's spinner stopped with the world")

        // Resumed with the pad, and paused again with the keyboard.
        ui.pad(GamepadButton.South)
        ui.advanceBy(300)
        assertNotEquals(chest, ui.fans("chest"), "the world's spinner did not start again")

        ui.key(Key.Enter)
        val frozen = ui.fans("chest")
        ui.advanceBy(300)
        assertEquals(frozen, ui.fans("chest"))
    }

    @Test
    fun `the pad and the keyboard step over a spinner and a click on one does nothing`() {
        val ui = open {
            Column {
                Button("RETRY", onClick = {}, initialFocus = true, modifier = Modifier.testTag("retry"))
                Spinner(Modifier.testTag("spin"))
                IndeterminateBar(Modifier.testTag("bar"))
                Button("CANCEL", onClick = {}, modifier = Modifier.testTag("cancel"))
            }
        }

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("cancel")
        ui.key(Key.Up)
        ui.assertFocused("retry")

        assertFalse(ui.click("spin"), "a spinner took a click")
        assertFalse(ui.click("bar"), "an indeterminate bar took a click")
        ui.assertFocused("retry")
    }

    @Test
    fun `a spinner is twenty-four across unless given a size and its arc sits inside the circle`() {
        val ui = open {
            Column {
                Spinner(Modifier.testTag("small"))
                Spinner(Modifier.size(60f, 40f).testTag("big"), thickness = 4f)
            }
        }
        ui.advanceBy(300)

        assertEquals(24f, ui.node("small").width)
        assertEquals(24f, ui.node("small").height)

        val big = ui.node("big").layoutBoundsInRoot
        val centre = Offset(big.left + 30f, big.top + 20f)
        val points = ui.fans("big").flatMap { it.points }
        assertTrue(points.isNotEmpty())
        points.forEach { point ->
            val distance = sqrt((point.x - centre.x) * (point.x - centre.x) + (point.y - centre.y) * (point.y - centre.y))
            assertTrue(distance in 15.99f..20.01f, "$point is $distance from the middle, outside a ring from 16 to 20")
        }
    }

    @Test
    fun `the arc's colour and the track under it are the skin's`() {
        val arcOnly = Skin(styles = mapOf("spinner" to Style(StateStyle(textColour = red))))
        val withTrack = Skin(
            styles = mapOf(
                "spinner" to Style(StateStyle(textColour = red)),
                "spinner.track" to Style(StateStyle(textColour = green)),
            ),
        )
        val ui = open {
            Column {
                ProvideSkin(arcOnly) { Spinner(Modifier.testTag("plain")) }
                ProvideSkin(withTrack) { Spinner(Modifier.testTag("tracked")) }
                Spinner(Modifier.testTag("default"))
            }
        }
        ui.advanceBy(300)

        assertEquals(setOf(red), ui.fans("plain").map { it.colour }.toSet(), "no track the skin did not name")

        val tracked = ui.fans("tracked")
        val firstRed = tracked.indexOfFirst { it.colour == red }
        assertTrue(firstRed > 0, "the track goes under the arc")
        assertTrue(tracked.take(firstRed).all { it.colour == green })

        val standard = ui.fans("default").map { it.colour }.toSet()
        assertEquals(setOf(Colour.rgb(0x2C3545), Colour.rgb(0x5B8DEF)), standard, "the default skin's spinner")
    }

    @Test
    fun `a spinner can be a sprite sheet instead`() {
        class Frame(val name: String) : TextureHandle {
            override val width = 16
            override val height = 16
        }
        val strip = listOf(Frame("a"), Frame("b"), Frame("c"))
        val ui = open { Spinner(rememberSpriteAnimation(strip, fps = 2f), Modifier.testTag("spin")) }

        val seen = mutableListOf<TextureHandle>()
        repeat(4) {
            seen += ui.drawn("spin").filterIsInstance<DrawCall.Image>().single().texture
            ui.advanceBy(500)
        }

        assertEquals(listOf<TextureHandle>(strip[0], strip[1], strip[2], strip[0]), seen)
    }

    @Test
    fun `a sprite spinner that does not loop is refused`() {
        val frame = object : TextureHandle {
            override val width = 16
            override val height = 16
        }
        assertFailsWith<IllegalArgumentException> {
            open { Spinner(rememberSpriteAnimation(listOf(frame), fps = 2f, loop = false)) }
        }
    }

    // --- the indeterminate bar ------------------------------------------------------------------

    private val barSkin = Skin(
        styles = mapOf(
            "indeterminatebar.track" to Style(StateStyle(background = SkinDrawable.Fill(black))),
            "indeterminatebar.fill" to Style(StateStyle(background = SkinDrawable.Fill(white))),
        ),
    )

    private fun UiTest.block(tag: String): DrawCall.Rectangle =
        drawn(tag).filterIsInstance<DrawCall.Rectangle>().single { it.colour == white }

    private fun UiTest.worldTime(): Long = host.clocks.time(Clock.Ui)

    @Test
    fun `a bar's block slides left to right along the track and is cut to it`() {
        val ui = open {
            ProvideSkin(barSkin) {
                IndeterminateBar(Modifier.width(200f).testTag("bar"), blockFraction = 0.25f, sweepMillis = 2000)
            }
        }
        val bar = ui.node("bar").layoutBoundsInRoot
        assertEquals(200f, bar.width)
        assertEquals(6f, bar.height)

        val lefts = (0 until 3).map {
            ui.advanceBy(400)
            val block = ui.block("bar")
            assertEquals(bar.left + indeterminateBlockAt(ui.worldTime(), 2000, 200f, 50f), block.rect.left, 0.01f)
            assertEquals(50f, block.rect.width, 0.01f)
            assertTrue(block.clip.left >= bar.left && block.clip.right <= bar.right, "the block is not cut to the bar")
            block.rect.left
        }
        assertTrue(lefts[0] < lefts[1] && lefts[1] < lefts[2], "not moving right: $lefts")

        val track = ui.drawn("bar").filterIsInstance<DrawCall.Rectangle>().single { it.colour == black }
        assertEquals(bar, track.rect, "the track is under the whole bar")
    }

    @Test
    fun `on a right-to-left screen the block runs from right to left`() {
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                ProvideSkin(barSkin) {
                    IndeterminateBar(Modifier.width(200f).testTag("bar"), blockFraction = 0.25f, sweepMillis = 2000)
                }
            }
        }
        val bar = ui.node("bar").layoutBoundsInRoot

        val rights = (0 until 3).map {
            ui.advanceBy(400)
            val block = ui.block("bar")
            assertEquals(bar.right - indeterminateBlockAt(ui.worldTime(), 2000, 200f, 50f), block.rect.right, 0.01f)
            block.rect.right
        }
        assertTrue(rights[0] > rights[1] && rights[1] > rights[2], "not moving left: $rights")
    }

    @Test
    fun `a vertical bar runs up from the bottom`() {
        val ui = open {
            ProvideSkin(barSkin) {
                IndeterminateBar(
                    Modifier.testTag("bar"),
                    orientation = Orientation.Vertical,
                    length = 120f,
                    thickness = 8f,
                    sweepMillis = 2000,
                )
            }
        }
        val bar = ui.node("bar").layoutBoundsInRoot
        assertEquals(8f, bar.width)
        assertEquals(120f, bar.height)

        val bottoms = (0 until 3).map {
            ui.advanceBy(400)
            ui.block("bar").rect.bottom
        }
        assertTrue(bottoms[0] > bottoms[1] && bottoms[1] > bottoms[2], "not moving up: $bottoms")
    }

    @Test
    fun `a bar moves without recomposing and freezes on a stopped clock`() {
        var composed = 0
        val ui = open {
            composed++
            ProvideSkin(barSkin) {
                IndeterminateBar(Modifier.width(200f).testTag("bar"), clock = Clock.World, sweepMillis = 2000)
            }
        }
        val before = composed
        ui.advanceBy(200)
        val moving = ui.block("bar")
        ui.advanceBy(200)
        assertNotEquals(moving, ui.block("bar"))
        assertEquals(before, composed, "the screen recomposed for a bar")

        ui.host.clocks.stop(Clock.World)
        ui.advanceBy(100)
        val frozen = ui.block("bar")
        ui.advanceBy(400)
        assertEquals(frozen, ui.block("bar"))
    }
}
