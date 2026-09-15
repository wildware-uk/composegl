package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which way a screen reads: left to right, or right to left.
 *
 * Arabic, Hebrew, Persian and Urdu read from the right, and a screen in one of them is the mirror
 * of the same screen in English — the first thing in a row is on the right, a label sits against
 * the right of its panel, the back button is in the top-right corner. Nothing in a layout says
 * "left": it says [HorizontalAlignment.Start] and [Arrangement.Start], and this is what decides
 * which side the start is on.
 *
 * What it moves: the children of a [Row], a [Column], a [Box], a [FlowRow] and a [Grid]; `Start` and
 * `End` in every alignment; `Modifier.paddingRelative`; and where text sits in a label or a field.
 * What it leaves alone: anything that names a side outright — `Modifier.padding(left = …)`, an
 * `offset`, a picture — a custom `Layout`, which asks [MeasureScope.layoutDirection] if it cares, and
 * the order of the children in the tree, so focus with Tab still goes first to last.
 */
enum class LayoutDirection {
    Ltr,
    Rtl,
}

/**
 * The direction of everything inside it. Left to right unless something above says otherwise.
 *
 * Read by every layout when it is composed, so changing it — a player picking Arabic on the
 * options screen — mirrors the screen on the next frame without anything being rebuilt by hand.
 */
val LocalLayoutDirection: ProvidableCompositionLocal<LayoutDirection> =
    staticCompositionLocalOf { LayoutDirection.Ltr }

/**
 * [content] laid out in [direction].
 *
 * Usually provided for you by [dev.wildware.composegl.ui.text.ProvideLocale], from the language.
 * Provide it by hand for the part of a right-to-left screen that must not mirror: a timeline, a
 * media scrubber, a map.
 */
@Composable
fun ProvideLayoutDirection(direction: LayoutDirection, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides direction, content = content)

/**
 * This alignment as the side it really is on: [HorizontalAlignment.Start] for the left and
 * [HorizontalAlignment.End] for the right, whatever [direction] is.
 */
internal fun HorizontalAlignment.absolute(direction: LayoutDirection): HorizontalAlignment =
    if (direction == LayoutDirection.Ltr) this else when (this) {
        HorizontalAlignment.Start -> HorizontalAlignment.End
        HorizontalAlignment.Centre -> HorizontalAlignment.Centre
        HorizontalAlignment.End -> HorizontalAlignment.Start
    }

/**
 * The corners a built-in layout has written, turned round for a right-to-left screen.
 *
 * Every layout here works its children out left to right and asks this last, so the mirroring is
 * one line of arithmetic in one place rather than a second copy of each arrangement: a child `x` in
 * from the left of a [width]-wide node goes `x` in from the right instead. That is also why every
 * arrangement mirrors correctly for nothing — `Start` packs right, `SpaceBetween` is still even.
 */
internal fun MeasureScope.mirrorPlacements(width: Float, count: Int) {
    if (layoutDirection == LayoutDirection.Ltr) return
    val placeables = placeables(count)
    val placements = placements(count)
    for (index in 0 until count) {
        val placeable = placeables[index] ?: continue
        placements[index * 2] = width - placements[index * 2] - placeable.width
    }
}
