package composegl.snake.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/** Which screen the player is looking at. */
enum class Screen { Menu, Playing, Paused, GameOver }

/** One row of the high score table. */
data class HighScore(val name: String, val score: Int, val difficulty: Difficulty)

/**
 * Everything the UI needs to know, in Compose state.
 *
 * This is the seam between the game and the interface: the app writes to it from the render
 * thread, and the Compose HUD reads it. Because every field is snapshot state, the HUD redraws
 * exactly when one of them changes and not otherwise — which is the whole reason a HUD in this
 * library costs nothing while nothing is happening.
 *
 * Nothing here touches LibGDX or OpenGL, so it can be driven from a test.
 */
class SnakeSession(private val scores: HighScoreStore = HighScoreStore.InMemory()) {

    var screen by mutableStateOf(Screen.Menu)
        private set

    var difficulty by mutableStateOf(Difficulty.Normal)

    var playerName by mutableStateOf(TextFieldValue("Player"))

    /** Cosmetic, and a good excuse for a Switch that changes what OpenGL draws. */
    var showGrid by mutableStateOf(true)

    var boardWidth by mutableIntStateOf(24)

    var score by mutableIntStateOf(0)
        private set

    var length by mutableIntStateOf(3)
        private set

    /** Set when the run that just ended earned a place in the table. */
    var lastRunWasHighScore by mutableStateOf(false)
        private set

    /**
     * Sorted and trimmed on the way in, not just on the way out. Whatever is on disk was written
     * by an older version, or edited by hand, and the menu should not be at its mercy.
     */
    val highScores = mutableStateListOf<HighScore>().apply {
        addAll(scores.load().sortedByDescending { it.score }.take(MAX_HIGH_SCORES))
    }

    /** The rules. Replaced on each new game so the board size can change. */
    var game: SnakeGame = SnakeGame(boardWidth, boardHeightFor(boardWidth))
        private set

    private var secondsSinceStep = 0f

    fun startGame() {
        game = SnakeGame(boardWidth, boardHeightFor(boardWidth))
        secondsSinceStep = 0f
        score = 0
        length = game.snake.size
        lastRunWasHighScore = false
        screen = Screen.Playing
    }

    fun pause() {
        if (screen == Screen.Playing) screen = Screen.Paused
    }

    fun resume() {
        if (screen == Screen.Paused) {
            secondsSinceStep = 0f
            screen = Screen.Playing
        }
    }

    fun toMenu() {
        screen = Screen.Menu
    }

    fun turn(direction: Direction) {
        if (screen == Screen.Playing) game.turn(direction)
    }

    /**
     * Advances the game by real time. Call every frame; it steps the snake only when enough time
     * has passed for the chosen difficulty, so the game runs at its own speed rather than the
     * display's.
     *
     * @return what the snake did, or null if it was not yet time to move.
     */
    fun advance(deltaSeconds: Float): StepResult? {
        if (screen != Screen.Playing) return null

        secondsSinceStep += deltaSeconds
        val secondsPerStep = 1f / difficulty.stepsPerSecond
        if (secondsSinceStep < secondsPerStep) return null
        secondsSinceStep -= secondsPerStep

        val result = game.step()
        // Writing these only when they change is what keeps the HUD still between mouthfuls.
        if (game.score != score) score = game.score
        if (game.snake.size != length) length = game.snake.size
        if (result == StepResult.Died) endRun()
        return result
    }

    private fun endRun() {
        lastRunWasHighScore = recordScore()
        screen = Screen.GameOver
    }

    /** @return whether the run made the table. */
    private fun recordScore(): Boolean {
        if (game.score <= 0) return false
        val name = playerName.text.trim().ifEmpty { "Player" }
        val entry = HighScore(name, game.score, difficulty)
        val updated = (highScores + entry).sortedByDescending { it.score }.take(MAX_HIGH_SCORES)
        val madeIt = entry in updated
        highScores.clear()
        highScores.addAll(updated)
        scores.save(updated)
        return madeIt
    }

    private companion object {
        const val MAX_HIGH_SCORES = 8

        /** A 4:3 board, so it fills a typical window without letterboxing. */
        fun boardHeightFor(width: Int) = (width * 3 / 4).coerceAtLeast(4)
    }
}

/** Where high scores live. Split out so tests do not touch the disk. */
interface HighScoreStore {
    fun load(): List<HighScore>
    fun save(scores: List<HighScore>)

    class InMemory(private var scores: List<HighScore> = emptyList()) : HighScoreStore {
        override fun load() = scores
        override fun save(scores: List<HighScore>) { this.scores = scores }
    }
}
