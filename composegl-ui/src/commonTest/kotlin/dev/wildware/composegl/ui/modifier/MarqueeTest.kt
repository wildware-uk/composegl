package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Marquee: a name too long for its slot, scrolling round inside it.
 *
 * Every screen here is composed for real by the test harness and driven the way a player drives
 * it — the pad and the keys moving focus between hotbar items, Enter pressing a button, the mouse
 * clicking one — with time passed on the host's clocks. What is checked is what is on the screen:
 * the boxes layout gave out, where the text was drawn and what it was cut to, and whether a frame
 * was asked for at all.
 *
 * The headless fonts are monospaced, so at size 20 every character is 12 wide and the widths below
 * are exact.
 */
class MarqueeTest {

    // --- screens ---------------------------------------------------------------------------------

    /** A now-playing strip: the title in a 160-wide slot, the time beside it. */
    private fun player(marquee: Modifier = Modifier.marquee(), title: () -> String = { LongTitle }): UiTest =
        uiTest(Size(400f, 160f)) {
            Row(Modifier.offset(20f, 30f)) {
                Text(title(), Modifier.testTag("title").width(160f).then(marquee), textStyle = Face)
                Text("3:41", Modifier.testTag("time"), textStyle = Face)
            }
        }

    /** Every run of [text] a whole frame draws, in the order it was drawn. */
    private fun UiTest.drawn(text: String = LongTitle): List<DrawCall.Text> =
        record().only<DrawCall.Text>().filter { it.text == text }

    private fun UiTest.record(): RecordingCanvas {
        val canvas = RecordingCanvas(Rect(0f, 0f, size.width, size.height))
        DrawPass(canvas).draw(root)
        canvas.assertBalanced()
        return canvas
    }

    /** Where the first copy of the title was drawn from. */
    private fun UiTest.titleX(text: String = LongTitle): Float = drawn(text).first().at.x

    // --- a name that fits ------------------------------------------------------------------------

    @Test
    fun `a title that fits sits still in its slot and asks for no frames`() {
        player { ShortTitle }.use { ui ->
            assertEquals(Rect(20f, 30f, 180f, 55f), ui.node("title").boundsInRoot)
            assertEquals(20f, ui.titleX(ShortTitle))

            ui.advanceBy(5_000)
            assertEquals(20f, ui.titleX(ShortTitle), "nothing to scroll, so nothing moved")
            val call = ui.drawn(ShortTitle).single()
            assertEquals(Rect(0f, 0f, 400f, 160f), call.clip, "and nothing was cut either")
            repeat(120) { assertFalse(ui.render(), "a marquee with nothing to do costs no frames") }
        }
    }

    @Test
    fun `a centred title that fits stays centred`() {
        uiTest(Size(400f, 160f)) {
            Text(
                ShortTitle,
                Modifier.offset(20f, 30f).testTag("title").width(160f).marquee(),
                textStyle = Face,
                align = HorizontalAlignment.Centre,
            )
        }.use { ui ->
            // Four characters is 48 wide, in the middle of 160.
            assertEquals(20f + (160f - 48f) / 2f, ui.titleX(ShortTitle))
        }
    }

    // --- a name that does not --------------------------------------------------------------------

    @Test
    fun `a long title rests for its delay and then slides left at its speed`() {
        player().use { ui ->
            assertEquals(listOf(20f), ui.drawn().map { it.at.x }, "one copy, at rest, on one line")

            ui.advanceBy(1_000)
            assertEquals(20f, ui.titleX(), "still resting a second in")

            ui.advanceBy(1_500)
            val firstX = ui.titleX()
            val firstAt = ui.nanos
            assertTrue(firstX < 20f, "moving once the delay is out, and at $firstX")

            ui.advanceBy(1_000)
            val secondX = ui.titleX()
            val seconds = (ui.nanos - firstAt) / 1_000_000_000f
            assertEquals(30f, (firstX - secondX) / seconds, 0.05f, "thirty units a second, to the left")
        }
    }

    @Test
    fun `the slot keeps its width and the neighbour never moves while the title scrolls`() {
        player().use { ui ->
            val slot = ui.node("title").boundsInRoot
            val time = ui.node("time").boundsInRoot
            assertEquals(160f, slot.width, "the slot, not the 276 the title would like")
            assertEquals(25f, slot.height, "one line tall: the title is not wrapped")
            assertEquals(180f, time.left)

            repeat(6) {
                ui.advanceBy(700)
                assertEquals(slot, ui.node("title").boundsInRoot)
                assertEquals(time, ui.node("time").boundsInRoot)
                assertEquals(180f, ui.titleX("3:41"), "the time is drawn where it always was")
            }
            assertTrue(ui.titleX() < 20f)
        }
    }

