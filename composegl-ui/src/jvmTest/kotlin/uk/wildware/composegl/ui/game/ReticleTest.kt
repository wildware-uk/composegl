package uk.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import uk.wildware.composegl.ui.animation.Clock
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.skin.Skin
import uk.wildware.composegl.ui.skin.SkinDrawable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The crosshair.
 *
 * The issue's three: the spread animates rather than snapping, a second hit does not stack a
 * second marker on the first, and the whole thing sits exactly in the middle whatever shape the
 * window is.
 */
class ReticleTest {

    private val host = UiHost()

    private var width = 400f
    private var height = 400f
    private var canvas = RecordingCanvas(Rect(0f, 0f, width, height))

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear(Rect(0f, 0f, width, height))
        MeasurePass().run(host.root, Constraints.atMost(width, height))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent(content)
        frames(2)
    }

    /** The runtime takes a frame to notice a state change and another to act on it. */
    private fun begin() = frames(2, millis = 0)

    private fun settle() = frame(0)

    private fun state() = ReticleState(Clock.Ui)

    private fun fill(style: String): Colour =
        (Skin.Default.resolve(style).background as SkinDrawable.Fill).colour

    private fun arms(colour: Colour = fill("reticle")): List<DrawCall.Rectangle> =
        canvas.calls.filterIsInstance<DrawCall.Rectangle>().filter { it.colour == colour }

    private fun ticks(): List<DrawCall.Fan> = canvas.calls.filterIsInstance<DrawCall.Fan>()

    /** How far the left arm's inner end is from the middle: the gap the spread opens up. */
    private fun gap(): Float {
        val middle = width / 2f
        val left = arms().minByOrNull { it.rect.left } ?: error("no arms drawn")
        return middle - left.rect.right
    }

    // --- the issue's three ----------------------------------------------------------------------

    @Test
    fun `the spread animates rather than snapping`() {
        val reticle = state()
        show { Reticle(reticle, spreadDistance = 40f) }
        val closed = gap()

        reticle.spread = 1f
        begin()
        frame(20)
        settle()

        val opening = gap()
        assertTrue(opening > closed, "it should have started opening")
        assertTrue(opening < closed + 40f, "and not arrived in one frame: $opening")

        frames(20, 20)
        assertEquals(closed + 40f, gap(), 1f, "and then it is all the way open")
    }

    @Test
    fun `a second hit restarts the marker rather than stacking on it`() {
        val reticle = state()
        show { Reticle(reticle) }

        reticle.hit()
        begin()
        val first = ticks()
        assertEquals(4, first.size, "a hit marker is four ticks")

        frames(4, 40)
        reticle.hit()
        begin()

        assertEquals(4, ticks().size, "eight ticks means two markers drawn on top of each other")
        assertEquals(
            first.first().colour.alphaFraction,
            ticks().first().colour.alphaFraction,
            0.05f,
            "and the second one is as bright as the first rather than brighter",
        )
    }

    @Test
    fun `the crosshair is in the middle at every shape of window`() {
        val reticle = state()
        show { Reticle(reticle) }

        listOf(400f to 400f, 1920f to 1080f, 640f to 1136f, 801f to 457f).forEach { (w, h) ->
            width = w
            height = h
            frame()

            val drawn = arms()
            val left = drawn.minByOrNull { it.rect.left }!!
            val right = drawn.maxByOrNull { it.rect.right }!!
            val top = drawn.minByOrNull { it.rect.top }!!
            val bottom = drawn.maxByOrNull { it.rect.bottom }!!

            assertEquals(w / 2f - left.rect.right, right.rect.left - w / 2f, 0.01f, "off centre across at ${w}x$h")
            assertEquals(h / 2f - top.rect.bottom, bottom.rect.top - h / 2f, 0.01f, "off centre down at ${w}x$h")
            assertEquals(w / 2f, top.rect.centre.x, 0.01f, "the vertical arms are not on the middle at ${w}x$h")
            assertEquals(h / 2f, left.rect.centre.y, 0.01f, "the horizontal arms are not on the middle at ${w}x$h")
        }
    }

    // --- the rest of it -------------------------------------------------------------------------

    @Test
    fun `a hostile target changes the colour and nothing else`() {
        val reticle = state()
        show { Reticle(reticle) }
        val friendly = arms().map { it.rect }

        reticle.hostile = true
        begin()

        assertTrue(arms().isEmpty(), "it should not still be drawn in the ordinary colour")
        assertEquals(friendly, arms(fill("reticle.hostile")).map { it.rect }, "and it should not have moved")
    }

    @Test
    fun `a kill is marked in its own colour`() {
        val reticle = state()
        show { Reticle(reticle) }

        reticle.hit(kill = true)
        begin()

        assertEquals(
            fill("reticle.kill").withAlpha(255),
            ticks().first().colour.withAlpha(255),
            "a kill is not an ordinary hit",
        )
    }

    @Test
    fun `a marker fades out and leaves nothing behind`() {
        val reticle = state()
        show { Reticle(reticle) }

        reticle.hit()
        begin()
        assertEquals(4, ticks().size)

        frames(20, 40)
        assertTrue(ticks().isEmpty(), "the marker should be gone")
    }

    @Test
    fun `a still crosshair costs nothing`() {
        val reticle = state()
        show { Reticle(reticle) }

        reticle.spread = 0.5f
        reticle.hit()
        frames(30, 40)

        repeat(100) { assertFalse(frame(), "frame $it redrew a crosshair that is not doing anything") }
    }

    @Test
    fun `the dot is optional and centred`() {
        val reticle = state()
        show { Reticle(reticle, dot = 4f) }

        val dot = arms().single { it.rect.width <= 4f && it.rect.height <= 4f }

        assertEquals(width / 2f, dot.rect.centre.x, 0.01f)
        assertEquals(height / 2f, dot.rect.centre.y, 0.01f)
    }
}
