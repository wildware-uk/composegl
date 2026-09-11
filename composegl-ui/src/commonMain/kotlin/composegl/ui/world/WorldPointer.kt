package composegl.ui.world

import composegl.ui.geometry.Offset
import composegl.ui.input.InputSink
import composegl.ui.input.PointerButton
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerType

/**
 * A player pointing at an interface that is in the world: a ray from the camera, or from the end
 * of a hand, meeting the quad a [WorldPanel] is drawn on.
 *
 * The game does the geometry — it is the only thing that knows where its quad is — and says where
 * on the panel the ray landed, in the panel's own units. Everything after that is the same code a
 * mouse goes through: the same router, the same hit testing, the same capture, the same click.
 *
 * ```kotlin
 * val pointer = WorldPointer(router)
 * // each frame, from the game's own raycast:
 * val hit = quad.rayHit(camera.ray)
 * if (hit != null) pointer.aim(hit.x, hit.y, now) else pointer.away(now)
 * if (trigger.justPressed) pointer.press(now)
 * if (trigger.justReleased) pointer.release(now)
 * ```
 *
 * **What a release is tracked by.** A release goes to the panel because *this pointer pressed on
 * this panel*, never because the toolkit said it did something with the press. Pressing on the
 * background of a panel does nothing and returns false, and the release still has to arrive — it
 * is what ends the gesture. Gating the release on the press being consumed is what left v1's
 * in-world panel dead after one click, and it is the single most likely bug to repeat here.
 *
 * The panel moving under the ray takes care of itself: the game recomputes where the ray lands
 * every frame, and a panel's own coordinates are all this ever sees.
 */
class WorldPointer(
    private val sink: InputSink,
    val pointerId: PointerId = PointerId.Mouse,
    private val type: PointerType = PointerType.Mouse,
) {

    /** Where the ray last landed, in the panel's units. Null when it is not on the panel. */
    var at: Offset? = null
        private set

    /** Which buttons are down. Empty nearly always, because most rays are one trigger. */
    val held: Set<PointerButton> get() = down

    private val down = mutableSetOf<PointerButton>()

    /**
     * Whether the press that is going on now started on this panel.
     *
     * The whole of the release rule, in one flag. Deliberately not "the toolkit consumed it".
     */
    var hasPress: Boolean = false
        private set

    private var lastAt = Offset.Zero

    /**
     * The ray landed on the panel, at ([x], [y]) in the panel's own units.
     *
     * Call it every frame it does. Outside the panel's bounds is allowed and is what a drag off
     * the edge looks like: the game can still say where on the panel's plane the ray is, and
     * whatever captured the press wants to hear about it.
     */
    fun aim(x: Float, y: Float, timeMillis: Long = 0L): Boolean {
        val position = Offset(x, y)
        at = position
        lastAt = position
        return sink.onPointer(PointerEvent.Move(pointerId, position, down.toSet(), type, timeMillis))
    }

    /**
     * The ray is not on the panel at all any more — the player looked away.
     *
     * Hover ends. A press does not: a gesture that has begun belongs to whatever captured it until
     * it is released or cancelled, which is the difference between a button that lets go and a
     * button that sticks.
     */
    fun away(timeMillis: Long = 0L) {
        if (at == null) return
        at = null
        sink.onPointer(PointerEvent.Exit(pointerId, lastAt, type, timeMillis))
    }

    /**
     * The trigger went down.
     *
     * Ignored when the ray is not on the panel: a player pressing while looking at the wall is not
     * pressing this panel.
     *
     * @return whether anything under the ray did something with it. A game may use it to decide
     *   whether to also fire the gun; nothing in here does, and nothing should.
     */
    fun press(button: PointerButton = PointerButton.Primary, timeMillis: Long = 0L): Boolean {
        val position = at ?: return false
        down += button
        hasPress = true
        return sink.onPointer(PointerEvent.Press(pointerId, position, button, type, timeMillis))
    }

    /**
     * The trigger came up.
     *
     * Delivered whenever this pointer pressed on this panel, wherever the ray is now — including
     * nowhere, if the player has looked away. What it is *not* conditional on is whether the press
     * was consumed.
     */
    fun release(button: PointerButton = PointerButton.Primary, timeMillis: Long = 0L): Boolean {
        if (!hasPress) return false
        down -= button
        if (down.isEmpty()) hasPress = false
        return sink.onPointer(PointerEvent.Release(pointerId, at ?: lastAt, button, type, timeMillis))
    }

    /**
     * The gesture is over without a click: the panel was destroyed, the player was teleported, the
     * game opened a menu over the top.
     */
    fun cancel(timeMillis: Long = 0L) {
        if (!hasPress && at == null) return
        down.clear()
        hasPress = false
        at = null
        sink.onPointer(PointerEvent.Cancel(pointerId, lastAt, type, timeMillis))
    }
}
