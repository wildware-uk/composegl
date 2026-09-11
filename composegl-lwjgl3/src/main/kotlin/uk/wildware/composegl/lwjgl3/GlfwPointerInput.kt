package uk.wildware.composegl.lwjgl3

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.input.InputSink
import uk.wildware.composegl.ui.input.Modifiers
import uk.wildware.composegl.ui.input.PointerButton
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerType
import uk.wildware.composegl.ui.layout.Viewport
import org.lwjgl.glfw.GLFW

/**
 * GLFW's mouse events, translated into the toolkit's.
 *
 * A translator and nothing else. It converts coordinates, remembers which buttons are down so a
 * drag can be told from a hover, and hands everything to an [InputSink]. What an event *means* —
 * which node it hit, whether it began a drag, whether it counts as a click — is the toolkit's
 * business, and keeping those decisions out of here is what lets them be tested with hand-written
 * events and no window at all.
 *
 * The methods below are the whole translation and can be called directly, which is how they are
 * tested. [attachTo] wires them to a real window.
 *
 * Two conversions happen, in this order:
 *
 * 1. **Window to framebuffer.** GLFW reports the cursor in logical window units. On a scaled
 *    display the framebuffer is larger, and the viewport is measured in framebuffer pixels.
 * 2. **Framebuffer to design.** The viewport undoes the letterbox and the scale, so a widget sees
 *    the coordinates it was laid out in.
 *
 * Nothing here filters by position. A press that ends with the cursor outside the window still
 * releases, because a widget holding a capture has to hear about the release wherever it happens.
 * A toolkit that drops those is a toolkit whose buttons stick down.
 *
 * @param sink where the translated events go.
 * @param viewport asked for on every event, because a window can be resized between two of them.
 * @param type what is doing the pointing. A desktop window is a mouse.
 * @param pixelScale framebuffer pixels per logical window unit.
 * @param clock monotonic milliseconds, for double-click and hover timing.
 */
class GlfwPointerInput(
    private val sink: InputSink,
    private val viewport: () -> Viewport,
    private val type: PointerType = PointerType.Mouse,
    private val pixelScale: () -> Float = { 1f },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    init {
        // The toolkit has no platform to ask, so a backend tells it. `Modifiers.isPrimary` is the
        // difference between Command-C and Control-C, and only something running on a desktop JVM
        // is in a position to know which one this is.
        Modifiers.isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    }

    /** Which buttons are down. What tells a drag from a hover. */
    private val held = mutableSetOf<PointerButton>()

    /** Where the cursor was last seen, so an event without a position has somewhere to happen. */
    private var lastSeen = Offset.Zero

    /** Registers this as [window]'s mouse handling. Whatever was there before is replaced. */
    fun attachTo(window: GlfwWindow) {
        GLFW.glfwSetCursorPosCallback(window.handle) { _, x, y -> moved(x, y) }
        GLFW.glfwSetMouseButtonCallback(window.handle) { _, button, action, _ -> button(button, action) }
        GLFW.glfwSetScrollCallback(window.handle) { _, x, y -> scrolled(x, y) }
        // Leaving ends hover. It deliberately does not end a drag.
        GLFW.glfwSetCursorEnterCallback(window.handle) { _, inside -> if (!inside) exited() }
        // A window that goes to the background will not necessarily report the release of a button
        // that was down when it went, so every gesture is abandoned rather than left hanging.
        GLFW.glfwSetWindowFocusCallback(window.handle) { _, focused -> if (!focused) cancelAll() }
    }

    /** The cursor moved to a point in logical window units. */
    fun moved(windowX: Double, windowY: Double): Boolean {
        val at = design(windowX, windowY)
        // The same event whether or not anything is held: hover is a reading of a move, not a
        // kind of one.
        return sink.onPointer(PointerEvent.Move(PointerId.Mouse, at, held.toSet(), type, clock()))
    }

    /** A GLFW mouse button went down or up. Buttons the toolkit has no name for are ignored. */
    fun button(button: Int, action: Int): Boolean {
        val named = button.asPointerButton() ?: return false
        val now = clock()
        return when (action) {
            GLFW.GLFW_PRESS -> {
                held += named
                sink.onPointer(PointerEvent.Press(PointerId.Mouse, lastSeen, named, type, now))
            }
            GLFW.GLFW_RELEASE -> {
                held -= named
                sink.onPointer(PointerEvent.Release(PointerId.Mouse, lastSeen, named, type, now))
            }
            // GLFW_REPEAT never arrives for a mouse button, and inventing a meaning for it would
            // be a second press that nothing ever releases.
            else -> false
        }
    }

    /** The wheel turned, in GLFW's units: up and right are positive. */
    fun scrolled(x: Double, y: Double): Boolean = sink.onPointer(
        PointerEvent.Scroll(PointerId.Mouse, lastSeen, Offset(turned(x), turned(y)), type, clock()),
    )

    /**
     * One axis of the wheel, the toolkit's way round.
     *
     * GLFW says which way the wheel went; the toolkit says which way the content should move, and
     * they are opposites. Zero is spelled out rather than negated, because negating it gives minus
     * zero, which is not equal to zero and would land in an event somebody compares.
     */
    private fun turned(amount: Double): Float = if (amount == 0.0) 0f else -amount.toFloat()

    /**
     * The cursor left the window, so nothing should be drawn as hovered.
     *
     * Deliberately separate from [cancelAll]: a mouse leaving the window ends hover and does not
     * end a drag, and conflating the two is how a button gets stuck in its pressed state.
     */
    fun exited(): Boolean =
        sink.onPointer(PointerEvent.Exit(PointerId.Mouse, lastSeen, type, clock()))

    /**
     * Abandons whatever gesture was in progress, without firing a click.
     *
     * Call it when the window loses focus or the game opens a modal of its own. Nothing happens
     * when no button was down, so it is safe to call on every focus change.
     */
    fun cancelAll(): Boolean {
        if (held.isEmpty()) return false
        held.clear()
        return sink.onPointer(PointerEvent.Cancel(PointerId.Mouse, lastSeen, type, clock()))
    }

    /** Window units to design units, remembering where the cursor ended up. */
    private fun design(windowX: Double, windowY: Double): Offset {
        val scale = pixelScale()
        val at = viewport().toDesign(Offset(windowX.toFloat() * scale, windowY.toFloat() * scale))
        lastSeen = at
        return at
    }

    private fun Int.asPointerButton(): PointerButton? = when (this) {
        GLFW.GLFW_MOUSE_BUTTON_LEFT -> PointerButton.Primary
        GLFW.GLFW_MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
        // Back and forward have no meaning in an interface toolkit, and inventing one would put
        // them in front of a game that has its own use for them.
        else -> null
    }
}