    @Test
    fun `the scrolling title is cut to its slot`() {
        player().use { ui ->
            ui.advanceBy(3_000)
            val slot = ui.node("title").boundsInRoot
            ui.drawn().forEach { assertEquals(slot, it.clip, "every copy is cut to the slot") }
            assertEquals(Rect(0f, 0f, 400f, 160f), ui.drawn("3:41").single().clip, "and nothing else is")
        }
    }

    @Test
    fun `a copy follows the title round so the loop has no seam and each trip rests again`() {
        player().use { ui ->
            // One trip is the 276 of the title and the 32 of the gap, 308, at thirty a second.
            ui.advanceBy(1_500 + 8_000)
            val copies = ui.drawn()
            assertEquals(2, copies.size, "the copy has come into the slot: $copies")
            assertEquals(308f, copies[1].at.x - copies[0].at.x, 0.01f, "one title and one gap behind")
            assertTrue(copies[1].at.x in 20f..180f, "and it is inside the slot, at ${copies[1].at.x}")

            // Past the end of the trip and inside the next delay: back to one copy, at rest, which is
            // exactly where the copy was about to arrive.
            ui.advanceBy(2_500)
            assertEquals(listOf(20f), ui.drawn().map { it.at.x })
            ui.advanceBy(500)
            assertEquals(20f, ui.titleX(), "resting at the start of the second trip too")
            ui.advanceBy(1_500)
            assertTrue(ui.titleX() < 20f, "and off again")
        }
    }

    @Test
    fun `redraws happen only while the title is moving`() {
        player().use { ui ->
            repeat(40) { assertFalse(ui.render(), "the delay changes nothing on the screen") }
            ui.advanceBy(1_500)
            repeat(40) { assertTrue(ui.render(), "every frame of movement is drawn") }
        }
    }

    @Test
    fun `a set number of trips ends at rest and stops asking for frames`() {
        player(Modifier.marquee(iterations = 1)).use { ui ->
            ui.advanceBy(3_000)
            assertTrue(ui.titleX() < 20f)

            ui.advanceBy(1_500 + 10_267 + 500)
            assertEquals(listOf(20f), ui.drawn().map { it.at.x }, "back at the start after its one trip")
            repeat(200) { assertFalse(ui.render(), "and resting there for good, for free") }
            assertEquals(20f, ui.titleX())
        }
    }

    // --- clocks ----------------------------------------------------------------------------------

    @Test
    fun `a world clock marquee freezes with the game and carries on after`() {
        player(Modifier.marquee(clock = Clock.World)).use { ui ->
            ui.advanceBy(2_500)
            ui.host.clocks.stop(Clock.World)
            ui.advanceBy(100)
            val frozen = ui.titleX()
            assertTrue(frozen < 20f)

            ui.advanceBy(3_000)
            assertEquals(frozen, ui.titleX(), "paused with the game")
            repeat(20) { assertFalse(ui.render(), "and drawing nothing while it is") }

            ui.host.clocks.start(Clock.World)
            ui.advanceBy(500)
            assertTrue(ui.titleX() < frozen, "carrying on from where it stopped")
            assertTrue(ui.titleX() > frozen - 20f, "rather than jumping to where it would have been")
        }
    }

    @Test
    fun `a ui clock marquee keeps going while the world is paused`() {
        player().use { ui ->
            ui.host.clocks.stop(Clock.World)
            ui.advanceBy(3_000)
            assertTrue(ui.titleX() < 20f)
        }
    }

    // --- driven by a player ----------------------------------------------------------------------

    /** Two hotbar items and the tooltip naming whichever one has focus. */
    private fun hotbar(): UiTest = uiTest(Size(400f, 200f)) {
        val sword = remember { InteractionState() }
        val potion = remember { InteractionState() }
        Column(Modifier.offset(20f, 20f)) {
            Row {
                Button("S", onClick = {}, modifier = Modifier.testTag("sword"), initialFocus = true, interaction = sword)
                Button("P", onClick = {}, modifier = Modifier.testTag("potion"), interaction = potion)
            }
            val name = if (potion.isFocused) Potion else Sword
            Text(name, Modifier.testTag("tooltip").width(160f).marquee(), textStyle = Face)
        }
    }

