package dev.wildware.composegl.gdx

import org.junit.jupiter.api.Assertions.assertEquals
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
            // Disposal must never build the thing it is about to destroy.
            canvas.dispose()
        }

        // Belt and braces. A refused call already failed this test on its way out of the block
        // above — unless the canvas caught it, which is the case this line is here for.
        assertEquals(emptyList<String>(), touched)
    }
}
