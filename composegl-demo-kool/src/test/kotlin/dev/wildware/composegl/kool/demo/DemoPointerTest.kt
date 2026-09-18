package dev.wildware.composegl.kool.demo

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolContext
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.pipeline.ClearDepthLoad
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.platform.glfw.GlfwWindow
import de.fabmax.kool.input.PointerInput
import de.fabmax.kool.scene.Scene
import dev.wildware.composegl.kool.PointerUse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The demo, run for real and clicked with the mouse, with a picture of each step.
 *
 * The mouse is Kool's own: each step calls the callback Kool installed on its GLFW window, which is where a
 * real mouse's events enter Kool. From there it is all the real path — Kool's pointer state, Kool's input
 * stack, the Kool frontend's translation, the toolkit's router, the button, the scene view. Nothing is
 * handed to the toolkit directly.
 *
 * Needs a display: run it under `xvfb-run -a`. The pictures go to `COMPOSEGL_KOOL_DEMO_SHOTS`, or to
 * `build/demo-shots`.
 */
class DemoPointerTest {

    private val work = ConcurrentLinkedQueue<(KoolContext) -> Unit>()

    /** Runs [block] on Kool's render thread after every scene has drawn, and waits for it. */
    private fun <T> late(block: (KoolContext) -> T): T {
        val answer = ArrayBlockingQueue<Result<T>>(1)
        work += { ctx -> answer.put(runCatching { block(ctx) }) }
        return checkNotNull(answer.poll(60, TimeUnit.SECONDS)) { "the demo stopped drawing frames" }.getOrThrow()
    }

    private fun frames(count: Int) = repeat(count) { late { } }

    private val shots = File(System.getenv("COMPOSEGL_KOOL_DEMO_SHOTS") ?: "build/demo-shots").apply { mkdirs() }

    /** The whole window as Kool has drawn it so far this frame, top row first, saved as [name]. */
    private fun shot(name: String): BufferedImage = late {
        val bytes = MemoryUtil.memAlloc(KoolDemo.Width * KoolDemo.Height * 4)
        try {
            GL11.glReadPixels(0, 0, KoolDemo.Width, KoolDemo.Height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)
            BufferedImage(KoolDemo.Width, KoolDemo.Height, BufferedImage.TYPE_INT_RGB).also { image ->
                for (y in 0 until KoolDemo.Height) for (x in 0 until KoolDemo.Width) {
                    val at = ((KoolDemo.Height - 1 - y) * KoolDemo.Width + x) * 4
                    image.setRGB(x, y, (bytes.get(at).toInt() and 0xFF shl 16) or (bytes.get(at + 1).toInt() and 0xFF shl 8) or (bytes.get(at + 2).toInt() and 0xFF))
                }
            }
        } finally {
            MemoryUtil.memFree(bytes)
        }
    }.also { ImageIO.write(it, "png", File(shots, "$name.png")) }

    /** Kool's own mouse callbacks on its window, called as GLFW calls them. */
    private fun window(ctx: KoolContext) = ((ctx as Lwjgl3Context).window as GlfwWindow).windowHandle

    private fun moveTo(x: Int, y: Int) = late { ctx ->
        val handle = window(ctx)
        val kool = checkNotNull(GLFW.glfwSetCursorPosCallback(handle, null)) { "Kool installed no cursor callback" }
        GLFW.glfwSetCursorPosCallback(handle, kool)
        kool.invoke(handle, x.toDouble(), y.toDouble())
    }

    private fun button(action: Int) = late { ctx ->
        val handle = window(ctx)
        val kool = checkNotNull(GLFW.glfwSetMouseButtonCallback(handle, null)) { "Kool installed no mouse button callback" }
        GLFW.glfwSetMouseButtonCallback(handle, kool)
        kool.invoke(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT, action, 0)
    }

    /** Kool's own callback for the mouse entering or leaving the window. */
    private fun cursorEnters(entered: Boolean) = late { ctx ->
        val handle = window(ctx)
        val kool = checkNotNull(GLFW.glfwSetCursorEnterCallback(handle, null)) { "Kool installed no cursor enter callback" }
        GLFW.glfwSetCursorEnterCallback(handle, kool)
        kool.invoke(handle, entered)
    }

