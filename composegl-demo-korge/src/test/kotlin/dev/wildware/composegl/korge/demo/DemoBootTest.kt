package dev.wildware.composegl.korge.demo

import korlibs.event.Key
import korlibs.event.KeyEvent
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.korge.Korge
import korlibs.korge.render.RenderContext
import korlibs.math.geom.Size
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The real demo, booted in a real KorGE game: its own fonts, world, interface and panel in the world.
 *
 * A key script given the way the environment gives one opens the settings and photographs them; then
 * KorGE key events dispatched into the stage go back to the menu and start the game, and the HUD's
 * score, the panel in the world and the window's pixels all move with it.
 *
 * Under Xvfb it opens KorGE's window; with `KORGE_HEADLESS=true` it renders offscreen with no display.
 * With neither it is skipped: `DemoScreensTest` plays the same screens with no GPU at all.
 */
class DemoBootTest {

    private val available = !System.getenv("DISPLAY").isNullOrBlank() || System.getenv("KORGE_HEADLESS") == "true"

    private lateinit var demo: KorgeDemo

    private fun boot(options: DemoOptions) {
        val ready = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        Thread({
            try {
                runBlocking {
                    Korge(windowSize = Size(1280, 720), title = "composegl korge demo test", backgroundColor = RGBA(0x0B, 0x0E, 0x14), forceRenderEveryFrame = true) {
                        demo = KorgeDemo(this, options)
                        ready.countDown()
                    }
                }
            } catch (thrown: Throwable) {
                failure[0] = thrown
                ready.countDown()
            }
        }, "korge-demo").apply { isDaemon = true }.start()
        check(ready.await(60, TimeUnit.SECONDS)) { "the demo never came up" }
        failure[0]?.let { throw IllegalStateException("the demo failed to start", it) }
    }

    /** Runs [block] on KorGE's thread after the next frame is drawn, and waits for it. */
    private fun <T> frame(block: (RenderContext) -> T): T {
        val answer = ArrayBlockingQueue<Result<T>>(1)
        demo.afterFrame += { ctx -> answer.put(runCatching { block(ctx) }) }
        return (answer.poll(60, TimeUnit.SECONDS) ?: error("the demo stopped drawing frames")).getOrThrow()
    }

    private fun waitFor(what: String, frames: Int = 600, check: () -> Boolean) {
        repeat(frames) { if (frame { check() }) return }
        org.junit.jupiter.api.Assertions.fail<Unit>("$what, after $frames frames; the demo is on ${frame { demo.state.screen }}")
    }

    private fun press(key: Key) = frame {
        demo.stage.views.dispatch(KeyEvent(type = KeyEvent.Type.DOWN, key = key))
        demo.stage.views.dispatch(KeyEvent(type = KeyEvent.Type.UP, key = key))
    }

    /** How many pixels differ between two pictures of the window, inside the middle third. */
    private fun changed(before: Bitmap32, after: Bitmap32): Int {
        var count = 0
        for (y in before.height / 3 until before.height * 2 / 3) for (x in before.width / 3 until before.width * 2 / 3) {
            if (before[x, y] != after[x, y]) count++
        }
        return count
    }

    @Test
    fun `the demo boots in KorGE, a key script opens settings, and KorGE keys go back to the menu and into the game`() {
        assumeTrue(available, "no display and KORGE_HEADLESS is not set; this test needs a real GL context")
        val shot = File("build/demo-boot/settings.png").absoluteFile.also { it.delete() }

        // The same options COMPOSEGL_DEMO_KEYS and COMPOSEGL_DEMO_SHOT give: down to SETTINGS, Enter.
        boot(DemoOptions(shot = shot.path, shotAt = 0.5, keys = "down,enter", closeAfterShot = false))

        waitFor("the key script opened the settings") { demo.state.screen == DemoScreen.Settings }
        waitFor("the demo photographed itself") { shot.isFile && shot.length() > 10_000 }
        val settings = frame { demo.window(it) }

        press(Key.ESCAPE)
        waitFor("Escape went back to the menu") { demo.state.screen == DemoScreen.Menu }
        val menu = frame { demo.window(it) }
        assertTrue(changed(settings, menu) > 5_000, "the settings panel was replaced by the menu in the pixels")

        // PLAY takes focus again when the menu comes back, so Enter starts the game.
        waitFor("the menu's PLAY button has focus") { demo.ui.focus.focused?.testTag == "play" }
        press(Key.ENTER)
        waitFor("Enter on PLAY started the game") { demo.state.screen == DemoScreen.Game }
        waitFor("the world scored hits the HUD shows") { demo.state.score > 0 }
        val drawsBefore = frame { demo.terminal.draws }
        waitFor("the panel in the world redrew for the new score") { demo.terminal.draws > drawsBefore }
        val game = frame { demo.window(it) }
        assertTrue(changed(menu, game) > 5_000, "the menu panel is gone from the middle of the window")
    }
}
