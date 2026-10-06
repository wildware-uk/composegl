package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A screen with something scrollable on it that nobody is touching asks for no frames.
 *
 * A fling needs a frame-by-frame loop, but only while something is flinging. A loop that waited on
 * every frame "in case" kept the Compose recomposer awake on every frame of a still screen: one
 * scroll area anywhere and the screen was never idle. These pin that it sleeps, wakes for a flick,
 * and sleeps again once the flick has played out.
 */
class IdleScrollUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f)) { content() }.also { opened += it }

    /** Ten rows of 100: a thousand tall in a 200 window, so 800 to scroll. */
    @Composable
    private fun Tall() = Column(Modifier.fillMaxWidth()) {
        repeat(10) { Box(Modifier.fillMaxWidth().height(100f)) }
    }

    private fun UiTest.assertAsleep(what: String) =
        assertFalse(host.hasPendingWork, "$what still asks for frames:\n" + dump())

    @Test
    fun `a still scroll area asks for no frames`() {
        val ui = open {
            ScrollArea(Modifier.size(200f).testTag("area"), rememberScrollState()) { Tall() }
        }

        ui.assertAsleep("a scroll area nobody has touched")
        ui.advanceBy(500)
        ui.assertAsleep("half a second later it")
    }

    @Test
    fun `a still lazy column asks for no frames`() {
        val ui = open {
            LazyColumn(100, Modifier.size(200f).testTag("list")) { Box(Modifier.fillMaxWidth().height(40f)) }
        }

        ui.assertAsleep("a lazy column nobody has touched")
    }

    @Test
    fun `a still lazy grid asks for no frames`() {
        val ui = open {
            LazyVerticalGrid(100, GridCells.Fixed(4), Modifier.size(200f).testTag("grid")) {
                Box(Modifier.fillMaxWidth().height(40f))
            }
        }

        ui.assertAsleep("a lazy grid nobody has touched")
    }

    @Test
    fun `a scroll area that scrolls both ways asks for no frames`() {
        val ui = open {
            ScrollArea(Modifier.size(200f), rememberScrollState(), horizontal = true) {
                Box(Modifier.size(1000f))
            }
        }

        ui.assertAsleep("a two-way scroll area nobody has touched")
    }

    @Test
    fun `a flick wakes only its own area and both sleep once it has stopped`() {
        val first = ScrollState()
        val second = ScrollState()
        val ui = open {
            Row {
                ScrollArea(Modifier.size(200f).testTag("first"), first, bars = false) { Tall() }
                ScrollArea(Modifier.size(200f).testTag("second"), second, bars = false) { Tall() }
            }
        }

        // 120 up, flicked: it carries on well past where the finger let go.
        ui.flick(Offset(100f, 180f), Offset(100f, 60f))
        assertTrue(first.y > 120f, "the flicked area carried on past the finger: ${first.y}")
        assertFalse(first.isFlinging, "and has come to rest")
        assertEquals(0f, second.y, "the other area did not move")
        ui.assertAsleep("a screen whose fling has played out")

        val rested = first.y
        ui.flick(Offset(300f, 180f), Offset(300f, 60f))
        assertTrue(second.y > 120f, "the second area flings in its turn: ${second.y}")
        assertEquals(rested, first.y, "and the first stays where it rested")
        ui.assertAsleep("after the second fling the screen")
    }

    @Test
    fun `an area still wakes for a flick after another area showing its state leaves`() {
        // Each area's loop is woken by the state it shows. The one that leaves stops waking only its
        // own: the one left behind is still woken when its state is flung.
        val state = ScrollState()
        var second by mutableStateOf(true)
        val ui = open {
            Row {
                ScrollArea(Modifier.size(200f).testTag("first"), state, bars = false) { Tall() }
                if (second) ScrollArea(Modifier.size(200f).testTag("second"), state, bars = false) { Tall() }
            }
        }

        second = false
        ui.settle()
        ui.flick(Offset(100f, 180f), Offset(100f, 60f))

        assertTrue(state.y > 120f, "the area left behind carried on past the finger: ${state.y}")
        assertFalse(state.isFlinging, "and came to rest")
        ui.assertAsleep("after its fling the screen")
    }

    @Test
    fun `an area that leaves or is handed another state is woken by the old one no more`() {
        // A state can outlive its screen, remembered and saved, so every area that ever showed it
        // must take its loop back with it rather than leave the state ringing a dead one.
        val first = ScrollState()
        val second = ScrollState()
        var current by mutableStateOf(first)
        var shown by mutableStateOf(true)
        var twice by mutableStateOf(true)
        val ui = open {
            Row {
                if (shown) ScrollArea(Modifier.size(200f), current, bars = false) { Tall() }
                if (twice) ScrollArea(Modifier.size(200f), first, bars = false) { Tall() }
            }
        }
        assertEquals(2, first.down.drivers, "two areas show the first state and a fling on it wakes both")
        assertEquals(2, first.across.drivers, "sideways too")

        twice = false
        ui.settle()
        assertEquals(1, first.down.drivers, "the area that left is woken no more")
        assertEquals(1, first.across.drivers)

        current = second
        ui.settle()
        assertEquals(0, first.down.drivers, "nor is an area handed another state")
        assertEquals(0, first.across.drivers)
        assertEquals(1, second.down.drivers, "the new state wakes it instead")

        shown = false
        ui.settle()
        assertEquals(0, second.down.drivers, "an area that leaves takes its loop with it")
        assertEquals(0, second.across.drivers)
    }

    @Test
    fun `a closed screen leaves nothing for its scroll states to wake`() {
        val area = ScrollState()
        val list = LazyListState()
        val ui = open {
            Row {
                ScrollArea(Modifier.size(200f), area, bars = false) { Tall() }
                LazyColumn(100, Modifier.size(200f), list, bars = false) { Box(Modifier.fillMaxWidth().height(40f)) }
            }
        }
        assertEquals(1, area.down.drivers)
        assertEquals(1, list.axis.drivers)

        ui.close()
        assertEquals(0, area.down.drivers, "a scroll state kept past its screen wakes nothing of it")
        assertEquals(0, area.across.drivers)
        assertEquals(0, list.axis.drivers, "nor does a lazy list's")
    }

    @Test
    fun `a flick on a lazy list wakes it and it sleeps again once stopped`() {
        val state = LazyListState()
        val ui = open {
            LazyColumn(100, Modifier.size(200f).testTag("list"), state, bars = false) {
                Box(Modifier.fillMaxWidth().height(40f))
            }
        }

        ui.flick(Offset(100f, 180f), Offset(100f, 60f))
        assertTrue(state.position > 120f, "it carried on past the finger: ${state.position}")
        ui.assertAsleep("a lazy list whose fling has played out")
    }

    @Test
    fun `a scroll area handed a new state flings the new one`() {
        val first = ScrollState()
        val second = ScrollState()
        var current by mutableStateOf(first)
        val ui = open {
            ScrollArea(Modifier.size(200f).testTag("area"), current, bars = false) { Tall() }
        }

        current = second
        ui.settle()
        ui.flick(Offset(100f, 180f), Offset(100f, 60f))

        assertTrue(second.y > 120f, "the new state carried on past the finger: ${second.y}")
        assertFalse(second.isFlinging, "and came to rest")
        assertEquals(0f, first.y, "the old state was not moved")
        ui.assertAsleep("after the new state's fling the screen")
    }

    @Test
    fun `a lazy column handed a new state flings the new one`() {
        val first = LazyListState()
        val second = LazyListState()
        var current by mutableStateOf(first)
        val ui = open {
            LazyColumn(100, Modifier.size(200f).testTag("list"), current, bars = false) {
                Box(Modifier.fillMaxWidth().height(40f))
            }
        }

        current = second
        ui.settle()
        ui.flick(Offset(100f, 180f), Offset(100f, 60f))

        assertTrue(second.position > 120f, "the new state carried on past the finger: ${second.position}")
        assertFalse(second.isFlinging, "and came to rest")
        assertEquals(0f, first.position, "the old state was not moved")
        ui.assertAsleep("after the new state's fling the screen")
    }
}
