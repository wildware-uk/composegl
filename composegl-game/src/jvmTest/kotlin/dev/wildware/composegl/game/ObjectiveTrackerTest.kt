package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
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
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinFormat
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The objective tracker on a HUD, driven the way a player drives one.
 *
 * The issue's four: a step that finishes gets a tick, a line through it and then goes; a quest that
 * arrives slides in and can raise a toast; steps count "3 / 5"; and a list longer than the corner
 * allows folds behind a row a mouse, a key or a pad opens — with the whole thing able to disappear
 * for a cutscene.
 *
 * Everything here goes through the real routers: a click is a press and a release at a point on the
 * screen, a key goes through the shortcut walk before the navigator sees it, and the pad is a pad.
 * The tracker is a readout, so the sharpest tests are the ones about what it must **not** do — take
 * focus, or swallow a button press in the middle of a fight.
 */
class ObjectiveTrackerTest {

    private val screen = Rect(0f, 0f, 600f, 400f)
    private val host = UiHost()
    private val canvas = RecordingCanvas(screen)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus, host.root)
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

    // --- the list itself, as a game holds it ------------------------------------------------------

    private class TrackedQuest(val name: String, val steps: List<QuestStep>)

    private class QuestStep(
        val text: String,
        val done: Boolean = false,
        val progress: ObjectiveProgress? = null,
    )

    private fun quest(name: String, vararg steps: QuestStep) = TrackedQuest(name, steps.toList())

    /** The tracker as a game writes it: a title and a line per step. */
    @Suppress("LongParameterList")
    @Composable
    private fun Track(
        quests: List<TrackedQuest>,
        modifier: Modifier = Modifier,
        maxVisible: Int = 3,
        visible: Boolean = true,
        notify: NotificationQueue? = null,
        keepCompleted: Boolean = false,
        focusable: Boolean = false,
        expandKey: Key? = null,
        expandButton: GamepadButton? = null,
    ) {
        ObjectiveTracker(
            quests = quests,
            modifier = modifier,
            keyOf = { it.name },
            maxVisible = maxVisible,
            visible = visible,
            notify = notify,
            keepCompleted = keepCompleted,
            focusable = focusable,
            expandKey = expandKey,
            expandButton = expandButton,
            clock = Clock.Ui,
        ) { tracked ->
            title(tracked.name)
            tracked.steps.forEach { step(it.text, done = it.done, progress = it.progress) }
        }
    }

    // --- reading what was drawn -------------------------------------------------------------------

    private fun drawn() = canvas.calls.filterIsInstance<DrawCall.Text>()

    private fun texts() = drawn().map { it.text }

    private fun textOf(text: String): DrawCall.Text? = drawn().firstOrNull { it.text == text }

    /** The checkmark: triangles in the tick style's own green. */
    private fun ticks() = canvas.calls.filterIsInstance<DrawCall.Fan>().filter { it.colour.argb == TickColour }

    /** The line through a finished step, drawn in the finished step's own colour. */
    private fun strikes() =
        canvas.calls.filterIsInstance<DrawCall.Rectangle>().filter { it.colour.argb == DoneColour }

    // --- what is on the list ----------------------------------------------------------------------

    @Test
    fun `a quest is drawn with its name its steps and its counters`() {
        val quests = listOf(
            quest(
                "Clear the pass",
                QuestStep("Kill the wolves", progress = ObjectiveProgress(3, 5)),
                QuestStep("Reach the cabin"),
            ),
        )
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd)) } }

        assertTrue(texts().contains("Clear the pass"), "the quest's name: ${texts()}")
        assertTrue(texts().contains("Kill the wolves"))
        assertTrue(texts().contains("Reach the cabin"))
        assertTrue(texts().contains("3 / 5"), "the counter is what tells a player they are getting there")
    }

    @Test
    fun `a counter that goes up says the new number`() {
        var quests by mutableStateOf(
            listOf(quest("Clear the pass", QuestStep("Kill the wolves", progress = ObjectiveProgress(3, 5)))),
        )
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd)) } }
        assertTrue(texts().contains("3 / 5"))

        quests = listOf(quest("Clear the pass", QuestStep("Kill the wolves", progress = ObjectiveProgress(4, 5))))
        frames(6)

        assertTrue(texts().contains("4 / 5"), "the counter did not move: ${texts()}")
        assertFalse(texts().contains("3 / 5"))
    }

    // --- finishing a step -------------------------------------------------------------------------

    @Test
    fun `a finished step is ticked then struck through then slides away`() {
        var quests by mutableStateOf(listOf(quest("Clear the pass", QuestStep("Kill the wolves"))))
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd)) } }
        assertTrue(ticks().isEmpty(), "nothing is ticked before it is done")
        assertTrue(strikes().isEmpty())

        quests = listOf(quest("Clear the pass", QuestStep("Kill the wolves", done = true)))
        frames(8)
        assertTrue(ticks().isNotEmpty(), "the tick should be being drawn by now")

        frames(20, millis = 20)
        assertTrue(strikes().isNotEmpty(), "and a line struck through the words after it")
        assertTrue(texts().contains("Kill the wolves"), "still readable while it is being struck out")

        frames(80, millis = 20)
        assertNull(textOf("Kill the wolves"), "it should have slid away by now: ${texts()}")
        assertTrue(texts().contains("Clear the pass"), "but the quest it belonged to is still tracked")
    }

    @Test
    fun `a list that keeps finished steps leaves them struck through instead`() {
        var quests by mutableStateOf(listOf(quest("Clear the pass", QuestStep("Kill the wolves"))))
        show {
            Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd), keepCompleted = true) }
        }

        quests = listOf(quest("Clear the pass", QuestStep("Kill the wolves", done = true)))
        frames(100, millis = 20)

        assertTrue(texts().contains("Kill the wolves"), "a quest log keeps what was done: ${texts()}")
        assertTrue(strikes().isNotEmpty(), "with the line still through it")
        assertTrue(ticks().isNotEmpty(), "and the tick still beside it")
    }

    @Test
    fun `a step already done the first time it is seen is never drawn`() {
        val quests = listOf(
            quest(
                "Clear the pass",
                QuestStep("Find the trail", done = true),
                QuestStep("Kill the wolves"),
            ),
        )
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd)) } }
        frames(40, millis = 20)

        assertNull(textOf("Find the trail"), "a loaded save should not replay its own quest log")
        assertTrue(ticks().isEmpty(), "and nothing should be ticking itself off")
        assertTrue(texts().contains("Kill the wolves"), "what is left to do is still there")
    }

    @Test
    fun `a step with a style of its own keeps it once it is finished`() {
        var done by mutableStateOf(false)
        show {
            ProvideSkin(Quests) {
                Box(Modifier.fillMaxSize()) {
                    ObjectiveTracker(
                        quests = listOf("Clear the pass"),
                        modifier = Modifier.align(Alignment.TopStart),
                        keepCompleted = true,
                        clock = Clock.Ui,
                    ) { name ->
                        title(name)
                        step("Kill the wolves", done = done, style = "main")
                        step("Reach the cabin", done = done, style = "side")
                    }
                }
            }
        }
        assertEquals(MainWords, checkNotNull(textOf("Kill the wolves")).colour.argb, "the step's own style")

        done = true
        frames(60, millis = 20)

        assertEquals(
            MainDone,
            checkNotNull(textOf("Kill the wolves")).colour.argb,
            "a main-quest line turned into an ordinary one the moment it was ticked off",
        )
        assertEquals(
            DoneColour,
            checkNotNull(textOf("Reach the cabin")).colour.argb,
            "a style with no finished version of its own should fall back to the tracker's",
        )
    }

    // --- arriving and leaving ---------------------------------------------------------------------

    @Test
    fun `a new quest raises a toast and the ones already there say nothing`() {
        val notices = NotificationQueue(clock = Clock.Ui)
        var quests by mutableStateOf(listOf(quest("Clear the pass", QuestStep("Kill the wolves"))))
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd), notify = notices) } }

        assertTrue(notices.isIdle, "loading a save is not five toasts: ${notices.shown.map { it.text }}")

        quests = quests + quest("Find the relay", QuestStep("Follow the cable"))
        frames(4)

        assertEquals(listOf("New objective"), notices.shown.map { it.text })
        assertEquals("Find the relay", notices.shown.first().detail, "the toast should say which one")
    }

    @Test
    fun `a quest whose last step is done says so`() {
        val notices = NotificationQueue(clock = Clock.Ui)
        var quests by mutableStateOf(
            listOf(quest("Clear the pass", QuestStep("Kill the wolves"), QuestStep("Reach the cabin"))),
        )
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd), notify = notices) } }

        quests = listOf(
            quest("Clear the pass", QuestStep("Kill the wolves", done = true), QuestStep("Reach the cabin")),
        )
        frames(4)
        assertTrue(notices.isIdle, "one step of two is not a finished quest")

        quests = listOf(
            quest(
                "Clear the pass",
                QuestStep("Kill the wolves", done = true),
                QuestStep("Reach the cabin", done = true),
            ),
        )
        frames(4)

        assertEquals(listOf("Objective complete"), notices.shown.map { it.text })
        assertEquals("Clear the pass", notices.shown.first().detail)
    }

    @Test
    fun `a quest the game stops tracking is still there while it slides away`() {
        var quests by mutableStateOf(
            listOf(
                quest("Clear the pass", QuestStep("Kill the wolves")),
                quest("Find the relay", QuestStep("Follow the cable")),
            ),
        )
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd)) } }

        quests = listOf(quest("Find the relay", QuestStep("Follow the cable")))
        frame()
        assertTrue(texts().contains("Clear the pass"), "it should leave rather than blink out")

        frames(40, millis = 20)
        assertNull(textOf("Clear the pass"), "and then be gone: ${texts()}")
        assertTrue(texts().contains("Find the relay"), "the one still tracked stays")
    }

    @Test
    fun `a quest handed back while it was sliding away slides in again`() {
        var quests by mutableStateOf(
            listOf(
                quest("Clear the pass", QuestStep("Kill the wolves")),
                quest("Find the relay", QuestStep("Follow the cable")),
            ),
        )
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopStart)) } }
        val rested = checkNotNull(textOf("Clear the pass")).at.x
        // Where the quest below it sits, which is the row's real height read off the screen.
        val below = checkNotNull(textOf("Find the relay")).at.y

        quests = listOf(quest("Find the relay", QuestStep("Follow the cable")))
        assertTrue(slidOut("Clear the pass", rested), "it never started sliding away")

        quests = listOf(
            quest("Clear the pass", QuestStep("Kill the wolves")),
            quest("Find the relay", QuestStep("Follow the cable")),
        )
        frames(140, millis = 10)

        val title = checkNotNull(textOf("Clear the pass")) { "the quest handed back is gone: ${texts()}" }
        assertEquals(rested, title.at.x, 0.5f, "it stuck where the way out had got to")
        assertEquals(below, checkNotNull(textOf("Find the relay")).at.y, 0.5f, "and never came back to full height")
    }

    @Test
    fun `a step un-finished while it was sliding away comes back`() {
        var quests by mutableStateOf(
            listOf(quest("Clear the pass", QuestStep("Kill the wolves"), QuestStep("Reach the cabin"))),
        )
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopStart)) } }
        val rested = checkNotNull(textOf("Kill the wolves")).at.x
        val below = checkNotNull(textOf("Reach the cabin")).at.y

        quests = listOf(
            quest("Clear the pass", QuestStep("Kill the wolves", done = true), QuestStep("Reach the cabin")),
        )
        assertTrue(slidOut("Kill the wolves", rested), "the tick and the line never led to a way out")

        quests = listOf(quest("Clear the pass", QuestStep("Kill the wolves"), QuestStep("Reach the cabin")))
        frames(140, millis = 10)

        val words = checkNotNull(textOf("Kill the wolves")) { "the step taken back is gone: ${texts()}" }
        assertEquals(rested, words.at.x, 0.5f, "it stuck where the way out had got to")
        assertEquals(below, checkNotNull(textOf("Reach the cabin")).at.y, 0.5f, "and never came back to full height")
        assertTrue(ticks().isEmpty(), "and its tick should have been rubbed out with it")
    }

    /**
     * Runs frames until [text] is a quarter of the way out, so the test never has to know how many
     * milliseconds of tick and pause come first. False if it never moved, or had gone before it did.
     */
    private fun slidOut(text: String, rested: Float): Boolean {
        repeat(200) {
            frame(10)
            val at = textOf(text)?.at?.x ?: return false
            if (at > rested + SlidOut) return true
        }
        return false
    }

    @Test
    fun `two quests that answer keyOf the same are drawn once`() {
        val quests = listOf(
            quest("Clear the pass", QuestStep("Kill the wolves")),
            quest("Clear the pass", QuestStep("Reach the cabin")),
        )
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopStart)) } }
        frames(40, millis = 20)

        assertEquals(1, texts().count { it == "Clear the pass" }, "one key is one row: ${texts()}")
        assertTrue(texts().contains("Kill the wolves"), "the first one declared is the one drawn")
        assertFalse(texts().contains("Reach the cabin"))
    }

    @Test
    fun `a quest arriving slides in from the side the language ends on`() {
        val english = arrivalOf(LayoutDirection.Ltr)
        val arabic = arrivalOf(LayoutDirection.Rtl)

        assertTrue(english > 0f, "in English a row comes in from the right: $english")
        assertTrue(arabic < 0f, "and in a right-to-left language from the left: $arabic")
    }

    /** How far, and which way, a row travelled between arriving and settling. */
    private fun arrivalOf(direction: LayoutDirection): Float {
        var quests by mutableStateOf(listOf(quest("Clear the pass", QuestStep("Kill the wolves"))))
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                // Keyed, because the other direction was composed here a moment ago: without it the
                // second run inherits the first one's list, animations half finished and all.
                key(direction) {
                    ProvideLayoutDirection(direction) {
                        Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopStart)) }
                    }
                }
            }
        }
        frames(6)

        quests = quests + quest("Find the relay", QuestStep("Follow the cable"))
        // Every place it was drawn on its way in, so the test does not have to guess which frame
        // caught it mid-flight.
        val seen = ArrayList<Float>()
        repeat(60) {
            frame(10)
            textOf("Find the relay")?.let { seen += it.at.x }
        }
        assertTrue(seen.size > 2, "it should have been drawn on its way in")
        return seen.first() - seen.last()
    }

    @Test
    fun `the line through a finished step is drawn out from the side the words start on`() {
        val english = strikeOf(LayoutDirection.Ltr)
        val arabic = strikeOf(LayoutDirection.Rtl)

        assertEquals(english.full.left, english.partial.left, 0.5f, "in English it starts at the left")
        assertTrue(english.partial.right < english.full.right - 1f, "and has not reached the right yet")

        assertEquals(arabic.full.right, arabic.partial.right, 0.5f, "in Arabic it starts at the right")
        assertTrue(arabic.partial.left > arabic.full.left + 1f, "and has not reached the left yet")
    }

    private class Struck(val partial: Rect, val full: Rect)

    /** The struck-through line halfway through being drawn, and once it has been drawn. */
    private fun strikeOf(direction: LayoutDirection): Struck {
        var quests by mutableStateOf(listOf(quest("Clear the pass", QuestStep("Kill the wolves"))))
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                key(direction) {
                    ProvideLayoutDirection(direction) {
                        Box(Modifier.fillMaxSize()) {
                            Track(quests, Modifier.align(Alignment.TopStart), keepCompleted = true)
                        }
                    }
                }
            }
        }
        frames(6)

        quests = listOf(quest("Clear the pass", QuestStep("Kill the wolves", done = true)))
        // Every line drawn while it was being drawn, rather than a guess at which frame caught it
        // halfway: the tick goes first, and how long that leaves is not a number a test should know.
        val seen = ArrayList<Rect>()
        repeat(120) {
            frame(10)
            strikes().firstOrNull()?.let { seen += it.rect }
        }
        val full = checkNotNull(seen.lastOrNull()) { "no line was ever struck through it" }
        val partial = checkNotNull(seen.firstOrNull { it.width > full.width * 0.1f && it.width < full.width * 0.9f }) {
            "the line arrived all at once rather than being drawn out"
        }
        return Struck(partial, full)
    }

    // --- folding the extra quests away -------------------------------------------------------------

    @Test
    fun `quests past the limit fold behind a row a click opens`() {
        show { Box(Modifier.fillMaxSize()) { Track(FourQuests, Modifier.align(Alignment.TopEnd), maxVisible = 2) } }

        assertTrue(texts().contains("Clear the pass"))
        assertTrue(texts().contains("Find the relay"))
        assertFalse(texts().contains("Feed the dogs"), "the third is folded away: ${texts()}")
        val more = checkNotNull(textOf("+2 more")) { "the fold row should say how many: ${texts()}" }

        click(Offset(more.at.x + 4f, more.at.y + 4f))
        frames(6)

        assertTrue(texts().contains("Feed the dogs"), "the click did not open it: ${texts()}")
        assertTrue(texts().contains("Mend the fence"))
        assertTrue(texts().contains("Show fewer"), "and the row should now offer to close again")
    }

    @Test
    fun `a key folds the extra quests in and out from anywhere`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Button("SHOOT", {}, Modifier.align(Alignment.BottomStart), initialFocus = true)
                Track(FourQuests, Modifier.align(Alignment.TopEnd), maxVisible = 2, expandKey = Key.J)
            }
        }
        assertFalse(texts().contains("Feed the dogs"))

        key(Key.J)
        frames(6)
        assertTrue(texts().contains("Feed the dogs"), "the key never reached it: ${texts()}")

        key(Key.J)
        frames(6)
        assertFalse(texts().contains("Feed the dogs"), "and it should fold up again")
    }

    @Test
    fun `a pad button does the same`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Button("SHOOT", {}, Modifier.align(Alignment.BottomStart), initialFocus = true)
                Track(FourQuests, Modifier.align(Alignment.TopEnd), maxVisible = 2, expandButton = GamepadButton.North)
            }
        }
        assertFalse(texts().contains("Feed the dogs"))

        padPress(GamepadButton.North)
        frames(6)

        assertTrue(texts().contains("Feed the dogs"), "the pad never reached it: ${texts()}")
    }

    // --- what it must not do -----------------------------------------------------------------------

    @Test
    fun `the list takes no turn in the focus order`() {
        val presses = mutableListOf<String>()
        show {
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("ONE", { presses += "ONE" })
                    Button("TWO", { presses += "TWO" })
                }
                Track(FourQuests, Modifier.align(Alignment.TopEnd), maxVisible = 2)
            }
        }

        repeat(3) {
            key(Key.Tab)
            frame()
            key(Key.Enter)
            frame()
        }
        // A pad too, because a player on a pad is the one who would find a HUD that takes focus.
        padPress(GamepadButton.South)
        frames(4)

        assertEquals(4, presses.size, "a press landed somewhere that is not a button: $presses")
        assertEquals(setOf("ONE", "TWO"), presses.toSet(), "focus stopped on the tracker: $presses")
        assertFalse(texts().contains("Feed the dogs"), "and it opened the fold on the way past")
    }

    @Test
    fun `a fold row asked to be focusable is reached by the pad`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Column {
                    Button("ONE", {})
                    Button("TWO", {})
                }
                Track(FourQuests, Modifier.align(Alignment.TopEnd), maxVisible = 2, focusable = true)
            }
        }

        // Round the focus order, pressing South at every stop until it opens: South on a button
        // that does nothing does nothing, and pressing on past the fold row would close it again.
        var opened = false
        repeat(6) {
            if (opened) return@repeat
            key(Key.Tab)
            frame()
            padPress(GamepadButton.South)
            frames(4)
            opened = texts().contains("Feed the dogs")
        }

        assertTrue(opened, "the pad never reached the fold row: ${texts()}")
    }

    @Test
    fun `a step finished while it was folded away plays its tick when the fold opens`() {
        var quests by mutableStateOf(FourQuests)
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd), maxVisible = 2) } }
        assertFalse(texts().contains("Open the store"), "the third quest is folded away: ${texts()}")

        // Finished behind the fold, where there was nobody to see it happen.
        quests = FourQuests.map { tracked ->
            val folded = tracked.name == "Feed the dogs"
            if (folded) quest(tracked.name, QuestStep("Open the store", done = true)) else tracked
        }
        frames(40, millis = 20)
        assertTrue(ticks().isEmpty(), "nothing should have ticked itself off behind the fold")

        val more = checkNotNull(textOf("+2 more")) { "the fold row should be there to open: ${texts()}" }
        click(Offset(more.at.x + 4f, more.at.y + 4f))
        frame()

        assertTrue(texts().contains("Open the store"), "the fold never opened: ${texts()}")
        assertTrue(ticks().isEmpty(), "it came up already ticked, so the player missed the one thing worth seeing")

        frames(12)
        assertTrue(ticks().isNotEmpty(), "and the tick was never drawn at all: ${texts()}")
    }

    @Test
    fun `a key pressed with nothing folded away does not spring the list open later`() {
        var quests by mutableStateOf(FourQuests.take(1))
        show {
            Box(Modifier.fillMaxSize()) {
                Button("SHOOT", {}, Modifier.align(Alignment.BottomStart), initialFocus = true)
                Track(quests, Modifier.align(Alignment.TopEnd), maxVisible = 2, expandKey = Key.J)
            }
        }

        key(Key.J)
        frames(6)

        quests = FourQuests.take(3)
        frames(60, millis = 20)

        assertFalse(texts().contains("Feed the dogs"), "it sprang open on a press made two quests ago: ${texts()}")
        assertTrue(texts().contains("+1 more"), "and it should be offering to open instead: ${texts()}")
    }

    @Test
    fun `the expand key and button are the game's while there is nothing to unfold`() {
        show {
            Box(Modifier.fillMaxSize()) {
                Button("SHOOT", {}, Modifier.align(Alignment.BottomStart), initialFocus = true)
                Track(
                    FourQuests.take(2),
                    Modifier.align(Alignment.TopEnd),
                    maxVisible = 3,
                    expandKey = Key.J,
                    expandButton = GamepadButton.North,
                )
            }
        }

        assertFalse(router.onKey(KeyEvent(Key.J, KeyEventType.Down)), "the tracker ate a key it had no fold row for")
        assertFalse(
            pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.North)),
            "and a pad button with it, in the middle of a fight",
        )
    }

    @Test
    fun `the fold row goes once there is nothing left folded away`() {
        var quests by mutableStateOf(FourQuests)
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd), maxVisible = 2) } }

        val more = checkNotNull(textOf("+2 more")) { "the fold row should be there to open: ${texts()}" }
        click(Offset(more.at.x + 4f, more.at.y + 4f))
        frames(6)
        assertTrue(texts().contains("Show fewer"))

        quests = FourQuests.take(2)
        frames(60, millis = 20)

        assertFalse(texts().contains("Show fewer"), "a row offering to fold nothing away: ${texts()}")
        assertTrue(texts().contains("Clear the pass"), "the two still tracked are still there")
    }

    @Test
    fun `a click where the list is reaches the game underneath`() {
        var hits = 0
        show {
            Box(Modifier.fillMaxSize()) {
                Button("SHOOT", { hits++ }, Modifier.fillMaxSize())
                Track(FourQuests, Modifier.align(Alignment.TopEnd), maxVisible = 2)
            }
        }

        val word = checkNotNull(textOf("Kill the wolves")).at
        click(Offset(word.x + 10f, word.y + 4f))
        frames(3)

        assertEquals(1, hits, "the tracker swallowed a press in the middle of a fight")
    }

    // --- cutscenes and idleness ---------------------------------------------------------------------

    @Test
    fun `hiding it for a cutscene fades it away and then costs nothing`() {
        var playing by mutableStateOf(false)
        show {
            Box(Modifier.fillMaxSize()) {
                Track(FourQuests, Modifier.align(Alignment.TopEnd), maxVisible = 2, visible = !playing)
            }
        }
        assertTrue(texts().contains("Clear the pass"))

        playing = true
        frames(40, millis = 20)
        assertTrue(texts().isEmpty(), "it should be gone for the cutscene: ${texts()}")

        repeat(20) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it asked for a redraw behind a cutscene")
        }

        playing = false
        frames(40, millis = 20)
        assertTrue(texts().contains("Clear the pass"), "and come back afterwards: ${texts()}")
    }

    @Test
    fun `a quest dropped behind a cutscene has gone by the time the cutscene ends`() {
        var playing by mutableStateOf(false)
        var quests by mutableStateOf(
            listOf(
                quest("Clear the pass", QuestStep("Kill the wolves")),
                quest("Find the relay", QuestStep("Follow the cable")),
            ),
        )
        show {
            Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd), visible = !playing) }
        }

        playing = true
        frames(40, millis = 20)
        assertTrue(texts().isEmpty(), "it should be gone for the cutscene: ${texts()}")

        quests = listOf(quest("Find the relay", QuestStep("Follow the cable")))
        frames(4)

        // Never drawn again, rather than merely gone by the end: the bug is the player watching a
        // quest they finished an hour ago being waved off as the cutscene hands the screen back.
        playing = false
        var seen = false
        repeat(40) {
            frame(20)
            seen = seen || texts().contains("Clear the pass")
        }

        assertFalse(seen, "a quest dropped behind the cutscene was waved off afterwards")
        assertTrue(texts().contains("Find the relay"), "the one still tracked comes back")
    }

    @Test
    fun `a tracker that opens with quests already on it animates nothing`() {
        show { Box(Modifier.fillMaxSize()) { Track(FourQuests.take(1), Modifier.align(Alignment.TopStart)) } }
        assertTrue(texts().contains("Kill the wolves"), "it should be drawn straight away: ${texts()}")

        repeat(20) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it slid something in that was there when it opened")
        }
    }

    @Test
    fun `a tracker with nothing on it costs nothing`() {
        show { Box(Modifier.fillMaxSize()) { Track(emptyList(), Modifier.align(Alignment.TopEnd)) } }
        frames(5)

        repeat(30) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it asked for a redraw with nothing to do")
        }
        assertTrue(texts().isEmpty())
    }

    @Test
    fun `it stops asking for frames once the last step has gone`() {
        var quests by mutableStateOf(listOf(quest("Clear the pass", QuestStep("Kill the wolves"))))
        show { Box(Modifier.fillMaxSize()) { Track(quests, Modifier.align(Alignment.TopEnd)) } }

        quests = listOf(quest("Clear the pass", QuestStep("Kill the wolves", done = true)))
        frames(120, millis = 20)
        assertNull(textOf("Kill the wolves"))

        repeat(20) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it was still animating a step that had gone")
        }
    }

    // --- driving it ---------------------------------------------------------------------------------

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

        /** Far enough out of place to be unmistakably on its way, of the tracker's 24 of travel. */
        const val SlidOut = 6f

        /** More than any corner of a HUD should show at once, which is what the fold is for. */
        val FourQuests = listOf(
            TrackedQuest("Clear the pass", listOf(QuestStep("Kill the wolves"))),
            TrackedQuest("Find the relay", listOf(QuestStep("Follow the cable"))),
            TrackedQuest("Feed the dogs", listOf(QuestStep("Open the store"))),
            TrackedQuest("Mend the fence", listOf(QuestStep("Cut the posts"))),
        )

        /** A main-quest line with a finished version of its own, and a side one with none. */
        val Quests = Skin.Default.overriddenWith(
            SkinFormat.read(
                """
                {
                  "styles": {
                    "main": { "textColour": "#FFD166" },
                    "main.done": { "textColour": "#FF3B30" },
                    "side": { "textColour": "#4CD964" }
                  }
                }
                """.trimIndent(),
            ),
        )

        val MainWords = 0xFFFFD166.toInt()

        val MainDone = 0xFFFF3B30.toInt()

        /** The skin's own colours, so a test never asserts on a number this file made up. */
        val TickColour =
            checkNotNull(Skin.Default.resolve("objective.tick").background.flatColour) { "the tick needs a colour" }.argb

        val DoneColour = Skin.Default.resolve("objective.step.done").textColour.argb
    }
}
