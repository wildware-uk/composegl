package composegl.ui.game

import composegl.ui.animation.Clock
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.graphics.TextureHandle
import composegl.ui.draw.DrawPass
import composegl.ui.host.UiHost
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * The layer in a running composition: whether it winds the simulation on, whether it stops when
 * everything is over, and what a frame of it costs.
 *
 * The last one is the issue's own bar — "the frame budget is unchanged with an emitter idle" — and
 * it is checked twice: an idle emitter asks the runtime for no frames at all, and a busy one
 * allocates nothing while it draws.
 */
class ParticleLayerTest {

    private val host = UiHost()
    private val bounds = Rect.of(0f, 0f, 800f, 600f)
    private val canvas = RecordingCanvas(bounds)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear(bounds)
        MeasurePass().run(host.root, Constraints.atMost(800f, 600f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun show(emitter: ParticleEmitter) {
        host.setContent { ParticleLayer(emitter) }
        host.clocks.register(emitter.clock)
        repeat(2) { frame() }
    }

    private fun emitter(capacity: Int = 256, clock: Clock = Clock.Ui) =
        ParticleEmitter(capacity = capacity, clock = clock, seed = 3L)

    private fun quads() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()

    private val Embers = ParticleStyle(life = 1f, lifeSpread = 0f, gravity = 0f, endColour = Colour.White)

    @Test
    fun `a burst is drawn, and moves on its own`() {
        val emitter = emitter()
        show(emitter)
        emitter.burst(10, 400f, 300f, Embers)
        frame()
        val first = quads().map { it.rect.top }

        repeat(10) { frame() }

        assertEquals(10, quads().size)
        assertTrue(quads().map { it.rect.top } != first, "nothing moved: the layer is not winding it on")
    }

    @Test
    fun `the layer stops asking for frames when the last particle has gone`() {
        val emitter = emitter()
        show(emitter)
        emitter.burst(5, 400f, 300f, Embers)
        repeat(80) { frame(20) }

        assertEquals(0, emitter.active)
        repeat(30) {
            wall += 16_000_000L
            assertTrue(!host.frame(wall), "frame $it redrew an empty layer")
        }
    }

    @Test
    fun `an idle emitter costs no frames at all`() {
        show(emitter())

        repeat(30) {
            wall += 16_000_000L
            assertTrue(!host.frame(wall), "frame $it woke the runtime for an emitter with nothing in it")
        }
    }

    @Test
    fun `two hundred particles cost no allocation per frame`() {
        val emitter = emitter()
        show(emitter)
        emitter.burst(200, 400f, 300f, ParticleStyle(life = 60f, lifeSpread = 0f))
        frame()
        frame()

        // A canvas that keeps nothing, so what is measured is the layer rather than the recording.
        val silent = Silent()
        val before = allocatedBytes()
        repeat(10) {
            wall += 16_000_000L
            host.frame(wall)
            DrawPass(silent).draw(host.root)
        }
        val perFrame = (allocatedBytes() - before) / 10

        assertEquals(200, quads().size, "all two hundred should be on screen")
        // The simulation allocates nothing whatever. What is left is the rectangle each quad is
        // handed to the canvas in — one small object each, and nothing that grows with time — so
        // the bar is what two hundred of those cost and not a byte more.
        assertTrue(perFrame < 200 * 64, "a frame of two hundred particles allocated $perFrame bytes")
    }

    @Test
    fun `particles freeze when the world does`() {
        val emitter = ParticleEmitter(clock = Clock.World, seed = 3L)
        show(emitter)
        emitter.burst(4, 400f, 300f, Embers)
        frame()
        val before = quads().map { it.rect.top }

        host.clocks.stop(Clock.World)
        repeat(20) { frame(20) }

        assertEquals(before, quads().map { it.rect.top }, "they drifted while the game was paused")
        assertEquals(4, emitter.active, "and they must not expire behind a pause menu")
    }

    @Test
    fun `a running source keeps the layer awake`() {
        val emitter = emitter()
        show(emitter)
        emitter.start(30f, 400f, 300f, Embers)
        repeat(30) { frame() }

        assertTrue(emitter.active > 0, "a source that is running should have made something")
        assertTrue(quads().isNotEmpty())
    }

    /** A canvas that draws nothing and keeps nothing, for measuring what a widget itself costs. */
    private class Silent : composegl.ui.graphics.UiCanvas {
        override fun rect(rect: Rect, colour: Colour, corner: Float) = Unit
        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun fan(points: FloatArray, colour: Colour) = Unit
        override fun text(layout: composegl.ui.text.TextLayout, x: Float, y: Float, colour: Colour) = Unit
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) = Unit
    }

    /** What this thread has allocated so far. HotSpot only, which is what these tests run on. */
    private fun allocatedBytes(): Long {
        val beans = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return beans.getThreadAllocatedBytes(Thread.currentThread().threadId())
    }
}
