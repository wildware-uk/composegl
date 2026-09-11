package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import uk.wildware.composegl.ui.animation.Animatable
import uk.wildware.composegl.ui.animation.Clock
import uk.wildware.composegl.ui.animation.Easings
import uk.wildware.composegl.ui.animation.FloatVectoriser
import uk.wildware.composegl.ui.animation.LocalClocks
import uk.wildware.composegl.ui.animation.Tween
import uk.wildware.composegl.ui.animation.wait
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.UiCanvas
import uk.wildware.composegl.ui.focus.FocusWithinHandler
import uk.wildware.composegl.ui.input.InteractionState
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerHandler
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.LeafLayout
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.drawBehind
import uk.wildware.composegl.ui.modifier.fillMaxSize
import uk.wildware.composegl.ui.modifier.interaction
import uk.wildware.composegl.ui.modifier.onFocusWithin
import uk.wildware.composegl.ui.modifier.onPointer
import uk.wildware.composegl.ui.skin.ResolvedStyle
import uk.wildware.composegl.ui.skin.rememberStyle
import uk.wildware.composegl.ui.text.FontProvider
import uk.wildware.composegl.ui.text.TextLayout

/**
 * The one tooltip a screen has, and everything it knows about what to show.
 *
 * There is one because only one can be up at a time, and because the interesting rule needs a
 * single place to live: **the delay is for deciding to look at something, not for each thing**. A
 * player who is already reading one tooltip and moves along a row of icons has already decided, so
 * the next one appears at once. Every toolkit that stores the delay per widget gets this wrong and
 * makes a toolbar feel like it is arguing.
 */
class Tooltips internal constructor(
    internal val delayMillis: Int,
    internal val fadeMillis: Int,
) {

    /** What the pointer or focus is on now, which is not yet what is drawn. */
    internal var wanted by mutableStateOf<TooltipAnchor?>(null)

    /** What is actually up. Null until the delay has passed. */
    internal var showing by mutableStateOf<TooltipAnchor?>(null)

    internal fun enter(anchor: TooltipAnchor) {
        wanted = anchor
    }

    /**
     * Nothing is on it any more — unless something else already is, which happens in that order
     * when a pointer crosses straight from one to the next.
     */
    internal fun leave(anchor: TooltipAnchor) {
        if (wanted === anchor) wanted = null
    }
}

/**
 * One thing that has a tooltip.
 *
 * Deliberately not Compose state: where it is on screen is written while the frame is being drawn
 * and read a few nodes later by the layer that draws the tooltip, in the same frame. Making it
 * state would invalidate the composition every time a panel moved a pixel.
 */
internal class TooltipAnchor {
    var text: String = ""
    var follow: Boolean = false
    var bounds: Rect = Rect.Zero
    var pointer: Offset = Offset.Zero
}

/** The screen's tooltip, for the widgets under it to put things on. */
val LocalTooltips = staticCompositionLocalOf<Tooltips> {
    error("no tooltips: wrap the screen in a TooltipHost")
}

/**
 * Makes tooltips work for everything inside it. Put it round a screen, once.
 *
 * It is a host rather than a modifier because a tooltip has to be drawn **over** everything,
 * including the panel it belongs to and whatever is next to that panel — so it is composed last,
 * above the whole screen, and positions itself in the screen's own coordinates.
 *
 * @param delayMillis how long a pointer rests on something before its tooltip appears. Skipped
 *   entirely when one is already up.
 * @param fadeMillis how long the fade in and out take.
 */
@Composable
fun TooltipHost(
    delayMillis: Int = 500,
    fadeMillis: Int = 110,
    style: String = "tooltip",
    content: @Composable () -> Unit,
) {
    val tooltips = remember(delayMillis, fadeMillis) { Tooltips(delayMillis, fadeMillis) }

    CompositionLocalProvider(LocalTooltips provides tooltips) {
        Box(Modifier.fillMaxSize()) {
            content()
            TooltipLayer(tooltips, style)
        }
    }
}

/**
 * Gives [content] a tooltip.
 *
 * It appears when a pointer rests on it **or when focus lands on it**, which is the whole of the
 * gamepad story: a player on a pad never hovers anything, so a tooltip that only knows about a
 * pointer is invisible on a console.
 *
 * ```kotlin
 * Tooltip("Cuts through armour. 12 charges.") { Image("icon/cutter") }
 * ```
 *
 * @param follow whether it sits under the pointer as it moves, rather than under the thing itself.
 *   Worth it over something big — a map, a long row — and in the way over a button.
 */
