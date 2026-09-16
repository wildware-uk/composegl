package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.text.TextOutline
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideTextOutline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The game widgets [dev.wildware.composegl.ui.widget.LocalTextOutline] names really do draw the ring.
 *
 * The toolkit's own widgets are checked in composegl-ui's `TextOutlineReachTest`; these two moved
 * out with the game widgets, and the promise moved with them. Each counts the copies rather than
 * trusting the wiring: a ring is eight copies round the face, so a ringed run is nine text calls.
 */
class GameTextOutlineTest {

    private val screen = Rect(0f, 0f, 400f, 300f)
    private val host = UiHost()
    private val canvas = RecordingCanvas(screen)
    private val focus = FocusManager(host.root)

    private var wall = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear(screen)
        MeasurePass().run(host.root, Constraints.atMost(screen.right, screen.bottom))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                ProvideTextOutline(Ring) { content() }
            }
        }
        frames(2)
    }

    private fun runsOf(text: String) = canvas.calls.filterIsInstance<DrawCall.Text>().filter { it.text == text }

    @Test
    fun `damage numbers take the ring from around them`() {
        val numbers = DamageNumbers(clock = Clock.Ui)
        show { DamageNumberLayer(numbers) }
        numbers.show("42", WorldAnchor.at(100f, 100f))
        frames(2)

        assertEquals(9, runsOf("42").size, "the layer is the case the whole feature was written for")
        assertTrue(runsOf("42").take(8).all { it.colour.argb and 0xFFFFFF == Ring.colour.argb and 0xFFFFFF })
    }

    @Test
    fun `a damage number's ring goes out with the number`() {
        val numbers = DamageNumbers(lifeMillis = 900, clock = Clock.Ui)
        show { DamageNumberLayer(numbers) }
        numbers.show("42", WorldAnchor.at(100f, 100f))
        frames(2)
        assertEquals(255, runsOf("42").last().colour.alpha, "it starts at full strength")

        // Past the point where a number starts fading, well before it expires.
        frames(10, 80)

        val drawn = runsOf("42")
        assertEquals(9, drawn.size, "still on screen")
        val faceAlpha = drawn.last().colour.alpha
        assertTrue(faceAlpha < 200, "it should be well into its fade by now: $faceAlpha")
        assertTrue(
            drawn.take(8).all { it.colour.alpha == faceAlpha },
            "the ring fades with the digits, or a faded-out number leaves a solid silhouette behind",
        )
    }

    @Test
    fun `the minimap's compass letters are outlined`() {
        show { MinimapFrame(Modifier.size(200f, 120f), compass = "N", live = false) }
        frames(2)

        assertEquals(9, runsOf("N").size, "north is drawn over whatever the game drew underneath it")
        assertTrue(runsOf("N").take(8).all { it.colour.argb and 0xFFFFFF == Ring.colour.argb and 0xFFFFFF })
    }

    @Test
    fun `a compass bar's names are outlined`() {
        show { CompassBar(heading = 0f, modifier = Modifier.size(400f, 80f), live = false) }
        frames(2)

        assertEquals(9, runsOf("N").size, "the strip sits over the game, so its names need the ring")
        assertTrue(runsOf("N").take(8).all { it.colour.argb and 0xFFFFFF == Ring.colour.argb and 0xFFFFFF })
    }

    private companion object {
        val Ring = TextOutline(Colour.Black, width = 2f)
    }
}
