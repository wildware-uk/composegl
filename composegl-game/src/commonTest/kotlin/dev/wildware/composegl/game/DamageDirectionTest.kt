package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arcs that say which way a hit came from.
 *
 * The issue's three: an arc points the way the game said, several of them stack, and all of it
 * runs on the world clock so a pause freezes it. The rest is what a direction indicator gets wrong
 * when nobody checks: the ring going round the wrong middle in split-screen, and turning itself
 * round in a right-to-left interface, where a hit from the left would then be drawn on the right.
 */
class DamageDirectionTest {

    private val opened = mutableListOf<UiTest>()

    private val screen = Rect.of(0f, 0f, 400f, 400f)
    private val headless = HeadlessBackend(screen)

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size = Size(400f, 400f), content: @Composable () -> Unit): UiTest =
        uiTest(size, backend = headless, content = content).also { opened += it }

    /** The quads of every arc on screen, drawn fresh. */
    private fun UiTest.arcs(): List<DrawCall.Fan> {
        headless.canvas.clear(screen)
        render()
        return headless.canvas.calls.filterIsInstance<DrawCall.Fan>()
    }

    /** Where the ink of every arc is, on average: which way the player is being told to look. */
    private fun List<DrawCall.Fan>.middle(): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var count = 0
        forEach { fan ->
            fan.points.forEach { point ->
                x += point.x
                y += point.y
                count++
            }
        }
        return (x / count) to (y / count)
    }

    private fun List<DrawCall.Fan>.brightest(): Float =
        maxOf { it.colour.alphaFraction }

    // --- the issue's three ------------------------------------------------------------------------

    @Test
    fun `an arc is drawn on the side the hit came from`() {
        val hits = DamageDirections()
        val ui = open { DamageDirectionLayer(hits) }

        // Straight ahead is up the screen; a quarter turn clockwise is the player's right.
        listOf(
            0f to (200f to 100f),
            90f to (300f to 200f),
            180f to (200f to 300f),
            270f to (100f to 200f),
        ).forEach { (angle, expected) ->
            hits.clear()
            hits.hit(fromAngle = angle)
            ui.settle()

            val (x, y) = ui.arcs().middle()
            val (wantX, wantY) = expected
            assertTrue(
                (x - wantX) * (x - wantX) + (y - wantY) * (y - wantY) < 60f * 60f,
                "a hit from $angle degrees drew its arc at $x by $y rather than near $wantX by $wantY",
            )
        }
    }

    @Test
    fun `several hits stack rather than replacing each other`() {
        val hits = DamageDirections()
        val ui = open { DamageDirectionLayer(hits) }

        hits.hit(fromAngle = 0f)
        ui.settle()
        val one = ui.arcs().size
        assertTrue(one > 0, "one hit drew nothing at all")

        hits.hit(fromAngle = 90f)
        hits.hit(fromAngle = 200f)
        ui.settle()

        assertEquals(3, hits.active, "three hits should be three arcs")
        assertEquals(one * 3, ui.arcs().size, "the three arcs are not all being drawn")
    }

    @Test
    fun `arcs freeze while the world is paused`() {
        val hits = DamageDirections()
        val ui = open { DamageDirectionLayer(hits) }

        hits.hit(fromAngle = 45f)
        ui.settle()
        // Past the hold, so the arc is actually fading: one that has not started yet would sit
        // still whether the world were paused or not, and prove nothing.
        ui.advanceBy(700)
        val paused = ui.arcs().brightest()

        ui.host.clocks.stop(Clock.World)
        ui.advanceBy(5_000)

        assertEquals(1, hits.active, "an arc expired behind a pause menu")
        assertEquals(paused, ui.arcs().brightest(), 0.001f, "a paused arc kept fading")

        ui.host.clocks.start(Clock.World)
        ui.advanceBy(2_000)
        assertEquals(0, hits.active, "and it should carry on when the world does")
    }

    // --- the rest of it ---------------------------------------------------------------------------

    @Test
    fun `an arc fades out and leaves nothing behind`() {
        // Long enough that settling between the steps is a rounding error: an arc is timed from
        // the hit, and settling costs a handful of frames either side of every reading.
        val hits = DamageDirections(lifeMillis = 900)
        val ui = open { DamageDirectionLayer(hits) }

        hits.hit(fromAngle = 0f)
        ui.settle()
        val fresh = ui.arcs().brightest()

        ui.advanceBy(500)
        assertTrue(ui.arcs().brightest() < fresh, "it should be fading by now")

        ui.advanceBy(600)
        assertEquals(0, hits.active, "the arc should have expired")
        assertTrue(ui.arcs().isEmpty(), "and nothing should be left on screen")
    }

    @Test
    fun `a graze is fainter and thinner than a full hit`() {
        val hits = DamageDirections()
        val ui = open { DamageDirectionLayer(hits) }

        hits.hit(fromAngle = 0f, strength = 1f)
        ui.settle()
        val heavy = ui.arcs()

        hits.clear()
        hits.hit(fromAngle = 0f, strength = 0f)
        ui.settle()
        val graze = ui.arcs()

        assertTrue(graze.brightest() < heavy.brightest(), "a graze is as bright as being shot")
        assertTrue(spread(graze) < spread(heavy), "a graze is as thick as being shot")
    }

    @Test
    fun `the oldest arc gives up its place when the pool is full`() {
        val hits = DamageDirections(capacity = 2)
        val ui = open { DamageDirectionLayer(hits) }

        hits.hit(fromAngle = 0f)
        hits.hit(fromAngle = 90f)
        hits.hit(fromAngle = 180f)
        ui.settle()

        assertEquals(2, hits.active, "the pool grew past what it was made with")
        val (_, y) = ui.arcs().middle()
        assertTrue(y > 200f, "the newest hit — from behind — should be one of the two still drawn")
    }

    @Test
    fun `the ring goes round the middle of the layer whatever shape it is`() {
        val hits = DamageDirections()

        listOf(Size(400f, 400f), Size(960f, 540f), Size(320f, 568f)).forEach { size ->
            val ui = open(size) { DamageDirectionLayer(hits) }
            hits.clear()
            hits.hit(fromAngle = 0f)
            ui.settle()

            val (x, _) = ui.arcs().middle()
            assertEquals(size.width / 2f, x, 1f, "the arc is off centre at ${size.width} by ${size.height}")
        }
    }

    @Test
    fun `a right-to-left screen does not swap the sides of the world`() {
        val hits = DamageDirections()
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { DamageDirectionLayer(hits) }
        }

        hits.hit(fromAngle = 90f)
        ui.settle()

        val (x, _) = ui.arcs().middle()
        assertTrue(x > 200f, "a hit from the player's right was drawn on the left of the screen: $x")
    }

    @Test
    fun `a hit taken while the layer is off the screen ages like any other`() {
        val hits = DamageDirections(lifeMillis = 400)
        var hudShown by mutableStateOf(true)
        val ui = open { if (hudShown) DamageDirectionLayer(hits) }

        // Composed once, so the pool knows which clock it is on. Then the player hides the HUD.
        ui.settle()
        hudShown = false
        ui.settle()

        hits.hit(fromAngle = 0f)
        ui.advanceBy(1_000)

        hudShown = true
        ui.settle()

        assertEquals(0, hits.active, "an arc from a hit taken behind a hidden HUD never aged")
        assertTrue(ui.arcs().isEmpty(), "and it came back at full strength when the HUD did")
    }

    @Test
    fun `nothing is drawn and no frames are asked for while nobody is shooting`() {
        val hits = DamageDirections()
        val ui = open { DamageDirectionLayer(hits) }

        assertTrue(ui.arcs().isEmpty(), "an empty layer drew something")
        repeat(20) { assertTrue(!ui.render(), "a quiet layer asked for a frame") }
    }

    /** How far the ink of an arc reaches out from its middle: its thickness, measured. */
    private fun spread(fans: List<DrawCall.Fan>): Float {
        val (x, y) = fans.middle()
        var furthest = 0f
        fans.forEach { fan ->
            fan.points.forEach { point ->
                val dx = point.x - x
                val dy = point.y - y
                val distance = dx * dx + dy * dy
                if (distance > furthest) furthest = distance
            }
        }
        return furthest
    }
}
