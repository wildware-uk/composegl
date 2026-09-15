package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.draw.RectCache
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.focus.FocusRequester
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.focus.RevealHandler
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.TextHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan

/** Degrees in, radians out: what a skew angle becomes before it becomes a slope. */
private const val DegreesToRadians = (PI / 180.0).toFloat()

/**
 * One thing to paint, and how far in from the node's edge it is painted.
 *
 * The inset is what makes chain order visible. `padding(8f).background(blue)` paints the blue
 * *inside* the padding and so records an inset of 8; `background(blue).padding(8f)` paints it
 * across the whole node and records an inset of nothing. Both are things people want, and a
 * toolkit that flattened modifiers into a bag of properties could express only one of them.
 */
data class PaintOp(val element: Modifier.Element, val inset: Padding) {

    /**
     * The rectangle this op was last painted into, handed back when it has not moved.
     *
     * Outside the constructor on purpose: it is scratch space, not part of what makes two ops
     * equal. See [dev.wildware.composegl.ui.draw.RectCache].
     */
    internal val painted = RectCache()
}

/**
 * A modifier chain read once, into the questions layout and drawing actually ask.
 *
 * Folding a chain is cheap but not free, and both layout and drawing want the same answers from
 * it, so it is done once per node per frame and the result passed around.
 *
 * Where two elements of the same kind appear, later wins for the ones that are a *choice* — a
 * size, an alignment, a weight — and they accumulate for the ones that are a *quantity*: padding
 * adds up, offsets add up, opacity multiplies. That matches what people expect when they write
 * `Modifier.padding(8f).padding(4f)` and get twelve.
 *
 * Sizes and fills settle per axis rather than wholesale, because `width(40f).height(25f)` is two
 * separate statements and neither one is a reply to the other.
 */
