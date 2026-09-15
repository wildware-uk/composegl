package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Content that slides, scales and resizes from one page to the next, driven the way a player drives it.
 *
 * Each test composes real pages, draws them into a recording canvas through the frame a game uses and
 * moves time on a frame at a time. What is asserted is what a player sees: where each page is drawn,
 * what it is cut off by, how big the space they share is, what text shows and where focus is.
 */
class AnimatedContentTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val renderer = UiRenderer(host, canvas).also { it.focus = focus }
    private val viewport = Viewport.oneToOne(Size(400f, 300f))
    private val pointer = PointerRouter(host.root, focus)

    private var wall = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(3)
    }

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        canvas.clear()
        return renderer.render(viewport, wall)
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun play(millis: Int) = frames((millis + 15) / 16)

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
    }

    private fun click(tag: String) = click(host.root.find(tag).boundsInRoot.centre)

    private fun left(tag: String) = host.root.find(tag).boundsInRoot.left

    private fun drawn(colour: Colour): DrawCall.Rectangle? =
        canvas.only<DrawCall.Rectangle>().firstOrNull { it.colour == colour }

    /** A page 200 wide, in its own colour, with a button that goes to [next]. */
    @Composable
    private fun Card(number: Int, next: () -> Unit) {
        Column(Modifier.size(200f, 100f).testTag("card-$number").background(Colours[number])) {
            Button("NEXT", onClick = next, modifier = Modifier.testTag("next-$number"))
        }
    }

    private val forwardOrBack: (Int, Int) -> ContentTransform = { from, to ->
        if (to > from) slideLeft(Tween(300, easing = Easings.Linear)) else slideRight(Tween(300, easing = Easings.Linear))
    }

    // --- slides ---------------------------------------------------------------------------------------

    @Test
    fun `clicking next slides the new card in from the right as the old one leaves to the left`() {
        var card by mutableStateOf(0)
        show {
            AnimatedContent(card, Modifier.testTag("carousel"), transition = forwardOrBack) { shown ->
                Card(shown) { card = shown + 1 }
            }
        }
        assertEquals(0f, left("card-0"))

        click("next-0")
        frame()
        assertEquals(1, card)
        assertEquals(200f, left("card-1"), "the new card starts one whole width to the right the frame it arrives")
        assertEquals(0f, left("card-0"), "while the old one has not moved yet")

        play(150)
        val leaving = left("card-0")
        val coming = left("card-1")
        assertTrue(leaving in -130f..-70f, "half way, the old card is half a width to the left, at $leaving")
        assertTrue(coming in 70f..130f, "and the new one half a width in from the right, at $coming")
        assertEquals(200f, coming - leaving, "side by side, as one strip sliding")

        val clip = assertNotNull(drawn(Colours[1])).clip
        assertEquals(200f, clip.right, "cut off at the edge of the carousel rather than drawn over what is beside it")
        assertEquals(0f, clip.left)
        assertEquals(0f, clip.left.coerceAtLeast(assertNotNull(drawn(Colours[0])).clip.left))

        play(300)
        assertNull(host.root.findOrNull("card-0"), "once it has gone, the old card leaves the tree")
        assertEquals(0f, left("card-1"))
        assertTrue(assertNotNull(drawn(Colours[1])).clip.right > 200f, "and settled, nothing is cut off")
    }

    @Test
    fun `going back slides the other way and the transition is told which page it left for which`() {
        var card by mutableStateOf(1)
        val asked = mutableListOf<Pair<Int, Int>>()
        show {
            AnimatedContent(
                card,
                transition = { from, to -> asked += from to to; forwardOrBack(from, to) },
            ) { shown -> Card(shown) { card = shown + 1 } }
        }
        assertEquals(emptyList(), asked, "nothing is asked before anything changes")

        card = 0
        frame()
        assertEquals(-200f, left("card-0"), "going back, the new card starts one width to the left")
        play(150)
        assertTrue(left("card-1") in 70f..130f, "and the old one leaves to the right, at ${left("card-1")}")
        play(300)

        click("next-0")
        play(400)
        click("next-1")
        play(400)
        assertEquals(listOf(1 to 0, 0 to 1, 1 to 2), asked, "asked once per change, with the page left and the page shown")
        assertEquals(0f, left("card-2"))
    }

    @Test
    fun `going back part way through a slide turns round from where it had got without jumping`() {
        var card by mutableStateOf(0)
        show {
            AnimatedContent(card, transition = forwardOrBack) { shown ->
                Column(Modifier.size(200f, 100f).testTag("card-$shown").background(Colours[shown])) {
                    var presses by remember { mutableStateOf(0) }
                    Button("PRESSED $presses", onClick = { presses++ }, modifier = Modifier.testTag("press-$shown"))
                }
            }
        }
        click("press-0")
        frame()

        card = 1
        play(100)
        val turnedAt = left("card-0")
        assertTrue(turnedAt in -120f..-30f, "part way out to the left, at $turnedAt")

        card = 0
        var previous = turnedAt
        var lowest = turnedAt
        repeat(30) {
            frame()
            val now = left("card-0")
            assertTrue(kotlin.math.abs(now - previous) < 20f, "frame $it jumped from $previous to $now")
            lowest = minOf(lowest, now)
            previous = now
        }
        // The frame the change is composed on, the slide already running takes one more step.
        assertTrue(lowest >= turnedAt - 12f, "it turned round rather than finishing its exit, reaching $lowest from $turnedAt")
        assertEquals(0f, left("card-0"), "it came all the way back")
        assertNull(host.root.findOrNull("card-1"), "and the card it was leaving for went back out to the right and has gone")
        assertEquals(1, canvas.only<DrawCall.Text>().count { it.text == "PRESSED 1" }, "with what it remembered intact")
    }

    @Test
    fun `a score rolls up with the old digits leaving upwards still showing the old score`() {
        var score by mutableStateOf(9)
        show {
            AnimatedContent(score, Modifier.testTag("score"), transition = { _, _ -> slideUp(Tween(200, easing = Easings.Linear)) }) {
                Text("$it", Modifier.testTag("digits-$it"))
            }
        }
        val height = host.root.find("score").boundsInRoot.height
        assertTrue(height > 0f)

        score = 10
        play(100)
        val texts = canvas.only<DrawCall.Text>().map { it.text }.toSet()
        assertEquals(setOf("9", "10"), texts, "both scores are drawn while it rolls")
        val old = host.root.find("digits-9").boundsInRoot.top
        val new = host.root.find("digits-10").boundsInRoot.top
        assertTrue(old < 0f, "the old score is on its way up and out, at $old")
        assertTrue(new > 0f && new < height, "the new score is on its way up and in, at $new")

        play(200)
        assertEquals(listOf("10"), canvas.only<DrawCall.Text>().map { it.text })
        assertEquals(0f, host.root.find("digits-10").boundsInRoot.top)
    }

    @Test
    fun `a slide on the world clock holds still while the game is paused`() {
        host.clocks.register(Clock.World)
        var card by mutableStateOf(0)
        show {
            AnimatedContent(card, transition = forwardOrBack, clock = Clock.World) { shown -> Card(shown) {} }
        }
        card = 1
        play(150)
        host.clocks.stop(Clock.World)
        frames(2)
        val frozen = left("card-1")
        assertTrue(frozen in 20f..180f, "under way before the pause, at $frozen")
        play(2000)
        assertEquals(frozen, left("card-1"), "the world is paused, so the slide is")
        host.clocks.start(Clock.World)
        play(500)
        assertEquals(0f, left("card-1"))
        assertNull(host.root.findOrNull("card-0"))
    }

    // --- scale and fade -------------------------------------------------------------------------------

    @Test
    fun `a page can grow in as the old one swells and fades away`() {
        var page by mutableStateOf(0)
        show {
            AnimatedContent(
                page,
                transition = { _, _ ->
                    scaleIn(from = 0.5f, spec = Tween(200, easing = Easings.Linear)) togetherWith
                        scaleOut(to = 1.5f, spec = Tween(200, easing = Easings.Linear)) + fadeOut(spec = Tween(200, easing = Easings.Linear))
                },
            ) { shown -> Box(Modifier.size(100f).testTag("page-$shown").background(Colours[shown])) }
        }
        page = 1
        frame()
        assertEquals(50f, host.root.find("page-1").boundsInRoot.width, "arriving at half its size")
        play(100)
        val growing = host.root.find("page-1").boundsInRoot.width
        val swelling = host.root.find("page-0").boundsInRoot.width
        assertTrue(growing in 60f..90f, "growing, at $growing")
        assertTrue(swelling in 110f..140f, "the old page swelling, at $swelling")
        // A scaled page is drawn as a picture of itself, so the fade is on the picture.
        val fading = canvas.only<DrawCall.Layer>().map { it.alpha }
        assertTrue(fading.any { it in 0.2f..0.8f }, "and fading, at $fading")
        play(200)
        assertEquals(100f, host.root.find("page-1").boundsInRoot.width)
        assertNull(host.root.findOrNull("page-0"))
    }

    // --- size -----------------------------------------------------------------------------------------

    @Test
    fun `the space grows from a small page to a big one instead of jumping and shrinks back`() {
        var big by mutableStateOf(false)
        val spec = Tween(200, easing = Easings.Linear)
        show {
            Column {
                AnimatedContent(
                    big,
                    Modifier.testTag("space"),
                    transition = { _, _ -> (fadeIn(spec = spec) togetherWith fadeOut(spec = spec)).using(spec) },
                ) { isBig ->
                    if (isBig) Box(Modifier.size(200f, 150f).testTag("big")) else Box(Modifier.size(100f, 50f).testTag("small"))
                }
                Box(Modifier.size(10f).testTag("below"))
            }
        }
        assertEquals(Size(100f, 50f), host.root.find("space").boundsInRoot.size)

        big = true
        play(100)
        val half = host.root.find("space").boundsInRoot.size
        assertTrue(half.width in 120f..180f, "half way wide, at ${half.width}")
        assertTrue(half.height in 70f..130f, "half way tall, at ${half.height}")
        assertEquals(half.height, host.root.find("below").boundsInRoot.top, "and what is under it moves down with it")

        play(300)
        assertEquals(Size(200f, 150f), host.root.find("space").boundsInRoot.size)

        big = false
        play(100)
        val shrinking = host.root.find("space").boundsInRoot.size
        assertTrue(shrinking.width in 120f..180f, "shrinking back, at ${shrinking.width}")
        play(300)
        assertEquals(Size(100f, 50f), host.root.find("space").boundsInRoot.size)
    }

    @Test
    fun `with no size animation the space is as big as the biggest page while they change`() {
        var big by mutableStateOf(false)
        show {
            AnimatedContent(
                big,
                Modifier.testTag("space"),
                contentAlignment = Alignment.Centre,
                transition = { _, _ -> slideDown(Tween(200)).using(null) },
            ) { isBig ->
                if (isBig) Box(Modifier.size(200f, 150f).testTag("big")) else Box(Modifier.size(100f, 50f).testTag("small"))
            }
        }
        big = true
        frame()
        assertEquals(Size(200f, 150f), host.root.find("space").boundsInRoot.size, "the frame it changes")
        play(400)
        big = false
        play(100)
        assertEquals(Size(200f, 150f), host.root.find("space").boundsInRoot.size, "still, while the big page leaves")
        assertEquals(50f, host.root.find("small").boundsInRoot.left, "the small page centred in it")
        play(300)
        assertEquals(Size(100f, 50f), host.root.find("space").boundsInRoot.size, "and just the page once it has gone")
    }

    // --- cost and keys --------------------------------------------------------------------------------

    @Test
    fun `settled animated content costs no frames before or after a slide`() {
        var card by mutableStateOf(0)
        show {
            AnimatedContent(card, transition = forwardOrBack) { shown -> Box(Modifier.size(40f).background(Colours[shown])) }
        }
        repeat(60) { assertFalse(frame(), "frame $it redrew a settled page") }
        card = 1
        assertTrue(frame(), "a change is drawn")
        play(600)
        repeat(60) { assertFalse(frame(), "frame $it redrew a page that had finished sliding in") }
        assertEquals(listOf(Colours[1]), canvas.only<DrawCall.Rectangle>().map { it.colour })
    }

    @Test
    fun `a change with the same key redraws at once and asks for no transition`() {
        var hp by mutableStateOf(Pair("ALIVE", 10))
        var asked = 0
        show {
            AnimatedContent(hp, contentKey = { it.first }, transition = { _, _ -> asked++; slideUp() }) {
                // A fixed size, so the shorter number does not start the space shrinking.
                Text("${it.first} ${it.second}", Modifier.size(120f, 20f))
            }
        }
        hp = hp.copy(second = 3)
        frame()
        assertEquals(listOf("ALIVE 3"), canvas.only<DrawCall.Text>().map { it.text })
        assertEquals(0, asked)
        assertFalse(host.clocks.isAnimating)

        hp = Pair("DOWN", 0)
        frame()
        assertEquals(1, asked)
    }

    @Test
    fun `a button on a card sliding out is clicked where it is drawn`() {
        var card by mutableStateOf(0)
        var clicks = 0
        show {
            AnimatedContent(card, transition = { _, _ -> slideLeft(Tween(400, easing = Easings.Linear)).using(null) }) { shown ->
                Row(Modifier.size(200f, 100f).testTag("card-$shown")) {
                    Button("HIT $shown", onClick = { if (shown == 0) clicks++ }, modifier = Modifier.testTag("hit-$shown"))
                }
            }
        }
        card = 1
        play(100)
        val hit = host.root.find("hit-0").boundsInRoot
        assertTrue(hit.left < -20f, "the button has slid left, to ${hit.left}")
        // Only the part still on screen can be clicked: the carousel cuts it off at its edge.
        click(Offset(hit.right - 2f, hit.centre.y))
        frame()
        assertEquals(1, clicks, "a click on the part of the button still showing reaches it")
    }

    @Test
    fun `a click on the other side of the screen leaves a settled carousel exactly as it was`() {
        var card by mutableStateOf(0)
        uiTest(Size(400f, 300f)) {
            var taps by remember { mutableStateOf(0) }
            val tapsNow = taps
            Column {
                Button("TAP $taps", onClick = { taps++ }, modifier = Modifier.testTag("tap"))
                AnimatedContent(
                    card,
                    Modifier.testTag("carousel"),
                    // A new transform, and a new spec in it, every change. The lambda holds this tap's
                    // count, so each tap is a new lambda and the carousel really does recompose.
                    transition = { _, _ -> slideLeft(Tween(200 + tapsNow * 0)).using(Tween(200)) },
                ) { shown -> Box(Modifier.size(100f, 50f).testTag("card-$shown").background(Colours[shown])) }
            }
        }.use { ui ->
            card = 1
            ui.advanceBy(600)
            ui.assertDoesNotExist("card-0")
            val carousel = ui.node("carousel").modifier
            val page = ui.node("card-1").parent!!.modifier

            ui.click("tap")
            ui.assertText("tap", "TAP 1")
            assertSame(carousel, ui.node("carousel").modifier, "an equal chain on the carousel is not even stored")
            assertSame(page, ui.node("card-1").parent!!.modifier, "nor on the page")
            assertFalse(ui.render(), "and a still carousel has nothing to redo")
        }
    }

    // --- edges ----------------------------------------------------------------------------------------

    @Test
    fun `a page that shows nothing slides to and from a real one`() {
        var shown by mutableStateOf(false)
        val spec = Tween(200, easing = Easings.Linear)
        show {
            Column {
                AnimatedContent(shown, Modifier.testTag("space"), transition = { _, _ -> slideUp(spec).using(spec) }) {
                    if (it) Box(Modifier.size(100f, 60f).testTag("panel").background(Colours[1]))
                }
                Box(Modifier.size(10f).testTag("below"))
            }
        }
        assertEquals(Size.Zero, host.root.find("space").boundsInRoot.size, "nothing to show is no size at all")

        shown = true
        play(100)
        val growing = host.root.find("space").boundsInRoot.height
        assertTrue(growing in 20f..40f, "the space grows out of nothing, at $growing")
        play(300)
        assertEquals(Size(100f, 60f), host.root.find("space").boundsInRoot.size)
        assertEquals(60f, host.root.find("below").boundsInRoot.top)

        shown = false
        play(400)
        assertNull(host.root.findOrNull("panel"))
        assertEquals(Size.Zero, host.root.find("space").boundsInRoot.size, "and back to nothing once it has gone")
        assertFalse(host.clocks.isAnimating, "with nothing left playing")
        repeat(10) { assertFalse(frame(), "frame $it redrew an empty space") }
    }

    @Test
    fun `a carousel taken off the screen part way through a slide lets go of its pages and stops asking for frames`() {
        var card by mutableStateOf(0)
        var open by mutableStateOf(true)
        show {
            Column {
                Button("CLOSE", onClick = { open = false }, modifier = Modifier.testTag("close"))
                if (open) {
                    AnimatedContent(card, transition = forwardOrBack) { shown -> Card(shown) { card = shown + 1 } }
                }
            }
        }
        click("next-0")
        play(100)
        assertTrue(host.clocks.isAnimating, "under way")
        assertNotNull(host.root.findOrNull("card-0"))

        click("close")
        frame()
        assertNull(host.root.findOrNull("card-0"), "the leaving card goes with the carousel")
        assertNull(host.root.findOrNull("card-1"), "and so does the arriving one")
        frame()
        assertFalse(host.clocks.isAnimating, "no slide carries on with nothing to move")
        repeat(10) { assertFalse(frame(), "frame $it redrew a carousel that is gone") }

        open = true
        frame()
        assertEquals(0f, host.root.find("card-1").boundsInRoot.left, "brought back, it starts settled on the card it was showing")
        assertNull(host.root.findOrNull("card-0"))
    }

    @Test
    fun `a slide inside a sliding page moves by its own size while the outer page moves by its own`() {
        var outer by mutableStateOf(0)
        var inner by mutableStateOf(0)
        val spec = Tween(200, easing = Easings.Linear)
        show {
            AnimatedContent(outer, transition = { _, _ -> slideLeft(spec) }) { page ->
                Column(Modifier.size(200f, 100f).testTag("page-$page")) {
                    AnimatedContent(inner, transition = { _, _ -> slideUp(spec) }) { digit ->
                        Box(Modifier.size(40f, 20f).testTag("digit-$page-$digit"))
                    }
                }
            }
        }
        outer = 1
        inner = 1
        play(100)
        val digit = host.root.find("digit-1-1").boundsInRoot
        val page = host.root.find("page-1").boundsInRoot
        assertTrue(page.left in 70f..130f, "the outer page half a page in from the right, at ${page.left}")
        assertEquals(page.left, digit.left, "the new page's digit rides along with its page")
        assertEquals(0f, digit.top, "arriving with the page, it has no older digit to roll from")
        val rolling = host.root.find("digit-0-1").boundsInRoot.top - host.root.find("page-0").boundsInRoot.top
        assertTrue(rolling in 5f..15f, "the old page's digit rolls up by half its own 20, not the page's 100, at $rolling")

        play(300)
        assertNull(host.root.findOrNull("page-0"))
        assertEquals(0f, host.root.find("digit-1-1").boundsInRoot.left)
        assertEquals(0f, host.root.find("digit-1-1").boundsInRoot.top)
        assertFalse(host.clocks.isAnimating)
    }

    // --- every way in ---------------------------------------------------------------------------------

    @Test
    fun `a pad and the keyboard page through a carousel and focus follows onto each card`() {
        var card by mutableStateOf(0)
        uiTest(Size(400f, 300f), onBack = { if (card > 0) card-- }) {
            AnimatedContent(card, Modifier.testTag("carousel"), transition = forwardOrBack, clock = Clock.World) { shown ->
                Column(Modifier.size(200f, 100f).testTag("card-$shown")) {
                    Text("CARD $shown")
                    Button("NEXT", onClick = { card = shown + 1 }, initialFocus = true, modifier = Modifier.testTag("next-$shown"))
                }
            }
        }.use { ui ->
            ui.assertFocused("next-0")

            // Every action waits out whatever is playing, so pause the world to catch the slide.
            ui.host.clocks.register(Clock.World)
            ui.host.clocks.stop(Clock.World)
            ui.pad(GamepadButton.South)
            assertEquals(1, card, "the pad pressed next")
            ui.assertExists("card-0")
            assertEquals(200f, ui.node("card-1").boundsInRoot.left, "the new card waits to the right while the world is paused")

            ui.host.clocks.start(Clock.World)
            ui.advanceBy(400)
            ui.assertDoesNotExist("card-0")
            assertEquals(0f, ui.node("card-1").boundsInRoot.left)
            ui.assertFocused("next-1")
            ui.assertText("card-1", "CARD 1\nNEXT")

            // The keyboard goes forward too.
            ui.key(Key.Enter)
            ui.advanceBy(400)
            ui.assertText("card-2", "CARD 2\nNEXT")
            ui.assertFocused("next-2")

            // And back slides the other way: catch it part way with the world paused again.
            ui.host.clocks.stop(Clock.World)
            ui.pad(GamepadButton.East)
            assertEquals(1, card)
            assertEquals(-200f, ui.node("card-1").boundsInRoot.left, "going back, the card comes from the left")
            ui.host.clocks.start(Clock.World)
            ui.advanceBy(400)
            ui.assertDoesNotExist("card-2")
            ui.assertFocused("next-1")

            // The mouse too.
            ui.click("next-1")
            ui.advanceBy(400)
            ui.assertText("card-2", "CARD 2\nNEXT")
        }
    }

    // --- the pages ------------------------------------------------------------------------------------

    @Test
    fun `each change hands its enter to the page shown and its exit to every other page`() {
        val first = slideLeft()
        val second = slideRight()
        var next = first
        val pages = ContentPages("A", "A")
        val transition: (String, String) -> ContentTransform = { _, _ -> next }

        pages.show("A", "A", transition)
        assertNull(pages.change, "showing the page already shown is not a change")

        pages.show("B", "B", transition)
        assertEquals(first, pages.change)
        val (a, b) = pages.all
        assertEquals(first.exit, a.exit)
        assertEquals(first.enter, b.enter)

        next = second
        pages.show("A", "A", transition)
        assertEquals(listOf<Any?>("A", "B"), pages.all.map { it.key }, "turning round keeps a page's place")
        assertEquals(second.enter, a.enter, "the page gone back to arrives on the new change's enter")
        assertEquals(second.exit, b.exit, "and the page it was leaving for goes on its exit")
    }

    private companion object {
        val Colours = listOf(Colour.rgb(0x3366CC), Colour.rgb(0xCC3333), Colour.rgb(0x33CC66), Colour.rgb(0xCCCC33))
    }
}
