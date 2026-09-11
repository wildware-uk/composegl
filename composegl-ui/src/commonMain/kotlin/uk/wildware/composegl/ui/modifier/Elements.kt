package uk.wildware.composegl.ui.modifier

import uk.wildware.composegl.ui.focus.FocusRequester
import uk.wildware.composegl.ui.focus.FocusWithinHandler
import uk.wildware.composegl.ui.focus.RevealHandler
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.effect.ShaderEffect
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.NinePatch
import uk.wildware.composegl.ui.graphics.UiCanvas
import uk.wildware.composegl.ui.input.DirectionHandler
import uk.wildware.composegl.ui.input.InteractionState
import uk.wildware.composegl.ui.input.KeyHandler
import uk.wildware.composegl.ui.input.PointerHandler
import uk.wildware.composegl.ui.input.TextHandler
import uk.wildware.composegl.ui.layout.Alignment
import uk.wildware.composegl.ui.layout.Padding
import uk.wildware.composegl.ui.skin.ResolvedStyle
import uk.wildware.composegl.ui.skin.SkinDrawable

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

/** Art behind the node, cut into nine so it can be any size. See [uk.wildware.composegl.ui.graphics.NinePatch]. */
data class NinePatchElement(val patch: NinePatch, val tint: Colour = Colour.White) : Modifier.Element

/** Nothing outside this node is drawn by it or by its children. */
data class ClipElement(val corner: Float = 0f) : Modifier.Element

data class AlphaElement(val alpha: Float) : Modifier.Element

/** @see uk.wildware.composegl.ui.modifier.effect */
data class EffectElement(val effect: ShaderEffect) : Modifier.Element

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

/** A skin's background for this node, drawn whatever kind of drawable it turned out to be. */
data class SkinBackgroundElement(val drawable: SkinDrawable, val tint: Colour) : Modifier.Element

/** Keys for this node while it has focus, and for its children. See [KeyHandler]. */
data class KeyInputElement(val handler: KeyHandler) : Modifier.Element

/** Committed text for this node while it has focus. See [TextHandler]. */
data class TextInputElement(val handler: TextHandler) : Modifier.Element

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

/** Directions this node uses itself rather than letting focus move off it. See [DirectionHandler]. */
data class FocusDirectionElement(val handler: DirectionHandler) : Modifier.Element

/** A handle on this node, so focus can be sent here by name rather than found by geometry. */
/** Asked to bring a descendant into view when focus lands on it. */
data class RevealElement(val handler: RevealHandler) : Modifier.Element

/** Told when focus arrives anywhere inside this node, or leaves it. */
data class FocusWithinElement(val handler: FocusWithinHandler) : Modifier.Element

/** Focus cannot leave this node's subtree while it is in the tree. */
data class FocusTrapElement(val enabled: Boolean) : Modifier.Element

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

/** The same, where the four numbers already exist as one — a nine-patch's, or a skin's. */
fun Modifier.padding(padding: Padding) = then(PaddingElement(padding))

fun Modifier.padding(horizontal: Float = 0f, vertical: Float = 0f) =
    then(PaddingElement(Padding.symmetric(horizontal, vertical)))

fun Modifier.padding(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f) =
    then(PaddingElement(Padding(left, top, right, bottom)))

fun Modifier.offset(x: Float = 0f, y: Float = 0f) = then(OffsetElement(x, y))

/**
 * Draws this node, and everything under it, through a shader.
 *
 * The subtree is drawn into an offscreen picture and the shader decides what that picture comes
 * out as: blurred, outlined, dissolving, whatever the GLSL says. Written in the chain twice, the
 * effects apply in the order they are written — the second one works on the first one's answer.
 *
 * ```kotlin
 * Panel(Modifier.effect(blur(radius = 8f))) { … }
 * ```
 *
 * Costs a picture the size of the node — plus its [ShaderEffect.bleed] — and a draw call, every
 * frame it is on screen. A backend with no offscreen drawing, or no shaders, draws the subtree
 * plainly instead: an effect degrades to no effect rather than to a broken frame.
 */
