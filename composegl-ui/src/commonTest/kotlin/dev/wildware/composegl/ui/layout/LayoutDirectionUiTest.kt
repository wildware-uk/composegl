package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.paddingRelative
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The same layouts in a right-to-left screen, composed for real: every one of them the mirror image
 * of itself, and nothing that names a side outright moved.
 *
 * Each layout under test is the screen's first child, so it sits at the screen's top-left and every
 * number here is measured from zero.
 */
class LayoutDirectionUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(direction: LayoutDirection = LayoutDirection.Rtl, content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f)) { ProvideLayoutDirection(direction, content) }.also { opened += it }

    private fun UiTest.left(tag: String) = node(tag).boundsInRoot.left
    private fun UiTest.top(tag: String) = node(tag).boundsInRoot.top

    @Composable
    private fun Square(tag: String, side: Float = 50f) = Box(Modifier.size(side).testTag(tag))

    @Test
    fun `a row puts its first child on the right`() {
        val ui = open {
            Row(Modifier.width(300f)) { Square("a"); Square("b"); Square("c") }
        }

        assertEquals(listOf(250f, 200f, 150f), listOf(ui.left("a"), ui.left("b"), ui.left("c")))
    }

    @Test
    fun `the same row left to right is where it always was`() {
        val ui = open(LayoutDirection.Ltr) {
            Row(Modifier.width(300f)) { Square("a"); Square("b"); Square("c") }
        }

        assertEquals(listOf(0f, 50f, 100f), listOf(ui.left("a"), ui.left("b"), ui.left("c")))
    }

    @Test
    fun `a fixed gap and space between mirror with the row`() {
        val ui = open {
            Column(Modifier.width(300f)) {
                Row(Modifier.width(300f), horizontalArrangement = Arrangement.spacedBy(10f)) { Square("a"); Square("b") }
                Row(Modifier.width(300f), horizontalArrangement = Arrangement.SpaceBetween) { Square("x"); Square("y"); Square("z") }
            }
        }

        assertEquals(listOf(250f, 190f), listOf(ui.left("a"), ui.left("b")))
        assertEquals(listOf(250f, 125f, 0f), listOf(ui.left("x"), ui.left("y"), ui.left("z")))
    }

    @Test
    fun `a weighted child takes what is left from the other side`() {
        val ui = open {
            Row(Modifier.width(300f)) {
                Square("fixed")
                Box(Modifier.weight(1f).height(50f).testTag("grow"))
            }
        }

        assertEquals(250f, ui.left("fixed"))
        assertEquals(0f, ui.left("grow"))
        assertEquals(250f, ui.node("grow").boundsInRoot.width)
    }

    @Test
    fun `a column's start children hug the right and its end children the left`() {
        val ui = open {
            Row {
                Column(Modifier.width(200f)) { Square("start") }
                Column(Modifier.width(200f), horizontalAlignment = HorizontalAlignment.End) { Square("end") }
            }
        }

        assertEquals(350f, ui.left("start"), "the first column is the right-hand one, and its child is against its right")
        assertEquals(0f, ui.left("end"))
    }

    @Test
    fun `a box's top start corner is its top right`() {
        val ui = open {
            Box(Modifier.size(200f)) {
                Square("start")
                Box(Modifier.size(50f).align(Alignment.BottomEnd).testTag("end"))
                Box(Modifier.size(50f).align(Alignment.Centre).testTag("centre"))
            }
        }

        assertEquals(150f, ui.left("start"))
        assertEquals(0f, ui.top("start"))
        assertEquals(0f, ui.left("end"))
        assertEquals(150f, ui.top("end"))
        assertEquals(75f, ui.left("centre"))
    }

    @Test
    fun `a flow row fills from the right and wraps onto a line below`() {
        val ui = open {
            FlowRow(Modifier.width(120f)) { Square("a"); Square("b"); Square("c") }
        }

        assertEquals(listOf(70f, 20f, 70f), listOf(ui.left("a"), ui.left("b"), ui.left("c")))
        assertEquals(50f, ui.top("c") - ui.top("a"))
    }

    @Test
    fun `a grid's first column is on the right`() {
        val ui = open {
            Grid(GridCells.Fixed(2), Modifier.width(200f)) { Square("a"); Square("b"); Square("c") }
        }

        assertEquals(listOf(150f, 50f, 150f), listOf(ui.left("a"), ui.left("b"), ui.left("c")))
        assertEquals(50f, ui.top("c"))
    }

    @Test
    fun `padding at the start is on the right`() {
        val ui = open {
            Box(Modifier.width(200f).paddingRelative(start = 20f, end = 5f)) { Square("inside") }
        }

        assertEquals(130f, ui.left("inside"), "twenty in from the right, and the child against it")
    }

    @Test
    fun `padding at the start is on the left left to right`() {
        val ui = open(LayoutDirection.Ltr) {
            Box(Modifier.width(200f).paddingRelative(start = 20f, end = 5f)) { Square("inside") }
        }

        assertEquals(20f, ui.left("inside"))
    }

    @Test
    fun `padding that names a side stays on that side`() {
        val ui = open {
            Box(Modifier.width(200f).padding(left = 20f)) { Square("inside", side = 200f) }
        }

        assertEquals(20f, ui.left("inside"))
        assertEquals(180f, ui.node("inside").boundsInRoot.width)
    }

    @Test
    fun `a custom layout is left as it is and can ask the direction`() {
        var asked: LayoutDirection? = null
        val ui = open {
            Layout(Modifier.width(300f), content = { Square("placed") }) { measurables, constraints ->
                asked = layoutDirection
                val placeable = measurables[0].measure(constraints)
                layout(300f, 50f) { placeable.at(10f, 0f) }
            }
        }

        assertEquals(LayoutDirection.Rtl, asked)
        assertEquals(10f, ui.left("placed"))
    }

    @Test
    fun `tab goes first to last while the pad goes the way the buttons are drawn`() {
        val ui = open {
            Row {
                Button("FIRST", onClick = {}, modifier = Modifier.testTag("first"))
                Button("SECOND", onClick = {}, modifier = Modifier.testTag("second"))
            }
        }
        assertTrue(ui.left("first") > ui.left("second"), "the first button is drawn on the right")
        ui.assertFocused("first")

        ui.pad(GamepadButton.DpadLeft)
        ui.assertFocused("second")
        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("first")
        ui.key(Key.Tab)
        ui.assertFocused("second")
    }

    @Test
    fun `a still right to left screen draws nothing new`() {
        val ui = open {
            Column(Modifier.width(300f).paddingRelative(start = 12f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8f)) { Square("a"); Square("b") }
                FlowRow { Square("c"); Square("d") }
            }
        }

        ui.render()

        assertFalse(ui.render())
    }
}
