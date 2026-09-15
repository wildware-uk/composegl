package dev.wildware.composegl.korge.demo

import korlibs.image.color.RGBA
import korlibs.korge.Korge
import korlibs.math.geom.Size
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * The KorGE demo: `./gradlew :composegl-demo-korge:run`.
 *
 * Arrows, Tab, Enter and Escape, a pad, or the mouse drive the menu. F3 shows or hides the frame budget.
 * With `KORGE_HEADLESS=true` it runs with no display, and `COMPOSEGL_DEMO_SHOT=out.png` saves a frame
 * and exits — see [DemoOptions] for the rest.
 */
fun main() {
    val options = DemoOptions.fromEnvironment()
    runBlocking {
        Korge(
            windowSize = Size(1280, 720),
            title = "ComposeGL on KorGE",
            backgroundColor = RGBA(0x0B, 0x0E, 0x14),
            // The scene moves every frame anyway, and a scripted shot counts frames.
            forceRenderEveryFrame = true,
        ) {
            KorgeDemo(this, options)
        }
    }
    // KorGE's window has closed; AWT's threads would otherwise keep the process alive.
    exitProcess(0)
}
