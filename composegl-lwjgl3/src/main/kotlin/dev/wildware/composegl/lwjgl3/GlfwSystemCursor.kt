package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.input.PointerIcon
import org.lwjgl.glfw.GLFW

/**
 * The mouse cursor, as GLFW changes it.
 *
 * GLFW makes a standard cursor once and then attaches it to a window, so each shape is made the
 * first time it is asked for and kept until [close]. A shape the desktop has no picture for — an
 * X11 cursor theme from before GLFW 3.4 has no "not allowed" — comes back from GLFW as nothing,
 * and the window shows the ordinary arrow instead, which is always a correct answer.
 *
 * The two functions are parameters so the bookkeeping can be checked without a window; left out,
 * they are GLFW's own.
 *
 * @param create makes a standard cursor from a GLFW shape, returning 0 when there is no such
 *   picture.
 * @param attach shows a cursor made by [create] on the window, or the default arrow for 0.
 * @param destroy lets go of a cursor made by [create].
 */
class GlfwSystemCursor internal constructor(
    private val create: (Int) -> Long,
    private val attach: (Long) -> Unit,
    private val destroy: (Long) -> Unit,
) : SystemCursor, AutoCloseable {

    constructor(window: GlfwWindow) : this(
        create = { shape -> GLFW.glfwCreateStandardCursor(shape) },
        attach = { cursor -> GLFW.glfwSetCursor(window.handle, cursor) },
        destroy = { cursor -> GLFW.glfwDestroyCursor(cursor) },
    )

    /** Every shape made so far, including the ones GLFW had nothing for, so those are asked once. */
    private val made = mutableMapOf<PointerIcon, Long>()

    override fun set(icon: PointerIcon) {
        // The arrow is GLFW's default and needs nothing made: 0 puts it back.
        val cursor = if (icon == PointerIcon.Default) 0L else made.getOrPut(icon) { create(shapeOf(icon)) }
        attach(cursor)
    }

    /**
     * Lets go of every cursor made. Close it before the window: GLFW puts a window showing a
     * destroyed cursor back to the arrow itself, so nothing here touches the window, but the last
     * window closing shuts GLFW down and takes every cursor with it.
     */
    override fun close() {
        made.values.filter { it != 0L }.forEach(destroy)
        made.clear()
    }

    companion object {

        /** GLFW's name for each shape. GLFW 3.4 has the same ten as the toolkit. */
        fun shapeOf(icon: PointerIcon): Int = when (icon) {
            PointerIcon.Default -> GLFW.GLFW_ARROW_CURSOR
            PointerIcon.Text -> GLFW.GLFW_IBEAM_CURSOR
            PointerIcon.Hand -> GLFW.GLFW_POINTING_HAND_CURSOR
            PointerIcon.Crosshair -> GLFW.GLFW_CROSSHAIR_CURSOR
            PointerIcon.ResizeHorizontal -> GLFW.GLFW_RESIZE_EW_CURSOR
            PointerIcon.ResizeVertical -> GLFW.GLFW_RESIZE_NS_CURSOR
            PointerIcon.ResizeTopLeftBottomRight -> GLFW.GLFW_RESIZE_NWSE_CURSOR
            PointerIcon.ResizeTopRightBottomLeft -> GLFW.GLFW_RESIZE_NESW_CURSOR
            PointerIcon.Move -> GLFW.GLFW_RESIZE_ALL_CURSOR
            PointerIcon.NotAllowed -> GLFW.GLFW_NOT_ALLOWED_CURSOR
        }
    }
}
