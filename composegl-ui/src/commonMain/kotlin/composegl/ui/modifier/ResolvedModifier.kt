package composegl.ui.modifier

import composegl.ui.geometry.Offset
import composegl.ui.input.InteractionState
import composegl.ui.input.PointerHandler
import composegl.ui.layout.Alignment
import composegl.ui.layout.Padding

/**
 * One thing to paint, and how far in from the node's edge it is painted.
 *
 * The inset is what makes chain order visible. `padding(8f).background(blue)` paints the blue
 * *inside* the padding and so records an inset of 8; `background(blue).padding(8f)` paints it
 * across the whole node and records an inset of nothing. Both are things people want, and a
 * toolkit that flattened modifiers into a bag of properties could express only one of them.
 */
data class PaintOp(val element: Modifier.Element, val inset: Padding)

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
    val padding: Padding,
    val offset: Offset,
    val weight: Float?,
    val alignment: Alignment?,
    val alpha: Float,
    val clip: ClipElement?,
    /** Backgrounds, borders, shadows and `drawBehind`, in chain order, under the node's content. */
    val behind: List<PaintOp>,
    /** `drawInFront`, in chain order, over the node and its children. */
    val inFront: List<PaintOp>,
    /** Every `interaction` state on the node. More than one is unusual but not wrong. */
    val interactions: List<InteractionState>,
    /** Raw pointer handlers, in chain order. Asked deepest-first, first to consume wins. */
    val handlers: List<PointerHandler>,
    /** The node's `clickable`, if it has one. A later one replaces an earlier one. */
    val click: ClickableElement?,
) {

    val hasPainting: Boolean get() = behind.isNotEmpty() || inFront.isNotEmpty()

    /**
     * Whether a pointer can find this node at all.
     *
     * A node that watches, handles or clicks is hit-testable; everything else is scenery the
     * pointer passes straight through, which is what makes hit testing cheap on a tree that is
     * mostly panels and labels.
     */
    val isInteractive: Boolean
        get() = interactions.isNotEmpty() || handlers.isNotEmpty() || click != null

    companion object {

        val None = Modifier.resolve()

        internal fun of(modifier: Modifier): ResolvedModifier {
            var size: SizeElement? = null
            var fill: FillElement? = null
            var padding = Padding.None
            var offset = Offset.Zero
            var weight: Float? = null
            var alignment: Alignment? = null
            var alpha = 1f
            var clip: ClipElement? = null
            val behind = mutableListOf<PaintOp>()
            val inFront = mutableListOf<PaintOp>()
            val interactions = mutableListOf<InteractionState>()
            val handlers = mutableListOf<PointerHandler>()
            var click: ClickableElement? = null

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
                    is PaddingElement -> padding += element.padding
                    is OffsetElement -> offset += Offset(element.x, element.y)
                    is WeightElement -> weight = element.weight
                    is AlignElement -> alignment = element.alignment
                    is AlphaElement -> alpha *= element.alpha.coerceIn(0f, 1f)
                    is ClipElement -> clip = element
                    is BackgroundElement, is BorderElement, is ShadowElement,
                    is NinePatchElement, is DrawBehindElement ->
                        behind += PaintOp(element, padding)
                    is DrawInFrontElement -> inFront += PaintOp(element, padding)
                    is InteractionElement -> interactions += element.state
                    is PointerInputElement -> handlers += element.handler
                    is ClickableElement -> click = element
                    else -> Unit   // elements later milestones add, meaningless to layout and drawing
                }
            }

            return ResolvedModifier(
                size, fill, padding, offset, weight, alignment, alpha, clip,
                behind.toList(), inFront.toList(),
                interactions.toList(), handlers.toList(), click,
            )
        }
    }
}

/** Reads the chain into the answers layout and drawing want. */
fun Modifier.resolve(): ResolvedModifier = ResolvedModifier.of(this)
