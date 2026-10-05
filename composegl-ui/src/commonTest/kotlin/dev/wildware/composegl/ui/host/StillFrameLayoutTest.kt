package dev.wildware.composegl.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.layout.run
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.animateContentSize
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Image
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A frame where nothing changed does not lay the tree out again.
 *
 * Measuring and placing every node costs the same whether anything moved or not, and on a still
 * screen it is all waste: the rectangles it writes are the ones already there. Measured in a real
 * game, the pass was nearly a twentieth of a phone's frame on a board standing still.
 *
 * What is counted is calls to a measure policy, because that is the pass: a frame that skips it
 * calls none. The other half of every test is that skipping never leaves a wrong rectangle behind
 * — a change, a different screen size, a resize under way and a click are all still laid out on
 * the frame they happen.
 */
class StillFrameLayoutTest {

    private val host = UiHost()
    private val viewport = Viewport.oneToOne(Size(200f, 100f))
    private var nanos = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private var measures = 0

    /**
     * Stacks its children at its corner, each as big as it likes, takes what it is offered up to 40
     * by 20, and counts every time it is asked.
     */
    private val counted = MeasurePolicy { measurables, constraints ->
        measures++
        val room = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val placed = measurables.map { it.measure(room) }
        layout(constraints.constrainWidth(40f), constraints.constrainHeight(20f)) {
            placed.forEach { it.at(0f, 0f) }
        }
    }

    private fun tick(): Long {
        nanos += 16_666_667L
        return nanos
    }

