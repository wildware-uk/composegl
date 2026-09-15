package dev.wildware.composegl.ui.text

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideTextOutline
import dev.wildware.composegl.ui.widget.Tooltip
import dev.wildware.composegl.ui.widget.TooltipHost
import dev.wildware.composegl.ui.widget.Typewriter
import dev.wildware.composegl.ui.widget.TypewriterEffect
import dev.wildware.composegl.ui.widget.rememberTypewriter
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every widget [dev.wildware.composegl.ui.widget.LocalTextOutline] names really does draw the ring.
 *
 * That KDoc is a promise to a game: put a ring round the HUD and these take it. A promise kept
 * by one line of wiring per widget is a promise that can be deleted by one line, and a widget that
 * quietly stopped outlining would look exactly like a widget nobody had outlined yet. So there is a
 * test per widget here, and each one counts the copies rather than trusting the wiring.
 *
 * [dev.wildware.composegl.ui.widget.Text] is covered next door in `TextOutlineWidgetTest` along
 * with everything about layout not moving, and the damage numbers and minimap compass in
 * `composegl-game`'s `GameTextOutlineTest`.
 */
class TextOutlineReachTest {

    private val screen = Rect(0f, 0f, 400f, 300f)
    private val host = UiHost()
    private val canvas = RecordingCanvas(screen)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)

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

    private fun runs() = canvas.calls.filterIsInstance<DrawCall.Text>()

    private fun runsOf(text: String) = runs().filter { it.text == text }

    // --- one per widget --------------------------------------------------------------------------

    @Test
    fun `a typewriter with no effect outlines the line it has revealed`() {
        show {
            val line = rememberTypewriter("hi", Clock.Ui)
            Typewriter(line, charactersPerSecond = 200f, pauses = false)
        }
        frames(6, 32)

        assertEquals(9, runsOf("hi").size, "eight copies and the face, from one call with the run in it")
        assertTrue(runsOf("hi").take(8).all { it.colour == Ring.colour })
    }

    @Test
    fun `a typewriter with an effect draws every ring before every face`() {
        // The reason this widget makes its own two passes: with an effect it draws a letter at a
        // time, so the canvas's "no ring lands on a face" promise is one letter wide. One pass and
        // the "i"'s ring would be stamped over the "h" that is already there.
        show {
            val line = rememberTypewriter("hi", Clock.Ui)
            Typewriter(line, charactersPerSecond = 200f, pauses = false, effect = Still)
        }
        frames(6, 32)

        val letters = runs().filter { it.text == "h" || it.text == "i" }
        assertEquals(18, letters.size, "two letters, nine runs each")
        assertEquals(
            listOf("h", "i"),
            letters.takeLast(2).map { it.text },
            "the two faces come last, after all sixteen ring copies",
        )
        assertTrue(letters.take(16).all { it.colour.argb and 0xFFFFFF == Ring.colour.argb and 0xFFFFFF })
    }

    @Test
    fun `a typewriter fades each ring with the letter inside it`() {
        // What TypewriterEffect.fadeIn does to every character, fixed at a quarter so the assertion
        // is arithmetic rather than timing. A ring at full strength here is a row of solid black
        // rings that fill themselves in afterwards.
        show {
            val line = rememberTypewriter("hi", Clock.Ui)
            Typewriter(line, charactersPerSecond = 200f, pauses = false, effect = Quarter)
        }
        frames(6, 32)

        val letters = runs().filter { it.text == "h" || it.text == "i" }
        assertEquals(18, letters.size)
        val faceAlpha = letters.last().colour.alpha
        assertTrue(faceAlpha in 50..80, "a quarter of an opaque letter: $faceAlpha")
        assertTrue(
            letters.take(16).all { abs(it.colour.alpha - faceAlpha) <= 2 },
            "every ring copy is as faded as its letter: ${letters.take(16).map { it.colour.alpha }}",
        )
    }

    @Test
    fun `a tooltip's text is outlined`() {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                ProvideTextOutline(Ring) {
                    TooltipHost(delayMillis = 100, fadeMillis = 20) {
                        Tooltip("tip") { Box(Modifier.size(40f)) }
                    }
                }
            }
        }
        frames(2)

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(20f, 20f)))
        frames(20, 20)

        assertEquals(9, runsOf("tip").size, "a tooltip over the game needs the ring as much as anything does")
        // Its fade is a pushAlpha over the box and the text together, so the ring keeps its own
        // colour and the canvas fades all nine copies at once. That is the alpha-correct way to do
        // it and the reason this widget was never the one with the fading problem.
        assertTrue(runsOf("tip").take(8).all { it.colour == Ring.colour })
    }

    private companion object {

        val Ring = TextOutline(Colour.Black, width = 2f)

        /** An effect that moves and fades nothing: only the two-pass ordering is being asked about. */
        val Still = TypewriterEffect { }

        /** Every letter at a quarter alpha, whatever its age: fadeIn, held still. */
        val Quarter = TypewriterEffect { it.colour = it.colour.scaleAlpha(0.25f) }
    }
}