    @Test
    fun `the pad moves focus along the hotbar and the tooltip name starts again from rest`() {
        hotbar().use { ui ->
            ui.assertFocused("sword")
            val left = ui.node("tooltip").boundsInRoot.left
            ui.advanceBy(3_000)
            assertTrue(ui.titleX(Sword) < left, "the sword's long name is scrolling")

            ui.pad(GamepadButton.DpadRight)
            ui.assertFocused("potion")
            assertEquals(Potion, ui.text("tooltip"))
            assertEquals(left, ui.titleX(Potion), "a short name sits in the slot")
            ui.advanceBy(4_000)
            assertEquals(left, ui.titleX(Potion), "and never moves")
            repeat(30) { assertFalse(ui.render()) }

            ui.pad(GamepadButton.DpadLeft)
            ui.assertFocused("sword")
            assertEquals(left, ui.titleX(Sword), "back on the sword, its name starts from the beginning")
            ui.advanceBy(1_000)
            assertEquals(left, ui.titleX(Sword), "with the delay first")
        }
    }

    @Test
    fun `the arrow keys do the same as the pad`() {
        hotbar().use { ui ->
            val left = ui.node("tooltip").boundsInRoot.left
            ui.advanceBy(3_000)
            val scrolled = ui.titleX(Sword)
            assertTrue(scrolled < left)

            ui.key(Key.Right)
            ui.assertFocused("potion")
            assertEquals(left, ui.titleX(Potion))

            ui.key(Key.Left)
            ui.assertFocused("sword")
            assertEquals(left, ui.titleX(Sword))
        }
    }

    @Test
    fun `the harness settles on a screen whose marquee never stops and a click still lands`() {
        uiTest(Size(400f, 200f)) {
            var plays by remember { mutableStateOf(0) }
            Column(Modifier.offset(20f, 20f)) {
                Text(LongTitle, Modifier.testTag("title").width(160f).marquee(delayMillis = 0), textStyle = Face)
                Button("PLAY", onClick = { plays++ }, modifier = Modifier.testTag("play"))
                Text("$plays", Modifier.testTag("plays"), textStyle = Face)
            }
        }.use { ui ->
            ui.advanceBy(2_000)
            val before = ui.titleX()
            assertTrue(before < 20f, "scrolling when the click comes")

            ui.click("play")
            ui.assertText("plays", "1")
            ui.key(Key.Enter)
            assertTrue(ui.titleX() < before, "and still scrolling on after it, not started over")
        }
    }

    @Test
    fun `enter on a button takes the marquee off and the title goes back to wrapping still`() {
        uiTest(Size(400f, 200f)) {
            var scrolling by remember { mutableStateOf(true) }
            Column(Modifier.offset(20f, 20f)) {
                Button("STOP", onClick = { scrolling = false }, modifier = Modifier.testTag("stop"), initialFocus = true)
                Text(
                    LongTitle,
                    Modifier.testTag("title").width(160f).then(if (scrolling) Modifier.marquee() else Modifier),
                    textStyle = Face,
                )
            }
        }.use { ui ->
            ui.advanceBy(3_000)
            val slot = ui.node("title").boundsInRoot
            assertTrue(ui.record().only<DrawCall.Text>().first { it.text != "STOP" }.at.x < slot.left)

            ui.key(Key.Enter)
            val title = ui.record().only<DrawCall.Text>().filter { it.text != "STOP" }
            assertTrue(title.all { it.at.x == slot.left }, "drawn from the slot's edge again: $title")
            assertTrue(ui.node("title").boundsInRoot.height > slot.height, "and wrapped, as a plain label does")
            repeat(120) { assertFalse(ui.render(), "with no marquee left asking for frames") }
        }
    }

    @Test
    fun `a title taken off the screen mid scroll comes back from rest`() {
        var showing by mutableStateOf(true)
        uiTest(Size(400f, 160f)) {
            if (showing) Text(LongTitle, Modifier.offset(20f, 30f).width(160f).marquee(), textStyle = Face)
        }.use { ui ->
            ui.advanceBy(3_000)
            assertTrue(ui.titleX() < 20f)

            showing = false
            ui.advanceBy(100)
            assertTrue(ui.drawn().isEmpty())
            repeat(60) { assertFalse(ui.render(), "a marquee that has gone waits on nothing") }

            showing = true
            ui.advanceBy(100)
            assertEquals(listOf(20f), ui.drawn().map { it.at.x })
        }
    }

