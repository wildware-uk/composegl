package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Layers that drift against a pointer, a stick or a scroll position, at rates of their own.
 *
 * The first half asks the sources where they think rest is. The second half composes real screens
 * with [uiTest], moves a real pointer, stick or wheel through a sink wrapped in [ParallaxAware] as a
 * game's is, and asks where the layers ended up, what was drawn, and what can be clicked.
 */
class ParallaxTest {

    private val opened = mutableListOf<UiTest>()

    /** The middle of the 400 by 400 the screens here are laid out in. */
    private val middle = Offset(200f, 200f)

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    /**
     * A 400 by 400 screen whose input passes [pointer] and [stick] on the way in, the way a game
     * wraps its sink in [ParallaxAware].
     */
    private fun open(
        pointer: PointerParallax? = null,
        stick: StickParallax? = null,
        backend: HeadlessBackend = HeadlessBackend(),
        content: @Composable () -> Unit,
    ): UiTest = uiTest(
        Size(400f, 400f),
        backend,
        input = { ParallaxAware(it, pointer, stick) },
        content = content,
    ).also { opened += it }

    private fun UiTest.at(tag: String) = node(tag).boundsInRoot

    private fun move(to: Offset) = PointerEvent.Move(PointerId.Mouse, to)

    private fun tilt(axis: GamepadAxis, value: Float) = GamepadEvent.Axis(GamepadId(0), axis, value)

    // --- the sources ---------------------------------------------------------------------------

    @Test
    fun `a pointer nobody has moved is at rest`() {
        assertEquals(Offset.Zero, PointerParallax(middle).position)
    }

    @Test
    fun `a pointer is as far from rest as it is from the centre`() {
        val pointer = PointerParallax(middle)

        pointer.saw(move(Offset(260f, 170f)))

        assertEquals(Offset(60f, -30f), pointer.position)
    }

    @Test
    fun `a press moves the source as well as a hover does`() {
        val pointer = PointerParallax(middle)

        pointer.saw(PointerEvent.Press(PointerId.Mouse, Offset(210f, 220f)))

        assertEquals(Offset(10f, 20f), pointer.position)
    }

    @Test
    fun `the mouse leaving the window puts the source back at rest`() {
        val pointer = PointerParallax(middle)
        pointer.saw(move(Offset(390f, 10f)))

        pointer.saw(PointerEvent.Exit(PointerId.Mouse, Offset(390f, 10f)))

        assertEquals(Offset.Zero, pointer.position)
    }

    @Test
    fun `a finger lifting puts the source back at rest`() {
        val pointer = PointerParallax(middle)
        val at = Offset(300f, 300f)
        pointer.saw(PointerEvent.Press(PointerId(7), at, type = PointerType.Touch))

        pointer.saw(PointerEvent.Release(PointerId(7), at, type = PointerType.Touch))

        assertEquals(Offset.Zero, pointer.position, "a finger that has gone has no place to lean towards")
    }

    @Test
    fun `a mouse letting go stays where it is`() {
        val pointer = PointerParallax(middle)
        val at = Offset(300f, 300f)
        pointer.saw(PointerEvent.Press(PointerId.Mouse, at))

        pointer.saw(PointerEvent.Release(PointerId.Mouse, at))

        assertEquals(Offset(100f, 100f), pointer.position)
    }

    @Test
    fun `a cancelled gesture leaves the source where the pointer was`() {
        val pointer = PointerParallax(middle)
        pointer.saw(move(Offset(250f, 200f)))

        pointer.saw(PointerEvent.Cancel(PointerId.Mouse, Offset(0f, 0f)))
        pointer.saw(PointerEvent.Scroll(PointerId.Mouse, Offset(0f, 0f), delta = Offset(0f, 1f)))

        assertEquals(Offset(50f, 0f), pointer.position)
    }

    @Test
    fun `moving the centre moves rest`() {
        val pointer = PointerParallax(middle)
        pointer.saw(move(Offset(250f, 200f)))

        pointer.centre = Offset(250f, 200f)

        assertEquals(Offset.Zero, pointer.position)
    }

    @Test
    fun `a stick pushed all the way is worth its reach`() {
        val stick = StickParallax(reach = Offset(200f, 100f))

        stick.saw(tilt(GamepadAxis.RightX, 1f))
        stick.saw(tilt(GamepadAxis.RightY, -0.5f))

        assertEquals(Offset(200f, -50f), stick.position)
    }

