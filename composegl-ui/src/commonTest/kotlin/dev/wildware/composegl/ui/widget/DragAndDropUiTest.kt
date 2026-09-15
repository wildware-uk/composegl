package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An inventory, composed for real and played with a mouse, a pad and a keyboard: an item picked up
 * out of one slot, carried over the others and put down, judged by what the slots show afterwards
 * and by where the carried picture is drawn on the way.
 */
class DragAndDropUiTest {

    private data class Item(val name: String, val kind: String)

    private val sword = Item("sword", "weapon")
    private val shield = Item("shield", "armour")

    private val opened = mutableListOf<UiTest>()

    /** What each source heard when its drag ended, in order: "dropped" or "kept". */
    private val ends = mutableListOf<String>()

    /** Everything the screen's own Back did, which a carried item should get to first. */
    private var screenBacks = 0

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(500f, 300f), onBack = { screenBacks++ }, content = content).also { opened += it }

    /**
     * Four slots in a row: the sword, the shield, an empty one and one that only takes armour.
     *
     * A slot reads out its item and its state: `+` while something it takes is over it, `x` while
     * something it refuses is, and `?` while something it would take is being carried anywhere.
     * Lifted with a zIndex, so a picture drawn over it is drawn over a lifted thing.
     */
    @Composable
    private fun Inventory() {
        val slots = remember { mutableStateListOf<Item?>(sword, shield, null, null) }
        DragAndDropHost {
            Row(Modifier.offset(20f, 100f).zIndex(5f), horizontalArrangement = Arrangement.spacedBy(20f)) {
                Slot(0, slots)
                Slot(1, slots)
                Slot(2, slots)
                Slot(3, slots, only = "armour")
            }
        }
    }

    @Composable
    private fun Slot(index: Int, slots: SnapshotStateList<Item?>, only: String? = null) {
        val state = remember { DropTargetState() }
        val item = slots[index]
        var modifier = Modifier.testTag("slot$index").size(60f, 60f)
        if (item != null) {
            modifier = modifier.dragSource(
                payload = item,
                onDragEnd = { dropped -> ends += if (dropped) "dropped" else "kept" },
            ) { Text(item.name, Modifier.testTag("picture")) }
        }
        modifier = modifier.dropTarget<Item>(
            state = state,
            accepts = { only == null || it.kind == only },
            onDrop = { moved ->
                val from = slots.indexOf(moved)
                slots[from] = slots[index]
                slots[index] = moved
            },
        )
        val look = when {
            state.isHovered -> "+"
            state.isRefusing -> "x"
            state.isOffered -> "?"
            else -> ""
        }
        Box(modifier) { Text((item?.name ?: "-") + look, Modifier.testTag("label$index")) }
    }

    private fun UiTest.centreOf(tag: String) = node(tag).boundsInRoot.centre

    private fun UiTest.assertSlots(vararg expected: String) =
        expected.forEachIndexed { index, text -> assertText("label$index", text) }

    /** Every run of text on the screen, in the order it is drawn. */
    private fun UiTest.drawnTexts(): List<String> {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        DrawPass(canvas).draw(root)
        return canvas.texts()
    }

    // --- a mouse -------------------------------------------------------------------------------

    @Test
    fun `an item dragged onto an empty slot moves there`() {
        val ui = open { Inventory() }
        ui.assertSlots("sword", "shield", "-", "-")

        ui.press("slot0")
        ui.moveTo(ui.centreOf("slot0") + Offset(40f, 0f))
        ui.moveTo(ui.centreOf("slot2"))
        ui.release()

        ui.assertSlots("-", "shield", "sword", "-")
        assertEquals(listOf("dropped"), ends)
        ui.assertDoesNotExist("picture")
    }

    @Test
    fun `dropping onto a full slot swaps the two`() {
        val ui = open { Inventory() }

        ui.press("slot0")
        ui.moveTo(ui.centreOf("slot1"))
        ui.release()

        ui.assertSlots("shield", "sword", "-", "-")
    }

    @Test
    fun `the picture is held where it was grabbed and drawn over everything`() {
        val ui = open { Inventory() }
        val slot = ui.node("slot0").boundsInRoot
        val grab = Offset(12f, 20f)

        ui.press(slot.topLeft + grab)
        ui.moveTo(Offset(300f, 40f))

        assertEquals(Offset(300f, 40f) - grab, ui.node("picture").boundsInRoot.topLeft)
        assertEquals("sword", ui.drawnTexts().last(), "drawn after the lifted row, so over it")

        ui.moveTo(Offset(410f, 230f))
        assertEquals(Offset(410f, 230f) - grab, ui.node("picture").boundsInRoot.topLeft, "and it followed")
    }

    @Test
    fun `slots light up for what they would take as it passes over them`() {
        val ui = open { Inventory() }

        ui.press("slot0")
        ui.moveTo(Offset(250f, 250f))
        // A sword could go back where it was, or anywhere but the armour slot.
        ui.assertSlots("sword?", "shield?", "-?", "-")

        ui.moveTo(ui.centreOf("slot2"))
        ui.assertSlots("sword?", "shield?", "-+", "-")

        ui.moveTo(ui.centreOf("slot3"))
        ui.assertSlots("sword?", "shield?", "-?", "-x")

        ui.moveTo(Offset(250f, 250f))
        ui.assertSlots("sword?", "shield?", "-?", "-")

        ui.release()
        ui.assertSlots("sword", "shield", "-", "-")
    }

    @Test
    fun `a slot that refuses the item leaves it where it was`() {
        val ui = open { Inventory() }

        ui.press("slot0")
        ui.moveTo(ui.centreOf("slot3"))
        ui.release()

        ui.assertSlots("sword", "shield", "-", "-")
        assertEquals(listOf("kept"), ends)
    }

    @Test
    fun `a slot that takes armour takes the shield`() {
        val ui = open { Inventory() }

        ui.press("slot1")
        ui.moveTo(ui.centreOf("slot3"))
        ui.assertText("label3", "-+")
        ui.release()

        ui.assertSlots("sword", "-", "-", "shield")
    }

    @Test
    fun `let go over nothing and the item stays and the picture goes`() {
        val ui = open { Inventory() }

        ui.press("slot0")
        ui.moveTo(Offset(250f, 20f))
        ui.assertExists("picture")
        ui.release()

        ui.assertSlots("sword", "shield", "-", "-")
        ui.assertDoesNotExist("picture")
        assertEquals(listOf("kept"), ends)
    }

    @Test
    fun `a wobbly press never picks anything up`() {
        val ui = open { Inventory() }

        ui.press("slot0")
        ui.moveTo(ui.centreOf("slot0") + Offset(3f, 2f))
        ui.assertDoesNotExist("picture")
        ui.release()

        ui.assertSlots("sword", "shield", "-", "-")
        assertTrue(ends.isEmpty())
    }

    @Test
    fun `Escape during a mouse drag puts the item back and the release drops nothing`() {
        val ui = open { Inventory() }

        ui.press("slot0")
        ui.moveTo(ui.centreOf("slot2"))
        ui.key(Key.Escape)

        ui.assertDoesNotExist("picture")
        ui.assertSlots("sword", "shield", "-", "-")
        assertEquals(0, screenBacks, "the carried item took the Escape, not the screen")

        ui.moveTo(ui.centreOf("slot2") + Offset(2f, 0f))
        ui.release()
        ui.assertSlots("sword", "shield", "-", "-")
        assertEquals(listOf("kept"), ends)
    }

    // --- a pad ---------------------------------------------------------------------------------

    @Test
    fun `a pad picks up with South carries with the d-pad and drops with South`() {
        val ui = open { Inventory() }
        ui.assertFocused("slot0")

        ui.pad(GamepadButton.South)
        assertEquals(ui.node("slot0").boundsInRoot.topLeft + Offset(16f, -16f), ui.node("picture").boundsInRoot.topLeft)
        ui.assertSlots("sword+", "shield?", "-?", "-")

        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("slot2")
        assertEquals(
            ui.node("slot2").boundsInRoot.topLeft + Offset(16f, -16f),
            ui.node("picture").boundsInRoot.topLeft,
            "the picture went with focus",
        )
        ui.assertText("label2", "-+")

        ui.pad(GamepadButton.South)
        ui.assertSlots("-", "shield", "sword", "-")
        ui.assertDoesNotExist("picture")
        assertEquals(listOf("dropped"), ends)
    }

    @Test
    fun `East puts a carried item back without closing the screen`() {
        val ui = open { Inventory() }

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.East)

        ui.assertDoesNotExist("picture")
        ui.assertSlots("sword", "shield", "-", "-")
        assertEquals(0, screenBacks)
        assertEquals(listOf("kept"), ends)

        ui.pad(GamepadButton.East)
        assertEquals(1, screenBacks, "with nothing carried, Back is the screen's again")
    }

    @Test
    fun `South on a slot that refuses keeps the item in hand`() {
        val ui = open { Inventory() }

        ui.pad(GamepadButton.South)
        repeat(3) { ui.pad(GamepadButton.DpadRight) }
        ui.assertFocused("slot3")
        ui.assertText("label3", "-x")

        ui.pad(GamepadButton.South)
        ui.assertExists("picture")
        ui.assertSlots("sword?", "shield?", "-?", "-x")

        ui.pad(GamepadButton.DpadLeft)
        ui.pad(GamepadButton.South)
        ui.assertSlots("-", "shield", "sword", "-")
    }

    @Test
    fun `the keyboard carries an item with Enter and the arrows`() {
        val ui = open { Inventory() }

        ui.key(Key.Enter)
        ui.assertExists("picture")
        ui.key(Key.Right)
        ui.key(Key.Enter)

        ui.assertSlots("shield", "sword", "-", "-")
    }

    // --- targets -------------------------------------------------------------------------------

    @Test
    fun `a target for another type refuses without being asked`() {
        var asked = 0
        val ui = open {
            DragAndDropHost {
                Row(horizontalArrangement = Arrangement.spacedBy(40f)) {
                    Box(Modifier.testTag("from").size(50f, 50f).dragSource(payload = sword) { Text("carried", Modifier.testTag("picture")) })
                    val state = remember { DropTargetState() }
                    Box(
                        Modifier.testTag("words").size(50f, 50f).dropTarget<String>(
                            state = state,
                            accepts = { asked++; true },
                            onDrop = { error("a sword is not a string") },
                        ),
                    ) { Text(if (state.isRefusing) "no" else "", Modifier.testTag("verdict")) }
                }
            }
        }

        ui.press("from")
        ui.moveTo(ui.centreOf("words"))
        ui.assertText("verdict", "no")
        ui.release()

        assertEquals(0, asked)
    }

    @Test
    fun `where two targets overlap the one on top takes the drop`() {
        val dropped = mutableListOf<String>()
        val ui = open {
            DragAndDropHost {
                Box(Modifier.size(400f, 200f)) {
                    Box(Modifier.testTag("from").size(40f, 40f).dragSource(payload = sword) { Text("carried") })
                    Box(Modifier.offset(100f, 0f).size(100f, 100f).zIndex(1f).dropTarget<Item> { dropped += "top" })
                    Box(Modifier.testTag("under").offset(80f, 0f).size(100f, 100f).dropTarget<Item> { dropped += "under" })
                }
            }
        }

        ui.press("from")
        ui.moveTo(Offset(150f, 50f))
        ui.release()
        assertEquals(listOf("top"), dropped, "drawn on top by its zIndex, though written first")

        ui.press("from")
        ui.moveTo(Offset(90f, 50f))
        ui.release()
        assertEquals(listOf("top", "under"), dropped, "and where only the lower one is, the lower one")
    }

    // --- the screen around the slots ----------------------------------------------------------

    @Test
    fun `the picture follows focus onto a button that is not a slot and back`() {
        var sorted = 0
        val ui = open {
            val slots = remember { mutableStateListOf<Item?>(sword, shield, null, null) }
            DragAndDropHost {
                Column(Modifier.offset(20f, 100f), verticalArrangement = Arrangement.spacedBy(20f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                        Slot(0, slots)
                        Slot(1, slots)
                    }
                    Box(Modifier.testTag("sort").size(140f, 30f).focusable().clickable { sorted++ })
                }
            }
        }

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("sort")
        assertEquals(
            ui.node("sort").boundsInRoot.topLeft + Offset(16f, -16f),
            ui.node("picture").boundsInRoot.topLeft,
            "over the button focus is on, not left behind on the slot",
        )
        ui.assertText("label0", "sword?")

        ui.pad(GamepadButton.South)
        assertEquals(1, sorted, "a button that is not a slot is still clicked")
        ui.assertExists("picture")

        ui.pad(GamepadButton.DpadUp)
        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("slot1")
        ui.assertText("label1", "shield+")
        ui.pad(GamepadButton.South)
        ui.assertSlots("shield", "sword")
    }

    @Test
    fun `a locked slot that takes nothing still gives its item to a pad`() {
        val ui = open {
            val slots = remember { mutableStateListOf<Item?>(sword, null) }
            DragAndDropHost {
                Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                    Box(
                        Modifier.testTag("slot0").size(60f, 60f)
                            .dragSource(payload = sword) { Text("sword", Modifier.testTag("picture")) }
                            .dropTarget<Item>(enabled = false) { error("locked") },
                    )
                    Slot(1, slots)
                }
            }
        }

        ui.assertFocused("slot0")
        ui.pad(GamepadButton.South)
        ui.assertExists("picture")
    }

    @Test
    fun `a slot that is both source and target tells the state it was handed about focus`() {
        val mine = InteractionState()
        val ui = open {
            DragAndDropHost {
                Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                    Box(Modifier.testTag("other").size(60f, 60f).focusable())
                    Box(
                        Modifier.testTag("slot").size(60f, 60f)
                            .dragSource(payload = sword, interaction = mine) { Text("sword") }
                            .dropTarget<Item> { },
                    )
                }
            }
        }

        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("slot")
        assertTrue(mine.isFocused, "the widget draws its focus ring from this")
    }

    @Test
    fun `a slot turned off while it is lit goes dark`() {
        var open by mutableStateOf(true)
        val ui = open {
            DragAndDropHost {
                Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                    Box(Modifier.testTag("from").size(60f, 60f).dragSource(payload = sword) { Text("carried") })
                    val state = remember { DropTargetState() }
                    Box(Modifier.testTag("to").size(60f, 60f).dropTarget<Item>(state = state, enabled = open) { error("closed") }) {
                        Text(state.toString(), Modifier.testTag("look"))
                    }
                }
            }
        }

        ui.press("from")
        ui.moveTo(ui.centreOf("to"))
        ui.assertText("look", "DropTargetState(hovered=true, refusing=false, offered=true)")

        open = false
        ui.advanceBy(16L)
        ui.assertText("look", "DropTargetState(hovered=false, refusing=false, offered=false)")
        ui.release()
    }

    @Test
    fun `a slot that leaves the screen mid drag drops nothing and the picture goes`() {
        var shown by mutableStateOf(true)
        val dropped = mutableListOf<Item>()
        val ui = open {
            DragAndDropHost {
                Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                    Box(Modifier.testTag("from").size(60f, 60f).dragSource(payload = sword, onDragEnd = { ends += if (it) "dropped" else "kept" }) {
                        Text("carried", Modifier.testTag("picture"))
                    })
                    if (shown) Box(Modifier.testTag("to").size(60f, 60f).dropTarget<Item> { dropped += it })
                }
            }
        }

        ui.press("from")
        ui.moveTo(ui.centreOf("to"))
        shown = false
        ui.advanceBy(16L)
        ui.release()

        assertTrue(dropped.isEmpty(), "a target that is gone takes nothing")
        assertEquals(listOf("kept"), ends)
        ui.assertDoesNotExist("picture")
    }

    @Test
    fun `the slot an item came out of leaving mid drag puts the drag down`() {
        var shown by mutableStateOf(true)
        val dropped = mutableListOf<Item>()
        val ui = open {
            DragAndDropHost {
                Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                    if (shown) {
                        Box(Modifier.testTag("from").size(60f, 60f).dragSource(payload = sword, onDragEnd = { ends += if (it) "dropped" else "kept" }) {
                            Text("carried", Modifier.testTag("picture"))
                        })
                    }
                    Box(Modifier.testTag("to").size(60f, 60f).dropTarget<Item> { dropped += it })
                }
            }
        }

        ui.press("from")
        ui.moveTo(ui.centreOf("to"))
        ui.assertExists("picture")
        shown = false
        ui.advanceBy(16L)

        ui.assertDoesNotExist("picture")
        assertEquals(listOf("kept"), ends)
        ui.release()
        assertTrue(dropped.isEmpty(), "what it was carrying has nowhere to come from")
    }

    @Test
    fun `with slots drawn twice as big the picture stays under the pointer and lands where it is let go`() {
        val ui = open {
            val slots = remember { mutableStateListOf<Item?>(sword, null, null, null) }
            // The slots are scaled and the host is not, so a grab in a slot's units is twice as far
            // in the host's.
            DragAndDropHost {
                Row(Modifier.scale(2f, Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(10f)) {
                    Slot(0, slots)
                    Slot(1, slots)
                    Slot(2, slots)
                }
            }
        }
        val slot = ui.node("slot0").boundsInRoot
        val grab = Offset(20f, 30f)

        ui.press(slot.topLeft + grab)
        ui.moveTo(Offset(300f, 60f))
        assertEquals(Offset(300f, 60f) - grab, ui.node("picture").boundsInRoot.topLeft, "grabbed at the same spot on screen")

        ui.moveTo(ui.centreOf("slot2"))
        ui.assertText("label2", "-+")
        ui.release()

        ui.assertSlots("-", "-", "sword")
    }

    @Test
    fun `a target scrolled out of sight is not dropped on`() {
        val dropped = mutableListOf<String>()
        val ui = open {
            DragAndDropHost {
                Column(verticalArrangement = Arrangement.spacedBy(20f)) {
                    Box(Modifier.testTag("from").size(40f, 40f).dragSource(payload = sword) { Text("carried") })
                    ScrollArea(Modifier.size(200f, 60f), state = rememberScrollState(initialY = 100f), bars = false) {
                        Column {
                            Box(Modifier.testTag("hidden").size(200f, 60f).dropTarget<Item> { dropped += "hidden" })
                            Box(Modifier.size(200f, 400f))
                        }
                    }
                }
            }
        }
        // Where the hidden slot would be if the list were not scrolled: inside the list's box, but
        // the list shows something else there.
        val listTop = ui.node("from").boundsInRoot.bottom + 20f

        ui.press("from")
        ui.moveTo(Offset(100f, listTop - 60f))
        ui.release()
        assertTrue(dropped.isEmpty(), "above the list, and the slot is scrolled up out of it")
    }

    @Test
    fun `the picture follows focus into a list that scrolls to show a button`() {
        val scroll = mutableListOf<ScrollState>()
        val ui = open {
            val slots = remember { mutableStateListOf<Item?>(sword, null, null, null) }
            val state = rememberScrollState().also { if (scroll.isEmpty()) scroll += it }
            DragAndDropHost {
                Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                    Slot(0, slots)
                    ScrollArea(Modifier.size(200f, 100f), state = state, bars = false) {
                        Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                            repeat(6) { Box(Modifier.testTag("row$it").size(200f, 40f).focusable().clickable { }) }
                        }
                    }
                }
            }
        }

        ui.pad(GamepadButton.South)
        repeat(5) { ui.pad(GamepadButton.DpadDown) }
        ui.assertFocused("row4")
        assertTrue(scroll.single().y > 0f, "the list scrolled to show it")

        assertEquals(
            ui.node("row4").boundsInRoot.topLeft + Offset(16f, -16f),
            ui.node("picture").boundsInRoot.topLeft,
            "over the button where it is drawn now, not where it was before the list moved",
        )
    }

    @Test
    fun `an item held still by a pad does not redraw the screen`() {
        val ui = open { Inventory() }
        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadRight)
        ui.assertExists("picture")
        ui.render()
        val changed = ui.host.changedFrames

        ui.advanceBy(200L)

        assertEquals(changed, ui.host.changedFrames, "the picture is where it was, so nothing changed")
        assertFalse(ui.render())
    }

    @Test
    fun `a still inventory that recomposes does not redraw`() {
        var tick by mutableStateOf(0)
        var recomposed = 0
        val ui = open {
            DragAndDropHost {
                // Read here, so bumping it writes both slots' chains afresh.
                tick.let { recomposed++ }
                val state = remember { DropTargetState() }
                Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
                    Box(Modifier.size(60f, 60f).dragSource(payload = sword) { Text("sword") }.dropTarget<Item>(state = state) { })
                    Box(Modifier.size(60f, 60f).dropTarget<Item>(state = state) { })
                }
            }
        }
        ui.render()
        val before = recomposed
        val changed = ui.host.changedFrames

        tick++
        ui.advanceBy(50L)

        assertTrue(recomposed > before, "the scope really did recompose")
        assertEquals(changed, ui.host.changedFrames, "equal slots are not a change")
        assertFalse(ui.render())
    }
}
