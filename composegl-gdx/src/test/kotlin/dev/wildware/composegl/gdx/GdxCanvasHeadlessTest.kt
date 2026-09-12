package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Having a canvas costs no OpenGL.
 *
 * This is the whole point of the change that made the batch and the effects late. A game object
 * that owns a canvas next to its host, its renderer and its focus manager could not be built at
 * all in a plain JVM test while the canvas made a mesh and compiled a shader in its own fields, so
 * focus, input routing and lifecycle were all dragged onto a GPU that has nothing to do with them.
 *
 * The test deliberately does *not* say "there is no GL, so nothing can have been called". The
 * module boots a real LibGDX application on a daemon thread for the tests that need pixels, and it
 * sets the static `Gdx.gl` for the whole JVM; tests share one JVM in an order nobody specifies, so
 * a test written that way would pass for the wrong reason or fail depending on what ran first.
 *
 * Instead `Gdx.gl` is pointed at something that throws on every call. Then a canvas that quietly
 * went back to building things in its constructor fails here, with a message naming the call it
 * made — rather than reaching a real driver with no context and taking the whole test JVM down
 * with it.
 */
class GdxCanvasHeadlessTest {

    @Test
    fun `building, reading and disposing a canvas touches no GL`() {
        val touched = mutableListOf<String>()
        val stub = Proxy.newProxyInstance(GL20::class.java.classLoader, arrayOf(GL20::class.java)) { proxy, method, _ ->
            // equals, hashCode and toString are not GL, and refusing them would only mean a
            // confusing failure if anything ever printed the stub.
            when (method.name) {
                "equals" -> return@newProxyInstance false
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "toString" -> return@newProxyInstance "a GL20 that refuses everything"
            }
            touched += method.name
            error("the canvas called GL20.${method.name}() without being asked to draw anything")
        } as GL20

        // The loop is held still first: it points Gdx.gl back at the real driver every frame.
        Gl.paused {
            val realGl = Gdx.gl
            val real20 = Gdx.gl20
            try {
                Gdx.gl = stub
                Gdx.gl20 = stub

                val canvas = GdxCanvas()
                assertEquals(0, canvas.drawCalls, "a canvas that has drawn nothing has made no draw calls")
                // Disposal must never build the thing it is about to destroy.
                canvas.dispose()
            } finally {
                Gdx.gl = realGl
                Gdx.gl20 = real20
            }
        }

        assertEquals(emptyList<String>(), touched)
    }
}
