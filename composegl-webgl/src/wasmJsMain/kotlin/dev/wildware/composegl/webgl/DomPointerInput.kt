package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.layout.Viewport
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.pointerevents.PointerEvent as DomPointerEvent

/**
 * The page's pointer events — a mouse, a finger or a pen on the canvas — translated into the toolkit's.
 *
 * A translator and nothing else, like the desktop ones: it converts coordinates, remembers which
 * buttons are down, and hands everything to an [InputSink]. What an event means is the toolkit's
 * business.
 *
 * Two conversions, in order: **page to canvas pixels**, since the canvas's CSS box and its backing
 * store differ by the device pixel ratio and whatever the page's stylesheet did to it; then **canvas
 * pixels to design**, through the viewport, which undoes the letterbox and the scale.
 *
 * The pointer is captured on a press, so a drag that leaves the canvas still ends where it should —
 * a toolkit that loses the release is a toolkit whose buttons stick down.
 *
 * Fingers and pens each get their own [PointerId], so two thumbs on a phone are two pointers. The
 * mouse is always [PointerId.Mouse].
 *
 * @param sink where the translated events go.
 * @param canvas the element being drawn on and pointed at.
 * @param viewport asked for on every event, because the page can be resized between two of them.
 * @param textBox the invisible text box a focused field types through, if there is one. A press
 *   leaves the page's focus with it rather than taking it back to the canvas, or a tap on the field
 *   that is already focused would drop a phone's keyboard and cut off an input method.
 */