    @Test
    fun `the stick a source is not watching moves nothing`() {
        val stick = StickParallax(reach = 100f)

        stick.saw(tilt(GamepadAxis.LeftX, 1f))
        stick.saw(tilt(GamepadAxis.RightTrigger, 1f))

        assertEquals(Offset.Zero, stick.position, "the left stick is moving focus, not the scenery")
    }

    @Test
    fun `a source can be pointed at the left stick`() {
        val stick = StickParallax(reach = 100f, x = GamepadAxis.LeftX, y = GamepadAxis.LeftY)

        stick.saw(tilt(GamepadAxis.LeftY, 1f))

        assertEquals(Offset(0f, 100f), stick.position)
    }

    @Test
    fun `a pad pulled out lets the layer come back to rest`() {
        val stick = StickParallax(reach = 100f)
        stick.saw(tilt(GamepadAxis.RightX, 1f))

        stick.saw(GamepadEvent.Disconnected(GamepadId(0)))

        assertEquals(Offset.Zero, stick.position)
    }

    @Test
    fun `a scroll source moves the opposite way to the scroll position`() {
        val state = ScrollState(initialX = 0f, initialY = 0f)
        val source = ScrollParallax(state)
        assertEquals(Offset.Zero, source.position)

        // Measured first, or there is nowhere to scroll to and the position clamps back to zero.
        state.measured(viewport = Size(100f, 100f), content = Size(500f, 500f))
        state.scrollTo(x = 12f, y = 30f)

        assertEquals(Offset(-12f, -30f), source.position, "the rows went up, so a layer following them goes up")
    }

    // --- the modifier --------------------------------------------------------------------------

    @Test
    fun `the offset is the factor times how far the source has moved`() {
        val pointer = PointerParallax(middle)
        pointer.saw(move(Offset(300f, 150f)))

        assertEquals(Offset(10f, -5f), Modifier.parallax(pointer, 0.1f).resolve().offset)
        assertEquals(Offset(-50f, 25f), Modifier.parallax(pointer, -0.5f).resolve().offset)
    }

    @Test
    fun `a source at rest adds nothing`() {
        assertEquals(Offset.Zero, Modifier.parallax(PointerParallax(middle), 0.5f).resolve().offset)
    }

    @Test
    fun `parallax adds to an offset already on the node`() {
        val stick = StickParallax(reach = 10f)
        stick.saw(tilt(GamepadAxis.RightX, 1f))

        val resolved = Modifier.offset(x = 4f, y = 2f).parallax(stick, 1f).resolve()

        assertEquals(Offset(14f, 2f), resolved.offset)
    }

