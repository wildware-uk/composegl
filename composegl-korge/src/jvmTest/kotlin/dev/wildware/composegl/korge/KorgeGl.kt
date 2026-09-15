package dev.wildware.composegl.korge

import korlibs.graphics.readColor
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.korge.Korge
import korlibs.korge.render.RenderContext
import korlibs.korge.view.Stage
import korlibs.math.geom.Size
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * One real KorGE game, shared by every test that needs pixels.
 *
 * Almost nothing in this module needs a GPU — fonts measure on the CPU, and the toolkit's recording
 * canvas answers nearly every layout question. What is left is what only a GPU can answer: what came
 * out in the pixels, and how many times the batch talked to the driver.
 *
 * Booting a game per test would take longer than the tests do, so one is booted on demand, on a
 * daemon thread, and every test posts work into its render loop — after KorGE has drawn the stage and
 * before it swaps, which is a moment a real [RenderContext] exists and nothing else is using it.
 *
 * Two ways to have a context, and both are tested: under Xvfb (`xvfb-run -a`) KorGE opens its
 * ordinary window, and with `KORGE_HEADLESS=true` it renders offscreen with a real driver and no
 * display at all. With neither, the tests that need one skip themselves rather than failing.
 */
object KorgeGl {

    val available: Boolean =
        !System.getenv("DISPLAY").isNullOrBlank() || System.getenv("KORGE_HEADLESS") == "true"

    const val size = 400

    private val work = ConcurrentLinkedQueue<(RenderContext) -> Unit>()

    @Volatile
    private var started = false

    /**
     * Why the game cannot be used, once that is known: it never came up, or it stopped drawing.
     * Every later test fails with this straight away. Without it each one would wait its own full
     * minute, and a suite of a few hundred pixel tests on a machine with no context becomes hours.
     */
    @Volatile
    private var broken: Throwable? = null

    /** The stage of the one game. Touch it only from inside [render]. */
    lateinit var stage: Stage
        private set

    @Synchronized
    private fun start() {
        broken?.let { throw IllegalStateException("the KorGE game is not usable; see the first failure", it) }
        if (started) return
        started = true
        val ready = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        Thread({
            try {
                runBlocking {
                    Korge(
                        windowSize = Size(size, size),
                        backgroundColor = Colors.BLACK,
                        title = "composegl korge tests",
                        forceRenderEveryFrame = true,
                    ) {
                        this@KorgeGl.stage = this
                        views.onAfterRender { ctx ->
                            while (true) (work.poll() ?: break).invoke(ctx)
                        }
                        ready.countDown()
                    }
                }
            } catch (thrown: Throwable) {
                failure[0] = thrown
                ready.countDown()
            }
        }, "korge").apply { isDaemon = true }.start()
        if (!ready.await(60, TimeUnit.SECONDS)) {
            throw IllegalStateException("the KorGE game never came up").also { broken = it }
        }
        failure[0]?.let { throw IllegalStateException("the KorGE game failed to start", it).also { broken = it } }
    }

    /**
     * Runs [block] inside the next frame, on KorGE's render thread, and waits for it. Skips the test
     * when there is no way to get a context.
     */
    fun <T> render(block: (RenderContext) -> T): T {
        assumeTrue(available, "no display and KORGE_HEADLESS is not set; this test needs a real GL context")
        start()
        val answer = ArrayBlockingQueue<Result<T>>(1)
        work += { ctx -> answer.put(runCatching { block(ctx) }) }
        val result = answer.poll(60, TimeUnit.SECONDS)
            ?: throw IllegalStateException("the KorGE game stopped drawing frames").also { broken = it }
        return result.getOrThrow()
    }

    /** Lets [count] whole frames go by, so something added to the stage has been drawn. */
    fun frames(count: Int = 2) = repeat(count) { render { } }

    /**
     * Draws [content] into an offscreen picture the size of the test window, over opaque black, and
     * reads it back. y is down from the top in what comes back, as the toolkit counts it.
     */
    fun picture(width: Int = size, height: Int = size, content: (RenderContext) -> Unit): Bitmap32 = render { ctx ->
        ctx.renderToBitmap(width, height) {
            ctx.clear(Colors.BLACK)
            content(ctx)
        }
    }

    /** What the window itself shows, top row first. */
    fun window(): Bitmap32 = render { ctx ->
        Bitmap32(ctx.mainFrameBuffer.width, ctx.mainFrameBuffer.height, premultiplied = false).also {
            ctx.ag.readColor(ctx.mainFrameBuffer, it)
        }
    }
}

/** Channels as fractions, for comparisons that read like the LibGDX tests'. */
data class Rgb(val r: Float, val g: Float, val b: Float)

fun Bitmap32.at(x: Int, y: Int): Rgb = this[x, y].let { Rgb(it.r / 255f, it.g / 255f, it.b / 255f) }

val Black = Rgb(0f, 0f, 0f)
val Red = Rgb(1f, 0f, 0f)
val Green = Rgb(0f, 1f, 0f)
val Blue = Rgb(0f, 0f, 1f)
val White = Rgb(1f, 1f, 1f)

/**
 * Colours are compared with a tolerance. Exact equality is the wrong question to ask of a rasteriser:
 * blending rounds, and llvmpipe is entitled to differ from a real driver by one.
 */
fun assertColour(expected: Rgb, actual: Rgb, because: String = "") {
    val close = abs(expected.r - actual.r) < 0.03f && abs(expected.g - actual.g) < 0.03f && abs(expected.b - actual.b) < 0.03f
    org.junit.jupiter.api.Assertions.assertTrue(close, "$because: expected about $expected, got $actual")
}

/** The raw colour, for a test that wants to print one. */
fun Bitmap32.rgba(x: Int, y: Int): RGBA = this[x, y]
