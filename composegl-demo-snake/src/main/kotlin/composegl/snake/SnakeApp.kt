package composegl.snake

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import composegl.gdx.ComposeGdx
import composegl.gdx.ComposeOverlay
import composegl.gdx.ComposeTexture
import composegl.snake.game.Direction
import composegl.snake.game.Screen
import composegl.snake.game.SnakeSession
import composegl.snake.game.StepResult
import composegl.snake.render.BoardRenderer
import composegl.snake.ui.SnakeUi
import composegl.snake.ui.WallScoreboard

/**
 * Snake, with its interface built out of Compose.
 *
 * The split is the whole point. [BoardRenderer] draws the game with OpenGL and knows nothing about
 * Compose; [SnakeUi] is ordinary Material 3 and knows nothing about OpenGL; [SnakeSession] is the
 * state they share. ComposeGL is the part in the middle, and there is not much of it:
 *
 * ```
 * ui = ComposeOverlay()
 * ui.setContent { SnakeUi(session, font) }
 * Gdx.input.inputProcessor = InputMultiplexer(ui, gameInput)
 * ```
 *
 * The interesting behaviour falls out of that. While a menu or dialog is up, Compose has the
 * keyboard and the arrow keys move focus. The moment play starts, nothing in the HUD holds focus,
 * so the same keys reach the snake — and the pause button still takes a click, because pointer
 * events and key events are decided separately.
 */
class SnakeApp(private val store: GdxHighScores = GdxHighScores()) : ApplicationAdapter() {

    lateinit var session: SnakeSession
        private set

    private lateinit var ui: ComposeOverlay
    private lateinit var wallScoreboard: ComposeTexture
    private lateinit var board: BoardRenderer
    private lateinit var batch: SpriteBatch
    private lateinit var fontFamily: FontFamily

    /** 0..1 through the current step, so the head glides between squares. */
    private var stepProgress = 0f

    private var framesDrawn = 0
    var selfCheckFailed = false
        private set

    /** Only ever sees what the Compose overlay did not want. */
    private val gameInput = object : InputAdapter() {
        override fun keyDown(keycode: Int): Boolean {
            val direction = when (keycode) {
                Input.Keys.UP, Input.Keys.W -> Direction.Up
                Input.Keys.DOWN, Input.Keys.S -> Direction.Down
                Input.Keys.LEFT, Input.Keys.A -> Direction.Left
                Input.Keys.RIGHT, Input.Keys.D -> Direction.Right
                else -> null
            }
            if (direction != null) {
                session.turn(direction)
                return true
            }
            return when (keycode) {
                Input.Keys.SPACE, Input.Keys.P, Input.Keys.ESCAPE -> {
                    when (session.screen) {
                        Screen.Playing -> session.pause()
                        Screen.Paused -> session.resume()
                        else -> Unit
                    }
                    true
                }
                Input.Keys.ENTER -> {
                    if (session.screen != Screen.Playing) session.startGame()
                    true
                }
                else -> false
            }
        }
    }

    override fun create() {
        session = SnakeSession(store)

        val bytes = Gdx.files.internal("fonts/DejaVuSans.ttf").readBytes()
        fontFamily = FontFamily(Font(identity = "DejaVuSans", data = bytes))

        ui = ComposeOverlay()
        ui.setContent { SnakeUi(session, fontFamily) }

        wallScoreboard = ComposeTexture(280, 96)
        wallScoreboard.setContent { WallScoreboard(session, fontFamily) }

        board = BoardRenderer()
        batch = SpriteBatch()

        Gdx.input.inputProcessor = InputMultiplexer(ui, gameInput)
    }

    override fun resize(width: Int, height: Int) {
        ui.resize(width, height)
        board.resize(width, height)
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime.coerceAtMost(MAX_DELTA)

        advanceGame(delta)
        drawWorld(delta)

        // The overlay last, so it sits over the board.
        ui.update()
        ui.draw()

        framesDrawn++
        maybeSelfCheck()
    }

    private fun advanceGame(delta: Float) {
        board.advanceTime(delta)
        board.update(delta)

        when (session.advance(delta)) {
            StepResult.Ate -> { board.onEat(); stepProgress = 0f }
            StepResult.Moved -> stepProgress = 0f
            StepResult.Died, null -> Unit
        }

        stepProgress = if (session.screen == Screen.Playing) {
            (stepProgress + delta * session.difficulty.stepsPerSecond).coerceAtMost(1f)
        } else {
            0f
        }
    }

