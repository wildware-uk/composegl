package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.node.UiNode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A pad, pretending to be a mouse.
 *
 * Focus hopping is the right way to drive a menu and the wrong way to drive a world map, a drag
 * and drop inventory or a skill tree: there is nothing to hop between on a map, and an inventory
 * wants a thing picked up and carried. This is the other answer — a cursor the left stick pushes
 * around, whose South button is the mouse's left button.
 *
 * It sends ordinary [PointerEvent]s to [pointer], so nothing on the screen knows a pad is behind
 * them: hover lights up, a press captures, a drag drags, a click clicks. Hand it the
 * [PointerRouter] itself rather than a [SourceAware] wrapped round it. The pad events already told
 * the tracker the player is on a pad, and a cursor that also said "mouse" would flip every prompt
 * to keyboard glyphs the moment the stick moved.
 *
 * What makes a stick cursor bearable rather than a chore:
 *
 * 1. **A curve.** Speed grows with the square of how far the stick is pushed, so a small push is
 *    fine aim and a full one crosses the screen in about a second and a half.
 * 2. **Friction over targets.** While [snapToTargets] is on, the cursor slows to [targetSlowdown]
 *    over anything it could click, so a player aiming at a button does not sail past it.
 * 3. **A snap.** When the stick is let go near something clickable — within [snapRadius] — the
 *    cursor glides to its middle. Letting go roughly on a button is then letting go on it.
 *
 * Off by default, and inert while off: every event is left for whatever comes after it, which is
 * usually a [GamepadNavigator]. So a sink asks this first — `cursor.onGamepad(e) ||
 * pad.onGamepad(e)` — and a screen switches between the two by switching [enabled], which is what
 * the `VirtualCursor` composable does while it is on the screen.
 *
 * Nothing here polls. [frame] is called once a frame, after layout, with the game's own clock, and
 * is where the cursor actually moves; events only record what the pad is doing.
 *
 * @param root the tree the cursor points at, for its bounds and for finding what is clickable.
 * @param pointer where the pointer events go. The screen's [PointerRouter].
 * @param pointerId the pointer this cursor is, apart from the mouse so the two keep their own hover
 *   and their own capture.
 * @param deadZone how far a stick must travel before the cursor moves. Smaller than the navigator's,
 *   because a cursor wants the small pushes a menu throws away.
 */
