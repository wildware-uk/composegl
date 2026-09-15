package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.focus.FocusRequester
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.focus.RevealHandler
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.TextHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.graphics.BlendMode

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

/** Art behind the node, cut into nine so it can be any size. See [dev.wildware.composegl.ui.graphics.NinePatch]. */
data class NinePatchElement(val patch: NinePatch, val tint: Colour = Colour.White) : Modifier.Element

/** Nothing outside this node is drawn by it or by its children. */
data class ClipElement(val corner: Float = 0f) : Modifier.Element

/** @see dev.wildware.composegl.ui.modifier.hitShape */
data class HitShapeElement(val contains: (Offset) -> Boolean) : Modifier.Element

data class AlphaElement(val alpha: Float) : Modifier.Element

/** @see dev.wildware.composegl.ui.modifier.blend */
data class BlendElement(val mode: BlendMode) : Modifier.Element

/** @see dev.wildware.composegl.ui.modifier.scale */
data class ScaleElement(
    val factor: Float,
    val origin: Alignment = Alignment.Centre,
) : Modifier.Element {
    init {
        // Zero is allowed and draws nothing, because a panel springing in from nothing is the
        // commonest way this gets used. Negative would be a mirror, which nothing here can do.
        require(!factor.isNaN()) { "a scale cannot be NaN" }
        require(factor >= 0f) { "a scale cannot be negative, was $factor" }
    }
}

/** @see dev.wildware.composegl.ui.modifier.rotate */
data class RotateElement(
    val degrees: Float,
    val origin: Alignment = Alignment.Centre,
) : Modifier.Element {
    init {
        // Any angle at all is meaningful, including a negative one and one past a full turn, so
        // the only things refused here are the two that are not angles.
        require(!degrees.isNaN()) { "a rotation cannot be NaN" }
        require(!degrees.isInfinite()) { "a rotation cannot be infinite, was $degrees" }
    }
}

/** @see dev.wildware.composegl.ui.modifier.effect */
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

/**
 * Which points inside this node's rectangle actually belong to it.
 *
 * Hit testing is rectangles, because nearly everything is a rectangle and rectangles are cheap. A
 * few things are not: a round button, a diamond, a cell in a honeycomb. Their rectangles overlap
 * their neighbours', so the empty corner of one sits over the middle of another and the pointer
 * hands the click to whichever happens to be drawn later. This is how a node says those corners
 * are not its own.
 *
 * [contains] is asked in the node's own coordinates — the ones [onPointer] delivers, and the ones
 * the node was laid out in. A [scale] here or anywhere above is already divided back out, so the
 * shape is written once against the node's own width and height and keeps working while the node
 * grows. The rectangle is still tested first and the shape only ever narrows it, so this costs
 * nothing on the nodes without one and a single call on the nodes with one.
 *
 * Saying no lets the event carry on to whatever is underneath, which is the whole point: in a
 * honeycomb the pointer falls through the corner it was handed to the cell that really owns it.
 *
 * It gates this node alone and says nothing about its children — unlike [clip] it does not change
 * what is drawn, so it must not change what is reachable. A child that wants the same shape asks
 * for it itself.
 *
 * Two of these on one node is a choice rather than a quantity, so the later one wins.
 *
 * [contains] written inline is a new object every recomposition and so never compares equal.
 * `remember` it when a node would otherwise be unchanged — a shape that closes over anything, a
 * radius or a grid pitch, is exactly that kind of lambda.
 */
fun Modifier.hitShape(contains: (Offset) -> Boolean) = then(HitShapeElement(contains))

fun Modifier.alpha(alpha: Float) = then(AlphaElement(alpha))

