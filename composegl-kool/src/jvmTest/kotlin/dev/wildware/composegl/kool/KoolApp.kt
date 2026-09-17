package dev.wildware.composegl.kool

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolContext
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.modules.ksl.KslUnlitShader
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.pipeline.ClearDepthFill
import de.fabmax.kool.pipeline.ClearDepthLoad
import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.util.Color
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One real Kool application, shared by every test that needs pixels.
 *
 * Kool runs its own loop on the thread that starts it and never gives that thread back, so one
 * application is started on demand on a daemon thread, and tests post work into its frames. The work
 * runs where a [ComposeGlScene] draws: while Kool renders a scene of its own, with Kool's OpenGL
 * context current and Kool's framebuffer bound. Kool's window is hidden.
 *
 * Three scenes, drawn in this order every frame: the first, which clears to opaque black and runs the
 * test's work; [kool], where Kool draws a square of its own; and the last, which clears nothing and
 * runs the test's second half. So a test can draw with ComposeGL, let Kool draw after it
 * with whatever Kool believes the GL state to be, and read the result — all in one frame.
 *
 * With no display the tests that need one skip themselves rather than failing. Run them under
 * `xvfb-run -a`.
 */
object KoolApp {

    val available: Boolean = !System.getenv("DISPLAY").isNullOrBlank()

    const val size = 400

    private val firstWork = ConcurrentLinkedQueue<(KoolContext) -> Unit>()
    private val lastWork = ConcurrentLinkedQueue<(KoolContext) -> Unit>()

    @Volatile
    private var started = false

    /** Why the application cannot be used, once that is known, so later tests fail at once. */
    @Volatile
    private var broken: Throwable? = null

    /** The application's context. Touch it only from inside [frame]. */
    lateinit var ctx: KoolContext
        private set

    /** The scene between the two work scenes: Kool's own drawing, a magenta square at [KoolSquare]. */
    lateinit var kool: Scene
        private set

    /**
     * Where Kool draws its magenta square every frame, in the framebuffer's pixels from the top-left:
     * the top-right corner, clear of everything the scene tests draw.
     */
    val KoolSquare = java.awt.Rectangle(300, 20, 80, 80)

    @Synchronized
    private fun start() {
        broken?.let { throw IllegalStateException("the Kool application is not usable; see the first failure", it) }
        if (started) return
        started = true
        val ready = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        Thread({
            try {
                initGlfwOnX11()
                val config = KoolConfigJvm(
                    renderBackend = RenderBackendGl,
                    windowTitle = "composegl kool tests",
                    windowSize = Vec2i(size, size),
                    showWindowOnStart = false,
                    numSamples = 1,
                    isVsync = false,
                )
                KoolApplication(config) {
                    this@KoolApp.ctx = ctx
                    ctx.addScene(workScene("first", ClearColorFill(Color.BLACK), firstWork).apply { clearDepth = ClearDepthFill })
                    kool = Scene("kool").apply {
                        clearColor = ClearColorLoad
                        clearDepth = ClearDepthLoad
                        camera = OrthographicCamera().apply {
                            // One unit a pixel, from the framebuffer's bottom-left corner.
                            isClipToViewport = true
                            setupCamera(position = Vec3f(0f, 0f, 10f), lookAt = Vec3f.ZERO)
                            clipNear = 1f
                            clipFar = 100f
                        }
                        addColorMesh("kool-square") {
                            generate {
                                rect {
                                    isCenteredOrigin = true
                                    origin.set(KoolSquare.centerX.toFloat(), (KoolApp.size - KoolSquare.centerY).toFloat(), 0f)
                                    size.set(KoolSquare.width.toFloat(), KoolSquare.height.toFloat())
                                }
                            }
                            shader = KslUnlitShader { color { constColor(Color.MAGENTA) } }
                        }
                    }
                    ctx.addScene(kool)
                    ctx.addScene(workScene("last", ClearColorLoad, lastWork))
                    ready.countDown()
                }
            } catch (thrown: Throwable) {
                failure[0] = thrown
                ready.countDown()
            }
        }, "kool").apply { isDaemon = true }.start()
        if (!ready.await(120, TimeUnit.SECONDS)) {
            throw IllegalStateException("the Kool application never came up").also { broken = it }
        }
        failure[0]?.let { throw IllegalStateException("the Kool application failed to start", it).also { broken = it } }
    }

    /** A scene with nothing in it that runs [work] when Kool renders its one view. */
    private fun workScene(name: String, clear: de.fabmax.kool.pipeline.ClearColor, work: ConcurrentLinkedQueue<(KoolContext) -> Unit>) =
        Scene(name).apply {
            clearColor = clear
            clearDepth = ClearDepthLoad
            mainRenderPass.defaultView.onSetupView {
                // Only the work already waiting, so each call gets a frame of its own.
                repeat(work.size) { (work.poll() ?: return@repeat).invoke(ctx) }
            }
        }

    /**
     * Kool 0.19.0 asks GLFW for Wayland whenever the library supports it, and does not fall back to
     * X11 when there is no Wayland display, which is the case under Xvfb. GLFW started on X11 first
     * stays on X11. The stack size is Kool's own, set before anything touches LWJGL, as Kool does.
     */
    private fun initGlfwOnX11() {
        if (System.getenv("WAYLAND_DISPLAY") != null) return
        Configuration.STACK_SIZE.set(128)
        GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11)
        check(GLFW.glfwInit()) { "glfwInit failed on X11 (DISPLAY=${System.getenv("DISPLAY")})" }
    }

    /**
     * Runs [during] in the next frame's first scene and [after] in the same frame's last scene, on
     * Kool's render thread, and waits for both. Each call runs in a later frame than the one before.
     */
    fun <T, R> frame(during: (KoolContext) -> T, after: (KoolContext, T) -> R): R {
        assumeTrue(available, "no display; this test needs a real GL context")
        start()
        val answer = ArrayBlockingQueue<Result<R>>(1)
        var first: Result<T>? = null
        firstWork += { ctx -> first = runCatching { during(ctx) } }
        // Posted at the same moment as the first half, so it may be picked up in a frame whose first
        // scene has already been drawn: then it waits for the next frame's last scene.
        fun second(ctx: KoolContext) {
            val made = first ?: return run { lastWork += ::second }
            answer.put(made.mapCatching { after(ctx, it) })
        }
        lastWork += ::second
        val result = answer.poll(60, TimeUnit.SECONDS)
            ?: throw IllegalStateException("the Kool application stopped drawing frames").also { broken = it }
        return result.getOrThrow()
    }

    /** Runs [block] inside the next frame, in the first scene, and waits for it. */
    fun <T> render(block: (KoolContext) -> T): T = frame(block) { _, made -> made }

    /**
     * The bottom-left [width] by [height] of the framebuffer Kool has bound, as `0xRRGGBB`, y down
     * from the top. Only inside [frame].
     */
    fun readPixels(width: Int = size, height: Int = size): IntArray {
        val bytes = MemoryUtil.memAlloc(width * height * 4)
        try {
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)
            return IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val at = ((height - 1 - y) * width + x) * 4
                (bytes.get(at).toInt() and 0xFF shl 16) or (bytes.get(at + 1).toInt() and 0xFF shl 8) or (bytes.get(at + 2).toInt() and 0xFF)
            }
        } finally {
            MemoryUtil.memFree(bytes)
        }
    }
}