    @Test
    fun `a factor that is not a number is refused`() {
        assertFailsWith<IllegalArgumentException> { Modifier.parallax(PointerParallax(middle), Float.NaN) }
        assertFailsWith<IllegalArgumentException> {
            Modifier.parallax(PointerParallax(middle), Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `the sink a source wraps still gives its own answers`() {
        val pointer = PointerParallax(middle)
        val stick = StickParallax(reach = 10f)
        val answers = object : InputSink {
            override fun onPointer(event: PointerEvent) = true
            override fun onKey(event: KeyEvent) = true
            override fun onText(event: TextEvent) = false
            override fun onGamepad(event: GamepadEvent) = false
        }
        val wrapped = ParallaxAware(answers, pointer, stick)

        assertTrue(wrapped.onPointer(move(Offset(210f, 200f))))
        assertFalse(wrapped.onGamepad(tilt(GamepadAxis.RightX, 1f)))

        assertEquals(Offset(10f, 0f), pointer.position, "it saw the pointer on the way through")
        assertEquals(Offset(10f, 0f), stick.position, "and the stick")
    }

    // --- on a real screen ----------------------------------------------------------------------

    @Test
    fun `layers under a moving pointer drift at their own rates`() {
        val pointer = PointerParallax(middle)
        val ui = open(pointer) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(100f, 100f).parallax(pointer, 0.1f).testTag("far"))
                Box(Modifier.size(100f, 100f).parallax(pointer, 0.5f).testTag("near"))
            }
        }
        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("far"))
        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("near"))

        ui.moveTo(Offset(300f, 250f))

        assertEquals(Rect.of(10f, 5f, 100f, 100f), ui.at("far"), "the far layer creeps")
        assertEquals(Rect.of(50f, 25f, 100f, 100f), ui.at("near"), "the near one runs")

        ui.moveTo(middle)

        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("far"), "and both settle home")
        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("near"))
    }

    @Test
    fun `a drifting layer does not move its neighbours or its parent`() {
        val pointer = PointerParallax(middle)
        val ui = open(pointer) {
            Column(Modifier.testTag("column")) {
                Box(Modifier.size(80f, 40f).parallax(pointer, 1f).testTag("layer"))
                Box(Modifier.size(80f, 40f).testTag("below"))
            }
        }
        val column = ui.at("column")
        val below = ui.at("below")

        ui.moveTo(Offset(230f, 260f))

        assertEquals(Rect.of(30f, 60f, 80f, 40f), ui.at("layer"))
        assertEquals(below, ui.at("below"), "the sibling keeps its place")
        assertEquals(column, ui.at("column"), "the column keeps its size")
    }

    @Test
    fun `a button on a drifting layer is clicked where it is drawn`() {
        val pointer = PointerParallax(middle)
        var clicks = 0
        // At rest the button covers 30 to 70. Leaning away at a half, a pointer at 100 has pushed it
        // fifty along, to 80 to 120: under the pointer that pushed it there.
        val ui = open(pointer) {
            Box(Modifier.fillMaxSize()) {
                Button(
                    "GO",
                    onClick = { clicks++ },
                    modifier = Modifier.offset(30f, 30f).size(40f, 40f).parallax(pointer, -0.5f).testTag("go"),
                )
            }
        }
        assertEquals(Rect.of(30f, 30f, 40f, 40f), ui.at("go"))

        ui.click(Offset(50f, 50f))
        assertEquals(0, clicks, "going to where it sat at rest pushed it away before the press landed")

        ui.click(Offset(100f, 100f))
        assertEquals(Rect.of(80f, 80f, 40f, 40f), ui.at("go"))
        assertEquals(1, clicks, "where it is drawn is where it is pressed")
    }

    @Test
    fun `a still source costs the frame nothing even when the layer recomposes`() {
        val pointer = PointerParallax(middle)
        var tick by mutableStateOf(0)
        var compositions = 0
        val ui = open(pointer) {
            // Read here so a tick recomposes the scope that writes the modifier, and nothing else.
            tick.let { SideEffect { compositions++ } }
            Box(Modifier.size(50f, 50f).parallax(pointer, -0.2f))
        }
        ui.render()

        // Each write below is picked up by the render that follows, so its answer is about that write.
        // The harness settles after every action, which would leave a render nothing to report.
        pointer.saw(move(Offset(250f, 200f)))
        assertTrue(ui.render(), "a source that moved is a frame to draw, so a false below means something")

        var before = compositions
        tick++
        assertFalse(ui.render(), "the scope recomposed and wrote an equal offset, so nothing needs drawing")
        assertTrue(compositions > before, "the layer's scope did recompose")

        ui.moveTo(Offset(250f, 200f))
        assertFalse(ui.render(), "a pointer moved to where it already was is nothing to draw")

        ui.moveTo(middle)
        before = compositions
        tick++
        assertFalse(ui.render(), "rest times a negative factor is still an equal offset")
        assertTrue(compositions > before)
    }

    @Test
    fun `the right stick moves a layer and letting go brings it back`() {
        val stick = StickParallax(reach = 200f)
        val ui = open(stick = stick) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(100f, 100f).parallax(stick, -0.1f).testTag("sky"))
            }
        }

        ui.stick(1f, 0.5f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)
        assertEquals(Rect.of(-20f, -10f, 100f, 100f), ui.at("sky"), "it leans away")

        ui.stick(0f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)
        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("sky"))
    }

    @Test
    fun `the left stick moves focus and leaves the scenery alone`() {
        val stick = StickParallax(reach = 200f)
        val ui = open(stick = stick) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(100f, 100f).parallax(stick, -0.1f).testTag("sky"))
                Column(Modifier.offset(150f, 150f)) {
                    Button("PLAY", onClick = {}, initialFocus = true, modifier = Modifier.testTag("play"))
                    Button("QUIT", onClick = {}, modifier = Modifier.testTag("quit"))
                }
            }
        }

        ui.stick(0f, 1f)

        ui.assertFocused("quit")
        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("sky"))
    }

    @Test
    fun `a layer behind a scroll area drifts at half the speed of the rows`() {
        val state = ScrollState()
        val scrolled = ScrollParallax(state)
        val ui = open {
            Box(Modifier.size(200f, 100f)) {
                Box(Modifier.size(200f, 100f).parallax(scrolled, 0.5f).testTag("hills"))
                ScrollArea(Modifier.size(200f, 100f).testTag("list"), state = state, bars = false) {
                    Column {
                        repeat(20) { row -> Box(Modifier.size(200f, 40f).testTag("row $row")) }
                    }
                }
            }
        }
        val firstRow = ui.at("row 0").top

        ui.scroll("list", Offset(0f, 1f))

        val rowsMoved = firstRow - ui.at("row 0").top
        assertTrue(rowsMoved > 0f, "the wheel scrolled the rows: $rowsMoved")
        assertEquals(-rowsMoved / 2f, ui.at("hills").top, "the hills went half as far")
    }

    @Test
    fun `the drift is where the layer is drawn`() {
        val pointer = PointerParallax(middle)
        val backend = HeadlessBackend()
        val ui = open(pointer, backend = backend) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(60f, 40f).parallax(pointer, 0.25f).background(Colour.White))
            }
        }
        ui.render()
        assertEquals(Rect.of(0f, 0f, 60f, 40f), backend.canvas.only<DrawCall.Rectangle>().last().rect)

        ui.moveTo(Offset(120f, 280f))
        ui.render()

        assertEquals(Rect.of(-20f, 20f, 60f, 40f), backend.canvas.only<DrawCall.Rectangle>().last().rect)
    }

    @Test
    fun `a layer inside a drifting layer drifts by both`() {
        val pointer = PointerParallax(middle)
        val stick = StickParallax(reach = 100f)
        val ui = open(pointer, stick) {
            Box(Modifier.size(200f, 200f).parallax(pointer, 0.5f).testTag("outer")) {
                // No size and no content: still a place, and still moved.
                Box(Modifier.parallax(stick, 0.1f).testTag("inner"))
            }
        }

        ui.moveTo(Offset(240f, 200f))
        ui.stick(0f, 1f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)

        assertEquals(Offset(20f, 0f), ui.at("outer").let { Offset(it.left, it.top) })
        assertEquals(Offset(20f, 10f), ui.at("inner").let { Offset(it.left, it.top) }, "its parent's drift and its own")
    }

    @Test
    fun `a layer that remembers its source keeps it across recomposition`() {
        var seen: PointerParallax? = null
        // The source only exists once the content composes, so the sink asks for it per event.
        val input = { sink: InputSink ->
            object : InputSink by sink {
                override fun onPointer(event: PointerEvent) = ParallaxAware(sink, pointer = seen).onPointer(event)
            }
        }
        val ui = uiTest(Size(400f, 400f), input = input) {
            val pointer = remember { PointerParallax(middle) }
            seen = pointer
            Box(Modifier.size(50f, 50f).parallax(pointer, 1f).testTag("layer"))
        }.also { opened += it }
        val pointer = seen!!
        ui.moveTo(Offset(205f, 207f))
        ui.moveTo(Offset(210f, 210f))

        assertEquals(Offset(10f, 10f), ui.at("layer").let { Offset(it.left, it.top) })
        assertTrue(seen === pointer, "the recompositions the moves caused kept the same source")
    }

    /** [open], but hands back the sink the screen's input goes into, for events the harness has no call for. */
    private fun openWithSink(
        pointer: PointerParallax? = null,
        stick: StickParallax? = null,
        content: @Composable () -> Unit,
    ): Pair<UiTest, InputSink> {
        var sink: InputSink? = null
        val ui = uiTest(
            Size(400f, 400f),
            input = { ParallaxAware(it, pointer, stick).also { wrapped -> sink = wrapped } },
            content = content,
        ).also { opened += it }
        return ui to sink!!
    }

    @Test
    fun `the mouse leaving the window lets the layers settle home`() {
        val pointer = PointerParallax(middle)
        val (ui, sink) = openWithSink(pointer) {
            Box(Modifier.size(100f, 100f).parallax(pointer, 0.5f).testTag("layer"))
        }
        ui.moveTo(Offset(380f, 20f))
        assertEquals(Rect.of(90f, -90f, 100f, 100f), ui.at("layer"))

        sink.onPointer(PointerEvent.Exit(PointerId.Mouse, Offset(380f, 20f)))
        ui.settle()

        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("layer"), "not left leaning at the edge it went out of")
    }

    @Test
    fun `a finger dragging a layer lets it go home when it lifts`() {
        val pointer = PointerParallax(middle)
        val (ui, sink) = openWithSink(pointer) {
            Box(Modifier.size(100f, 100f).parallax(pointer, -0.5f).testTag("layer"))
        }
        val finger = PointerId(3)

        sink.onPointer(PointerEvent.Press(finger, Offset(260f, 200f), type = PointerType.Touch))
        ui.settle()
        assertEquals(Rect.of(-30f, 0f, 100f, 100f), ui.at("layer"))

        sink.onPointer(PointerEvent.Release(finger, Offset(260f, 200f), type = PointerType.Touch))
        ui.settle()
        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("layer"))
    }

    @Test
    fun `lifting a second finger leaves the layer with the one still pressing`() {
        val pointer = PointerParallax(middle)
        val (ui, sink) = openWithSink(pointer) {
            Box(Modifier.size(100f, 100f).parallax(pointer, 1f).testTag("layer"))
        }
        val first = PointerId(3)
        val second = PointerId(4)

        sink.onPointer(PointerEvent.Press(second, Offset(100f, 100f), type = PointerType.Touch))
        sink.onPointer(PointerEvent.Press(first, Offset(260f, 200f), type = PointerType.Touch))
        sink.onPointer(PointerEvent.Release(second, Offset(100f, 100f), type = PointerType.Touch))
        ui.settle()

        assertEquals(Rect.of(60f, 0f, 100f, 100f), ui.at("layer"), "the finger still down is still leaning it")

        sink.onPointer(PointerEvent.Release(first, Offset(260f, 200f), type = PointerType.Touch))
        ui.settle()
        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("layer"), "and lifting that one sends it home")
    }

    @Test
    fun `unplugging a pad that is not leaning the layer leaves it leaning`() {
        val stick = StickParallax(reach = 100f)
        val (ui, sink) = openWithSink(stick = stick) {
            Box(Modifier.size(100f, 100f).parallax(stick, 0.5f).testTag("layer"))
        }
        ui.stick(1f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)

        sink.onGamepad(GamepadEvent.Disconnected(GamepadId(1)))
        ui.settle()
        assertEquals(Rect.of(50f, 0f, 100f, 100f), ui.at("layer"), "the other pad had nothing to do with it")

        sink.onGamepad(GamepadEvent.Disconnected(GamepadId.First))
        ui.settle()
        assertEquals(Rect.of(0f, 0f, 100f, 100f), ui.at("layer"), "the pad that pushed it going does")
    }

    @Test
    fun `moving the centre moves the layers on the screen`() {
        val pointer = PointerParallax(middle)
        val ui = open(pointer) {
            Box(Modifier.size(100f, 100f).parallax(pointer, 1f).testTag("layer"))
        }
        ui.moveTo(Offset(250f, 200f))
        assertEquals(50f, ui.at("layer").left)

        // The window grew, so its middle moved under a pointer that did not.
        pointer.centre = Offset(250f, 200f)
        ui.settle()

        assertEquals(0f, ui.at("layer").left)
    }

    @Test
    fun `a stick that reports minus zero at rest costs the frame nothing`() {
        val stick = StickParallax(reach = 100f)
        val (ui, sink) = openWithSink(stick = stick) {
            Box(Modifier.size(50f, 50f).parallax(stick, 1f))
        }
        ui.render()

        // Some drivers let go of a stick at -0. The source sees a new value, but the layer is where it was.
        sink.onGamepad(tilt(GamepadAxis.RightX, -0f))

        assertFalse(ui.render(), "an offset of -0 is the offset of 0, and nothing needs drawing")
    }

    @Test
    fun `a layer taken off the screen leaves its source still working`() {
        val pointer = PointerParallax(middle)
        var shown by mutableStateOf(true)
        val ui = open(pointer) {
            if (shown) Box(Modifier.size(50f, 50f).parallax(pointer, 1f).testTag("gone"))
            Box(Modifier.size(50f, 50f).parallax(pointer, -1f).testTag("stays"))
        }

        shown = false
        ui.settle()
        ui.moveTo(Offset(230f, 200f))

        ui.assertDoesNotExist("gone")
        assertEquals(-30f, ui.at("stays").left)
    }
}
