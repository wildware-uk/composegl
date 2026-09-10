package composegl.ui.backend

import composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonospaceFontProviderTest {

    private val fonts = MonospaceFontProvider()
    private val style = TextStyle(size = 10f)   // ten-pixel text, so a character is six pixels

    @Test
    fun `a short string is one line as wide as its characters`() {
        val layout = fonts.measure("PLAY", style)

        assertEquals(24f, layout.size.width, 0.001f, "four characters at six pixels")
        assertEquals(12.5f, layout.size.height, 0.001f, "one line at 1.25 line height")
        assertEquals(1, layout.lineCount)
    }

    @Test
    fun `measuring the same text twice gives the same answer`() {
        val once = fonts.measure("the same string", style, maxWidth = 40f)
        val twice = fonts.measure("the same string", style, maxWidth = 40f)

        assertEquals(once.size, twice.size)
        assertEquals(once.lineCount, twice.lineCount)
    }

    @Test
    fun `wrapping breaks between words, never inside one that fits`() {
        // 60 pixels is ten characters a line. "hello there" is eleven with its space, so each
        // five-letter word gets a line of its own rather than being split across two.
        val layout = fonts.measure("hello there world", style, maxWidth = 60f)

        assertEquals(3, layout.lineCount)
        assertTrue(layout.toString().contains("hello / there / world"), "words stayed whole: $layout")
    }

    @Test
    fun `a wider line fits more words on it`() {
        val layout = fonts.measure("hello there world", style, maxWidth = 120f)

        assertEquals(1, layout.lineCount, "twenty characters a line takes the lot")
    }

    @Test
    fun `a word too long for a line is broken rather than overflowing`() {
        val layout = fonts.measure("supercalifragilistic", style, maxWidth = 30f)

        assertEquals(4, layout.lineCount)
        assertTrue(layout.size.width <= 30f, "nothing sticks out past the wrap width")
    }

    @Test
    fun `newlines break lines even without wrapping`() {
        val layout = fonts.measure("one\ntwo\nthree", style)

        assertEquals(3, layout.lineCount)
        assertEquals(30f, layout.size.width, 0.001f, "as wide as the longest line, not the whole string")
    }

    @Test
    fun `maxLines cuts it off and marks where`() {
        val layout = fonts.measure("one two three four five six", style, maxWidth = 60f)
        val limited = fonts.measure(
            "one two three four five six",
            style.copy(maxLines = 2),
            maxWidth = 60f,
        )

        assertTrue(layout.lineCount > 2)
        assertEquals(2, limited.lineCount)
        assertTrue(limited.toString().contains("…"), "the cut is visible: $limited")
    }

    @Test
    fun `empty text measures to nothing wide and one line tall`() {
        val layout = fonts.measure("", style)

        assertEquals(0f, layout.size.width)
        assertEquals(1, layout.lineCount)
    }
}

class HeadlessBackendTest {

    @Test
    fun `a whole backend with no window, no OpenGL and no engine`() {
        val backend = HeadlessBackend()

        assertEquals(0, backend.canvas.calls.size)
        assertNull(backend.textures.texture("nothing"))
        assertNull(backend.clipboard.read())
        assertFalse(backend.softKeyboard.isVisible)
    }

    @Test
    fun `the clipboard remembers`() {
        val clipboard = InMemoryClipboard()

        clipboard.write("copied")

        assertEquals("copied", clipboard.read())
    }

    @Test
    fun `the keyboard records what it was asked, so a field can be checked`() {
        val keyboard = RecordingSoftKeyboard()

        keyboard.show()
        keyboard.hide()

        assertEquals(listOf("show", "hide"), keyboard.requests)
        assertFalse(keyboard.isVisible)
    }

    @Test
    fun `a missing texture is null rather than an exception`() {
        val source = MapTextureSource(mapOf("panel" to FakeTexture(16, 16)))

        assertEquals(16, source.texture("panel")?.width)
        assertNull(source.texture("panel-that-nobody-made"), "a typo in a skin should not crash a game")
    }
}
