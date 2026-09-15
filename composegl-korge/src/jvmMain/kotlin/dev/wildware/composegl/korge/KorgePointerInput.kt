package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.event.MouseButton
import korlibs.event.MouseEvent
import korlibs.event.Touch
import korlibs.event.TouchEvent
import korlibs.math.geom.Point

/**
 * KorGE's mouse and touches, translated into the toolkit's.
 *
 * A translator and nothing else. It converts coordinates, remembers which buttons and fingers are
 * down so a drag can be told from a hover, and hands everything to an [InputSink]. Which node was
 * hit, and whether that was a click, are the toolkit's decisions.
 *
 * **Coordinates.** KorGE's AWT window multiplies the mouse position by the display's scale before it
 * dispatches (`BaseAwtGameWindow.handleMouseEvent`), so a [MouseEvent]'s `x` and `y` already count
 * framebuffer pixels — the same pixels a [Viewport] is measured in. That is the opposite of LibGDX,
 * and nothing here scales them again. Touches reach the stage in the stage's own units, so
 * [stageToWindow] brings them back first.
 *
 * **Copies.** KorGE makes a touch out of every mouse event and a mouse event out of every single
 * touch, marked `emulated`. Both copies are ignored, so a click is one click and a tap one tap.
 *
 * **Scroll.** The toolkit's scroll is in notches. An AWT window reports the wheel's notches as they
 * are, labelled pixels, so the deltas go through untouched.
 *
 * Nothing is filtered by position: a release outside the window still releases, because a widget
 * that captured the pointer must hear about it wherever it happens.
 *
 * @param sink where the translated events go.
 * @param viewport asked for on every event, because the window can be resized between two of them.
 * @param clock monotonic milliseconds, for double-click and hover timing.
 * @param stageToWindow from the stage's units to the window's pixels, for touches.
 */
class KorgePointerInput(
    private val sink: InputSink,
    private val viewport: () -> Viewport,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val stageToWindow: (Point) -> Point = { it },
) {

    init {
        Modifiers.isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    }

    /** Which mouse buttons are down. */
    private val held = mutableSetOf<PointerButton>()

    /** Which fingers are down, by KorGE's touch id. */
    private val fingers = mutableSetOf<Int>()

    /** Where each pointer was last seen, so a cancellation has somewhere to say it happened. */
    private val lastSeen = mutableMapOf<PointerId, Offset>()

    /** One KorGE mouse event, in window pixels. */
    fun onMouse(event: MouseEvent): Boolean {
        if (event.emulated) return false
        val at = design(PointerId.Mouse, Offset(event.x.toFloat(), event.y.toFloat()))
        val now = clock()
        return when (event.type) {
            MouseEvent.Type.DOWN -> {
                val button = event.button.asPointerButton() ?: return false
                held += button
                sink.onPointer(PointerEvent.Press(PointerId.Mouse, at, button, PointerType.Mouse, now))
            }
            MouseEvent.Type.UP -> {
                val button = event.button.asPointerButton() ?: return false
                held -= button
                sink.onPointer(PointerEvent.Release(PointerId.Mouse, at, button, PointerType.Mouse, now))
            }
            // A hover is a move with nothing held; a drag is the same event with something held.
            MouseEvent.Type.MOVE, MouseEvent.Type.DRAG ->
                sink.onPointer(PointerEvent.Move(PointerId.Mouse, at, held.toSet(), PointerType.Mouse, now))
            MouseEvent.Type.SCROLL ->
                sink.onPointer(PointerEvent.Scroll(PointerId.Mouse, at, Offset(event.scrollDeltaX(event.scrollDeltaMode), event.scrollDeltaY(event.scrollDeltaMode)), PointerType.Mouse, now))
            MouseEvent.Type.EXIT -> exited()
            // Click is the router's to decide, and enter says nothing a move does not.
            MouseEvent.Type.CLICK, MouseEvent.Type.ENTER -> false
        }
    }

    /** One KorGE touch frame: every finger in it, each with whether it arrived, stayed or left. */
    fun onTouch(event: TouchEvent): Boolean {
        if (event.emulated) return false
        var used = false
        val now = clock()
        event.touches.forEach { touch ->
            val id = touchId(touch.id)
            val window = stageToWindow(touch.p)
            val at = design(id, Offset(window.x.toFloat(), window.y.toFloat()))
            used = when (touch.status) {
                Touch.Status.ADD -> {
                    if (!fingers.add(touch.id)) return@forEach
                    sink.onPointer(PointerEvent.Press(id, at, PointerButton.Primary, PointerType.Touch, now))
                }
                Touch.Status.KEEP -> {
                    // A finger that was already down and did not move this frame is still listed.
                    if (event.type != TouchEvent.Type.MOVE || touch.id !in fingers) return@forEach
                    sink.onPointer(PointerEvent.Move(id, at, Primary, PointerType.Touch, now))
                }
                Touch.Status.REMOVE -> {
                    if (!fingers.remove(touch.id)) return@forEach
                    sink.onPointer(PointerEvent.Release(id, at, PointerButton.Primary, PointerType.Touch, now))
                }
            } || used
        }
        return used
    }

    /**
     * Abandons every gesture in progress, without firing a click. Call it when the window loses
     * focus or the game pauses: the platform will not necessarily send a release for what was down.
     */
    fun cancelAll() {
        val now = clock()
        if (held.isNotEmpty()) {
            held.clear()
            sink.onPointer(PointerEvent.Cancel(PointerId.Mouse, lastSeen[PointerId.Mouse] ?: Offset.Zero, PointerType.Mouse, now))
        }
        fingers.toList().forEach { finger ->
            fingers.remove(finger)
            val id = touchId(finger)
            sink.onPointer(PointerEvent.Cancel(id, lastSeen[id] ?: Offset.Zero, PointerType.Touch, now))
        }
    }

    /**
     * The mouse left the window, so nothing should be drawn as hovered. Not a cancel: a mouse leaving
     * ends hover and does not end a drag.
     */
    fun exited(): Boolean =
        sink.onPointer(PointerEvent.Exit(PointerId.Mouse, lastSeen[PointerId.Mouse] ?: Offset.Zero, PointerType.Mouse, clock()))

    private fun design(id: PointerId, window: Offset): Offset =
        viewport().toDesign(window).also { lastSeen[id] = it }

    private fun MouseButton.asPointerButton(): PointerButton? = when (this) {
        MouseButton.LEFT -> PointerButton.Primary
        MouseButton.RIGHT -> PointerButton.Secondary
        MouseButton.MIDDLE -> PointerButton.Tertiary
        // Back and forward have no meaning in an interface toolkit; the game keeps them.
        else -> null
    }

    private companion object {
        val Primary = setOf(PointerButton.Primary)

        /** Fingers are numbered after the mouse, so a finger never shares the mouse's hover or capture. */
        fun touchId(id: Int) = PointerId(1L + id)
    }
}
