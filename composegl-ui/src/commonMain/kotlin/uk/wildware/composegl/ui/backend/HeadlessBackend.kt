package uk.wildware.composegl.ui.backend

import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.graphics.TextureHandle
import uk.wildware.composegl.ui.text.FontMetrics
import uk.wildware.composegl.ui.text.FontProvider
import uk.wildware.composegl.ui.text.TextLayout
import uk.wildware.composegl.ui.text.TextStyle

/**
 * Text measured as if every glyph were the same width.
 *
 * Not a real font, and that is the point: a test asserting that a button is 84 pixels wide should
 * be asserting the layout rule, not the shape of a particular letter in a particular typeface.
 * Widths here are exact and obvious — a character is 0.6 of the text size — so an expected number
 * in a test can be worked out on paper.
 */
class MonospaceFontProvider(private val advanceRatio: Float = 0.6f) : FontProvider {

    override fun metrics(style: TextStyle) = FontMetrics(
        size = style.size,
        ascent = style.size * 0.8f,
        descent = style.size * 0.2f,
        capHeight = style.size * 0.7f,
        lineHeight = style.lineHeight,
        spaceAdvance = style.size * advanceRatio,
    )

    override fun measure(text: String, style: TextStyle, maxWidth: Float): TextLayout {
        val advance = style.size * advanceRatio
        val lines = wrap(text, maxWidth / advance)
        val limited = if (style.maxLines > 0 && lines.size > style.maxLines) {
            lines.take(style.maxLines).toMutableList().also { kept ->
                kept[kept.lastIndex] = kept.last() + style.ellipsis
            }
        } else {
            lines
        }

        return MeasuredText(
            text = text,
            size = Size(
                width = (limited.maxOfOrNull { it.length } ?: 0) * advance,
                height = limited.size * style.lineHeight,
            ),
            lineCount = limited.size,
            firstBaseline = style.size * 0.8f,
            lines = limited,
        )
    }

    /**
     * Greedy wrapping by whole words, breaking a word only when it cannot fit a line by itself.
     *
     * The same rule real text layout uses, so a test written against this measures the same
     * *decisions* a real font would make even though the widths differ.
     */
    private fun wrap(text: String, charactersPerLine: Float): List<String> {
        val limit = if (charactersPerLine.isFinite()) charactersPerLine.toInt() else Int.MAX_VALUE
        if (limit <= 0) return listOf("")

        return text.split('\n').flatMap { paragraph ->
            if (paragraph.length <= limit) return@flatMap listOf(paragraph)

            val lines = mutableListOf<String>()
            var current = StringBuilder()

            paragraph.split(' ').forEach { word ->
                var remaining = word
                while (remaining.length > limit) {
                    if (current.isNotEmpty()) { lines += current.toString(); current = StringBuilder() }
                    lines += remaining.take(limit)
                    remaining = remaining.drop(limit)
                }
                val separator = if (current.isEmpty()) "" else " "
                if (current.length + separator.length + remaining.length > limit) {
                    lines += current.toString()
                    current = StringBuilder(remaining)
                } else {
                    current.append(separator).append(remaining)
                }
            }
            if (current.isNotEmpty() || lines.isEmpty()) lines += current.toString()
            lines
        }
    }

    private class MeasuredText(
        override val text: String,
        override val size: Size,
        override val lineCount: Int,
        override val firstBaseline: Float,
        val lines: List<String>,
    ) : TextLayout {
        override fun toString() = "MeasuredText(${lines.joinToString(" / ")}, $size)"
    }
}

/** A picture that is only a size. Enough to lay out around, which is all a headless test needs. */
data class FakeTexture(override val width: Int, override val height: Int) : TextureHandle

/** Textures by name, from a map. Anything not in the map does not exist. */
class MapTextureSource(private val textures: Map<String, TextureHandle> = emptyMap()) : TextureSource {
    override fun texture(name: String): TextureHandle? = textures[name]
}

/**
 * A whole backend with no window, no OpenGL and no engine.
 *
 * The default way to test anything in this toolkit: build one, draw a tree into it, and assert
 * what came out of [canvas]. It ships in the main source set so the modules downstream of this one
 * can use it too.
 */
class HeadlessBackend(
    bounds: Rect = Rect.of(0f, 0f, 1280f, 720f),
    override val fonts: FontProvider = MonospaceFontProvider(),
    override val clipboard: Clipboard = InMemoryClipboard(),
    override val softKeyboard: SoftKeyboard = RecordingSoftKeyboard(),
    override val textures: TextureSource = MapTextureSource(),
) : UiBackend {

    override val canvas: RecordingCanvas = RecordingCanvas(bounds)
}
