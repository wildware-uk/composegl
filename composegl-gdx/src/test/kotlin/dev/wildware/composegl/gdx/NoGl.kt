package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.GL31
import com.badlogic.gdx.graphics.GL32
import com.badlogic.gdx.graphics.g2d.Batch
import java.lang.reflect.Proxy

/**
 * A JVM where every OpenGL call is refused, for the tests whose claim is that nothing needs one.
 *
 * Saying "there is no GL here" is not enough on its own. This module boots a real LibGDX
 * application on a daemon thread for the tests that need pixels, and that sets the static `Gdx.gl`
 * for the whole JVM; tests share one JVM in an order nobody specifies. A test that simply assumed
 * `Gdx.gl` was null would pass or fail depending on what ran first, and — worse — code that *did*
 * reach that driver would be calling it from the wrong thread, where a GL call does not throw but
 * aborts the process. An aborted JVM writes no report, so the one honest failure would arrive as a
 * dead test task with every other class in the module silently unrun.
 *
 * So `Gdx.gl` is pointed at something that throws instead. A class that quietly went back to
 * building GPU resources when it was merely constructed then fails as one ordinary red test,
 * naming the call it made.
 */
object NoGl {

    /**
     * Runs [block] with every version of `Gdx.gl` pointing at a stub that throws when it is called.
     *
     * Returns the GL calls [block] made, which is empty unless something swallowed the exception —
     * a refused call throws out of here and fails the test by itself, so the list is the check for
     * the case where a `runCatching` somewhere hid it.
     */
    fun refusingGl(block: () -> Unit): List<String> {
        // The loop is started first where there is one. Otherwise class order decides whether this
        // mechanism is exercised at all: with no loop running nothing has ever set `Gdx.gl`, the
        // pause below is a no-op, and a regression would fail on a missing native rather than on
        // the stub it is supposed to meet.
        if (Gl.available) Gl.render { }

        val touched = mutableListOf<String>()
        // GL32 rather than GL20: it extends the lot, so one stub stands in for every version the
        // engine might hand out. Leaving `Gdx.gl30` and its friends pointing at a real driver would
        // mean a regressed class could reach the GPU through them the moment this module's test
        // configuration asked for GL30 — and these tests would pass while it did.
        val stub = Proxy.newProxyInstance(GL32::class.java.classLoader, arrayOf(GL32::class.java)) { proxy, method, _ ->
            // equals, hashCode and toString are not GL, and refusing them would only mean a
            // confusing failure if anything ever printed the stub.
            when (method.name) {
                "equals" -> return@newProxyInstance false
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "toString" -> return@newProxyInstance "a GL that refuses everything"
            }
            touched += method.name
            error("GL.${method.name}() was called by something that is supposed to need no OpenGL")
        } as GL32

        // Held still first: the loop points `Gdx.gl` back at the real driver every frame.
        Gl.paused {
            val real: Array<Any?> = arrayOf(Gdx.gl, Gdx.gl20, Gdx.gl30, Gdx.gl31, Gdx.gl32)
            try {
                Gdx.gl = stub
                Gdx.gl20 = stub
                Gdx.gl30 = stub
                Gdx.gl31 = stub
                Gdx.gl32 = stub
                block()
            } finally {
                Gdx.gl = real[0] as GL20?
                Gdx.gl20 = real[1] as GL20?
                Gdx.gl30 = real[2] as GL30?
                Gdx.gl31 = real[3] as GL31?
                Gdx.gl32 = real[4] as GL32?
            }
        }

        return touched
    }

    /**
     * A [Batch] that exists and does nothing, for a test that only needs the canvas to have been
     * given one.
     *
     * A real `SpriteBatch` builds a mesh and compiles a shader in its constructor, so it cannot be
     * made inside [refusingGl] at all — and the question being asked, "was this canvas handed a
     * batch", is answered by the reference and never by the object. Every call on it refuses, so a
     * canvas that quietly *used* it fails by name rather than by drawing nothing.
     */
    fun batch(): Batch = Proxy.newProxyInstance(
        Batch::class.java.classLoader,
        arrayOf(Batch::class.java),
    ) { proxy, method, _ ->
        when (method.name) {
            "equals" -> return@newProxyInstance false
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "a Batch that refuses everything"
        }
        error("Batch.${method.name}() was called by something that is only supposed to hold one")
    } as Batch
}
