package uk.wildware.composegl.gdx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The clipboard adapter, with no LibGDX application: the engine's end is an interface too. */
class GdxClipboardTest {

    /** LibGDX's own interface, implemented by a test rather than by a platform. */
    private class Fake : com.badlogic.gdx.utils.Clipboard {
        private var held: String? = null
        override fun hasContents(): Boolean = !held.isNullOrEmpty()
        override fun getContents(): String? = held
        override fun setContents(content: String?) {
            held = content
        }
    }

    private val engine = Fake()
    private val clipboard = GdxClipboard(engine)

    @Test
    fun `what is written is what comes back`() {
        clipboard.write("Ryland Vos")

        assertEquals("Ryland Vos", engine.contents)
        assertEquals("Ryland Vos", clipboard.read())
    }

    @Test
    fun `an empty clipboard is nothing to paste`() {
        assertNull(clipboard.read(), "null and empty mean the same thing to a name box")

        engine.contents = ""
        assertNull(clipboard.read())
    }
}
