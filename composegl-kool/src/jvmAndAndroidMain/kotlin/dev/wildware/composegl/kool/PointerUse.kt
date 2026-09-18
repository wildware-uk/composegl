package dev.wildware.composegl.kool

/**
 * Whether the interface used one of Kool's pointers in one of Kool's frames: the verdict
 * [ComposeGlScene.onPointerUsed] reports, so a game can leave alone a click the interface took.
 *
 * @param pointer Kool's id for the pointer, as `Pointer.id` gives it: `PointerInput.MOUSE_POINTER_ID` for
 *   the mouse, the touch's own id for a finger.
 * @param frame Kool's frame the pointer was read in, as `Time.frameCount` gave it then. The verdict
 *   arrives a frame or more after that one, so match on this rather than on when it arrives.
 * @param used whether any event made from the pointer that frame was used: a press or release on a
 *   control, a scroll the interface scrolled, a move a control handled. False when the pointer did something the
 *   interface ignored, or nothing at all.
 */
data class PointerUse(val pointer: Int, val frame: Int, val used: Boolean)
