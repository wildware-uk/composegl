package composegl.snake.game

import kotlin.random.Random

/** A square on the board. (0, 0) is the bottom-left. */
data class Cell(val x: Int, val y: Int)

enum class Direction(val dx: Int, val dy: Int) {
    Up(0, 1),
    Down(0, -1),
    Left(-1, 0),
    Right(1, 0),
    ;

    /** You cannot turn back on yourself; the snake would eat its own neck. */
    fun isOpposite(other: Direction): Boolean = dx == -other.dx && dy == -other.dy
}

enum class Difficulty(val label: String, val stepsPerSecond: Float) {
    Gentle("Gentle", 6f),
    Normal("Normal", 9f),
    Brisk("Brisk", 13f),
    Frantic("Frantic", 18f),
}

/** What the last [SnakeGame.step] did, so the app can react — play a sound, shake the screen. */
enum class StepResult { Moved, Ate, Died }

/**
 * The rules of Snake, and nothing else.
 *
 * No LibGDX, no Compose, no OpenGL, no clock. It moves when you tell it to, and everything it
 * decides is a function of what it was given — which is what makes the rules testable without a
 * window, a driver or a frame loop.
 *
 * Feed it a fixed [Random] and it is fully deterministic, so a test can say exactly where the food
 * will be.
 */
class SnakeGame(
    val width: Int = 24,
    val height: Int = 18,
    private val random: Random = Random.Default,
) {

    init {
        require(width >= 4 && height >= 4) { "The board needs room to turn around, got ${width}x$height" }
    }

    /** Head first, tail last. */
    var snake: List<Cell> = emptyList()
        private set

    var food: Cell = Cell(0, 0)
        private set

    var direction: Direction = Direction.Right
        private set

    var score: Int = 0
        private set

    var isOver: Boolean = false
        private set

    /**
     * Turns the player has asked for but the snake has not made yet, oldest first.
     *
     * A queue rather than a single slot, because players double-tap. Right-then-up-then-left
     * arrives well inside one tick at any sensible speed. With one slot the second turn overwrites
     * the first, so the snake never actually goes up, and "left" is then a reversal into its own
     * neck. With a queue it goes up on this tick and left on the next, which is what the player
     * meant. Two is enough: nobody plans three turns ahead at nine steps a second.
     */
    private val pendingTurns = ArrayDeque<Direction>()

    init {
        reset()
    }

    fun reset() {
        val midY = height / 2
        // Three segments, head to the right, with room ahead.
        snake = listOf(Cell(3, midY), Cell(2, midY), Cell(1, midY))
        direction = Direction.Right
        pendingTurns.clear()
        score = 0
        isOver = false
        placeFood()
    }

    /**
     * Asks the snake to turn. The turn takes effect on the next [step], and a reversal is ignored.
     *
     * Queuing rather than turning immediately is what stops a fast double-tap — right then down
     * then left within one tick — from turning the head into the neck.
     */
    fun turn(towards: Direction) {
        if (isOver || pendingTurns.size >= MAX_PENDING_TURNS) return
        // Judge the turn against where the snake will be pointing when it happens, not where it
        // points now — otherwise the second turn of a double-tap is measured against the wrong thing.
        val from = pendingTurns.lastOrNull() ?: direction
        if (towards.isOpposite(from) || towards == from) return
        pendingTurns.addLast(towards)
    }

    /** Advances one square. Does nothing once the game is over. */
    fun step(): StepResult {
        if (isOver) return StepResult.Died

        pendingTurns.removeFirstOrNull()?.let { direction = it }

        val head = snake.first()
        val next = Cell(head.x + direction.dx, head.y + direction.dy)

        if (next.x !in 0 until width || next.y !in 0 until height) {
            isOver = true
            return StepResult.Died
        }

        // The tail square is about to be vacated, so moving into it is legal — unless we just ate.
        val eating = next == food
        val body = if (eating) snake else snake.dropLast(1)
        if (next in body) {
            isOver = true
            return StepResult.Died
        }

        snake = listOf(next) + body
        if (!eating) return StepResult.Moved

        score += 1
        placeFood()
        return StepResult.Ate
    }

    /** True when the snake fills the board and there is nowhere left to put food. */
    val isWon: Boolean get() = snake.size == width * height

    private companion object {
        const val MAX_PENDING_TURNS = 2
    }

    private fun placeFood() {
        val free = buildList {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val cell = Cell(x, y)
                    if (cell !in snake) add(cell)
                }
            }
        }
        if (free.isEmpty()) {
            isOver = true
            return
        }
        food = free[random.nextInt(free.size)]
    }
}