class ResolvedModifier private constructor(
    val size: SizeElement?,
    val fill: FillElement?,
    /** The shape this node keeps, or null for none. See [dev.wildware.composegl.ui.modifier.aspectRatio]. */
    val aspectRatio: AspectRatioElement?,
    /** The range the node's size stays inside; see [dev.wildware.composegl.ui.modifier.sizeIn]. */
    val sizeIn: SizeInElement?,
    /** See [dev.wildware.composegl.ui.modifier.defaultMinSize]. */
    val defaultMinSize: DefaultMinSizeElement?,
    /** Which axes are sized to what the contents would like, asked before measuring them. */
    val intrinsicSize: IntrinsicSizeElement?,
    val padding: Padding,
    /**
     * Room measured from a line of text, in chain order. Empty for almost every node there has ever
     * been; see [dev.wildware.composegl.ui.modifier.paddingFrom].
     */
    val baselinePadding: List<BaselinePaddingElement>,
    val offset: Offset,
    val weight: Float?,
    val alignment: Alignment?,
    /** The name a parent layout can find this node by; see [dev.wildware.composegl.ui.modifier.layoutId]. */
    val layoutId: Any?,
    /**
     * Whether this node keeps its own size inside a bigger slot, and where it sits in it. Null for
     * almost every node there has ever been; see [dev.wildware.composegl.ui.modifier.wrapContentSize].
     */
    val wrap: WrapContentElement?,
    val alpha: Float,
    /**
     * How much bigger or smaller this node is drawn than it was laid out. One for almost every
     * node there has ever been; see [dev.wildware.composegl.ui.modifier.scale].
     */
    val scale: Float,
    /** The point a [scale] leaves where it is, as a place inside the node. */
    val scaleOrigin: Alignment,
    /**
     * How far clockwise this node is turned as it is drawn, in degrees. Zero for almost every node
     * there has ever been; see [dev.wildware.composegl.ui.modifier.rotate].
     */
    val rotation: Float,
    /** The point a [rotation] turns about, as a place inside the node. */
    val rotationOrigin: Alignment,
    /**
     * Whether this node is drawn with its left and right swapped. False for almost every node there
     * has ever been; see [dev.wildware.composegl.ui.modifier.mirror].
     */
    val mirrorX: Boolean,
    /** Whether this node is drawn with its top and bottom swapped. */
    val mirrorY: Boolean,
    /**
     * How far this node is slanted across and down as it is drawn, in degrees. Zero for almost
     * every node there has ever been; see [dev.wildware.composegl.ui.modifier.skew].
     */
    val skewX: Float,
    val skewY: Float,
    /** The point a skew leaves where it is, as a place inside the node. */
    val skewOrigin: Alignment,
    /**
     * The blend function this node and its subtree are drawn with. [BlendMode.SourceOver] for
     * almost every node there has ever been; see [dev.wildware.composegl.ui.modifier.blend].
     */
    val blend: BlendMode,
    /**
     * Where this node sits among its siblings when they are drawn and hit. Zero for almost every
     * node there has ever been; see [dev.wildware.composegl.ui.modifier.zIndex].
     */
    val zIndex: Float,
    /**
     * The opaque colour this node and its subtree are multiplied by. White, which changes nothing,
     * for almost every node there has ever been; see [dev.wildware.composegl.ui.modifier.tint].
     */
    val tint: Colour,
    val clip: ClipElement?,
    /**
     * How many of [behind] came before the [clip] in the chain, and so are drawn outside a clip
     * that is not a rectangle. See [dev.wildware.composegl.ui.modifier.clipShape].
     */
    val clipBehind: Int,
    /** The same for [inFront]. */
    val clipInFront: Int,
    /**
     * Which points inside the node's rectangle are its own, or null for all of them, which is
     * almost every node. Asked with a point in the node's own coordinates and the node's own size.
     * See [dev.wildware.composegl.ui.modifier.hitShape].
     */
    val hitShape: ((Offset, Size) -> Boolean)?,
    /**
     * The cursor shape asked for while the pointer is over this node, or null to show whatever its
     * nearest ancestor asks for. See [dev.wildware.composegl.ui.modifier.pointerHoverIcon].
     */
    val hoverIcon: PointerIcon?,
    /** The shaders this node is drawn through, in the order the chain wrote them. */
    val effects: List<ShaderEffect>,
    /** Backgrounds, borders, shadows and `drawBehind`, in chain order, under the node's content. */
    val behind: List<PaintOp>,
    /** `drawInFront`, in chain order, over the node and its children. */
    val inFront: List<PaintOp>,
    /** Every `interaction` state on the node. More than one is unusual but not wrong. */
    val interactions: List<InteractionState>,
    /** Raw pointer handlers, in chain order. Asked deepest-first, first to consume wins. */
    val handlers: List<PointerHandler>,
    /** Key handlers, in chain order. Asked from the focused node outwards, first to consume wins. */
    val keyHandlers: List<KeyHandler>,
    /** Text handlers, in chain order. Only ever asked on the focused node itself. */
    val textHandlers: List<TextHandler>,
    /** The node's `clickable`, if it has one. A later one replaces an earlier one. */
    val click: ClickableElement?,
    /** The node's `draggable`, if it has an enabled one. A later one replaces an earlier one. */
    val drag: DraggableElement?,
    /** Whether and how this node can hold focus. */
    val focusable: FocusableElement?,
    /** The handle a screen can use to send focus straight here. */
    val focusRequester: FocusRequester?,
    /** Directions this node answers itself rather than leaving to the geometry. */
    val focusOrder: FocusOrderElement?,
    /** Directions this node uses itself, asked before focus looks for a neighbour. */
    val focusDirections: List<DirectionHandler>,
    /** Asked to bring a descendant into view when focus lands on it. */
    val reveals: List<RevealHandler>,
    /** Told when focus arrives anywhere inside this node, or leaves it. */
    val focusWithin: List<FocusWithinHandler>,
    /** Whether focus is confined to this node's subtree. */
    val focusTrap: Boolean,
    /**
     * The name a test finds this node by, or null for almost every node there has ever been. See
     * [dev.wildware.composegl.ui.modifier.testTag].
     */
    val testTag: String?,
    /** Told after layout when the node's size changes, in chain order. */
    val sizeChanged: List<SizeChangedHandler>,
    /** Told after layout when where the node is drawn changes, in chain order. */
    val placed: List<PlacedHandler>,
) {

    val hasPainting: Boolean get() = behind.isNotEmpty() || inFront.isNotEmpty()

    /** Whether layout has anything to tell this node once it is finished. False for nearly all. */
    val watchesLayout: Boolean get() = sizeChanged.isNotEmpty() || placed.isNotEmpty()

    /**
     * Whether a pointer can find this node at all.
     *
     * A node that watches, handles or clicks is hit-testable; everything else is scenery the
     * pointer passes straight through, which is what makes hit testing cheap on a tree that is
     * mostly panels and labels.
     */
    val isInteractive: Boolean
        get() = interactions.isNotEmpty() || handlers.isNotEmpty() || click != null || drag != null ||
            focusable?.enabled == true || hoverIcon != null

    companion object {

        val None = Modifier.resolve()

        internal fun of(modifier: Modifier): ResolvedModifier {
            var size: SizeElement? = null
            var fill: FillElement? = null
            var aspectRatio: AspectRatioElement? = null
            var sizeIn: SizeInElement? = null
            var defaultMinSize: DefaultMinSizeElement? = null
            var intrinsicSize: IntrinsicSizeElement? = null
            var padding = Padding.None
            val baselinePadding = mutableListOf<BaselinePaddingElement>()
            var offset = Offset.Zero
            var weight: Float? = null
            var alignment: Alignment? = null
            var layoutId: Any? = null
            var wrap: WrapContentElement? = null
            var alpha = 1f
            var blend = BlendMode.SourceOver
            var zIndex = 0f
            var tint = Colour.White
            var scale = 1f
            var scaleOrigin = Alignment.Centre
            var rotation = 0f
            var rotationOrigin = Alignment.Centre
            var mirrorX = false
            var mirrorY = false
            var skewSlopeX = 0f
            var skewSlopeY = 0f
            var skewOrigin = Alignment.Centre
            var clip: ClipElement? = null
            var clipBehind = 0
            var clipInFront = 0
            var hitShape: ((Offset, Size) -> Boolean)? = null
            var hoverIcon: PointerIcon? = null
            val effects = mutableListOf<ShaderEffect>()
            val behind = mutableListOf<PaintOp>()
            val inFront = mutableListOf<PaintOp>()
            val interactions = mutableListOf<InteractionState>()
            val handlers = mutableListOf<PointerHandler>()
            val keyHandlers = mutableListOf<KeyHandler>()
            val textHandlers = mutableListOf<TextHandler>()
            var click: ClickableElement? = null
            var drag: DraggableElement? = null
            var focusable: FocusableElement? = null
            var focusRequester: FocusRequester? = null
            var focusOrder: FocusOrderElement? = null
            val focusDirections = mutableListOf<DirectionHandler>()
            val reveals = mutableListOf<RevealHandler>()
            val focusWithin = mutableListOf<FocusWithinHandler>()
            var focusTrap = false
            var testTag: String? = null
            val sizeChanged = mutableListOf<SizeChangedHandler>()
            val placed = mutableListOf<PlacedHandler>()

            modifier.fold(Unit) { _, element ->
                when (element) {
                    // Per axis, not wholesale: `width(40f).height(25f)` names two different
                    // things, and a later element that says nothing about an axis leaves it alone.
                    is SizeElement -> size = SizeElement(
                        element.width ?: size?.width,
                        element.height ?: size?.height,
                    )
                    is FillElement -> fill = FillElement(
                        element.widthFraction ?: fill?.widthFraction,
                        element.heightFraction ?: fill?.heightFraction,
                    )
                    // A choice: two shapes are two answers to one question, so the later one is it.
                    is AspectRatioElement -> aspectRatio = element
                    is SizeInElement -> sizeIn = sizeIn.then(element)
                    is DefaultMinSizeElement -> defaultMinSize = DefaultMinSizeElement(
                        element.minWidth ?: defaultMinSize?.minWidth,
                        element.minHeight ?: defaultMinSize?.minHeight,
                    )
                    is IntrinsicSizeElement -> intrinsicSize = IntrinsicSizeElement(
                        element.width ?: intrinsicSize?.width,
                        element.height ?: intrinsicSize?.height,
                    )
                    is PaddingElement -> padding += element.padding
                    // Kept, not folded: each one is measured from a line that is not known until
                    // layout, and the room one adds is the most any of them asks for, not a sum.
                    is BaselinePaddingElement -> baselinePadding += element
                    is OffsetElement -> offset += Offset(element.x, element.y)
                    is WeightElement -> weight = element.weight
                    is AlignElement -> alignment = element.alignment
                    // A choice: a node has one name, and a later one is a rename.
                    is LayoutIdElement -> layoutId = element.layoutId
                    // Per axis, like a size: `wrapContentWidth().wrapContentHeight()` is both.
                    is WrapContentElement -> wrap = WrapContentElement(
                        element.horizontal ?: wrap?.horizontal,
                        element.vertical ?: wrap?.vertical,
                    )
                    is AlphaElement -> alpha *= element.alpha.coerceIn(0f, 1f)
                    // A choice rather than a quantity: two blend functions on one node are two
                    // answers to the same question, so the later one is the answer. Nesting still
                    // works, because an inner node resolves its own and the canvas stacks them.
                    is BlendElement -> blend = element.mode
                    // A quantity, like an offset: two on one node add, so a resting lift and a
                    // drag's lift on the same node are both there.
                    is ZIndexElement -> zIndex += element.z
                    // A quantity, like opacity: a team colour and a locked grey on one node are
                    // both there. Read as a tint first, so its alpha is a strength by the time two
                    // of them meet.
                    is TintElement -> tint = tint.modulate(element.colour.asTint())
                    // A quantity, like opacity: two scales on one node multiply, so a panel
                    // arriving at 0.9 inside a fit correction of 0.8 is drawn at 0.72 rather than
                    // silently losing one of them. Where it grows from is a choice, so later wins.
                    is ScaleElement -> {
                        scale *= element.factor
                        scaleOrigin = element.origin
                    }
                    // An angle, like padding: two rotations on one node add, so a resting tilt
                    // and an animated one on the same node are both there. Where it turns about is
                    // a choice, so later wins.
                    is RotateElement -> {
                        rotation += element.degrees
                        rotationOrigin = element.origin
                    }
                    // A quantity, like a scale of minus one: two mirrors on one node cancel, so a
                    // portrait flipped inside a flipped panel faces the way the art was drawn.
                    is MirrorElement -> {
                        mirrorX = mirrorX != element.horizontal
                        mirrorY = mirrorY != element.vertical
                    }
                    // Where it sits in the chain matters for a shape: what was painted before it
                    // stays whole and what comes after it is cut. A later clip is a later answer.
                    is ClipElement -> {
                        clip = element
                        clipBehind = behind.size
                        clipInFront = inFront.size
                    }
                    // A shear after a shear along the same axis is one shear whose slope is the
                    // two slopes added, so it is the slopes that accumulate rather than the
                    // angles. Where it slants about is a choice, so later wins.
                    is SkewElement -> {
                        if (element.x != 0f) skewSlopeX += tan(element.x * DegreesToRadians)
                        if (element.y != 0f) skewSlopeY += tan(element.y * DegreesToRadians)
                        skewOrigin = element.origin
                    }
                    // A choice rather than a quantity, like an alignment: two shapes on one node
                    // are two answers to the same question, so the later one is the answer.
                    is HitShapeElement -> {
                        val contains = element.contains
                        hitShape = { point, _ -> contains(point) }
                    }
                    is ShapedHitElement -> hitShape = element.shape::contains
                    // A choice as well: the pointer is one shape at a time, so later wins.
                    is PointerHoverIconElement -> hoverIcon = element.icon
                    is EffectElement -> effects += element.effect
                    is BackgroundElement, is BrushBackgroundElement, is BorderElement, is BorderSidesElement, is ShadowElement,
                    is NinePatchElement, is SkinBackgroundElement, is DrawBehindElement ->
                        behind += PaintOp(element, padding)
                    is DrawInFrontElement -> inFront += PaintOp(element, padding)
                    is InteractionElement -> interactions += element.state
                    is PointerInputElement -> handlers += element.handler
                    is KeyInputElement -> keyHandlers += element.handler
                    is TextInputElement -> textHandlers += element.handler
                    is ClickableElement -> click = element
                    // Resolved away when disabled, rather than carried: a disabled draggable is an
                    // absent one, and every reader asking `drag != null` gets that for free.
                    is DraggableElement -> drag = element.takeIf { it.enabled }
                    is FocusableElement -> focusable = element
                    is FocusRequesterElement -> focusRequester = element.requester
                    is FocusOrderElement -> focusOrder = element
                    is FocusDirectionElement -> focusDirections += element.handler
                    is RevealElement -> reveals += element.handler
                    is FocusWithinElement -> focusWithin += element.handler
                    is FocusTrapElement -> focusTrap = element.enabled
                    is TestTagElement -> testTag = element.tag
                    is OnSizeChangedElement -> sizeChanged += element.handler
                    is OnPlacedElement -> placed += element.handler
                    else -> Unit   // elements later milestones add, meaningless to layout and drawing
                }
            }

            return ResolvedModifier(
                size, fill, aspectRatio, sizeIn, defaultMinSize, intrinsicSize, padding, baselinePadding.toList(), offset, weight, alignment, layoutId, wrap, alpha,
                scale, scaleOrigin,
                rotation, rotationOrigin, mirrorX, mirrorY,
                if (skewSlopeX == 0f) 0f else atan(skewSlopeX) / DegreesToRadians,
                if (skewSlopeY == 0f) 0f else atan(skewSlopeY) / DegreesToRadians,
                skewOrigin,
                blend, zIndex, tint, clip, clipBehind, clipInFront, hitShape, hoverIcon, effects.toList(),
                behind.toList(), inFront.toList(),
                interactions.toList(), handlers.toList(),
                keyHandlers.toList(), textHandlers.toList(), click, drag,
                focusable, focusRequester, focusOrder, focusDirections.toList(),
                reveals.toList(), focusWithin.toList(), focusTrap, testTag,
                sizeChanged.toList(), placed.toList(),
            )
        }
    }
}

/**
 * Two ranges on one node, settled bound by bound with the later one winning.
 *
 * A later bound can land on the wrong side of an earlier one — `widthIn(max = 100f)` then
 * `widthIn(min = 150f)` — and a range with its minimum above its maximum fits nothing. The bound
 * written later is the one that meant it, so the earlier one on the other side moves to meet it.
 */
private fun SizeInElement?.then(later: SizeInElement): SizeInElement {
    if (this == null) return later
    var minWidth = later.minWidth ?: this.minWidth
    var maxWidth = later.maxWidth ?: this.maxWidth
    if (minWidth != null && maxWidth != null && minWidth > maxWidth) {
        if (later.minWidth != null) maxWidth = minWidth else minWidth = maxWidth
    }
    var minHeight = later.minHeight ?: this.minHeight
    var maxHeight = later.maxHeight ?: this.maxHeight
    if (minHeight != null && maxHeight != null && minHeight > maxHeight) {
        if (later.minHeight != null) maxHeight = minHeight else minHeight = maxHeight
    }
    return SizeInElement(minWidth, maxWidth, minHeight, maxHeight)
}

/** Reads the chain into the answers layout and drawing want. */
fun Modifier.resolve(): ResolvedModifier = ResolvedModifier.of(this)