/**
 * Draws this node, and everything under it, with a different blend function.
 *
 * The default - [BlendMode.SourceOver] - paints: what is drawn covers what is behind it in
 * proportion to its opacity. [BlendMode.Additive] adds instead, so a thing looks like it is giving
 * off light rather than reflecting it, and overlapping glows get brighter instead of flatter.
 *
 * A colour cannot do this. Above one it clamps back to white when it is packed to eight bits a
 * channel, so the art comes out exactly as it started; the blend function is the only lever.
 *
 * Applies to the whole subtree, the way [alpha] does, because the thing that glows is usually a
 * picture with a caption or a badge on it rather than a lone sprite.
 *
 * A backend is allowed not to have one. [UiCanvas.supports] answers for a mode before it is used,
 * and a canvas that cannot blend draws the ordinary way rather than refusing - a flash that does
 * not brighten is a worse picture, not a broken one.
 */
fun Modifier.blend(mode: BlendMode) = then(BlendElement(mode))

/**
 * Draws this node, and everything under it, bigger or smaller.
 *
 * **An arrival-and-fit tool, not a camera.** The subtree is drawn into an offscreen picture at its
 * ordinary size and that picture is magnified, so scaling up past about 1.15 is visibly soft and
 * text is soft sooner. It is meant for a panel that springs in, and for the fit correction that
 * shrinks a panel which outgrew its height budget so it lands as one object rather than being
 * re-laid-out. A world that wants to be crisp at three times the size wants to be laid out three
 * times the size.
 *
 * ```kotlin
 * Panel(Modifier.scale(spring.value)) { … }               // arriving
 * Panel(Modifier.scale(min(1f, budget / measured))) { … } // fitting
 * ```
 *
 * Layout does not move. The node keeps the slot it measured into, so a panel scaling in does not
 * shove its siblings about and a fit correction does not re-flow the panel's contents. What does
 * move is [dev.wildware.composegl.ui.node.UiNode.boundsInRoot], so clicks and pad focus land on
 * what you can see rather than on where the node would have been.
 *
 * Three things worth knowing before you reach for it:
 *
 * - **A capture is a clip.** Anything a child draws outside this node's own rectangle — a glow, an
 *   overflowing label — is cut off at the edge the moment the factor is not one, whether or not
 *   there is a [clip] anywhere in the chain. Hit testing and focus agree with it: what the capture
 *   cut off is not clickable either. It also arrives and leaves *with* the factor, so a figure that
 *   pulses from one loses its glow on the first frame of each punch and has it back at rest.
 * - **A [clip] on the same node does not hold the picture in.** The capture is composited filling
 *   the scaled rectangle, so a scaled node's rectangle *is* the scaled one — `clip().scale(2f)` on
 *   a 200-pixel box draws 400 pixels, and `boundsInRoot` and hit testing say the same. A viewport
 *   that must not spill wants the [clip] on the parent and the `scale` on the child inside it.
 * - **There is a ceiling.** A canvas can refuse to make the picture — the two backends here refuse
 *   one bigger than 4096 screen pixels a side, which is their limit rather than a rule, and any
 *   canvas with no offscreen drawing refuses every one. Then the subtree is drawn plainly, at its
 *   ordinary size, and hit testing goes back to that size with it — present and honest rather than
 *   missing. Ask [dev.wildware.composegl.ui.graphics.UiCanvas.drawsLayers] first if a screen would
 *   rather pick a different animation. A subtree that must scale should be viewport-sized with its
 *   contents offset inside it, not laid out bigger than the screen.
 * - **Factors multiply, the last origin wins.** `scale(0.5f).scale(2f)` is one, which is what makes
 *   an arrival animation and a fit correction composable on the same node.
 *
 * A factor of one costs a comparison and takes no picture at all. Zero draws nothing, the same
 * early-out a fully transparent node gets, and nothing inside it can be clicked or focused either.
 *
 * Unlike [alpha], which quietly clamps, this throws on a factor it cannot draw. A negative one is a
 * mirror, and nothing here mirrors, so clamping it to zero would answer a question nobody asked
 * with an invisible widget. That matters for one animation in particular: an anticipate or back
 * easing dips below zero on the way in, so hand it over as `scale(t.coerceAtLeast(0f))` rather than
 * discovering it on the frame the curve undershoots.
 *
 * @param origin the point that stays where it is, as a place inside this node: [Alignment.Centre]
 *   grows it about its middle, [Alignment.TopStart] about its top-left corner.
 * @throws IllegalArgumentException if [factor] is negative or not a number. Checked here, where the
 *   bad value is nearest whatever produced it, rather than turning every rectangle under it to NaN.
 */
