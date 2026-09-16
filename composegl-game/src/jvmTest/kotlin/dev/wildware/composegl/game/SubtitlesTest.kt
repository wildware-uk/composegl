package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinFormat
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideTextScale
import dev.wildware.composegl.ui.widget.Toggle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Subtitles and captions on a screen, driven the way a player drives them.
 *
 * The issue's four: a speaker with a colour of their own and captions for sounds, the player's own
 * size, background and speaker-name settings, a queue timed either by a clock or by the audio, and
 * words that wrap, stop at a line limit and read the right way round in a right-to-left language.
 *
 * The band is a readout rather than a control, so the input tests here are mostly about what it
 * must **not** do — take focus, or swallow the button press a player made in the middle of a fight.
 * The settings themselves are changed through real widgets with real clicks and real key presses,
 * because that is how they change in a game.
 */
class SubtitlesTest {

    private val screen = Rect(0f, 0f, 600f, 400f)
    private val host = UiHost()
    private val canvas = RecordingCanvas(screen)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear(screen)
        MeasurePass().run(host.root, Constraints.atMost(screen.right, screen.bottom))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) { content() }
        }
        frames(3)
    }

    /** The whole screen with a band at the bottom of it, which is where a game puts one. */
    private fun band(
        queue: SubtitleQueue,
        settings: SubtitleSettings = SubtitleSettings(),
        speakerColours: Map<String, Colour> = emptyMap(),
    ) = show {
        Box(Modifier.fillMaxSize()) {
            Subtitles(queue, Modifier.align(Alignment.BottomCentre), settings, speakerColours)
        }
    }

    /** The same band under a skin that knows a radio voice and a shout, for the per-line styles. */
    private fun voiced(queue: SubtitleQueue) = show {
        ProvideSkin(Voices) {
            Box(Modifier.fillMaxSize()) { Subtitles(queue, Modifier.align(Alignment.BottomCentre)) }
        }
    }

    private fun drawn() = canvas.calls.filterIsInstance<DrawCall.Text>()

    private fun texts() = drawn().map { it.text }

    private fun textOf(text: String): DrawCall.Text? = drawn().firstOrNull { it.text == text }

    /** The band behind the words: the widest rectangle drawn under them. */
    private fun bandRect(): DrawCall.Rectangle? =
        canvas.calls.filterIsInstance<DrawCall.Rectangle>().maxByOrNull { it.rect.right - it.rect.left }

    // --- saying something -----------------------------------------------------------------------

    @Test
    fun `a line goes up and comes down as its time runs out`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        band(subs)

        subs.show("We are through the gate.", speaker = "Mira", durationMillis = 400)
        frames(3)
        assertTrue(texts().contains("We are through the gate."), "it should be on screen: ${texts()}")

        frames(40, millis = 20)

        assertTrue(subs.isIdle, "it was still there long after its time ran out")
        assertTrue(texts().isEmpty(), "and drawn after it had gone: ${texts()}")
    }

    @Test
    fun `the speaker's name is drawn above the line in their own colour`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        band(subs, speakerColours = mapOf("Mira" to Colour(0xFF4CC2FF.toInt())))

        subs.show("Stay behind me.", speaker = "Mira", durationMillis = 10_000)
        frames(3)

        val name = checkNotNull(textOf("Mira")) { "the name should be drawn: ${texts()}" }
        val line = checkNotNull(textOf("Stay behind me."))
        assertTrue(name.at.y < line.at.y, "the name belongs above what was said")
        assertEquals(0xFF4CC2FF.toInt(), name.colour.argb, "the cast's own colour was not used")
    }

    @Test
    fun `a line may bring a colour of its own for a speaker nobody listed`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        band(subs, speakerColours = mapOf("Mira" to Colour(0xFF4CC2FF.toInt())))

        subs.show(SubtitleLine("Who is this?", speaker = "Radio", speakerColour = Colour.Red, durationMillis = 10_000))
        frames(3)

        assertEquals(Colour.Red.argb, checkNotNull(textOf("Radio")).colour.argb)
    }

    @Test
    fun `a caption for a sound is drawn beside the line being spoken`() {
        val subs = SubtitleQueue(capacity = 2, clock = Clock.Ui)
        band(subs)

        subs.show("Stay down.", speaker = "Mira", durationMillis = 10_000)
        subs.caption("[explosion in the distance]", durationMillis = 10_000)
        frames(3)

        assertTrue(texts().contains("Stay down."))
        val caption = checkNotNull(textOf("[explosion in the distance]")) { "the caption is missing: ${texts()}" }
        val spoken = checkNotNull(textOf("Stay down."))
        assertTrue(caption.at.y > spoken.at.y, "the sound goes under the sentence somebody is speaking")
    }

    @Test
    fun `a line in a voice of its own has the name over it in that voice too`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        voiced(subs)

        subs.show("Say again, over.", speaker = "Control", durationMillis = 100_000, style = "radio")
        frames(3)

        assertEquals(RadioWords, checkNotNull(textOf("Say again, over.")).colour.argb)
        assertEquals(RadioName, checkNotNull(textOf("Control")).colour.argb, "the name was left in the normal voice")
    }

    @Test
    fun `a voice with no name style of its own leaves the name as the band draws it`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        voiced(subs)

        subs.show("Get down!", speaker = "Mira", durationMillis = 100_000, style = "shout")
        frames(3)
        val shouted = checkNotNull(textOf("Mira")).colour.argb
        subs.clear()

        subs.show("Stay behind me.", speaker = "Mira", durationMillis = 100_000)
        frames(3)

        assertEquals(checkNotNull(textOf("Mira")).colour.argb, shouted, "the name took the words' own style")
    }

    @Test
    fun `a caption is drawn in the caption style rather than the spoken one`() {
        val subs = SubtitleQueue(capacity = 2, clock = Clock.Ui)
        band(subs)

        subs.show("Stay down.", durationMillis = 10_000)
        subs.caption("[thunder]", durationMillis = 10_000)
        frames(3)

        val spoken = checkNotNull(textOf("Stay down.")).colour.argb
        val heard = checkNotNull(textOf("[thunder]")).colour.argb
        assertTrue(spoken != heard, "a sound and a sentence should not look the same")
    }

    // --- the player's own settings ----------------------------------------------------------

    @Test
    fun `turning speaker names off in the options takes the name away`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        var settings by mutableStateOf(SubtitleSettings())
        show {
            Box(Modifier.fillMaxSize()) {
                Toggle(
                    settings.speakerNames,
                    { settings = settings.copy(speakerNames = it) },
                    Modifier.align(Alignment.TopStart),
                    label = "Speaker names",
                )
                Subtitles(subs, Modifier.align(Alignment.BottomCentre), settings)
            }
        }

        subs.show("Stay behind me.", speaker = "Mira", durationMillis = 30_000)
        frames(3)
        checkNotNull(textOf("Mira")) { "the name should start on: ${texts()}" }

        // A real click on the real toggle, where it really is.
        val knob = canvas.calls.filterIsInstance<DrawCall.Rectangle>().first().rect
        val at = Offset((knob.left + knob.right) / 2f, (knob.top + knob.bottom) / 2f)
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
        frames(4)

        assertFalse(settings.speakerNames, "the click missed the toggle")
        assertNull(textOf("Mira"), "the name is still there: ${texts()}")
        checkNotNull(textOf("Stay behind me.")) { "and the words themselves should still be said" }
    }

    @Test
    fun `the size the player chose is their own rather than the interface's text scale`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)

        fun width(scale: Float, size: SubtitleSize): Float {
            host.setContent {
                ProvideFonts(MonospaceFontProvider()) {
                    ProvideTextScale(scale) {
                        Box(Modifier.fillMaxSize()) {
                            Subtitles(
                                subs,
                                Modifier.align(Alignment.BottomCentre),
                                SubtitleSettings(size = size),
                            )
                        }
                    }
                }
            }
            frames(3)
            return checkNotNull(bandRect()).let { it.rect.right - it.rect.left }
        }

        subs.show("Stay behind me.", durationMillis = 100_000)
        val plain = width(1f, SubtitleSize.Medium)
        val scaled = width(2f, SubtitleSize.Medium)
        val large = width(1f, SubtitleSize.Large)

        assertEquals(plain, scaled, 0.5f, "a subtitle size is its own setting, not the interface's")
        assertTrue(large > plain, "and the subtitle preset is the one that does change it: $large vs $plain")
    }

    @Test
    fun `turning the background down leaves the words at full strength`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        band(subs, SubtitleSettings(backgroundOpacity = 0.25f))

        subs.show("Stay behind me.", durationMillis = 100_000)
        frames(3)

        val behind = checkNotNull(bandRect())
        val word = checkNotNull(textOf("Stay behind me."))
        assertTrue(behind.colour.alpha < 100, "the band should be faint: ${behind.colour.alpha}")
        assertEquals(255, word.colour.alpha, "text faded to match its own background is unreadable on purpose")
    }

    @Test
    fun `a background turned all the way down still leaves the words where they were`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        band(subs, SubtitleSettings(backgroundOpacity = 1f))
        subs.show("Stay behind me.", durationMillis = 100_000)
        frames(3)
        val solid = checkNotNull(textOf("Stay behind me.")).at

        band(subs, SubtitleSettings(backgroundOpacity = 0f))
        frames(3)

        val faint = checkNotNull(textOf("Stay behind me.")).at.x
        assertEquals(solid.x, faint, 0.5f, "the words moved when the band behind them was turned down")
    }

    // --- wrapping and line limits ---------------------------------------------------------------

    @Test
    fun `a long line wraps inside the width the player allowed`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        band(subs, SubtitleSettings(widthFraction = 0.5f))

        subs.show(LongLine, durationMillis = 100_000)
        frames(3)

        val band = checkNotNull(bandRect()).rect
        assertTrue(band.right - band.left <= screen.right * 0.5f + 1f, "it ran past half the screen: $band")
        assertTrue(drawn().size > 1, "a line that long should have wrapped: ${texts()}")
    }

    @Test
    fun `a line past the limit is cut off rather than covering the screen`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        band(subs, SubtitleSettings(widthFraction = 0.35f, maxLines = 2))

        subs.show(LongLine, durationMillis = 100_000)
        frames(3)

        // Counted by the rows the words were drawn on, because the ellipsis is a piece of its own
        // at the end of the last row rather than a row of its own.
        assertEquals(2, drawn().map { it.at.y }.distinct().size, "two lines is two lines: ${texts()}")
        assertTrue(texts().last().endsWith("…"), "the cut line should say it was cut: ${texts().last()}")
    }

    @Test
    fun `a right to left line is laid out right to left`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        show {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) { Subtitles(subs, Modifier.align(Alignment.BottomCentre)) }
            }
        }

        subs.show(Hebrew, durationMillis = 100_000)
        frames(3)

        assertNull(textOf(Hebrew), "handed to the backend whole it would be drawn back to front")
        checkNotNull(textOf(Hebrew.reversed())) { "it should be reordered for drawing: ${texts()}" }
    }

    @Test
    fun `the band sits in the middle whichever way the language runs`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        subs.show("Stay behind me.", durationMillis = 100_000)

        band(subs)
        val leftToRight = checkNotNull(bandRect()).rect

        show {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) { Subtitles(subs, Modifier.align(Alignment.BottomCentre)) }
            }
        }
        val rightToLeft = checkNotNull(bandRect()).rect

        assertEquals(leftToRight.left, rightToLeft.left, 0.5f, "the middle is the middle in either language")
    }

    @Test
    fun `a band given the whole width is still in the middle of it`() {
        // fillMaxWidth makes the node wider than the band, which Modifier.align never does — so this
        // is the case where a band placed at its node's left edge sits against the screen edge.
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        subs.show("Stay behind me.", durationMillis = 100_000)

        show { Box(Modifier.fillMaxSize()) { Subtitles(subs, Modifier.fillMaxWidth()) } }
        val leftToRight = checkNotNull(bandRect()).rect
        assertCentred(leftToRight, "left to right")

        show {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) { Subtitles(subs, Modifier.fillMaxWidth()) }
            }
        }
        assertCentred(checkNotNull(bandRect()).rect, "right to left")
    }

    /** The band's own middle is the screen's, which is the same place whichever way the words run. */
    private fun assertCentred(band: Rect, language: String) {
        val middle = (band.left + band.right) / 2f
        assertEquals(screen.right / 2f, middle, 0.5f, "the band is not in the middle $language: $band")
    }

    // --- time ------------------------------------------------------------------------------------

    @Test
    fun `a queue on the world's clock holds behind a pause`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.World)
        band(subs)
        host.clocks.register(Clock.World)

        subs.show("We are through the gate.", durationMillis = 400)
        frames(3)
        host.clocks.stop(Clock.World)
        frames(60, millis = 20)

        assertEquals(1, subs.shown.size, "it ran out behind a pause menu")

        host.clocks.start(Clock.World)
        frames(40, millis = 20)
        assertTrue(subs.isIdle, "and never came back")
    }

    @Test
    fun `a queue with no clock is driven by the audio instead`() {
        val subs = SubtitleQueue(capacity = 1, clock = null)
        var position = 0L
        show {
            Box(Modifier.fillMaxSize()) {
                // What a game does: hand the sound system's position over once a frame, from its
                // own loop rather than from the composition.
                LaunchedEffect(subs) {
                    while (true) {
                        withFrameNanos { }
                        subs.playTo(position)
                    }
                }
                Subtitles(subs, Modifier.align(Alignment.BottomCentre))
            }
        }

        subs.show("We are through the gate.", durationMillis = 2_000)
        frames(3)
        assertNotNull(textOf("We are through the gate."))

        // Frames go by and the audio does not move: the words stay, because the actor is still there.
        frames(40, millis = 20)
        assertEquals(1, subs.shown.size, "time the audio did not spend was spent anyway")

        position = 2_000
        frames(3)

        assertTrue(subs.isIdle, "the words should leave when the recording does")
    }

    @Test
    fun `an empty queue costs nothing`() {
        val subs = SubtitleQueue(clock = Clock.Ui)
        band(subs)

        repeat(30) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it asked for a redraw with nothing being said")
        }
    }

    @Test
    fun `it stops asking for frames once the last line has gone`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        band(subs)

        subs.show("Go.", durationMillis = 200)
        frames(40, millis = 20)
        assertTrue(subs.isIdle)

        repeat(20) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it was still counting down an empty queue")
        }
    }

    // --- what it must not do ----------------------------------------------------------------------

    @Test
    fun `the band takes no turn in the focus order`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        val presses = mutableListOf<String>()
        show {
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("ONE", { presses += "ONE" })
                    Button("TWO", { presses += "TWO" })
                }
                Subtitles(subs, Modifier.align(Alignment.BottomCentre))
            }
        }
        subs.show("Stay behind me.", speaker = "Mira", durationMillis = 100_000)
        frames(3)

        key(Key.Tab)
        frame()
        key(Key.Enter)
        frame()
        key(Key.Tab)
        frame()
        // A pad, because a player on a pad is the one who would find a band that takes focus.
        padPress(GamepadButton.South)
        frame()

        assertEquals(2, presses.size, "focus stopped on the subtitles on its way past: $presses")
        assertEquals(setOf("ONE", "TWO"), presses.toSet(), "both buttons should have been reached: $presses")
    }

    @Test
    fun `a click where a subtitle is still reaches the game underneath`() {
        val subs = SubtitleQueue(capacity = 1, clock = Clock.Ui)
        var hits = 0
        show {
            Box(Modifier.fillMaxSize()) {
                Button("SHOOT", { hits++ }, Modifier.fillMaxSize())
                Subtitles(subs, Modifier.align(Alignment.BottomCentre))
            }
        }
        subs.show("Stay behind me.", durationMillis = 100_000)
        frames(3)

        val word = checkNotNull(textOf("Stay behind me.")).at
        click(Offset(word.x + 10f, word.y + 4f))
        frames(3)

        assertEquals(1, hits, "the band swallowed a press in the middle of a fight")
    }

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, PointerButton.Primary))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, PointerButton.Primary))
    }

    private fun key(key: Key) {
        val down = KeyEvent(key, KeyEventType.Down)
        if (!router.onKey(down)) keys.onKey(down)
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) keys.onKey(up)
    }

    private fun padPress(button: GamepadButton) {
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
    }

    private companion object {

        val LongLine =
            "They came over the ridge before dawn and nobody heard a thing until the second charge went off."

        /** Three Hebrew letters, which read right to left. */
        const val Hebrew = "אבג"

        /** A radio voice with a name style of its own, and a shout with none. */
        val Voices = Skin.Default.overriddenWith(
            SkinFormat.read(
                """
                {
                  "styles": {
                    "radio": { "textColour": "#FFD166" },
                    "radio.speaker": { "textColour": "#FF3B30" },
                    "shout": { "textColour": "#4CD964" }
                  }
                }
                """.trimIndent(),
            ),
        )

        val RadioWords = 0xFFFFD166.toInt()

        val RadioName = 0xFFFF3B30.toInt()
    }
}
