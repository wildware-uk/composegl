package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Several values moving off one state, driven the way a player drives them.
 *
 * Every test composes a real screen, presses it with a real pointer, key or pad button, moves time
 * on a frame at a time and reads back what was laid out and drawn. The question each one asks is the
 * one the issue asked: do the values move as one thing — starting, turning round and finishing
 * together — or as several things that happen to have been told the same news?
 */
class TransitionTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val renderer = UiRenderer(host, canvas).also { it.focus = focus }
    private val viewport = Viewport.oneToOne(Size(400f, 300f))
    private val pointer = PointerRouter(host.root, focus)

    private var wall = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(3)
    }

    /** One frame, [millis] after the last, drawn from scratch. Returns whether anything changed. */
    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        canvas.clear()
        return renderer.render(viewport, wall)
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    /** Plays [millis] of real time at sixty frames a second. */
    private fun play(millis: Int) = frames((millis + 15) / 16)

    private fun press(tag: String) {
        val at = host.root.find(tag).boundsInRoot.centre
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
    }

    private fun release(tag: String) {
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, host.root.find(tag).layoutBoundsInRoot.centre))
    }

    /** The button's fill as last drawn. */
    private fun drawnColour(): Colour =
        assertNotNull(canvas.only<DrawCall.Rectangle>().lastOrNull(), "nothing was drawn:\n$canvas").colour

    /** How far the button has got from [Light] to [Dark], judged by the red it was drawn with. */
    private fun colourProgress(): Float = (drawnColour().red - Light.red) / (Dark.red - Light.red).toFloat()

    /** How far the button has got from full size to half, judged by the width it was drawn at. */
    private fun scaleProgress(): Float = (100f - host.root.find("button").boundsInRoot.width) / 50f

    /** A 100-pixel button that shrinks to half and darkens while held, off one transition. */
    @Composable
    private fun PressButton(
        spec: Transition.Segment<Boolean>.() -> AnimationSpec = { Tween(200, easing = Easings.Linear) },
        colourSpec: Transition.Segment<Boolean>.() -> AnimationSpec = spec,
        seen: (Transition<Boolean>) -> Unit = {},
    ) {
        val interaction = remember { InteractionState() }
        val pressed = updateTransition(interaction.isPressed)
        seen(pressed)
        val scale by pressed.animateFloat(spec) { if (it) 0.5f else 1f }
        val colour by pressed.animateColour(colourSpec) { if (it) Dark else Light }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
            Box(
                Modifier.size(100f).testTag("button").interaction(interaction).clickable { }
                    .scale(scale).background(colour),
            )
        }
    }

    // --- one movement ---------------------------------------------------------------------------

    @Test
    fun `holding a button shrinks and darkens it in step and letting go turns both round on the same frame`() {
        show { PressButton() }
        assertEquals(0f, scaleProgress())
        assertEquals(Light, drawnColour())

        press("button")
        val down = mutableListOf<Pair<Float, Float>>()
        repeat(6) {
            frame()
            down += scaleProgress() to colourProgress()
        }
        val heldAt = down.last()
        assertTrue(heldAt.first in 0.2f..0.8f, "part way down after six frames: $down")
        down.forEachIndexed { at, (scale, colour) ->
            assertEquals(scale, colour, 0.03f, "frame $at of the press has them apart: $down")
        }

        release("button")
        val up = mutableListOf<Pair<Float, Float>>()
        repeat(30) {
            frame()
            up += scaleProgress() to colourProgress()
        }
        up.forEachIndexed { at, (scale, colour) ->
            assertEquals(scale, colour, 0.03f, "frame $at of the release has them apart: $up")
        }
        // The release reaches the transition a frame after it happens, as every state change does,
        // so the press carries on for that one frame — 16ms of a 200ms tween — and then turns.
        val peak = up.indexOfFirst { it.first == up.maxOf { p -> p.first } }
        assertTrue(peak == 0 && up[peak].first <= heldAt.first + 0.1f, "the release finished the press first: $up")
        val turned = up.indexOfFirst { it.first < up[peak].first - 0.01f }
        assertTrue(turned >= 0, "the scale never turned round: $up")
        assertEquals(turned, up.indexOfFirst { it.second < up[peak].second - 0.01f }, "the colour turned round on a different frame: $up")
        assertEquals(0f, up.last().first, "back to full size")
        assertEquals(Light, drawnColour(), "and its own colour")
    }

    @Test
    fun `the state it rests in changes only once the slowest value has arrived`() {
        lateinit var transition: Transition<Boolean>
        show {
            PressButton(
                spec = { Tween(100, easing = Easings.Linear) },
                colourSpec = { Tween(300, easing = Easings.Linear) },
                seen = { transition = it },
            )
        }
        assertFalse(transition.isRunning)
        assertFalse(transition.currentState)

        press("button")
        play(200)
        assertEquals(1f, scaleProgress(), "the quick scale has arrived")
        assertTrue(colourProgress() in 0.3f..0.9f, "the slow colour has not: ${colourProgress()}")
        assertTrue(transition.targetState, "it is heading for pressed")
        assertFalse(transition.currentState, "and has not yet come to rest there")
        assertTrue(transition.isRunning)
        assertTrue(host.clocks.isAnimating, "and the host's clocks know something is playing")

        play(200)
        assertEquals(Dark, drawnColour())
        assertTrue(transition.currentState, "every value arrived, so it rests in pressed")
        assertFalse(transition.isRunning)
        assertFalse(host.clocks.isAnimating)
    }

    @Test
    fun `a press can be quick and the release slow`() {
        show {
            PressButton(spec = { if (false isTransitioningTo true) Tween(50, easing = Easings.Linear) else Tween(400, easing = Easings.Linear) })
        }

        press("button")
        play(100)
        assertEquals(1f, scaleProgress(), "a 50ms press is long over")

        release("button")
        frames(2, millis = 0)
        play(200)
        assertTrue(scaleProgress() in 0.35f..0.65f, "half way through a 400ms release: ${scaleProgress()}")
        assertEquals(scaleProgress(), colourProgress(), 0.03f)
    }

    @Test
    fun `a spec changed while it rests is the one the next movement plays`() {
        var quick by mutableStateOf(false)
        show {
            // A new lambda each time the setting changes, as a speed option in a settings menu gives.
            val millis = if (quick) 50 else 1000
            PressButton(spec = { Tween(millis, easing = Easings.Linear) })
        }

        quick = true
        frames(2)
        press("button")
        play(100)
        assertEquals(1f, scaleProgress(), "the quick spec it was given last is the one it played")
        assertEquals(Dark, drawnColour())
    }

    @Test
    fun `let go before it finishes on a spring it turns round without a jump`() {
        show { PressButton(spec = { Spring(stiffness = Spring.Low) }) }

        press("button")
        val seen = mutableListOf<Float>()
        repeat(8) {
            frame()
            seen += scaleProgress()
        }
        release("button")
        repeat(120) {
            frame()
            seen += scaleProgress()
        }

        val biggestStep = seen.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(biggestStep < 0.12f, "something jumped by $biggestStep: $seen")
        assertEquals(0f, seen.last(), 0.01f, "and it came home")
    }

    // --- values coming and going ----------------------------------------------------------------

    @Test
    fun `a value composed part way through starts from the old state and the transition waits for it`() {
        var open by mutableStateOf(false)
        var withBadge by mutableStateOf(false)
        lateinit var transition: Transition<Boolean>
        show {
            val t = updateTransition(open)
            transition = t
            val height by t.animateFloat({ Tween(200, easing = Easings.Linear) }) { if (it) 100f else 20f }
            Column {
                Box(Modifier.size(100f, height).testTag("panel").background(Light))
                if (withBadge) {
                    val width by t.animateFloat({ Tween(400, easing = Easings.Linear) }) { if (it) 80f else 0f }
                    Box(Modifier.size(width, 10f).testTag("badge").background(Dark))
                }
            }
        }

        open = true
        play(100)
        withBadge = true
        frame(0)
        assertEquals(0f, host.root.find("badge").boundsInRoot.width, "it appears where closed puts it")

        play(150)
        assertEquals(100f, host.root.find("panel").boundsInRoot.height, "the panel has arrived")
        val badge = host.root.find("badge").boundsInRoot.width
        assertTrue(badge in 10f..60f, "the late badge is still on its way: $badge")
        assertFalse(transition.currentState, "and the transition is waiting for it")

        play(400)
        assertEquals(80f, host.root.find("badge").boundsInRoot.width)
        assertTrue(transition.currentState)
    }

    @Test
    fun `a value taken away part way through does not hold the transition up`() {
        var open by mutableStateOf(false)
        var withBadge by mutableStateOf(true)
        lateinit var transition: Transition<Boolean>
        show {
            val t = updateTransition(open)
            transition = t
            val height by t.animateFloat({ Tween(100) }) { if (it) 100f else 20f }
            Column {
                Box(Modifier.size(100f, height).testTag("panel"))
                if (withBadge) {
                    val width by t.animateFloat({ Tween(5000) }) { if (it) 80f else 0f }
                    Box(Modifier.size(width, 10f))
                }
            }
        }

        open = true
        play(50)
        withBadge = false
        play(200)

        assertTrue(transition.currentState, "the only value left arrived, so it rests")
        assertFalse(host.clocks.isAnimating)
        repeat(10) { assertFalse(frame(), "frame $it redrew a settled screen") }
    }

    @Test
    fun `a target that moves while the state stands still is animated to and not jumped to`() {
        var tint by mutableStateOf(Light)
        show {
            val t = updateTransition(true)
            val colour by t.animateColour({ Tween(200, easing = Easings.Linear) }) { tint }
            Box(Modifier.size(40f).background(colour))
        }
        assertEquals(Light, drawnColour())

        tint = Dark
        frames(2, millis = 0)
        play(100)
        assertTrue(colourProgress() in 0.3f..0.7f, "half way to the new tint: ${colourProgress()}")

        play(200)
        assertEquals(Dark, drawnColour())
    }

    // --- edges ----------------------------------------------------------------------------------

    @Test
    fun `a transition with no values in it still comes to rest in the new state`() {
        var open by mutableStateOf(false)
        lateinit var transition: Transition<Boolean>
        show {
            transition = updateTransition(open)
            Box(Modifier.size(10f))
        }

        open = true
        frames(3)
        assertTrue(transition.targetState)
        assertTrue(transition.currentState, "nothing to wait for, so it rests at once")
        assertFalse(transition.isRunning)
        repeat(10) { assertFalse(frame(), "frame $it redrew a transition with nothing in it") }
    }

    @Test
    fun `a transition that follows where another came to rest waits for the first to finish`() {
        var open by mutableStateOf(false)
        show {
            val outer = updateTransition(open)
            val height by outer.animateFloat({ Tween(200, easing = Easings.Linear) }) { if (it) 100f else 20f }
            val inner = updateTransition(outer.currentState)
            val width by inner.animateFloat({ Tween(200, easing = Easings.Linear) }) { if (it) 80f else 10f }
            Column {
                Box(Modifier.size(100f, height).testTag("panel"))
                Box(Modifier.size(width, 10f).testTag("content"))
            }
        }

        open = true
        play(120)
        assertTrue(host.root.find("panel").boundsInRoot.height in 40f..90f, "the panel is on its way")
        assertEquals(10f, host.root.find("content").boundsInRoot.width, "the content has not started")

        play(200)
        assertEquals(100f, host.root.find("panel").boundsInRoot.height)
        assertTrue(host.root.find("content").boundsInRoot.width in 20f..75f, "then the content goes")

        play(300)
        assertEquals(80f, host.root.find("content").boundsInRoot.width)
        assertFalse(host.clocks.isAnimating)
    }

    @Test
    fun `a transition taken away mid flight lets go of the clock and asks for no more frames`() {
        var open by mutableStateOf(false)
        var shown by mutableStateOf(true)
        show {
            if (shown) {
                val t = updateTransition(open)
                val scale by t.animateFloat({ Tween(5000) }) { if (it) 0.5f else 1f }
                Box(Modifier.size(40f).scale(scale).background(Light))
            }
        }

        open = true
        play(100)
        assertTrue(host.clocks.isAnimating)

        shown = false
        frames(2)
        assertFalse(host.clocks.isAnimating, "gone, it no longer counts as playing")
        repeat(10) { assertFalse(frame(), "frame $it redrew after the transition went") }
    }

    @Test
    fun `a size can shrink all the way to nothing and grow back`() {
        var open by mutableStateOf(true)
        show {
            val t = updateTransition(open)
            val size by t.animateSize { if (it) Size(60f, 40f) else Size.Zero }
            Box(Modifier.size(size.width, size.height).testTag("card").background(Light))
        }
        assertEquals(60f, host.root.find("card").layoutBoundsInRoot.width)

        open = false
        play(1500)
        assertEquals(Size.Zero, host.root.find("card").layoutBoundsInRoot.size)

        open = true
        play(1500)
        assertEquals(Size(60f, 40f), host.root.find("card").layoutBoundsInRoot.size)
    }

    @Test
    fun `recomposing for something else mid flight does not start the movement again`() {
        var open by mutableStateOf(false)
        var label by mutableStateOf(0)
        show {
            val t = updateTransition(open)
            val scale by t.animateFloat({ Tween(200, easing = Easings.Linear) }) { if (it) 0.5f else 1f }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
                Box(Modifier.size(100f).testTag("button").scale(scale).background(Light)) { if (label >= 0) Box(Modifier.size(label.toFloat())) }
            }
        }

        open = true
        val seen = mutableListOf<Float>()
        repeat(14) {
            label++
            frame()
            seen += scaleProgress()
        }
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }, "it went backwards: $seen")
        assertTrue(seen.last() >= 0.9f, "a recomposition every frame held it back: $seen")
    }

    // --- clocks and cost ------------------------------------------------------------------------

    @Test
    fun `settled it asks for no frames either side of a movement`() {
        var open by mutableStateOf(false)
        show {
            val t = updateTransition(open)
            val scale by t.animateFloat { if (it) 1f else 0.5f }
            val colour by t.animateColour { if (it) Dark else Light }
            val shift by t.animateOffset { if (it) Offset(10f, 0f) else Offset.Zero }
            Box(Modifier.size(40f).offset(shift.x, shift.y).scale(scale).background(colour))
        }
        repeat(60) { assertFalse(frame(), "frame $it redrew a transition that never moved") }

        open = true
        play(1000)
        repeat(60) { assertFalse(frame(), "frame $it redrew a transition that had arrived") }
    }

    @Test
    fun `on the world clock a pause freezes every value together`() {
        host.clocks.register(Clock.World)
        var open by mutableStateOf(false)
        show {
            val t = updateTransition(open, clock = Clock.World)
            val scale by t.animateFloat({ Tween(400, easing = Easings.Linear) }) { if (it) 0.5f else 1f }
            val colour by t.animateColour({ Tween(400, easing = Easings.Linear) }) { if (it) Dark else Light }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
                Box(Modifier.size(100f).testTag("button").scale(scale).background(colour))
            }
        }

        open = true
        play(200)
        host.clocks.stop(Clock.World)
        frames(2)
        val frozen = scaleProgress() to colourProgress()
        assertTrue(frozen.first in 0.2f..0.8f, "under way before the pause: $frozen")

        play(2000)
        assertEquals(frozen, scaleProgress() to colourProgress(), "paused, both are held where they were")
        assertFalse(host.clocks.isAnimating, "and nothing waits on a stopped clock")

        host.clocks.start(Clock.World)
        play(400)
        assertEquals(1f, scaleProgress())
        assertEquals(Dark, drawnColour())
    }

    // --- through the test harness, with every kind of input -------------------------------------

    @Test
    fun `a card picked with the mouse then the keyboard then the pad grows moves and lights up as one`() {
        var picked by mutableStateOf(false)
        lateinit var transition: Transition<Boolean>
        uiTest(Size(400f, 300f)) {
            Column {
                Button("PICK", onClick = { picked = !picked }, initialFocus = true, modifier = Modifier.testTag("pick"))
                val t = updateTransition(picked)
                transition = t
                val size by t.animateSize({ Tween(250) }) { if (it) Size(120f, 80f) else Size(60f, 40f) }
                val shift by t.animateOffset({ Tween(250) }) { if (it) Offset(30f, 0f) else Offset.Zero }
                val colour by t.animateColour({ Tween(250) }) { if (it) Dark else Light }
                Box(Modifier.testTag("card").offset(shift.x, shift.y).size(size.width, size.height).background(colour))
            }
        }.use { ui ->
            fun assertCard(width: Float, height: Float, left: Float, colour: Colour) {
                val card = ui.node("card")
                assertEquals(width, card.layoutBoundsInRoot.width, 0.01f, "laid out wide")
                assertEquals(height, card.layoutBoundsInRoot.height, 0.01f, "laid out tall")
                assertEquals(left, card.boundsInRoot.left, 0.01f, "drawn from")
                ui.render()
                val canvas = ui.backend.canvas as RecordingCanvas
                assertEquals(colour, canvas.only<DrawCall.Rectangle>().last().colour, "drawn in")
                assertFalse(transition.isRunning, "every action waited the movement out")
            }
            assertCard(60f, 40f, 0f, Light)

            ui.click("pick")
            assertCard(120f, 80f, 30f, Dark)

            ui.key(Key.Enter)
            assertCard(60f, 40f, 0f, Light)

            ui.pad(GamepadButton.South)
            assertCard(120f, 80f, 30f, Dark)
            assertTrue(transition.currentState)
        }
    }

    private companion object {
        val Light = Colour.rgb(0xE0E0E0)
        val Dark = Colour.rgb(0x202020)
    }
}
