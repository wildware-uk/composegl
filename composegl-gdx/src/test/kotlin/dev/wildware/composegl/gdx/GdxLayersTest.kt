package dev.wildware.composegl.gdx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The pool behind [dev.wildware.composegl.ui.graphics.UiCanvas.layer].
 *
 * What it has to get right is arithmetic rather than pixels: hand the same picture back rather
 * than making a new one, never hand out one that is still being drawn into, and let go of the ones
 * nobody has wanted for a while.
 */
class GdxLayersTest {

    @Test
    fun `a released layer of the same size comes back`() = Gl.render {
        val layers = GdxLayers()
        try {
            val first = layers.acquire(64, 64)
            layers.release(first)

            assertSame(first, layers.acquire(64, 64), "the same picture should have been reused")
            assertEquals(1, layers.size)
        } finally {
            layers.dispose()
        }
    }

    @Test
    fun `a layer that is still in use is never handed out twice`() = Gl.render {
        val layers = GdxLayers()
        try {
            val outer = layers.acquire(64, 64)
            val inner = layers.acquire(64, 64)

            assertNotSame(outer, inner, "a layer inside a layer would draw into its own parent")
            assertEquals(2, layers.size)
        } finally {
            layers.dispose()
        }
    }

    @Test
    fun `a different size is a different layer`() = Gl.render {
        val layers = GdxLayers()
        try {
            val small = layers.acquire(64, 64)
            layers.release(small)

            assertNotSame(small, layers.acquire(64, 32), "a nearly right size is a soft picture")
        } finally {
            layers.dispose()
        }
    }

    @Test
    fun `a layer nobody wants for a while goes back to the driver`() = Gl.render {
        val layers = GdxLayers(spare = 2)
        try {
            layers.release(layers.acquire(64, 64))

            layers.trim()
            assertEquals(1, layers.size, "one quiet frame is not enough to throw it away")
            layers.trim()
            layers.trim()
            assertEquals(0, layers.size, "three quiet frames are")
        } finally {
            layers.dispose()
        }
    }

    @Test
    fun `a layer in use is kept however long the frame takes`() = Gl.render {
        val layers = GdxLayers(spare = 0)
        try {
            val held = layers.acquire(64, 64)

            repeat(3) { layers.trim() }

            assertEquals(1, layers.size, "it is still being drawn into")
            layers.release(held)
        } finally {
            layers.dispose()
        }
    }
}
