package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.animation.AnimationSpec
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Spring
import dev.wildware.composegl.ui.focus.FocusRequester
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.focus.RevealHandler
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shape
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.ActivateHandler
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PointerWatcher
import dev.wildware.composegl.ui.input.TextHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Baseline
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.BorderSide
import dev.wildware.composegl.ui.graphics.BorderStyle

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

/** @see dev.wildware.composegl.ui.modifier.aspectRatio */
data class AspectRatioElement(
    val ratio: Float,
    val matchHeightConstraintsFirst: Boolean = false,
) : Modifier.Element {
    init {
        // Checked here, where the bad number is nearest whatever worked it out, rather than turning
        // a whole screen of rectangles to NaN on the frame a thumbnail's height comes back as zero.
        require(!ratio.isNaN()) { "an aspect ratio cannot be NaN" }
        require(ratio > 0f && ratio.isFinite()) { "an aspect ratio must be positive and finite, was $ratio" }
    }
}

/**
 * As big as the contents would like to be, asked before they are measured. Null on an axis means
 * "leave it alone". See [IntrinsicSize].
 */
data class IntrinsicSizeElement(
    val width: IntrinsicSize? = null,
    val height: IntrinsicSize? = null,
) : Modifier.Element

data class PaddingElement(val padding: Padding) : Modifier.Element

/** Padding at the start and end of a line rather than at the left and right; see [paddingRelative]. */
data class RelativePaddingElement(
    val start: Float = 0f,
    val top: Float = 0f,
    val end: Float = 0f,
    val bottom: Float = 0f,
) : Modifier.Element

/** Room measured from a line of text; see [paddingFrom]. */
data class BaselinePaddingElement(
    val baseline: Baseline,
    val before: Float = 0f,
    val after: Float = 0f,
) : Modifier.Element {
    init {
        require(before >= 0f) { "before cannot be negative, was $before" }
        require(after >= 0f) { "after cannot be negative, was $after" }
    }
}

/** Moved from where layout put it, without changing the space it takes up. */
data class OffsetElement(val x: Float = 0f, val y: Float = 0f) : Modifier.Element

/**
 * A range the node's size has to stay inside. Null on a bound means "whatever the parent allows".
 *
 * @see dev.wildware.composegl.ui.modifier.sizeIn
 */
data class SizeInElement(
    val minWidth: Float? = null,
    val maxWidth: Float? = null,
    val minHeight: Float? = null,
    val maxHeight: Float? = null,
) : Modifier.Element {
    init {
        requireMinimum("minWidth", minWidth)
        requireBound("maxWidth", maxWidth)
        requireMinimum("minHeight", minHeight)
        requireBound("maxHeight", maxHeight)
        require(minWidth == null || maxWidth == null || minWidth <= maxWidth) {
            "minWidth $minWidth is more than maxWidth $maxWidth"
        }
        require(minHeight == null || maxHeight == null || minHeight <= maxHeight) {
            "minHeight $minHeight is more than maxHeight $maxHeight"
        }
    }
}

/**
 * A smallest size that holds only while nothing else has said one.
 *
 * @see dev.wildware.composegl.ui.modifier.defaultMinSize
 */
data class DefaultMinSizeElement(val minWidth: Float? = null, val minHeight: Float? = null) : Modifier.Element {
    init {
        requireMinimum("minWidth", minWidth)
        requireMinimum("minHeight", minHeight)
    }
}

private fun requireBound(name: String, value: Float?) {
    if (value == null) return
    require(!value.isNaN()) { "$name cannot be NaN" }
    require(value >= 0f) { "$name cannot be negative, was $value" }
}

private fun requireMinimum(name: String, value: Float?) {
    requireBound(name, value)
    // Infinite would be a minimum nothing can meet, and under a parent that offers everything it
    // makes a node infinitely big. Unlike a maximum, it is never meaningful.
    require(value == null || value.isFinite()) { "$name cannot be infinite" }
}

/** A share of the space a row or column has left over after its fixed children. */
data class WeightElement(val weight: Float) : Modifier.Element {
    init { require(weight > 0f) { "weight must be positive, was $weight" } }
}

/** Where this child sits inside the space its parent gave it. */
data class AlignElement(val alignment: Alignment) : Modifier.Element

/** A name the parent's layout can find this child by, in place of where it comes in the list. */
data class LayoutIdElement(val layoutId: Any) : Modifier.Element

/**
 * Its own size, even when the parent insists on more, sitting where these say inside the slot.
 * Null on an axis means "leave it alone". See [dev.wildware.composegl.ui.modifier.wrapContentSize].
 */
data class WrapContentElement(
    val horizontal: HorizontalAlignment? = null,
    val vertical: VerticalAlignment? = null,
) : Modifier.Element

// --- how a node looks ----------------------------------------------------------------------

/**
 * A filled box behind the node, with a radius for each of its [corners].
 *
 * The four radii are the element's only corner field, so two backgrounds that differ in one corner
 * never compare equal. The constructor that takes a single `corner` is kept, and means the same
 * radius on all four.
 */
data class BackgroundElement(val colour: Colour, val corners: Corners = Corners.None) : Modifier.Element {
    constructor(colour: Colour, corner: Float) : this(colour, Corners.single(corner))

    /** Kept so code that read the one radius still compiles. It is the smallest of the four. */
    @Deprecated("A background has a radius per corner now.", ReplaceWith("corners"))
    val corner: Float get() = corners.smallest
}

/** @see dev.wildware.composegl.ui.modifier.background */
data class BrushBackgroundElement(val brush: Brush, val corners: Corners = Corners.None) : Modifier.Element {
    constructor(brush: Brush, corner: Float) : this(brush, Corners.single(corner))

    /** Kept so code that read the one radius still compiles. It is the smallest of the four. */
    @Deprecated("A gradient background has a radius per corner now.", ReplaceWith("corners"))
    val corner: Float get() = corners.smallest
}

data class BorderElement(
    val colour: Colour,
    val width: Float,
    val corners: Corners = Corners.None,
    val style: BorderStyle = BorderStyle.Solid,
) : Modifier.Element {
    constructor(colour: Colour, width: Float, corner: Float, style: BorderStyle = BorderStyle.Solid) :
        this(colour, width, Corners.single(corner), style)

    init { require(width >= 0f && width.isFinite()) { "border width cannot be negative, was $width" } }

    /** Kept so code that read the one radius still compiles. It is the smallest of the four. */
    @Deprecated("A border has a radius per corner now.", ReplaceWith("corners"))
    val corner: Float get() = corners.smallest
}

/** A border with each edge its own, or missing. @see dev.wildware.composegl.ui.modifier.border */
data class BorderSidesElement(
    val left: BorderSide? = null,
    val top: BorderSide? = null,
    val right: BorderSide? = null,
    val bottom: BorderSide? = null,
) : Modifier.Element

data class ShadowElement(
    val colour: Colour,
    val spread: Float,
    val corners: Corners = Corners.None,
) : Modifier.Element {
    constructor(colour: Colour, spread: Float, corner: Float) : this(colour, spread, Corners.single(corner))

    /** Kept so code that read the one radius still compiles. It is the smallest of the four. */
    @Deprecated("A shadow has a radius per corner now.", ReplaceWith("corners"))
    val corner: Float get() = corners.smallest
}

/** An outline hugging the outside of the node. @see dev.wildware.composegl.ui.modifier.borderOutside */
data class OutsideBorderElement(
    val colour: Colour,
    val width: Float,
    val corners: Corners = Corners.None,
) : Modifier.Element {
    constructor(colour: Colour, width: Float, corner: Float) : this(colour, width, Corners.single(corner))

    init { require(width >= 0f && width.isFinite()) { "border width cannot be negative, was $width" } }
}

/** Shade falling inwards from the node's edge. @see dev.wildware.composegl.ui.modifier.innerShade */
data class InnerShadeElement(
    val colour: Colour,
    val depth: Float,
    val corners: Corners = Corners.None,
    val offset: Offset = Offset.Zero,
) : Modifier.Element {
    constructor(colour: Colour, depth: Float, corner: Float, offset: Offset = Offset.Zero) :
        this(colour, depth, Corners.single(corner), offset)

    init { require(depth >= 0f && depth.isFinite()) { "shade depth cannot be negative, was $depth" } }
}

/**
 * A box lit from one direction: the lift, the shade, the shine and the shadow, worked out together.
 *
 * @see dev.wildware.composegl.ui.modifier.moulded
 */
data class MouldedElement(
    val corners: Corners = Corners.None,
    val light: Float = 90f,
    val depth: Float = 0.14f,
    val shine: Float = 0.45f,
    val strength: Float = 0.35f,
    val shadow: Float = 0f,
    val outline: Colour? = null,
    val outlineWidth: Float = 0.07f,
) : Modifier.Element {
    init {
        require(depth >= 0f && depth <= 1f) { "a moulded edge reaches between none and all of the box, not $depth" }
        require(shine >= 0f && shine <= 1f) { "a shine covers between none and all of the box, not $shine" }
        require(strength >= 0f && strength <= 1f) { "a light is between off and full, not $strength" }
        require(shadow >= 0f) { "a shadow cannot reach $shadow" }
        require(!light.isNaN() && !light.isInfinite()) { "the light comes from an angle, not $light" }
    }
}

