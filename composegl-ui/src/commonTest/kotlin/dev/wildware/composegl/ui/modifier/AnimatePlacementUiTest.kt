package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyGridState
import dev.wildware.composegl.ui.widget.LazyListState
import dev.wildware.composegl.ui.widget.LazyVerticalGrid
import dev.wildware.composegl.ui.widget.ScrollArea
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Rows that slide to their new place when a list is reordered, driven by clicks, the pad, the
 * wheel and a drag, and judged by where the rows are on screen on the way.
 *
 * Most slides here run on [Clock.World] and that clock is stopped before the thing that moves the
 * rows. A click still settles — it runs on the interface's own clock — but the slide it starts
 * stays exactly where it began, so a test can look at the frame the move happened in and then let
 * time out a frame at a time.
 */
class AnimatePlacementUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 400f), content = content).also { opened += it }

    /** 400ms and straight: a frame is a sixtieth of a second, so twelve frames is half way. */
    private val slow = Tween(durationMillis = 400, easing = Easings.Linear)

    private var order by mutableStateOf(listOf("a", "b", "c"))
    private val clicked = mutableListOf<String>()

    private fun UiTest.top(tag: String) = node(tag).boundsInRoot.top
    private fun UiTest.left(tag: String) = node(tag).boundsInRoot.left
    private fun UiTest.freeze() = host.clocks.stop(Clock.World)
    private fun UiTest.thaw() = host.clocks.start(Clock.World)

    private fun assertNear(expected: Float, actual: Float, because: String, within: Float = 4f) =
        assertTrue(abs(expected - actual) <= within, "$because: expected about $expected, got $actual")

    private fun column(animated: Boolean = true) = open {
        Column {
            Button(
                "REVERSE",
                onClick = { order = order.reversed() },
                initialFocus = true,
                modifier = Modifier.testTag("reverse"),
            )
            Button("DROP A", onClick = { order = order - "a" }, modifier = Modifier.testTag("drop"))
            order.forEach { name ->
                key(name) {
                    Box(
                        Modifier.size(100f, 40f)
                            .then(if (animated) Modifier.animatePlacement(slow, Clock.World) else Modifier)
                            .background(if (name == "a") Colour.Red else Colour.Grey)
                            .clickable { clicked += name }
                            .testTag(name),
                    )
                }
            }
        }
    }

    @Test
    fun `a reordered row starts where it was and slides to its new slot`() {
        val ui = column()
        val topA = ui.top("a")
        val topC = ui.top("c")
        ui.freeze()

        ui.pad(GamepadButton.South)

        assertEquals(topA, ui.top("a"), "the frame it moved, it is still drawn where it was")
        assertEquals(topC, ui.top("c"))

        ui.thaw()
        repeat(12) { ui.render() }
        assertNear((topA + topC) / 2f, ui.top("a"), "half way through, half way down")
        assertNear((topA + topC) / 2f, ui.top("c"), "and the one coming up the other way")

        ui.settle()
        assertEquals(topC, ui.top("a"), "it lands exactly on its new slot")
        assertEquals(topA, ui.top("c"))
    }

    @Test
    fun `without it a reordered row jumps`() {
        val ui = column(animated = false)
        val topA = ui.top("a")
        val topC = ui.top("c")
        ui.freeze()

        ui.pad(GamepadButton.South)

        assertEquals(topC, ui.top("a"))
        assertEquals(topA, ui.top("c"))
    }

    @Test
    fun `a sliding row is clicked where it is seen`() {
        val ui = column()
        val topA = ui.top("a")
        ui.freeze()
        ui.pad(GamepadButton.South)

        // Where a was drawn, which is also where c's slot is now.
        ui.click(Offset(50f, topA + 20f))

        assertEquals(listOf("a"), clicked)
    }

    @Test
    fun `the slide is what is drawn`() {
        val ui = column()
        val topA = ui.top("a")
        ui.freeze()
        ui.pad(GamepadButton.South)

        ui.render()

        val red = (ui.backend.canvas as RecordingCanvas).calls
            .filterIsInstance<DrawCall.Rectangle>()
            .single { it.colour == Colour.Red }
        assertEquals(topA, red.rect.top, "painted where it was, not in its new slot")
    }

    @Test
    fun `a move in the middle of a slide carries on from where it had got to`() {
        val ui = column()
        val topA = ui.top("a")
        ui.freeze()
        ui.pad(GamepadButton.South)
        ui.thaw()
        repeat(12) { ui.render() }
        val midway = ui.top("a")
        ui.freeze()

        ui.pad(GamepadButton.South)

        assertEquals(midway, ui.top("a"), "turning round does not jump")
        ui.thaw()
        ui.settle()
        assertEquals(topA, ui.top("a"))
    }

    @Test
    fun `the harness waits for a slide on the interface clock to land`() {
        var names by mutableStateOf(listOf("x", "y"))
        val ui = open {
            Column {
                Button("SWAP", onClick = { names = names.reversed() }, modifier = Modifier.testTag("swap"))
                names.forEach { name ->
                    key(name) { Box(Modifier.size(100f, 40f).animatePlacement().testTag(name)) }
                }
            }
        }
        val topX = ui.top("x")
        val topY = ui.top("y")

        ui.click("swap")

        assertEquals(topY, ui.top("x"), "the default spring arrives exactly")
        assertEquals(topX, ui.top("y"))
        assertFalse(ui.render(), "a landed slide leaves a still screen costing nothing")
    }

    @Test
    fun `a row added above appears where it lands and pushes the rest down smoothly`() {
        var names by mutableStateOf(listOf("b", "c"))
        val ui = open {
            Column {
                Button("ADD", onClick = { names = listOf("new") + names }, modifier = Modifier.testTag("add"))
                names.forEach { name ->
                    key(name) { Box(Modifier.size(100f, 40f).animatePlacement(slow, Clock.World).testTag(name)) }
                }
            }
        }
        val topB = ui.top("b")
        ui.freeze()

        ui.click("add")

        assertEquals(topB, ui.top("new"), "a first place is not a move")
        assertEquals(topB, ui.top("b"), "b has not been pushed yet")
        ui.thaw()
        ui.settle()
        assertEquals(topB + 40f, ui.top("b"))
    }

    @Test
    fun `a row removed while it slides does not keep the screen waiting`() {
        val ui = column()
        ui.freeze()
        ui.pad(GamepadButton.South)
        ui.thaw()
        repeat(6) { ui.render() }

        ui.click("drop")

        ui.assertDoesNotExist("a")
        assertFalse(ui.host.clocks.isAnimating, "nothing is left playing for a row that is gone")
    }

    @Test
    fun `an animated cell inside an animated row moves with the row and does not slide twice`() {
        val ui = open {
            Column {
                Button("REVERSE", onClick = { order = order.reversed() }, modifier = Modifier.testTag("reverse"))
                order.forEach { name ->
                    key(name) {
                        Box(Modifier.size(100f, 40f).animatePlacement(slow, Clock.World).testTag(name)) {
                            Box(Modifier.offset(10f, 5f).size(20f, 20f).animatePlacement(slow, Clock.World).testTag("$name.cell"))
                        }
                    }
                }
            }
        }
        ui.freeze()
        ui.click("reverse")
        ui.thaw()

        repeat(24) {
            ui.render()
            // Near rather than equal: two positions summed up the tree differ in the last bit.
            assertNear(5f, ui.top("a.cell") - ui.top("a"), "the cell stays in its row", within = 0.01f)
            assertNear(10f, ui.left("a.cell") - ui.left("a"), "and in its column", within = 0.01f)
        }
    }

    @Test
    fun `scrolling a lazy list is not a move`() {
        val state = LazyListState()
        val ui = open {
            LazyColumn(count = 30, key = { "item$it" }, state = state, modifier = Modifier.size(200f, 300f).testTag("list")) {
                Box(Modifier.size(180f, 40f).animatePlacement(slow, Clock.World).testTag("item$it"))
            }
        }
        val listTop = ui.top("list")
        ui.freeze()

        ui.scroll("list", Offset(0f, 3f))

        assertTrue(state.position > 0f, "the wheel scrolled the list")
        assertEquals(listTop + 3 * 40f - state.position, ui.top("item3"), "the row moved with the scroll, at once")
    }

    @Test
    fun `scrolling a lazy grid is not a move`() {
        val state = LazyGridState()
        val ui = open {
            LazyVerticalGrid(
                count = 60,
                columns = GridCells.Fixed(2),
                key = { "cell$it" },
                state = state,
                modifier = Modifier.size(200f, 300f).testTag("grid"),
            ) {
                Box(Modifier.size(90f, 40f).animatePlacement(slow, Clock.World).testTag("cell$it"))
            }
        }
        val gridTop = ui.top("grid")
        ui.freeze()

        ui.scroll("grid", Offset(0f, 3f))

        assertTrue(state.position > 0f, "the wheel scrolled the grid")
        assertEquals(gridTop + 3 * 40f - state.position, ui.top("cell7"), "the cell moved with the scroll, at once")
    }

    @Test
    fun `sorting a keyed lazy list slides its rows`() {
        var items by mutableStateOf((0 until 10).map { "item$it" })
        val ui = open {
            Column {
                Button(
                    "SWAP",
                    onClick = { items = listOf(items[1], items[0]) + items.drop(2) },
                    initialFocus = true,
                    modifier = Modifier.testTag("swap"),
                )
                LazyColumn(count = items.size, key = { items[it] }, modifier = Modifier.size(200f, 300f)) { index ->
                    Box(Modifier.size(180f, 40f).animatePlacement(slow, Clock.World).testTag(items[index]))
                }
            }
        }
        val top0 = ui.top("item0")
        val top1 = ui.top("item1")
        ui.freeze()

        ui.pad(GamepadButton.South)

        assertEquals(top0, ui.top("item0"), "still where it was the frame the list was sorted")
        ui.thaw()
        repeat(12) { ui.render() }
        assertNear((top0 + top1) / 2f, ui.top("item0"), "half way")
        ui.settle()
        assertEquals(top1, ui.top("item0"))
        assertEquals(top0, ui.top("item1"))
    }

    @Test
    fun `scrolling a scroll area is not a move`() {
        val ui = open {
            ScrollArea(Modifier.size(200f, 200f).testTag("area")) {
                Column {
                    repeat(20) { Box(Modifier.size(180f, 40f).animatePlacement(slow, Clock.World).testTag("row$it")) }
                }
            }
        }
        val before = ui.top("row2")
        ui.freeze()

        ui.scroll("area", Offset(0f, 3f))

        assertNotEquals(before, ui.top("row2"), "the row went with the scroll rather than being held back")
        assertEquals(40f, ui.top("row3") - ui.top("row2"))
    }

    private var panelAt by mutableStateOf(Offset(20f, 20f))

    private fun panel(framed: Boolean) = open {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.offset(panelAt.x, panelAt.y).size(200f, 200f)
                    .then(if (framed) Modifier.placementFrame() else Modifier)
                    .draggable { panelAt += it }
                    .testTag("panel"),
            ) {
                Box(Modifier.offset(0f, 150f).size(100f, 40f).animatePlacement(slow, Clock.World).testTag("row"))
            }
        }
    }

    @Test
    fun `a dragged panel marked as a frame carries its rows with it`() {
        val ui = panel(framed = true)
        ui.freeze()

        ui.press(Offset(100f, 100f))
        ui.moveTo(Offset(140f, 110f))
        ui.moveTo(Offset(180f, 130f))
        ui.release()

        assertEquals(ui.left("panel"), ui.left("row"))
        assertEquals(ui.top("panel") + 150f, ui.top("row"))
        assertTrue(ui.left("panel") > 20f, "the panel was dragged")
    }

    @Test
    fun `a dragged panel that is not a frame leaves its rows trailing`() {
        val ui = panel(framed = false)
        ui.freeze()

        ui.press(Offset(100f, 100f))
        ui.moveTo(Offset(180f, 130f))
        ui.release()

        assertEquals(20f, ui.left("row"), "measured against the screen, the row is still where it was")
        ui.thaw()
        ui.settle()
        assertEquals(ui.left("panel"), ui.left("row"))
    }

    @Test
    fun `a slide on the world clock waits while the game is paused`() {
        val ui = column()
        val topA = ui.top("a")
        ui.freeze()
        ui.pad(GamepadButton.South)

        ui.advanceBy(2_000)

        assertEquals(topA, ui.top("a"), "two seconds of a paused game is no time at all")
    }

    @Test
    fun `a row taken out of the middle lets the rows below slide up`() {
        order = listOf("b", "a", "c")
        val ui = column()
        val topB = ui.top("b")
        val topC = ui.top("c")
        ui.freeze()

        ui.click("drop")

        ui.assertDoesNotExist("a")
        assertEquals(topC, ui.top("c"), "the frame a went, c is still below the gap")
        ui.thaw()
        repeat(12) { ui.render() }
        assertNear((topB + 40f + topC) / 2f, ui.top("c"), "half way up into the gap")
        ui.settle()
        assertEquals(topB + 40f, ui.top("c"), "it closes the gap exactly")
    }

    @Test
    fun `taking the modifier off in the middle of a slide lands the row at once`() {
        var animated by mutableStateOf(true)
        val ui = open {
            Column {
                Button("REVERSE", onClick = { order = order.reversed() }, modifier = Modifier.testTag("reverse"))
                Button("STILL", onClick = { animated = false }, modifier = Modifier.testTag("still"))
                order.forEach { name ->
                    key(name) {
                        Box(
                            Modifier.size(100f, 40f)
                                .then(if (animated) Modifier.animatePlacement(slow, Clock.World) else Modifier)
                                .testTag(name),
                        )
                    }
                }
            }
        }
        val topA = ui.top("a")
        val topC = ui.top("c")
        ui.freeze()
        ui.click("reverse")
        assertEquals(topA, ui.top("a"), "sliding")

        ui.click("still")

        assertEquals(topC, ui.top("a"), "without the modifier it sits in its slot")
        ui.thaw()
        ui.render()
        assertFalse(ui.host.clocks.isAnimating, "and nothing is left playing for it")
    }

    @Test
    fun `recomposing a sliding row does not restart or drop its slide`() {
        var tick by mutableStateOf(0)
        val ui = open {
            Column {
                Button("REVERSE", onClick = { order = order.reversed() }, modifier = Modifier.testTag("reverse"))
                Button("TICK", onClick = { tick++ }, modifier = Modifier.testTag("tick"))
                order.forEach { name ->
                    key(name) {
                        // Read here so every row recomposes, handed a fresh spec each time.
                        val width = 100f + 0f * tick
                        Box(Modifier.size(width, 40f).animatePlacement(Tween(durationMillis = 400, easing = Easings.Linear), Clock.World).testTag(name))
                    }
                }
            }
        }
        val topA = ui.top("a")
        val topC = ui.top("c")
        ui.freeze()
        ui.click("reverse")
        ui.thaw()
        repeat(12) { ui.render() }
        val midway = ui.top("a")
        assertNear((topA + topC) / 2f, midway, "it is half way before anything recomposes")
        ui.freeze()

        ui.click("tick")

        assertEquals(1, tick)
        assertEquals(midway, ui.top("a"), "a recomposition is not a move, and does not forget the slide")
        ui.thaw()
        ui.settle()
        assertEquals(topC, ui.top("a"))
        assertFalse(ui.render(), "once it has landed the screen is still")
    }

    @Test
    fun `a sliding row is hovered where it is seen`() {
        val ui = open {
            Column {
                Button("REVERSE", onClick = { order = order.reversed() }, modifier = Modifier.testTag("reverse"))
                order.forEach { name ->
                    key(name) {
                        val cursor = if (name == "a") Modifier.pointerHoverIcon(PointerIcon.Text) else Modifier
                        Box(Modifier.size(100f, 40f).animatePlacement(slow, Clock.World).then(cursor).testTag(name))
                    }
                }
            }
        }
        val topA = ui.top("a")
        ui.freeze()
        ui.click("reverse")

        // Where a is drawn, which is c's slot now.
        ui.moveTo(Offset(50f, topA + 20f))

        assertEquals(PointerIcon.Text, ui.pointerIcon, "the mouse is over a, not over c's new slot")
    }

    @Test
    fun `the pad moves focus to the row drawn below rather than the one whose slot is there`() {
        val ui = open {
            Column {
                Button(
                    "REVERSE",
                    onClick = { order = order.reversed() },
                    initialFocus = true,
                    modifier = Modifier.testTag("reverse"),
                )
                order.forEach { name ->
                    key(name) {
                        Box(Modifier.size(100f, 40f).animatePlacement(slow, Clock.World).focusable().testTag(name))
                    }
                }
            }
        }
        ui.freeze()
        ui.pad(GamepadButton.South)

        ui.pad(GamepadButton.DpadDown)

        ui.assertFocused("a")
    }

    @Test
    fun `a safe area arriving is not a move`() {
        val ui = column()
        val topA = ui.top("a")
        ui.freeze()

        // A phone turned on its side: the whole screen is laid out 30 further down.
        val inset = Viewport(ui.size, ui.size, safeArea = Padding(top = 30f))
        ui.host.settle(inset, ui.focus, nanos = ui.nanos + 16_666_667L)
        ui.host.settle(inset, ui.focus, nanos = ui.nanos + 33_333_334L)

        assertEquals(topA + 30f, ui.top("a"), "everything went down together, at once")
        assertFalse(ui.host.clocks.isAnimating, "and nothing started sliding")
    }

    @Test
    fun `a node with no size still slides`() {
        var names by mutableStateOf(listOf("wide", "empty"))
        val ui = open {
            Column {
                Button("SWAP", onClick = { names = names.reversed() }, modifier = Modifier.testTag("swap"))
                names.forEach { name ->
                    key(name) {
                        val size = if (name == "empty") Modifier.size(0f, 0f) else Modifier.size(100f, 40f)
                        Box(size.animatePlacement(slow, Clock.World).testTag(name))
                    }
                }
            }
        }
        val topWide = ui.top("wide")
        val topEmpty = ui.top("empty")
        ui.freeze()

        ui.click("swap")

        assertEquals(topEmpty, ui.top("empty"), "held where it was")
        ui.thaw()
        ui.settle()
        assertEquals(topWide, ui.top("empty"))
        assertEquals(topWide, ui.top("wide"), "the empty one takes no room above it")
    }
}