    @Test
    fun `the next track of the same length starts again from rest`() {
        uiTest(Size(400f, 200f)) {
            var track by remember { mutableStateOf(0) }
            Column(Modifier.offset(20f, 20f)) {
                Button("NEXT", onClick = { track++ }, modifier = Modifier.testTag("next"), initialFocus = true)
                Text(Tracks[track % Tracks.size], Modifier.testTag("title").width(160f).marquee(), textStyle = Face)
            }
        }.use { ui ->
            val left = ui.node("title").boundsInRoot.left
            ui.advanceBy(3_000)
            assertTrue(ui.titleX(Tracks[0]) < left, "the first track is scrolling")

            ui.key(Key.Enter)
            assertEquals(Tracks[1], ui.text("title"))
            assertEquals(left, ui.titleX(Tracks[1]), "a new song as wide as the last still starts from the beginning")
            ui.advanceBy(1_000)
            assertEquals(left, ui.titleX(Tracks[1]), "with the delay first")
            ui.advanceBy(1_000)
            assertTrue(ui.titleX(Tracks[1]) < left, "and then off")
        }
    }

    @Test
    fun `hovering the title lights it up and it carries on scrolling from where it was`() {
        uiTest(Size(400f, 160f)) {
            val hover = remember { InteractionState() }
            Text(
                LongTitle,
                Modifier.offset(20f, 30f).testTag("title").width(160f).interaction(hover).clickable {}
                    .marquee(delayMillis = 0),
                textStyle = Face,
                colour = if (hover.isHovered) Lit else Dim,
            )
        }.use { ui ->
            ui.advanceBy(2_000)
            val before = ui.drawn().first()
            assertEquals(Dim, before.colour)
            assertTrue(before.at.x < 0f, "well under way: ${before.at.x}")

            ui.moveTo(Offset(100f, 40f))
            val after = ui.drawn().first()
            assertEquals(Lit, after.colour, "the pointer lit the title up")
            assertTrue(after.at.x <= before.at.x, "and it did not go back to the start: ${after.at.x}")

            ui.advanceBy(500)
            assertTrue(ui.titleX() < after.at.x, "it is still moving")
        }
    }

    @Test
    fun `the harness reads a scrolling title once even while its copy is in view`() {
        player().use { ui ->
            ui.advanceBy(1_500 + 8_000)
            assertEquals(2, ui.drawn().size, "both copies are on the screen")
            ui.assertText("title", LongTitle)
        }
    }

    // --- edges -----------------------------------------------------------------------------------

    @Test
    fun `an empty title sits still and asks for no frames`() {
        player { "" }.use { ui ->
            assertEquals(Rect(20f, 30f, 180f, 55f), ui.node("title").boundsInRoot)
            ui.advanceBy(4_000)
            repeat(60) { assertFalse(ui.render(), "nothing to scroll") }
            assertEquals(180f, ui.titleX("3:41"))
        }
    }

    @Test
    fun `contents that take all the room they are offered sit still rather than scroll for ever`() {
        uiTest(Size(400f, 160f)) {
            Row(Modifier.offset(20f, 30f)) {
                Layout(
                    Modifier.testTag("greedy").width(160f).marquee(delayMillis = 0),
                    draw = { rect(it, Red) },
                    measurePolicy = MeasurePolicy { _, constraints -> layout(constraints.maxWidth, 20f) {} },
                )
                Text("3:41", Modifier.testTag("time"), textStyle = Face)
            }
        }.use { ui ->
            ui.advanceBy(3_000)
            assertEquals(Rect(20f, 30f, 180f, 50f), ui.node("greedy").boundsInRoot)
            assertEquals(180f, ui.node("time").boundsInRoot.left)
            val red = ui.record().only<DrawCall.Rectangle>().single { it.colour == Red }
            assertEquals(20f, red.rect.left, "drawn where layout put it")
            repeat(60) { assertFalse(ui.render(), "and asking for no frames") }
        }
    }

