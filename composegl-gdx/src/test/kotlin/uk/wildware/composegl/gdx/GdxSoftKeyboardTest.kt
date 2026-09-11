package uk.wildware.composegl.gdx

import com.badlogic.gdx.backends.headless.mock.input.MockInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phone's keyboard, as far as it can be checked without a phone.
 *
 * What is here is the wiring: asking for the keyboard reaches the engine, and a game that has not
 * started LibGDX yet does not fall over asking. What is not here, and cannot be, is whether a
 * device actually raises the keyboard, whether the field ends up hidden behind it, and whether
 * autocorrect leaves the value alone. Those need a device, and the issue stays open until one.
 */
class GdxSoftKeyboardTest {

    /** LibGDX's own no-op input, with the one call this cares about written down. */
    private class Recording : MockInput() {
        val asked = mutableListOf<Boolean>()
        override fun setOnscreenKeyboardVisible(visible: Boolean) {
            asked += visible
        }
    }

    private val input = Recording()

    private val keyboard = GdxSoftKeyboard(input)

    @Test
    fun `showing and hiding reach the engine`() {
        keyboard.show()
        assertTrue(keyboard.isVisible)

        keyboard.hide()
        assertFalse(keyboard.isVisible)

        assertEquals(listOf(true, false), input.asked)
    }

    @Test
    fun `it starts down`() {
        assertFalse(keyboard.isVisible, "a game that has focused nothing should not have a keyboard up")
        assertTrue(input.asked.isEmpty())
    }

    @Test
    fun `a game that has not started yet can still ask`() {
        // A game builds its interface before `Gdx.input` exists, so a field focusing itself on the
        // first frame must be a no-op rather than a crash on startup.
        val early = GdxSoftKeyboard()

        early.show()
        early.hide()

        assertFalse(early.isVisible)
    }
}
