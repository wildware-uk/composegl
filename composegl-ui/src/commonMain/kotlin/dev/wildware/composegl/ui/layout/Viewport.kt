package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.node.UiNode
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
 * @param area the part of the screen this interface gets, in real pixels. All of it by default.
 *   Split-screen gives each player one: the design is fitted into the area rather than the whole
 *   window, and [origin] is still measured from the window's corner, so a canvas draws into the
 *   right place and a mouse position turns into the right player's coordinates with nothing else
 *   told about the split. [safeArea] stays measured from the window's edges, so only the players
 *   whose area actually reaches a notch are pushed in by it. [splitScreen] makes these.
 */
data class Viewport(
    val design: Size,
    val physical: Size,
    val policy: ScalePolicy = ScalePolicy.Fit,
    val safeArea: Padding = Padding.None,
    val area: Rect = Rect.of(0f, 0f, physical.width, physical.height),
) {

    private val rawX = if (design.width > 0f) area.width / design.width else 1f
    private val rawY = if (design.height > 0f) area.height / design.height else 1f

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

    /**
     * Where the interface's origin sits on the screen. Half the [area]'s leftover, so it is centred
     * in its area, and measured from the window's corner.
     */
    val origin = Offset(
        area.left + (area.width - design.width * scaleX) / 2f,
        area.top + (area.height - design.height * scaleY) / 2f,
    )

    /**
     * The safe area in the interface's own units, counting only what actually overlaps it.
     *
     * A letterbox bar is already keeping the interface clear of that edge, so an inset smaller
     * than the bar costs nothing. This is what stops a design being pushed in twice on a wide
     * screen with a notch. The gap on each side is measured to the window's own edge, so the
     * player on the right of a split is not pushed in by a notch on the left: the whole of the
     * other player's half is between them and it.
     */
    val safeInsets = Padding(
        left = ((safeArea.left - origin.x).coerceAtLeast(0f)) / scaleX,
        top = ((safeArea.top - origin.y).coerceAtLeast(0f)) / scaleY,
        right = ((safeArea.right - (physical.width - origin.x - design.width * scaleX)).coerceAtLeast(0f)) / scaleX,
        bottom = ((safeArea.bottom - (physical.height - origin.y - design.height * scaleY)).coerceAtLeast(0f)) / scaleY,
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

        /**
         * One viewport per player, sharing one window: local co-op.
         *
         * One player gets the whole window. Two sit side by side, or one above the other with
         * [stacked]. Three and four get a quarter each, reading left to right and then down, and a
         * third player leaves the bottom-right quarter to the game — a map, a scoreboard.
         *
         * Every player is laid out at the same [design] resolution, fitted into their own area by
         * [policy], so one HUD written once is right in every quarter. Pair each with its own
         * `UiHost`, and give an [dev.wildware.composegl.ui.input.InputRouter] the same viewports so
         * a click lands in the half it was made in.
         *
         * @param physical the whole window, in real pixels.
         * @param safeArea the window's own safe area, measured from its edges; each player only
         *   gives up the part of it their area reaches.
         */
        fun splitScreen(
            design: Size,
            physical: Size,
            players: Int,
            policy: ScalePolicy = ScalePolicy.Fit,
            safeArea: Padding = Padding.None,
            stacked: Boolean = false,
        ): List<Viewport> {
            require(players in 1..4) { "split-screen is for one to four players, not $players" }
            val halfWidth = physical.width / 2f
            val halfHeight = physical.height / 2f
            val areas = when {
                players == 1 -> listOf(Rect.of(0f, 0f, physical.width, physical.height))
                players == 2 && stacked -> listOf(
                    Rect.of(0f, 0f, physical.width, halfHeight),
                    Rect.of(0f, halfHeight, physical.width, physical.height - halfHeight),
                )
                players == 2 -> listOf(
                    Rect.of(0f, 0f, halfWidth, physical.height),
                    Rect.of(halfWidth, 0f, physical.width - halfWidth, physical.height),
                )
                else -> listOf(
                    Rect.of(0f, 0f, halfWidth, halfHeight),
                    Rect.of(halfWidth, 0f, physical.width - halfWidth, halfHeight),
                    Rect.of(0f, halfHeight, halfWidth, physical.height - halfHeight),
                    Rect.of(halfWidth, halfHeight, physical.width - halfWidth, physical.height - halfHeight),
                ).take(players)
            }
            return areas.map { Viewport(design, physical, policy, safeArea, it) }
        }
    }
}

/** Lays a tree out to fill [viewport], with the safe area already taken off. */
fun MeasurePass.run(root: UiNode, viewport: Viewport) {
    beginAt(root)
    measure(root, viewport.rootConstraints).placeAt(viewport.contentOrigin.x, viewport.contentOrigin.y)
    reportLayout()
}
