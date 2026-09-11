package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.graphics.TextureHandle
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.Alignment
import composegl.ui.layout.Constraints
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.Measurable
import composegl.ui.layout.MeasurePolicy
import composegl.ui.layout.MeasureResult
import composegl.ui.layout.MeasureScope
import composegl.ui.modifier.Modifier
import composegl.ui.skin.LocalSkin

/**
 * What a picture does when the room it is given is not the shape it is.
 *
 * Four answers, and a game wants all four: an icon must not go oval, a portrait must fill its
 * frame with no gaps, a background must cover the screen whatever shape the screen is, and a
 * pixel-art sprite must be drawn at its own size or not at all.
 */
enum class ImageFit {

    /** Pulled to fill. Aspect ignored — right for a gradient, wrong for a face. */
    Stretch,

    /** As big as fits, aspect kept. The whole picture is visible; there may be gaps. */
    Contain,

    /** Big enough to leave no gaps, aspect kept. The overflowing edges are cropped off. */
    Cover,

    /** Its own size in texture pixels, whatever room it was given. Can overflow. */
    None,
}

/**
 * A picture.
 *
 * It asks for its own size and takes whatever it is given, so a game that wants something else
 * says so with a size modifier — one rule instead of four.
 *
 * @param fit what to do when the room is not the picture's shape.
 * @param tint multiplied into the picture. White leaves it alone; a colour with alpha fades it.
 * @param alignment where the picture sits when it does not fill its room, and which part of it
 *   survives when [ImageFit.Cover] crops.
 */
@Composable
fun Image(
    texture: TextureHandle,
    modifier: Modifier = Modifier,
    fit: ImageFit = ImageFit.Contain,
    tint: Colour = Colour.White,
    alignment: Alignment = Alignment.Centre,
) {
    val painter = remember(texture, fit, tint, alignment) { ImagePainter(texture, fit, tint, alignment) }
    LeafLayout(modifier = modifier, name = "image", measurePolicy = painter, draw = painter.draw)
}

/**
 * The same picture, named rather than held.
 *
 * What a game writes: `Image("icons/heart")`. The name is looked up in the skin's atlas, and a name
 * that is not in it stops here and says so — because a missing picture draws nothing, and nothing
 * looks exactly like a widget somebody has not written yet.
 */
@Composable
fun Image(
    region: String,
    modifier: Modifier = Modifier,
    fit: ImageFit = ImageFit.Contain,
    tint: Colour = Colour.White,
    alignment: Alignment = Alignment.Centre,
) {
    val atlas = LocalSkin.current.art ?: error("no art atlas is loaded, so \"$region\" cannot be found")
    val texture = atlas.region(region) ?: error("no region called \"$region\" in the atlas")
    Image(texture, modifier, fit, tint, alignment)
}

private class ImagePainter(
    private val texture: TextureHandle,
    private val fit: ImageFit,
    private val tint: Colour,
    private val alignment: Alignment,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult = layout(
        constraints.constrainWidth(texture.width.toFloat()),
        constraints.constrainHeight(texture.height.toFloat()),
    ) {}

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        val (destination, source) = fitInto(bounds, texture.width.toFloat(), texture.height.toFloat(), fit, alignment)
        if (!destination.isEmpty) image(texture, destination, tint, source)
    }
}

/**
 * Where the picture goes, and how much of it is used.
 *
 * Returns the rectangle on screen and the part of the texture to take, in texture pixels. Only
 * [ImageFit.Cover] needs the second — every other fit uses the whole picture and moves the first.
 *
 * Aspect is kept by construction: one scale is worked out and both sides are multiplied by it, so
 * there is no arithmetic anywhere that could stretch one side and not the other.
 */
internal fun fitInto(
    bounds: Rect,
    textureWidth: Float,
    textureHeight: Float,
    fit: ImageFit,
    alignment: Alignment,
): Pair<Rect, Rect?> {
    if (textureWidth <= 0f || textureHeight <= 0f || bounds.isEmpty) return bounds to null

    fun placed(width: Float, height: Float): Rect {
        val (x, y) = alignment.offsetIn(bounds.width, bounds.height, width, height)
        return Rect.of(bounds.left + x, bounds.top + y, width, height)
    }

    return when (fit) {
        ImageFit.Stretch -> bounds to null

        ImageFit.Contain -> {
            val scale = minOf(bounds.width / textureWidth, bounds.height / textureHeight)
            placed(textureWidth * scale, textureHeight * scale) to null
        }

        ImageFit.None -> placed(textureWidth, textureHeight) to null

        ImageFit.Cover -> {
            // Crop rather than overflow: the destination is the whole box, and the source is the
            // part of the picture that has the box's shape, moved by the alignment.
            val scale = maxOf(bounds.width / textureWidth, bounds.height / textureHeight)
            val across = (bounds.width / scale).coerceAtMost(textureWidth)
            val down = (bounds.height / scale).coerceAtMost(textureHeight)
            val (x, y) = alignment.offsetIn(textureWidth, textureHeight, across, down)
            bounds to Rect.of(x, y, across, down)
        }
    }
}
