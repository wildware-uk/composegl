package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.text.TextOutline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An outlined label lays out exactly like an unoutlined one.
 *
 * That is the promise this whole feature rests on, and it is the one worth testing hardest.
 * The ring is painted outside the box and the box does not grow for it, the way the outline effect
 * in `composegl-effects` already reaches past the node it wraps. So switching an outline on cannot
 * move a neighbour, cannot rewrap a paragraph, and cannot drop the words off the baseline that a
 * key-cap beside them is sitting on. Every assertion below is a different way of saying that.
 */
class TextOutlineWidgetTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private var clock = 0L

    /** 16pt monospace at 0.6: every character is 9.6 wide and every line 20 tall. */
    private val advance = 9.6f

    private val ring = TextOutline(Colour.Black, width = 2f)

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
    }

    private fun frame(width: Float = 400f, height: Float = 400f) {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(width, height))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun node() = find(host.root, "text") ?: error("no text node was composed")

    private fun find(node: UiNode, name: String): UiNode? =
        if (node.name == name) node else node.children.firstNotNullOfOrNull { find(it, name) }

    private fun runs() = canvas.only<DrawCall.Text>()

    /** The one drawn in the text's own colour rather than the ring's. */
    private fun face() = runs().last()

    @Test
    fun `an outline changes nothing about the box or where the words are drawn`() {
        show { Text("hello") }
        frame()
        val plainWidth = node().width
        val plainHeight = node().height
        val plainAt = face().at
        assertEquals(1, runs().size, "no outline means one run, as it always did")

        show { Text("hello", outline = ring) }
        frame()

        assertEquals(plainWidth, node().width, "the ring is painted outside the box, not inside a bigger one")
        assertEquals(plainHeight, node().height)
        assertEquals(plainAt, face().at, "and the words are in exactly the same place")
        assertEquals(9, runs().size, "eight copies and the face")
    }

    @Test
    fun `wrapping is offered the same width outlined as unoutlined`() {
        val sentence = "the relay went quiet six hours ago"
        show { Text(sentence, Modifier.width(10f * advance)) }
        frame()
        val plainHeight = node().height
        assertTrue(plainHeight > 20f, "the sentence wrapped, so there is something to compare")

        show { Text(sentence, Modifier.width(10f * advance), outline = ring) }
        frame()

        assertEquals(plainHeight, node().height, "the same words broke into the same lines")
        assertTrue(node().width <= 10f * advance, "and nothing reports itself wider than it was allowed")
    }

    @Test
    fun `alignment puts the words where it would have put them anyway`() {
        for (align in HorizontalAlignment.entries) {
            show { Text("hi", Modifier.width(200f), align = align) }
            frame()
            val plain = face().at

            show { Text("hi", Modifier.width(200f), align = align, outline = ring) }
            frame()

            assertEquals(plain, face().at, "$align should not move because of a ring round the letters")
        }
    }

    @Test
    fun `the ring reaches outside the box - the price of not growing it`() {
        show { Text("hi", outline = ring) }
        frame()

        val box = node()
        val leftmost = runs().minOf { it.at.x }
        assertTrue(leftmost < 0f, "the ring is painted left of the node's own left edge")
        assertEquals(0f, box.width - 2f * advance, "and the node is still just the width of the words")
    }

    @Test
    fun `a whole subtree can be outlined at once`() {
        show {
            ProvideTextOutline(Colour.Black, width = 2f) {
                Text("hello")
            }
        }
        frame()

        assertEquals(9, runs().size, "the label took the ring from around it")
        assertEquals(Colour.Black, runs().first().colour)
    }

    @Test
    fun `a label that names its own outline ignores the one around it`() {
        show {
            ProvideTextOutline(Colour.Black, width = 2f) {
                Text("hello", outline = TextOutline(Colour.White, width = 4f))
            }
        }
        frame()

        assertEquals(9, runs().size)
        assertEquals(Colour.White, runs().first().colour, "its own ring, not the surrounding one")
        // Width four, so the straight copies are four out rather than two.
        assertEquals(-4f, runs().minOf { it.at.x })
    }

    @Test
    fun `a label can refuse the outline around it by asking for none`() {
        show {
            ProvideTextOutline(Colour.Black, width = 2f) {
                Text("hello", outline = null)
            }
        }
        frame()

        assertEquals(1, runs().size, "an explicit null is an explicit no")
    }

    @Test
    fun `turning an outline on part way through redraws the label`() {
        // One composition across both frames, changed in place. Composing it again instead would
        // throw every `remember` away between the frames, and then the label would repaint however
        // its painter was keyed — which is to say the test would pass with the outline missing from
        // the key and would be guarding nothing.
        var outline by mutableStateOf<TextOutline?>(null)
        show { Text("hello", outline = outline) }
        frame()
        assertEquals(1, runs().size)

        outline = ring
        frame()

        assertEquals(9, runs().size, "the painter is remembered on the outline too, or nothing repaints")
    }

    @Test
    fun `nothing is outlined unless something asked for it`() {
        var ambient: TextOutline? = ring
        show {
            ambient = LocalTextOutline.current
            Text("hello")
        }
        frame()

        assertNull(ambient, "with nothing providing one, the ambient outline is no ring at all")
        assertEquals(1, runs().size)
        assertEquals(Offset(0f, 0f), face().at)
    }
}
