package composegl.ui.modifier

import composegl.ui.graphics.Colour
import composegl.ui.graphics.NinePatch
import composegl.ui.graphics.UiCanvas
import composegl.ui.focus.FocusRequester
import composegl.ui.geometry.Rect
import composegl.ui.input.InteractionState
import composegl.ui.input.PointerHandler
import composegl.ui.layout.Alignment
import composegl.ui.layout.Padding

// --- what a node is ------------------------------------------------------------------------

/** An exact size. Null on an axis means "whatever the content needs". */
data class SizeElement(val width: Float? = null, val height: Float? = null) : Modifier.Element {
    init {
        require(width == null || width >= 0f) { "width cannot be negative, was $width" }
        require(height == null || height >= 0f) { "height cannot be negative, was $height" }
    }
}

/** A share of what the parent offered, from 0 to 1. Null on an axis means "leave it alone". */
data class FillElement(val widthFraction: Float? = null, val heightFraction: Float? = null) : Modifier.Element

data class PaddingElement(val padding: Padding) : Modifier.Element

/** Moved from where layout put it, without changing the space it takes up. */
data class OffsetElement(val x: Float = 0f, val y: Float = 0f) : Modifier.Element

/** A share of the space a row or column has left over after its fixed children. */
data class WeightElement(val weight: Float) : Modifier.Element {
    init { require(weight > 0f) { "weight must be positive, was $weight" } }
}

/** Where this child sits inside the space its parent gave it. */
data class AlignElement(val alignment: Alignment) : Modifier.Element

// --- how a node looks ----------------------------------------------------------------------

data class BackgroundElement(val colour: Colour, val corner: Float = 0f) : Modifier.Element

data class BorderElement(val colour: Colour, val width: Float, val corner: Float = 0f) : Modifier.Element {
    init { require(width >= 0f) { "border width cannot be negative, was $width" } }
}

data class ShadowElement(val colour: Colour, val spread: Float, val corner: Float = 0f) : Modifier.Element

/** Art behind the node, cut into nine so it can be any size. See [composegl.ui.graphics.NinePatch]. */
data class NinePatchElement(val patch: NinePatch, val tint: Colour = Colour.White) : Modifier.Element

/** Nothing outside this node is drawn by it or by its children. */
data class ClipElement(val corner: Float = 0f) : Modifier.Element

data class AlphaElement(val alpha: Float) : Modifier.Element

/**
 * Draw whatever you like, underneath this node's own drawing.
 *
 * Along with [DrawInFrontElement], the escape hatch that stops the widget set becoming a ceiling.
 * Note that an inline lambda is a new object each recomposition and so never compares equal —
 * `remember` it when a node would otherwise be unchanged.
 */
data class DrawBehindElement(val draw: UiCanvas.(Rect) -> Unit) : Modifier.Element

/** Draw whatever you like, on top of this node and its children. */
data class DrawInFrontElement(val draw: UiCanvas.(Rect) -> Unit) : Modifier.Element

// --- what a node does about the player ------------------------------------------------------

/**
 * Makes the node hit-testable and keeps [state] up to date with what the pointer is doing to it.
 *
 * On its own it watches and consumes nothing: an event still reaches whatever is underneath. Put
 * it on a panel to highlight the panel while the pointer is anywhere inside it.
 */
data class InteractionElement(val state: InteractionState) : Modifier.Element

/**
 * The node can be clicked, and eats the presses that land on it.
 *
 * A click is a press and a release on the same node, with the release inside it. A drag that
 * wanders off and lets go somewhere else is not a click, and neither is a gesture the platform
 * cancelled — which is the whole reason this is a modifier the toolkit understands rather than two
 * lines in a handler.
 *
 * [onClick] written inline is a new object every recomposition and so never compares equal.
 * `remember` it when a node would otherwise be unchanged.
 */
data class ClickableElement(val enabled: Boolean, val onClick: () -> Unit) : Modifier.Element

/** Raw pointer events for this node, in its own coordinates. See [PointerHandler]. */
data class PointerInputElement(val handler: PointerHandler) : Modifier.Element

/**
 * The node can hold focus, so keys and pad presses can reach it.
 *
 * [initial] is how a screen says where focus should start. Exactly one node per screen should
 * claim it; the first in the tree wins if two do.
 */
data class FocusableElement(
    val enabled: Boolean,
    val state: InteractionState?,
    val initial: Boolean,
) : Modifier.Element

/** A handle on this node, so focus can be sent here by name rather than found by geometry. */
data class FocusRequesterElement(val requester: FocusRequester) : Modifier.Element

