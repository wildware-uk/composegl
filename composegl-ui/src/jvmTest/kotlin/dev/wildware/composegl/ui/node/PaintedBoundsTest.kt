package dev.wildware.composegl.ui.node

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.ImageFit
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a subtree actually painted, as opposed to where its nodes are.
 *
 * For most nodes those are the same rectangle; for text they are not, and that difference is the
 * whole reason this exists. A text node's box is a *line* box — line height, plus any insets — and
 * the glyphs inside it occupy a smaller rectangle. A frame sized to the painted content and checked
 * against the node box is told it does not contain its own contents, by roughly the leading plus
 * the descent: small enough to look like a rounding bug, big enough to fail a strict check.
 *
 * At 16pt monospace the numbers are: ascent 12.8, descent 3.2, line height 20 by default here, so
 * the glyphs are 16 of a 20-unit line box and sit at the top of it.
 */
class PaintedBoundsTest {

    private val host = UiHost()
    private val fonts = MonospaceFontProvider()
    private val style = TextStyle(size = 16f)

    @AfterEach
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(fonts) { content() } }
        host.frame(0L)
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
    }

    private fun node(name: String): UiNode =
        find(host.root, name) ?: error("no $name node was composed")

    private fun find(node: UiNode, name: String): UiNode? =
        if (node.name == name) node else node.children.firstNotNullOfOrNull { find(it, name) }

    @Test
    fun `a node nobody has laid out yet has not painted nothing - it has not been asked`() {
        // The two answers a caller has to be able to tell apart, and a zero-sized rectangle at the
        // origin cannot: "drew nothing" and "no layout pass has reached this".
        assertNull(UiNode("fresh").paintedInRoot)
    }

    @Test
    fun `a bare box used for layout paints nothing at all`() {
        show { Box(Modifier.size(100f, 50f)) {} }

        val box = node("box")
        assertEquals(Rect.of(0f, 0f, 100f, 50f), box.boundsInRoot, "it is somewhere")
        assertNull(box.paintedInRoot, "but it put no ink anywhere")
    }

    @Test
    fun `a background is paint, so the same box now reports one`() {
        show { Box(Modifier.size(100f, 50f).background(Colour.White)) {} }

        assertEquals(Rect.of(0f, 0f, 100f, 50f), node("box").paintedInRoot)
    }

    @Test
    fun `where a background is painted follows the chain order`() {
        // The property PaintOp's inset exists for: padding *then* background paints inside the
        // padding, so the ink is the smaller rectangle and this has to say so.
        show { Box(Modifier.size(100f, 50f).padding(8f).background(Colour.White)) {} }

        assertEquals(Rect(8f, 8f, 92f, 42f), node("box").paintedInRoot)
    }

    @Test
    fun `a text node paints its glyphs and not its line box`() {
        show { Text("hi", textStyle = style) }

        val text = node("text")
        val metrics = fonts.metrics(style)
        val painted = checkNotNull(text.paintedInRoot) { "the text drew nothing" }

        assertEquals(20f, text.boundsInRoot.height, "the line box is the style's line height")
        assertEquals(metrics.height, painted.height, 1e-4f, "the glyphs are the ascent and descent")
        assertTrue(painted.height < text.boundsInRoot.height, "which is the whole point of asking")
        assertEquals(0f, painted.top, 1e-4f, "the first baseline here is the ascent, so they start at the top")
        assertEquals(metrics.height, painted.bottom, 1e-4f, "and the leading under them is not ink")
    }

    @Test
    fun `over several lines the leading under the last one is still not ink`() {
        show { Text("one\ntwo", textStyle = style) }

        val text = node("text")
        val metrics = fonts.metrics(style)
        val painted = checkNotNull(text.paintedInRoot) { "the text drew nothing" }

        assertEquals(40f, text.boundsInRoot.height, "two 20-unit line boxes")
        // Last baseline is 12.8 + 20, and the descent hangs 3.2 under it.
        assertEquals(12.8f + 20f + metrics.descent, painted.bottom, 1e-4f)
    }

    @Test
    fun `a subtree is the union of what everything under it painted`() {
        show {
            Column(Modifier.size(200f, 200f)) {
                Box(Modifier.size(40f, 10f).background(Colour.White)) {}
                Text("hi", textStyle = style)
            }
        }

        val column = node("column")
        val painted = checkNotNull(column.paintedInRoot) { "the column drew nothing" }

        // The column itself paints nothing, so its extent is entirely its children's — which is
        // what separates this from unioning the node rectangles, where the 200-square column would
        // swallow both answers.
        assertEquals(Rect.of(0f, 0f, 200f, 200f), column.boundsInRoot, "the column is the big one")
        assertEquals(0f, painted.top, 1e-4f)
        assertEquals(40f, painted.right, 1e-4f, "the wider of the two children")
        assertEquals(10f + fonts.metrics(style).height, painted.bottom, 1e-4f, "the box, then the glyphs")
    }

    @Test
    fun `a letterboxed picture paints where it landed and not the bars beside it`() {
        // A square box round a wide picture: Contain leaves a bar top and bottom, and nothing is
        // painted there. Using the node box would report the bars as content.
        show { Image(FakeTexture(100, 50), Modifier.size(100f, 100f), fit = ImageFit.Contain) }

        val image = node("image")
        val painted = checkNotNull(image.paintedInRoot) { "the picture drew nothing" }

        assertEquals(Rect.of(0f, 0f, 100f, 100f), image.boundsInRoot, "the node is the square box")
        assertEquals(Rect(0f, 25f, 100f, 75f), painted, "the picture is the band in the middle of it")
    }

    @Test
    fun `a scale folds in, the same way it does for where a node is`() {
        show {
            Box(Modifier.scale(2f, Alignment.TopStart)) {
                Box(Modifier.size(100f, 50f).background(Colour.White)) {}
            }
        }

        // Drawn pixels, not laid-out ones. Folding it into drawing alone is the mistake that makes
        // a scaled screen look perfect and answer every other question wrong.
        val painted = checkNotNull(node("box").paintedInRoot) { "the box drew nothing" }
        assertEquals(Rect(0f, 0f, 200f, 100f), painted)
    }
}
