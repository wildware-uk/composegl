package composegl.gdx

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Boots a real LWJGL3 window with a GL 3.2 core context, runs [frames] frames, and hands each one
 * to [onFrame]. Anything thrown inside is re-thrown from [runGl] on the test thread.
 *
 * On CI this runs under Xvfb with Mesa's llvmpipe, so "a real GL context" means a real software
 * one — which is exactly the point: everything ComposeGL asks of the driver has to be ordinary.
 */
fun runGl(
    width: Int = 800,
    height: Int = 600,
    frames: Int = 20,
    onCreate: () -> Unit = {},
    onFrame: (frame: Int) -> Unit,
) {
    var failure: Throwable? = null
    val listener = object : ApplicationAdapter() {
        private var frame = 0

        override fun create() {
            try {
                onCreate()
            } catch (t: Throwable) {
                failure = t
                Gdx.app.exit()
            }
        }

        override fun render() {
            frame++
            try {
                onFrame(frame)
            } catch (t: Throwable) {
                failure = t
                Gdx.app.exit()
                return
            }
            if (frame >= frames) Gdx.app.exit()
        }
    }

    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("composegl-integration")
        setWindowedMode(width, height)
        useVsync(false)
        setForegroundFPS(0)
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 3, 2)
        setInitialVisible(false)
    }
    Lwjgl3Application(listener, config)
    failure?.let { throw it }
}

/** Reads one pixel from the bound framebuffer. GL coordinates: y counts up from the bottom. */
fun readPixel(x: Int, y: Int): Int {
    val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    Gdx.gl.glReadPixels(x, y, 1, 1, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buffer)
    fun byteAt(i: Int) = buffer.get(i).toInt() and 0xff
    return (byteAt(3) shl 24) or (byteAt(0) shl 16) or (byteAt(1) shl 8) or byteAt(2)
}

/** The same pixel, addressed the way Compose does: from the top-left, y down. */
fun screenPixel(x: Int, y: Int): Int = readPixel(x, Gdx.graphics.backBufferHeight - 1 - y)

fun hex(argb: Int): String = "#%08X".format(argb)
