package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.SystemCursor
import dev.wildware.composegl.ui.input.PointerIcon
import korlibs.image.color.Colors
import korlibs.image.vector.buildShape
import korlibs.math.geom.Point
import korlibs.math.geom.vector.StrokeInfo
import korlibs.render.GameWindow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The mouse cursor, as KorGE changes it. One property.
 *
 * On desktop that is the window's own standard cursors, so an I-beam is the one the rest of the
 * machine uses. Where there is no cursor to change, KorGE ignores it, which is the right answer
 * there — so a game wires this up once and never asks what it is running on.
 *
 * KorGE has a standard cursor for nine of the toolkit's ten shapes. The tenth, "not allowed", is
 * drawn: a struck-through ring, the shape every desktop uses, as a KorGE custom cursor. An AWT window
 * turns it into a real system cursor, so it moves as smoothly as the others.
 *
 * @param window the game's window, asked for each time rather than held, because a game builds its
 *   interface before KorGE has made one. Null does nothing.
 */
class KorgeSystemCursor(private val window: () -> GameWindow?) : SystemCursor {

    override fun set(icon: PointerIcon) {
        window()?.cursor = shapeOf(icon)
    }

    companion object {

        /** KorGE's shape for each icon. Every icon has its own. */
        fun shapeOf(icon: PointerIcon): GameWindow.ICursor = when (icon) {
            PointerIcon.Default -> GameWindow.Cursor.DEFAULT
            PointerIcon.Text -> GameWindow.Cursor.TEXT
            PointerIcon.Hand -> GameWindow.Cursor.HAND
            PointerIcon.Crosshair -> GameWindow.Cursor.CROSSHAIR
            PointerIcon.ResizeHorizontal -> GameWindow.Cursor.RESIZE_EAST
            PointerIcon.ResizeVertical -> GameWindow.Cursor.RESIZE_SOUTH
            PointerIcon.ResizeTopLeftBottomRight -> GameWindow.Cursor.RESIZE_SOUTH_EAST
            PointerIcon.ResizeTopRightBottomLeft -> GameWindow.Cursor.RESIZE_SOUTH_WEST
            PointerIcon.Move -> GameWindow.Cursor.MOVE
            PointerIcon.NotAllowed -> NotAllowed
        }

        /**
         * A ring with a bar through it, dark under light so it reads on any background. Drawn round
         * the origin, which KorGE makes the hotspot, so the click lands in the middle of the ring.
         */
        val NotAllowed: GameWindow.CustomCursor by lazy {
            val radius = 8.0
            val shape = buildShape {
                listOf(Colors.BLACK to 5.0, Colors.WHITE to 2.5).forEach { (colour, thickness) ->
                    stroke(colour, StrokeInfo(thickness)) {
                        val steps = 32
                        moveTo(Point(radius, 0.0))
                        for (step in 1..steps) {
                            val angle = 2 * PI * step / steps
                            lineTo(Point(radius * cos(angle), radius * sin(angle)))
                        }
                        val bar = radius * 0.7071
                        moveTo(Point(-bar, -bar))
                        lineTo(Point(bar, bar))
                    }
                }
            }
            GameWindow.CustomCursor(shape, "not-allowed")
        }
    }
}
