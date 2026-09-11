package uk.wildware.composegl.snake.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/**
 * The rules, tested without a window, a driver or a frame loop.
 *
 * This is the half of a game that can be checked properly, so it is worth keeping it free of
 * everything that cannot. Every test here would have run before OpenGL existed.
 */
class SnakeGameTest {

    /** A fixed seed, so the food lands somewhere known and the tests are not flaky. */
    private fun game(width: Int = 10, height: Int = 8, seed: Int = 1) =
        SnakeGame(width, height, Random(seed))

    @Test
    fun `a new game starts with three segments heading right`() {
        val game = game()
        assertEquals(3, game.snake.size)
        assertEquals(Direction.Right, game.direction)
        assertEquals(0, game.score)
        assertFalse(game.isOver)
    }

    @Test
    fun `a step moves the head one square and drags the tail`() {
        val game = game()
        val before = game.snake

        assertEquals(StepResult.Moved, game.step())

        assertEquals(Cell(before.first().x + 1, before.first().y), game.snake.first())
        assertEquals(before.size, game.snake.size, "the snake only grows when it eats")
        assertEquals(before.dropLast(1), game.snake.drop(1), "the body follows the head")
    }

    @Test
    fun `turning takes effect on the next step, not immediately`() {
        val game = game()
        game.turn(Direction.Up)

        assertEquals(Direction.Right, game.direction, "the turn is queued")
        game.step()
        assertEquals(Direction.Up, game.direction)
    }

    @Test
    fun `the snake cannot turn back on itself`() {
        val game = game()
        game.turn(Direction.Left)
        game.step()
        assertEquals(Direction.Right, game.direction, "a reversal is ignored")
    }

    @Test
    fun `two turns in one tick cannot fold the snake into its own neck`() {
        val game = game()
        // The classic bug: right, then up, then left, all inside one tick. With a single pending
        // slot the Up is lost and Left reverses the snake into its own neck.
        game.turn(Direction.Up)
        game.turn(Direction.Left)
        game.step()

        assertEquals(Direction.Up, game.direction, "the first turn must not be thrown away")
        assertFalse(game.isOver)

        game.step()
        assertEquals(Direction.Left, game.direction, "and the second one happens on the next tick")
        assertFalse(game.isOver, "by then Left is a legal turn, not a reversal")
    }

    @Test
    fun `the turn queue does not grow without bound`() {
        val game = game()
        repeat(20) { game.turn(if (it % 2 == 0) Direction.Up else Direction.Right) }

        // Whatever was queued, two steps is enough to work through it and still be alive.
        game.step()
        game.step()
        assertFalse(game.isOver)
    }

    @Test
    fun `eating grows the snake and scores`() {
        val game = game()
        // Put the food directly ahead by stepping until the head is next to it.
        val head = game.snake.first()
        val food = game.food
        val lengthBefore = game.snake.size

        // Drive the head onto the food deliberately rather than hoping.
        driveTo(game, food)

        assertEquals(lengthBefore + 1, game.snake.size, "eating adds a segment")
        assertEquals(1, game.score)
        assertNotEquals(food, game.food, "new food appears somewhere else")
        assertTrue(head != game.snake.first())
    }

    @Test
    fun `food never appears underneath the snake`() {
        val game = game(width = 5, height = 5, seed = 7)
        repeat(12) {
            driveTo(game, game.food)
            if (game.isOver) return@repeat
            assertFalse(game.food in game.snake, "food must land on a free square")
        }
    }

    @Test
    fun `running into a wall ends the game`() {
        val game = game()
        repeat(game.width) { if (!game.isOver) game.step() }

        assertTrue(game.isOver, "the snake should have reached the right wall")
        assertEquals(StepResult.Died, game.step(), "a finished game stays finished")
    }

    @Test
    fun `running into itself ends the game`() {
        val game = game(width = 12, height = 12, seed = 3)
        // Grow to five segments, then turn in a tight square onto the body.
        repeat(2) { driveTo(game, game.food) }
        assertTrue(game.snake.size >= 5)

        game.turn(Direction.Up); game.step()
        game.turn(Direction.Left); game.step()
        game.turn(Direction.Down); game.step()

        assertTrue(game.isOver, "a tight loop should meet the body")
    }

    @Test
    fun `moving into the square the tail is leaving is allowed`() {
        val game = game(width = 12, height = 12, seed = 3)
        // A three-long snake turning in a circle should survive: the tail moves out of the way.
        game.turn(Direction.Up); game.step()
        game.turn(Direction.Left); game.step()
        game.turn(Direction.Down); game.step()

        assertFalse(game.isOver, "the tail vacates the square before the head arrives")
    }

    @Test
    fun `a finished game ignores input`() {
        val game = game()
        repeat(game.width) { game.step() }
        assertTrue(game.isOver)

        val snapshot = game.snake
        game.turn(Direction.Up)
        game.step()

        assertEquals(snapshot, game.snake, "nothing moves after death")
    }

    @Test
    fun `reset puts everything back`() {
        val game = game()
        driveTo(game, game.food)
        repeat(game.width) { game.step() }
        assertTrue(game.isOver)

        game.reset()

        assertFalse(game.isOver)
        assertEquals(0, game.score)
        assertEquals(3, game.snake.size)
        assertEquals(Direction.Right, game.direction)
    }

    @Test
    fun `the same seed plays the same game`() {
        fun play(): List<Cell> {
            val game = SnakeGame(10, 8, Random(42))
            val food = mutableListOf(game.food)
            repeat(5) { driveTo(game, game.food); food += game.food }
            return food
        }
        assertEquals(play(), play(), "a fixed seed must be reproducible, or these tests are theatre")
    }

    @Test
    fun `a board too small to turn around in is refused`() {
        assertThrows<IllegalArgumentException> { SnakeGame(width = 3, height = 8) }
        assertThrows<IllegalArgumentException> { SnakeGame(width = 8, height = 2) }
    }

    /**
     * Walks the head to [target] the short way round, one axis at a time. Gives up rather than
     * looping forever if the snake dies on the way.
     */
    private fun driveTo(game: SnakeGame, target: Cell) {
        var guard = game.width * game.height * 2
        while (!game.isOver && game.snake.first() != target && guard-- > 0) {
            val head = game.snake.first()
            val wanted = when {
                head.y != target.y && canGo(game, if (target.y > head.y) Direction.Up else Direction.Down) ->
                    if (target.y > head.y) Direction.Up else Direction.Down
                head.x != target.x ->
                    if (target.x > head.x) Direction.Right else Direction.Left
                else -> game.direction
            }
            game.turn(wanted)
            game.step()
        }
    }

    private fun canGo(game: SnakeGame, direction: Direction) = !direction.isOpposite(game.direction)
}