fun Modifier.scale(factor: Float, origin: Alignment = Alignment.Centre) =
    then(ScaleElement(factor, origin))

/**
 * Turns this node and everything under it, clockwise, by [degrees].
 *
 * Built the same way [scale] is: the subtree is drawn into an offscreen picture at the size and
 * angle it was laid out, and that picture is put down turned. So nothing inside knows it is
 * happening — no angle threaded through the walk, no text asked for a rotated font — and a card
 * leaning as it is dealt costs the same as one lying flat.
 *
 * ```kotlin
 * Card(Modifier.rotate(lean * 8f)) { … }   // a dealt card leaning as it flies
 * Needle(Modifier.rotate(heading, origin = Alignment.BottomCentre))
 * ```
 *
 * **Clicks do not follow it.** This is the one way it differs from [scale], and it is deliberate
 * rather than pending: hit testing, focus and
 * [dev.wildware.composegl.ui.node.UiNode.boundsInRoot] all work in rectangles, and a turned
 * rectangle is not one. A turned node is still hit inside its upright box — which is right for the
 * cases this exists for, where a thing is turning precisely because it is in flight and not
 * asking to be clicked yet. If a player must click something at an angle, give it a
 * [hitShape], or turn the art inside a node that stays upright.
 *
 * Everything else is [scale]'s bargain, unchanged:
 *
 * - **A capture is a clip.** Anything a child draws outside this node's own rectangle is cut off
 *   the moment the angle is not zero, whether or not there is a [clip] in the chain. Corners are
 *   the thing to watch: the picture is the node's upright box, so a square turned by 45 degrees is
 *   composited as a turned square, but a glow that reached past the box was already gone.
 * - **There is a ceiling, and it degrades honestly.** A canvas with no offscreen drawing, or one
 *   refusing a picture this big, draws the subtree plainly and *upright* — present and the right
 *   size rather than missing. Ask [dev.wildware.composegl.ui.graphics.UiCanvas.drawsLayers] and
 *   [dev.wildware.composegl.ui.graphics.UiCanvas.turnsLayers] first if a screen would rather pick
 *   a different animation than show an unturned one.
 * - **Angles add, the last origin wins.** `rotate(10f).rotate(5f)` is fifteen, which is what makes
 *   a resting tilt and an animated one composable on the same node.
 *
 * An angle of zero costs a comparison and takes no picture at all, so a node that is only
 * sometimes turned pays nothing while it is straight.
 *
 * Layout does not move, exactly as it does not for a scale: the node keeps the slot it measured
 * into, so a card leaning does not shove its neighbours about.
 *
 * @param origin the point that stays where it is, as a place inside this node: [Alignment.Centre]
 *   turns it about its middle, [Alignment.BottomCentre] swings it like a pendulum.
 * @throws IllegalArgumentException if [degrees] is not a number, or is infinite. Checked here,
 *   where the bad value is nearest whatever produced it, rather than turning a quad to NaN.
 */
fun Modifier.rotate(degrees: Float, origin: Alignment = Alignment.Centre) =
    then(RotateElement(degrees, origin))

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

/** @see dev.wildware.composegl.ui.modifier.testTag */
data class TestTagElement(val tag: String) : Modifier.Element

/**
 * A name a test can find this node by, in UI that was composed rather than built by hand.
 *
 * ```
 * Button("PLAY", onClick = ::play, modifier = Modifier.testTag("play"))
 *
 * host.root.find("play").boundsInRoot
 * host.root.findAll("slot")
 * ```
 *
 * It lands on whichever node the widget hands its modifier to — for a button that is the button
 * itself, not the label inside it. It changes nothing about layout, drawing or input: a tagged
 * label is still scenery to the pointer. A data class holding a string, so a recomposition that
 * writes the same tag again compares equal and a still screen stays free. Two on one node are two
 * answers to the same question, so the later one wins.
 */
fun Modifier.testTag(tag: String) = then(TestTagElement(tag))

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
