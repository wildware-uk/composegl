package composegl.ui.input

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the player is using right now, and what an interface should do about it.
 *
 * One field of Compose state, so reading it subscribes to it: an interface that shows **E** when
 * the player is on a keyboard and **A** when they pick up a pad recomposes the moment they do, with
 * nothing reloaded and no event plumbed to every prompt on screen.
 *
 * The rule is "last one wins", and that is the whole heuristic. It is also the right one: a player
 * who puts the pad down and touches the mouse has said what they want with the clearest signal
 * available, and anything cleverer gets it wrong for somebody.
 *
 * @param initial what to assume before anything has happened. A console starts on [InputSource.Gamepad];
 *   a desktop game starts on [InputSource.Mouse] and is corrected by the first event either way.
 */
@Stable
class InputSourceTracker(initial: InputSource = InputSource.Mouse) {

    var current: InputSource by mutableStateOf(initial)
        private set

    /** The player is driving something with a cursor, so there is a cursor to draw. */
    val isPointing: Boolean get() = current == InputSource.Mouse || current == InputSource.Touch

    /**
     * Whether a focus ring belongs on screen.
     *
     * A ring is a cursor for people who have no cursor. Drawn while somebody is using a mouse it
     * is just a second highlight fighting the hover, which is why every interface that draws it
     * unconditionally looks wrong on a desktop.
     */
    val showsFocusRing: Boolean get() = !isPointing

    fun saw(source: InputSource) {
        if (source != current) current = source
    }

    /**
     * A pointer did something.
     *
     * [PointerEvent.Exit] is deliberately not a signal: the mouse leaving the window says nothing
     * about what the player picked up, and a pad that moves focus off screen must not flip the
     * prompts back to mouse glyphs because the cursor happened to drift out.
     */
    fun saw(event: PointerEvent) {
        if (event is PointerEvent.Exit) return
        saw(event.type.source)
    }

    fun saw(event: KeyEvent) = saw(InputSource.Keyboard)

    fun saw(event: TextEvent) = saw(InputSource.Keyboard)

    fun saw(event: GamepadEvent) = saw(InputSource.Gamepad)

    override fun toString(): String = "InputSourceTracker($current)"
}

/**
 * Which device a kind of pointer belongs to.
 *
 * A stylus counts as touch: it is the same interface decision — big targets, no hover, no cursor.
 * A ray is an in-world panel being pointed at, and whatever is aiming it is already the current
 * source, so it leaves the answer alone by claiming to be a mouse.
 */
val PointerType.source: InputSource
    get() = when (this) {
        PointerType.Mouse, PointerType.Ray -> InputSource.Mouse
        PointerType.Touch, PointerType.Stylus -> InputSource.Touch
    }

/**
 * Everything passing through [sink] also tells [tracker] what the player is using.
 *
 * A decorator rather than a job for the router, because the router only knows about pointers and
 * the question is about all four kinds of event. Wrap the sink a backend pushes into and the
 * tracking is done.
 */
class SourceAware(private val tracker: InputSourceTracker, private val sink: InputSink) : InputSink {

    override fun onPointer(event: PointerEvent): Boolean {
        tracker.saw(event)
        return sink.onPointer(event)
    }

    override fun onKey(event: KeyEvent): Boolean {
        tracker.saw(event)
        return sink.onKey(event)
    }

    override fun onText(event: TextEvent): Boolean {
        tracker.saw(event)
        return sink.onText(event)
    }

    override fun onGamepad(event: GamepadEvent): Boolean {
        tracker.saw(event)
        return sink.onGamepad(event)
    }
}
