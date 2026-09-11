package dev.wildware.composegl.snake

import dev.wildware.composegl.snake.game.Difficulty
import dev.wildware.composegl.snake.game.Direction
import dev.wildware.composegl.snake.game.HighScore
import dev.wildware.composegl.snake.game.HighScoreStore
import dev.wildware.composegl.snake.game.Screen
import dev.wildware.composegl.snake.game.SnakeSession
import dev.wildware.composegl.lwjgl3.GlCanvas
import dev.wildware.composegl.lwjgl3.GlfwClipboard
import dev.wildware.composegl.lwjgl3.GlfwKeyboardInput
import dev.wildware.composegl.lwjgl3.GlfwPointerInput
import dev.wildware.composegl.lwjgl3.GlfwTextInput
import dev.wildware.composegl.lwjgl3.GlfwWindow
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.ScalePolicy
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
 * Nothing here is the game. A window, fonts, a canvas and a loop that calls [SnakeApp] four times;
 * the rules, the board, the interface and the input are in `composegl-demo-snake-core`, and the
 * Android launcher calls exactly the same four methods.
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
    // The desktop's own input method, so the name field can be typed in Japanese, Chinese or
    // Korean rather than only in the languages one key press can express.
    val app = SnakeApp(fonts, FileHighScores(), GlfwClipboard(window), textInput = GlfwTextInput(window))

    var viewport = window.viewport(SnakeApp.Design, ScalePolicy.Fit)
    GlfwPointerInput(sink = app.input, viewport = { viewport }, pixelScale = { window.pixelScale }).attachTo(window)
    GlfwKeyboardInput(app.input).attachTo(window)

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

            app.update(delta)

            viewport = window.viewport(SnakeApp.Design, ScalePolicy.Fit)
            if (frames < script.size) play(app.session, script[frames], app.budget)

            GL11.glClearColor(0.043f, 0.055f, 0.075f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            app.frame(canvas, viewport, System.nanoTime())

            window.present()

            frames++
            if (shot != null && frames >= script.size + 2 && now >= shotAt) {
                save(shot, window.framebuffer, canvas.drawCalls)
                break
            }
        }
    } finally {
        app.close()
        canvas.close()
        fonts.close()
        window.close()
    }
}

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
