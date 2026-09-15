package dev.wildware.composegl.ui.debug

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import kotlin.math.max

/**
 * What `Modifier.debugBounds` draws: an outline, a faint wash, and optionally the size in a chip.
 *
 * The label is drawn out of rectangles in a three-by-five digit font rather than as text, because a
 * modifier is drawn with a canvas and a rectangle and nothing else — no fonts. That is also what
 * makes it the same on every backend and in a headless test, and readable on a game whose skin has
 * not loaded a font yet, which is exactly when somebody is trying to find out where a box went.
 *
 * It costs a few dozen rectangles a frame per labelled box while it is on. It is a debugging aid:
 * take it off before shipping.
 */
internal object DebugBounds {

    /** How many design units one dot of the digit font is. */
    const val Pixel = 2f

    /** The gap between the chip's edge and the digits. */
    const val Pad = 2f

    const val Columns = 3
    const val Rows = 5

    /** One glyph and the gap after it. */
    const val Advance = (Columns + 1) * Pixel

    const val ChipHeight = Rows * Pixel + 2 * Pad

    /** How strongly the box is washed with its colour: enough to see an empty box, not to hide one. */
    const val WashAlpha = 0.12f

    /** Every character a label can hold. */
    const val Alphabet = "0123456789x"

    fun draw(canvas: UiCanvas, rect: Rect, colour: Colour, label: Boolean) {
        if (rect.width < 1f || rect.height < 1f) {
            // A widget laid out with no width or no height is the commonest reason to reach for this,
            // and an outline of nothing draws nothing. So it shows as a line one unit thick instead.
            canvas.rect(
                Rect(rect.left, rect.top, max(rect.right, rect.left + 1f), max(rect.bottom, rect.top + 1f)),
                colour,
            )
        } else {
            canvas.rect(rect, colour.scaleAlpha(WashAlpha))
            canvas.border(rect, colour, 1f)
        }
        if (label) drawLabel(canvas, rect, colour)
    }

    /** The size of [rect], rounded to whole units, as `120x40`. */
    fun label(rect: Rect): String = "${rounded(rect.width)}x${rounded(rect.height)}"

    /**
     * The chip in the top-left corner, inside the box so a clip round the box keeps it, and the digits
     * on it in black or white, whichever the colour is lighter or darker than.
     */
    private fun drawLabel(canvas: UiCanvas, rect: Rect, colour: Colour) {
        val text = label(rect)
        val chip = Rect.of(rect.left, rect.top, 2 * Pad + text.length * Advance - Pixel, ChipHeight)
        canvas.rect(chip, colour)

        val luminance = (colour.red * 299 + colour.green * 587 + colour.blue * 114) / 1000
        val ink = if (luminance >= 140) Colour.Black else Colour.White
        val x = chip.left + Pad
        val y = chip.top + Pad
        for (index in text.indices) {
            val mask = glyph(text[index])
            val left = x + index * Advance
            for (row in 0 until Rows) {
                // One rectangle per run of lit dots in a row rather than one per dot.
                var column = 0
                while (column < Columns) {
                    if (mask and bit(row, column) == 0) {
                        column++
                        continue
                    }
                    val start = column
                    while (column < Columns && mask and bit(row, column) != 0) column++
                    canvas.rect(
                        Rect(left + start * Pixel, y + row * Pixel, left + column * Pixel, y + (row + 1) * Pixel),
                        ink,
                    )
                }
            }
        }
    }

    /** The dot at [row], [column] of a glyph, as a bit of its mask: top-left is the highest. */
    fun bit(row: Int, column: Int): Int = 1 shl (Rows * Columns - 1 - (row * Columns + column))

    /** A character of [Alphabet] as fifteen dots, three a row, top row first. */
    fun glyph(char: Char): Int = when (char) {
        '0' -> 0b111_101_101_101_111
        '1' -> 0b010_110_010_010_111
        '2' -> 0b111_001_111_100_111
        '3' -> 0b111_001_111_001_111
        '4' -> 0b101_101_111_001_001
        '5' -> 0b111_100_111_001_111
        '6' -> 0b111_100_111_101_111
        '7' -> 0b111_001_001_001_001
        '8' -> 0b111_101_111_101_111
        '9' -> 0b111_101_111_001_111
        'x' -> 0b000_101_010_101_000
        else -> error("no glyph for '$char'")
    }

    private fun rounded(value: Float): Int = (value + 0.5f).toInt().coerceAtLeast(0)
}