    /** Settles until a frame reports nothing, failing rather than spinning. */
    private fun settled(at: Viewport = viewport) {
        repeat(8) { if (!host.settle(at, nanos = tick())) return }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    @Test
    fun `a second frame with nothing changed calls no measure`() {
        host.setContent { Layout(name = "counted", measurePolicy = counted) }
        val canvas = RecordingCanvas()
        val renderer = UiRenderer(host, canvas)
        renderer.render(viewport, tick())
        val first = measures
        assertTrue(first > 0, "the first frame has to be laid out")

        repeat(5) { assertEquals(false, renderer.render(viewport, tick()), "nothing changed") }

        assertEquals(first, measures, "a still frame measured the tree again")
    }

    @Test
    fun `a state change is laid out on the frame it lands`() {
        var wide by mutableStateOf(10f)
        host.setContent {
            Layout(name = "counted", measurePolicy = counted, content = {
                LeafLayout(Modifier.size(wide, 4f), name = "box")
            })
        }
        settled()
        val before = measures

        wide = 30f
        assertTrue(host.settle(viewport, nanos = tick()))

        assertTrue(measures > before, "a frame that changed was not measured")
        assertEquals(30f, host.root.children.single().children.single().width)
    }

    @Test
    fun `a different screen size is laid out with nothing recomposed`() {
        host.setContent { LeafLayout(Modifier.size(10f, 4f), name = "box") }
        settled()
        assertEquals(200f, host.root.width)

        settled(Viewport.oneToOne(Size(300f, 150f)))

        assertEquals(300f, host.root.width, "the root kept the old screen's size")
        assertEquals(150f, host.root.height)
    }

    @Test
    fun `a frame run without layout is laid out by the next settle`() {
        var wide by mutableStateOf(10f)
        host.setContent { LeafLayout(Modifier.size(wide, 4f), name = "box") }
        settled()

        wide = 30f
        // A game that pumps the runtime itself and only later settles: the change was taken by this
        // frame, but nothing has laid it out yet.
        assertTrue(host.frame(tick()))
        host.settle(viewport, nanos = tick())

        assertEquals(30f, host.root.children.single().width, "the change was never laid out")
    }

    @Test
    fun `a tree laid out somewhere else in between is laid out again`() {
        host.setContent { LeafLayout(Modifier.size(10f, 4f), name = "box") }
        settled()

        // Someone measures the same tree at another size between two frames.
        MeasurePass().run(host.root, Constraints.fixed(50f, 50f))
        assertEquals(50f, host.root.width)
        host.settle(viewport, nanos = tick())

        assertEquals(200f, host.root.width, "the tree was left at the size someone else laid it out at")
    }

    @Test
    fun `a settle against other room is laid out again`() {
        host.setContent { LeafLayout(Modifier.size(10f, 4f), name = "box") }
        settled()

        host.settle(Constraints.atMost(80f, 60f), nanos = tick())

        assertEquals(10f, host.root.width, "plain constraints lay the root out at what it holds")
        assertEquals(Rect.of(0f, 0f, 10f, 4f), host.root.layoutBoundsInRoot)
    }

    @Test
    fun `a frame that only redraws measures nothing`() {
        host.setContent { Layout(name = "counted", measurePolicy = counted) }
        settled()
        val before = measures

        // What a spinner or a marquee does each frame: the picture moved, nothing about the tree did.
        host.tree.redraw(host.root.children.single())
        assertTrue(host.settle(viewport, nanos = tick()), "a redraw is still a change to draw")

        assertEquals(before, measures, "a frame that only redrew was measured")
    }

    @Test
    fun `a resize under way is laid out every frame until it lands`() {
        var wide by mutableStateOf(10f)
        host.setContent {
            Layout(name = "counted", measurePolicy = counted, content = {
                Box(Modifier.animateContentSize(Tween(100))) { LeafLayout(Modifier.size(wide, 4f), name = "box") }
            })
        }
        settled()

        wide = 90f
        val widths = mutableListOf<Float>()
        repeat(12) {
            host.settle(viewport, nanos = tick())
            widths += host.root.children.single().children.single().width
        }

        assertEquals(90f, widths.last(), "the resize never landed")
        assertTrue(widths.distinct().size > 3, "the resize jumped instead of stepping: $widths")
        settled()
        val landed = measures
        repeat(3) { host.settle(viewport, nanos = tick()) }
        assertEquals(landed, measures, "a resize that had landed kept the tree being measured")
    }

    /**
     * A layout that reads where another node is, while it measures, reads wherever the last pass
     * left it: in a box both are measured before either is placed. Laid out every frame, the next
     * frame put that right. Laid out only on change, a pass that moved something is followed by one
     * more, which is what puts it right now.
     */
    @Test
    fun `a layout that reads where another node is catches up after that node moves`() {
        var shift by mutableStateOf(0f)
        var follows = 0
        val follower = MeasurePolicy { _, _ ->
            follows++
            layout(host.root.find("mover").x, 4f) {}
        }
        host.setContent {
            Box {
                LeafLayout(Modifier.offset(shift, 0f).size(10f).testTag("mover"))
                LeafLayout(Modifier.testTag("follower"), measurePolicy = follower)
            }
        }
        settled()

        shift = 30f
        settled()

        assertEquals(30f, host.root.find("follower").width, "the follower kept where the mover was")
        val after = follows
        repeat(3) { host.settle(viewport, nanos = tick()) }
        assertEquals(after, follows, "the catching up never stopped")
    }

    /**
     * The same reading on a screen's very first frame, when the node it asks about has never been
     * placed at all. Right on that frame, and nothing left to put right on the next one.
     */
    @Test
    fun `a layout that reads where another node is is right on the first frame`() {
        var follows = 0
        val follower = MeasurePolicy { _, _ ->
            follows++
            layout(host.root.find("mover").x, 4f) {}
        }
        host.setContent {
            Box {
                LeafLayout(Modifier.offset(30f, 0f).size(10f).testTag("mover"))
                LeafLayout(Modifier.testTag("follower"), measurePolicy = follower)
            }
        }

        host.settle(viewport, nanos = tick())
        assertEquals(30f, host.root.find("follower").width, "the follower read where the mover was before it was placed")

        settled()
        val after = follows
        repeat(3) { host.settle(viewport, nanos = tick()) }
        assertEquals(after, follows, "a settled screen was still being measured")
    }

    /**
     * A canvas that cannot take a picture draws a scaled node at its own size, and says so on the
     * node after the frame is drawn. Where an `onPlaced` is told the node is has to follow, on a
     * screen where nothing else changes.
     */
    @Test
    fun `a node the canvas could not scale is reported where it is really drawn`() {
        var told: Rect? = null
        val placed = PlacedHandler { told = it.boundsInRoot }
        host.setContent {
            LeafLayout(Modifier.size(20f).scale(2f).onPlaced(placed).background(Colour.White), name = "big")
        }
        // Neither road a scale can take: no transform to draw it straight, and no picture.
        val refuses = object : UiCanvas by RecordingCanvas() {
            override val transforms: Boolean get() = false
            override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? = null
        }
        val renderer = UiRenderer(host, refuses)
        renderer.render(viewport, tick())
        assertEquals(40f, told?.width, "before anything is drawn the node is believed")

        repeat(3) { renderer.render(viewport, tick()) }

        assertEquals(20f, told?.width, "the node is drawn at its own size and was still reported at twice it")
    }

    /** A picture still loading: no size, until it has one. */
    private class Loading : TextureHandle {
        override var width = 0
        override var height = 0
    }

    @Test
    fun `an image whose texture arrives later is laid out when it does`() {
        val texture = Loading()
        host.setContent { Image(texture, Modifier.testTag("picture")) }
        val renderer = UiRenderer(host, RecordingCanvas())
        repeat(3) { renderer.render(viewport, tick()) }
        assertEquals(0f, host.root.find("picture").width)

        texture.width = 32
        texture.height = 16
        // Drawing is what notices; the frame after the one that drew it lays it out.
        repeat(3) { renderer.render(viewport, tick()) }

        assertEquals(32f, host.root.find("picture").width, "the picture kept the size it had while loading")
        assertEquals(16f, host.root.find("picture").height)
    }

    @Test
    fun `an image whose texture changes size in place is laid out again`() {
        val texture = Loading().apply {
            width = 32
            height = 16
        }
        host.setContent { Image(texture, Modifier.testTag("picture")) }
        val renderer = UiRenderer(host, RecordingCanvas())
        repeat(3) { renderer.render(viewport, tick()) }
        assertEquals(32f, host.root.find("picture").width)

        // A scene's picture following the window: the same handle, a different size.
        texture.width = 64
        texture.height = 8
        repeat(3) { renderer.render(viewport, tick()) }

        assertEquals(64f, host.root.find("picture").width, "the picture kept its old size")
        assertEquals(8f, host.root.find("picture").height)
    }

    @Test
    fun `a layout that threw is laid out again on the next frame`() {
        var broken = true
        val fragile = MeasurePolicy { _, constraints ->
            check(!broken) { "not ready" }
            layout(constraints.constrainWidth(40f), constraints.constrainHeight(20f)) {}
        }
        host.setContent { Layout(name = "fragile", measurePolicy = fragile) }
        assertFailsWith<IllegalStateException> { host.settle(viewport, nanos = tick()) }

        broken = false
        host.settle(viewport, nanos = tick())

        assertEquals(40f, host.root.children.single().width, "a half-done layout was taken for a finished one")
    }

    /** The wiki's advice for a custom layout, kept true: read what moves it while composing. */
    @Test
    fun `a policy remembered against a value is laid out when the value changes`() {
        var heading by mutableStateOf(0f)
        host.setContent {
            val policy = remember(heading) {
                MeasurePolicy { measurables, constraints ->
                    val placeables = measurables.map { it.measure(constraints.loosen()) }
                    val width = constraints.constrainWidth(360f)
                    layout(width, constraints.constrainHeight(24f)) {
                        placeables.forEach { it.at(width / 2f - heading, 0f) }
                    }
                }
            }
            Layout(measurePolicy = policy, name = "strip", content = { LeafLayout(Modifier.size(4f), name = "mark") })
        }
        settled()
        val mark = host.root.children.single().children.single()
        assertEquals(100f, mark.x)

        heading = 30f
        settled()

        assertEquals(70f, mark.x, "a new heading was not laid out")
    }

    @Test
    fun `a click that grows a box is laid out and still frames after it are not`() {
        var count = 0
        val counting = MeasurePolicy { measurables, constraints ->
            count++
            val placed = measurables.map { it.measure(constraints) }
            layout(placed.maxOf { it.width }, placed.sumOf { it.height.toDouble() }.toFloat()) {
                var y = 0f
                placed.forEach {
                    it.at(0f, y)
                    y += it.height
                }
            }
        }
        val ui = uiTest(Size(400f, 300f)) {
            var big by remember { mutableStateOf(false) }
            Column {
                Button("Grow", onClick = { big = true }, modifier = Modifier.testTag("grow"))
                Layout(name = "counted", measurePolicy = counting, content = {
                    LeafLayout(Modifier.size(if (big) 120f else 20f, 10f).background(Colour.White).testTag("box"))
                })
            }
        }
        try {
            assertEquals(20f, ui.root.find("box").width)

            ui.click("grow")

            assertEquals(120f, ui.root.find("box").width, "the click was not laid out")
            val afterClick = count
            ui.advanceBy(500)
            assertEquals(afterClick, count, "half a second of still frames measured the tree again")
        } finally {
            ui.close()
        }
    }
}
