package dev.wildware.composegl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.wrapContentSize
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * What a whole screen costs when nothing on it is changing.
 *
 * The claim this project is built on is that an interface which is not moving costs almost nothing
 * a frame. Two widgets have their own allocation tests; this one asks it of a screen — a HUD with
 * panels, text, sliders and buttons on it — because a cost per frame that only appears when there
 * are twenty widgets is exactly the cost a per-widget test cannot see. The same HUD with health
 * bars and a crosshair on it is asked the same questions in `composegl-game`.
 *
 * Two separate questions, and they fail differently:
 *
 *  - a still screen asks the runtime for no frames at all, so a game's own loop skips the whole of
 *    layout and drawing;
 *  - and drawing one anyway — which a game does every frame if it never checks — allocates a
 *    handful of small objects rather than a pile of them.
 *
 * The numbers are deliberately loose. This runs on whatever JVM CI has, and what is being caught
 * is an order of magnitude — a lambda made per widget per frame, a list rebuilt per frame, a string
 * formatted per frame — and not a byte.
 *
 * The tests tagged `allocation` run a second time in `jvmAllocationTest`, with escape analysis off.
 * The desktop JVM would otherwise remove the short-lived objects Android's runtime keeps — an
 * iterator per node walked was the big one (#248) — and pass on code that makes thousands of them a
 * frame on a phone.
 */
class FrameCostTest {

    private val host = UiHost()
    private val bounds = Rect.of(0f, 0f, 1280f, 720f)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private var hull by mutableFloatStateOf(0.8f)
    private var ammo by mutableStateOf(148)

    /** Plain boxes beside the HUD, none focusable and none drawing anything: nodes for a walk to visit. */
    private var plain by mutableIntStateOf(0)

    /** Small windows beside the HUD, standing still: a settings page or an inventory has several. */
    private var windows by mutableIntStateOf(0)

    /** Whether those windows scroll, or are plain clipped boxes with the same thing inside. */
    private var scrolling by mutableStateOf(true)

    /** A combat HUD: about twenty widgets, the sort of thing a game actually leaves on screen. */
    private fun hud() {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                Box(Modifier.fillMaxSize()) {
                    repeat(plain) { Box(Modifier.size(4f)) {} }
                    repeat(windows) {
                        if (scrolling) {
                            // No bars: a bar draws its thumb through a new rectangle every frame,
                            // which is drawing rather than asking for frames (#258).
                            ScrollArea(Modifier.size(40f), bars = false) { Box(Modifier.size(40f, 400f)) {} }
                        } else {
                            Box(Modifier.size(40f).clip()) { Box(Modifier.size(40f, 400f)) {} }
                        }
                    }
                    Panel(Modifier.align(Alignment.BottomStart).padding(left = 28f, bottom = 28f).width(280f)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
                            Text("HULL")
                            Slider(hull, onValueChange = { hull = it }, Modifier.fillMaxWidth())
                            Text("HEAT")
                            Slider(0.24f, onValueChange = {}, Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("AMMO")
                                Text("$ammo")
                            }
                        }
                    }

                    Panel(Modifier.align(Alignment.TopEnd).padding(right = 28f, top = 28f).width(260f)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8f)) {
                            Text("SYSTEMS")
                            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                                Button("PULSE", onClick = {})
                                Button("CLOAK", onClick = {})
                                Button("REPAIR", onClick = {})
                            }
                        }
                    }
                }
            }
        }
        repeat(3) { frame() }
    }

    private fun frame() {
        wall += 16_000_000L
        host.frame(wall)
    }

    @Test
    fun `a still screen asks for no frames at all`() {
        hud()

        repeat(60) {
            wall += 16_000_000L
            assertTrue(!host.frame(wall), "frame $it redrew a screen where nothing had changed")
        }
    }

    /**
     * The renderer's own frame, `settle`, on the same HUD: a still frame does not lay it out.
     *
     * Asked through the budget, with a clock that moves a millisecond every time it is read, so any
     * layout pass at all would show as a millisecond of it.
     */
    @Test
    fun `settling a still screen lays nothing out`() {
        hud()
        var nanos = 0L
        val budget = FrameBudget(publishEveryMillis = 0L, nanoTime = { nanos += 1_000_000; nanos })
        budget.isOn = true
        val viewport = Viewport.oneToOne(Size(1280f, 720f))
        repeat(5) {
            wall += 16_000_000L
            host.settle(viewport, nanos = wall, budget = budget)
        }
        budget.reset()

        repeat(30) {
            wall += 16_000_000L
            assertTrue(!host.settle(viewport, nanos = wall, budget = budget), "frame $it changed something")
            budget.endFrame()
        }

        val reading = budget.reading
        assertTrue(reading.frames == 30L, "the budget did not see the frames: ${reading.frames}")
        assertTrue(reading.recomposeMillis > 0f, "the budget timed nothing at all")
        assertTrue(reading.layoutMillis == 0f, "a still frame laid the HUD out: ${reading.layoutMillis}ms")
    }

    @Test
    @Tag("allocation")
    fun `drawing a still screen anyway allocates almost nothing`() {
        hud()

        val silent = Silent()
        // Measured after a few passes, so what is counted is the steady state rather than the
        // first sight of every glyph on the screen.
        repeat(5) {
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
            DrawPass(silent).draw(host.root)
        }

        // Twice: as a still frame really is laid out, which measures nothing, and with every node
        // measured, as a frame that changed the whole screen is — the pass that would make
        // something per node if anything did.
        for (everything in listOf(false, true)) {
            val before = allocatedBytes()
            repeat(20) {
                wall += 16_000_000L
                host.frame(wall)
                if (everything) host.tree.invalidate()
                MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
                DrawPass(silent).draw(host.root)
            }
            val perFrame = (allocatedBytes() - before) / 20

            // Where it stands today, measured: about 40 bytes of it is the layout pass and 40 the
            // draw. Neither pass makes anything per node any more — what is left is the recomposer
            // being asked for a frame it has nothing to do in, plus the throwaway passes this loop
            // makes itself. A ratchet rather than a target.
            assertTrue(perFrame < 1_536, "a still frame of a whole HUD allocated $perFrame bytes (every node measured: $everything)")
        }
    }

    @Test
    @Tag("allocation")
    fun `watching where things are costs a still screen nothing`() {
        var told = 0
        val onSize = SizeChangedHandler { told++ }
        val onPlace = PlacedHandler { told++ }
        host.setContent {
            Box(Modifier.fillMaxSize()) {
                repeat(40) { index ->
                    Box(
                        Modifier.offset(index * 30f, index * 15f).size(24f)
                            .onSizeChanged(onSize).onPlaced(onPlace),
                    )
                }
            }
        }
        repeat(3) { frame() }

        val silent = Silent()
        // Warmed the same way as the HUD above, so what is counted is the steady state.
        repeat(5) {
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
            DrawPass(silent).draw(host.root)
        }
        assertTrue(told == 80, "forty nodes, two handlers each, told once: $told")

        // Skipped, as a still frame is, the watchers are still gathered and asked; measured in full
        // they are reached the ordinary way. Neither may make anything per watcher.
        for (everything in listOf(false, true)) {
            val before = allocatedBytes()
            repeat(20) {
                wall += 16_000_000L
                host.frame(wall)
                if (everything) host.tree.invalidate()
                MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
                DrawPass(silent).draw(host.root)
            }
            val perFrame = (allocatedBytes() - before) / 20

            assertTrue(told == 80, "a still screen told its watchers something: $told")
            // The same ratchet as a still HUD: forty watchers add one small list to the pass, not an
            // object each.
            assertTrue(perFrame < 1_536, "a still frame with forty watchers allocated $perFrame bytes (every node measured: $everything)")
        }
    }

    @Test
    @Tag("allocation")
    fun `a still screen of wrapped badges lays out without making anything per badge`() {
        host.setContent {
            Column {
                repeat(40) {
                    Row(Modifier.width(600f).height(30f)) {
                        repeat(3) {
                            Box(Modifier.weight(1f).wrapContentSize(Alignment.BottomEnd).size(12f)) {}
                        }
                    }
                }
            }
        }
        repeat(3) { frame() }
        // Every node measured each time: a pass over a tree nothing changed measures nothing, and
        // so would show nothing about what measuring a wrapped node costs.
        repeat(20) {
            host.tree.invalidate()
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
        }

        val before = allocatedBytes()
        repeat(20) {
            host.tree.invalidate()
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
        }
        val perPass = (allocatedBytes() - before) / 20

        // A hundred and twenty wrapped nodes. Anything made per node per pass — an Alignment to
        // ask where it sits, a Constraints for the loosened offer — is thousands of bytes here.
        assertTrue(perPass < 512, "a layout pass over 120 wrapped badges allocated $perPass bytes")
    }

    @Test
    @Tag("allocation")
    fun `one number changing redraws without dragging the whole screen with it`() {
        hud()

        val silent = Silent()
        repeat(5) {
            ammo -= 1
            frame()
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
            DrawPass(silent).draw(host.root)
        }

        val before = allocatedBytes()
        repeat(20) {
            ammo -= 1
            wall += 16_000_000L
            assertTrue(host.frame(wall), "a changed number should have redrawn something")
            MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
            DrawPass(silent).draw(host.root)
        }
        val perFrame = (allocatedBytes() - before) / 20

        // A number that changes every frame has to be measured again — a new string, a new layout —
        // so this is not free, and is not expected to be. What it is watching is that it stays in
        // the same order of magnitude as a still frame rather than recomposing the whole screen.
        assertTrue(perFrame < 65_536, "one changed number cost $perFrame bytes a frame")
    }

    /**
     * The frame a game actually runs: [UiRenderer] with a [FocusManager], which keeps focus on
     * something real — on a still frame by seeing that nothing has moved. Something has focus, as it
     * does on a menu.
     */
    @Test
    @Tag("allocation")
    fun `a still frame through the renderer with focus allocates almost nothing`() {
        hud()
        val renderer = UiRenderer(host, Silent())
        val focus = FocusManager(host.root)
        renderer.focus = focus
        val viewport = Viewport.oneToOne(Size(1280f, 720f))
        repeat(5) {
            wall += 16_000_000L
            renderer.render(viewport, wall)
        }
        assertTrue(focus.focused != null, "the HUD's first button should have taken focus")

        val before = allocatedBytes()
        repeat(20) {
            wall += 16_000_000L
            renderer.render(viewport, wall)
        }
        val perFrame = (allocatedBytes() - before) / 20

        // A ratchet: 248 bytes today, from 416 and about 2,140 before that. The focus refresh's own
        // list of focusable nodes and where focus was last seen are gone: on a frame where the tree
        // did not change it makes nothing (#251). Nothing is made per node: the next test holds that.
        assertTrue(perFrame < 384, "a still frame of a whole HUD through the renderer allocated $perFrame bytes")
    }

    /**
     * The same frame costs nothing per node. On a still frame the focus refresh does not walk the
     * tree at all (#251); the next test holds the frames where it does.
     */
    @Test
    @Tag("allocation")
    fun `a still frame through the renderer with focus costs nothing per node`() {
        hud()
        val renderer = UiRenderer(host, Silent())
        renderer.focus = FocusManager(host.root)
        val viewport = Viewport.oneToOne(Size(1280f, 720f))

        val fewer = stillFrameCost(renderer, viewport, boxes = 10)
        val more = stillFrameCost(renderer, viewport, boxes = 80)

        assertEquals(fewer, more, "seventy more nodes on a still screen should cost not one byte more a frame")
    }

    /**
     * A frame where the focus refresh does look costs nothing per node either. It walks the whole
     * tree twice, once for a focus trap and once for the focusable nodes, on every frame something
     * changes — every frame of an animated screen — and an iterator per node per walk was how a
     * screen on a phone made most of its garbage (#248). The refresh is told to look again before
     * each frame, so the screen around it stays still and the walks are all that is measured.
     */
    @Test
    @Tag("allocation")
    fun `a frame where focus looks again costs nothing per node`() {
        hud()
        val renderer = UiRenderer(host, Silent())
        val focus = FocusManager(host.root)
        renderer.focus = focus
        val viewport = Viewport.oneToOne(Size(1280f, 720f))

        val walked = focus.walks
        val fewer = stillFrameCost(renderer, viewport, boxes = 10) { focus.lookAgain() }
        val more = stillFrameCost(renderer, viewport, boxes = 80) { focus.lookAgain() }

        // Two walks a frame, a trap and the focusable nodes, over the 2 x (10 + 5 x 20) frames above.
        assertTrue(focus.walks - walked >= 2 * 220, "the refresh did not walk the tree on every frame measured")
        assertEquals(fewer, more, "seventy more nodes for focus to walk should cost not one byte more a frame")
    }

    /**
     * A scroll area on a still screen costs a frame no more than the clipped box it is drawn as.
     * Each one's fling loop used to wait on every frame in case a fling started, which woke the
     * recomposer on every frame of a screen where nothing moved, and made garbage doing it: 7,960
     * bytes a frame for fifteen of them (#249).
     */
    @Test
    @Tag("allocation")
    fun `still scroll areas cost a frame no more than clipped boxes`() {
        hud()
        val renderer = UiRenderer(host, Silent())
        renderer.focus = FocusManager(host.root)
        val viewport = Viewport.oneToOne(Size(1280f, 720f))
        windows = 15

        scrolling = false
        val clipped = stillFrameCost(renderer, viewport, boxes = 0)
        scrolling = true
        val scrollAreas = stillFrameCost(renderer, viewport, boxes = 0)

        assertEquals(clipped, scrollAreas, "fifteen still scroll areas should cost not one byte more a frame than fifteen clipped boxes")
    }

    /**
     * What a still frame of the HUD with [boxes] plain boxes beside it allocates: the fewest of five
     * rounds of twenty. [beforeEach] runs before every frame.
     */
    private fun stillFrameCost(renderer: UiRenderer, viewport: Viewport, boxes: Int, beforeEach: () -> Unit = {}): Long {
        plain = boxes
        repeat(10) {
            beforeEach()
            wall += 16_000_000L
            renderer.render(viewport, wall)
        }
        // The fewest of five rounds: the JVM now and then allocates a few hundred bytes on this
        // thread for itself, compiling code that has just become hot.
        var least = Long.MAX_VALUE
        repeat(5) {
            val before = allocatedBytes()
            repeat(20) {
                beforeEach()
                wall += 16_000_000L
                renderer.render(viewport, wall)
            }
            least = minOf(least, allocatedBytes() - before)
        }
        return least / 20
    }

    /** A game asks its tree questions every frame; walking it is free. */
    @Test
    @Tag("allocation")
    fun `walking the tree allocates nothing`() {
        hud()
        var visited = 0
        val visit: (UiNode) -> Unit = { visited++ }
        val never: (UiNode) -> Boolean = { false }
        val walk = {
            host.root.forEach(visit)
            host.root.firstOrNull(never)
        }
        repeat(20) { walk() }
        visited = 0

        // The fewest of five rounds: the JVM now and then allocates a few hundred bytes on this
        // thread for itself, compiling code that has just become hot. A walk that made anything
        // would make it every round.
        var bytes = Long.MAX_VALUE
        repeat(5) {
            val before = allocatedBytes()
            repeat(20) { walk() }
            bytes = minOf(bytes, allocatedBytes() - before)
        }

        assertTrue(visited > 5 * 20 * 20, "the walk should have visited every node of the HUD: $visited")
        assertEquals(0L, bytes, "twenty walks of the HUD allocated $bytes bytes")
    }

    /** A canvas that draws nothing and keeps nothing, for measuring what the toolkit itself costs. */
    private class Silent : dev.wildware.composegl.ui.graphics.UiCanvas {
        override fun rect(rect: Rect, colour: Colour, corner: Float) = Unit
        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun fan(points: FloatArray, colour: Colour) = Unit
        override fun text(layout: dev.wildware.composegl.ui.text.TextLayout, x: Float, y: Float, colour: Colour) = Unit
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) = Unit
    }

    /**
     * What this thread has allocated so far. HotSpot only, which is what these tests run on.
     *
     * Through a bean fetched once: `ManagementFactory.getThreadMXBean()` allocates about 800 bytes a
     * call itself, which an exact count would see.
     */
    private fun allocatedBytes(): Long = threads.currentThreadAllocatedBytes

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
}