/** A shine across the top of the node. @see dev.wildware.composegl.ui.modifier.gloss */
data class GlossElement(
    val colour: Colour,
    val fraction: Float,
    val corners: Corners = Corners.None,
    val inset: Float = 0f,
) : Modifier.Element {
    constructor(colour: Colour, fraction: Float, corner: Float, inset: Float = 0f) :
        this(colour, fraction, Corners.single(corner), inset)

    init {
        require(fraction > 0f && fraction <= 1f) { "a gloss covers between none and all of the box, not $fraction" }
        require(inset >= 0f && inset.isFinite()) { "a gloss cannot be inset by $inset" }
    }
}

/** Art behind the node, cut into nine so it can be any size. See [dev.wildware.composegl.ui.graphics.NinePatch]. */
data class NinePatchElement(val patch: NinePatch, val tint: Colour = Colour.White) : Modifier.Element

/**
 * Nothing outside this node's [shape] is drawn by it or by its children.
 *
 * @see dev.wildware.composegl.ui.modifier.clip
 * @see dev.wildware.composegl.ui.modifier.clipShape
 */
data class ClipElement(val shape: Shape = Shapes.Rectangle) : Modifier.Element {

    /** A rectangle, with its corners rounded when [corner] is more than nothing. */
    constructor(corner: Float) : this(Corners.single(corner))

    /** A rectangle rounded by [corners]; square corners all round is the plain rectangle. */
    constructor(corners: Corners) : this(if (corners == Corners.None) Shapes.Rectangle else Shapes.roundedRect(corners))

    /** The radii this clip rounds its rectangle by, or none when its shape is not a rounded rectangle. */
    val corners: Corners get() = (shape as? Shapes.RoundedRect)?.corners ?: Corners.None

    /** Kept so code that read the one radius still compiles. It is the smallest of the four. */
    @Deprecated("A clip has a radius per corner now.", ReplaceWith("corners"))
    val corner: Float get() = corners.smallest
}

/** @see dev.wildware.composegl.ui.modifier.hitShape */
data class HitShapeElement(val contains: (Offset) -> Boolean) : Modifier.Element

/** @see dev.wildware.composegl.ui.modifier.hitShape */
data class ShapedHitElement(val shape: Shape) : Modifier.Element

/** @see dev.wildware.composegl.ui.modifier.pointerHoverIcon */
data class PointerHoverIconElement(val icon: PointerIcon) : Modifier.Element

data class AlphaElement(val alpha: Float) : Modifier.Element

/** @see dev.wildware.composegl.ui.modifier.blend */
data class BlendElement(val mode: BlendMode) : Modifier.Element

/** @see dev.wildware.composegl.ui.modifier.zIndex */
data class ZIndexElement(val z: Float) : Modifier.Element {
    init {
        // A sort key that is not a number sorts nowhere in particular, and an infinite one hides
        // the mistake that produced it. Every finite value, negative included, means something.
        require(!z.isNaN()) { "a zIndex cannot be NaN" }
        require(!z.isInfinite()) { "a zIndex cannot be infinite, was $z" }
    }
}

/** @see dev.wildware.composegl.ui.modifier.tint */
data class TintElement(val colour: Colour) : Modifier.Element

/** @see dev.wildware.composegl.ui.modifier.scale */
data class ScaleElement(
    val factor: Float,
    val origin: Alignment = Alignment.Centre,
) : Modifier.Element {
    init {
        // Zero is allowed and draws nothing, because a panel springing in from nothing is the
        // commonest way this gets used. Negative would be a mirror, which is its own modifier so
        // that an easing dipping below zero is caught rather than flipping a panel for a frame.
        require(!factor.isNaN()) { "a scale cannot be NaN" }
        require(factor >= 0f) { "a scale cannot be negative, was $factor; flip with Modifier.mirror" }
    }
}

/** @see dev.wildware.composegl.ui.modifier.mirror */
data class MirrorElement(val horizontal: Boolean = true, val vertical: Boolean = false) : Modifier.Element

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

/** @see dev.wildware.composegl.ui.modifier.skew */
data class SkewElement(
    val x: Float = 0f,
    val y: Float = 0f,
    val origin: Alignment = Alignment.Centre,
) : Modifier.Element {
    init {
        // Unlike a turn, a slant has an end: at 90 degrees every point of the node is flung
        // infinitely far along the axis, so the answer is not a picture of anything.
        require(!x.isNaN() && !y.isNaN()) { "a skew cannot be NaN" }
        require(x > -90f && x < 90f) { "a horizontal skew must be between -90 and 90 degrees, was $x" }
        require(y > -90f && y < 90f) { "a vertical skew must be between -90 and 90 degrees, was $y" }
    }
}

/** @see dev.wildware.composegl.ui.modifier.rotate3d */
data class Rotate3dElement(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val cameraDistance: Float = DefaultCameraDistance,
    val origin: Alignment = Alignment.Centre,
) : Modifier.Element {
    init {
        // Any angle is an angle, as it is for a flat turn. A camera has to be somewhere in front.
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "a 3D rotation must be finite, was ($x, $y, $z)" }
        require(cameraDistance > 0f && cameraDistance.isFinite()) {
            "a camera distance must be positive and finite, was $cameraDistance"
        }
    }
}

/**
 * How far away the camera looking at a [rotate3d] sits unless told otherwise, in the unit
 * [CameraDistanceUnit] names: eight, which is Android's and Compose's default too.
 */
const val DefaultCameraDistance = 8f

/**
 * How many of the toolkit's pixels one unit of camera distance is: seventy-two, a typographic
 * inch, the unit Android measures its camera in. So the default camera is 576 pixels away.
 */
const val CameraDistanceUnit = 72f

/** @see dev.wildware.composegl.ui.modifier.perspective */
data class PerspectiveElement(
    val distance: Float,
    val origin: Alignment = Alignment.Centre,
) : Modifier.Element {
    init {
        require(distance > 0f && distance.isFinite()) {
            "a perspective distance must be positive and finite, was $distance"
        }
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

/** @see dev.wildware.composegl.ui.modifier.debugBounds */
data class DebugBoundsElement(val colour: Colour, val label: Boolean) : Modifier.Element

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
data class ClickableElement(
    val enabled: Boolean,
    val onClick: () -> Unit,
    /** Called instead of [onClick] for the second of two clicks close together. */
    val onDoubleClick: (() -> Unit)? = null,
    /** Called once when a press is held for [longPressMillis]. The release after it is not a click. */
    val onLongPress: (() -> Unit)? = null,
    /** How [onClick] repeats while the press is held, or null for a click that happens once. */
    val repeat: ClickRepeat? = null,
    /** Whose time every one of these timings is measured in. */
    val clock: Clock = Clock.Ui,
    val longPressMillis: Int = DefaultLongPressMillis,
    val doubleClickMillis: Int = DefaultDoubleClickMillis,
) : Modifier.Element {
    init {
        require(longPressMillis > 0) { "longPressMillis must be positive, was $longPressMillis" }
        require(doubleClickMillis > 0) { "doubleClickMillis must be positive, was $doubleClickMillis" }
    }

    /** Whether anything here has to watch a clock while the press is held. */
    internal val isTimed: Boolean get() = enabled && (onLongPress != null || repeat != null)
}

/**
 * How a held press repeats its click: once after [initialDelayMillis], then every [intervalMillis].
 *
 * The same shape as a held key, for the same reason — one step, a pause long enough to let go in,
 * then steps quick enough to cross a range.
 */
data class ClickRepeat(val initialDelayMillis: Int, val intervalMillis: Int) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive, was $initialDelayMillis" }
        require(intervalMillis > 0) { "intervalMillis must be positive, was $intervalMillis" }
    }
}

/** How long a press has to be held before it is a long press. */
const val DefaultLongPressMillis = 500

/** How close together, from the first click to the second press, two clicks must be to count as one double. */
const val DefaultDoubleClickMillis = 300

/** Raw pointer events for this node, in its own coordinates. See [PointerHandler]. */
data class PointerInputElement(val handler: PointerHandler) : Modifier.Element

/** Pointer events this node watches without ever taking them. See [watchPointer]. */
data class PointerWatchElement(val watcher: PointerWatcher) : Modifier.Element

/**
 * The node can be dragged. See [draggable].
 *
 * The gesture itself — where it started, whether it has passed the slop, where the pointer was
 * last — lives in the pointer router rather than in here. That is what lets [onDrag] move the very
 * state this node is composed from: the recomposition that follows builds a new element, and the
 * drag carries on through it because nothing about the drag was in the old one.
 */
