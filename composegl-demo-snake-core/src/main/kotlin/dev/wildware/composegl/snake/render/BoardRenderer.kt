package dev.wildware.composegl.snake.render

import dev.wildware.composegl.snake.game.SnakeGame
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import kotlin.math.sin

/**
 * The game, drawn with OpenGL. No composition anywhere in this file.
 *
 * That separation is the point of the demo: this is the half a game already has, and the toolkit's
 * job is to put an interface over it without either side knowing about the other. It is drawn
 * before the interface, into the same frame, every frame — because a snake moves whether or not
 * anything on the HUD changed.
 *
 * It takes a [UiCanvas] rather than a backend so that the same board draws on whichever backend the
 * window came from. A game with its own renderer would use that instead and nothing here would
 * change on the interface's side.
 */
class BoardRenderer {

    /** Grows while the snake is eating, so the board flashes. Cosmetic, and it proves GL is live. */
    private var eatFlash = 0f

    private var time = 0f

    /** Where the board ended up last frame, so the HUD can be laid out beside it. */
    var bounds: Rect = Rect(0f, 0f, 0f, 0f)
        private set

    fun onEat() {
        eatFlash = 1f
    }

    fun advance(deltaSeconds: Float) {
        time += deltaSeconds
        eatFlash = (eatFlash - deltaSeconds * 3f).coerceAtLeast(0f)
    }

    /**
     * Draws the board inside [area], letterboxed to keep the cells square.
     *
     * @param headProgress 0..1 through the current step, so the head slides rather than teleports.
     */
    fun draw(canvas: UiCanvas, area: Rect, game: SnakeGame, showGrid: Boolean, headProgress: Float) {
        val cell = minOf(
            (area.right - area.left) / game.width,
            (area.bottom - area.top) / game.height,
        )
        val width = cell * game.width
        val height = cell * game.height
        val left = area.left + ((area.right - area.left) - width) / 2f
        val top = area.top + ((area.bottom - area.top) - height) / 2f
        bounds = Rect(left, top, left + width, top + height)

        fun x(at: Int) = left + at * cell
        // The rules put (0, 0) at the bottom left, as a game does; a canvas puts it at the top.
        fun y(at: Int) = top + (game.height - 1 - at) * cell

        canvas.rect(bounds, Board.mix(BoardFlash, eatFlash * eatFlash), corner = 4f)

        if (showGrid) {
            for (column in 1 until game.width) {
                canvas.rect(Rect(x(column), top, x(column) + 1f, top + height), Grid)
            }
            for (row in 1 until game.height) {
                canvas.rect(Rect(left, y(row), left + width, y(row) + 1f), Grid)
            }
        }

        // The food pulses gently, so the eye finds it.
        val pulse = 0.82f + 0.18f * sin(time * 6f)
        val foodInset = cell * (1f - 0.62f * pulse) / 2f
        canvas.rect(
            Rect(
                x(game.food.x) + foodInset,
                y(game.food.y) + foodInset,
                x(game.food.x) + cell - foodInset,
                y(game.food.y) + cell - foodInset,
            ),
            Food,
            corner = cell * 0.3f,
        )

        // The snake, head brightest, fading down the body.
        val inset = cell * 0.06f
        val slide = headProgress.coerceIn(0f, 1f) * cell * 0.35f
        game.snake.forEachIndexed { index, segment ->
            val along = if (game.snake.size <= 1) 0f else index.toFloat() / (game.snake.size - 1)
            val nudgeX = if (index == 0) game.direction.dx * slide else 0f
            val nudgeY = if (index == 0) -game.direction.dy * slide else 0f
            canvas.rect(
                Rect(
                    x(segment.x) + inset + nudgeX,
                    y(segment.y) + inset + nudgeY,
                    x(segment.x) + cell - inset + nudgeX,
                    y(segment.y) + cell - inset + nudgeY,
                ),
                SnakeHead.mix(SnakeTail, along),
                corner = cell * 0.22f,
            )
        }

        canvas.border(bounds, Border, width = 2f, corner = 4f)
    }

    /** Straight down the line between two colours, which is all this needs. */
    private fun Colour.mix(other: Colour, amount: Float): Colour {
        val towards = amount.coerceIn(0f, 1f)
        fun blend(from: Int, to: Int) = (from + (to - from) * towards).toInt().coerceIn(0, 255)
        return Colour(
            (blend(alpha, other.alpha) shl 24) or
                (blend(red, other.red) shl 16) or
                (blend(green, other.green) shl 8) or
                blend(blue, other.blue),
        )
    }

    private companion object {
        val Board = Colour.rgb(0x121822)
        val BoardFlash = Colour.rgb(0x1E3040)
        val Grid = Colour.argb(0x14FFFFFF)
        val Border = Colour.argb(0x334CC2FF)
        val Food = Colour.rgb(0xFF7A45)
        val SnakeHead = Colour.rgb(0x4CC2FF)
        val SnakeTail = Colour.rgb(0x1E5F82)
    }
}
