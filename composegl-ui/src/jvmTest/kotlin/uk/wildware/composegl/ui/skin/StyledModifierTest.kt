package uk.wildware.composegl.ui.skin

import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.layout.Padding
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.size
import uk.wildware.composegl.ui.modifier.styled
import uk.wildware.composegl.ui.node.UiNode
import uk.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A widget drawn entirely from a style, with no colour of its own.
 *
 * This is the part of the skin that has to actually reach the screen. The style says what goes
 * behind, how far in the contents sit and how far they move; the widget writes one modifier and a
 * piece of text, and knows none of those numbers.
 */
class StyledModifierTest {

    private val canvas = RecordingCanvas()
    private val tree = UiTree()

    private val skin = Skin(
        styles = mapOf(
            "button" to Style(
                base = StateStyle(
                    background = SkinDrawable.Fill(Colour.rgb(0x203040), corner = 4f),
                    padding = Padding.all(10f),
                ),
                pressed = StateStyle(
                    background = SkinDrawable.Fill(Colour.rgb(0x405060), corner = 4f),
                    contentOffset = Offset(0f, 2f),
                ),
            ),
        ),
    )

    /** A button-shaped node with a label inside it, dressed by the skin and nothing else. */
    private fun button(states: Set<WidgetState>): UiNode {
        val style = skin.resolve("button", states)
        val label = UiNode("label").also { it.modifier = Modifier.size(20f) }
        label.content = { rect -> rect(rect, style.textColour) }
        return UiNode("button").also {
            it.modifier = Modifier.size(60f).styled(style)
            it.insertAt(0, label)
        }
    }

    private fun draw(node: UiNode) {
        tree.root.insertAt(0, node)
        MeasurePass().run(tree.root, Constraints.atMost(200f, 200f))
        DrawPass(canvas).draw(tree.root)
        canvas.assertBalanced()
    }

    private fun rectangles() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()

    @Test
    fun `the background comes from the style and covers the node`() {
        draw(button(emptySet()))

        val background = rectangles().first()
        assertEquals(Colour.rgb(0x203040), background.colour)
        assertEquals(4f, background.corner)
        assertEquals(Rect.of(0f, 0f, 60f, 60f), background.rect)
    }

    @Test
    fun `the contents are kept off the edge by the style's padding`() {
        draw(button(emptySet()))

        val label = rectangles()[1]
        assertEquals(
            Offset(10f, 10f),
            label.rect.topLeft,
            "the gap between the frame and the label is the skin's number, not the widget's",
        )
    }

    @Test
    fun `pressing moves the contents and leaves the frame where it is`() {
        draw(button(setOf(WidgetState.Pressed)))

        val background = rectangles().first()
        val label = rectangles()[1]

        assertEquals(Colour.rgb(0x405060), background.colour, "the pressed background")
        assertEquals(Rect.of(0f, 0f, 60f, 60f), background.rect, "the frame has not moved")
        assertEquals(
            Offset(10f, 12f),
            label.rect.topLeft,
            "and the label has, by the two pixels the skin asked for",
        )
    }

    @Test
    fun `a style with nothing behind it draws nothing behind it`() {
        val plain = UiNode("plain").also { it.modifier = Modifier.size(30f).styled(ResolvedStyle.Plain) }
        plain.content = { rect -> rect(rect, Colour.White) }
        draw(plain)

        assertEquals(
            1,
            rectangles().size,
            "an unskinned widget still draws itself, and adds nothing it was not asked for",
        )
    }
}
