package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.TestTree
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `Modifier.zIndex`: which of a pile of siblings is drawn on top, and gets the click.
 *
 * The UI half composes a real screen in a [UiHost], renders it through [UiRenderer] every frame,
 * and drives it with pointer presses, moves and keys — the same doors a backend uses. What it
 * asserts is what a player would notice: which colour went down last, which card took the press,
 * where Tab went, where the row put things.
 */
class ZIndexModifierTest {

    private val viewport = Viewport.oneToOne(Size(400f, 300f))
    private val backend = HeadlessBackend()
    private val canvas: RecordingCanvas = backend.canvas
    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private val renderer = UiRenderer(host, canvas).also { it.focus = focus }
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)
    private var clock = 0L

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() {
        host.dispose()
        opened.forEach { it.close() }
    }

    private fun show(content: @Composable () -> Unit) {
        host.setContent(content)
        frame()
    }

    private fun frame() {
        canvas.clear()
        renderer.render(viewport, clock)
        clock += 16_666_667L
    }

    /** The colours of every rectangle drawn, in the order they reached the canvas. */
    private fun paintOrder(): List<Colour> = canvas.only<DrawCall.Rectangle>().map { it.colour }

    private fun click(x: Float, y: Float) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))
        frame()
    }

    private fun tab() {
        keys.onKey(KeyEvent(Key.Tab, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Tab, KeyEventType.Up))
        frame()
    }

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)
    private val blue = Colour.rgb(0x0000FF)

    /** Three 100-wide cards, each 50 to the right of the last, so each overlaps the next by half. */
    @Composable
    private fun Hand(z: (Int) -> Float, onClick: (Int) -> Unit = {}) {
        Box(Modifier.size(300f, 200f)) {
            listOf(red, green, blue).forEachIndexed { index, colour ->
                LeafLayout(
                    Modifier.offset(index * 50f, 0f).size(100f, 100f)
                        .zIndex(z(index))
                        .background(colour)
                        .clickable { onClick(index) },
                )
            }
        }
    }

    // --- the modifier itself ---------------------------------------------------------------

    @Test
    fun `a modifier with no zIndex on it sits at zero`() {
        assertEquals(0f, Modifier.resolve().zIndex)
    }

    @Test
    fun `two zIndexes on one node add`() {
        assertEquals(3f, Modifier.zIndex(1f).zIndex(2f).resolve().zIndex)
    }

    @Test
    fun `a zIndex that is not a number is refused`() {
        assertFailsWith<IllegalArgumentException> { Modifier.zIndex(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Modifier.zIndex(Float.POSITIVE_INFINITY) }
    }

    // --- drawing ---------------------------------------------------------------------------

    @Test
    fun `without a zIndex siblings paint in the order they are written`() {
        show { Hand(z = { 0f }) }

        assertEquals(listOf(red, green, blue), paintOrder())
    }

    @Test
    fun `a lifted sibling paints after the others`() {
        show { Hand(z = { if (it == 0) 1f else 0f }) }

        assertEquals(listOf(green, blue, red), paintOrder())
    }

    @Test
    fun `equal zIndexes keep source order`() {
        show { Hand(z = { if (it == 2) 0f else 5f }) }

        assertEquals(listOf(blue, red, green), paintOrder(), "red and green tie and stay as written")
    }

    @Test
    fun `a negative zIndex sinks under unlifted siblings`() {
        show { Hand(z = { if (it == 2) -1f else 0f }) }

        assertEquals(listOf(blue, red, green), paintOrder())
    }

    @Test
    fun `a zIndex orders siblings only and a parent carries its subtree`() {
        show {
            Box(Modifier.size(300f, 200f)) {
                Box(Modifier.size(100f, 100f).background(red)) {
                    // Enormous, and still under green: it is only lifted inside red.
                    LeafLayout(Modifier.size(50f, 50f).zIndex(100f).background(blue))
                }
                LeafLayout(Modifier.size(100f, 100f).background(green))
            }
        }

        assertEquals(listOf(red, blue, green), paintOrder())
    }

    @Test
    fun `changing a zIndex in state repaints in the new order on the next frame`() {
        var selected by mutableStateOf(-1)
        show { Hand(z = { if (it == selected) 1f else 0f }) }
        assertEquals(listOf(red, green, blue), paintOrder())

        selected = 0
        frame()
        assertEquals(listOf(green, blue, red), paintOrder(), "red was selected and came forward")

        selected = 1
        frame()
        assertEquals(listOf(red, blue, green), paintOrder(), "and went back when green was")

        selected = -1
        frame()
        assertEquals(listOf(red, green, blue), paintOrder(), "and nothing lifted is source order again")
    }

    // --- the pointer -----------------------------------------------------------------------

    @Test
    fun `a click where cards overlap goes to the one drawn on top`() {
        val clicked = mutableListOf<Int>()
        show { Hand(z = { if (it == 0) 1f else 0f }, onClick = { clicked += it }) }

        // x 75 is inside red (0..100) and green (50..150).
        click(75f, 50f)

        assertEquals(listOf(0), clicked, "red is lifted, so red is under the finger")
    }

    @Test
    fun `selecting a card by clicking it lifts it and the next click finds it on top`() {
        var selected by mutableStateOf(-1)
        val clicked = mutableListOf<Int>()
        show {
            Hand(z = { if (it == selected) 1f else 0f }, onClick = { clicked += it; selected = it })
        }

        // Nothing lifted: green is written after red, so the overlap is green's.
        click(75f, 50f)
        assertEquals(listOf(1), clicked)
        assertEquals(listOf(red, blue, green), paintOrder(), "green came forward over blue too")

        // Green is now over blue as well, so the green-blue overlap is still green.
        click(125f, 50f)
        assertEquals(listOf(1, 1), clicked)

        // Red's own part, clear of every overlap, selects red, which then owns the red-green overlap.
        click(25f, 50f)
        click(75f, 50f)
        assertEquals(listOf(1, 1, 0, 0), clicked)
        assertEquals(listOf(green, blue, red), paintOrder())
    }

    @Test
    fun `hover lands on the lifted card and not the one under it`() {
        val states = List(2) { InteractionState() }
        show {
            Box(Modifier.size(300f, 200f)) {
                LeafLayout(Modifier.size(100f, 100f).zIndex(1f).interaction(states[0]).background(red))
                LeafLayout(Modifier.offset(50f, 0f).size(100f, 100f).interaction(states[1]).background(green))
            }
        }

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(75f, 50f)))
        frame()

        assertTrue(states[0].isHovered, "red is on top")
        assertFalse(states[1].isHovered, "green is under it")
    }

    // --- what does not move ----------------------------------------------------------------

    @Test
    fun `Tab still walks cards in source order when one is lifted`() {
        val nodes = mutableListOf<String>()
        show {
            Row {
                listOf("a", "b", "c").forEachIndexed { index, name ->
                    LeafLayout(
                        Modifier.size(40f, 40f).zIndex(if (index == 0) 9f else 0f).focusable(initial = index == 0),
                        name = name,
                    )
                }
            }
        }
        repeat(3) {
            nodes += focus.focused?.name ?: "none"
            tab()
        }

        assertEquals(listOf("a", "b", "c"), nodes)
    }

    @Test
    fun `a lifted child keeps its place in a row`() {
        show {
            Row {
                LeafLayout(Modifier.size(40f, 20f).zIndex(5f), name = "first")
                LeafLayout(Modifier.size(40f, 20f), name = "second")
            }
        }

        assertEquals(0f, node("first").boundsInRoot.left)
        assertEquals(40f, node("second").boundsInRoot.left)
    }

    private fun node(name: String): UiNode = host.root.firstOrNull { it.name == name }!!

    // --- the node's own bookkeeping --------------------------------------------------------

    @Test
    fun `a pile with no zIndex draws from the children list itself`() {
        val tree = TestTree()
        tree.box("a", 0f, 0f, 10f, 10f)
        tree.box("b", 0f, 0f, 10f, 10f)

        assertSame(tree.root.children, tree.root.drawOrder, "nothing lifted, nothing copied")
    }

    @Test
    fun `the draw order is worked out again when the pile changes`() {
        val tree = TestTree()
        val a = tree.box("a", 0f, 0f, 10f, 10f)
        tree.box("b", 0f, 0f, 10f, 10f)
        assertEquals(listOf("a", "b"), tree.root.drawOrder.map { it.name })

        a.modifier = Modifier.zIndex(1f)
        assertEquals(listOf("b", "a"), tree.root.drawOrder.map { it.name }, "a chain change")

        tree.box("c", 0f, 0f, 10f, 10f, Modifier.zIndex(2f))
        assertEquals(listOf("b", "a", "c"), tree.root.drawOrder.map { it.name }, "an insertion")

        // Source order is now c, a, b. Lifts decide, so the picture does not change.
        tree.root.move(from = 2, to = 0, count = 1)
        assertEquals(listOf("b", "a", "c"), tree.root.drawOrder.map { it.name }, "a move")

        // a comes back down, and the tie between a and b is settled by the new source order.
        a.modifier = Modifier
        assertEquals(listOf("a", "b", "c"), tree.root.drawOrder.map { it.name }, "a lift taken away")

        tree.root.removeAt(2, 1)
        assertEquals(listOf("a", "c"), tree.root.drawOrder.map { it.name }, "a removal")
    }

    // --- driven through uiTest -------------------------------------------------------------

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    /** Renders one frame of [ui] afresh and returns the colours of its rectangles, in paint order. */
    private fun paintOrder(ui: UiTest): List<Colour> {
        val recording = ui.backend.canvas as RecordingCanvas
        recording.clear()
        ui.render()
        return recording.only<DrawCall.Rectangle>().map { it.colour }
    }

    @Test
    fun `a dragged card floats over the hand while held and drops back into source order`() {
        var dragging by mutableStateOf(-1)
        var dragX by mutableStateOf(0f)
        val clicked = mutableListOf<Int>()
        val ui = open {
            Box(Modifier.size(300f, 200f)) {
                listOf(red, green, blue).forEachIndexed { index, colour ->
                    val handler = remember(index) {
                        var grabbedAt = 0f
                        PointerHandler { event ->
                            when (event) {
                                is PointerEvent.Press -> { dragging = index; grabbedAt = event.position.x }
                                // In the card's own coordinates, which move with it, so this is the
                                // distance still to go rather than the distance from the press.
                                is PointerEvent.Move -> if (dragging == index) dragX += event.position.x - grabbedAt
                                is PointerEvent.Release -> { if (dragging == index) clicked += index; dragging = -1 }
                                else -> {}
                            }
                            true
                        }
                    }
                    LeafLayout(
                        Modifier.offset(index * 50f + if (index == 0) dragX else 0f, 0f).size(100f, 100f)
                            .zIndex(if (dragging == index) 1f else 0f)
                            .testTag("card$index")
                            .background(colour)
                            .onPointer(handler),
                    )
                }
            }
        }

        // Grab red by its own corner and carry it onto blue.
        ui.press(Offset(25f, 50f))
        ui.moveTo(Offset(125f, 50f))

        assertEquals(100f, ui.node("card0").boundsInRoot.left, "red followed the mouse")
        assertEquals(listOf(green, blue, red), paintOrder(ui), "held, red is over the card it is dragged onto")

        ui.release()
        assertEquals(listOf(red, green, blue), paintOrder(ui), "let go, it settles back under blue")

        // Where red and blue now sit on each other, blue is on top again and takes the click.
        ui.click(Offset(150f, 50f))
        assertEquals(listOf(0, 2), clicked)
    }

    @Test
    fun `a lifted card that is composed away gives the overlap back and takes it again on return`() {
        var showRed by mutableStateOf(true)
        val clicked = mutableListOf<Int>()
        val ui = open {
            Box(Modifier.size(300f, 200f)) {
                if (showRed) {
                    LeafLayout(Modifier.size(100f, 100f).zIndex(1f).background(red).clickable { clicked += 0 })
                }
                LeafLayout(Modifier.offset(50f, 0f).size(100f, 100f).background(green).clickable { clicked += 1 })
            }
        }
        ui.click(Offset(75f, 50f))
        assertEquals(listOf(green, red), paintOrder(ui))

        showRed = false
        ui.settle()
        ui.click(Offset(75f, 50f))
        assertEquals(listOf(green), paintOrder(ui))

        showRed = true
        ui.settle()
        ui.click(Offset(75f, 50f))
        assertEquals(listOf(green, red), paintOrder(ui))

        assertEquals(listOf(0, 1, 0), clicked)
    }

    @Test
    fun `a lifted card with no area takes no click from the card under it`() {
        val clicked = mutableListOf<String>()
        val ui = open {
            Box(Modifier.size(300f, 200f)) {
                LeafLayout(Modifier.size(100f, 100f).background(red).clickable { clicked += "red" })
                LeafLayout(Modifier.offset(50f, 50f).size(0f, 0f).zIndex(10f).clickable { clicked += "empty" })
            }
        }

        ui.click(Offset(50f, 50f))

        assertEquals(listOf("red"), clicked)
    }

    @Test
    fun `a screen with a lifted card that is standing still does not redraw`() {
        val ui = open { Hand(z = { if (it == 1) 1f else 0f }) }

        assertEquals(listOf(red, blue, green), paintOrder(ui))
        assertFalse(ui.render(), "nothing changed, so nothing to draw")
    }
}
