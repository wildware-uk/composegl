package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An emoji in typed-out text. Nearly every emoji is past U+FFFF, so it is two chars, and either
 * half on its own is a character no font has: drawn, it is a box.
 */
class TypewriterEmojiTest {

    private val screen = Rect(0f, 0f, 400f, 300f)
    private val host = UiHost()
    private val canvas = RecordingCanvas(screen)
    private var wall = 0L

    /** Every run drawn over every frame so far. */
    private val drawn = mutableListOf<String>()

    @AfterTest
    fun tearDown() = host.dispose()

    private fun frame(millis: Long) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear(screen)
        MeasurePass().run(host.root, Constraints.atMost(screen.right, screen.bottom))
        DrawPass(canvas).draw(host.root)
        drawn += canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }
    }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        // Small steps, so that some frame lands between the two halves of the emoji.
        repeat(60) { frame(5) }
    }

    private fun halves() = drawn.filter { run -> run.any { it.isSurrogate() } && !wholeSurrogates(run) }

    private fun wholeSurrogates(run: String): Boolean {
        var at = 0
        while (at < run.length) {
            val high = run[at]
            if (high.isLowSurrogate()) return false
            if (high.isHighSurrogate()) {
                if (at + 1 >= run.length || !run[at + 1].isLowSurrogate()) return false
                at++
            }
            at++
        }
        return true
    }

    @Test
    fun `a typewriter with an effect draws an emoji whole and never half of one`() {
        show {
            val line = rememberTypewriter("a😀b", Clock.Ui)
            Typewriter(line, charactersPerSecond = 60f, pauses = false, effect = { })
        }

        assertEquals(emptyList(), halves(), "no run is half an emoji")
        assertTrue("😀" in drawn, "the emoji is drawn as one run of its own, got ${drawn.distinct()}")
    }

    @Test
    fun `a typewriter with no effect never reveals half an emoji`() {
        show {
            val line = rememberTypewriter("😀😀", Clock.Ui)
            Typewriter(line, charactersPerSecond = 60f, pauses = false)
        }

        assertEquals(emptyList(), halves(), "no revealed prefix ends halfway through an emoji")
        assertTrue("😀😀" in drawn, "the whole line arrives, got ${drawn.distinct()}")
    }
}
