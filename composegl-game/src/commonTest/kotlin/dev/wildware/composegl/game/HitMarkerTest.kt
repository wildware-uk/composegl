package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ticks that flash when a shot lands.
 *
 * The issue's three: the three kinds are told apart by shape as well as by colour, a marker grows
 * and fades rather than blinking, and the sound hook fires for every hit a game marks — which is
 * what a game hangs its own audio on. Driven from a click, a key and a pad button as well, because
 * a hit is a trigger being pulled and the marker has to come up on the frame it is pulled.
 *
 * These step frames rather than settling: settling plays every animation out to its end, and a
 * marker that has finished is nothing on screen to look at.
 */
class HitMarkerTest {

    private val opened = mutableListOf<UiTest>()

    private val screen = Rect.of(0f, 0f, 400f, 400f)
    private val headless = HeadlessBackend(screen)

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 400f), backend = headless, content = content).also { opened += it }

    /** One frame, and every tick it drew. */
    private fun UiTest.ticks(): List<DrawCall.Fan> {
        headless.canvas.clear(screen)
        render()
        return headless.canvas.calls.filterIsInstance<DrawCall.Fan>()
    }

    private fun UiTest.frames(count: Int) = repeat(count) { render() }

    /** Frames until the marker is up: the runtime takes one or two to notice a hit. */
    private fun UiTest.begin(): List<DrawCall.Fan> {
        repeat(6) {
            val drawn = ticks()
            if (drawn.isNotEmpty()) return drawn
        }
        throw AssertionError("no marker ever appeared")
    }

    private fun fill(style: String): Colour =
        (Skin.Default.resolve(style).background as SkinDrawable.Fill).colour

    /** How far the furthest corner of the marker is from the middle of the screen. */
    private fun List<DrawCall.Fan>.reach(): Float {
        var furthest = 0f
        forEach { fan ->
            fan.points.forEach { point ->
                val dx = point.x - 200f
                val dy = point.y - 200f
                val distance = dx * dx + dy * dy
                if (distance > furthest) furthest = distance
            }
        }
        return furthest
    }

    // --- the issue's three ------------------------------------------------------------------------

    @Test
    fun `each kind has its own shape and its own colour`() {
        val marker = HitMarkerState(Clock.World)
        val ui = open { HitMarker(marker) }

        marker.hit()
        val normal = ui.begin()
        assertEquals(4, normal.size, "an ordinary hit is four ticks")
        assertEquals(fill("hitmarker").withAlpha(255), normal.first().colour.withAlpha(255))
        ui.frames(40)

        marker.hit(HitKind.Critical)
        val critical = ui.begin()
        assertEquals(8, critical.size, "a critical is a star rather than a cross")
        assertEquals(fill("hitmarker.critical").withAlpha(255), critical.first().colour.withAlpha(255))
        ui.frames(40)

        marker.hit(HitKind.Kill)
        val kill = ui.begin()
        assertEquals(5, kill.size, "a kill is the cross with a diamond inside it")
        assertEquals(fill("hitmarker.kill").withAlpha(255), kill.first().colour.withAlpha(255))
    }

    @Test
    fun `a marker grows as it fades and then leaves nothing behind`() {
        val marker = HitMarkerState(Clock.World)
        val ui = open { HitMarker(marker) }

        marker.hit()
        val fresh = ui.begin()

        ui.frames(6)
        val fading = ui.ticks()

        assertTrue(fading.first().colour.alphaFraction < fresh.first().colour.alphaFraction, "it is not fading")
        assertTrue(fading.reach() > fresh.reach(), "it is not growing")

        ui.frames(30)
        assertTrue(ui.ticks().isEmpty(), "the marker should be gone")
    }

    @Test
    fun `the sound hook fires for every hit with what was hit`() {
        val marker = HitMarkerState(Clock.World)
        val heard = mutableListOf<HitKind>()
        val ui = open { HitMarker(marker, onHit = { heard += it }) }

        ui.frames(10)
        assertEquals(emptyList(), heard, "nothing has been hit yet")

        marker.hit()
        ui.frames(4)
        marker.hit(HitKind.Critical)
        ui.frames(4)
        // On top of one still fading: the marker is taken over, and the sound still happens.
        marker.hit(HitKind.Kill)
        ui.frames(4)

        assertEquals(listOf(HitKind.Normal, HitKind.Critical, HitKind.Kill), heard)
    }

    // --- the rest of it ---------------------------------------------------------------------------

    @Test
    fun `a second hit restarts the marker rather than stacking on it`() {
        val marker = HitMarkerState(Clock.World)
        val ui = open { HitMarker(marker) }

        marker.hit()
        val first = ui.begin()

        ui.frames(6)
        marker.hit()
        ui.frames(2)
        val second = ui.ticks()

        assertEquals(first.size, second.size, "eight ticks means two markers drawn on top of each other")
        assertTrue(
            second.first().colour.alphaFraction > 0.85f,
            "the second marker started from where the first had faded to: ${second.first().colour.alphaFraction}",
        )
    }

    @Test
    fun `a marker that comes back does not mark the hit it left behind`() {
        val marker = HitMarkerState(Clock.World)
        val heard = mutableListOf<HitKind>()
        var hudShown by mutableStateOf(true)
        val ui = open { if (hudShown) HitMarker(marker, onHit = { heard += it }) }

        marker.hit(HitKind.Kill)
        ui.begin()
        assertEquals(listOf(HitKind.Kill), heard, "the hit itself should have sounded")
        ui.frames(40)

        // The HUD is turned off and on again, with the state hoisted above it the way a game's is.
        // Nothing was hit in between, so nothing should flash and nothing should sound.
        hudShown = false
        ui.frames(4)
        hudShown = true
        ui.frames(6)

        assertEquals(listOf(HitKind.Kill), heard, "coming back sounded the last hit all over again")
        assertTrue(ui.ticks().isEmpty(), "coming back flashed a marker with nothing behind it")

        // And the next real hit still marks, so the guard has not simply turned the widget off.
        marker.hit()
        ui.begin()
        assertEquals(listOf(HitKind.Kill, HitKind.Normal), heard, "a real hit after it came back did not mark")
    }

    @Test
    fun `a kill stays up longer than an ordinary hit`() {
        val marker = HitMarkerState(Clock.World)
        val ui = open { HitMarker(marker) }

        marker.hit()
        ui.begin()
        ui.frames(20)
        assertTrue(ui.ticks().isEmpty(), "an ordinary marker should be over by now")

        marker.hit(HitKind.Kill)
        ui.begin()
        ui.frames(20)
        assertTrue(ui.ticks().isNotEmpty(), "a kill should still be up")
    }

    @Test
    fun `it freezes with the world and costs nothing once it has gone`() {
        val marker = HitMarkerState(Clock.World)
        val ui = open { HitMarker(marker) }

        marker.hit(HitKind.Kill)
        ui.begin()
        ui.frames(6)

        ui.host.clocks.stop(Clock.World)
        val paused = ui.ticks().first().colour.alphaFraction
        ui.frames(30)
        assertEquals(paused, ui.ticks().first().colour.alphaFraction, 0.001f, "a paused marker kept fading")

        ui.host.clocks.start(Clock.World)
        ui.advanceBy(1_000)
        repeat(20) { assertTrue(!ui.render(), "a marker that has gone is still asking for frames") }
    }

    @Test
    fun `the marker sits in the middle of whatever box it is given`() {
        val marker = HitMarkerState(Clock.World)
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(120f).testTag("slot")) { HitMarker(marker) }
            }
        }

        marker.hit()
        val drawn = ui.begin()

        val slot = ui.node("slot").boundsInRoot
        val left = drawn.flatMap { it.points }.minOf { it.x }
        val right = drawn.flatMap { it.points }.maxOf { it.x }
        val top = drawn.flatMap { it.points }.minOf { it.y }
        val bottom = drawn.flatMap { it.points }.maxOf { it.y }

        assertEquals(slot.centre.x, (left + right) / 2f, 0.5f, "the marker is not on the middle of its box across")
        assertEquals(slot.centre.y, (top + bottom) / 2f, 0.5f, "the marker is not on the middle of its box down")
    }

    // --- driven the way a player drives it --------------------------------------------------------

    @Test
    fun `a shot fired by mouse by key and by pad all mark the same marker`() {
        val marker = HitMarkerState(Clock.World)
        val heard = mutableListOf<HitKind>()
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                HitMarker(marker, onHit = { heard += it })
                Button("FIRE", onClick = { marker.hit(HitKind.Critical) }, modifier = Modifier.testTag("fire"))
            }
        }

        ui.click("fire")
        assertEquals(1, marker.hits, "the click did not reach the game")

        ui.focus.focusOn(ui.node("fire"))
        ui.settle()
        ui.key(Key.Enter)
        assertEquals(2, marker.hits, "Enter on the focused trigger did not fire it")

        ui.pad(GamepadButton.South)
        assertEquals(3, marker.hits, "the pad's South did not fire it")

        // Each of the three was marked, and each was a critical: the kind goes through the state
        // the game writes rather than through whatever drew last.
        assertEquals(listOf(HitKind.Critical, HitKind.Critical, HitKind.Critical), heard)
    }
}