class DomPointerInput(
    private val sink: InputSink,
    private val canvas: HTMLCanvasElement,
    private val textBox: HTMLElement? = null,
    private val viewport: () -> Viewport,
) {

    /** Which mouse buttons are down. What tells a drag from a hover. */
    private val held = mutableSetOf<PointerButton>()

    /** Fingers and pens currently touching, by the browser's id. */
    private val touching = mutableSetOf<Int>()

    private val listeners = Listeners()

    /** Registers this on the canvas. [detach] takes it off again. */
    fun attach() {
        // Without this a finger on the canvas scrolls or zooms the page instead of reaching it.
        canvas.style.setProperty("touch-action", "none")
        listeners.add(canvas, "pointerdown") { pressed(it.unsafeCast<DomPointerEvent>()) }
        listeners.add(canvas, "pointermove") { moved(it.unsafeCast<DomPointerEvent>()) }
        listeners.add(canvas, "pointerup") { released(it.unsafeCast<DomPointerEvent>()) }
        listeners.add(canvas, "pointercancel") { cancelled(it.unsafeCast<DomPointerEvent>()) }
        listeners.add(canvas, "pointerleave") { left(it.unsafeCast<DomPointerEvent>()) }
        listeners.add(canvas, "wheel", passive = false) { scrolled(it.unsafeCast<WheelEvent>()) }
        // The right button is a button here, not a menu.
        listeners.add(canvas, "contextmenu") { it.preventDefault() }
    }

    fun detach() = listeners.clear()

    fun pressed(event: DomPointerEvent): Boolean {
        val type = typeOf(event)
        val at = design(event)
        val now = event.timeStamp.toDouble().toLong()
        // The keyboard follows the pointer: clicking the canvas is how a page says "this has focus".
        // Unless a field is typing through the text box: the field decides whether it lets go.
        if (textBox == null || document.activeElement !== textBox) focusQuietly(canvas)
        runCatching { canvas.setPointerCapture(event.pointerId) }
        event.preventDefault()
        if (type != PointerType.Mouse) {
            touching += event.pointerId
            return sink.onPointer(PointerEvent.Press(idOf(event), at, PointerButton.Primary, type, now))
        }
        val button = buttonOf(event.button.toInt()) ?: return false
        held += button
        return sink.onPointer(PointerEvent.Press(PointerId.Mouse, at, button, type, now))
    }

    fun moved(event: DomPointerEvent): Boolean {
        val type = typeOf(event)
        val at = design(event)
        val now = event.timeStamp.toDouble().toLong()
        if (type != PointerType.Mouse) {
            val pressed = if (event.pointerId in touching) setOf(PointerButton.Primary) else emptySet()
            return sink.onPointer(PointerEvent.Move(idOf(event), at, pressed, type, now))
        }
        // A second button pressed while one is already down arrives as a move, not a press — the
        // browser calls it a chord. The difference is in the bitmask, so it is read from there.
        var used = chord(event.buttons.toInt(), at, now)
        used = sink.onPointer(PointerEvent.Move(PointerId.Mouse, at, held.toSet(), type, now)) || used
        return used
    }

    fun released(event: DomPointerEvent): Boolean {
        val type = typeOf(event)
        val at = design(event)
        val now = event.timeStamp.toDouble().toLong()
        if (type != PointerType.Mouse) {
            touching -= event.pointerId
            return sink.onPointer(PointerEvent.Release(idOf(event), at, PointerButton.Primary, type, now))
        }
        val button = buttonOf(event.button.toInt()) ?: return false
        if (!held.remove(button)) return false
        return sink.onPointer(PointerEvent.Release(PointerId.Mouse, at, button, type, now))
    }

    /** The browser took the gesture away — a system swipe, an alert. Abandoned without a click. */
    fun cancelled(event: DomPointerEvent): Boolean {
        val type = typeOf(event)
        val at = design(event)
        if (type == PointerType.Mouse) held.clear() else touching -= event.pointerId
        return sink.onPointer(PointerEvent.Cancel(idOf(event), at, type, event.timeStamp.toDouble().toLong()))
    }

    /** The mouse left the canvas: hover ends. A drag does not — capture keeps that coming. */
    fun left(event: DomPointerEvent): Boolean {
        if (typeOf(event) != PointerType.Mouse) return false
        return sink.onPointer(PointerEvent.Exit(PointerId.Mouse, design(event), PointerType.Mouse, event.timeStamp.toDouble().toLong()))
    }

    /**
     * The wheel, in notches, the toolkit's way round.
     *
     * A browser reports pixels, lines or pages depending on the device and the browser. A notch is
     * about a hundred pixels in every current browser and three lines in the ones that count lines,
     * so both become the one notch a desktop wheel reports. Positive is content moving up, which is
     * what the browser already means by a positive delta.
     */
    fun scrolled(event: WheelEvent): Boolean {
        val scale = when (event.deltaMode) {
            WheelEvent.DOM_DELTA_LINE -> 1.0 / 3.0
            WheelEvent.DOM_DELTA_PAGE -> 1.0
            else -> 1.0 / 100.0
        }
        val delta = Offset((event.deltaX * scale).toFloat(), (event.deltaY * scale).toFloat())
        val used = sink.onPointer(PointerEvent.Scroll(PointerId.Mouse, design(event), delta, PointerType.Mouse, event.timeStamp.toDouble().toLong()))
        // Only a wheel the interface used stops the page scrolling, so a page with a canvas in the
        // middle of an article still scrolls past it.
        if (used) event.preventDefault()
        return used
    }

    /** Lets go of every mouse button, without a click. For when the page loses focus. */
    fun cancelAll(): Boolean {
        if (held.isEmpty()) return false
        held.clear()
        return sink.onPointer(PointerEvent.Cancel(PointerId.Mouse, Offset.Zero, PointerType.Mouse, 0L))
    }

    private fun chord(buttons: Int, at: Offset, now: Long): Boolean {
        var used = false
        for ((bit, button) in Bits) {
            val down = buttons and bit != 0
            if (down && button !in held) {
                held += button
                used = sink.onPointer(PointerEvent.Press(PointerId.Mouse, at, button, PointerType.Mouse, now)) || used
            } else if (!down && button in held) {
                held -= button
                used = sink.onPointer(PointerEvent.Release(PointerId.Mouse, at, button, PointerType.Mouse, now)) || used
            }
        }
        return used
    }

    /** Page coordinates to design units: through the canvas's box, then through the viewport. */
    private fun design(event: MouseEvent): Offset {
        val box = canvas.getBoundingClientRect()
        val acrossScale = if (box.width > 0.0) canvas.width / box.width else 1.0
        val downScale = if (box.height > 0.0) canvas.height / box.height else 1.0
        val x = ((event.clientX - box.left) * acrossScale).toFloat()
        val y = ((event.clientY - box.top) * downScale).toFloat()
        return viewport().toDesign(Offset(x, y))
    }

    private fun typeOf(event: DomPointerEvent): PointerType = when (event.pointerType) {
        "touch" -> PointerType.Touch
        "pen" -> PointerType.Stylus
        else -> PointerType.Mouse
    }

    private fun idOf(event: DomPointerEvent): PointerId =
        if (typeOf(event) == PointerType.Mouse) PointerId.Mouse else PointerId(event.pointerId.toLong() + 1)

    private fun buttonOf(button: Int): PointerButton? = when (button) {
        0 -> PointerButton.Primary
        1 -> PointerButton.Tertiary
        2 -> PointerButton.Secondary
        // Back and forward: a game has its own use for them, or the browser does.
        else -> null
    }

    private companion object {
        /** The `buttons` bitmask's order is not `button`'s: the middle button is 4 here and 1 there. */
        val Bits = listOf(1 to PointerButton.Primary, 2 to PointerButton.Secondary, 4 to PointerButton.Tertiary)
    }
}

/**
 * Event listeners added through here, so they can all be taken off again.
 *
 * A page outlives the interface on it — a single-page site that shows a game on one route and an
 * article on the next — and a listener left behind keeps the whole interface alive and still
 * answering keys.
 */
internal class Listeners {

    private class Entry(val target: org.w3c.dom.events.EventTarget, val type: String, val callback: (Event) -> Unit, val passive: Boolean?)

    private val entries = mutableListOf<Entry>()

    fun add(target: org.w3c.dom.events.EventTarget, type: String, passive: Boolean? = null, callback: (Event) -> Unit) {
        if (passive == null) target.addEventListener(type, callback) else addListener(target, type, callback, passive)
        entries += Entry(target, type, callback, passive)
    }

    fun clear() {
        entries.forEach { it.target.removeEventListener(it.type, it.callback) }
        entries.clear()
    }
}

private fun addListener(target: org.w3c.dom.events.EventTarget, type: String, callback: (Event) -> Unit, passive: Boolean) {
    target.addEventListener(type, callback, listenerOptions(passive))
}

private fun listenerOptions(passive: Boolean): org.w3c.dom.AddEventListenerOptions = js("({ passive: passive })")