    @Test
    fun `a slot of no width shows nothing of its title and the neighbour stays put`() {
        uiTest(Size(400f, 160f)) {
            Row(Modifier.offset(20f, 30f)) {
                Text(LongTitle, Modifier.testTag("title").width(0f).marquee(delayMillis = 0), textStyle = Face)
                Text("3:41", Modifier.testTag("time"), textStyle = Face)
            }
        }.use { ui ->
            ui.advanceBy(2_000)
            assertEquals(0f, ui.node("title").boundsInRoot.width)
            assertEquals(20f, ui.node("time").boundsInRoot.left)
            ui.drawn().forEach { assertTrue(it.clip.isEmpty, "cut to nothing: ${it.clip}") }
        }
    }

    @Test
    fun `a marquee inside a scrolling marquee is cut to both slots`() {
        uiTest(Size(400f, 160f)) {
            Row(Modifier.offset(20f, 20f).testTag("outer").width(120f).marquee(speed = 60f, delayMillis = 0)) {
                Text(LongTitle, Modifier.testTag("inner").width(100f).marquee(delayMillis = 0), textStyle = Face)
                Box(Modifier.size(100f, 25f).background(Red))
            }
        }.use { ui ->
            ui.advanceBy(1_000)
            val outer = ui.node("outer").boundsInRoot
            val inner = ui.node("inner").boundsInRoot
            val copies = ui.drawn()
            assertTrue(copies.isNotEmpty())
            copies.forEach {
                assertTrue(it.clip.left >= outer.left && it.clip.right <= outer.right, "${it.clip} inside $outer")
                assertTrue(it.clip.width <= inner.width, "${it.clip} no wider than the inner slot")
            }
            ui.record().only<DrawCall.Rectangle>().filter { it.colour == Red }.forEach {
                assertEquals(outer, it.clip, "the box is cut to the outer slot")
            }
        }
    }

    // --- boxes -----------------------------------------------------------------------------------

    @Test
    fun `padding keeps the scrolling title inside the padded box`() {
        uiTest(Size(400f, 160f)) {
            Text(
                LongTitle,
                Modifier.offset(20f, 30f).testTag("title").width(160f).background(Panel).padding(horizontal = 10f).marquee(),
                textStyle = Face,
            )
        }.use { ui ->
            val slot = ui.node("title").boundsInRoot
            assertEquals(30f, ui.titleX(), "at rest, inside the padding")
            ui.advanceBy(3_000)
            val canvas = ui.record()
            val window = Rect(slot.left + 10f, slot.top, slot.right - 10f, slot.bottom)
            canvas.only<DrawCall.Text>().forEach { assertEquals(window, it.clip) }
            val background = canvas.only<DrawCall.Rectangle>().single { it.colour == Panel }
            assertEquals(slot, background.rect, "the background still fills the whole slot")
        }
    }

    @Test
    fun `a title lined up by its baseline stays at that height while it scrolls`() {
        fun screen(marquee: Modifier) = uiTest(Size(400f, 160f)) {
            Text(
                LongTitle,
                Modifier.offset(20f, 30f).testTag("title").width(160f).paddingFromBaseline(top = 40f).then(marquee),
                textStyle = Face,
            )
        }
        // Where the words go with no marquee at all: moved down by the room `paddingFrom` made.
        val still = screen(Modifier).use { ui -> ui.drawn().single().at.y }
        assertTrue(still > 30f, "paddingFrom moved the words down, to $still")

        screen(Modifier.marquee(delayMillis = 0)).use { ui ->
            assertEquals(still, ui.drawn().first().at.y, "at rest")
            ui.advanceBy(2_000)
            val calls = ui.drawn()
            assertTrue(calls.first().at.x < 20f, "scrolling by now")
            calls.forEach { assertEquals(still, it.at.y, "and every copy on the same line as before") }
        }
    }

    @Test
    fun `a row of boxes scrolls as one and is cut to the slot`() {
        uiTest(Size(400f, 160f)) {
            Row(Modifier.offset(20f, 20f).testTag("strip").width(120f).marquee(speed = 60f, delayMillis = 500)) {
                Box(Modifier.testTag("red").size(100f, 40f).background(Red))
                Box(Modifier.testTag("green").size(100f, 40f).background(Green))
                Box(Modifier.size(100f, 40f).background(Blue))
            }
        }.use { ui ->
            fun rect(colour: Colour) = ui.record().only<DrawCall.Rectangle>().first { it.colour == colour }

            assertEquals(Rect(20f, 20f, 140f, 60f), ui.node("strip").boundsInRoot)
            assertEquals(20f, rect(Red).rect.left)
            assertEquals(220f, rect(Blue).rect.left)

            ui.advanceBy(1_500)
            val red = rect(Red)
            val green = rect(Green)
            assertTrue(red.rect.left < 0f, "a second of travel at sixty moved it about sixty: ${red.rect}")
            assertEquals(100f, green.rect.left - red.rect.left, 0.01f, "the boxes move together")
            assertEquals(Rect(20f, 20f, 140f, 60f), green.clip)
            assertEquals(120f, ui.node("green").boundsInRoot.left, "where the box is laid out has not moved")
        }
    }

