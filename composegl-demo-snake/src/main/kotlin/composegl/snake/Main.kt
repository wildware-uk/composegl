package composegl.snake

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import composegl.snake.game.Difficulty
import composegl.snake.game.HighScore
import composegl.snake.game.HighScoreStore
import kotlin.system.exitProcess

/**
 * Run it with `./gradlew :composegl-demo-snake:run`.
 *
 * Arrows or WASD to steer, Space to pause, Enter to start.
 */
fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("ComposeGL — Snake")
        setWindowedMode(1280, 800)
        // ComposeGL needs a GL 3.0+ context; LibGDX defaults to 2.0.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 3, 2)
    }
    val app = SnakeApp()
    Lwjgl3Application(app, config)
    if (app.selfCheckFailed) exitProcess(1)
}

/** High scores that survive a restart, through LibGDX's own preferences. */
class GdxHighScores(private val name: String = "composegl-snake") : HighScoreStore {

    override fun load(): List<HighScore> {
        val prefs = Gdx.app?.getPreferences(name) ?: return emptyList()
        return prefs.getString(KEY, "")
            .split(ROW)
            .filter { it.isNotBlank() }
            .mapNotNull(::parse)
    }

    override fun save(scores: List<HighScore>) {
        val prefs = Gdx.app?.getPreferences(name) ?: return
        prefs.putString(KEY, scores.joinToString(ROW) { "${it.name.replace(FIELD, " ")}$FIELD${it.score}$FIELD${it.difficulty.name}" })
        prefs.flush()
    }

    private fun parse(row: String): HighScore? {
        val parts = row.split(FIELD)
        if (parts.size != 3) return null
        val score = parts[1].toIntOrNull() ?: return null
        val difficulty = Difficulty.entries.firstOrNull { it.name == parts[2] } ?: return null
        return HighScore(parts[0], score, difficulty)
    }

    private companion object {
        const val KEY = "scores"
        const val ROW = "\n"
        const val FIELD = ""
    }
}
