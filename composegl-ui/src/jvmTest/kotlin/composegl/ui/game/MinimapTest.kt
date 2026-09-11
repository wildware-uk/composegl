package composegl.ui.game

import androidx.compose.runtime.Composable
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.draw.DrawPass
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.host.UiHost
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.size
import composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The chrome round a minimap.
 *
 * The issue's two: a game draws its own map inside it, and an edge marker points at the right
 * bearing whatever shape the frame is. The second is the interesting one — the maths is a ray
 * meeting a rectangle, and a round version of it is wrong on every map that is not square.
 */
class MinimapTest {

    private val host = UiHost()
    private val bounds = Rect(0f, 0f, 400f, 400f)
    private val canvas = RecordingCanvas(bounds)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear(bounds)
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        repeat(3) { frame() }
    }

    /** Everything the frame drew for a marker: one fan for an arrow, one square for a dot. */
    private fun arrows() = canvas.calls.filterIsInstance<DrawCall.Fan>()

    private fun markerDots() = canvas.calls.filterIsInstance<DrawCall.Rectangle>().filter {
        it.colour == Colour(0xFFF2C94C.toInt())
    }

    private fun letter(which: String) =
        canvas.calls.filterIsInstance<DrawCall.Text>().firstOrNull { it.text == which }

    /** The tip of an arrow: the first point the fan was given. */
    private fun tipOf(fan: DrawCall.Fan) = fan.points.first()

    // --- the issue's two -----------------------------------------------------------------------

    @Test
    fun `the game draws its own map inside the frame and it is clipped to it`() {
        var area: Rect? = null
        show {
            MinimapFrame(Modifier.size(200f, 120f), live = false) { inner ->
                area = inner
                // Twice the size of the hole it was given, on purpose.
                rect(Rect(inner.left - 50f, inner.top - 50f, inner.right + 50f, inner.bottom + 50f), Colour(0xFF123456.toInt()), 0f)
            }
        }

        val map = canvas.calls.filterIsInstance<DrawCall.Rectangle>().first { it.colour == Colour(0xFF123456.toInt()) }
        val inner = checkNotNull(area)

        assertEquals(inner, map.clip, "what the game draws has to be clipped to the hole it was given")
        assertTrue(inner.right - inner.left < 200f, "the hole is inside the frame, not the frame")
    }

    @Test
    fun `an objective off the map sits on the edge of a frame that is not square`() {
        // Twice as wide as it is tall, which is where a circle would be wrong.
        val northEast = MinimapMarker(x = 500f, y = 500f)
        show { MinimapFrame(Modifier.size(300f, 140f), markers = listOf(northEast), range = 100f, live = false) }

        val tip = tipOf(arrows().first())
        val middleX = 150f
        val middleY = 70f

        // North east at forty five degrees: on a wide frame that is the top edge, not the side.
        assertTrue(tip.y < middleY + 1f, "it should be above the middle")
        assertTrue(tip.x > middleX, "and to the right of it")
        assertTrue(
            abs((tip.x - middleX) - (middleY - tip.y)) < 14f,
            "forty five degrees means it goes as far across as it goes up: $tip",
        )
        assertTrue(tip.y < 20f, "a forty five degree bearing leaves a wide frame by its top: $tip")
    }

    @Test
    fun `an objective due east is on the middle of the right hand edge`() {
        val east = MinimapMarker(x = 900f, y = 0f)
        show { MinimapFrame(Modifier.size(300f, 140f), markers = listOf(east), range = 100f, live = false) }

        val tip = tipOf(arrows().first())
        assertEquals(70f, tip.y, 2f, "due east is level with the player")
        assertTrue(tip.x > 280f, "and hard against the right hand edge: $tip")
    }

    // --- the rest of it ------------------------------------------------------------------------

    @Test
    fun `something close is drawn where it is rather than on the edge`() {
        val near = MinimapMarker(x = 25f, y = 0f)
        show { MinimapFrame(Modifier.size(200f, 200f), markers = listOf(near), range = 100f, live = false) }

        assertTrue(arrows().isEmpty(), "something on the map is a dot, not an arrow")
        val dot = markerDots().first().rect
        val at = (dot.left + dot.right) / 2f
        // A quarter of the way out to the right hand edge of the hole.
        assertEquals(100f + (100f - 6f) / 4f, at, 2f, "it is not where the game put it: $at")
    }

    @Test
    fun `an arrow points away from the middle`() {
        val west = MinimapMarker(x = -900f, y = 0f)
        show { MinimapFrame(Modifier.size(200f, 200f), markers = listOf(west), range = 100f, live = false) }

        val arrow = arrows().first()
        val tip = tipOf(arrow)
        val back = arrow.points.drop(1)

        assertTrue(back.all { it.x > tip.x }, "the arrow's tip has to be its outermost point: ${arrow.points}")
    }

    @Test
    fun `a marker that does not want the edge is simply not drawn`() {
        val far = MinimapMarker(x = 900f, y = 0f, edge = false)
        show { MinimapFrame(Modifier.size(200f, 200f), markers = listOf(far), range = 100f, live = false) }

        assertTrue(arrows().isEmpty())
        assertTrue(markerDots().isEmpty())
    }

    @Test
    fun `north is up on a map that does not turn`() {
        show { MinimapFrame(Modifier.size(200f, 200f), heading = 90f, live = false) }

        val north = checkNotNull(letter("N"))
        assertTrue(north.at.y < 40f, "north should be at the top of a map that does not turn")
        assertEquals(100f, north.at.x, 8f)
    }

    @Test
    fun `north swings round on a map that turns with the player`() {
        show { MinimapFrame(Modifier.size(200f, 200f), heading = 90f, rotate = true, live = false) }

        val north = checkNotNull(letter("N"))
        assertTrue(north.at.x < 40f, "facing east puts north on the left: ${north.at}")
        assertEquals(100f, north.at.y, 10f)
    }

    @Test
    fun `the compass can name every point or none`() {
        show { MinimapFrame(Modifier.size(200f, 200f), compass = "NESW", live = false) }
        assertTrue(listOf("N", "E", "S", "W").all { letter(it) != null }, "all four should be drawn")

        host.setContent {
            ProvideFonts(MonospaceFontProvider()) { MinimapFrame(Modifier.size(200f, 200f), compass = "", live = false) }
        }
        repeat(3) { frame() }
        assertNull(letter("N"), "a map that wants no compass should not have one")
    }

    @Test
    fun `a marker can wear a style of its own`() {
        val objective = MinimapMarker(x = 20f, y = 0f, style = "label.danger")
        show { MinimapFrame(Modifier.size(200f, 200f), markers = listOf(objective), range = 100f, live = false) }

        assertTrue(markerDots().isEmpty(), "it should not be drawn in the frame's own marker colour")
        assertNotNull(
            canvas.calls.filterIsInstance<DrawCall.Rectangle>().firstOrNull { it.colour == Colour(0xFFE5484D.toInt()) },
            "a marker with a style of its own should be drawn in it",
        )
    }

    @Test
    fun `a map that is not live costs nothing`() {
        show { MinimapFrame(Modifier.size(200f, 200f), live = false) }

        repeat(30) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it redrew a map nothing is happening on")
        }
    }

    @Test
    fun `a live map is redrawn every frame`() {
        show { MinimapFrame(Modifier.size(200f, 200f), live = true) }

        repeat(5) {
            wall += 16_000_000L
            assertTrue(host.frame(wall), "frame $it did not redraw a map the game is moving under")
        }
    }
}
