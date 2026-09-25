package dev.wildware.composegl.render

import dev.wildware.composegl.ui.graphics.Brush
import kotlin.math.roundToInt

/**
 * A gradient of more than two colours, baked into a strip of the atlas and sampled like a picture.
 *
 * Two colours ride in the vertex itself — the start in the fill's slot, the end in the border's —
 * and the shader mixes them. A run of stops has nowhere to ride, so it is drawn once into
 * [Texels] pixels on the page solid colour already comes from, and the shader reads across it. The
 * quad stays the same quad, so a button with a three-colour face still batches with every flat
 * panel around it.
 *
 * The strip is written premultiplied, because the GPU mixes neighbouring texels for us and that is
 * the mix [Brush.between] does: a run fading to transparent keeps its hue instead of darkening.
 * The shader straightens it again.
 *
 * One strip per brush, kept for as long as the atlas is. A skin has a handful of gradients and
 * hands back the same [Brush] every frame, so this fills up once and then answers from the map.
 */
internal class GradientRamps(private val atlas: GlyphAtlas) {

    private val spots = HashMap<Brush.Ramp, AtlasSpot?>()

    /** Where [ramp] lives on the page, or null when the atlas had no room for another strip. */
    fun spotFor(ramp: Brush.Ramp): AtlasSpot? = spots.getOrPut(ramp) { bake(ramp) }

    private fun bake(ramp: Brush.Ramp): AtlasSpot? {
        val spot = atlas.placeOrNull(Texels, 1) ?: return null
        val pixels = ByteArray(Texels * 4)
        for (texel in 0 until Texels) {
            // The middle of the texel, so the ends of the strip are the ends of the run.
            val colour = ramp.at((texel + 0.5f) / Texels)
            val alpha = colour.alphaFraction
            val at = texel * 4
            pixels[at] = (colour.red * alpha).roundToInt().coerceIn(0, 255).toByte()
            pixels[at + 1] = (colour.green * alpha).roundToInt().coerceIn(0, 255).toByte()
            pixels[at + 2] = (colour.blue * alpha).roundToInt().coerceIn(0, 255).toByte()
            pixels[at + 3] = colour.alpha.toByte()
        }
        spot.page.writeRgba(spot.x, spot.y, Texels, 1, pixels)
        return spot
    }

    companion object {

        /** How many pixels a run is drawn into. Enough that a band is smooth; small enough to ignore. */
        const val Texels = 64
    }
}
