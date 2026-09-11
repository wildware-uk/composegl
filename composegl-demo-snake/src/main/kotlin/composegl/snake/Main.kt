package composegl.snake

import composegl.snake.game.Difficulty
import composegl.snake.game.Direction
import composegl.snake.game.HighScore
import composegl.snake.game.HighScoreStore
import composegl.snake.game.Screen
import composegl.snake.game.SnakeSession
import composegl.snake.game.StepResult
import composegl.snake.render.BoardRenderer
import composegl.snake.ui.SnakeUi
import composegl.lwjgl3.GlCanvas
import composegl.lwjgl3.GlfwClipboard
import composegl.lwjgl3.GlfwKeyboardInput
import composegl.lwjgl3.GlfwPointerInput
import composegl.lwjgl3.GlfwWindow
import composegl.lwjgl3.StbFonts
import composegl.ui.debug.FrameBudget
import composegl.ui.draw.DrawPass
import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.host.UiHost
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.run
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11

/**
 * Snake, on the raw OpenGL backend. No LibGDX anywhere on the classpath.
 *
 * Run it with `./gradlew :composegl-demo-snake:run`. Arrows or WASD to steer, Space to pause.
 *
 * The shape of the frame is the whole demo: the board is drawn first, by [BoardRenderer], which has
 * never heard of a composition; the interface is drawn second, into the same frame, by the toolkit.
 * The only thing they share is [SnakeSession], which is Compose state the game loop writes and the
 * interface reads.
 *
 * `COMPOSEGL_SNAKE_SHOT=<path>` draws one frame, saves it and exits; `COMPOSEGL_SNAKE_SHOT_AT` is
 * how many seconds to let the game run first, and `COMPOSEGL_SNAKE_SCRIPT` is a comma-separated
 * list of `play`, `pause`, `die` and the four directions, played a step a frame at the start, so a
 * screenshot can show a screen a still frame would never reach.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun main() {
    val window = GlfwWindow("ComposeGL - Snake", 1280, 720)
    val fonts = StbFonts(pageSize = 1024)
    val typeface = resource("fonts/DejaVuSans.ttf")
    fonts.register("body", typeface, listOf(13, 16, 20))
    fonts.register("display", typeface, listOf(34))

    val canvas = GlCanvas(fonts)
    val session = SnakeSession(FileHighScores())
    val board = BoardRenderer()
    val host = UiHost()
    val skin = snakeSkin(fonts)
    // Off: it is a debug tool, and F3 puts it up.
    val budget = FrameBudget().also { it.isOn = false }
    host.setContent { SnakeUi(session, fonts, skin.skin, GlfwClipboard(window), budget) }

    var viewport = window.viewport(Design, ScalePolicy.Fit)
    val input = SnakeInput(session, host.root, budget)
    GlfwPointerInput(sink = input, viewport = { viewport }, pixelScale = { window.pixelScale }).attachTo(window)
    GlfwKeyboardInput(input).attachTo(window)

    val shot: String? = System.getenv("COMPOSEGL_SNAKE_SHOT")
    val shotAt: Float = System.getenv("COMPOSEGL_SNAKE_SHOT_AT")?.toFloatOrNull() ?: 0f
    val script: List<String> = System.getenv("COMPOSEGL_SNAKE_SCRIPT")
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    var frames = 0
    var last = GLFW.glfwGetTime().toFloat()

    try {
        while (!window.shouldClose()) {
            val now = GLFW.glfwGetTime().toFloat()
            val delta = (now - last).coerceAtMost(0.1f)
            last = now

            skin.reloadIfChanged()
            board.advance(delta)
            if (session.advance(delta) == StepResult.Ate) board.onEat()
            val changed = budget.recompose { host.frame(System.nanoTime()) }

            viewport = window.viewport(Design, ScalePolicy.Fit)
            budget.layout { MeasurePass().run(host.root, viewport) }
            input.frame(System.nanoTime() / 1_000_000)
            if (frames < script.size) play(session, script[frames], budget)

            GL11.glClearColor(0.043f, 0.055f, 0.075f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            // The game first, the interface over it, in one canvas and one frame.
            board.draw(canvas, BoardArea, session.game, session.showGrid, session.stepProgress)
            budget.draw { DrawPass(canvas).draw(host.root) }
            canvas.end()

            // After end(), because that is when the last batch is actually handed over.
            budget.endFrame(canvas.drawCalls, changed)

            window.present()

            frames++
            if (shot != null && frames >= script.size + 2 && now >= shotAt) {
                save(shot, window.framebuffer, canvas.drawCalls)
                break
            }
        }
    } finally {
        host.dispose()
        canvas.close()
        fonts.close()
        window.close()
    }
}

private val Design = Size(1280f, 720f)

/** Where the board goes: the whole screen bar the column the HUD sits in. */
private val BoardArea = Rect(330f, 40f, 1240f, 680f)

