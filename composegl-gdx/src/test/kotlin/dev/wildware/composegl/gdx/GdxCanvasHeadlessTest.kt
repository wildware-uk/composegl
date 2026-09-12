package dev.wildware.composegl.gdx

import dev.wildware.composegl.ui.graphics.BlendMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Having a canvas costs no OpenGL.
 *
 * This is the whole point of the change that made the batch and the effects late. A game object
 * that owns a canvas next to its host, its renderer and its focus manager could not be built at
 * all in a plain JVM test while the canvas made a mesh and compiled a shader in its own fields, so
 * focus, input routing and lifecycle were all dragged onto a GPU that has nothing to do with them.
 *
 * [NoGl] is what makes the question answerable in a JVM the other tests have booted a real LibGDX
 * application into; read its comment for why the answer is not simply "there is no GL here".
 */
class GdxCanvasHeadlessTest {

    @Test
    fun `building, reading and disposing a canvas touches no GL`() {
        val touched = NoGl.refusingGl {
            val canvas = GdxCanvas()
            assertEquals(0, canvas.drawCalls, "a canvas that has drawn nothing has made no draw calls")
            // Pushing and popping the state stacks must not build a batch either: a blend mode
            // reaches the driver only through the batch, and outside a frame there is none.
            canvas.pushBlend(BlendMode.Additive)
            canvas.popBlend()
            // Disposal must never build the thing it is about to destroy.
            canvas.dispose()
        }

        // Belt and braces. A refused call already failed this test on its way out of the block
        // above — unless the canvas caught it, which is the case this line is here for.
        assertEquals(emptyList<String>(), touched)
    }

    @Test
    fun `it says yes to turning and to adding, and saying so costs no GL`() {
        // The two queries a caller is told to ask before it draws. A backend that answered yes and
        // then turned nothing and added nothing would be the one dishonesty these two additions
        // could commit, and it would be silent — so the answers are pinned here, where they can be
        // pinned without a display.
        val touched = NoGl.refusingGl {
            val canvas = GdxCanvas()
            assertTrue(canvas.rotatesImages, "it turns a picture on the quad, and the pixel tests agree")
            assertTrue(canvas.supports(BlendMode.Additive), "and it really adds light")
            assertTrue(canvas.supports(BlendMode.SourceOver), "everything can do the ordinary one")
            canvas.dispose()
        }

        assertEquals(emptyList<String>(), touched)
    }
}
