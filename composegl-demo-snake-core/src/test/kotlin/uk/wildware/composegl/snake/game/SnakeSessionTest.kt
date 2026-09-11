package uk.wildware.composegl.snake.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The screen flow and the score table, without a window.
 *
 * `SnakeSession` is the seam between the game and the interface, so it is worth being able to
 * drive it from a test: every transition a player can cause is reachable here.
 */
class SnakeSessionTest {

    private val store = HighScoreStore.InMemory()
    private val session = SnakeSession(store)

    /** One second of game time at the current speed, in the size of step the app uses. */
    private fun playFor(seconds: Float, step: Float = 1f / 60f) {
        var elapsed = 0f
        while (elapsed < seconds) {
            session.advance(step)
            elapsed += step
        }
    }

    @Test
    fun `it opens on the menu and nothing is running`() {
        assertEquals(Screen.Menu, session.screen)
        assertNull(session.advance(1f), "the game must not tick behind the menu")
    }

    @Test
    fun `starting a game puts the player on the board`() {
        session.startGame()

        assertEquals(Screen.Playing, session.screen)
        assertEquals(0, session.score)
        assertEquals(3, session.length)
    }

    @Test
    fun `the board size chosen in the menu is the board you get`() {
        session.boardWidth = 30
        session.startGame()

        assertEquals(30, session.game.width)
        assertEquals(22, session.game.height, "a 4:3 board")
    }

    @Test
    fun `the snake moves at the speed the difficulty asks for`() {
        session.difficulty = Difficulty.Gentle   // 6 steps a second
        session.startGame()
        val start = session.game.snake.first()

        playFor(1f)

        val moved = session.game.snake.first().x - start.x
        // Six steps in a second, give or take one for where the accumulator started.
        assertTrue(moved in 5..7, "expected about six steps at Gentle, got $moved")
    }

    @Test
    fun `pausing stops the snake and resuming starts it again`() {
        session.startGame()
        playFor(0.5f)
        val whenPaused = session.game.snake.first()

        session.pause()
        assertEquals(Screen.Paused, session.screen)
        playFor(1f)
        assertEquals(whenPaused, session.game.snake.first(), "a paused snake does not move")

        session.resume()
        assertEquals(Screen.Playing, session.screen)
        playFor(1f)
        assertTrue(session.game.snake.first() != whenPaused, "resuming starts it again")
    }

    @Test
    fun `pause does nothing from the menu`() {
        session.pause()
        assertEquals(Screen.Menu, session.screen)
    }

    @Test
    fun `hitting a wall ends the run and shows game over`() {
        session.startGame()

        playFor(10f)   // long enough to cross any board

        assertEquals(Screen.GameOver, session.screen)
        assertNull(session.advance(1f), "nothing ticks after the run ends")
    }

    @Test
    fun `a run that scored nothing does not enter the table`() {
        session.startGame()
        playFor(10f)

        assertEquals(Screen.GameOver, session.screen)
        assertTrue(session.highScores.isEmpty(), "zero is not a high score")
        assertFalse(session.lastRunWasHighScore)
    }

    @Test
    fun `a scoring run is recorded under the player's name`() {
        session.playerName = "Ada"
        session.difficulty = Difficulty.Brisk
        session.startGame()
        eatOnce()
        playFor(10f)

        assertEquals(Screen.GameOver, session.screen)
        assertEquals(1, session.highScores.size)
        assertEquals("Ada", session.highScores.first().name)
        assertEquals(Difficulty.Brisk, session.highScores.first().difficulty)
        assertTrue(session.lastRunWasHighScore)
    }

    @Test
    fun `an empty name falls back to Player`() {
        session.playerName = "   "
        session.startGame()
        eatOnce()
        playFor(10f)

        assertEquals("Player", session.highScores.first().name)
    }

    @Test
    fun `high scores are ordered best first and survive a reload`() {
        // Play three runs of whatever length the steering manages; what matters is the ordering,
        // not the numbers, so the test does not depend on how well it plays.
        repeat(3) { run ->
            session.startGame()
            repeat(run + 1) { eatOnce() }
            playFor(10f)
        }

        val table = session.highScores.map { it.score }
        assertTrue(table.isNotEmpty(), "three scoring runs should leave a table")
        assertEquals(table.sortedDescending(), table, "best first")

        val reloaded = SnakeSession(store)
        assertEquals(table, reloaded.highScores.map { it.score }, "the table was saved")
    }

    @Test
    fun `the table keeps only the best eight`() {
        val store = HighScoreStore.InMemory(
            (1..20).map { HighScore("p$it", it, Difficulty.Normal) },
        )
        val loaded = SnakeSession(store)
        assertEquals(8, loaded.highScores.size, "a stale file must be trimmed on load")
        assertEquals(20, loaded.highScores.first().score, "and sorted")

        loaded.startGame()
        eatOnce(loaded)
        while (loaded.screen == Screen.Playing) loaded.advance(1f / 60f)

        assertTrue(loaded.highScores.size <= 8, "got ${loaded.highScores.size}")
        assertEquals(loaded.highScores.map { it.score }.sortedDescending(), loaded.highScores.map { it.score })
    }

    @Test
    fun `going back to the menu leaves the table alone`() {
        session.startGame()
        eatOnce()
        playFor(10f)
        val table = session.highScores.toList()

        session.toMenu()

        assertEquals(Screen.Menu, session.screen)
        assertEquals(table, session.highScores)
    }

    @Test
    fun `turning is ignored unless a game is running`() {
        val before = session.game.direction
        session.turn(Direction.Up)
        session.advance(1f)
        assertEquals(before, session.game.direction)
    }

    /** Walks the snake onto the food so the score goes up, without depending on where it lands. */
    private fun eatOnce(session: SnakeSession = this.session) {
        val scoreBefore = session.score
        var guard = 400
        while (session.screen == Screen.Playing && session.score == scoreBefore && guard-- > 0) {
            val head = session.game.snake.first()
            val food = session.game.food
            val wanted = when {
                food.y > head.y -> Direction.Up
                food.y < head.y -> Direction.Down
                food.x > head.x -> Direction.Right
                else -> Direction.Left
            }
            session.turn(wanted)
            // One step's worth of time at the current speed.
            session.advance(1f / session.difficulty.stepsPerSecond)
        }
    }
}