/** One step of a screenshot script. A real run never calls this. */
private fun play(session: SnakeSession, step: String, budget: FrameBudget) {
    when (step) {
        "play" -> session.startGame()
        "pause" -> session.pause()
        "menu" -> session.toMenu()
        // Straight into the wall on the left, which is the shortest way to a game over screen.
        "die" -> {
            session.turn(Direction.Left)
            while (session.screen == Screen.Playing) session.advance(1f)
        }
        "up" -> session.turn(Direction.Up)
        "down" -> session.turn(Direction.Down)
        "left" -> session.turn(Direction.Left)
        "right" -> session.turn(Direction.Right)
        "budget" -> budget.toggle()
        else -> error("a script step is play, pause, menu, die, budget or a direction, not '$step'")
    }
}

private fun resource(path: String): ByteArray =
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream(path)) { "no $path on the classpath" }
        .use { it.readBytes() }

/**
 * High scores that survive a restart, in one small file beside the player's other settings.
 *
 * Deliberately forgiving: a file that has been edited by hand, or written by an older version, is
 * read for whatever rows still parse rather than throwing a game away at startup.
 */
private class FileHighScores(
    private val file: Path = Path.of(System.getProperty("user.home"), ".composegl-snake-scores"),
) : HighScoreStore {

    override fun load(): List<HighScore> = runCatching {
        if (!Files.exists(file)) return emptyList()
        Files.readAllLines(file).mapNotNull(::parse)
    }.getOrElse { emptyList() }

    override fun save(scores: List<HighScore>) {
        runCatching {
            Files.write(
                file,
                scores.map { "${it.name.replace(FIELD, " ")}$FIELD${it.score}$FIELD${it.difficulty.name}" },
            )
        }
    }

    private fun parse(row: String): HighScore? {
        val parts = row.split(FIELD)
        if (parts.size != 3) return null
        val score = parts[1].toIntOrNull() ?: return null
        val difficulty = Difficulty.entries.firstOrNull { it.name == parts[2] } ?: return null
        return HighScore(parts[0], score, difficulty)
    }

    private companion object {
        /** A separator no player can type into their own name. */
        const val FIELD = "\u001F"
    }
}

/** One frame, written out as a PNG, the right way up. */
private fun save(path: String, size: Size, drawCalls: Int) {
    val width = size.width.toInt()
    val height = size.height.toInt()
    val bytes = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4)
    GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            // OpenGL hands back the bottom row first.
            val at = ((height - 1 - y) * width + x) * 4
            image.setRGB(
                x,
                y,
                (bytes.get(at).toInt() and 0xFF shl 16) or
                    (bytes.get(at + 1).toInt() and 0xFF shl 8) or
                    (bytes.get(at + 2).toInt() and 0xFF),
            )
        }
    }
    ImageIO.write(image, "png", File(path))
    println("wrote $path ($drawCalls draw calls)")
}