    @Test
    fun `the mouse hovers and clicks a ComposeGL button over a Kool world`() {
        assumeTrue(!System.getenv("DISPLAY").isNullOrBlank(), "no display; this test opens a Kool window")
        val started = ArrayBlockingQueue<Result<RunningDemo>>(1)
        Thread({
            try {
                initGlfwOnX11()
                val config = KoolConfigJvm(
                    renderBackend = RenderBackendGl,
                    windowTitle = "ComposeGL on Kool",
                    windowSize = Vec2i(KoolDemo.Width, KoolDemo.Height),
                    showWindowOnStart = false,
                    numSamples = 1,
                    isVsync = false,
                )
                KoolApplication(config) {
                    val demo = KoolDemo.start(ctx)
                    // Last of all, where every scene has drawn: the test's steps and pictures.
                    ctx.addScene(Scene("test").apply {
                        clearColor = ClearColorLoad
                        clearDepth = ClearDepthLoad
                        mainRenderPass.defaultView.onSetupView { repeat(work.size) { (work.poll() ?: return@repeat).invoke(ctx) } }
                    })
                    started.put(Result.success(demo))
                }
            } catch (thrown: Throwable) {
                started.put(Result.failure(thrown))
            }
        }, "kool-demo").apply { isDaemon = true }.start()
        val demo = checkNotNull(started.poll(120, TimeUnit.SECONDS)) { "the demo never started" }.getOrThrow()

        // Set on the render thread, where the report is made, so no frame is half-reported.
        val uses = ConcurrentLinkedQueue<PointerUse>()
        late { demo.ui.onPointerUsed = { uses += it } }
        fun mouseUses() = generateSequence { uses.poll() }.filter { it.pointer == PointerInput.MOUSE_POINTER_ID }.toList()

        val button = KoolDemo.ButtonAt
        val sceneAt = 200 to 418
        frames(10)
        val before = shot("1-before")
        val green = DemoState.Palette[0].argb and 0xFFFFFF
        assertEquals(green, before.getRGB(sceneAt.first, sceneAt.second) and 0xFFFFFF, "the scene view starts green")

        moveTo(button.x + button.width / 2, button.y + button.height / 2)
        frames(5)
        val hovered = shot("2-hover")
        val inButton = button.x + 12 to button.y + button.height / 2
        assertNotEquals(
            before.getRGB(inButton.first, inButton.second),
            hovered.getRGB(inButton.first, inButton.second),
            "the button is drawn hovered once Kool's mouse is over it",
        )
        assertEquals(0, demo.state.clicks)

        mouseUses()
        button(GLFW.GLFW_PRESS)
        frames(2)
        shot("3-pressed")
        button(GLFW.GLFW_RELEASE)
        frames(5)
        val clicked = shot("4-clicked")
        val onButton = mouseUses()
        assertTrue(onButton.count { it.used } >= 2, "the press and the release on the button are reported used: $onButton")
        assertEquals(onButton.map { it.frame }.sorted(), onButton.map { it.frame }, "reported in the order Kool read them")

        assertEquals(1, demo.state.clicks, "one press and release over the button is one click")
        assertEquals(DemoState.Palette[1].argb and 0xFFFFFF, clicked.getRGB(sceneAt.first, sceneAt.second) and 0xFFFFFF, "the scene view drew its new colour")
        assertEquals(before.getRGB(1100, 600), clicked.getRGB(1100, 600), "Kool's world outside the panel did not change")

        // A click on Kool's world, away from the panel: the game's, and reported so.
        moveTo(1100, 600)
        frames(5)
        mouseUses()
        button(GLFW.GLFW_PRESS)
        frames(2)
        button(GLFW.GLFW_RELEASE)
        frames(5)
        val onWorld = mouseUses()
        assertTrue(onWorld.isNotEmpty(), "the mouse is still reported every frame")
        assertTrue(onWorld.none { it.used }, "a click on the world is not the interface's: $onWorld")
        assertEquals(1, demo.state.clicks, "and it clicked nothing in the interface")

        // Pressed on the button, dragged out of the window, let go outside, and back in over the button.
        // Kool forgets the mouse when it leaves; the one that comes back is new to the toolkit.
        moveTo(button.x + button.width / 2, button.y + button.height / 2)
        frames(3)
        button(GLFW.GLFW_PRESS)
        frames(2)
        cursorEnters(false)
        frames(2)
        button(GLFW.GLFW_RELEASE)
        frames(2)
        cursorEnters(true)
        moveTo(button.x + button.width / 2, button.y + button.height / 2)
        frames(5)
        shot("5-back-in")
        assertEquals(1, demo.state.clicks, "a mouse coming back over the button does not click it")
    }
}
