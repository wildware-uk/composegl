package dev.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * The numbers that fly off a thing when it is hit.
 *
 * The issue's three: two hundred of them on screen cost no allocation per frame, an expired one
 * leaves nothing behind, and one over a target that moves follows it. The allocation test is the
 * interesting one and it is real — it reads the bytes this thread allocated across a frame.
 */
class DamageNumbersTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(dev.wildware.composegl.ui.geometry.Rect(0f, 0f, 800f, 600f))

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear(dev.wildware.composegl.ui.geometry.Rect(0f, 0f, 800f, 600f))
        MeasurePass().run(host.root, Constraints.atMost(800f, 600f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun show(numbers: DamageNumbers) {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                DamageNumberLayer(numbers)
            }
        }
        repeat(2) { frame() }
    }

    private fun numbers(capacity: Int = 256) = DamageNumbers(capacity = capacity, clock = Clock.Ui)

    private fun drawn(): List<DrawCall.Text> = canvas.calls.filterIsInstance<DrawCall.Text>()

    private fun drawn(text: String): DrawCall.Text? = drawn().firstOrNull { it.text == text }

    // --- the three the issue asks for ----------------------------------------------------------

    @Test
    fun `two hundred numbers cost no allocation per frame`() {
        val numbers = numbers()
        show(numbers)
        repeat(200) { numbers.show("$it", WorldAnchor.at(it % 40 * 20f, it / 40 * 20f)) }
        // Two frames to measure them all and settle, so that what is measured below is the
        // steady state rather than the first sight of two hundred strings.
        frame()
        frame()

        // Drawn through a canvas that keeps nothing, so what is measured is the layer's own
        // allocation rather than the recording canvas making a call object per number.
        val silent = Silent()
        val before = allocatedBytes()
        repeat(10) {
            wall += 16_000_000L
            host.frame(wall)
            DrawPass(silent).draw(host.root)
        }
        val perFrame = (allocatedBytes() - before) / 10

        assertEquals(200, drawn().size, "all two hundred should be on screen")
        assertTrue(
            perFrame < 4_096,
            "drawing two hundred numbers allocated $perFrame bytes a frame",
        )
    }

    @Test
    fun `an expired number leaves nothing behind`() {
        val numbers = numbers()
        show(numbers)
        numbers.show("12", WorldAnchor.at(100f, 100f))
        frame()

        assertNotNull(drawn("12"))

        repeat(60) { frame(20) }

        assertNull(drawn("12"), "it was still being drawn after its life ran out")
        assertEquals(0, numbers.active, "and its place went back in the pool")
    }

    @Test
    fun `a number over a target that moves follows it`() {
        val numbers = numbers()
        var x = 100f
        show(numbers)
        numbers.show("7", WorldAnchor { it.set(x, 100f) })
        frame()
        val first = checkNotNull(drawn("7")).at.x

        x = 300f
        frame()

        assertEquals(first + 200f, checkNotNull(drawn("7")).at.x, 1f, "it stayed where the hit was")
    }

    // --- how one behaves -----------------------------------------------------------------------

    @Test
    fun `a number floats up and fades out`() {
        val numbers = numbers()
        show(numbers)
        numbers.show("9", WorldAnchor.at(100f, 300f))
        frame()
        val start = checkNotNull(drawn("9"))

        // Far enough in that the fade has started: it holds full strength for the first
        // two thirds of its life so a hit is readable before it goes.
        repeat(35) { frame(20) }
        val later = checkNotNull(drawn("9"))

        assertTrue(later.at.y < start.at.y, "it should have risen")
        assertTrue(later.colour.alpha < start.colour.alpha, "and started fading")
    }

    @Test
    fun `a critical is bigger and lives longer`() {
        val numbers = numbers()
        show(numbers)
        numbers.show("100", WorldAnchor.at(100f, 300f), critical = true)
        numbers.show("100", WorldAnchor.at(400f, 300f))
        frame()

        val both = drawn().sortedBy { it.at.x }
        val critical = both.first()
        val ordinary = both.last()
        assertTrue(
            critical.at.x < ordinary.at.x - 300f + 4f,
            "the critical is drawn wider, so its left edge sits further out",
        )

        // Long enough for an ordinary number to be over and a critical not to be.
        repeat(55) { frame(20) }

        assertEquals(1, drawn().size, "the critical should still be there and the ordinary gone")
        assertEquals(1, numbers.active)
    }

    @Test
    fun `numbers in the same place do not sit on top of each other`() {
        val numbers = numbers()
        show(numbers)
        numbers.show("1", WorldAnchor.at(200f, 200f))
        numbers.show("2", WorldAnchor.at(200f, 200f))
        repeat(6) { frame(20) }

        val one = checkNotNull(drawn("1"))
        val two = checkNotNull(drawn("2"))

        assertTrue(one.at.x != two.at.x, "two numbers from the same spot are unreadable stacked")
    }

    @Test
    fun `a number the camera cannot see is not drawn`() {
        val numbers = numbers()
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                DamageNumberLayer(
                    numbers,
                    // A camera that can only see the left half of the world.
                    projection = WorldProjection { point, _, onto ->
                        onto.set(point.x, point.y)
                        point.x < 400f
                    },
                )
            }
        }
        repeat(2) { frame() }

        numbers.show("near", WorldAnchor.at(100f, 100f))
        numbers.show("far", WorldAnchor.at(900f, 100f))
        frame()

        assertNotNull(drawn("near"))
        assertNull(drawn("far"), "something behind the camera was drawn anyway")
    }

    @Test
    fun `a centred camera puts zero in the middle of the view`() {
        val numbers = numbers()
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                DamageNumberLayer(numbers, projection = WorldProjection.Centred)
            }
        }
        repeat(2) { frame() }

        numbers.show("8", WorldAnchor.at(0f, 0f))
        frame()

        // Drawn from its left edge, so the middle of the view is a character's width to its right.
        val text = checkNotNull(drawn("8"))
        assertTrue(text.at.x < 400f && text.at.x > 380f, "not in the middle across: ${text.at.x}")
        assertTrue(text.at.y <= 300f && text.at.y > 240f, "not in the middle down: ${text.at.y}")
    }

    @Test
    fun `the pool gives the oldest place away rather than growing`() {
        val numbers = numbers(capacity = 4)
        show(numbers)
        repeat(6) { numbers.show("$it", WorldAnchor.at(it * 50f, 100f)) }
        frame()

        assertEquals(4, numbers.active, "a pool of four holds four")
        assertEquals(4, drawn().size)
        assertNotNull(drawn("5"), "the newest number is the one the player is looking for")
        assertNull(drawn("0"), "and the oldest gave up its place")
    }

    @Test
    fun `numbers freeze when the world does`() {
        val numbers = DamageNumbers(clock = Clock.World)
        show(numbers)
        host.clocks.register(Clock.World)
        numbers.show("5", WorldAnchor.at(100f, 300f))
        frame()
        val before = checkNotNull(drawn("5")).at.y

        host.clocks.stop(Clock.World)
        repeat(40) { frame(20) }

        assertEquals(before, checkNotNull(drawn("5")).at.y, 0.01f, "it drifted while the game was paused")
        assertEquals(1, numbers.active, "and it must not expire behind a pause menu")
    }

    @Test
    fun `a layer with nothing on it costs nothing`() {
        val numbers = numbers()
        show(numbers)

        repeat(30) {
            wall += 16_000_000L
            assertTrue(!host.frame(wall), "frame $it redrew an empty layer")
        }
    }

    @Test
    fun `clearing takes everything off the screen`() {
        val numbers = numbers()
        show(numbers)
        repeat(5) { numbers.show("$it", WorldAnchor.at(it * 40f, 100f)) }
        frame()

        numbers.clear()
        frame()

        assertEquals(0, numbers.active)
        assertTrue(drawn().isEmpty())
    }

    /** A canvas that draws nothing and keeps nothing, for measuring what a widget itself costs. */
    private class Silent : dev.wildware.composegl.ui.graphics.UiCanvas {
        override fun rect(rect: dev.wildware.composegl.ui.geometry.Rect, colour: dev.wildware.composegl.ui.graphics.Colour, corner: Float) = Unit
        override fun border(rect: dev.wildware.composegl.ui.geometry.Rect, colour: dev.wildware.composegl.ui.graphics.Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: dev.wildware.composegl.ui.geometry.Rect, colour: dev.wildware.composegl.ui.graphics.Colour, spread: Float, corner: Float) = Unit
        override fun fan(points: FloatArray, colour: dev.wildware.composegl.ui.graphics.Colour) = Unit
        override fun text(layout: dev.wildware.composegl.ui.text.TextLayout, x: Float, y: Float, colour: dev.wildware.composegl.ui.graphics.Colour) = Unit
        override fun image(
            texture: dev.wildware.composegl.ui.graphics.TextureHandle,
            destination: dev.wildware.composegl.ui.geometry.Rect,
            tint: dev.wildware.composegl.ui.graphics.Colour,
            source: dev.wildware.composegl.ui.geometry.Rect?,
        ) = Unit
        override fun pushClip(rect: dev.wildware.composegl.ui.geometry.Rect) = Unit
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
