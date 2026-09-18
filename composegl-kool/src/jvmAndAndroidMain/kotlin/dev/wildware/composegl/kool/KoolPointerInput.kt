package dev.wildware.composegl.kool

import de.fabmax.kool.input.Pointer
import de.fabmax.kool.input.PointerInput
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.layout.Viewport

/**
 * Kool's mouse and touches, translated into the toolkit's pointer events.
 *
 * A translator and nothing else: which node was hit, and whether that made a click, are the toolkit's
 * decisions. It converts coordinates, remembers which buttons each pointer holds so a drag can be told
 * from a hover, and hands every event to an [InputSink].
 *
 * **Kool reports state, once a frame.** Where other engines send an event per change, Kool hands its
 * pointer listeners each pointer's position, the buttons down now and the buttons that changed this
 * frame. So this compares a frame with the one before. Kool already splits a press and a release that
 * arrived in the same frame into one click, and leaves the button marked as changed with the same
 * state either side; that is read back as a press followed by a release, so a quick click is still a
 * click. A pointer seen for the first time is read by which buttons are down, not by what changed:
 * Kool reuses a pointer's slot, and a mouse coming back into the window can carry the changed bits of
 * a button let go outside it, which would otherwise click whatever it came back over.
 *
 * **Coordinates.** Kool's pointer positions are the framebuffer's pixels, top-left origin — the pixels a
 * [Viewport] is measured in — so they go through [Viewport.toDesign] and nothing else.
 *
 * **Scroll** is in the wheel's notches, as GLFW reports them to Kool, turned round: GLFW says which way
 * the wheel went and the toolkit which way the content should move.
 *
 * @param sink where the translated events go.
 * @param viewport asked for on every frame, because the window can be resized between two of them.
 * @param clock monotonic milliseconds, for double-click and hover timing.
 */
internal class KoolPointerInput(
    private val sink: InputSink,
    private val viewport: () -> Viewport,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private class Seen(val kool: Int, var at: Offset, val held: MutableSet<PointerButton> = mutableSetOf())

    /** Every pointer seen in the last frame, by the toolkit's id. */
    private val seen = LinkedHashMap<PointerId, Seen>()

    /**
     * One of Kool's frames of pointers, as plain values taken with [sample]: the ones not listed have
     * gone. Returns, for every pointer listed and every one that went, whether the interface used any
     * event made from it, in the order they were handled. [ComposeGlScene] takes the values on Kool's
     * update thread and hands them over here on its render thread.
     *
     * @param frame Kool's frame the pointers were read in, carried into each [PointerUse].
     */
    fun onFrame(pointers: List<Sample>, frame: Int = 0): List<PointerUse> {
        val now = clock()
        val uses = mutableListOf<PointerUse>()
        val present = HashSet<PointerId>()
        pointers.forEach { pointer ->
            var used = false
            val id = if (pointer.id == PointerInput.MOUSE_POINTER_ID) PointerId.Mouse else PointerId(1L + pointer.id)
            val type = if (id == PointerId.Mouse) PointerType.Mouse else PointerType.Touch
            present += id
            val at = viewport().toDesign(Offset(pointer.x, pointer.y))
            val last = seen[id]
            val seenNow = last ?: Seen(pointer.id, at).also { seen[id] = it }
            if (last == null || last.at != at) {
                seenNow.at = at
                used = sink.onPointer(PointerEvent.Move(id, at, seenNow.held.toSet(), type, now)) || used
            }
            Buttons.forEach { (mask, button) ->
                val wasDown = button in seenNow.held
                val isDown = pointer.buttons and mask != 0
                val changed = last != null && pointer.changed and mask != 0
                fun press() = sink.onPointer(PointerEvent.Press(id, at, button, type, now)).also { seenNow.held += button }
                fun release() = sink.onPointer(PointerEvent.Release(id, at, button, type, now)).also { seenNow.held -= button }
                used = when {
                    !wasDown && isDown -> press()
                    wasDown && !isDown -> release()
                    // Changed and back again inside one frame: a quick click, or a quick lift and press.
                    changed && !isDown -> press() or release()
                    changed && isDown -> release() or press()
                    else -> false
                } || used
            }
            if (pointer.scrollX != 0f || pointer.scrollY != 0f) {
                used = sink.onPointer(PointerEvent.Scroll(id, at, Offset(turned(pointer.scrollX), turned(pointer.scrollY)), type, now)) || used
            }
            uses += PointerUse(pointer.id, frame, used)
        }
        seen.keys.filter { it !in present }.forEach { id ->
            val gone = checkNotNull(seen.remove(id))
            val type = if (id == PointerId.Mouse) PointerType.Mouse else PointerType.Touch
            // A mouse that left the window ends hover, not a drag; Kool drops what was held with it, so
            // the drag is abandoned rather than finished. A finger that lifted is a release.
            val used = when {
                gone.held.isNotEmpty() && type == PointerType.Mouse ->
                    sink.onPointer(PointerEvent.Cancel(id, gone.at, type, now)) or sink.onPointer(PointerEvent.Exit(id, gone.at, type, now))
                gone.held.isNotEmpty() -> gone.held.toList().map { sink.onPointer(PointerEvent.Release(id, gone.at, it, type, now)) }.any { it }
                type == PointerType.Mouse -> sink.onPointer(PointerEvent.Exit(id, gone.at, type, now))
                else -> false
            }
            uses += PointerUse(gone.kool, frame, used)
        }
        return uses
    }

    /** One of Kool's pointers in one frame. */
    class Sample(
        val id: Int,
        val x: Float,
        val y: Float,
        val buttons: Int,
        val changed: Int,
        val scrollX: Float = 0f,
        val scrollY: Float = 0f,
    )

    /** What this reads of one of Kool's pointers, taken now, so it can be handed to another thread. */
    fun sample(pointer: Pointer) =
        Sample(pointer.id, pointer.pos.x, pointer.pos.y, pointer.buttonMask, pointer.buttonEventMask, pointer.scroll.x, pointer.scroll.y)

    /** Zero spelled out, because negating it gives minus zero, which does not equal zero. */
    private fun turned(amount: Float): Float = if (amount == 0f) 0f else -amount

    private companion object {
        /** Kool's buttons with a meaning in an interface. Back and forward stay the game's. */
        val Buttons = listOf(
            PointerInput.LEFT_BUTTON_MASK to PointerButton.Primary,
            PointerInput.RIGHT_BUTTON_MASK to PointerButton.Secondary,
            PointerInput.MIDDLE_BUTTON_MASK to PointerButton.Tertiary,
        )
    }
}
