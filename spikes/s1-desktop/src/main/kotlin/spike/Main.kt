package spike

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration

/**
 * S1 desktop feasibility spike. Throwaway. Runs a LibGDX LWJGL3 app, performs the sub-checks
 * from issue #1, prints one PASS/FAIL line per check, then exits.
 */
fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("composegl-s1")
        setWindowedMode(1280, 720)
        useVsync(false)
        setForegroundFPS(0)
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 3, 2)
        setInitialVisible(false)
    }
    val app = SpikeApp()
    Lwjgl3Application(app, config)
    println()
    println("==== S1 RESULTS ====")
    app.results.forEach { println(it) }
    val failed = app.results.count { it.startsWith("FAIL") }
    println("==== ${app.results.size} checks, $failed failed ====")
}
