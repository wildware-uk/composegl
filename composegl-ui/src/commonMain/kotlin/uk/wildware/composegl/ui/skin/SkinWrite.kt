package uk.wildware.composegl.ui.skin

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.ArtAtlas
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.EdgeMode
import uk.wildware.composegl.ui.graphics.NinePatch
import uk.wildware.composegl.ui.graphics.TextureHandle
import uk.wildware.composegl.ui.layout.Padding
import uk.wildware.composegl.ui.text.TextStyle

/**
 * A skin turned back into the file it could have been loaded from.
 *
 * Writing exists mostly so that reading can be trusted: a skin that survives a trip out to text and
 * back is a reader and a format that agree about every field, which is a much harder thing to get
 * wrong by accident than a test per key. It also makes a skin editor possible without the editor
 * having to know the format.
 *
 * The output is canonical rather than pretty-printed from the original: keys in a fixed order,
 * padding written the shortest way that means the same thing, and no comments, because comments
 * belong to the person who wrote the file and this did not.
 */
internal class SkinWrite(private val art: ArtAtlas?) {

    /** Region names by the texture they resolve to, so a background can be written back by name. */
    private val names: Map<TextureHandle, String> by lazy {
        art?.names.orEmpty().mapNotNull { name -> art?.region(name)?.let { it to name } }.toMap()
    }

    private val text = StringBuilder()
    private var depth = 0

    fun skin(skin: Skin): String {
        text.clear()
        depth = 0
        obj {
            skin.defaults?.let { key("defaults") { state(it.asState()) } }
            key("styles") {
                obj {
                    skin.styles.forEach { (name, style) -> key(name) { style(style) } }
                }
            }
        }
        return text.append('\n').toString()
    }

    private fun style(style: Style) = obj {
        state(style.base, braces = false)
        style.hovered?.let { key("hovered") { state(it) } }
        style.focused?.let { key("focused") { state(it) } }
        style.pressed?.let { key("pressed") { state(it) } }
        style.disabled?.let { key("disabled") { state(it) } }
    }

    /**
     * @param braces false when the state's keys sit directly in a style, which is how a base is
     *   written: a style *is* its base, plus the states that differ from it.
     */
    private fun state(state: StateStyle, braces: Boolean = true) {
        val body = {
            state.background?.let { key("background") { drawable(it) } }
            state.textColour?.let { key("textColour") { colour(it) } }
            state.tint?.let { key("tint") { colour(it) } }
            state.padding?.let { key("padding") { padding(it) } }
            state.textStyle?.let { key("text") { textStyle(it) } }
            state.contentOffset?.let { key("contentOffset") { offset(it) } }
        }
        if (braces) obj { body() } else body()
    }

    private fun drawable(drawable: SkinDrawable) = when (drawable) {
        is SkinDrawable.Blank -> text.append("\"none\"")
        is SkinDrawable.Fill -> obj {
            key("fill") { colour(drawable.colour) }
            if (drawable.corner != 0f) key("corner") { number(drawable.corner) }
            drawable.border?.let { key("border") { colour(it) } }
            if (drawable.border != null && drawable.borderWidth != 1f) {
                key("borderWidth") { number(drawable.borderWidth) }
            }
            if (drawable.padding != Padding.None) key("padding") { padding(drawable.padding) }
        }
        is SkinDrawable.Image -> obj {
            key("image") { string(nameOf(drawable.texture)) }
            if (drawable.padding != Padding.None) key("padding") { padding(drawable.padding) }
        }
        is SkinDrawable.Patch -> patch(drawable.patch)
    }

    private fun patch(patch: NinePatch) = obj {
        key("patch") { string(nameOf(patch.texture)) }
        key("slice") { padding(patch.slice) }
        if (patch.padding != patch.slice) key("padding") { padding(patch.padding) }
        val edges = listOf(
            "left" to patch.leftEdge,
            "top" to patch.topEdge,
            "right" to patch.rightEdge,
            "bottom" to patch.bottomEdge,
            "centreAcross" to patch.centreAcross,
            "centreDown" to patch.centreDown,
        ).filter { (_, mode) -> mode != EdgeMode.Stretch }
        if (edges.isNotEmpty()) {
            key("edges") {
                obj { edges.forEach { (name, mode) -> key(name) { string(mode.name.lowercase()) } } }
            }
        }
    }

    private fun textStyle(style: TextStyle) = obj {
        key("font") { string(style.family) }
        key("size") { number(style.size) }
        key("lineHeight") { number(style.lineHeightRatio) }
        if (style.maxLines != 0) key("maxLines") { number(style.maxLines.toFloat()) }
        if (style.ellipsis != TextStyle.Default.ellipsis) key("ellipsis") { string(style.ellipsis) }
    }

    private fun padding(padding: Padding) = when {
        padding.left == padding.right && padding.top == padding.bottom &&
            padding.left == padding.top -> number(padding.left)
        padding.left == padding.right && padding.top == padding.bottom ->
            list(padding.left, padding.top)
        else -> list(padding.left, padding.top, padding.right, padding.bottom)
    }

    private fun offset(offset: Offset) = list(offset.x, offset.y)

    private fun colour(colour: Colour) = string(
        if (colour.alpha == 0xFF) "#" + hex(colour.argb and 0xFFFFFF, 6)
        else "#" + hex(colour.argb, 8),
    )

    private fun hex(value: Int, digits: Int) =
        value.toUInt().toString(16).uppercase().padStart(digits, '0').takeLast(digits)

    private fun nameOf(texture: TextureHandle): String = names[texture]
        ?: throw SkinFormatException(
            "a background uses a texture the atlas does not contain, so it has no name to write",
        )

    // --- the shape of the output ---

    private fun obj(body: () -> Unit) {
        text.append("{\n")
        depth++
        val start = text.length
        body()
        if (text.length == start) {
            // An object nobody put anything in. Undo the newline so it reads as `{}`.
            text.setLength(start - 1)
            depth--
            text.append("}")
            return
        }
        text.append('\n')
        depth--
        indent()
        text.append('}')
    }

    private fun key(name: String, body: () -> Unit) {
        if (text.last() != '\n') text.append(",\n")
        indent()
        string(name)
        text.append(": ")
        body()
    }

    private fun list(vararg values: Float) {
        text.append(values.joinToString(", ", "[", "]") { number ->
            if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()
        })
    }

    private fun number(value: Float) {
        text.append(if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString())
    }

    private fun string(value: String) {
        text.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> text.append("\\\"")
                '\\' -> text.append("\\\\")
                '\n' -> text.append("\\n")
                '\r' -> text.append("\\r")
                '\t' -> text.append("\\t")
                else ->
                    if (character.code < 0x20) text.append("\\u").append(hex(character.code, 4))
                    else text.append(character)
            }
        }
        text.append('"')
    }

    private fun indent() = text.append("  ".repeat(depth))
}

/** A resolved style as the overrides that would produce it, which is how defaults are written. */
private fun ResolvedStyle.asState() = StateStyle(
    background = background,
    textColour = textColour,
    tint = tint,
    padding = padding,
    textStyle = textStyle,
    contentOffset = contentOffset,
)
