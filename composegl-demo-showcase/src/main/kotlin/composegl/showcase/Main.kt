package composegl.showcase

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import kotlin.system.exitProcess

/**
 * Run it with `./gradlew :composegl-demo-showcase:run`.
 *
 * Click to shoot, 1-4 for abilities, and use the switches to turn each exhibit off so you can see
 * what each one was contributing.
 */
fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("ComposeGL — showcase")
        setWindowedMode(1600, 900)
        // ComposeGL needs a GL 3.0+ context; LibGDX defaults to 2.0.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 3, 2)
        setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
    }
    val app = ShowcaseApp()
    Lwjgl3Application(app, config)
    if (app.selfCheckFailed) exitProcess(1)
}