@Composable
fun Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    follow: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tooltips = LocalTooltips.current
    val interaction = remember { InteractionState() }
    val anchor = remember { TooltipAnchor() }
    anchor.text = text
    anchor.follow = follow

    // Written while drawing, because that is where a node's place on screen is actually known.
    val capture: UiCanvas.(Rect) -> Unit = remember(anchor) { { bounds -> anchor.bounds = bounds } }

    val handler = remember(anchor) {
        PointerHandler { event ->
            if (event is PointerEvent.Move) {
                anchor.pointer = Offset(anchor.bounds.left + event.position.x, anchor.bounds.top + event.position.y)
            }
            false
        }
    }

    // Focus counts as hovering: on a pad nothing is ever hovered, so focus landing anywhere
    // inside — on the button this is wrapped round, not on this node — is what shows it there.
    var focused by remember { mutableStateOf(false) }
    val within = remember(anchor) { FocusWithinHandler { focused = it } }

    // An effect rather than a call in composition, so a widget that goes away while its tooltip is
    // up takes the tooltip with it.
    val wanted = interaction.isHovered || focused
    DisposableEffect(wanted, anchor, tooltips) {
        if (wanted) tooltips.enter(anchor)
        onDispose { if (wanted) tooltips.leave(anchor) }
    }

    Box(
        modifier.interaction(interaction)
            .onFocusWithin(within)
            .onPointer(handler)
            .drawBehind(capture),
    ) { content() }
}

/**
 * The tooltip itself: one node, over everything, drawn straight onto the canvas.
 *
 * It is drawn rather than laid out because where it goes depends on where it would end up — a
 * tooltip near the bottom of the screen goes above the thing it belongs to instead of below it,
 * and one near a side slides along until it fits. A layout cannot decide that; it has already been
 * given its place by the time it knows its size.
 */
@Composable
private fun TooltipLayer(tooltips: Tooltips, style: String) {
    val clocks = LocalClocks.current
    val fonts = rememberFonts()
    val resolved = rememberStyle(style)

    val fade = remember(clocks) { Animatable(0f, FloatVectoriser, Clock.Ui, clocks) }

    LaunchedEffect(tooltips, tooltips.wanted) {
        val wanted = tooltips.wanted
        if (wanted == null) {
            fade.animateTo(0f, Tween(tooltips.fadeMillis, easing = Easings.EaseOut))
            tooltips.showing = null
        } else {
            // The delay is for deciding to look, and a player with a tooltip already up has decided.
            if (tooltips.showing == null) clocks.wait(Clock.Ui, tooltips.delayMillis)
            tooltips.showing = wanted
            fade.animateTo(1f, Tween(tooltips.fadeMillis, easing = Easings.EaseOut))
        }
    }

    val showing = tooltips.showing
    val layout = remember(showing, showing?.text, resolved.textStyle, fonts) {
        showing?.let { fonts.measure(it.text, resolved.textStyle) }
    }

    val painter = remember(showing, layout, resolved, fade.value) {
        if (showing == null || layout == null || fade.value <= 0f) {
            null
        } else {
            TooltipPainter(showing, layout, resolved, fade.value)
        }
    }

    LeafLayout(Modifier.fillMaxSize(), name = "tooltip", draw = painter?.draw)
}

/** How far a tooltip sits from the thing it belongs to. */
private const val Gap = 8f

/** The box and its text, placed so that all of it is on screen. */
private class TooltipPainter(
    private val anchor: TooltipAnchor,
    private val layout: TextLayout,
    private val style: ResolvedStyle,
    private val fade: Float,
) {

    val draw: UiCanvas.(Rect) -> Unit = { screen ->
        val padding = style.padding
        val width = layout.size.width + padding.horizontal
        val height = layout.size.height + padding.vertical

        val from = if (anchor.follow) {
            Rect(anchor.pointer.x, anchor.pointer.y, anchor.pointer.x, anchor.pointer.y)
        } else {
            anchor.bounds
        }

        // Below by choice, above when below would not fit. A tooltip that is half off the bottom
        // of the screen is the one thing a player cannot scroll to.
        val below = from.bottom + Gap
        val top = if (below + height <= screen.bottom) below else (from.top - Gap - height)

        // Sideways it slides rather than flips: there is nothing on the other side of a screen
        // edge to flip to, and a tooltip that has moved along is still under the thing it is about.
        val wanted = (from.left + from.right) / 2f - width / 2f
        val left = wanted.coerceIn(screen.left, (screen.right - width).coerceAtLeast(screen.left))

        val box = Rect(left, top.coerceAtLeast(screen.top), left + width, top.coerceAtLeast(screen.top) + height)

        // The skin's own drawable rather than a rectangle, so a tooltip cut out of art works and
        // the border in the default skin is drawn. The fade is an alpha over both, which is one
        // comparison rather than a colour worked out twice.
        pushAlpha(fade)
        style.background.drawInto(this, box, Colour.White)
        text(layout, box.left + padding.left, box.top + padding.top, style.textColour)
        popAlpha()
    }
}
