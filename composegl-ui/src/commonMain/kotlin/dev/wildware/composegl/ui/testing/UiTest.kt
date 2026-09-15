package dev.wildware.composegl.ui.testing

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.debug.OverdrawMap
import dev.wildware.composegl.ui.debug.measureOverdraw
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.BackStack
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadCursor
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.InputRouter
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.InputSourceTracker
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.SourceAware
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.dump
import dev.wildware.composegl.ui.text.graphemeAfter
import dev.wildware.composegl.ui.widget.ProvideBackStack
import dev.wildware.composegl.ui.widget.ProvideClipboard
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideGamepadCursor
import dev.wildware.composegl.ui.widget.ProvideHaptics
import dev.wildware.composegl.ui.widget.ProvideInputSource
import dev.wildware.composegl.ui.widget.ProvideSoftKeyboard

/**
 * A screen, composed for real, that a test drives the way a player does.
 *
 * ```kotlin
 * uiTest { MainMenu() }.use { ui ->
 *     ui.click("play")
 *     ui.type("Ada")
 *     ui.pad(GamepadButton.DpadDown); ui.pad(GamepadButton.South)
 *     ui.assertFocused("options")
 *     ui.assertText("name", "Ada")
 * }
 * ```
 *
 * Everything a game wires by hand is wired here the same way: a [PointerRouter] for the pointer,
 * a [KeyRouter] asked before a [KeyNavigator] so a field keeps the keys it needs, a
 * [GamepadNavigator] for the pad, all behind one [SourceAware] sink so prompts follow the device.
 * The content gets the backend's fonts, clipboard and soft keyboard, the input source and a
 * [BackStack], so `OnBack` and `PromptGlyph` behave as they do in a game.
 *
 * Every action settles the host afterwards — recompose, lay out, refresh focus, until nothing
 * changes — so the next thing a test does sees the screen the action left behind, and a click
 * lands where a button is now rather than where it was a frame ago. Time only passes when the test
 * says: each settling turn is one frame, and [advanceBy] runs as many as it is asked for.
 *
 * Nodes are named by `Modifier.testTag`, and everything that takes a tag fails with the tree
 * printed when the tag is not there, because a misspelt tag is otherwise a test about nothing.
 *
 * @param size the screen, which is also the viewport the tree is laid out and drawn in.
 * @param backend where fonts, clipboard, keyboard and the canvas [render] draws into come from.
 *   Headless by default; a GL test hands a real one in and reads the pixels back.
 * @param onBack what Escape, East and the pad's Back do once nothing on the [BackStack] took it.
 * @param input wraps the sink every event goes into, the way a game wraps its own — a
 *   `ParallaxAware`, say — so what sits in front of the toolkit in a game sits in front of it here.
 * @param viewport where on the window the screen is laid out and drawn. One to one over [size] by
 *   default. A split-screen test hands each player one of `Viewport.splitScreen`, so [render] draws
 *   into that player's part of the window and an [InputRouter] routes by the same areas.
 * @param budget what [UiTest.render] times each frame with, and where it says which nodes cut the
 *   batch. A test asserting on its reading usually wants `publishEveryMillis = 0`.
 * @param content the screen.
 */
