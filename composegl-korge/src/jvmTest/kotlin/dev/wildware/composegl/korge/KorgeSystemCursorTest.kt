package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.input.PointerIcon
import korlibs.math.geom.Size
import korlibs.render.GameWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The mouse cursor, through KorGE: a shape for every icon, and each reaching the window. */
class KorgeSystemCursorTest {

    @Test
    fun `a shape reaches the window as KorGE's name for it`() {
        val window = RecordingWindow()
        val cursor = KorgeSystemCursor { window }

        cursor.set(PointerIcon.Text)
        cursor.set(PointerIcon.Hand)
        cursor.set(PointerIcon.Default)

        assertEquals(listOf(GameWindow.Cursor.TEXT, GameWindow.Cursor.HAND, GameWindow.Cursor.DEFAULT), window.cursors)
    }

    @Test
    fun `every icon has a cursor of its own`() {
        val shapes = PointerIcon.entries.map { KorgeSystemCursor.shapeOf(it) }

        assertEquals(PointerIcon.entries.size, shapes.toSet().size, "two icons came out as one shape: $shapes")
    }

    @Test
    fun `not allowed is a drawn picture with its hotspot in the middle of the ring`() {
        val picture = KorgeSystemCursor.NotAllowed.createBitmap(Size(32, 32), native = false)
        val bitmap = picture.bitmap.toBMP32()

        val opaque = (0 until bitmap.height).sumOf { y -> (0 until bitmap.width).count { x -> bitmap[x, y].a > 128 } }
        assertTrue(opaque > 40, "the ring and bar should be drawn, found $opaque opaque pixels")
        assertTrue(abs(picture.hotspot.x - bitmap.width / 2) <= 2, "hotspot x ${picture.hotspot.x} in a ${bitmap.width} wide picture")
        assertTrue(abs(picture.hotspot.y - bitmap.height / 2) <= 2, "hotspot y ${picture.hotspot.y} in a ${bitmap.height} tall picture")
    }

    @Test
    fun `a game with no window yet can still ask`() {
        KorgeSystemCursor { null }.set(PointerIcon.NotAllowed)
    }

    @Test
    fun `a real KorGE window takes every shape`() {
        val taken = KorgeGl.render {
            val cursor = KorgeSystemCursor { KorgeGl.stage.views.gameWindow }
            PointerIcon.entries.forEach { cursor.set(it) }
            cursor.set(PointerIcon.Default)
            KorgeGl.stage.views.gameWindow.cursor
        }

        assertEquals(GameWindow.Cursor.DEFAULT, taken)
    }
}
