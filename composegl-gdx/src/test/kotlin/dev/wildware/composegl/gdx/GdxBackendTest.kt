package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.Batch
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

/**
 * The LibGDX [dev.wildware.composegl.ui.backend.UiBackend], which is the class the documentation
 * now asks a game to hold instead of a [GdxCanvas].
 *
 * Every test here runs inside [NoGl], with every OpenGL call refused, and that is the point: the
 * whole reason to hold the interface is that the object holding it can be built in a plain JVM
 * test, and a backend that needed a GPU to exist would quietly take that back.
 */
class GdxBackendTest {

    /** A [Batch] that does nothing and remembers what it was asked to do. Never touches a driver. */
    private class SpyBatch {
        val calls = mutableListOf<String>()

        val batch: Batch = Proxy.newProxyInstance(
            Batch::class.java.classLoader,
            arrayOf(Batch::class.java),
        ) { _, method, _ ->
            calls += method.name
            // A primitive return of null would come back as a NullPointerException on unboxing,
            // so every shape LibGDX's interface uses gets an empty answer of the right kind.
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        } as Batch
    }

    @Test
    fun `a backend is the four pieces, wired up, with no GPU anywhere`() {
        val fonts = HeadlessFonts.registry()

        NoGl.refusingGl {
            val backend = GdxBackend(fonts)
            try {
                assertSame(fonts, backend.fonts, "the registry handed over is the one the toolkit measures with")
                assertEquals(0, backend.canvas.drawCalls, "a canvas nothing has drawn on has made no draw calls")
                assertTrue(backend.clipboard is GdxClipboard)
                assertTrue(backend.softKeyboard is GdxSoftKeyboard)
                assertNull(backend.textures.texture("nothing was registered"))
            } finally {
                backend.dispose()
            }
        }
    }

    /**
     * The claim [GdxBackend] makes about its clipboard and its keyboard: they read `Gdx.app` and
     * `Gdx.input` when they are used, not when they are made, so a game can build its interface
     * before the engine is up. Nothing was checking it.
     */
    @Test
    fun `a backend can be built before the engine exists`() {
        val fonts = HeadlessFonts.registry()

        // [NoGl] holds the render loop still, which is what makes it safe to take these two away:
        // they belong to the whole JVM and the tests that need pixels are reading them.
        NoGl.refusingGl {
            val app = Gdx.app
            val input = Gdx.input
            try {
                Gdx.app = null
                Gdx.input = null

                val backend = GdxBackend(fonts)
                try {
                    assertNull(backend.clipboard.read(), "nothing to paste, rather than a crash")
                    backend.softKeyboard.show()
                    assertTrue(backend.softKeyboard.isVisible, "asked for, even with no engine to ask")
                } finally {
                    backend.dispose()
                }
            } finally {
                Gdx.app = app
                Gdx.input = input
            }
        }
    }

    /**
     * Who owns what. The fonts were handed over to be used for the life of the backend, so the
     * backend lets go of them; the sprite batch is the game's own drawing and stays the game's to
     * dispose. Disposing something a caller is still using is the sort of mistake that shows up as
     * a blank screen three frames later, so it is pinned here rather than left to the KDoc.
     */
    @Test
    fun `disposal lets go of the fonts and leaves the game's sprite batch alone`() {
        val sprites = SpyBatch()
        val fonts = HeadlessFonts.registry()

        NoGl.refusingGl {
            GdxBackend(fonts, sprites.batch).dispose()
        }

        assertFalse("dispose" in sprites.calls, "the sprite batch is the game's: ${sprites.calls}")
        assertThrows<IllegalStateException>(
            "the registry was disposed, so it has no fonts left to measure with",
        ) { fonts.measure("anything", TextStyle(family = "test", size = 16f)) }
    }
}
