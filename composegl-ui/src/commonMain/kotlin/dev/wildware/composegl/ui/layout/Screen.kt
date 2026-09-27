package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.onSizeChanged

/**
 * How much screen the interface has, in its own units, for a screen that wants to change shape.
 *
 * [Viewport] already makes one design fit any screen: a 1280×720 interface is the same interface
 * on a phone and on a television, only smaller. That is scaling, and it is not the same thing as
 * being responsive — scaled down, a desktop layout on a phone is a desktop layout with tiny text
 * and three columns nobody can read. Being responsive is *changing* the layout, and to change it
 * something has to know how much room there is.
 *
 * This is that something, and the host provides it from the viewport every frame:
 *
 * ```kotlin
 * val screen = LocalScreen.current
 * if (screen.width < 700f) Column { menu(); page() } else Row { menu(); page() }
 * ```
 *
 * [size] is what the root is laid out at — the design size with the safe area already taken off —
 * so it is the room a screen actually has rather than the resolution somebody declared.
 *
 * @param size the room the interface has, in design units.
 * @param safeInsets what was taken off for a notch or a television's overscan, in the same units.
 *   Already subtracted from [size]; here so a background that deliberately runs under a notch can
 *   put it back.
 */
@Immutable
data class Screen(val size: Size, val safeInsets: Padding = Padding.None) {

    val width: Float get() = size.width
    val height: Float get() = size.height

    /** Which way round it is. A tablet turned on its side is a small desktop, not a tall phone. */
    val orientation: Orientation get() = if (width >= height) Orientation.Landscape else Orientation.Portrait

    /** How much room there is, in the three sizes a layout usually cares about. */
    val windowClass: WindowClass get() = WindowClass.of(width)

    companion object {

        /** What the interface is laid out in, off the viewport. */
        fun of(viewport: Viewport) = Screen(viewport.contentSize, viewport.safeInsets)
    }
}

/** Which way round a screen is. */
enum class Orientation { Portrait, Landscape }

/**
 * How much room there is, coarsely: a phone, a tablet, or a desktop.
 *
 * The breakpoints are Material's own — 600 and 840 — because they are where a layout stops
 * working rather than where a device happens to be, and because a number somebody already knows
 * is worth more than a better number nobody does. In design units, so they mean the same thing
 * whatever the screen is really made of.
 *
 * ```kotlin
 * when (LocalWindowClass.current) {
 *     WindowClass.Compact -> Stacked()     // a phone: one column, a bar along the bottom
 *     WindowClass.Medium -> Split()        // a tablet: a list beside the thing it opens
 *     WindowClass.Expanded -> Desktop()    // room for the lot
 * }
 * ```
 */
enum class WindowClass {

    /** Narrower than 600: a phone held upright. One column, and nothing beside anything. */
    Compact,

    /** 600 to 840: a tablet, or a window somebody has made small. Two columns at most. */
    Medium,

    /** 840 and wider: a laptop, a desktop, a television. Everything can be on at once. */
    Expanded;

    companion object {

        fun of(width: Float) = when {
            width < CompactBelow -> Compact
            width < ExpandedFrom -> Medium
            else -> Expanded
        }

        /** Narrower than this is [Compact]. */
        const val CompactBelow = 600f

        /** This and wider is [Expanded]. */
        const val ExpandedFrom = 840f
    }
}

/**
 * The room the interface has, provided by the host from the viewport it is laid out with.
 *
 * Dynamic rather than static, so a resize recomposes what reads it and nothing else.
 */
val LocalScreen = compositionLocalOf { Screen(Size.Zero) }

/**
 * The same thing coarsely, so a layout that only cares about phone-tablet-desktop recomposes when
 * that changes and not on every pixel of a drag on a desktop window's edge.
 */
val LocalWindowClass = compositionLocalOf { WindowClass.Expanded }

/**
 * A box that tells its content how much room it was given.
 *
 * [LocalScreen] answers for the whole screen; this answers for one place in it — a panel inside a
 * split, a card in a grid — which is what a component that has to work in more than one place
 * needs. The width is the width the parent offered, because the box fills it.
 *
 * ```kotlin
 * WithSize { room ->
 *     if (room.width < 420f) Column { icon(); label() } else Row { icon(); label() }
 * }
 * ```
 *
 * **It knows on the second frame.** The size comes from the layout pass, so the first composition
 * has to guess, and [estimate] is that guess — the whole screen by default, which is right for a
 * box that fills the screen and wrong for a narrow card. Pass something nearer if the first frame
 * matters. Everything after the first is exact, and a resize is picked up the frame after it.
 *
 * That is the honest shape of it without subcomposition, which the toolkit does not have: a real
 * `BoxWithConstraints` composes its content *inside* the measure pass, having already been told
 * the constraints. The cost here is one frame at the start, and the gain is that nothing else in
 * the layout core has to change.
 *
 * The height is the height the content asked for, not the room it was offered — a box's height is
 * decided by what is in it. Give the modifier a height, or `fillMaxSize`, if the height is what
 * the decision turns on.
 *
 * @param modifier what shape the box is. It fills the width it is offered unless told otherwise.
 * @param estimate the size to compose with before the first layout has measured it.
 */
@Composable
fun WithSize(
    modifier: Modifier = Modifier,
    estimate: Size = LocalScreen.current.size,
    content: @Composable (Size) -> Unit,
) {
    var measured by remember { mutableStateOf<Size?>(null) }
    // Remembered, because a handler made afresh every recomposition never compares equal to the
    // one the node already has and so replaces it every frame.
    val handler = remember { SizeChangedHandler { size -> measured = size } }
    Box(modifier.fillMaxWidth().onSizeChanged(handler)) {
        content(measured ?: estimate)
    }
}