    @Test
    fun `what the title paints stays inside its slot`() {
        player().use { ui ->
            val slot = ui.node("title").boundsInRoot
            listOf(0L, 3_000L, 7_000L).forEach { wait ->
                ui.advanceBy(wait)
                val painted = assertNotNull(ui.node("title").paintedInRoot)
                assertTrue(painted.left >= slot.left && painted.right <= slot.right, "$painted is inside $slot")
            }
            player { ShortTitle }.use { still ->
                val painted = assertNotNull(still.node("title").paintedInRoot)
                assertEquals(20f + 48f, painted.right, "a title that fits paints just its letters")
            }
        }
    }

    // --- the numbers -----------------------------------------------------------------------------

    @Test
    fun `where the contents are comes from the time alone`() {
        val marquee = MarqueeElement(speed = 30f, delayMillis = 1_000, spacing = 10f, iterations = MarqueeForever, clock = Clock.Ui)
        // One trip is 100 units at thirty a second, three and a third seconds, after a second's rest.
        assertEquals(0f, marquee.offsetAt(0L, 100f))
        assertEquals(0f, marquee.offsetAt(millis(999), 100f))
        assertEquals(30f, marquee.offsetAt(millis(2_000), 100f), 0.001f)
        assertEquals(99f, marquee.offsetAt(millis(4_300), 100f), 0.01f)
        assertEquals(0f, marquee.offsetAt(millis(4_400), 100f), "resting again at the start of trip two")
        assertEquals(30f, marquee.offsetAt(millis(4_334 + 2_000), 100f), 0.05f)
        assertEquals(0f, marquee.offsetAt(millis(2_000), 0f), "nothing to travel is no movement")
        assertFalse(marquee.isFinishedAt(millis(1_000_000_000), 100f), "for ever is for ever")

        val twice = marquee.copy(iterations = 2)
        assertFalse(twice.isFinishedAt(millis(8_600), 100f))
        assertTrue(twice.isFinishedAt(millis(8_700), 100f))
        assertEquals(0f, twice.offsetAt(millis(8_700 + 2_000), 100f), "and rests once it has finished")
    }

    @Test
    fun `a marquee that cannot move is refused`() {
        assertFailsWith<IllegalArgumentException> { Modifier.marquee(speed = 0f) }
        assertFailsWith<IllegalArgumentException> { Modifier.marquee(speed = -30f) }
        assertFailsWith<IllegalArgumentException> { Modifier.marquee(speed = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Modifier.marquee(delayMillis = -1) }
        assertFailsWith<IllegalArgumentException> { Modifier.marquee(spacing = -1f) }
        assertFailsWith<IllegalArgumentException> { Modifier.marquee(iterations = 0) }
        assertEquals(Modifier.marquee(), Modifier.marquee(), "the same marquee twice is the same element")
    }

    private fun millis(value: Long) = value * 1_000_000L

    private companion object {
        val Face = TextStyle(size = 20f)

        /** 23 characters, 276 wide: too long for a 160 slot. */
        const val LongTitle = "THE LONG DARK ROAD HOME"
        const val ShortTitle = "HOME"
        const val Sword = "SWORD OF A THOUSAND TRUTHS"
        const val Potion = "POTION"

        /** Two names of exactly the same width, so only the words tell them apart. */
        val Tracks = listOf("THE LONG DARK ROAD HOME", "A SHORT BRIGHT WAY BACK")

        val Dim = Colour.rgb(0x8090A0)
        val Lit = Colour.rgb(0xFFFFFF)
        val Panel = Colour.rgb(0x2C3545)
        val Red = Colour.rgb(0xFF0000)
        val Green = Colour.rgb(0x00FF00)
        val Blue = Colour.rgb(0x0000FF)
    }
}
