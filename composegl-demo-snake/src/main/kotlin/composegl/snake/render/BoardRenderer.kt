package composegl.snake.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.utils.Disposable
import composegl.snake.game.SnakeGame

/**
 * The game, drawn with OpenGL. No Compose anywhere in this file.
 *
 * That separation is the point of the demo: this is what a game already has, and ComposeGL's job
 * is to put an interface on top of it without either side knowing about the other.
 */
class BoardRenderer : Disposable {

    private val shapes = ShapeRenderer()
    private val camera = OrthographicCamera()

    /** Grows while the snake is eating, so the board flashes. Cosmetic, and it proves GL is live. */
    private var eatFlash = 0f

    fun resize(width: Int, height: Int) {
        camera.setToOrtho(false, width.toFloat(), height.toFloat())
        camera.update()
    }

    fun onEat() {
        eatFlash = 1f
    }

    fun update(deltaSeconds: Float) {
        eatFlash = (eatFlash - deltaSeconds * 3f).coerceAtLeast(0f)
    }

    /**
     * Draws the board centred in the window, letterboxed to keep the cells square.
     *
     * @param headProgress 0..1 through the current step, so the head slides rather than teleports.
     */
    fun render(game: SnakeGame, showGrid: Boolean, headProgress: Float) {
        val screenWidth = Gdx.graphics.width.toFloat()
        val screenHeight = Gdx.graphics.height.toFloat()
        camera.setToOrtho(false, screenWidth, screenHeight)
        camera.update()

        // Leave room down the left for the HUD panel, and a margin all round.
        val left = screenWidth * 0.28f
        val available = Rect(left, MARGIN, screenWidth - left - MARGIN, screenHeight - MARGIN * 2)
        val cell = minOf(available.width / game.width, available.height / game.height)
        val boardWidth = cell * game.width
        val boardHeight = cell * game.height
        val originX = available.x + (available.width - boardWidth) / 2f
        val originY = available.y + (available.height - boardHeight) / 2f

        fun cellX(x: Int) = originX + x * cell
        fun cellY(y: Int) = originY + y * cell

        shapes.projectionMatrix = camera.combined

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        // Board.
        val flash = Interpolation.pow2Out.apply(eatFlash)
        shapes.color = BOARD.cpy().lerp(BOARD_FLASH, flash)
        shapes.rect(originX, originY, boardWidth, boardHeight)

        // Food, pulsing gently so the eye finds it.
        val pulse = 0.82f + 0.18f * kotlin.math.sin(gameTime * 6f)
        val foodInset = cell * (1f - 0.62f * pulse) / 2f
        shapes.color = FOOD
        shapes.rect(
            cellX(game.food.x) + foodInset,
            cellY(game.food.y) + foodInset,
            cell - foodInset * 2f,
            cell - foodInset * 2f,
        )

        // Snake, head brightest, fading down the body.
        game.snake.forEachIndexed { index, segment ->
            val t = if (game.snake.size <= 1) 0f else index.toFloat() / (game.snake.size - 1)
            shapes.color = SNAKE_HEAD.cpy().lerp(SNAKE_TAIL, t)
            val inset = cell * 0.06f
            val slide = if (index == 0) headProgress.coerceIn(0f, 1f) else 0f
            shapes.rect(
                cellX(segment.x) + inset + game.direction.dx * slide * cell * 0.35f,
                cellY(segment.y) + inset + game.direction.dy * slide * cell * 0.35f,
                cell - inset * 2f,
                cell - inset * 2f,
            )
        }
        shapes.end()

        if (showGrid) {
            shapes.begin(ShapeRenderer.ShapeType.Line)
            shapes.color = GRID
            for (x in 0..game.width) shapes.line(cellX(x), originY, cellX(x), originY + boardHeight)
            for (y in 0..game.height) shapes.line(originX, cellY(y), originX + boardWidth, cellY(y))
            shapes.end()
        }

        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = BORDER
        shapes.rect(originX, originY, boardWidth, boardHeight)
        shapes.end()
    }

    private var gameTime = 0f

    fun advanceTime(deltaSeconds: Float) {
        gameTime += deltaSeconds
    }

    override fun dispose() = shapes.dispose()

    private data class Rect(val x: Float, val y: Float, val width: Float, val height: Float)

    private companion object {
        const val MARGIN = 48f
        val BOARD: Color = Color.valueOf("11151c")
        val BOARD_FLASH: Color = Color.valueOf("1d2740")
        val GRID: Color = Color.valueOf("1b2029")
        val BORDER: Color = Color.valueOf("2d3646")
        val FOOD: Color = Color.valueOf("ef5350")
        val SNAKE_HEAD: Color = Color.valueOf("7ee081")
        val SNAKE_TAIL: Color = Color.valueOf("2e7d4f")
    }
}