data class DraggableElement(
    val enabled: Boolean,
    val slop: Float,
    val onDragStart: (Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit,
) : Modifier.Element {
    init {
        require(!slop.isNaN() && slop >= 0f) { "a drag slop cannot be negative, was $slop" }
    }
}

/** A skin's background for this node, drawn whatever kind of drawable it turned out to be. */
data class SkinBackgroundElement(val drawable: SkinDrawable, val tint: Colour) : Modifier.Element

/** Keys for this node while it has focus, and for its children. See [KeyHandler]. */
data class KeyInputElement(val handler: KeyHandler) : Modifier.Element

/** Committed text for this node while it has focus. See [TextHandler]. */
data class TextInputElement(val handler: TextHandler) : Modifier.Element

/** Pad events for this node while it has focus, before navigation. See [GamepadHandler]. */
data class GamepadInputElement(val handler: GamepadHandler) : Modifier.Element

/** Keys nothing around focus took, offered here wherever focus is. See [onShortcutKey]. */
data class ShortcutKeyElement(val handler: KeyHandler) : Modifier.Element

/** Pad events nothing around focus took, offered here wherever focus is. See [onShortcutGamepad]. */
data class ShortcutGamepadElement(val handler: GamepadHandler) : Modifier.Element

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

/**
 * The node takes focus when it is pressed, and at no other time. See [focusableByPointer].
 *
 * Its own element rather than a flag on [FocusableElement], because adding a field to a published
 * data class changes its constructor and every game compiled against the old one fails to link.
 */
data class PointerFocusElement(val state: InteractionState?) : Modifier.Element

/** South or Enter on this node while it has focus, before its click. See [ActivateHandler]. */
data class ActivateElement(val handler: ActivateHandler) : Modifier.Element

/** Directions this node uses itself rather than letting focus move off it. See [DirectionHandler]. */
data class FocusDirectionElement(val handler: DirectionHandler) : Modifier.Element

/** A handle on this node, so focus can be sent here by name rather than found by geometry. */
/** Asked to bring a descendant into view when focus lands on it. */
data class RevealElement(val handler: RevealHandler) : Modifier.Element

/** Told when focus arrives anywhere inside this node, or leaves it. */
data class FocusWithinElement(val handler: FocusWithinHandler) : Modifier.Element

/** Focus cannot leave this node's subtree while it is in the tree. */
data class FocusTrapElement(val enabled: Boolean) : Modifier.Element

/** Told after layout when this node's size changes. See [SizeChangedHandler]. */
data class OnSizeChangedElement(val handler: SizeChangedHandler) : Modifier.Element

/** Told after layout when where this node is drawn changes. See [PlacedHandler]. */
data class OnPlacedElement(val handler: PlacedHandler) : Modifier.Element

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

/**
 * At least [min] wide and at most [max], and otherwise as wide as the contents want.
 *
 * ```kotlin
 * Tooltip(Modifier.widthIn(max = 400f))       // grows with its text, then wraps
 * Panel(Modifier.widthIn(min = 200f, max = 400f))
 * ```
 *
 * Both ends stay inside what the parent allows, the same as [width] does: a parent that offers 300
 * gets 300 from `widthIn(min = 400f)`, never 400. Leave a bound out and the parent's own stands.
 *
 * A range is a rule about the node rather than a wish, so it holds whichever order the chain puts
 * it in: [width] and [fillMaxWidth] are both measured inside it. `widthIn(max = 400f).fillMaxWidth()`
 * and `fillMaxWidth().widthIn(max = 400f)` are the same panel — as wide as it can be, up to 400.
 *
 * Written twice, each bound is a choice and the later one wins, bound by bound. A later minimum
 * above an earlier maximum carries the maximum up with it rather than leaving a range nothing fits.
 *
 * @throws IllegalArgumentException if a bound is negative or NaN, [min] is infinite, or [min] is
 *   more than [max].
 */
fun Modifier.widthIn(min: Float? = null, max: Float? = null) =
    then(SizeInElement(minWidth = min, maxWidth = max))

/** At least [min] tall and at most [max]. Everything [widthIn] says, on the other axis. */
fun Modifier.heightIn(min: Float? = null, max: Float? = null) =
    then(SizeInElement(minHeight = min, maxHeight = max))

/** [widthIn] and [heightIn] in one call. Null leaves that bound to the parent. */
fun Modifier.sizeIn(
    minWidth: Float? = null,
    minHeight: Float? = null,
    maxWidth: Float? = null,
    maxHeight: Float? = null,
) = then(SizeInElement(minWidth, maxWidth, minHeight, maxHeight))

/**
 * A smallest size that only holds when nothing else has asked for one.
 *
 * What a widget puts on itself so it is never smaller than a thumb can hit, while leaving the
 * screen that uses it free to say otherwise:
 *
 * ```kotlin
 * Box(modifier.defaultMinSize(minWidth = 48f, minHeight = 48f)) { … }
 * ```
 *
 * It gives way, on its own axis, to anything more definite: a parent that already offers a
 * minimum, a [width] or a [fillMaxWidth], or a `widthIn(min = …)`. What it never gives way to is
 * nothing at all — so a button whose label is one letter is still 48 wide. It still stays inside
 * the parent's maximum and any [widthIn] maximum, like every other size here.
 *
 * @throws IllegalArgumentException if a minimum is negative, NaN or infinite.
 */
fun Modifier.defaultMinSize(minWidth: Float? = null, minHeight: Float? = null) =
    then(DefaultMinSizeElement(minWidth, minHeight))

/**
 * As wide as the contents would like to be, found out before they are measured.
 *
 * A menu whose buttons are all as wide as the longest label:
 *
 * ```kotlin
 * Column(Modifier.width(IntrinsicSize.Max)) {
 *     Button("PLAY", onClick = { }, modifier = Modifier.fillMaxWidth())
 *     Button("OPTIONS", onClick = { }, modifier = Modifier.fillMaxWidth())
 * }
 * ```
 *
 * The column asks each button how wide it would be if it could have any width, takes the widest,
 * and is exactly that wide — so `fillMaxWidth` inside it fills to the longest label rather than to
 * the screen. `IntrinsicSize.Min` asks for the narrowest instead, which for text is its longest
 * word. A `width` or a `fill` on the same axis wins.
 */
fun Modifier.width(intrinsicSize: IntrinsicSize) = then(IntrinsicSizeElement(width = intrinsicSize))

/**
 * As tall as the contents would like to be, found out before they are measured.
 *
 * A row whose divider is as tall as its tallest cell: `Row(Modifier.height(IntrinsicSize.Min))`
 * with the divider on `fillMaxHeight()`. See the `width` above.
 */
fun Modifier.height(intrinsicSize: IntrinsicSize) = then(IntrinsicSizeElement(height = intrinsicSize))

fun Modifier.fillMaxWidth(fraction: Float = 1f) = then(FillElement(widthFraction = fraction))

fun Modifier.fillMaxHeight(fraction: Float = 1f) = then(FillElement(heightFraction = fraction))

fun Modifier.fillMaxSize(fraction: Float = 1f) = then(FillElement(fraction, fraction))

/**
 * Keeps this node a fixed shape — [ratio] is width divided by height — inside whatever room it gets.
 *
 * ```kotlin
 * Image(portrait, Modifier.fillMaxWidth().aspectRatio(3f / 4f))   // as wide as the slot, 4 tall per 3 wide
 * MinimapFrame(Modifier.height(180f).aspectRatio(1f))              // square
 * ```
 *
 * One rule: take the axis that is settled, derive the other from it, then clamp to what the
 * parent allows. "Settled" is read after [size], [width], [height] and the `fillMax*` family, so
 * those decide the axis and this decides the other. With neither axis settled it picks the biggest
 * shape that fits the offer — the widest first, or the tallest first when
 * [matchHeightConstraintsFirst] says so.
 *
 * When no shape of this ratio fits at all — full width in a slot too short for it — the axis that
 * was asked for is kept and the derived one is cut down to the room there is. The node comes out
 * the wrong shape rather than spilling over its neighbours, which is the same bargain [size] makes
 * when it asks for more than the parent has.
 *
 * With nothing bounded either way, a scrolling list's cross axis inside another scrolling list,
 * there is no shape to derive and the content decides the size.
 *
 * Two of these on one node is a choice rather than a quantity, so the later one wins.
 *
 * @throws IllegalArgumentException if [ratio] is zero, negative, infinite or not a number.
 */
fun Modifier.aspectRatio(ratio: Float, matchHeightConstraintsFirst: Boolean = false) =
    then(AspectRatioElement(ratio, matchHeightConstraintsFirst))

fun Modifier.padding(all: Float) = then(PaddingElement(Padding.all(all)))

/** The same, where the four numbers already exist as one — a nine-patch's, or a skin's. */
fun Modifier.padding(padding: Padding) = then(PaddingElement(padding))

fun Modifier.padding(horizontal: Float = 0f, vertical: Float = 0f) =
    then(PaddingElement(Padding.symmetric(horizontal, vertical)))

fun Modifier.padding(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f) =
    then(PaddingElement(Padding(left, top, right, bottom)))

/**
 * Padding named by where a line starts and ends rather than by left and right.
 *
 * The same as `padding(left = start, right = end)` in a left-to-right screen, and the mirror of it
 * in a right-to-left one: an icon's gap before its label stays between the icon and the label
 * whichever side the icon is on. Adds to any other padding on the node.
 */
fun Modifier.paddingRelative(start: Float = 0f, top: Float = 0f, end: Float = 0f, bottom: Float = 0f) =
    then(RelativePaddingElement(start, top, end, bottom))

fun Modifier.offset(x: Float = 0f, y: Float = 0f) = then(OffsetElement(x, y))

/**
 * Room measured from a line of text rather than from the edge of the box.
 *
 * A designer specifies "24 from the top of the panel to the title's baseline", not "24 to the top of
 * the title's line box", and the two differ by the font's ascent — which changes with the size. This
 * says the first:
 *
 * ```kotlin
 * Text("Title", Modifier.paddingFrom(Baseline.First, before = 24f))
 * Text("Body", Modifier.paddingFrom(Baseline.Last, after = 16f))
 * ```
 *
 * @param before how far down from the top of this node the line should stand. Room is added above
 *   until it does; a line already further down is left alone.
 * @param after how far up from the bottom of this node the line should stand, the same way.
 *
 * The room is part of the node, the way `background(c).padding(8f)` is: a background paints across
 * it and a click on it lands on the node. A node with no text inside it has no line to measure from,
 * and gets no room at all. Two of these on one node — one from each line — both apply.
 */
fun Modifier.paddingFrom(baseline: Baseline, before: Float = 0f, after: Float = 0f) =
    then(BaselinePaddingElement(baseline, before, after))

/**
 * The common case of [paddingFrom]: [top] down to the first line, and [bottom] up to the last.
 *
 * ```kotlin
 * Text(body, Modifier.paddingFromBaseline(top = 28f, bottom = 12f))
 * ```
 */
fun Modifier.paddingFromBaseline(top: Float = 0f, bottom: Float = 0f) =
    paddingFrom(Baseline.First, before = top).paddingFrom(Baseline.Last, after = bottom)

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

/**
 * Names this child for the layout it sits in, so a layout with slots can ask for "the icon" rather
 * than "the first child".
 *
 * ```kotlin
 * Icon(Modifier.layoutId("icon"))
 * // in the policy:
 * val icon = measurables.first { it.layoutId == "icon" }
 * ```
 *
 * Child order stops meaning anything the moment a child is only there sometimes: a badge that
 * appears in front of the icon makes the icon second. A name does not move. Anything with a sensible
 * `equals` will do — a string, an enum, an object. Named twice, the later name is the one.
 *
 * It does nothing on its own. `Row`, `Column` and `Box` ignore it; only a layout that reads
 * [dev.wildware.composegl.ui.layout.layoutId] acts on it.
 */
fun Modifier.layoutId(layoutId: Any) = then(LayoutIdElement(layoutId))

/**
 * Stays its own size when the parent hands it a bigger slot, and sits at [alignment] inside it.
 *
 * A weighted slot in a row, a fixed cell in a grid, a `fillMaxSize` box: all of them force a
 * minimum on a child, and a small icon or badge given one is stretched to fit. This takes the
 * minimum away, so the node measures at the size its content and its own `size` ask for, and then
 * puts that box inside the slot rather than across it.
 *
 * ```kotlin
 * Row(Modifier.width(300f)) {
 *     Icon(Modifier.weight(1f).wrapContentSize(Alignment.Centre))   // a third of the row, icon centred
 * }
 * ```
 *
 * The parent still sees the whole slot, so a row's arithmetic does not change. What moves is the
 * node: its rectangle, what it paints and where it takes clicks are the small box, and the empty
 * part of the slot belongs to whatever is underneath. A `background` on the same node therefore
 * paints the icon, not the cell; to paint the cell, put the background on a parent.
 *
 * It acts on what the parent offered, before `size` and `fillMax*` read it — wherever it sits in
 * the chain — so `weight(1f).wrapContentSize().size(24f)` is a 24-pixel box centred in its share.
 * A maximum is still a maximum: something too big for the slot is cut down to it, not let out.
 * An `offset` moves the box from where it was aligned, as it would anywhere else.
 *
 * It only moves a node along an axis the parent actually forces. A row forces a weighted child's
 * width but not its height, so up and down stay the row's `verticalAlignment` to decide; a `Box`
 * forces neither, and has `align` for that. On an axis nothing forces, this changes nothing.
 *
 * Two on one node are two answers to the same question, so the later one wins on each axis it names.
 */
fun Modifier.wrapContentSize(alignment: Alignment = Alignment.Centre) =
    then(WrapContentElement(alignment.horizontal, alignment.vertical))

/** [wrapContentSize] across only: its own width, the height it was given. */
fun Modifier.wrapContentWidth(alignment: HorizontalAlignment = HorizontalAlignment.Centre) =
    then(WrapContentElement(horizontal = alignment))

/** [wrapContentSize] down only: its own height, the width it was given. */
fun Modifier.wrapContentHeight(alignment: VerticalAlignment = VerticalAlignment.Centre) =
    then(WrapContentElement(vertical = alignment))

fun Modifier.background(colour: Colour, corner: Float = 0f) = then(BackgroundElement(colour, corner))

/**
 * A filled box with its own radius on each corner.
 *
 * ```kotlin
 * Modifier.background(colour, Corners(topLeft = 8f, topRight = 8f))   // a tab
 * ```
 *
 * On a canvas that cannot round corners separately every corner is drawn at the smallest of the
 * four; see [UiCanvas.roundsCornersSeparately].
 */
fun Modifier.background(colour: Colour, corners: Corners) = then(BackgroundElement(colour, corners))

/**
 * A gradient behind this node, running edge to edge across it.
 *
 * ```kotlin
 * Modifier.background(Brush.vertical(sky, horizon), corner = 6f)
 * Modifier.background(Brush.radial(Colour.Transparent, Colour.argb(0xC0000000)))  // a vignette
 * ```
 *
 * Measured against the node, so it stretches with it: a bar that shrinks as health drops squeezes
 * its whole gradient into what is left. Paint it on a full-width node and [clip] a child to the
 * health instead when the colour should say where on the bar it is.
 *
 * A backend without gradients draws the brush's first colour flat; see
 * [UiCanvas.drawsGradients]. A brush is a data class, so the same one written again next
 * recomposition compares equal and costs nothing.
 */
fun Modifier.background(brush: Brush, corner: Float = 0f) = then(BrushBackgroundElement(brush, corner))

/** A gradient with its own radius on each corner: `background(Brush.vertical(a, b), Corners.top(8f))`. */
fun Modifier.background(brush: Brush, corners: Corners) = then(BrushBackgroundElement(brush, corners))

/**
 * An outline drawn inside this node, the same on all four sides.
 *
 * ```kotlin
 * Modifier.border(colour, width = 2f, corner = 6f)
 * Modifier.border(colour, width = 2f, style = BorderStyle.Dashed(on = 6f, off = 4f))
 * Modifier.border(colour, width = 2f, style = BorderStyle.Dotted)
 * ```
 */
fun Modifier.border(
    colour: Colour,
    width: Float = 1f,
    corner: Float = 0f,
    style: BorderStyle = BorderStyle.Solid,
) = then(BorderElement(colour, width, corner, style))

/**
 * An outline with each edge given separately. A side left out is not drawn.
 *
 * ```kotlin
 * Modifier.border(bottom = BorderSide(1f, divider))                  // a divider under a header
 * Modifier.border(bottom = BorderSide(3f, accent))                   // a tab's underline
 * Modifier.border(left = BorderSide(2f, accent, BorderStyle.Dotted))
 * ```
 *
 * Square-cornered: the edges meet in square corners, top and bottom running the full width.
 * A rounded outline is the all-sides [border], where one line can follow the curve.
 */
fun Modifier.border(
    left: BorderSide? = null,
    top: BorderSide? = null,
    right: BorderSide? = null,
    bottom: BorderSide? = null,
) = then(BorderSidesElement(left, top, right, bottom))

/**
 * An outline with its own radius on each corner. Give it the same [Corners] as the background.
 * Dashed and dotted follow the four curves the same way they follow one.
 */
fun Modifier.border(colour: Colour, width: Float = 1f, corners: Corners, style: BorderStyle = BorderStyle.Solid) =
    then(BorderElement(colour, width, corners, style))

fun Modifier.shadow(colour: Colour, spread: Float, corner: Float = 0f) =
    then(ShadowElement(colour, spread, corner))

/**
 * An outline hugging the outside of this node, so it takes nothing off the fill.
 *
 * The heavy dark line round a game button. A [border] of the same width is drawn inside the box and
 * eats into the colour it frames; this one sits outside it, and the node keeps every pixel it was
 * given. It draws outside the node's bounds, so leave room for it — a click still only counts
 * inside the node, as it does with a shadow.
 *
 * ```kotlin
 * Modifier.background(green, corner = 12f).borderOutside(almostBlack, width = 3f, corner = 12f)
 * ```
 */
fun Modifier.borderOutside(colour: Colour, width: Float = 1f, corner: Float = 0f) =
    then(OutsideBorderElement(colour, width, corner))

/** The same, with its own radius on each corner. Give it the same [Corners] as the background. */
fun Modifier.borderOutside(colour: Colour, width: Float = 1f, corners: Corners) =
    then(OutsideBorderElement(colour, width, corners))

/**
 * Shade falling inwards from this node's edge, [depth] deep, gathered towards [offset].
 *
 * What makes a flat box look moulded. The offset says which way the light comes from: an offset
 * downwards gathers the shade along the top inside edge, as though lit from below. With no offset
 * it rings the whole edge, which is the look of a socket.
 *
 * Put it after the background, because that is the order things are painted in.
 *
 * ```kotlin
 * Modifier.background(green, corner = 12f)
 *     .innerShade(black.scaleAlpha(0.35f), depth = 6f, corner = 12f, offset = Offset(0f, -4f))
 * ```
 */
fun Modifier.innerShade(colour: Colour, depth: Float, corner: Float = 0f, offset: Offset = Offset.Zero) =
    then(InnerShadeElement(colour, depth, corner, offset))

/** The same, with its own radius on each corner. */
fun Modifier.innerShade(colour: Colour, depth: Float, corners: Corners, offset: Offset = Offset.Zero) =
    then(InnerShadeElement(colour, depth, corners, offset))

/**
 * A moulded edge: [light] gathered along the top inside edge, [dark] along the bottom, [depth] deep.
 *
 * Two [innerShade]s, the pair a game button wants, written once. Lit from above, which is where
 * light comes from in nearly every interface.
 *
 * ```kotlin
 * Modifier.background(green, corner = 12f).bevel(depth = 6f, corner = 12f)
 * ```
 */
fun Modifier.bevel(
    depth: Float,
    corner: Float = 0f,
    light: Colour = Colour.White.scaleAlpha(0.35f),
    dark: Colour = Colour.Black.scaleAlpha(0.35f),
) = bevel(depth, Corners.single(corner), light, dark)

/** The same, with its own radius on each corner. */
fun Modifier.bevel(
    depth: Float,
    corners: Corners,
    light: Colour = Colour.White.scaleAlpha(0.35f),
    dark: Colour = Colour.Black.scaleAlpha(0.35f),
) = then(InnerShadeElement(light, depth, corners, Offset(0f, depth)))
    .then(InnerShadeElement(dark, depth, corners, Offset(0f, -depth)))

/**
 * One light, lighting the whole box: the bevel, the shine and the shadow all agree about it.
 *
 * The four look modifiers written one at a time are four chances to disagree — a shine from the top
 * over a bevel lit from the left reads as a mistake before anybody can say why. This takes the
 * direction the light falls and works the rest out: the edge facing the light is lifted, the far
 * edge is shaded, the shine lies across the lit side, and the shadow is cast away from it.
 *
 * Everything is a fraction of the node's shorter side, so the same call dresses a 200-wide button
 * and a 24-wide checkbox. Put it after the background, because that is the order things are painted.
 *
 * ```kotlin
 * Modifier.background(face, corner = 12f).moulded(corner = 12f, outline = almostBlack)
 * Modifier.background(face, corner = 12f).moulded(corner = 12f, light = 180f)   // lit from the right
 * ```
 *
 * @param light the direction the light falls, in degrees clockwise from pointing right, the way
 *   every other angle in this toolkit turns. 90 is from above, which is where interfaces are lit
 *   from; 0 is from the left.
 * @param depth how far the lifted and shaded edges reach inwards.
 * @param shine how far across the box the light band reaches, as a fraction of it. Zero for none.
 * @param strength how strong the light is: the opacity of the lift and the shine.
 * @param shadow how far a shadow is cast beyond the box. Zero for none.
 * @param outline the line round the outside, drawn outside the box so it eats none of the fill.
 * @param outlineWidth how thick that line is, as a fraction of the shorter side.
 */
@Suppress("LongParameterList")
fun Modifier.moulded(
    corner: Float = 0f,
    light: Float = 90f,
    depth: Float = 0.14f,
    shine: Float = 0.45f,
    strength: Float = 0.35f,
    shadow: Float = 0f,
    outline: Colour? = null,
    outlineWidth: Float = 0.07f,
) = moulded(Corners.single(corner), light, depth, shine, strength, shadow, outline, outlineWidth)

/** The same, with its own radius on each corner. */
@Suppress("LongParameterList")
fun Modifier.moulded(
    corners: Corners,
    light: Float = 90f,
    depth: Float = 0.14f,
    shine: Float = 0.45f,
    strength: Float = 0.35f,
    shadow: Float = 0f,
    outline: Colour? = null,
    outlineWidth: Float = 0.07f,
) = then(MouldedElement(corners, light, depth, shine, strength, shadow, outline, outlineWidth))

/**
 * A shine across the top [fraction] of this node, fading downwards: the glassy top of a game button.
 *
 * Drawn inside the node, rounded by the top corners of [corners] and square along the bottom, where
 * it fades out. [inset] pulls it in from the sides, which is what makes a shine look like a
 * reflection rather than a stripe.
 *
 * ```kotlin
 * Modifier.background(green, corner = 12f).gloss(corner = 12f)
 * ```
 */
fun Modifier.gloss(
    fraction: Float = 0.45f,
    corner: Float = 0f,
    colour: Colour = Colour.White.scaleAlpha(0.35f),
    inset: Float = 0f,
) = then(GlossElement(colour, fraction, corner, inset))

/** The same, with its own radius on each corner: only the top two are used. */
fun Modifier.gloss(
    fraction: Float = 0.45f,
    corners: Corners,
    colour: Colour = Colour.White.scaleAlpha(0.35f),
    inset: Float = 0f,
) = then(GlossElement(colour, fraction, corners, inset))

/** A soft shadow with its own radius on each corner, following the box it is under. */
fun Modifier.shadow(colour: Colour, spread: Float, corners: Corners) =
    then(ShadowElement(colour, spread, corners))

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

/**
 * Nothing this node's children draw lands outside it.
 *
 * With no [corner] it is a scissor: free, and what every scroll area and text field in this
 * toolkit uses. A [corner] rounds it, which is [clipShape] with [Shapes.roundedRect] and costs what
 * that costs.
 */
fun Modifier.clip(corner: Float = 0f) = then(ClipElement(corner))

/** The same, written with four radii so a clip can say the same [Corners] its background does. */
fun Modifier.clip(corners: Corners) = then(ClipElement(corners))

/**
 * Nothing this node draws lands outside [shape]: a round portrait, a diamond minimap, a hexagon
 * tile, cut from square art at draw time instead of masked by hand for every picture.
 *
 * ```kotlin
 * Image(portrait, Modifier.size(64f).clipShape(Shapes.Circle).hitShape(Shapes.Circle))
 * ```
 *
 * **Chain order decides what is cut.** Everything the chain paints *after* this — a background, a
 * `drawInFront` — is cut along with the node's content and children; everything painted *before*
 * it stays whole. So `border(ring).clipShape(Shapes.Circle).background(grey)` is a grey disc inside
 * a square ring, and `clipShape(Shapes.Circle).background(grey)` is only the disc.
 *
 * **Clicks agree with it.** A point the shape cut away reaches neither this node nor anything
 * under it in the tree, the same rule [clip] has always had for its rectangle — so what you cannot
 * see you cannot press, and the click carries on to whatever *is* drawn there. Pass the same shape
 * to [hitShape] when the node itself should be round to the pointer too; a clip cuts, but a node
 * with no hit shape still claims its whole rectangle wherever the clip allows it.
 *
 * **It takes a picture.** A scissor is only ever a rectangle, so the subtree is drawn into an
 * offscreen layer the size of the node and put back through the shape, with an edge softened over
 * about one screen pixel. That is one layer per clipped node per frame — fine for a portrait or a
 * row of tiles, not for a thousand of them. [Shapes.Rectangle] takes no picture at all and is
 * exactly [clip].
 *
 * A canvas that cannot make pictures, or that cannot cut one ([UiCanvas.cutsLayers]), clips to the
 * node's rectangle instead: everything still drawn, square. The hit testing does not follow that
 * degrade, so on such a canvas a cut-away corner is visible and not clickable.
 *
 * Only convex shapes: see [Shape].
 */
fun Modifier.clipShape(shape: Shape) = then(ClipElement(shape))

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

/**
 * The same, as a [Shape]: the one value [clipShape] takes, so what is drawn and what is clicked
 * are one outline rather than a picture and a lambda that have to be kept in step by hand.
 *
 * Asked in the node's own coordinates against its own size, like the lambda above, and it competes
 * with it the same way: whichever comes later in the chain is the node's shape. A shape compares
 * equal to itself, so unlike the lambda it needs no `remember`.
 */
fun Modifier.hitShape(shape: Shape) = then(ShapedHitElement(shape))

/**
 * The shape the mouse cursor takes while it is over this node: an I-beam on a text field, a hand
 * on a link, a resize arrow on an edge that drags.
 *
 * The [dev.wildware.composegl.ui.input.PointerRouter] shows the icon of the topmost node under the
 * pointer, and a node that asks for nothing shows its nearest ancestor's — so an icon on a panel
 * covers everything inside it that has not asked for its own, and a button drawn over that panel
 * without one still shows the panel's. Something drawn on top that is not inside the panel hides
 * it, the same way it would take the click.
 *
 * It makes the node findable by the pointer, but not a thing that takes presses: a click goes
 * straight through to whatever is underneath, exactly as it does through [interaction].
 *
 * While a press is held the icon stays whatever it was when the press began, wherever the pointer
 * is dragged, so a resize arrow does not flicker back to an arrow the moment a fast drag outruns
 * the edge it started on.
 *
 * Two of these on one node is a choice rather than a quantity, so the later one wins — which is
 * how a caller's own `pointerHoverIcon` on a widget's modifier beats the widget's default.
 */
fun Modifier.pointerHoverIcon(icon: PointerIcon) = then(PointerHoverIconElement(icon))

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
 * Where this node sits in the pile its siblings make: higher is drawn later, so on top.
 *
 * ```kotlin
 * Card(Modifier.zIndex(if (selected) 1f else 0f))
 * ```
 *
 * Without it, children paint in the order they are written, and the only way to lift one was to
 * write it last — which also moves it in focus order and changes the keys recomposition matches
 * nodes by. This changes the picture and nothing else.
 *
 * - **Siblings only.** It orders a node against the other children of the same parent. A child
 *   with a huge zIndex inside a low one is still under the low one's higher siblings, the same as
 *   Compose: a parent carries its whole subtree up or down with it.
 * - **Stable.** Equal values keep source order, so the default of zero changes nothing.
 * - **Clicks agree.** The pointer asks nodes in the reverse of the order they are drawn, so the
 *   one on top gets the press — a dragged tile over its grid, a hovered card over the hand.
 * - **Focus does not move.** Pad and key focus go by geometry and source order, not by paint order.
 * - **Layout does not move.** A row still lays its children out left to right as written.
 *
 * Two on one node add, like [offset]: a resting lift and a dragged one are both there.
 */
fun Modifier.zIndex(z: Float) = then(ZIndexElement(z))

/**
 * Multiplies every colour this node and everything under it draws by [colour].
 *
 * ```kotlin
 * Hotbar(slots, Modifier.tint(Colour.Red.scaleAlpha(flash)))   // a damage flash
 * Image("sword", Modifier.tint(Colour.Grey))                   // a locked item, dimmed
 * Image("banner", Modifier.tint(team.colour))                  // one piece of art, every team
 * ```
 *
 * **The alpha is how strong the tint is, not an opacity.** At zero nothing changes and at one the
 * colour applies in full, so an animated flash is one number going from one to zero. Use [alpha]
 * to fade.
 *
 * A plain multiply, the one a tinted picture already gets: no shader, no offscreen picture, and so
 * no clip at the node's edge and no ceiling on how big the subtree is. White leaves a thing alone
 * and black makes it black; nothing can be made brighter than it was, so a flash towards white
 * wants [blend] with [dev.wildware.composegl.ui.graphics.BlendMode.Additive] on an overlay
 * instead. Greying out *into* grey rather than dimming wants a shader — see `colourGrade`.
 *
 * Applies to the whole subtree, the way [alpha] does. Two on one node, or one inside another,
 * multiply: a team colour on a slot that is also locked is both.
 *
 * A canvas is allowed not to have one — [dev.wildware.composegl.ui.graphics.UiCanvas.tints]
 * answers — and then the subtree draws in its own colours. What a canvas's `raw` block draws, and
 * what a shader effect adds of its own, are not tinted.
 */
fun Modifier.tint(colour: Colour) = then(TintElement(colour))

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
 * Unlike [alpha], which quietly clamps, this throws on a factor it cannot draw. A negative one would
 * be a mirror, and a mirror is [mirror]: clamping it to zero would answer a question nobody asked
 * with an invisible widget, and flipping would turn a panel inside out for the one frame an
 * anticipate or back easing dips below zero. Hand one of those over as `scale(t.coerceAtLeast(0f))`
 * rather than discovering it on the frame the curve undershoots.
 *
 * @param origin the point that stays where it is, as a place inside this node: [Alignment.Centre]
 *   grows it about its middle, [Alignment.TopStart] about its top-left corner.
 * @throws IllegalArgumentException if [factor] is negative or not a number. Checked here, where the
 *   bad value is nearest whatever produced it, rather than turning every rectangle under it to NaN.
 */
fun Modifier.scale(factor: Float, origin: Alignment = Alignment.Centre) =
    then(ScaleElement(factor, origin))

/**
 * Flips this node and everything under it, left for right when [horizontal] is set and top for
 * bottom when [vertical] is, in place about its own middle.
 *
 * ```kotlin
 * Portrait(Modifier.mirror(horizontal = speaker.isOnRight))  // one piece of art, facing either way
 * Arrow(Modifier.mirror(vertical = pointsDown))
 * ```
 *
 * Built the way [scale] is: the subtree is drawn into an offscreen picture the right way round and
 * that picture is put down read from the other side. Nothing inside knows — so **text inside flips
 * too** and reads backwards. That is what a mirror is, and it is why this belongs round the art and
 * not round a whole speech bubble: put the portrait in a mirrored node and its name label beside it.
 *
 * Layout does not move: the node keeps its slot, and its own rectangle is the same rectangle
 * mirrored. What moves is everything *inside* it, and clicks, pad focus and
 * [dev.wildware.composegl.ui.node.UiNode.boundsInRoot] move with it — a button drawn on the right
 * of a mirrored row is clicked on the right and is the one focus reaches going right.
 * [dev.wildware.composegl.ui.node.UiNode.toLocal] is mirrored as well, so a pointer handler inside
 * sees a drag to the right on screen as one to the left in its own coordinates, which is the only
 * answer that keeps a mirrored slider under the player's finger.
 *
 * The rest is [scale]'s bargain:
 *
 * - **A capture is a clip.** Anything a child draws outside this node's rectangle is cut off while
 *   the mirror is on, and cannot be clicked there either.
 * - **It degrades honestly.** A canvas that cannot make the picture, or that says no to
 *   [dev.wildware.composegl.ui.graphics.UiCanvas.mirrorsLayers], draws the subtree the right way
 *   round, and hit testing stays the right way round with it.
 * - **Two mirrors cancel.** `mirror().mirror()` is the node as it was, and costs nothing, so a
 *   mirrored screen can hold a mirrored portrait and get it facing the original way.
 *
 * It composes with the rest of the chain on the same node. A [scale] grows the mirrored picture
 * about its origin, sharing the one capture. A [rotate] turns the mirrored picture. An [effect]
 * works on the mirrored picture, which costs one more capture than the effect alone.
 *
 * `mirror(horizontal = false)` changes nothing and takes no picture, so the flag can come straight
 * from game state.
 */
fun Modifier.mirror(horizontal: Boolean = true, vertical: Boolean = false) =
    then(MirrorElement(horizontal, vertical))

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

/**
 * Slants this node and everything under it, by [x] degrees across and [y] degrees down.
 *
 * A parallelogram rather than a rectangle: with [x] the top and bottom edges stay level and slide
 * past each other, with [y] the left and right edges stay upright and slide instead. The sign is
 * the one CSS uses in y-down coordinates, so a negative [x] leans the top forward, to the right,
 * the way italic type does:
 *
 * ```kotlin
 * Banner(Modifier.skew(x = -12f))                            // a title card leaning into speed
 * HealthBar(Modifier.skew(x = -20f, origin = Alignment.BottomStart))  // a fighting-game HUD
 * ```
 *
 * Built exactly as [rotate] is, and on the same picture: the subtree is drawn upright into an
 * offscreen picture at the size it was laid out, and that picture is put down on four corners that
 * are not a rectangle. So nothing inside knows — text is not asked for an oblique font, and a
 * leaning panel costs what an upright one does plus one composite.
 *
 * A node that is skewed *and* turned still costs one picture, not two: the slant is applied first,
 * about its own [origin], then the turn about the one [rotate] names, and both land on the same
 * four corners.
 *
 * Everything [rotate] says about its bargain holds here too:
 *
 * - **Clicks do not follow it.** A slanted node is hit inside its upright box, for the same reason
 *   a turned one is: hit testing, focus and `boundsInRoot` work in rectangles. A banner leaning
 *   a few degrees overlaps its box at two corners and falls short at the other two, which is
 *   rarely a place a player aims.
 * - **A capture is a clip**, the node's own rectangle.
 * - **It degrades honestly.** A canvas with no offscreen drawing draws the subtree plainly; one that
 *   can make pictures but not put one on four corners
 *   ([dev.wildware.composegl.ui.graphics.UiCanvas.drawsLayersOnto] is false) draws it without the
 *   slant, still turned if there is a [rotate].
 * - **Slants add along each axis.** `skew(x = -8f).skew(x = -4f)` is the slant whose slope is the
 *   two slopes added — what drawing one shear after the other does — which is almost exactly -12
 *   for angles this size. The two axes are folded into one shear, and the last origin wins.
 *
 * A skew of zero on both axes costs two comparisons and takes no picture.
 *
 * @param origin the point that stays where it is, as a place inside this node: [Alignment.Centre]
 *   slants it about its middle, [Alignment.BottomStart] keeps its bottom edge where it was laid out.
 * @throws IllegalArgumentException if either angle is not a number, or is not strictly between
 *   -90 and 90. At 90 a slant is infinitely long, so the bad value is refused here rather than
 *   turning four corners to infinity.
 */
fun Modifier.skew(x: Float = 0f, y: Float = 0f, origin: Alignment = Alignment.Centre) =
    then(SkewElement(x, y, origin))

/**
 * Turns this node and everything under it in depth, by [x], [y] and [z] degrees, seen through a
 * camera [cameraDistance] away.
 *
 * What a card flip, a panel swinging in from the side or a menu tilting towards the pointer is:
 *
 * ```kotlin
 * Card(Modifier.rotate3d(y = flip * 180f))                    // a card turning over
 * Panel(Modifier.rotate3d(y = -70f * (1f - arrival), origin = Alignment.CentreStart))  // a door
 * Menu(Modifier.rotate3d(x = -tilt.y * 8f, y = tilt.x * 8f))  // leaning towards the pointer
 * ```
 *
 * The signs are CSS's in y-down coordinates: a positive [y] sends the right edge away from you,
 * a positive [x] sends the top edge away, and a positive [z] turns clockwise in the plane of the
 * screen, the way [rotate] does. The turns are applied [z] first, then [y], then [x] — the order
 * `rotateX() rotateY() rotateZ()` means in CSS — about [origin], and the result is seen by a
 * camera straight in front of that point.
 *
 * [cameraDistance] is in inches of 72 pixels, as Android's is, so the default of eight puts the
 * camera 576 pixels away. Closer is more dramatic: a card turning near a close camera swells
 * towards you as its edge passes. Further is flatter, and a very large distance is nearly an
 * orthographic squash.
 *
 * Built exactly as [rotate] and [skew] are, and on the same picture: the subtree is drawn upright
 * into an offscreen picture at the size it was laid out, and that picture is put down on a quad
 * with its far side smaller. The GPU divides by depth for every pixel, so the picture is not bent
 * along the quad's diagonal the way four corners alone would bend it. A [skew] and a [rotate] on
 * the same node share the picture: the slant is applied to the flat picture first, then the turn
 * in depth, then the flat turn on the screen.
 *
 * Past ninety degrees you are looking at the back of the picture, which shows it mirrored — CSS's
 * `backface-visibility: visible`. A card with two faces swaps what it composes at the halfway point:
 *
 * ```kotlin
 * val angle = flip * 180f
 * Card(Modifier.rotate3d(y = angle)) {
 *     if (angle < 90f) Front() else Back(Modifier.mirror())   // mirrored once more, so it reads
 * }
 * ```
 *
 * The bargain [rotate] makes holds here too:
 *
 * - **Clicks do not follow it.** A tilted node is hit inside its upright box, because hit testing,
 *   focus and `boundsInRoot` work in rectangles. `hitShape` is the escape hatch for the rare node
 *   that needs better.
 * - **A capture is a clip**, the node's own rectangle.
 * - **It degrades honestly.** A canvas with no offscreen drawing draws the subtree plainly, and one
 *   that can make pictures but not tilt them
 *   ([dev.wildware.composegl.ui.graphics.UiCanvas.tiltsLayers] is false) draws it without the turn
 *   in depth, still slanted and turned if a [skew] or [rotate] asks.
 * - **Two on one node add angle by angle.** `rotate3d(y = 20f).rotate3d(y = flip * 180f)` is a
 *   standing tilt plus an animated flip. That is exact for turns about one axis and only close for
 *   several, which do not commute; the last camera distance and origin win.
 *
 * A node with all three angles at zero costs three comparisons and takes no picture.
 *
 * @param origin the point that stays where it is and that the camera looks at: [Alignment.Centre]
 *   spins a card about its middle, [Alignment.CentreStart] swings a panel on its left edge.
 * @throws IllegalArgumentException if an angle is not finite, or the camera distance is not
 *   positive and finite.
 */
fun Modifier.rotate3d(
    x: Float = 0f,
    y: Float = 0f,
    z: Float = 0f,
    cameraDistance: Float = DefaultCameraDistance,
    origin: Alignment = Alignment.Centre,
) = then(Rotate3dElement(x, y, z, cameraDistance, origin))

/**
 * One camera for every [rotate3d] inside this node, [distance] pixels in front of the point
 * [origin] names, so a row of cards tilting together looks like one scene rather than five.
 *
 * ```kotlin
 * Row(Modifier.perspective(distance = 800f, origin = Alignment.Centre)) {
 *     cards.forEach { Card(Modifier.rotate3d(y = tilt)) }
 * }
 * ```
 *
 * Without it, each tilted node has a camera of its own straight in front of its own middle, so
 * five cards turned the same way are five identical shapes side by side. With it, they share a
 * vanishing point: a card left of the camera shows more of its face than one to the right, the
 * way a row of real cards on a table does. CSS's `perspective` and `perspective-origin`, in the
 * same units — pixels, where [rotate3d]'s own `cameraDistance` is in 72-pixel inches.
 *
 * Every tilted node anywhere under this one uses it, not only direct children, so a card
 * wrapped in a box of its own still joins the scene. The shared camera replaces each node's own
 * `cameraDistance`; the node's `origin` is still what it turns about. Two things stop it:
 *
 * - **A nearer `perspective` wins.** A group inside the scene can set up a camera of its own.
 * - **A tilted node flattens what is inside it.** Its subtree is drawn into a flat picture first,
 *   so a tilt inside that picture goes back to its own camera — unless the tilted node carries a
 *   `perspective` itself, which then applies inside its picture.
 *
 * The node with this modifier is not tilted by it, and its own [rotate3d], if it has one, is seen
 * by the camera *outside* it. It costs nothing to draw: no picture, only a camera handed down.
 * Clicks do not follow what the camera does, as with [rotate3d].
 *
 * @param distance how far the camera is from the screen, in pixels. Closer is more dramatic.
 * @param origin where on this node the camera sits: [Alignment.Centre] looks at the middle of the
 *   row, [Alignment.CentreStart] looks along it from the left end.
 * @throws IllegalArgumentException if [distance] is not positive and finite.
 */
fun Modifier.perspective(distance: Float, origin: Alignment = Alignment.Centre) =
    then(PerspectiveElement(distance, origin))

fun Modifier.drawBehind(draw: UiCanvas.(Rect) -> Unit) = then(DrawBehindElement(draw))

fun Modifier.drawInFront(draw: UiCanvas.(Rect) -> Unit) = then(DrawInFrontElement(draw))

/**
 * Shows where this node is: an outline in [colour] over it and its children, and a faint wash.
 *
 * ```kotlin
 * Panel(Modifier.debugBounds())                              // a magenta box
 * Panel(Modifier.debugBounds(Colour.Red, label = true))      // …with its size in the corner
 * ```
 *
 * The thing to reach for instead of a temporary [border]. It changes nothing about the node but what
 * is drawn on top of it: no size, no padding, no input — a click passes straight through — and a
 * data class, so a screen recomposing with the same box on it stays still and costs nothing.
 *
 * It paints where it sits in the chain, like a border does. First in the chain, which is where a
 * widget's own `modifier` parameter puts it, outlines the whole widget; after a `padding` it outlines
 * what is inside the padding. A node with no width or no height shows as a line, which is usually
 * the answer to why it could not be seen.
 *
 * [label] puts the size in whole units, `120x40`, in a chip in the top-left corner. The digits are
 * drawn out of rectangles, so they need no font and read the same on every backend. Black on a light
 * [colour] and white on a dark one.
 */
fun Modifier.debugBounds(colour: Colour = Colour.Magenta, label: Boolean = false) =
    then(DebugBoundsElement(colour, label))

fun Modifier.interaction(state: InteractionState) = then(InteractionElement(state))

/**
 * Calls [onClick] when this node is clicked, and reports the press through any
 * [interaction] state on the same node.
 *
 * Disabled is not the same as absent: a disabled node still swallows the press, so a click cannot
 * fall through to whatever is behind a greyed-out button.
 *
 * ```kotlin
 * Modifier.clickable(
 *     onDoubleClick = { equip(item) },
 *     onLongPress = { showActions(item) },
 * ) { select(item) }
 * ```
 *
 * The same gestures come from a mouse, a finger, Enter or the pad's South button, because all of
 * them are a press and a release on the node:
 *
 * - **A double click replaces the second click.** The first click still fires [onClick] at once —
 *   a game cannot wait a third of a second to find out whether a click was the start of something
 *   — and a second press within [doubleClickMillis] of it fires [onDoubleClick] instead of a second
 *   [onClick]. A third is an ordinary click again.
 * - **A long press is instead of a click.** Held for [longPressMillis] it fires [onLongPress] once,
 *   while still held, and the release that follows does nothing. Sliding off the node before then
 *   is a change of mind, and it stays one even if the pointer comes back.
 *
 * Every timing is measured on [clock], so a press held on a world panel while the game is paused
 * waits with it, and a test runs a hold by advancing frames rather than sleeping. The time is the
 * host's own [dev.wildware.composegl.ui.animation.Clocks], the same ones a
 * [dev.wildware.composegl.ui.host.UiHost] advances every frame.
 *
 * A null [onDoubleClick] and [onLongPress] cost what a plain click always did: nothing is timed.
 *
 * @throws IllegalArgumentException if either timing is not positive.
 */
fun Modifier.clickable(
    enabled: Boolean = true,
    onDoubleClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    clock: Clock = Clock.Ui,
    longPressMillis: Int = DefaultLongPressMillis,
    doubleClickMillis: Int = DefaultDoubleClickMillis,
    onClick: () -> Unit,
) = then(
    ClickableElement(
        enabled = enabled,
        onClick = onClick,
        onDoubleClick = onDoubleClick,
        onLongPress = onLongPress,
        clock = clock,
        longPressMillis = longPressMillis,
        doubleClickMillis = doubleClickMillis,
    ),
)

/**
 * A click that keeps happening while it is held: the + and − on a quantity picker.
 *
 * ```kotlin
 * Box(Modifier.repeatingClickable { count++ }) { Text("+") }
 * ```
 *
 * A tap is one click, on the release, exactly like [clickable] — so sliding off is still a change of
 * mind. Held, it clicks after [initialDelayMillis] and every [intervalMillis] from then on, and the
 * release that ends a hold that has already clicked adds nothing. Sliding off pauses the repeat and
 * coming back resumes it.
 *
 * At most one repeat a frame: a frame that stalls for a second does not fire a burst of sixteen
 * steps the player never saw happen one at a time.
 *
 * The timing is on [clock], like every other timed click. Enter and the pad's South button repeat
 * it too, and the platform's own key repeat is ignored so the two do not stack.
 *
 * @throws IllegalArgumentException if either timing is not positive.
 */
fun Modifier.repeatingClickable(
    enabled: Boolean = true,
    initialDelayMillis: Int = 400,
    intervalMillis: Int = 60,
    clock: Clock = Clock.Ui,
    onClick: () -> Unit,
) = then(
    ClickableElement(
        enabled = enabled,
        onClick = onClick,
        repeat = ClickRepeat(initialDelayMillis, intervalMillis),
        clock = clock,
    ),
)

fun Modifier.onPointer(handler: PointerHandler) = then(PointerInputElement(handler))

/**
 * The node hears the pointer without standing in front of anything.
 *
 * ```kotlin
 * val where = remember { PointerWatcher { if (it is PointerEvent.Move) at = it.position } }
 * Box(Modifier.fillMaxSize().watchPointer(where)) { … }
 * ```
 *
 * A full-screen layer with [onPointer] on it is the topmost thing the pointer finds, so everything
 * under it stops being hovered even when the handler takes nothing. This is the other half of that:
 * the node is found by the pointer and told what it did, but it is never hovered, never pressed and
 * can never consume — so a bag slot beneath a card layer lights up exactly as it did before the
 * layer was there.
 *
 * `remember` the watcher, as with [onPointer]. See [PointerWatcher].
 */
fun Modifier.watchPointer(watcher: PointerWatcher) = then(PointerWatchElement(watcher))

/**
 * How far a pointer may wander, in the node's own units, before a press becomes a drag.
 *
 * A hand is not still. A mouse moves a pixel or two under a click and a finger rolls further than
 * that, so a press on something that is both clickable and draggable needs a margin, or every
 * slightly sloppy click is a tiny drag instead.
 */
const val DefaultDragSlop = 8f

/**
 * The node can be dragged with the primary button or a finger.
 *
 * ```kotlin
 * var at by remember { mutableStateOf(Offset.Zero) }
 * Panel(Modifier.offset(at.x, at.y).draggable(onDrag = { at += it })) { … }
 * ```
 *
 * Everything a hand-rolled drag on [onPointer] gets slightly wrong, done once:
 *
 * - **Slop.** Nothing happens until the pointer is more than [slop] from where it was pressed.
 *   Then [onDragStart] is told where the press was, and the first [onDrag] carries the whole way
 *   from there — so the thing being dragged is never [slop] behind the pointer. A press that never
 *   goes that far is still a click, if the node is [clickable].
 * - **Capture.** The press takes the pointer, so the drag carries on outside the node and outside
 *   the window, and the node stays pressed however far the pointer outruns it. A drag that did
 *   start is never a click, wherever it is let go.
 * - **A node that moves.** Deltas are in the node's own units but measured against the screen,
 *   so a window following the pointer does not cancel out its own movement. A node drawn at half
 *   [scale] reports a 10 pixel drag as 20, the same way [onPointer] does.
 * - **Cancel.** A platform taking the gesture away calls [onDragCancel] and never [onDragEnd], so
 *   a dragged item can go back where it came from. It defaults to [onDragEnd] for the many drags
 *   where letting go and being interrupted mean the same thing.
 * - **Things inside it.** A press on a button inside a draggable panel belongs to the button until
 *   the pointer passes the panel's slop. Then the button lets go — un-pressed, told
 *   [dev.wildware.composegl.ui.input.PointerEvent.Cancel], no click — and the panel drags. A child
 *   that is using the moves itself, a [dev.wildware.composegl.ui.widget.Slider] say, keeps them:
 *   dragging a slider inside a draggable window moves the slider, not the window.
 *
 * Disabled is absent: a disabled draggable does not take the press, so it falls through to
 * whatever is underneath. Turned off in the middle of a drag, the drag is cancelled.
 *
 * The callbacks written inline are new objects every recomposition, so the element never compares
 * equal — `remember` them when a node would otherwise be unchanged. Unlike a hand-rolled handler,
 * a new one does not lose the drag: the router asks the node's current element each time.
 *
 * @param onDragStart where the press was, in the node's own coordinates.
 * @param onDrag how far the pointer moved since the last call, in the node's own units.
 */
fun Modifier.draggable(
    enabled: Boolean = true,
    slop: Float = DefaultDragSlop,
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = onDragEnd,
    onDrag: (Offset) -> Unit,
) = then(DraggableElement(enabled, slop, onDragStart, onDrag, onDragEnd, onDragCancel))

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
 * Pad buttons and sticks for this node, while focus is on it or inside it, before the pad moves
 * focus or presses anything.
 *
 * What a control that listens for "any button" needs, and nothing else should: a widget that takes
 * South away from the navigator is a widget a pad cannot press.
 */
fun Modifier.onGamepadEvent(handler: GamepadHandler) = then(GamepadInputElement(handler))

/**
 * Keys for this node **wherever focus is**, once the focused node and everything around it have let
 * them through.
 *
 * ```kotlin
 * val save = remember { KeyHandler { event -> (Modifiers.Primary + Key.S).matches(event).also { if (it) save() } } }
 * Box(Modifier.onShortcutKey(save)) { … }
 * ```
 *
 * What a shortcut needs and [onKeyEvent] cannot give it: Ctrl+S has to save while focus is on a
 * field at the other side of the screen, which is not inside the node that knows how to save. A
 * `MenuBar` puts its items' shortcuts here.
 *
 * Three rules keep that from being a free-for-all:
 *
 * - **Last.** The key goes to the focused node and outwards first, as every key does, so a field
 *   that uses Ctrl+A for itself keeps it. Only a key nobody on that walk took comes here, and it
 *   comes here before the keyboard navigator, so a shortcut on an arrow key still works.
 * - **Inside the dialogue.** Only nodes inside the innermost focus trap are asked. A shortcut on the
 *   screen behind an open dialogue does not fire through it.
 * - **Seen.** A node faded or shrunk to nothing is not asked, nor is anything inside it.
 *
 * Nodes are asked in tree order until one says it used the key. A `KeyRouter` does the asking.
 */
fun Modifier.onShortcutKey(handler: KeyHandler) = then(ShortcutKeyElement(handler))

/**
 * Pad buttons and sticks for this node wherever focus is, once the focused node and everything
 * around it have let them through, and before the pad navigates.
 *
 * The pad's twin of [onShortcutKey], with the same three rules: last, inside the innermost trap,
 * and only where it can be seen. A `MenuBar` bound to a pad button listens here for it.
 */
fun Modifier.onShortcutGamepad(handler: GamepadHandler) = then(ShortcutGamepadElement(handler))

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
 * Lets a press put focus on this node, without making it somewhere Tab or the pad can go.
 *
 * What a selectable label is: clicking it has to bring the keyboard to it, or Ctrl+C would be
 * talking to whatever button had focus before. But a pad walking a menu must not stop on every
 * line of text in it, because there is nothing a pad can do to a label. So a node with this is
 * never auto-focused, never a Tab stop and never a neighbour, and once a press has put focus here
 * it stays until something else takes it — the same as a web page's `tabindex="-1"`.
 *
 * @param state told when focus arrives and leaves, like the one handed to [focusable].
 */
fun Modifier.focusableByPointer(state: InteractionState? = null) = then(PointerFocusElement(state))

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
 * Lets this node answer the pad's South button or Enter itself, before they are a click.
 *
 * ```kotlin
 * Slot(Modifier.focusable().onActivate { pickUp(item) })
 * ```
 *
 * Asked when the press comes up on the focused node, in chain order, first to say yes wins. A yes
 * is instead of the click; a no, or no handler at all, leaves the click to happen as it always did.
 * The node does not need a [clickable] for South to reach it. The pointer never asks this: a mouse
 * has its own ways to say the same thing, and [draggable] is the usual one.
 *
 * `remember` the handler, for the same reason as every other handler here.
 */
fun Modifier.onActivate(handler: ActivateHandler) = then(ActivateElement(handler))

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

/**
 * Called after layout with this node's new size, whenever it has one.
 *
 * ```kotlin
 * Panel(Modifier.onSizeChanged { size -> emitter.resize(size) }) { … }
 * ```
 *
 * Once for the first layout, then only when the size really changes: a still screen calls
 * nothing, and a node that only moves is not a size change. See [SizeChangedHandler].
 */
fun Modifier.onSizeChanged(handler: SizeChangedHandler) = then(OnSizeChangedElement(handler))

/**
 * Called after layout whenever where this node is drawn changes, with the node to ask.
 *
 * ```kotlin
 * Button("Options", Modifier.onPlaced { node -> anchor = node.boundsInRoot }) { … }
 * ```
 *
 * The way to put a popup under a button, or point a tutorial arrow at something, without asking
 * every frame. A parent moving or scaling it counts, because that moves it on screen. See
 * [PlacedHandler].
 */
fun Modifier.onPlaced(handler: PlacedHandler) = then(OnPlacedElement(handler))

/** @see dev.wildware.composegl.ui.modifier.animateContentSize */
data class AnimateContentSizeElement(
    val spec: AnimationSpec,
    val alignment: Alignment,
    val clock: Clock,
) : Modifier.Element

/**
 * Grows and shrinks towards the size this node's contents want, rather than jumping there.
 *
 * ```kotlin
 * Panel(Modifier.animateContentSize()) {
 *     Text(quest.title)
 *     if (expanded) Text(quest.description)
 * }
 * ```
 *
 * An expanding quest entry, a chat bubble growing as its text types in, a tooltip whose text
 * changes: the contents are measured at their new size at once, and the node is laid out at a size
 * that travels from the old one to the new one. What does not fit yet is cut off at the node's edge,
 * and cannot be clicked there either, until it arrives. Once it has, nothing is cut, so a glow
 * hanging over the edge of a settled panel is left alone.
 *
 * The node's whole size is animated — padding, background and border included — wherever this sits
 * in the chain. Its parent sees the moving size too, so the rows under an expanding entry slide down
 * with it rather than being overlapped. A size the chain or the parent fixes has nothing to follow,
 * and a node's first layout takes its size straight away: appearing is not resizing.
 *
 * It runs on the host's clocks and is stepped by layout, so it costs nothing once it has arrived,
 * a test's `settle` waits for it, and one on [Clock.World] stops with the game. A spec written
 * inline is fine: specs compare by what they say, so recomposing does not start it again.
 *
 * @param spec how it travels. A spring by default, which turns round smoothly when the contents
 *   change again part-way — a bubble that is still growing when the next word arrives.
 * @param alignment where the contents sit inside the node while it is the wrong size.
 *   [Alignment.TopStart] reveals them downwards and rightwards; [Alignment.BottomStart] grows a chat
 *   log upwards from its newest line.
 * @param clock which clock it runs on.
 */
fun Modifier.animateContentSize(
    spec: AnimationSpec = Spring(threshold = 0.5f),
    alignment: Alignment = Alignment.TopStart,
    clock: Clock = Clock.Ui,
) = then(AnimateContentSizeElement(spec, alignment, clock))

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
