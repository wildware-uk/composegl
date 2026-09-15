package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.input.PointerIcon
import korlibs.render.GameWindow

/**
 * The mouse cursor, as KorGE changes it. One property.
 *
 * On desktop that is the window's own standard cursors, so an I-beam is the one the rest of the
 * machine uses. Where there is no cursor to change, KorGE ignores it, which is the right answer
 * there — so a game wires this up once and never asks what it is running on.
 *
 * @param window the game's window, asked for each time rather than held, because a game builds its
 *   interface before KorGE has made one. Null does nothing.
 */
class KorgeSystemCursor(private val window: () -> GameWindow?) : SystemCursor {

    override fun set(icon: PointerIcon) {
        window()?.cursor = shapeOf(icon)
    }

    companion object {

        /**
         * KorGE's name for each shape. It has no "not allowed", so that one is the plain arrow: a
         * cursor that says nothing is better than one that says something else.
         */
        fun shapeOf(icon: PointerIcon): GameWindow.Cursor = when (icon) {
            PointerIcon.Default -> GameWindow.Cursor.DEFAULT
            PointerIcon.Text -> GameWindow.Cursor.TEXT
            PointerIcon.Hand -> GameWindow.Cursor.HAND
            PointerIcon.Crosshair -> GameWindow.Cursor.CROSSHAIR
            PointerIcon.ResizeHorizontal -> GameWindow.Cursor.RESIZE_EAST
            PointerIcon.ResizeVertical -> GameWindow.Cursor.RESIZE_SOUTH
            PointerIcon.ResizeTopLeftBottomRight -> GameWindow.Cursor.RESIZE_SOUTH_EAST
            PointerIcon.ResizeTopRightBottomLeft -> GameWindow.Cursor.RESIZE_SOUTH_WEST
            PointerIcon.Move -> GameWindow.Cursor.MOVE
            PointerIcon.NotAllowed -> GameWindow.Cursor.DEFAULT
        }
    }
}