@Stable
class GamepadCursor(
    private val root: UiNode,
    private val pointer: InputSink,
    val pointerId: PointerId = Id,
    private val deadZone: Float = 0.15f,
) {

    /**
     * Whether the pad is driving the cursor. Switched off, the cursor lets go of whatever it was
     * holding — without a click — and stops hovering.
     */
    var enabled by mutableStateOf(false)

    /** How far a fully pushed stick moves the cursor in a second, in the tree's units. */
    var speed = 900f

    /** Whether the cursor slows over clickable things and snaps to them when the stick is let go. */
    var snapToTargets = true

    /** The fraction of [speed] the cursor keeps over something clickable, when [snapToTargets] is on. */
    var targetSlowdown = 0.45f

    /** How close to something clickable a let-go stick has to leave the cursor for it to snap there. */
    var snapRadius = 32f

    /** How many wheel notches a second the right stick turns, pushed all the way. */
    var scrollSpeed = 12f

    /**
     * Where the cursor may go, in the root's coordinates. Null is the whole tree.
     *
     * A map screen with a sidebar can keep the cursor over the map by setting this to the map.
     */
    var area: Rect? = null

    /** Where the cursor is, in the root's coordinates. Compose state, so drawing it follows it. */
    var position: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Whether the cursor is over something it could click. The drawn cursor changes colour on it. */
    var overTarget: Boolean by mutableStateOf(false)
        private set

    /** Whether South is holding the cursor's button down. */
    var pressed: Boolean by mutableStateOf(false)
        private set

    /**
     * The `VirtualCursor`s on the screen switching this on, in the order they came. Kept so that when
     * two are on at once, the first to leave does not switch the cursor off under the other, and so
     * only the first draws the arrow and sets the speed. Compose state, so the next in line takes
     * over drawing the moment the first goes.
     */
    internal val claims = mutableStateListOf<Any>()

    private var stickX = 0f
    private var stickY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private val dpad = mutableSetOf<GamepadButton>()

    /** Whether the cursor was on last frame, so switching off is noticed once. */
    private var active = false

    /** Whether the cursor has ever been put anywhere. The first time it is on, it starts in the middle. */
    private var placed = false

    /**
     * Whether a let-go cursor has finished snapping, or had nothing to snap to.
     *
     * Kept so a cursor standing still costs nothing: the search for a target walks the tree, and a
     * cursor that has already arrived does not need to ask again until the stick moves.
     */
    private var settled = false

    /** The pad that last moved or pressed the cursor, so only its going away lets go of a drag. */
    private var driver: GamepadId? = null

    private var lastFrame = -1L

    /**
     * The time stamped on every event: the last frame's. A press lands between frames, and the
     * router reads these stamps to tell a double click from two slow ones.
     */
    private val now: Long get() = lastFrame.coerceAtLeast(0L)

    fun onGamepad(event: GamepadEvent): Boolean {
        when (event) {
            is GamepadEvent.Disconnected -> {
                // A second pad being pulled out says nothing about the one holding the cursor.
                if (driver != null && event.gamepadId != driver) return false
                // The pad went mid-drag. Let go of everything, and cancel rather than release, so
                // nothing is dropped somewhere the player did not choose.
                driver = null
                stickX = 0f; stickY = 0f; scrollX = 0f; scrollY = 0f
                dpad.clear()
                if (pressed) cancelPress()
                return false
            }
            is GamepadEvent.Connected -> return false
            is GamepadEvent.Axis -> {
                // Recorded whether or not the cursor is on, so a stick already held when a map
                // opens moves the cursor straight away instead of waiting for the stick to twitch.
                driver = event.gamepadId
                when (event.axis) {
                    GamepadAxis.LeftX -> stickX = event.value
                    GamepadAxis.LeftY -> stickY = event.value
                    GamepadAxis.RightX -> scrollX = event.value
                    GamepadAxis.RightY -> scrollY = event.value
                    else -> return false
                }
                return enabled
            }
            is GamepadEvent.ButtonDown -> return when (event.button) {
                GamepadButton.DpadUp, GamepadButton.DpadDown,
                GamepadButton.DpadLeft, GamepadButton.DpadRight -> {
                    dpad += event.button
                    enabled
                }
                GamepadButton.South -> if (enabled) { driver = event.gamepadId; press() } else false
                else -> false
            }
            is GamepadEvent.ButtonUp -> return when (event.button) {
                GamepadButton.DpadUp, GamepadButton.DpadDown,
                GamepadButton.DpadLeft, GamepadButton.DpadRight -> {
                    dpad -= event.button
                    enabled
                }
                // Only a press this cursor made. A South that went down to the navigator before the
                // cursor came on has to go back up to the navigator, or its button stays held.
                GamepadButton.South -> if (pressed) release() else false
                else -> false
            }
        }
    }

    /**
     * One frame of cursor: move with the stick, or snap once it is let go, and scroll.
     *
     * Call it after layout, since what the cursor is over depends on where things are.
     *
     * @param timeMillis the game's own monotonic clock. A long gap — a pause, a breakpoint — counts
     *   as one short frame rather than flinging the cursor across the screen.
     */
    fun frame(timeMillis: Long) {
        val seconds = if (lastFrame < 0L) 0f else (timeMillis - lastFrame).coerceIn(0L, MaxStepMillis) / 1000f
        lastFrame = timeMillis

        if (!enabled) {
            if (active) standDown()
            return
        }
        if (!active) {
            // Nothing has a size before the first layout, and a cursor switched on then would
            // start in the corner rather than the middle. Switched on in the frame a screen opens,
            // it waits the one frame for the tree to be laid out.
            if (bounds().isEmpty) return
            active = true
            settled = false
            val start = if (placed) position else bounds().centre
            placed = true
            // Sent even when nothing moved, so what is under the cursor is hovered from the start.
            send(start, force = true)
        } else if (clamp(position) != position) {
            // The screen or the area shrank under a still cursor. Arithmetic, so asked every frame.
            settled = false
            send(position, force = false)
        }

        if (!aim(seconds) && snapToTargets && !pressed && !settled) snap(seconds)
        scroll(seconds)
    }

    /**
     * Puts the cursor at [to], clamped to the [area], as though the stick had carried it there.
     *
     * For a screen that wants the cursor somewhere to begin with — on the player's marker when the
     * map opens. Returns whether it moved.
     */
    fun moveTo(to: Offset): Boolean {
        placed = true
        settled = false
        return send(to, force = false)
    }

    // --- the stick -----------------------------------------------------------------------------

    /** Moves with the stick or the d-pad. Returns whether either was pushed. */
    private fun aim(seconds: Float): Boolean {
        var x: Float
        var y: Float
        val digitalX = (if (GamepadButton.DpadRight in dpad) 1f else 0f) - (if (GamepadButton.DpadLeft in dpad) 1f else 0f)
        val digitalY = (if (GamepadButton.DpadDown in dpad) 1f else 0f) - (if (GamepadButton.DpadUp in dpad) 1f else 0f)
        if (digitalX != 0f || digitalY != 0f) {
            // Full speed, the same in every direction: a diagonal is not faster than a straight.
            val length = sqrt(digitalX * digitalX + digitalY * digitalY)
            x = digitalX / length
            y = digitalY / length
        } else {
            val length = sqrt(stickX * stickX + stickY * stickY)
            // A round dead zone rather than one per axis, so a slight diagonal is not snapped onto a
            // straight line the way a square one would.
            if (length < deadZone) return false
            val push = ((length - deadZone) / (1f - deadZone)).coerceAtMost(1f)
            val curve = push * push
            x = stickX / length * curve
            y = stickY / length * curve
        }

        settled = false
        if (seconds == 0f) return true
        val slow = if (snapToTargets && overTarget) targetSlowdown else 1f
        val step = speed * slow * seconds
        send(Offset(position.x + x * step, position.y + y * step), force = false)
        return true
    }

    /** Glides towards the middle of whatever clickable thing the let-go cursor is near. */
    private fun snap(seconds: Float) {
        val target = nearestTarget(position, snapRadius)
        if (target == null) {
            settled = true
            return
        }
        val centre = clamp(target.boundsInRoot.centre)
        val dx = centre.x - position.x
        val dy = centre.y - position.y
        if (abs(dx) < SnapDone && abs(dy) < SnapDone) {
            send(centre, force = false)
            settled = true
            return
        }
        // Eased rather than jumped, so the player sees where the cursor went and why.
        val fraction = (seconds * SnapRate).coerceAtMost(1f)
        send(Offset(position.x + dx * fraction, position.y + dy * fraction), force = false)
    }

    private fun scroll(seconds: Float) {
        val x = if (abs(scrollX) < deadZone) 0f else scrollX
        val y = if (abs(scrollY) < deadZone) 0f else scrollY
        if ((x == 0f && y == 0f) || seconds == 0f) return
        // Stick down is scrolling down, which is a positive wheel delta.
        val notches = scrollSpeed * seconds
        pointer.onPointer(PointerEvent.Scroll(pointerId, position, Offset(x * notches, y * notches), Type, timeMillis = now))
    }

    // --- the button ----------------------------------------------------------------------------

    private fun press(): Boolean {
        // Taken whether or not anything was under the cursor: the navigator must not press the
        // focused button somewhere else on the screen because the cursor was over empty map. And
        // taken but not pressed before the cursor has been placed, which would press the corner.
        if (pressed || !active) return true
        pressed = true
        pointer.onPointer(PointerEvent.Press(pointerId, position, type = Type, timeMillis = now))
        return true
    }

    private fun release(): Boolean {
        pressed = false
        pointer.onPointer(PointerEvent.Release(pointerId, position, type = Type, timeMillis = now))
        return true
    }

    private fun cancelPress() {
        pressed = false
        pointer.onPointer(PointerEvent.Cancel(pointerId, position, Type, timeMillis = now))
    }

    /** Switched off: nothing held, nothing hovered, and the stick forgotten until it moves again. */
    private fun standDown() {
        active = false
        if (pressed) cancelPress()
        pointer.onPointer(PointerEvent.Exit(pointerId, position, Type, timeMillis = now))
        overTarget = false
    }

    // --- the machinery -------------------------------------------------------------------------

    /** Moves the cursor and tells the pointer. [force] sends the move even if it is where it was. */
    private fun send(to: Offset, force: Boolean): Boolean {
        val clamped = clamp(to)
        val moved = clamped != position
        if (!moved && !force) return false
        position = clamped
        overTarget = nearestTarget(clamped, 0f) != null
        val buttons = if (pressed) HeldPrimary else emptySet()
        pointer.onPointer(PointerEvent.Move(pointerId, clamped, buttons, Type, timeMillis = now))
        return moved
    }

    private fun bounds(): Rect = area ?: Rect.of(0f, 0f, root.width, root.height)

    /**
     * Inside [bounds], and short of its right and bottom edges: a rectangle holds its left edge and
     * not its right, so a cursor pressed against the right of the screen still hits what is there.
     */
    private fun clamp(point: Offset): Offset {
        val bounds = bounds()
        val x = point.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - Edge))
        val y = point.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - Edge))
        return if (x == point.x && y == point.y) point else Offset(x, y)
    }

    /**
     * The clickable node nearest [point] and no further than [radius], or null.
     *
     * Clickable means a `clickable` or an enabled `focusable` — the things a player would aim at.
     * Walked in draw order and ties go to the later node, so among overlapping targets the one on
     * top, and the deepest, wins, as it does for the pointer router. Invisible subtrees are skipped,
     * and so is anything a clip hides from [point].
     *
     * It walks the whole tree and reads each node's bounds, which is why it only runs while the
     * cursor is moving or snapping, never for a cursor standing still.
     */
    private fun nearestTarget(point: Offset, radius: Float): UiNode? {
        var best: UiNode? = null
        var bestDistance = Float.MAX_VALUE
        fun walk(node: UiNode) {
            val resolved = node.resolved
            if (resolved.alpha <= 0f) return
            val clipped = resolved.clip != null
            val bounds = if (clipped || resolved.click != null || resolved.focusable != null) node.boundsInRoot else null
            if (clipped && bounds != null && distance(point, bounds) > radius) return
            val target = resolved.click?.enabled == true || resolved.focusable?.enabled == true
            if (target && bounds != null && !bounds.isEmpty) {
                val distance = distance(point, bounds)
                if (distance <= radius && distance <= bestDistance) {
                    best = node
                    bestDistance = distance
                }
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return best
    }

    /** How far [point] is from [rect]: zero inside it, the gap to the nearest edge outside it. */
    private fun distance(point: Offset, rect: Rect): Float {
        if (point in rect) return 0f
        val dx = maxOf(rect.left - point.x, 0f, point.x - rect.right)
        val dy = maxOf(rect.top - point.y, 0f, point.y - rect.bottom)
        return sqrt(dx * dx + dy * dy)
    }

    override fun toString(): String = "GamepadCursor(enabled=$enabled, at=$position)"

    companion object {
        /** The pointer a pad cursor is, unless it is given another. Never one a platform hands out. */
        val Id = PointerId(-1L)

        /**
         * The kind of pointer every event says it is: a ray, something aimed by whatever the player
         * is already holding. Not a mouse, because the router gives a mouse's hover to the desktop
         * cursor, and a pad passing over a button would turn the idle mouse into a hand.
         */
        val Type = PointerType.Ray

        /** The longest a frame is allowed to be, so a pause does not fling the cursor. */
        private const val MaxStepMillis = 100L

        /** How much of the way to a target a snap covers in a second, as a rate. */
        private const val SnapRate = 15f

        /** Close enough to a target's middle to stop gliding and sit on it. */
        private const val SnapDone = 0.5f

        /** How far short of a right or bottom edge the cursor stops. */
        private const val Edge = 0.5f

        private val HeldPrimary = setOf(PointerButton.Primary)
    }
}
