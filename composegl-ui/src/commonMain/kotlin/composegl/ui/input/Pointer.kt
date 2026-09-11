package composegl.ui.input

import composegl.ui.geometry.Offset
import kotlin.jvm.JvmInline

/**
 * What is doing the pointing.
 *
 * [Ray] is a panel standing in the 3D world, hit by the game's own raycast. It behaves exactly like
 * a mouse as far as the toolkit is concerned, which is the point: an in-world interface is not a
 * special case.
 */
enum class PointerType { Mouse, Touch, Stylus, Ray }

/**
 * Which pointer this is.
 *
 * A mouse is always the same one. Fingers are not: a platform hands out an id per touch and reuses
 * it later, so a press and its release are matched by id rather than by position.
 */
@JvmInline
value class PointerId(val value: Long) {
    companion object {
        val Mouse = PointerId(0)
    }
}

enum class PointerButton { Primary, Secondary, Tertiary }

/**
 * Something a pointer did.
 *
 * Hover is not an event. It is [Move] with nothing pressed, which keeps the backend's job to
 * "report what happened" and leaves "what does that mean" to the toolkit.
 */
sealed interface PointerEvent {

    val pointerId: PointerId
    val position: Offset
    val type: PointerType

    /** Monotonic, from the game's own clock. Used for double-click and hover delays. */
    val timeMillis: Long

    data class Press(
        override val pointerId: PointerId,
        override val position: Offset,
        val button: PointerButton = PointerButton.Primary,
        override val type: PointerType = PointerType.Mouse,
        override val timeMillis: Long = 0L,
    ) : PointerEvent

    data class Move(
        override val pointerId: PointerId,
        override val position: Offset,
        /** Which buttons are still held, so a drag is distinguishable from a hover. */
        val pressed: Set<PointerButton> = emptySet(),
        override val type: PointerType = PointerType.Mouse,
        override val timeMillis: Long = 0L,
    ) : PointerEvent

    data class Release(
        override val pointerId: PointerId,
        override val position: Offset,
        val button: PointerButton = PointerButton.Primary,
        override val type: PointerType = PointerType.Mouse,
        override val timeMillis: Long = 0L,
    ) : PointerEvent

    /**
     * The gesture was taken away — the window lost focus, the platform started a system gesture, a
     * finger was lifted outside. Whatever had captured this pointer must let go without firing a
     * click.
     */
    data class Cancel(
        override val pointerId: PointerId,
        override val position: Offset,
        override val type: PointerType = PointerType.Mouse,
        override val timeMillis: Long = 0L,
    ) : PointerEvent

    /** A wheel or a two-finger scroll. Positive [delta] scrolls content up and left. */
    data class Scroll(
        override val pointerId: PointerId,
        override val position: Offset,
        val delta: Offset,
        override val type: PointerType = PointerType.Mouse,
        override val timeMillis: Long = 0L,
    ) : PointerEvent

    /**
     * The pointer left the surface entirely, so nothing should be drawn as hovered.
     *
     * Distinct from [Cancel]: the mouse leaving a window ends hover but does not abandon a drag,
     * and every toolkit that conflates the two produces buttons that stick.
     */
    data class Exit(
        override val pointerId: PointerId,
        override val position: Offset,
        override val type: PointerType = PointerType.Mouse,
        override val timeMillis: Long = 0L,
    ) : PointerEvent
}

/**
 * The same event, somewhere else.
 *
 * Used to hand a node an event in its own coordinates rather than the root's, so a handler can ask
 * "where in me?" without knowing where it is on the screen.
 */
fun PointerEvent.movedTo(position: Offset): PointerEvent = when (this) {
    is PointerEvent.Press -> copy(position = position)
    is PointerEvent.Move -> copy(position = position)
    is PointerEvent.Release -> copy(position = position)
    is PointerEvent.Cancel -> copy(position = position)
    is PointerEvent.Scroll -> copy(position = position)
    is PointerEvent.Exit -> copy(position = position)
}