/**
 * Where a direction goes from this node, when the geometry would get it wrong.
 *
 * Null on a direction means "work it out", which is the right answer almost everywhere. The places
 * it is not are the ones every console interface has: the end of a wrapped row, either side of a
 * gap, the corner of an L.
 */
data class FocusOrderElement(
    val up: FocusRequester? = null,
    val down: FocusRequester? = null,
    val left: FocusRequester? = null,
    val right: FocusRequester? = null,
    val next: FocusRequester? = null,
    val previous: FocusRequester? = null,
) : Modifier.Element

// --- the sentences --------------------------------------------------------------------------

fun Modifier.size(width: Float, height: Float) = then(SizeElement(width, height))

fun Modifier.size(side: Float) = then(SizeElement(side, side))

fun Modifier.width(width: Float) = then(SizeElement(width = width))

fun Modifier.height(height: Float) = then(SizeElement(height = height))

fun Modifier.fillMaxWidth(fraction: Float = 1f) = then(FillElement(widthFraction = fraction))

fun Modifier.fillMaxHeight(fraction: Float = 1f) = then(FillElement(heightFraction = fraction))

fun Modifier.fillMaxSize(fraction: Float = 1f) = then(FillElement(fraction, fraction))

fun Modifier.padding(all: Float) = then(PaddingElement(Padding.all(all)))

fun Modifier.padding(horizontal: Float = 0f, vertical: Float = 0f) =
    then(PaddingElement(Padding.symmetric(horizontal, vertical)))

fun Modifier.padding(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f) =
    then(PaddingElement(Padding(left, top, right, bottom)))

fun Modifier.offset(x: Float = 0f, y: Float = 0f) = then(OffsetElement(x, y))

fun Modifier.weight(weight: Float) = then(WeightElement(weight))

fun Modifier.align(alignment: Alignment) = then(AlignElement(alignment))

fun Modifier.background(colour: Colour, corner: Float = 0f) = then(BackgroundElement(colour, corner))

fun Modifier.border(colour: Colour, width: Float = 1f, corner: Float = 0f) =
    then(BorderElement(colour, width, corner))

fun Modifier.shadow(colour: Colour, spread: Float, corner: Float = 0f) =
    then(ShadowElement(colour, spread, corner))

/**
 * Draws [patch] behind this node, sized to it.
 *
 * By default the art's own content insets become the node's padding, so the gap between a panel's
 * frame and its contents is a property of the skin rather than a number copied into the layout.
 * Pass `applyPadding = false` when you want to place things over the art instead of inside it.
 */
fun Modifier.ninePatch(
    patch: NinePatch,
    tint: Colour = Colour.White,
    applyPadding: Boolean = true,
): Modifier {
    val painted = then(NinePatchElement(patch, tint))
    // After the paint, not before: the art is drawn across the whole node, and the padding it asks
    // for applies to what comes next — which is exactly what `background(c).padding(n)` means.
    return if (applyPadding) painted.then(PaddingElement(patch.padding)) else painted
}

fun Modifier.clip(corner: Float = 0f) = then(ClipElement(corner))

fun Modifier.alpha(alpha: Float) = then(AlphaElement(alpha))

fun Modifier.drawBehind(draw: UiCanvas.(Rect) -> Unit) = then(DrawBehindElement(draw))

fun Modifier.drawInFront(draw: UiCanvas.(Rect) -> Unit) = then(DrawInFrontElement(draw))

fun Modifier.interaction(state: InteractionState) = then(InteractionElement(state))

/**
 * Calls [onClick] when this node is clicked, and reports the press through any
 * [interaction] state on the same node.
 *
 * Disabled is not the same as absent: a disabled node still swallows the press, so a click cannot
 * fall through to whatever is behind a greyed-out button.
 */
fun Modifier.clickable(enabled: Boolean = true, onClick: () -> Unit) =
    then(ClickableElement(enabled, onClick))

fun Modifier.onPointer(handler: PointerHandler) = then(PointerInputElement(handler))

/**
 * Lets this node hold focus.
 *
 * @param state the same [InteractionState] the node's hover and press go through, so a widget
 *   reads one object and draws its focus ring from `isFocused`.
 * @param initial true on the one node a screen should open with focus on.
 */
fun Modifier.focusable(
    state: InteractionState? = null,
    enabled: Boolean = true,
    initial: Boolean = false,
) = then(FocusableElement(enabled, state, initial))

fun Modifier.focusRequester(requester: FocusRequester) = then(FocusRequesterElement(requester))

fun Modifier.focusOrder(
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    next: FocusRequester? = null,
    previous: FocusRequester? = null,
) = then(FocusOrderElement(up, down, left, right, next, previous))