fun uiTest(
    size: Size = Size(1280f, 720f),
    backend: UiBackend = HeadlessBackend(),
    onBack: () -> Unit = {},
    input: (InputSink) -> InputSink = { it },
    viewport: Viewport = Viewport.oneToOne(size),
    budget: FrameBudget = FrameBudget(),
    content: @Composable () -> Unit,
): UiTest {
    val test = UiTest(size, backend, onBack, input, viewport, budget)
    try {
        test.setContent(content)
    } catch (failure: Throwable) {
        // Nobody holds the test to close it when the first settle is what failed.
        // Kept behind the settle's own failure, which is the one that says what went wrong.
        runCatching { test.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
        throw failure
    }
    return test
}

/** What [uiTest] hands back. Close it, or `use` it, so the composition is let go of. */
class UiTest(
    val size: Size,
    val backend: UiBackend,
    private val onBack: () -> Unit,
    wrapInput: (InputSink) -> InputSink = { it },
    val viewport: Viewport = Viewport.oneToOne(size),
    /** What [render] times frames with. Its reading says what the last rendered frame cost. */
    val budget: FrameBudget = FrameBudget(),
) : AutoCloseable {

    val host = UiHost()
    val root: UiNode get() = host.root
    val focus = FocusManager(host.root)

    /** What the player last used. Every event a test sends goes past it first. */
    val source = InputSourceTracker()

    /** Where `OnBack` handlers in the content put themselves. */
    val backs = BackStack()

    /** The frame time handed to the host, in nanoseconds. Moves one frame per settling turn. */
    var nanos = 0L
        private set

    /** Where the mouse is, after the last pointer action. Starts off the screen. */
    var pointerAt: Offset = Offset(-1f, -1f)
        private set

    /** The mouse buttons held down now: the primary one between a [press] and a [release]. */
    private var held: Set<PointerButton> = emptySet()

    private val back = { if (!backs.back()) onBack() }
    // The backend's cursor, so a test asks the headless one which shape the mouse was given.
    private val pointerRouter = PointerRouter(host.root, focus, backend.cursor)

    /** The shape the mouse cursor should be now, as the router worked it out. */
    val pointerIcon: PointerIcon get() = pointerRouter.pointerIcon
    private val keyRouter = KeyRouter(focus, host.root)
    private val keyNavigator = KeyNavigator(focus, onBack = back)
    private val padNavigator = GamepadNavigator(focus, onBack = back)
    private val renderer by lazy { UiRenderer(host, backend.canvas, budget).also { it.focus = focus } }

    /**
     * The pad's cursor, for a `VirtualCursor` in the content to switch on. Straight into the pointer
     * router, as a game wires it, so the tracker still hears a pad.
     */
    val cursor = GamepadCursor(host.root, pointerRouter)

    /**
     * The same shape as a game's sink: the router first, the navigator for what nobody took.
     *
     * Public for a test that delivers events itself — several players' screens behind one
     * [InputRouter], say. Events sent here do not settle the screen; call [settle] afterwards.
     * Pointer positions are in the screen's own coordinates, the ones [node] bounds are in.
     */
    val input: InputSink = wrapInput(SourceAware(
        source,
        object : InputSink {
            override fun onPointer(event: PointerEvent) = pointerRouter.onPointer(event)
            override fun onKey(event: KeyEvent) = keyRouter.onKey(event) || keyNavigator.onKey(event)
            override fun onText(event: TextEvent) = keyRouter.onText(event)
            override fun onGamepad(event: GamepadEvent) = cursor.onGamepad(event) || padNavigator.onGamepad(event)
        },
    ))

    internal fun setContent(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(backend.fonts) {
                ProvideClipboard(backend.clipboard) {
                    ProvideSoftKeyboard(backend.softKeyboard) {
                        ProvideHaptics(backend.haptics) {
                            ProvideInputSource(source) {
                                ProvideGamepadCursor(cursor) {
                                    ProvideBackStack(backs, content)
                                }
                            }
                        }
                    }
                }
            }
        }
        settle()
    }

    // --- time ------------------------------------------------------------------------------------

    /**
     * Frames until the host says nothing changed, and fails if that never happens.
     *
     * A frame passes each turn, as it would in a game, so state written by a coroutine that
     * resumed during one turn is published in the next, and an animation a click started plays
     * out to its end before the test looks. [turns] is a ceiling rather than a count: five seconds
     * of frames by default, which no transition on a menu comes near, and a screen still changing
     * after that is animating forever — which a test should hear about rather than hang on.
     *
     * [QuietFrames] frames in a row where nothing changed, with no animation playing on the host's
     * clocks, end it. Several rather than one, because an animation takes a frame or two to get
     * going — the recompose that set its target changes nothing visible, and its first frame only
     * notes the start time. The clocks rather than the tree alone, because the tail of an eased
     * fade moves by less than anything a label rounds to, and a test looking then reads 98 where
     * the player will see 100. A caret that blinks twice a second is not an animation and changes
     * one frame in thirty, so a focused field does not keep a test waiting. Something that waits
     * without changing anything — a countdown, a hold delay — is what [advanceBy] is for.
     *
     * A frame that only moved a `marquee` along counts as quiet too. A marquee goes round for ever,
     * and moves nothing a test can ask about — no box, no text, no focus — so waiting for it to
     * stop would be failing every screen that has a long name on it.
     */
    fun settle(turns: Int = 300) {
        var quiet = 0
        repeat(turns) {
            val changed = step()
            quiet = if (changed || host.clocks.isAnimating) 0 else quiet + 1
            if (quiet == QuietFrames) return
        }
        throw AssertionError(
            "the screen was still changing after $turns frames; something is animating forever or " +
                "writing state every frame:\n" + dump(),
        )
    }

    /**
     * Lets [millis] of game time pass, a frame at a time, then settles.
     *
     * The way to finish an animation, wait out a tooltip delay or hold a stick long enough to
     * repeat. Nothing here reads a real clock, so a four-second wait takes no time at all.
     */
    fun advanceBy(millis: Long) {
        val until = nanos + millis * 1_000_000L
        while (nanos + FrameNanos <= until) step()
        settle()
    }

    /**
     * One frame: the pad's cursor and held-direction repeat, then the host, on the same clock.
     *
     * The pad first, as a game polls input before it draws, so a step the stick takes is on the
     * screen by the end of the frame it happened in. A focus move counts as a change by itself: a
     * repeat on the last quiet frame would otherwise end the settle with the screen a step behind.
     * So does the cursor moving, for the same reason.
     */
    private fun step(): Boolean {
        nanos += FrameNanos
        val before = focus.focused
        val cursorWas = cursor.position
        cursor.frame(nanos / 1_000_000L)
        padNavigator.frame(nanos / 1_000_000L)
        val changed = host.settle(viewport, focus, nanos = nanos) && !host.onlyRedrawn
        return changed || focus.focused !== before || cursor.position != cursorWas
    }

    // --- the pointer -----------------------------------------------------------------------------

    /** Presses and releases the mouse on the middle of the node tagged [tag]. */
    fun click(tag: String, button: PointerButton = PointerButton.Primary): Boolean = click(centreOf(tag), button)

    /**
     * Presses and releases the mouse at [at], in screen coordinates, with a frame between.
     *
     * Returns whether something took the press. A click on plain scenery is not a failure — a test
     * may be checking exactly that it does nothing.
     */
    fun click(at: Offset, button: PointerButton = PointerButton.Primary): Boolean {
        val took = press(at, button)
        release(button)
        return took
    }

    /** Moves the mouse onto the middle of [tag] with nothing held: a hover. */
    fun moveTo(tag: String): Boolean = moveTo(centreOf(tag))

    /**
     * Moves the mouse to [at]. Between a [press] and a [release] that is a drag, and the move says
     * the button is still held, as a real mouse's does — a slider only follows a move that does.
     */
    fun moveTo(at: Offset): Boolean =
        send(PointerEvent.Move(PointerId.Mouse, at, pressed = held)).also { pointerAt = at }

    /** Moves onto [tag] and holds the mouse down there. [release] lets go. */
    fun press(tag: String, button: PointerButton = PointerButton.Primary): Boolean = press(centreOf(tag), button)

    fun press(at: Offset, button: PointerButton = PointerButton.Primary): Boolean {
        moveTo(at)
        held = setOf(button)
        return send(PointerEvent.Press(PointerId.Mouse, at, button))
    }

    /**
     * Moves the mouse to [at] with its button held, which is a drag once [press] has started one:
     * a slider's knob, a scrollbar, a window by its title.
     */
    fun dragTo(at: Offset): Boolean =
        send(PointerEvent.Move(PointerId.Mouse, at, setOf(PointerButton.Primary))).also { pointerAt = at }

    /** Lets go of the mouse wherever it is now, which is where a drag ended. */
    fun release(button: PointerButton = PointerButton.Primary): Boolean {
        held = emptySet()
        return send(PointerEvent.Release(PointerId.Mouse, pointerAt, button))
    }

    /** A wheel turned over [tag]. Positive scrolls content up and left. */
    fun scroll(tag: String, delta: Offset): Boolean {
        val at = centreOf(tag)
        moveTo(at)
        return send(PointerEvent.Scroll(PointerId.Mouse, at, delta))
    }

    // --- the keyboard ----------------------------------------------------------------------------

    /** A key pressed and let go, through the focused widget first and navigation after. */
    fun key(key: Key, modifiers: Modifiers = Modifiers.None): Boolean {
        val took = keyDown(key, modifiers)
        keyUp(key, modifiers)
        return took
    }

    /** A key going down. [repeat] is the platform saying a held key is still held. */
    fun keyDown(key: Key, modifiers: Modifiers = Modifiers.None, repeat: Boolean = false): Boolean =
        send(KeyEvent(key, KeyEventType.Down, modifiers, repeat))

    fun keyUp(key: Key, modifiers: Modifiers = Modifiers.None): Boolean =
        send(KeyEvent(key, KeyEventType.Up, modifiers))

    /**
     * Types [text] into whatever has focus, one character at a time, as a keyboard sends it.
     *
     * One event per character — a letter with its accent, a flag, a family emoji each count as
     * one — rather than the whole string at once, which is what a paste or an input method does
     * and is not the same thing to a field with a length limit.
     *
     * Fails when nothing has focus, since that is the typing going nowhere. Returns whether every
     * character was taken; a full field refusing the last few is something a test can ask about.
     */
    fun type(text: String): Boolean {
        if (focus.focused == null) {
            throw AssertionError("typed \"$text\" with nothing focused, so it went nowhere:\n" + dump())
        }
        var all = true
        var at = 0
        while (at < text.length) {
            val next = text.graphemeAfter(at)
            if (!input.onText(TextEvent(text.substring(at, next)))) all = false
            settle()
            at = next
        }
        return all
    }

    // --- the pad ---------------------------------------------------------------------------------

    /** A pad button pressed and let go: a d-pad step, South to press, East to go back. */
    fun pad(button: GamepadButton, gamepad: GamepadId = GamepadId.First): Boolean {
        val took = padDown(button, gamepad)
        padUp(button, gamepad)
        return took
    }

    fun padDown(button: GamepadButton, gamepad: GamepadId = GamepadId.First): Boolean =
        send(GamepadEvent.ButtonDown(gamepad, button))

    fun padUp(button: GamepadButton, gamepad: GamepadId = GamepadId.First): Boolean =
        send(GamepadEvent.ButtonUp(gamepad, button))

    /**
     * Pushes a stick to [x], [y] and leaves it there; y is positive down.
     *
     * The left stick unless [horizontal] and [vertical] name other axes, such as the right stick's.
     */
    fun stick(
        x: Float,
        y: Float,
        gamepad: GamepadId = GamepadId.First,
        horizontal: GamepadAxis = GamepadAxis.LeftX,
        vertical: GamepadAxis = GamepadAxis.LeftY,
    ): Boolean {
        val sideways = send(GamepadEvent.Axis(gamepad, horizontal, x))
        val upDown = send(GamepadEvent.Axis(gamepad, vertical, y))
        return sideways || upDown
    }

    /**
     * Holds the left stick at [x], [y] for [millis] of game time, then lets it go and settles.
     *
     * What a `VirtualCursor` test wants: [stick] settles with the stick still pushed, which carries
     * a cursor all the way to the edge of the screen. This pushes, lets frames pass while the stick
     * is held, and only then lets go — so the cursor travels as far as that long a push takes it,
     * and a snap after the let-go has played out by the time the test looks.
     */
    fun holdStick(x: Float, y: Float, millis: Long, gamepad: GamepadId = GamepadId.First) {
        input.onGamepad(GamepadEvent.Axis(gamepad, GamepadAxis.LeftX, x))
        input.onGamepad(GamepadEvent.Axis(gamepad, GamepadAxis.LeftY, y))
        val until = nanos + millis * 1_000_000L
        while (nanos + FrameNanos <= until) step()
        input.onGamepad(GamepadEvent.Axis(gamepad, GamepadAxis.LeftX, 0f))
        input.onGamepad(GamepadEvent.Axis(gamepad, GamepadAxis.LeftY, 0f))
        settle()
    }

    // --- reading the screen ----------------------------------------------------------------------

    /** The one node tagged [tag]. Fails with the tree printed when there is none, or two. */
    fun node(tag: String): UiNode = checkNotNull(root.findOrNull(tag)) {
        // Rather than root.find, whose dump cannot know where focus is.
        "no node under ${root.name} is tagged $tag:\n" + dump()
    }

    /**
     * Every run of text drawn by [tag] and the nodes inside it, in the order they were drawn.
     *
     * Read off a drawing rather than off a widget's state, so it is what a player reads: the label
     * on a button, the lines in a field, a field's placeholder while it is empty. Nothing is drawn
     * under a node that something above it has faded or shrunk to nothing, and that comes back
     * empty too.
     */
    fun texts(tag: String): List<String> {
        val node = node(tag)
        if (node.hiddenByAncestor()) return emptyList()
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        val bounds = node.layoutBoundsInRoot
        // A scrolling marquee is read as one title, not as the title and its copy coming round.
        DrawPass(canvas).apply { marqueesAtRest = true }.draw(node, bounds.left - node.x, bounds.top - node.y)
        return canvas.texts()
    }

    /** The text [tag] draws, its runs one per line. */
    fun text(tag: String): String = texts(tag).joinToString("\n")

    /**
     * The screen as text, focus marked: each node's name, tag, box, padding and the room it was
     * given. What every failure here prints, and a line to add to a test while writing it.
     *
     * @param modifiers also list each node's modifier chain.
     */
    fun dump(modifiers: Boolean = false): String = root.dump(modifiers, focus.focused)

    fun assertExists(tag: String) {
        node(tag)
    }

    fun assertDoesNotExist(tag: String) {
        root.findOrNull(tag)?.let {
            throw AssertionError("$tag is still on the screen at ${it.boundsInRoot}:\n" + dump())
        }
    }

    /** Fails unless focus is on the node tagged [tag], saying where it is instead. */
    fun assertFocused(tag: String) {
        val expected = node(tag)
        val actual = focus.focused
        if (actual === expected) return
        val where = actual?.let { it.testTag?.let { tagged -> "#$tagged" } ?: it.name } ?: "nothing"
        throw AssertionError("expected focus on #$tag but it is on $where:\n" + dump())
    }

    /** Fails unless [tag] draws exactly [expected], saying what it drew instead. */
    fun assertText(tag: String, expected: String) {
        val actual = text(tag)
        if (actual != expected) {
            throw AssertionError("expected #$tag to show \"$expected\" but it shows \"$actual\"")
        }
    }

    // --- drawing ---------------------------------------------------------------------------------

    /**
     * Draws one whole frame into the backend's canvas, the way `UiRenderer` does in a game.
     *
     * On the headless backend that is a recording to assert on; on a real one it is pixels to read
     * back. Returns whether anything had changed.
     */
    fun render(): Boolean {
        nanos += FrameNanos
        return renderer.render(viewport, nanos)
    }

    /**
     * How many times each part of the screen is painted in one frame, as the backend's canvas
     * would draw it. See [measureOverdraw] for what is counted.
     *
     * For a test that holds a screen to a fill-rate budget: `assertTrue(ui.overdraw().deepest <= 3)`.
     */
    fun overdraw(cell: Float = 1f): OverdrawMap = measureOverdraw(root, backend.canvas, cell)

    override fun close() = host.dispose()

    // ---------------------------------------------------------------------------------------------

    private fun send(event: PointerEvent) = input.onPointer(event).also { settle() }
    private fun send(event: KeyEvent) = input.onKey(event).also { settle() }
    private fun send(event: GamepadEvent) = input.onGamepad(event).also { settle() }

    /**
     * The middle of [tag] on the screen, refusing a node a player could not click.
     *
     * A node with no area or outside the screen takes no pointer at all, so a click there would
     * quietly do nothing and a test asserting "nothing happened" would pass for the wrong reason.
     */
    private fun centreOf(tag: String): Offset {
        val bounds = node(tag).boundsInRoot
        if (bounds.isEmpty) {
            throw AssertionError("#$tag has no area ($bounds), so nothing can point at it:\n" + dump())
        }
        val centre = bounds.centre
        if (centre.x < 0f || centre.y < 0f || centre.x >= size.width || centre.y >= size.height) {
            throw AssertionError(
                "#$tag is off the ${size.width.toInt()}x${size.height.toInt()} screen at $bounds, so a " +
                    "player cannot point at it:\n" + dump(),
            )
        }
        return centre
    }

    private fun UiNode.hiddenByAncestor(): Boolean {
        var walk: UiNode? = this
        while (walk != null) {
            // The same two tests DrawPass stops a subtree on: faded out, or shrunk to nothing.
            if (walk.resolved.alpha <= 0f || walk.resolved.scale <= 0f) return true
            walk = walk.parent
        }
        return false
    }

    private companion object {
        /** Sixty frames a second, as near as a whole number of nanoseconds gets. */
        const val FrameNanos = 16_666_667L

        /** How many unchanged frames in a row count as a screen that has stopped. See [settle]. */
        const val QuietFrames = 3
    }
}
