package composegl.gdx

import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import composegl.ui.graphics.EdgeMode
import composegl.ui.graphics.NinePatch
import composegl.ui.layout.Padding

/**
 * Nine-patches read out of a LibGDX atlas.
 *
 * An artist draws `panel.9.png` with the one-pixel black guide marks down its edges, the texture
 * packer turns those marks into `split` and `pad` lines in the `.atlas` file, and the numbers
 * arrive here. Nobody has to type a corner size into the source, and when the art changes the
 * layout follows it.
 *
 * The two are separate on purpose. `split` is where the picture may be stretched; `pad` is where
 * the contents are allowed to sit. They are usually the same, which is why `pad` is optional, but
 * art with an outer glow or a drop shadow baked in needs a `pad` larger than its `split`.
 */
fun TextureAtlas.ninePatch(
    name: String,
    leftEdge: EdgeMode = EdgeMode.Stretch,
    topEdge: EdgeMode = EdgeMode.Stretch,
    rightEdge: EdgeMode = EdgeMode.Stretch,
    bottomEdge: EdgeMode = EdgeMode.Stretch,
    centreAcross: EdgeMode = EdgeMode.Stretch,
    centreDown: EdgeMode = EdgeMode.Stretch,
): NinePatch {
    val region = findRegion(name)
        ?: error("no region called \"$name\" in this atlas; it has ${regions.map { it.name }.distinct()}")
    return region.ninePatch(leftEdge, topEdge, rightEdge, bottomEdge, centreAcross, centreDown)
}

/** The same, for a region already in hand. */
fun TextureAtlas.AtlasRegion.ninePatch(
    leftEdge: EdgeMode = EdgeMode.Stretch,
    topEdge: EdgeMode = EdgeMode.Stretch,
    rightEdge: EdgeMode = EdgeMode.Stretch,
    bottomEdge: EdgeMode = EdgeMode.Stretch,
    centreAcross: EdgeMode = EdgeMode.Stretch,
    centreDown: EdgeMode = EdgeMode.Stretch,
): NinePatch {
    val split = findValue("split")
        ?: error("\"$name\" has no split marks, so it is a picture and not a nine-patch")
    val pad = findValue("pad")
    return NinePatch(
        texture = GdxTexture(this),
        slice = split.toPadding(),
        padding = (pad ?: split).toPadding(),
        leftEdge = leftEdge,
        topEdge = topEdge,
        rightEdge = rightEdge,
        bottomEdge = bottomEdge,
        centreAcross = centreAcross,
        centreDown = centreDown,
    )
}

/**
 * A nine-patch from a plain picture, when there is no atlas and no guide marks.
 *
 * What a demo or a test uses, and what a game uses for the one texture it did not pack.
 */
fun TextureRegion.ninePatch(
    slice: Padding,
    padding: Padding = slice,
    leftEdge: EdgeMode = EdgeMode.Stretch,
    topEdge: EdgeMode = EdgeMode.Stretch,
    rightEdge: EdgeMode = EdgeMode.Stretch,
    bottomEdge: EdgeMode = EdgeMode.Stretch,
    centreAcross: EdgeMode = EdgeMode.Stretch,
    centreDown: EdgeMode = EdgeMode.Stretch,
) = NinePatch(
    GdxTexture(this), slice, padding, leftEdge, topEdge, rightEdge, bottomEdge,
    centreAcross, centreDown,
)

/** LibGDX writes these four as left, right, top, bottom. Reordering them once, here. */
private fun IntArray.toPadding() = Padding(
    left = this[0].toFloat(),
    top = this[2].toFloat(),
    right = this[1].toFloat(),
    bottom = this[3].toFloat(),
)
