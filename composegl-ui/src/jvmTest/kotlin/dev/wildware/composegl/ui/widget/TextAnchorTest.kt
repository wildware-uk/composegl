package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.text.TextAnchor
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where a piece of text lands when it is placed by something other than its line box.
 *
 * The bug this closes is silent — nothing clips, nothing overflows, the text is simply low — and
 * it scales with the font, so the same mistake on a screen with three text sizes reads as three
 * separate layout problems. That is why every number below is worked out from the metrics rather
 * than written down: the assertion is the *rule*, not one face's numbers.
 *
 * The monospace provider makes them easy to check on paper. At 16: ascent 12.8, cap height 11.2,
 * so the cap inset is 1.6 and the baseline is 12.8 down from the top of the line box.
 */
class TextAnchorTest {

    private val host = UiHost()
    private val fonts = MonospaceFontProvider()
    private val canvas = RecordingCanvas()

    @AfterEach
    fun tearDown() = host.dispose()

    private val small = TextStyle(size = 16f)
    private val large = TextStyle(size = 32f)

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(fonts) { content() } }
    }

    /** Lays the tree out and draws it, then hands back where the text node ended up. */
    private fun topOfText(): Float {
        host.frame(0L)
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        DrawPass(canvas).draw(host.root)
        return find(host.root, "text")?.boundsInRoot?.top ?: error("no text node was composed")
    }

    private fun find(node: UiNode, name: String): UiNode? =
        if (node.name == name) node else node.children.firstNotNullOfOrNull { find(it, name) }

    @Test
    fun `the cap inset is the gap a caller keeps rediscovering`() {
        val metrics = fonts.metrics(small)

        assertEquals(metrics.ascent - metrics.capHeight, metrics.capInset)
        assertEquals(1.6f, metrics.capInset, 1e-4f, "16pt: 12.8 of ascent over 11.2 of capital")
    }

    @Test
    fun `a line box anchor is where text has always been placed`() {
        show { Text("12", textStyle = small, anchor = TextAnchor.LineBox) }

        assertEquals(0f, topOfText(), "the default anchor moves nothing at all")
    }

    @Test
    fun `a cap top anchor lifts the node by the cap inset`() {
        show { Text("12", textStyle = small, anchor = TextAnchor.CapTop) }

        // The node moves up so that the *capitals* start where the line box used to. That is what
        // makes a ported coordinate, which means a cap top, land where it meant to.
        assertEquals(-fonts.metrics(small).capInset, topOfText(), 1e-4f)
    }

    @Test
    fun `a baseline anchor puts the baseline on the y the node was given`() {
        show { Text("12", textStyle = small, anchor = TextAnchor.Baseline) }

        val metrics = fonts.metrics(small)
        val top = topOfText()
        assertEquals(-metrics.ascent, top, 1e-4f)
        assertEquals(0f, top + metrics.ascent, 1e-4f, "which puts the baseline back at nought")
    }

    @Test
    fun `the lift scales with the face - one cause and two numbers`() {
        show { Text("12", textStyle = large, anchor = TextAnchor.CapTop) }
        val big = topOfText()

        host.setContent { ProvideFonts(fonts) { Text("12", textStyle = small, anchor = TextAnchor.CapTop) } }
        val little = topOfText()

        // This is the property that makes the bug expensive to find: one cause, a different number
        // per size, so it reads as a layout that needs tuning rather than as one offset.
        assertEquals(-fonts.metrics(large).capInset, big, 1e-4f)
        assertEquals(-fonts.metrics(small).capInset, little, 1e-4f)
        assertEquals(2f, big / little, 1e-4f, "twice the size, twice the error it was hiding")
    }

    @Test
    fun `the caller's own offset still applies on top of the anchor`() {
        show { Text("12", Modifier.offset(y = 100f), textStyle = small, anchor = TextAnchor.CapTop) }

        // Their placement is not replaced by ours. A ported layout that positions with `offset` is
        // exactly the caller this exists for, so the two have to add.
        assertEquals(100f - fonts.metrics(small).capInset, topOfText(), 1e-4f)
    }

    @Test
    fun `an anchored node is the same size as an unanchored one`() {
        show { Text("12", textStyle = small, anchor = TextAnchor.Baseline) }
        host.frame(0L)
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        val anchored = find(host.root, "text") ?: error("no text node")
        val size = anchored.size

        host.setContent { ProvideFonts(fonts) { Text("12", textStyle = small) } }
        host.frame(0L)
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        val plain = find(host.root, "text") ?: error("no text node")

        // Only where it sits moves. Nothing about the box, the wrapping or the glyphs changes,
        // which is what keeps this composable with everything else that measures text.
        assertEquals(plain.size, size)
    }
}
