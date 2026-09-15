package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.AnimationSpec
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Spring
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.run
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An expanding quest entry, driven the way a player drives it: a click, a pad press, a key. Every
 * test looks at what came out — the boxes layout wrote, what was drawn and where it was cut off,
 * what a click reaches — frame by frame on a clock the test holds.
 *
 * The resize runs on a clock of its own, [resize], which a test stops before it acts. A click
 * settles the screen, and settling waits for animations on running clocks to land; on a stopped one
 * the resize is left at its first frame, so the test can start the clock and step through it.
 */
class AnimateContentSizeUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(640f, 480f), content = content).also { opened += it }

    private val resize = Clock("resize")

    /** A tenth of the way every frame, near enough: 160ms at sixty frames a second. */
    private val linear = Tween(durationMillis = 160, easing = Easings.Linear)

    private val detailsColour = Colour.rgb(0x33AA55)

    private var expanded by mutableStateOf(false)
    private var showPanel by mutableStateOf(true)
    private var animated by mutableStateOf(true)
    private var detailClicks = 0

    /**
     * A header 80 by 40 that toggles the entry, and 200 by 100 of details under it when open; a
     * strip under the whole entry to show its neighbours follow the moving size.
     */
    private fun openEntry(
        spec: AnimationSpec = linear,
        alignment: Alignment = Alignment.TopStart,
        clock: Clock = resize,
    ) = open {
        Column {
            if (showPanel) {
                val resizing = if (animated) Modifier.animateContentSize(spec, alignment, clock) else Modifier
                Box(resizing.testTag("entry")) {
                    Column(Modifier.testTag("contents")) {
                        Box(
                            Modifier.size(80f, 40f)
                                .focusable(initial = true)
                                .clickable { expanded = !expanded }
                                .testTag("header"),
                        )
                        if (expanded) {
                            Box(
                                Modifier.size(200f, 100f)
                                    .background(detailsColour)
                                    .clickable { detailClicks++ }
                                    .testTag("details"),
                            )
                        }
                    }
                }
            }
            Box(Modifier.size(50f, 20f).testTag("below"))
        }
    }

    private fun UiTest.sizeOf(tag: String) = node(tag).let { Size(it.width, it.height) }

    /** [count] frames of time, without waiting for anything to settle. */
    private fun UiTest.frames(count: Int) = repeat(count) { render() }

    /** Draws the tree the way a game does, into a recording, and hands back what was drawn. */
    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        DrawPass(canvas).draw(root)
        return canvas
    }

    private fun RecordingCanvas.details(): DrawCall.Rectangle =
        calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == detailsColour }

    @Test
    fun `a click that opens the entry grows it towards its contents over several frames`() {
        val ui = openEntry()
        assertEquals(Size(80f, 40f), ui.sizeOf("entry"))
        ui.host.clocks.stop(resize)

        ui.click("header")

        assertEquals(Size(80f, 40f), ui.sizeOf("entry"), "the entry has not jumped to its new contents")
        assertEquals(Size(200f, 140f), ui.sizeOf("contents"), "the contents are already their new size")

        ui.host.clocks.start(resize)
        ui.frames(4)
        val halfway = ui.sizeOf("entry")
        assertTrue(halfway.width > 100f && halfway.width < 180f, "part-way across, at $halfway")
        assertTrue(halfway.height > 55f && halfway.height < 125f, "part-way down, at $halfway")
        assertEquals(halfway.height, ui.node("below").layoutBoundsInRoot.top, "the strip under it follows the moving size")

        ui.settle()

        assertEquals(Size(200f, 140f), ui.sizeOf("entry"))
        assertEquals(140f, ui.node("below").layoutBoundsInRoot.top)
        assertFalse(ui.host.clocks.isAnimating)
    }

    @Test
    fun `each frame of the resize is a bigger box than the last`() {
        val ui = openEntry()
        ui.host.clocks.stop(resize)
        ui.click("header")
        ui.host.clocks.start(resize)

        var last = ui.sizeOf("entry")
        repeat(9) {
            ui.render()
            val now = ui.sizeOf("entry")
            assertTrue(now.width > last.width && now.height > last.height, "grew from $last to $now")
            last = now
        }
    }

    @Test
    fun `south on the pad closes it again and it shrinks rather than snapping`() {
        val ui = openEntry()
        ui.pad(GamepadButton.South)
        assertEquals(Size(200f, 140f), ui.sizeOf("entry"), "opened by the pad and landed")
        ui.host.clocks.stop(resize)

        ui.pad(GamepadButton.South)

        assertEquals(Size(200f, 140f), ui.sizeOf("entry"), "still the open size on the frame it was closed")
        ui.host.clocks.start(resize)
        ui.frames(5)
        val part = ui.sizeOf("entry")
        assertTrue(part.width < 200f && part.width > 80f, "shrinking, at $part")
        assertTrue(part.height < 140f && part.height > 40f, "shrinking, at $part")

        ui.settle()
        assertEquals(Size(80f, 40f), ui.sizeOf("entry"))
    }

    @Test
    fun `contents that do not fit yet are cut off at the moving edge and not once it has arrived`() {
        val ui = openEntry()
        ui.host.clocks.stop(resize)
        ui.click("header")
        ui.host.clocks.start(resize)
        ui.frames(3)

        val entry = ui.node("entry").layoutBoundsInRoot
        val cut = ui.drawn().details()
        assertEquals(entry, cut.clip, "the details are drawn through the entry's edge")
        assertTrue(cut.rect.bottom > cut.clip.bottom, "and they reach past it, so the clip is what hides them")

        ui.settle()

        val whole = ui.drawn().details()
        assertEquals(Rect.of(0f, 0f, 640f, 480f), whole.clip, "nothing is cut once the entry has arrived")
    }

    @Test
    fun `details outside the moving edge cannot be clicked until they are shown`() {
        val ui = openEntry()
        ui.host.clocks.stop(resize)
        ui.click("header")
        assertEquals(40f, ui.sizeOf("entry").height)

        // The middle of the details is at (100, 90): drawn nowhere yet, because the entry is still
        // 80 by 40 and cuts it off.
        ui.click(Offset(60f, 90f))
        assertEquals(0, detailClicks, "a click on what is cut off reaches nothing")

        ui.host.clocks.start(resize)
        ui.settle()
        ui.click(Offset(60f, 90f))
        assertEquals(1, detailClicks, "the same place, once the details are shown")
    }

    @Test
    fun `an entry on the screen from the start is its size at once`() {
        expanded = true
        val ui = openEntry()

        assertEquals(Size(200f, 140f), ui.sizeOf("entry"))
        assertFalse(ui.host.clocks.isAnimating)
    }

    @Test
    fun `an entry that appears later appears at its size rather than growing from nothing`() {
        showPanel = false
        expanded = true
        val ui = openEntry()
        ui.host.clocks.stop(resize)

        showPanel = true
        ui.settle()

        assertEquals(Size(200f, 140f), ui.sizeOf("entry"))
        assertFalse(ui.node("entry").let { ui.drawn().details().clip == it.layoutBoundsInRoot })
    }

    @Test
    fun `closing it part-way turns round from where it had got to`() {
        val ui = openEntry()
        ui.host.clocks.stop(resize)
        ui.click("header")
        ui.host.clocks.start(resize)
        ui.frames(5)
        val reached = ui.sizeOf("entry")
        ui.host.clocks.stop(resize)

        ui.click("header")
        ui.host.clocks.start(resize)
        ui.render()

        val turned = ui.sizeOf("entry")
        assertTrue(abs(turned.height - reached.height) < 15f, "went from ${reached.height} to ${turned.height} rather than jumping")
        assertTrue(turned.height < reached.height, "and is on its way back")
        ui.settle()
        assertEquals(Size(80f, 40f), ui.sizeOf("entry"))
    }

    @Test
    fun `every frame of the resize reports a change and the frame after it lands does not`() {
        val ui = openEntry()
        ui.host.clocks.stop(resize)
        ui.click("header")
        ui.host.clocks.start(resize)

        var frames = 0
        while (ui.sizeOf("entry") != Size(200f, 140f)) {
            assertTrue(ui.render(), "frame $frames moved the entry, so a game has to draw it")
            frames++
            assertTrue(frames < 60, "the resize never landed")
        }
        assertTrue(frames >= 9, "it took $frames frames rather than jumping")
        assertFalse(ui.render(), "an entry that has arrived changes nothing")
    }

    @Test
    fun `an entry that has arrived costs no frames however long it sits there`() {
        val ui = openEntry()
        ui.click("header")
        val before = ui.host.changedFrames

        ui.advanceBy(2000)

        assertEquals(before, ui.host.changedFrames)
        assertFalse(ui.host.clocks.isAnimating)
    }

    @Test
    fun `pausing the world freezes a resize on the world clock and resuming finishes it`() {
        val ui = openEntry(clock = Clock.World)
        ui.host.clocks.stop(Clock.World)

        ui.click("header")
        ui.advanceBy(1000)

        assertEquals(Size(80f, 40f), ui.sizeOf("entry"), "a paused world holds it where it was")
        assertEquals(ui.node("entry").layoutBoundsInRoot, ui.drawn().details().clip, "and still cuts it off")

        ui.host.clocks.start(Clock.World)
        ui.settle()

        assertEquals(Size(200f, 140f), ui.sizeOf("entry"))
    }

    @Test
    fun `an entry removed part-way does not leave anything waiting for it`() {
        val ui = openEntry()
        ui.host.clocks.stop(resize)
        ui.click("header")
        ui.host.clocks.start(resize)
        ui.frames(3)
        assertTrue(ui.host.clocks.isAnimating)

        showPanel = false
        ui.settle()

        ui.assertDoesNotExist("entry")
        assertFalse(ui.host.clocks.isAnimating, "a removed entry is not still playing")
    }

    @Test
    fun `an entry that loses the modifier part-way takes its contents' size at once`() {
        val ui = openEntry()
        ui.host.clocks.stop(resize)
        ui.click("header")
        ui.host.clocks.start(resize)
        ui.frames(3)

        animated = false
        ui.settle()

        assertEquals(Size(200f, 140f), ui.sizeOf("entry"))
        assertFalse(ui.host.clocks.isAnimating)
    }

    @Test
    fun `bottom end alignment keeps the contents against the far corner while it grows`() {
        val ui = openEntry(alignment = Alignment.BottomEnd)
        ui.host.clocks.stop(resize)
        ui.click("header")
        ui.host.clocks.start(resize)
        ui.frames(4)

        val entry = ui.node("entry").layoutBoundsInRoot
        val contents = ui.node("contents").layoutBoundsInRoot
        assertTrue(entry.width < 200f, "still growing")
        assertEquals(entry.right, contents.right, 0.01f)
        assertEquals(entry.bottom, contents.bottom, 0.01f)
    }

    @Test
    fun `the line of text inside moves with the contents while a bottom aligned entry grows`() {
        val ui = open {
            Box(Modifier.animateContentSize(linear, Alignment.BottomStart, resize).testTag("entry")) {
                Column(Modifier.testTag("contents")) {
                    Text("The lost ring", Modifier.clickable { expanded = !expanded }.testTag("title"))
                    if (expanded) Box(Modifier.size(200f, 100f))
                }
            }
        }
        ui.host.clocks.stop(resize)
        ui.click("title")
        ui.host.clocks.start(resize)
        ui.frames(4)

        val entry = ui.node("entry")
        val contents = ui.node("contents")
        val drop = contents.layoutBoundsInRoot.top - entry.layoutBoundsInRoot.top
        assertTrue(drop < -1f, "the title is pushed up out of sight while it grows, by $drop")
        assertEquals(drop + contents.firstBaseline, entry.firstBaseline, 0.01f)
    }

    @Test
    fun `a game that lays out only on a changed frame still sees the resize through to the end`() {
        val ui = openEntry()
        ui.host.clocks.stop(resize)
        ui.click("header")

        // The game's own time from here, well past the harness's; the first frame takes the jump
        // while the resize's clock is still stopped, so none of it is counted against the resize.
        var now = 1_000_000_000_000L
        if (ui.host.frame(now)) MeasurePass().run(ui.root, ui.viewport)
        ui.host.clocks.start(resize)

        // The loop UiHost's own documentation shows: lay out and draw only when the frame changed.
        repeat(60) {
            now += 16_666_667L
            if (ui.host.frame(now)) MeasurePass().run(ui.root, ui.viewport)
        }

        assertEquals(Size(200f, 140f), ui.sizeOf("entry"), "the resize did not stall")
        assertFalse(ui.host.clocks.isAnimating)
    }

    @Test
    fun `a paused resize costs no frames while it waits`() {
        val ui = openEntry(clock = Clock.World)
        ui.host.clocks.stop(Clock.World)
        ui.click("header")
        val before = ui.host.changedFrames

        ui.advanceBy(1000)

        assertEquals(before, ui.host.changedFrames, "nothing moves, so nothing is drawn again")
    }

    @Test
    fun `an entry inside an entry grows both and lands both`() {
        val ui = open {
            Column {
                Box(Modifier.animateContentSize(linear, clock = resize).testTag("outer")) {
                    Column {
                        Box(Modifier.size(60f, 30f).clickable { expanded = !expanded }.testTag("toggle"))
                        Box(Modifier.animateContentSize(linear, clock = resize).testTag("inner")) {
                            Box(Modifier.size(if (expanded) 150f else 60f, if (expanded) 90f else 10f))
                        }
                    }
                }
                Box(Modifier.size(50f, 20f).testTag("below"))
            }
        }
        assertEquals(Size(60f, 40f), ui.sizeOf("outer"))
        ui.host.clocks.stop(resize)
        ui.click("toggle")
        ui.host.clocks.start(resize)
        ui.frames(4)

        val inner = ui.sizeOf("inner")
        val outer = ui.sizeOf("outer")
        assertTrue(inner.height > 10f && inner.height < 90f, "the inner one is part-way, at $inner")
        assertTrue(outer.height < 30f + inner.height, "the outer one follows behind it, at $outer")
        assertEquals(outer.height, ui.node("below").layoutBoundsInRoot.top)

        ui.settle()
        assertEquals(Size(150f, 90f), ui.sizeOf("inner"))
        assertEquals(Size(150f, 120f), ui.sizeOf("outer"))
        assertFalse(ui.host.clocks.isAnimating)
    }

    @Test
    fun `an empty panel grows from nothing when its first contents arrive`() {
        val ui = open {
            Box(Modifier.animateContentSize(linear, clock = resize).clickable { expanded = true }.testTag("panel")) {
                if (expanded) Box(Modifier.size(100f, 60f).background(detailsColour).testTag("details"))
            }
            Box(Modifier.size(640f, 480f).clickable { expanded = true })
        }
        assertEquals(Size(0f, 0f), ui.sizeOf("panel"))
        ui.host.clocks.stop(resize)

        ui.click(Offset(300f, 300f))

        assertEquals(Size(0f, 0f), ui.sizeOf("panel"), "the empty panel has not jumped")
        ui.host.clocks.start(resize)
        ui.frames(4)
        val part = ui.sizeOf("panel")
        assertTrue(part.width > 0f && part.width < 100f, "part-way, at $part")
        assertEquals(ui.node("panel").layoutBoundsInRoot, ui.drawn().details().clip)

        ui.settle()
        assertEquals(Size(100f, 60f), ui.sizeOf("panel"))
    }

    @Test
    fun `recomposing a still panel with its spec written inline draws nothing again`() {
        var presses by mutableStateOf(0)
        val ui = open {
            // Read here so every press recomposes this block, and with it makes a fresh Spring.
            presses.let { }
            Box(
                Modifier.animateContentSize(Spring(threshold = 0.5f), clock = resize)
                    .focusable(initial = true)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.Down) presses++
                        event.type == KeyEventType.Down
                    }
                    .testTag("panel"),
            ) {
                Box(Modifier.size(80f, 40f))
            }
        }
        val before = ui.host.changedFrames

        ui.key(Key.Space)
        ui.key(Key.Space)

        assertEquals(2, presses, "the presses reached the panel")
        assertEquals(before, ui.host.changedFrames, "a panel that says the same thing is not a change")
    }

    @Test
    fun `a bubble grows as keys add words to it`() {
        var words by mutableStateOf("Hi")
        val ui = open {
            Box(
                Modifier.animateContentSize(linear, clock = resize)
                    .padding(8f)
                    .focusable(initial = true)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.Down && event.key == Key.Space) {
                            words += " there traveller"
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("bubble"),
            ) {
                Text(words, Modifier.testTag("words"))
            }
        }
        val before = ui.sizeOf("bubble")
        ui.host.clocks.stop(resize)

        ui.key(Key.Space)

        assertEquals("Hi there traveller", ui.text("words"))
        assertEquals(before, ui.sizeOf("bubble"), "the bubble has not jumped")
        val wants = ui.node("words").width + 16f
        assertTrue(wants > before.width, "the words want more room: $wants against ${before.width}")

        ui.host.clocks.start(resize)
        ui.frames(4)
        val part = ui.sizeOf("bubble").width
        assertTrue(part > before.width && part < wants, "part-way, at $part")

        ui.settle()
        assertEquals(wants, ui.sizeOf("bubble").width)
    }
}
