package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.graphics.Cursor
import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.input.PointerIcon

/**
 * The mouse cursor, as LibGDX changes it. One call.
 *
 * On desktop that is GLFW's own standard cursors, so an I-beam is the one the rest of the machine
 * uses. On Android and iOS `setSystemCursor` does nothing at all, which is the right answer there —
 * there is no cursor — so a game wires this up once and never asks what it is running on.
 *
 * @param graphics the engine's, or null to take `Gdx.graphics` when it is asked. Taking it lazily
 *   matters: a game builds its interface before `Gdx.graphics` exists.
 */
class GdxSystemCursor(private val graphics: Graphics? = null) : SystemCursor {

    private val engine: Graphics? get() = graphics ?: Gdx.graphics

    override fun set(icon: PointerIcon) {
        engine?.setSystemCursor(shapeOf(icon))
    }

    companion object {

        /** LibGDX's name for each shape. The two lists are the same ten, so nothing is guessed. */
        fun shapeOf(icon: PointerIcon): Cursor.SystemCursor = when (icon) {
            PointerIcon.Default -> Cursor.SystemCursor.Arrow
            PointerIcon.Text -> Cursor.SystemCursor.Ibeam
            PointerIcon.Hand -> Cursor.SystemCursor.Hand
            PointerIcon.Crosshair -> Cursor.SystemCursor.Crosshair
            PointerIcon.ResizeHorizontal -> Cursor.SystemCursor.HorizontalResize
            PointerIcon.ResizeVertical -> Cursor.SystemCursor.VerticalResize
            PointerIcon.ResizeTopLeftBottomRight -> Cursor.SystemCursor.NWSEResize
            PointerIcon.ResizeTopRightBottomLeft -> Cursor.SystemCursor.NESWResize
            PointerIcon.Move -> Cursor.SystemCursor.AllResize
            PointerIcon.NotAllowed -> Cursor.SystemCursor.NotAllowed
        }
    }
}