fun Modifier.effect(effect: ShaderEffect) = then(EffectElement(effect))

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
 * Keys for this node, while focus is on it or on something inside it.
 *
 * A key starts at the focused node and walks outwards until something says it used it, so a
 * dialogue three deep can put Escape on itself and close the innermost one — the one focus is in —
 * without knowing anything about the two outside it.
 *
 * The node does not have to be focusable itself. A panel that handles Escape for whatever is
 * inside it is the common case, and it never takes focus of its own.
 */
fun Modifier.onKeyEvent(handler: KeyHandler) = then(KeyInputElement(handler))

/**
 * Committed text for this node, while it has focus.
 *
 * Only the focused node is offered text, and it is never passed outwards: a character belongs to
 * the thing being typed into, and a parent quietly collecting the leftovers would be a parent
 * quietly collecting the player's password.
 */
fun Modifier.onTextEvent(handler: TextHandler) = then(TextInputElement(handler))

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

/**
 * Lets this node use a direction instead of losing focus to it.
 *
 * Left on a slider is a smaller number; Down in a list is the next row. The handler is asked
 * before focus goes looking for a neighbour, and returning true keeps focus here. Returning false
 * at the end of a range is what lets a player leave the control — a slider that is already at its
 * maximum should not swallow another press to the right.
 *
 * Both the arrow keys and the pad arrive here, because both ask focus to move.
 */
fun Modifier.onFocusDirection(handler: DirectionHandler) = then(FocusDirectionElement(handler))

/**
 * Called when focus lands on something inside this node, with that thing's bounds.
 *
 * What a scrolling area uses to scroll a focused child into view. Anything that can move its
 * contents can answer it: the toolkit asks, and what "into view" means is the node's own business.
 */
fun Modifier.onReveal(handler: RevealHandler) = then(RevealElement(handler))

/**
 * Told when focus arrives anywhere inside this node, and when it leaves.
 *
 * Not the same as the node itself being focused: this is what something wrapped *round* a control
 * asks — a tooltip over a button, a panel that lights up while the player is in it. It is the only
 * way a pad ever triggers those, because nothing is ever hovered on a pad.
 */
fun Modifier.onFocusWithin(handler: FocusWithinHandler) = then(FocusWithinElement(handler))

/**
 * Focus stays inside this node.
 *
 * What a dialogue is, as far as the pad is concerned: pressing down at the bottom of it must not
 * walk out into the screen behind. The innermost trap in the tree wins, so a dialogue over a
 * dialogue behaves the way a player expects, and when a trap goes away focus returns to whatever
 * had it before — the button that opened the dialogue, rather than the top of the screen.
 */
fun Modifier.focusTrap(enabled: Boolean = true) = then(FocusTrapElement(enabled))

fun Modifier.focusRequester(requester: FocusRequester) = then(FocusRequesterElement(requester))

fun Modifier.focusOrder(
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    next: FocusRequester? = null,
    previous: FocusRequester? = null,
) = then(FocusOrderElement(up, down, left, right, next, previous))

/**
 * Wears a resolved style: its background, its tint, its padding and its content offset.
 *
 * The whole of "a widget is drawn from a style". A widget that writes this and then draws its text
 * in `style.textColour` contains no colour, no corner radius and no texture name of its own, which
 * is what lets one skin file change the look of a game.
 *
 * Order is the reason this is one call rather than three. The background paints across the node,
 * the padding keeps the contents off its edge, and the offset moves those contents without moving
 * the background — a pressed button whose label shifts down a pixel while its frame stays put.
 */
fun Modifier.styled(style: ResolvedStyle): Modifier = this
    .then(SkinBackgroundElement(style.background, style.tint))
    .padding(style.padding + style.contentOffset.asShift())

/**
 * A shift written as padding: more on one side, less on the other.
 *
 * Not [offset], which moves the whole node, frame and all. This adds to one edge and takes the
 * same amount off the opposite one, so the contents move and the size does not change.
 */
private fun Offset.asShift() = Padding(left = x, top = y, right = -x, bottom = -y)