    private fun drawWorld(delta: Float) {
        Gdx.gl.glClearColor(0.043f, 0.055f, 0.075f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        board.render(session.game, session.showGrid, stepProgress)

        // The wall scoreboard is a Compose UI the *game* composites, not the overlay.
        wallScoreboard.update()
        wallScoreboard.render()
        if (session.screen != Screen.Menu) {
            val width = Gdx.graphics.width.toFloat()
            val height = Gdx.graphics.height.toFloat()
            batch.projectionMatrix.setToOrtho2D(0f, 0f, width, height)
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
            batch.begin()
            batch.draw(
                TextureRegion(wallScoreboard.texture),
                width - 280f - 40f,
                height - 96f - 32f,
                280f,
                96f,
            )
            batch.end()
            Gdx.gl.glDisable(GL20.GL_BLEND)
        }
    }

    override fun dispose() {
        ui.dispose()
        wallScoreboard.dispose()
        ComposeGdx.dispose()
        board.dispose()
        batch.dispose()
    }

    /**
     * Plays the game without a person, so CI can tell whether it still works: start a run, steer
     * for a while, pause, resume, then check the screens and the score behaved. Enabled by
     * `COMPOSEGL_SNAKE_FRAMES`.
     */
    private fun maybeSelfCheck() {
        val limit = System.getenv("COMPOSEGL_SNAKE_FRAMES")?.toIntOrNull() ?: return
        val processor = Gdx.input.inputProcessor
        // When set, the snake is steered into a wall instead, so a screenshot can show game over.
        val crashAt = System.getenv("COMPOSEGL_SNAKE_CRASH_AT")?.toIntOrNull()

        fun tap(keycode: Int) {
            processor.keyDown(keycode)
            processor.keyUp(keycode)
        }

        when {
            framesDrawn == 10 -> {
                check(session.screen == Screen.Menu, "should open on the menu")
                tap(Input.Keys.ENTER)
            }
            framesDrawn == 12 -> check(session.screen == Screen.Playing, "Enter should start a game")

            // Head for the wall, then leave it alone and let it die.
            crashAt != null && framesDrawn == crashAt -> tap(Input.Keys.UP)
            crashAt != null -> Unit

            framesDrawn == 210 -> tap(Input.Keys.SPACE)
            framesDrawn == 212 -> check(session.screen == Screen.Paused, "Space should pause")
            framesDrawn == 215 -> tap(Input.Keys.SPACE)
            framesDrawn == 217 -> check(session.screen == Screen.Playing, "Space should resume")

            // Otherwise: keep playing. Steering has to continue for the whole run, or the snake
            // sails on in its last direction and dies against a wall.
            framesDrawn > 20 && framesDrawn % 4 == 0 -> steerTowardsFood(processor)
        }

        System.getenv("COMPOSEGL_SNAKE_SHOT_AT")?.toIntOrNull()?.let {
            if (framesDrawn == it) saveScreenshot()
        }

        if (framesDrawn >= limit) {
            println(
                "snake: $framesDrawn frames, score ${session.score}, length ${session.length}, " +
                    "screen ${session.screen}, ${ui.stats.composeRenders} compose renders",
            )
            // A short run is for taking a screenshot; only a real one is long enough to score.
            if (limit >= 300 && crashAt == null) {
                check(session.score > 0, "the snake should have eaten something in $limit frames")
                check(session.screen == Screen.Playing, "it should still be playing")
            }
            if (crashAt != null) check(session.screen == Screen.GameOver, "it should have crashed")
            Gdx.app.exit()
        }
    }

    /** Saves what is on screen, so a headless run can be looked at. */
    private fun saveScreenshot() {
        val path = System.getenv("COMPOSEGL_SNAKE_SCREENSHOT") ?: "snake.png"
        // glReadPixels hands back rows bottom-up; flip so the PNG is the right way up.
        val source = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        val flipped = Pixmap(source.width, source.height, source.format)
        for (y in 0 until source.height) {
            flipped.drawPixmap(source, 0, y, 0, source.height - 1 - y, source.width, 1)
        }
        source.dispose()
        val file = if (path.startsWith("/")) Gdx.files.absolute(path) else Gdx.files.local(path)
        PixmapIO.writePNG(file, flipped)
        flipped.dispose()
        println("snake: screenshot -> $path")
    }

    /**
     * Plays, badly but not suicidally: head for the food, and refuse any turn that would run into
     * a wall or into the snake. Good enough to keep a run alive for as long as CI needs.
     */
    private fun steerTowardsFood(processor: com.badlogic.gdx.InputProcessor) {
        if (session.screen != Screen.Playing) return
        val game = session.game
        val head = game.snake.firstOrNull() ?: return
        val food = game.food

        // Towards the food first, then anything that is not immediately fatal.
        val wanted = buildList {
            if (food.y > head.y) add(Direction.Up)
            if (food.y < head.y) add(Direction.Down)
            if (food.x > head.x) add(Direction.Right)
            if (food.x < head.x) add(Direction.Left)
            addAll(Direction.entries)
        }

        val safe = wanted.firstOrNull { direction ->
            if (direction.isOpposite(game.direction)) return@firstOrNull false
            val next = composegl.snake.game.Cell(head.x + direction.dx, head.y + direction.dy)
            next.x in 0 until game.width &&
                next.y in 0 until game.height &&
                next !in game.snake.dropLast(1)
        } ?: return

        val key = when (safe) {
            Direction.Up -> Input.Keys.UP
            Direction.Down -> Input.Keys.DOWN
            Direction.Left -> Input.Keys.LEFT
            Direction.Right -> Input.Keys.RIGHT
        }
        processor.keyDown(key)
        processor.keyUp(key)
    }

    private fun check(condition: Boolean, what: String) {
        if (condition) return
        println("snake: FAIL $what (screen=${session.screen}, frame=$framesDrawn)")
        selfCheckFailed = true
    }

    private companion object {
        /** A long frame after a stall should not teleport the snake across the board. */
        const val MAX_DELTA = 0.1f
    }
}
