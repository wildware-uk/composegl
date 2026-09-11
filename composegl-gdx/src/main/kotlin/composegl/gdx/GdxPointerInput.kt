package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Buttons
import com.badlogic.gdx.InputAdapter
import composegl.ui.geometry.Offset
import composegl.ui.input.InputSink
import composegl.ui.input.PointerButton
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerType
import composegl.ui.layout.Viewport

/**
 * LibGDX's pointer events, translated into the toolkit's.
 *
 * A translator and nothing else. It converts coordinates, remembers which buttons are down so that
 * a drag can be told from a hover, and hands everything to an [InputSink]. It makes no decision
 * about what an event means — which node it hit, whether it started a drag, whether it counts as a
 * click. Those are the toolkit's, and keeping them out of here is what lets the same decisions be
 * tested with hand-written events and no engine at all.
 *
 * Three conversions happen, in this order:
 *
 * 1. **Window to back-buffer.** LibGDX reports the pointer in logical window units. On a Retina or
 *    a scaled display the framebuffer is larger, and the viewport is measured in framebuffer
 *    pixels, so the two have to be reconciled before anything else is done.
 * 2. **Back-buffer to design.** The viewport undoes the letterbox and the scale, so a widget sees
 *    the coordinates it was laid out in.
 * 3. **Button numbers to names.** Buttons LibGDX has and the toolkit does not — back and forward —
 *    are declined rather than mapped onto something that looks similar.
 *
 * Nothing here filters by position. A press that ends with the mouse outside the window still
 * releases, and a release outside the letterbox bars still arrives with negative coordinates,
 * because a widget that captured a pointer has to hear about the release wherever it happens. A
 * toolkit that drops those is a toolkit whose buttons stick down.
 *
 * @param sink where the translated events go — usually the toolkit's input handling.
 * @param viewport asked for on every event, because a window can be resized between two of them.
 * @param type what is doing the pointing. Desktop is a mouse; a touch platform passes `Touch`.
 * @param hdpiScale framebuffer pixels per logical window unit. Read from LibGDX by default.
 * @param clock monotonic milliseconds, for double-click and hover timing.
 */
class GdxPointerInput(
    private val sink: InputSink,
    private val viewport: () -> Viewport,
    private val type: PointerType = PointerType.Mouse,
    private val hdpiScale: () -> Float = { Gdx.graphics.backBufferScale },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : InputAdapter() {

    /** Which buttons are down, per pointer. What tells a drag from a hover. */
    private val held = mutableMapOf<Int, MutableSet<PointerButton>>()

    /** Where each pointer was last seen, so a cancellation has somewhere to say it happened. */
    private val lastSeen = mutableMapOf<Int, Offset>()

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val named = button.asPointerButton() ?: return false
        val at = design(screenX, screenY, pointer)
        held.getOrPut(pointer) { mutableSetOf() } += named
        return sink.onPointer(PointerEvent.Press(id(pointer), at, named, type, clock()))
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val named = button.asPointerButton() ?: return false
        val at = design(screenX, screenY, pointer)
        held[pointer]?.remove(named)
        return sink.onPointer(PointerEvent.Release(id(pointer), at, named, type, clock()))
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        val at = design(screenX, screenY, pointer)
        return sink.onPointer(PointerEvent.Move(id(pointer), at, buttonsOn(pointer), type, clock()))
    }

    override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
        val at = design(screenX, screenY, MousePointer)
        // The same event as a drag, with nothing held. Hover is a reading of this, not a kind of it.
        return sink.onPointer(PointerEvent.Move(PointerId.Mouse, at, emptySet(), type, clock()))
    }

    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val at = design(screenX, screenY, pointer)
        held.remove(pointer)
        return sink.onPointer(PointerEvent.Cancel(id(pointer), at, type, clock()))
    }

    override fun scrolled(amountX: Float, amountY: Float): Boolean {
        val at = lastSeen[MousePointer] ?: Offset.Zero
        return sink.onPointer(
            PointerEvent.Scroll(PointerId.Mouse, at, Offset(amountX, amountY), type, clock()),
        )
    }

    /**
     * Abandons every gesture in progress, without firing a click.
     *
     * Call it when the window loses focus, is iconified, or the game opens a modal of its own. The
     * platform will not necessarily send a release for a button that was down when the window went
     * away, and a widget still holding a capture from ten minutes ago is a bug that only shows up
     * on somebody else's machine.
     */
    fun cancelAll() {
        val now = clock()
        held.keys.toList().forEach { pointer ->
            held.remove(pointer)
            val at = lastSeen[pointer] ?: Offset.Zero
            sink.onPointer(PointerEvent.Cancel(id(pointer), at, type, now))
        }
    }

    /**
     * The pointer left the surface, so nothing should be drawn as hovered.
     *
     * Deliberately separate from [cancelAll]: a mouse leaving the window ends hover and does not
     * end a drag, and conflating the two is how a button gets stuck in its pressed state.
     */
    fun exited() {
        val at = lastSeen[MousePointer] ?: Offset.Zero
        sink.onPointer(PointerEvent.Exit(PointerId.Mouse, at, type, clock()))
    }

    /** Window units to design units, remembering where the pointer ended up. */
    private fun design(screenX: Int, screenY: Int, pointer: Int): Offset {
        val scale = hdpiScale()
        val at = viewport().toDesign(Offset(screenX * scale, screenY * scale))
        lastSeen[pointer] = at
        return at
    }

    private fun buttonsOn(pointer: Int): Set<PointerButton> = held[pointer]?.toSet() ?: emptySet()

    private fun id(pointer: Int) = PointerId(pointer.toLong())

    private fun Int.asPointerButton(): PointerButton? = when (this) {
        Buttons.LEFT -> PointerButton.Primary
        Buttons.RIGHT -> PointerButton.Secondary
        Buttons.MIDDLE -> PointerButton.Tertiary
        // Back and forward have no meaning in an interface toolkit, and inventing one for them
        // would put them in front of a game that has its own use for them.
        else -> null
    }

    private companion object {
        /** LibGDX numbers the mouse as pointer zero, which is also the toolkit's mouse id. */
        const val MousePointer = 0
    }
}
