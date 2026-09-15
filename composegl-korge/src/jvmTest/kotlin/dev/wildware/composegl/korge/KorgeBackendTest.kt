package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.text.TextStyle
import korlibs.render.GameWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Everything about the backend that needs no GPU: that it can be built, handed to a game object and
 * measured with, on a machine with no display, without starting a game.
 *
 * Nothing here calls [KorgeGl], and a KorGE game is the only thing in this module that makes a GL
 * context — so a class that quietly started building GPU resources when it was merely constructed
 * could not do it here, and would fail on the first draw call it made rather than pass.
 */
class KorgeBackendTest {

    private fun fonts() = KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(16)) }

    @Test
    fun `a backend is built, measured with and closed with no window and no GL`() {
        val backend: UiBackend = KorgeBackend(fonts())
        val layout = backend.fonts.measure("Continue", TextStyle.Default)
        assertTrue(layout.size.width > 0f)
        (backend as KorgeBackend).close()
    }

    @Test
    fun `the canvas shares the fonts' atlas`() {
        val fonts = fonts()
        val backend = KorgeBackend(fonts)
        // The only observable way: a label measured by these fonts draws from the atlas the canvas uses
        // for its white block — both are page zero of the same object.
        val layout = fonts.measure("x", TextStyle.Default) as KorgeTextLayout
        assertSame(fonts.atlas, layout.atlas)
        assertFalse(backend.canvas.warmedUp, "merely having a canvas builds nothing")
    }

    @Test
    fun `with no window the clipboard remembers what it was given`() {
        val backend = KorgeBackend(fonts())
        assertNull(backend.clipboard.read())
        backend.clipboard.write("Ada")
        assertEquals("Ada", backend.clipboard.read())
        backend.clipboard.write("")
        assertNull(backend.clipboard.read(), "an empty clipboard is nothing to paste")
    }

    @Test
    fun `a window with no clipboard of its own falls back to what was written`() {
        // KorGE's base window has no clipboard: its read answers null.
        val window = object : GameWindow() {}
        val clipboard = KorgeClipboard { window }
        clipboard.write("copied")
        assertEquals("copied", clipboard.read())
    }

    @Test
    fun `the cursor is set on the window, one KorGE shape per toolkit shape`() {
        val window = object : GameWindow() {}
        val cursor = KorgeSystemCursor { window }
        cursor.set(PointerIcon.Text)
        assertEquals(GameWindow.Cursor.TEXT, window.cursor)
        cursor.set(PointerIcon.Hand)
        assertEquals(GameWindow.Cursor.HAND, window.cursor)

        val shapes = PointerIcon.entries.filter { it != PointerIcon.NotAllowed }.map { KorgeSystemCursor.shapeOf(it) }
        assertEquals(shapes.size, shapes.toSet().size, "no two shapes the toolkit tells apart look the same")
    }

    @Test
    fun `with no window the cursor and the keyboard do nothing and do not throw`() {
        KorgeSystemCursor { null }.set(PointerIcon.Move)
        val keyboard = KorgeSoftKeyboard { null }
        keyboard.show()
        assertTrue(keyboard.isVisible)
        keyboard.hide()
        assertFalse(keyboard.isVisible)
    }
}
