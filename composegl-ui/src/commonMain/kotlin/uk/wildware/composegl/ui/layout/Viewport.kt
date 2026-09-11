package uk.wildware.composegl.ui.layout

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.node.UiNode
import kotlin.math.floor

/** How a design resolution is fitted onto a screen that is not the same shape. */
enum class ScalePolicy {

    /** Scale until it just fits, keeping the shape. Bars on two sides. The safe default. */
    Fit,

    /** Scale until it covers the screen, keeping the shape. Nothing is barred; edges are lost. */
    Fill,

    /** Scale each axis on its own. Nothing is lost and nothing is barred, but circles go oval. */
    Stretch,

    /**
     * Whole multiples only, never larger than would fit.
     *
     * The one that matters for pixel art: a 2.5× scale puts one source pixel across two screen
     * pixels and the next across three, and the result shimmers as things move.
     */
    Integer,
}

/**
 * The bridge between the interface's own coordinates and the screen's.
 *
 * There is no `dp` here, because a game is not a document. A game declares the resolution it was
 * designed at — say 1280×720 — and everything in the tree is in those virtual pixels. The same
 * interface then lays out identically on a phone and on a television, and only the scale differs.
 *
 * @param design the resolution the interface was drawn for. Positions and sizes in the tree are
 *   in these units.
 * @param physical the framebuffer, in real pixels.
 * @param safeArea room to keep clear at the edges of the *physical* screen: a phone's notch, a
 *   television's overscan. Subtracted before anything is laid out, so nothing lands under it.
 */
data class Viewport(
    val design: Size,
    val physical: Size,
    val policy: ScalePolicy = ScalePolicy.Fit,
    val safeArea: Padding = Padding.None,
) {

    private val rawX = if (design.width > 0f) physical.width / design.width else 1f
    private val rawY = if (design.height > 0f) physical.height / design.height else 1f

    val scaleX: Float
    val scaleY: Float

    init {
        when (policy) {
            ScalePolicy.Fit -> minOf(rawX, rawY).let { scaleX = it; scaleY = it }
            ScalePolicy.Fill -> maxOf(rawX, rawY).let { scaleX = it; scaleY = it }
            ScalePolicy.Stretch -> {
                scaleX = rawX
                scaleY = rawY
            }
            // Never zero: at less than one whole multiple the interface overflows rather than
            // vanishing, which is at least something a person can see and report.
            ScalePolicy.Integer -> floor(minOf(rawX, rawY)).coerceAtLeast(1f)
                .let { scaleX = it; scaleY = it }
        }
    }

    /** Where the interface's origin sits on the screen. Half the leftover, so it is centred. */
    val origin = Offset(
        (physical.width - design.width * scaleX) / 2f,
        (physical.height - design.height * scaleY) / 2f,
    )

    /**
     * The safe area in the interface's own units, counting only what actually overlaps it.
     *
     * A letterbox bar is already keeping the interface clear of that edge, so an inset smaller
     * than the bar costs nothing. This is what stops a design being pushed in twice on a wide
     * screen with a notch.
     */
    val safeInsets = Padding(
        left = ((safeArea.left - origin.x).coerceAtLeast(0f)) / scaleX,
        top = ((safeArea.top - origin.y).coerceAtLeast(0f)) / scaleY,
        right = ((safeArea.right - origin.x).coerceAtLeast(0f)) / scaleX,
        bottom = ((safeArea.bottom - origin.y).coerceAtLeast(0f)) / scaleY,
    )

    /** Where the root of the tree goes, in the interface's units. */
    val contentOrigin = Offset(safeInsets.left, safeInsets.top)

    /** How big the root of the tree is, in the interface's units. */
    val contentSize = Size(
        (design.width - safeInsets.horizontal).coerceAtLeast(0f),
        (design.height - safeInsets.vertical).coerceAtLeast(0f),
    )

    /** Exactly this size and no other: the root fills what is left after the safe area. */
    val rootConstraints = Constraints.fixed(contentSize.width, contentSize.height)

    /** A screen position — a mouse, a finger — in the interface's own units. */
    fun toDesign(screen: Offset) = Offset(
        (screen.x - origin.x) / scaleX,
        (screen.y - origin.y) / scaleY,
    )

    /** An interface position on the screen. What a renderer needs for every draw call. */
    fun toScreen(design: Offset) = Offset(
        design.x * scaleX + origin.x,
        design.y * scaleY + origin.y,
    )

    companion object {

        /** A viewport that does nothing at all: one virtual pixel per real one. */
        fun oneToOne(size: Size) = Viewport(size, size, ScalePolicy.Stretch)
    }
}

/** Lays a tree out to fill [viewport], with the safe area already taken off. */
fun MeasurePass.run(root: UiNode, viewport: Viewport) {
    measure(root, viewport.rootConstraints).placeAt(viewport.contentOrigin.x, viewport.contentOrigin.y)
}
