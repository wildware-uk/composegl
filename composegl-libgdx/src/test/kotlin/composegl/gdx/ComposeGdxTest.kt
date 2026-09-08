package composegl.gdx

import com.badlogic.gdx.graphics.Cursor.SystemCursor
import composegl.ComposeGlUnsupportedException
import composegl.CursorShape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ComposeGdxTest {

    @Test
    fun `GL 2 is refused with the exact line to change`() {
        val thrown = assertThrows<ComposeGlUnsupportedException> { requireGlVersion(2, 0) }
        val message = thrown.message.orEmpty()
        assertEquals(
            "ComposeGL needs OpenGL 3.0+, but this context is 2.0. " +
                "Call config.useOpenGL3(true, 3, 2) before Lwjgl3Application.",
            message,
        )
    }

    @Test
    fun `GL 3 and newer are accepted`() {
        requireGlVersion(3, 0)
        requireGlVersion(3, 2)
        requireGlVersion(4, 6)
    }

    @Test
    fun `every cursor shape maps to a system cursor`() {
        assertEquals(SystemCursor.Arrow, systemCursorFor(CursorShape.Default))
        assertEquals(SystemCursor.Ibeam, systemCursorFor(CursorShape.Text))
        assertEquals(SystemCursor.Hand, systemCursorFor(CursorShape.Hand))
        assertEquals(SystemCursor.Crosshair, systemCursorFor(CursorShape.Crosshair))
        assertEquals(CursorShape.entries.size, CursorShape.entries.map(::systemCursorFor).size)
    }

    @Test
    fun `density is the backbuffer to window ratio, not the monitor DPI`() {
        assertEquals(1f, densityOf(1280, 1280), "an ordinary display")
        assertEquals(2f, densityOf(2560, 1280), "a retina display")
        assertEquals(1.5f, densityOf(1920, 1280), "fractional scaling")
        assertEquals(1f, densityOf(0, 0), "a minimised window must not divide by zero")
    }
}
