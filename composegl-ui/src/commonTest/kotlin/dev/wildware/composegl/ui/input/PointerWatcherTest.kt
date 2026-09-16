package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.resolve
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.watchPointer
import dev.wildware.composegl.ui.widget.ProvideFonts
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A layer that watches the pointer without standing in front of what is under it.
 *
 * The thing every test here is about is the difference between `watchPointer` and `onPointer` on a
 * full-screen overlay: both are told where the mouse went, and only one of them takes the hover
 * away from the slot underneath. A card that hangs beside the cursor needs the first one, and
 * getting it wrong is invisible until a player tries to hover something.
 */
class PointerWatcherTest {

    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private val backend = HeadlessBackend()
    private val pointer = PointerRouter(host.root, focus, backend.cursor)
    private var clock = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(backend.fonts) { content() } }
        settle()
    }

    private fun settle() {
        repeat(8) {
            clock += 16_666_667L
            if (!host.settle(Constraints.atMost(400f, 400f), focus, nanos = clock)) return
        }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    private fun move(x: Float, y: Float) = pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y)))

    private fun down(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y), PointerButton.Primary))

    private fun up(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y), PointerButton.Primary))

    private fun exit(x: Float, y: Float) = pointer.onPointer(PointerEvent.Exit(PointerId.Mouse, Offset(x, y)))

    private fun press(x: Float, y: Float) {
        down(x, y)
        up(x, y)
    }

    /** A slot with a full-screen layer over it, watching or handling, as a game would build it. */
    private fun screen(
        slot: InteractionState,
        seen: MutableList<Offset>,
        handling: Boolean = false,
    ): @Composable () -> Unit = {
        val watcher = remember { PointerWatcher { if (it is PointerEvent.Move) seen += it.position } }
        val handler = remember {
            PointerHandler { event ->
                if (event is PointerEvent.Move) seen += event.position
                false
            }
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.size(56f).interaction(slot))
            Box(if (handling) Modifier.fillMaxSize().onPointer(handler) else Modifier.fillMaxSize().watchPointer(watcher))
        }
    }

    @Test
    fun `a watching layer over a slot leaves the slot hovered`() {
        val slot = InteractionState()
        val seen = mutableListOf<Offset>()
        show(screen(slot, seen))

        move(20f, 20f)

        assertTrue(slot.isHovered, "the layer took the hover the game works its own item out from")
        assertEquals(listOf(Offset(20f, 20f)), seen, "and it was not told where the pointer went")
    }

    @Test
    fun `a handling layer over the same slot is what takes the hover away`() {
        val slot = InteractionState()
        val seen = mutableListOf<Offset>()
        show(screen(slot, seen, handling = true))

        move(20f, 20f)

        // Deliberate, and the whole reason the watching kind exists: a handler is in front of
        // everything under it even when it consumes nothing, which is what a modal scrim wants.
        assertFalse(slot.isHovered, "a raw handler is in front of what is under it")
        assertEquals(listOf(Offset(20f, 20f)), seen)
    }

    @Test
    fun `a watching layer lets a click through to the button under it`() {
        var clicks = 0
        val seen = mutableListOf<Offset>()
        show {
            val watcher = remember { PointerWatcher { if (it is PointerEvent.Move) seen += it.position } }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(56f).clickable { clicks++ })
                Box(Modifier.fillMaxSize().watchPointer(watcher))
            }
        }

        press(20f, 20f)

        assertEquals(1, clicks, "the button under the layer never heard the press")
    }

    @Test
    fun `a watcher is told about presses and releases as well as moves`() {
        val seen = mutableListOf<PointerEvent>()
        show {
            val watcher = remember { PointerWatcher { seen += it } }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(56f).clickable { })
                Box(Modifier.fillMaxSize().watchPointer(watcher))
            }
        }

        move(20f, 20f)
        down(20f, 20f)
        move(30f, 30f)
        up(30f, 30f)

        // The press is taken by the button underneath, and with it the whole of the rest of the
        // gesture. The layer is told anyway: what it is for is knowing what the pointer did, not
        // deciding it, so there is nothing for a capture to keep from it.
        val kinds = seen.map { it::class.simpleName }
        assertTrue(seen.any { it is PointerEvent.Move }, "no move: $kinds")
        assertTrue(seen.any { it is PointerEvent.Press }, "no press: $kinds")
        assertTrue(seen.any { it is PointerEvent.Release }, "no release: $kinds")
        assertEquals(
            Offset(30f, 30f),
            seen.filterIsInstance<PointerEvent.Move>().last().position,
            "the move while the button was down never arrived, so a card beside the cursor freezes",
        )
        assertEquals(
            Offset(30f, 30f),
            seen.filterIsInstance<PointerEvent.Release>().last().position,
            "the layer was never told where the button came up",
        )
    }

    @Test
    fun `a watcher keeps up with a drag it is no part of`() {
        val seen = mutableListOf<Offset>()
        var moved = Offset.Zero
        show {
            val watcher = remember { PointerWatcher { if (it is PointerEvent.Move) seen += it.position } }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(56f).draggable { delta -> moved += delta })
                Box(Modifier.fillMaxSize().watchPointer(watcher))
            }
        }

        // A real bag: the slot is draggable, so the press is captured and the pointer is dragged
        // clean off the slot. The card hanging beside the cursor still has to follow it.
        down(20f, 20f)
        move(60f, 20f)
        move(120f, 40f)
        up(120f, 40f)

        assertTrue(moved.x > 0f, "the slot under the layer never dragged")
        assertEquals(Offset(120f, 40f), seen.last(), "the layer stopped hearing where the pointer went")
    }

    @Test
    fun `a watcher hears the pointer leave the window`() {
        val seen = mutableListOf<PointerEvent>()
        show {
            val watcher = remember { PointerWatcher { seen += it } }
            Box(Modifier.fillMaxSize().watchPointer(watcher))
        }

        move(20f, 20f)
        exit(20f, 0f)

        // The one way a layer can learn there is no pointer any more: hover ending is something
        // only interactive nodes are told about, and a watcher is not one.
        assertTrue(seen.any { it is PointerEvent.Exit }, "no exit: ${seen.map { it::class.simpleName }}")
    }

    @Test
    fun `a watcher hears an exit reported past the edge it left by`() {
        val seen = mutableListOf<PointerEvent>()
        show {
            val watcher = remember { PointerWatcher { seen += it } }
            Box(Modifier.fillMaxSize().watchPointer(watcher))
        }

        // What a real backend reports. The browser's `pointerleave` gives the point on the boundary
        // the pointer crossed, and a split screen tells the area it left with a point in the *other*
        // area. Neither is inside the layer being told — 400 is one past the last pixel of a 400
        // wide screen — so hit testing finds nothing and only a walk of the watchers reaches it.
        move(200f, 200f)
        exit(400f, 200f)
        exit(-1f, 200f)
        exit(200f, 800f)

        assertEquals(3, seen.count { it is PointerEvent.Exit }, "seen: ${seen.map { it::class.simpleName }}")
    }

    @Test
    fun `a watcher hears the pointer leave in the middle of a drag`() {
        val seen = mutableListOf<PointerEvent>()
        show {
            val watcher = remember { PointerWatcher { seen += it } }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(56f).draggable { })
                Box(Modifier.fillMaxSize().watchPointer(watcher))
            }
        }

        // The slot holds the gesture, so nothing is hovered to end and the exit is delivered to
        // nobody else at all. The card hanging beside the cursor still has to put itself away.
        down(20f, 20f)
        move(120f, 40f)
        exit(400f, 40f)

        assertTrue(seen.any { it is PointerEvent.Exit }, "no exit: ${seen.map { it::class.simpleName }}")
    }

    @Test
    fun `a watcher hears the platform take a gesture away`() {
        val seen = mutableListOf<PointerEvent>()
        show {
            val watcher = remember { PointerWatcher { seen += it } }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(56f).draggable { })
                Box(Modifier.fillMaxSize().watchPointer(watcher))
            }
        }

        down(20f, 20f)
        move(120f, 40f)
        pointer.onPointer(PointerEvent.Cancel(PointerId.Mouse, Offset(500f, 40f)))

        assertTrue(seen.any { it is PointerEvent.Cancel }, "no cancel: ${seen.map { it::class.simpleName }}")
    }

    @Test
    fun `a tree with no watcher on it keeps none`() {
        show {
            Box(Modifier.fillMaxSize()) { Box(Modifier.size(56f).clickable { }) }
        }

        // What the router checks before it walks the tree an extra time. A game with no watcher
        // anywhere pays nothing for the feature, and a drag move stays the O(1) it always was.
        assertTrue(host.tree.pointerWatchers.isEmpty(), "a tree with no watchPointer registered one")
    }

    @Test
    fun `a layer that goes away stops being watched`() {
        val showing = mutableStateOf(true)
        val seen = mutableListOf<PointerEvent>()
        show {
            val watcher = remember { PointerWatcher { seen += it } }
            Box(Modifier.fillMaxSize()) {
                if (showing.value) Box(Modifier.fillMaxSize().watchPointer(watcher))
            }
        }

        assertEquals(1, host.tree.pointerWatchers.size, "the layer never registered")

        showing.value = false
        settle()

        assertTrue(host.tree.pointerWatchers.isEmpty(), "the register still holds a layer that is gone")
    }

    @Test
    fun `a node that only watches is not interactive`() {
        val resolved = Modifier.watchPointer(PointerWatcher { }).resolve()

        assertFalse(resolved.isInteractive, "a watcher stands in front of nothing")
        assertTrue(resolved.hearsPointer, "but the pointer still has to find it")
    }
}
