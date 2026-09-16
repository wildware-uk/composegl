package dev.wildware.composegl.ui.graphics

/**
 * The sizes zoomed text is made again at.
 *
 * A glyph is a picture made at one pixel size. Zoomed in past it, it goes soft; zoomed out, it
 * shimmers. Making every glyph again at every zoom a pinch passes through would fill the glyph
 * atlas in a second, so a canvas makes them at the nearest of a few [Steps] instead and stretches
 * that copy the rest of the way: a little soft between two steps, sharp at each one, and a handful
 * of sizes rather than one a frame.
 *
 * See [UiCanvas.pushTransform].
 */
object TextZoom {

    /** The zooms text is made at. Below the first or above the last, the nearest end is used. */
    val Steps: List<Float> = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f)

    /** [zoom] to the nearest of [Steps]. One for anything that is not a positive, finite number. */
    fun snap(zoom: Float): Float {
        if (!zoom.isFinite() || zoom <= 0f) return 1f
        var best = Steps[0]
        for (step in Steps) {
            if (kotlin.math.abs(step - zoom) < kotlin.math.abs(best - zoom)) best = step
        }
        return best
    }
}
