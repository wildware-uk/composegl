package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phone's keyboard, as far as it can be checked without a phone: the wiring, and the height a
 * launcher lays out above.
 */
class KorgeSoftKeyboardTest {

    private val window = RecordingWindow()

    @Test
    fun `showing and hiding reach the window`() {
        val keyboard = KorgeSoftKeyboard({ window })

        keyboard.show()
        assertTrue(keyboard.isVisible)
        keyboard.hide()
        assertFalse(keyboard.isVisible)

        assertEquals(listOf(true, false), window.keyboard)
    }

    @Test
    fun `the height counts only while the keyboard is up`() {
        val keyboard = KorgeSoftKeyboard { window }.apply { height = { 300f } }

        assertEquals(0f, keyboard.heightPixels, "a keyboard that was never raised covers nothing")
        keyboard.show()
        assertEquals(300f, keyboard.heightPixels)
        keyboard.hide()
        assertEquals(0f, keyboard.heightPixels)
    }

    @Test
    fun `with nothing to ask the height is zero, and the viewport lays out as before`() {
        val keyboard = KorgeSoftKeyboard({ window })
        keyboard.show()

        assertEquals(0f, keyboard.heightPixels)
    }

    @Test
    fun `the height moves the interface above the keyboard through the safe area`() {
        val keyboard = KorgeSoftKeyboard { window }.apply { height = { 200f } }
        keyboard.show()

        val viewport = Viewport(
            design = Size(400f, 800f),
            physical = Size(400f, 800f),
            policy = ScalePolicy.Fit,
            safeArea = Padding(bottom = keyboard.heightPixels),
        )

        assertEquals(200f, viewport.safeInsets.bottom, "the bottom 200 of the design is behind the keyboard")
    }

    @Test
    fun `a game with no window yet can still ask`() {
        val early = KorgeSoftKeyboard({ null })
        early.show()
        early.hide()
        assertFalse(early.isVisible)
    }
}
