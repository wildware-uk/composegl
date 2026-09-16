package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.modifier.MarqueeRun
import dev.wildware.composegl.ui.modifier.ResolvedModifier
import dev.wildware.composegl.ui.modifier.WrapContentElement
import dev.wildware.composegl.ui.node.UiNode

/**
 * One layout pass over a tree.
 *
 * Two walks, not one, and both of them fall out of the same recursion: a node measures its
 * children, chooses its own size, and then places them. Positions are stored relative to the
 * parent, so nothing has to be revisited when the parent itself moves.
 *
 * A pass is a throwaway object, and it is the only thing here that is. The small objects a walk
 * needs — the wrapper round each child, the list they go in, the placeable each node hands back —
 * live on the nodes and are used again next frame, because a game runs this every frame whether
 * anything changed or not: making them fresh each time is a few hundred pieces of rubbish a frame
 * for a screen that is standing still. Which pass is running is a reference, compared by identity,
 * so "measured exactly once" is still checked and still costs nothing.
 */
class MeasurePass {

    /**
     * The nodes this pass reached that have an `onSizeChanged` or `onPlaced` on them, parents before
     * children. The list belongs to the root and is emptied and refilled each pass rather than made
     * again, for the same reason as everything else a pass touches: a screen standing still with
     * forty watchers on it would otherwise grow a fresh list to forty every frame.
     */
    private var watching: MutableList<UiNode>? = null

    /** Measures and places [node] and everything under it. The root ends up at the origin. */
    fun run(node: UiNode, constraints: Constraints) {
        beginAt(node)
        measure(node, constraints).placeAt(0f, 0f)
        reportLayout()
    }

    /** Points this pass at [root]'s watcher list, emptied. Called before the root is measured. */
    internal fun beginAt(root: UiNode) {
        root.layoutWatchers?.clear()
        watching = null
        this.root = root
    }

    private var root: UiNode? = null

    /**
     * Tells every watching node what changed, now the whole tree has its rectangles.
     *
     * After placement rather than during it, for two reasons. A node's place on screen is its
     * parent's decision, and its parent's parent's, so nothing is final until the root is down. And
     * a handler that writes state or asks a neighbour where it is must not see half a frame.
     */
    internal fun reportLayout() {
        val watching = watching ?: return
        for (index in watching.indices) watching[index].reportLayout()
    }

    /** The root's list, made the first time any pass over that tree meets a watcher. */
    private fun watchers(): MutableList<UiNode> {
        watching?.let { return it }
        val root = checkNotNull(root) { "a pass was measured without being begun" }
        val list = root.layoutWatchers ?: mutableListOf<UiNode>().also { root.layoutWatchers = it }
        watching = list
        return list
    }

    internal fun measure(node: UiNode, incoming: Constraints): Placeable {
        val resolved = node.resolved
        if (resolved.watchesLayout) {
            watchers().add(node)
        } else {
            // It may have had handlers last frame; if they come back they hear about it afresh.
            node.forgetReportedLayout()
        }
        node.givenConstraints = incoming
        val wrap = resolved.wrap
        // A node that wraps its content is not held to the parent's minimum: it measures at its
        // own size and is put inside the slot below, rather than being stretched across it.
        val offered = if (wrap == null) incoming else incoming.unforced(wrap, node.wrapConstraints)
        // Intrinsics after size and fill, because they fix an axis inside whatever those allowed,
        // and only on a node that asked: the question walks the subtree, and most nodes never ask.
        val sized = resolved.applyTo(offered, node.outerConstraints)
        val outer = if (resolved.intrinsicSize == null) sized else node.applyIntrinsics(resolved, sized)
        val padding = resolved.padding
        // Not loosened. A policy has to see the minimum it was given, or a row told to be 200
        // wide arranges its children inside the 40 they happen to add up to. Loosening for
        // children is each policy's own decision, and every one of them makes it.
        val content = outer.shrink(padding.horizontal, padding.vertical, node.contentConstraints)

        // A marquee's contents are as wide as they like: that is how it finds out they overflow.
        // The minimum is kept, so contents that fit still fill the slot and a centred label stays
        // centred. One null check for every node without one.
        val marquee = resolved.marquee
        val run = if (marquee == null) null else node.marquee ?: MarqueeRun(node).also { node.marquee = it }
        val offer = if (run == null) {
            content
        } else {
            run.offers.of(content.minWidth, Float.POSITIVE_INFINITY, content.minHeight, content.maxHeight)
        }

        val measurables = measurables(node)
        val result = with(node.measurePolicy) { node.scope.measure(measurables, offer) }

        // Children are placed now, in this node's coordinates. Where *this* node ends up is its
        // parent's business and does not change any of them.
        result.placeChildren(node.inset.at(padding.left, padding.top))

        // Read before the node's size is settled, because `paddingFrom` can only decide how much
        // room to add once it knows where the words are.
        baselines(node, result, padding.top)
        val natural = result.height + padding.vertical
        roomForBaselines(node, resolved, natural)

        // The two axes separately rather than through a Size: the object would be made and read
        // once each, per node, every frame.
        node.width = outer.constrainWidth(result.width + padding.horizontal)
        node.height = outer.constrainHeight(natural + node.baselineTop + node.baselineBottom)
        node.everMeasured = true

        // What the contents want is now known, and an animated node is laid out at where it has got
        // to on the way there instead. The contents keep the size they measured at and are placed
        // by the alignment inside the smaller or bigger box; the draw pass clips them meanwhile.
        val resize = resolved.contentSize
        if (resize != null) {
            val animation = node.sizeAnimation ?: SizeAnimation().also { node.sizeAnimation = it }
            val wantWidth = node.width
            val wantHeight = node.height
            // A frame whose size moved is a frame that changed, even though nothing recomposed.
            if (animation.follow(wantWidth, wantHeight, resize.spec, resize.clock, node.tree?.clocks)) {
                node.invalidate()
            }
            node.width = outer.constrainWidth(animation.width)
            node.height = outer.constrainHeight(animation.height)
            shiftContents(
                node,
                resize.alignment.xIn(node.width, wantWidth, node.layoutDirection),
                resize.alignment.yIn(node.height, wantHeight),
            )
        } else {
            node.sizeAnimation?.forget()
        }

        if (run != null && marquee != null) {
            run.measured(marquee, (node.width - padding.horizontal).coerceAtLeast(0f), result.width)
        } else if (node.marquee != null) {
            // The chain lost its marquee: back to rest, and no more frames.
            node.marquee?.stop()
            node.marquee = null
        }

        // The parent is told about the slot it insisted on, so its own arithmetic is unchanged,
        // and the node sits inside that slot where it asked to. With no wrap the two are the same.
        val placeable = node.placeable.on(resolved)
        if (wrap == null) {
            placeable.slot(node.width, node.height, 0f, 0f)
        } else {
            val slotWidth = incoming.constrainWidth(node.width)
            val slotHeight = incoming.constrainHeight(node.height)
            // Worked out from the two enums directly: an Alignment made here to ask would be one
            // more object per wrapped node, every frame.
            val dx = when (wrap.horizontal?.absolute(node.layoutDirection)) {
                HorizontalAlignment.Centre -> (slotWidth - node.width) / 2f
                HorizontalAlignment.End -> slotWidth - node.width
                HorizontalAlignment.Start, null -> 0f
            }
            val dy = when (wrap.vertical) {
                VerticalAlignment.Centre -> (slotHeight - node.height) / 2f
                VerticalAlignment.Bottom -> slotHeight - node.height
                // Nothing beside it in its own slot to share a line with, so the same as a Box: top.
                VerticalAlignment.Top, VerticalAlignment.Baseline, null -> 0f
            }
            placeable.slot(slotWidth, slotHeight, dx, dy)
        }

        return placeable
    }

    /**
     * Where [node]'s first and last lines of text stand, down from the top of its own box.
     *
     * A layout that draws text says so itself. Everything else — a row, a box, a button — takes
     * them from the children it has just placed: the highest first baseline among them and the
     * lowest last one, which is where a reader's eye finds the first and last lines of whatever is
     * inside. That is what lets a button stand on the same line as a label beside it without the
     * button knowing anything about baselines.
     *
     * Each child's own `offset` is taken back off, because an offset moves a node without moving
     * the space it takes, and a child's baseline is a fact about that space. Only children this
     * pass measured count: one skipped this frame — an item a lazy list scrolled past — is still
     * sitting wherever it was last frame.
     */
    private fun baselines(node: UiNode, result: MeasureResult, top: Float) {
        var first = result.firstBaseline
        var last = result.lastBaseline
        if (!first.isNaN()) first += top
        if (!last.isNaN()) last += top

        if (first.isNaN() || last.isNaN()) {
            val own = !first.isNaN()
            val ownLast = !last.isNaN()
            val children = node.children
            for (index in children.indices) {
                val child = children[index]
                if (!child.measurable.measuredIn(this)) continue
                val y = child.y - child.resolved.offset.y
                val childFirst = child.firstBaseline
                if (!own && !childFirst.isNaN() && (first.isNaN() || y + childFirst < first)) {
                    first = y + childFirst
                }
                val childLast = child.lastBaseline
                if (!ownLast && !childLast.isNaN() && (last.isNaN() || y + childLast > last)) {
                    last = y + childLast
                }
            }
        }

        node.firstBaseline = first
        node.lastBaseline = last
    }

    /**
     * Moves the children this pass placed, and the lines of text they carry, by [dx] and [dy]: a
     * resize part-way lines its full-size contents up inside the box it has reached.
     */
    private fun shiftContents(node: UiNode, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        node.firstBaseline += dy
        node.lastBaseline += dy
        val children = node.children
        for (index in children.indices) {
            val child = children[index]
            if (!child.measurable.measuredIn(this)) continue
            child.x += dx
            child.y += dy
        }
    }

    /**
     * The room `paddingFrom` asks for, now that the node knows where its words are.
     *
     * Before a baseline is the distance from the top of the node down to it, and it only ever adds:
     * a line already further down than that is left where it is. After is the same from the line to
     * the bottom. What is added above moves the children and both baselines down with it.
     *
     * Nothing at all for a node that asked for none, which is nearly every node.
     */
    private fun roomForBaselines(node: UiNode, resolved: ResolvedModifier, natural: Float) {
        var above = 0f
        var below = 0f
        val wanted = resolved.baselinePadding
        for (index in wanted.indices) {
            val padding = wanted[index]
            val line = if (padding.baseline == Baseline.First) node.firstBaseline else node.lastBaseline
            // No text inside means no line to measure from, and so nothing to add.
            if (line.isNaN()) continue
            if (padding.before - line > above) above = padding.before - line
            if (padding.after - (natural - line) > below) below = padding.after - (natural - line)
        }

        node.baselineTop = above
        node.baselineBottom = below
        if (above == 0f) return

        node.firstBaseline += above
        node.lastBaseline += above
        val children = node.children
        for (index in children.indices) {
            val child = children[index]
            if (child.measurable.measuredIn(this)) child.y += above
        }
    }

    /**
     * This node's children, each wrapped, in a list that belongs to the node.
     *
     * The list is refilled rather than rebuilt, and in the ordinary case — the same children as
     * last frame, in the same order — refilling it writes nothing at all.
     */
    private fun measurables(node: UiNode): List<Measurable> {
        val children = node.children
        val measurables = node.measurables
        if (measurables.size != children.size) {
            measurables.clear()
            for (index in children.indices) measurables.add(children[index].measurable.begin(this))
        } else {
            for (index in children.indices) {
                val wrapped = children[index].measurable.begin(this)
                if (measurables[index] !== wrapped) measurables[index] = wrapped
            }
        }
        return measurables
    }
}

/**
 * A child, wrapped so that measuring it twice is an error rather than a quiet cost.
 *
 * One per node, kept on the node. [begin] is what makes it safe to keep: it hands the wrapper to
 * whichever pass is running now, which is what the check below compares against.
 */
internal class OnceMeasurable(private val node: UiNode) : Measurable {

    private var pass: MeasurePass? = null
    private var measuredBy: MeasurePass? = null
    private var data = LayoutData.None

    override val layoutData: LayoutData get() = data

    /** Whether [pass] has measured this child, which is what makes its rectangle this frame's. */
    fun measuredIn(pass: MeasurePass): Boolean = measuredBy === pass

    fun begin(pass: MeasurePass): OnceMeasurable {
        this.pass = pass
        measuredBy = null
        return refresh()
    }

    /**
     * Brings [layoutData] up to date without starting a measure. What an intrinsic question does
     * first, since it can reach a child before the pass has.
     */
    fun refresh(): OnceMeasurable {
        // Rebuilt only when it actually differs, which for almost every node is never: a weight,
        // an alignment, an id and a world point are written in a modifier chain and then stay there.
        val resolved = node.resolved
        if (data.weight != resolved.weight || data.alignment != resolved.alignment ||
            data.layoutId != resolved.layoutId || data.worldPosition != resolved.worldPosition
        ) {
            data = if (resolved.weight == null && resolved.alignment == null &&
                resolved.layoutId == null && resolved.worldPosition == null
            ) {
                LayoutData.None
            } else {
                LayoutData(resolved.weight, resolved.alignment, resolved.layoutId, resolved.worldPosition)
            }
        }
        return this
    }

    // Asking is not measuring: none of these touch the pass, so a layout may ask about a child
    // and then measure it once, as it always could.
    override fun minIntrinsicWidth(height: Float) = node.intrinsic(Intrinsic.MinWidth, height)
    override fun maxIntrinsicWidth(height: Float) = node.intrinsic(Intrinsic.MaxWidth, height)
    override fun minIntrinsicHeight(width: Float) = node.intrinsic(Intrinsic.MinHeight, width)
    override fun maxIntrinsicHeight(width: Float) = node.intrinsic(Intrinsic.MaxHeight, width)

    override fun measure(constraints: Constraints): Placeable {
        val pass = checkNotNull(pass) { "${node.name} was measured outside a pass" }
        check(measuredBy !== pass) {
            "${node.name} was measured twice in one pass. A layout that measures a child more " +
                "than once doubles the cost of every node beneath it, and nesting two of them " +
                "squares it. Measure once and use the Placeable you got back."
        }
        measuredBy = pass
        return pass.measure(node, constraints)
    }
}

/**
 * Placing a node writes its position, plus whatever its `offset` modifier asked for, plus how far
 * an `animatePlacement` slide still has to go.
 *
 * One per node, kept on the node, and [on] points it at the resolution this pass read — which can
 * be a different object from last frame's even when it says the same thing.
 */
internal class NodePlaceable(private val node: UiNode) : Placeable() {

    private var resolved: ResolvedModifier = ResolvedModifier.None

    private var slotWidth = 0f
    private var slotHeight = 0f
    private var dx = 0f
    private var dy = 0f

    fun on(resolved: ResolvedModifier): NodePlaceable {
        this.resolved = resolved
        return this
    }

    /**
     * The room the parent reserves for this node, and where the node sits inside it. The node's
     * own size, unless `wrapContentSize` let it be smaller than what it was made to take.
     */
    fun slot(width: Float, height: Float, dx: Float, dy: Float) {
        slotWidth = width
        slotHeight = height
        this.dx = dx
        this.dy = dy
    }

    override val width get() = slotWidth
    override val height get() = slotHeight

    // Measured from the top of the slot, which is where the parent will stand the node, so the
    // node's own lines are moved down by however far it sits inside it.
    override val firstBaseline get() = node.firstBaseline + dy
    override val lastBaseline get() = node.lastBaseline + dy

    override fun placeAt(x: Float, y: Float) {
        val placement = resolved.placement
        if (placement == null) {
            node.x = x + dx + resolved.offset.x
            node.y = y + dy + resolved.offset.y
        } else {
            // Mid-slide, the node is still partway back towards where it was.
            node.x = x + dx + resolved.offset.x + placement.x
            node.y = y + dy + resolved.offset.y + placement.y
        }
    }
}

/**
 * Placement inside a padded node: the content box starts in from the edge.
 *
 * One per node, kept on the node. Nothing nests here — a node places its own children and then
 * hands back, so the two numbers are only ever read by the placement they were set for.
 */
internal class Inset : PlacementScope {

    private var dx = 0f
    private var dy = 0f

    fun at(dx: Float, dy: Float): Inset {
        this.dx = dx
        this.dy = dy
        return this
    }

    override fun Placeable.at(x: Float, y: Float) = placeAt(x + dx, y + dy)
}

/**
 * What `size`, `width`, `height` and the `fillMax*` family do to the room a node is offered.
 *
 * `size` asks for exactly that, clamped to what the parent allows — a child cannot escape its
 * parent by asking to be enormous. `fillMaxWidth` takes a share of what is on offer, and does
 * nothing at all when the offer is unbounded, because there is no share of infinity.
 *
 * When a chain says both, `fill` wins on the axis it names: `Modifier.size(50f).fillMaxWidth()`
 * is 50 tall and as wide as it can be.
 *
 * `widthIn`, `heightIn` and `sizeIn` narrow the offer first, wherever they sit in the chain, so a
 * size or a fill is measured inside the range. `defaultMinSize` lifts the minimum only on an axis
 * nothing else has said anything definite about.
 */
internal fun ResolvedModifier.applyTo(incoming: Constraints, cache: ConstraintsCache): Constraints {
    if (size == null && fill == null && aspectRatio == null && sizeIn == null && defaultMinSize == null) return incoming

    // The four numbers first, one object at the end. Written out rather than with `let`, because
    // a lambda that assigns to a local puts that local in a heap box and makes a fresh lambda to
    // reach it — and built once rather than an axis at a time, because a node that says both
    // `width` and `height` would otherwise make an object to throw away on the way to the second.
    var minWidth = incoming.minWidth
    var maxWidth = incoming.maxWidth
    var minHeight = incoming.minHeight
    var maxHeight = incoming.maxHeight

    // A range first, whatever order the chain wrote it in, because it is a rule about the node and
    // everything after this is measured inside it. Each end is kept inside the parent's offer, so
    // the pair can only ever narrow it — and, the two ends being in order to begin with, they are
    // still in order after both are pulled in.
    val atLeastWide = sizeIn?.minWidth
    if (atLeastWide != null) minWidth = incoming.constrainWidth(atLeastWide)
    val atMostWide = sizeIn?.maxWidth
    if (atMostWide != null) maxWidth = incoming.constrainWidth(atMostWide)
    val atLeastTall = sizeIn?.minHeight
    if (atLeastTall != null) minHeight = incoming.constrainHeight(atLeastTall)
    val atMostTall = sizeIn?.maxHeight
    if (atMostTall != null) maxHeight = incoming.constrainHeight(atMostTall)

    val width = size?.width
    val widthFraction = fill?.widthFraction
    val height = size?.height
    val heightFraction = fill?.heightFraction

    // A default gives way to anything that said something definite about its axis: the parent's
    // own minimum, a size, a fill, or a range's minimum. What is left is the case it exists for —
    // nobody asked, so the contents would have decided, and the contents are one letter.
    val defaultMinWidth = defaultMinSize?.minWidth
    if (defaultMinWidth != null && incoming.minWidth == 0f && width == null && widthFraction == null &&
        sizeIn?.minWidth == null
    ) {
        minWidth = defaultMinWidth.coerceIn(minWidth, maxWidth)
    }
    val defaultMinHeight = defaultMinSize?.minHeight
    if (defaultMinHeight != null && incoming.minHeight == 0f && height == null && heightFraction == null &&
        sizeIn?.minHeight == null
    ) {
        minHeight = defaultMinHeight.coerceIn(minHeight, maxHeight)
    }

    // The range as it stands now, before a size narrows it to one number: a fill below takes its
    // share of this, not of whatever a size on the same axis already said.
    val rangeMinWidth = minWidth
    val rangeMaxWidth = maxWidth
    val rangeMinHeight = minHeight
    val rangeMaxHeight = maxHeight

    if (width != null) {
        val fixed = width.coerceIn(rangeMinWidth, rangeMaxWidth)
        minWidth = fixed
        maxWidth = fixed
    }
    if (height != null) {
        val fixed = height.coerceIn(rangeMinHeight, rangeMaxHeight)
        minHeight = fixed
        maxHeight = fixed
    }

    // `fill` wins on the axis it names: Modifier.size(50f).fillMaxWidth() is 50 tall and as wide
    // as it can be. There is no share of infinity, so an unbounded offer is left alone. The share
    // is of the range's maximum rather than the parent's, so `widthIn(max = 400f).fillMaxWidth()`
    // is everything up to 400.
    if (widthFraction != null && rangeMaxWidth.isFinite()) {
        val fixed = (rangeMaxWidth * widthFraction).coerceIn(rangeMinWidth, rangeMaxWidth)
        minWidth = fixed
        maxWidth = fixed
    }
    if (heightFraction != null && rangeMaxHeight.isFinite()) {
        val fixed = (rangeMaxHeight * heightFraction).coerceIn(rangeMinHeight, rangeMaxHeight)
        minHeight = fixed
        maxHeight = fixed
    }

    // `aspectRatio` reads the room the lines above left, so whichever axis they settled is the one
    // it derives from. Up to four shapes are tried, each one axis's limit with the other worked out
    // from it: first keeping to every limit, then — if none fits — keeping only the one it started
    // from and clamping the other. A shape of zero on either side is not a shape, so a minimum of
    // nothing is never a starting point. With every try out, the content decides.
    val shape = aspectRatio
    if (shape != null) {
        val ratio = shape.ratio
        var found = false
        var shapedWidth = 0f
        var shapedHeight = 0f
        pass@ for (strict in 0..1) {
            for (step in 0..3) {
                // Width's maximum, height's maximum, width's minimum, height's minimum; the two axes
                // swapped at each level when height is to be matched first.
                val fromWidth = (step % 2 == 0) != shape.matchHeightConstraintsFirst
                val fromMax = step < 2
                val width: Float
                val height: Float
                if (fromWidth) {
                    width = if (fromMax) maxWidth else minWidth
                    if (!width.isFinite()) continue
                    height = width / ratio
                } else {
                    height = if (fromMax) maxHeight else minHeight
                    if (!height.isFinite()) continue
                    width = height * ratio
                }
                if (width <= 0f || height <= 0f) continue
                if (strict == 0 && !(width in minWidth..maxWidth && height in minHeight..maxHeight)) continue
                shapedWidth = width.coerceIn(minWidth, maxWidth)
                shapedHeight = height.coerceIn(minHeight, maxHeight)
                found = true
                break@pass
            }
        }
        if (found) {
            minWidth = shapedWidth
            maxWidth = shapedWidth
            minHeight = shapedHeight
            maxHeight = shapedHeight
        }
    }

    if (minWidth == incoming.minWidth && maxWidth == incoming.maxWidth &&
        minHeight == incoming.minHeight && maxHeight == incoming.maxHeight
    ) {
        return incoming
    }
    return cache.of(minWidth, maxWidth, minHeight, maxHeight)
}

/**
 * The same room with the minimum taken off the axes [wrap] names: the parent's slot becomes
 * the most the node may take rather than the least it must. The maximum stays, so a node that
 * wraps still cannot escape its parent by asking to be enormous.
 */
internal fun Constraints.unforced(wrap: WrapContentElement, cache: ConstraintsCache): Constraints {
    val minW = if (wrap.horizontal != null) 0f else minWidth
    val minH = if (wrap.vertical != null) 0f else minHeight
    if (minW == minWidth && minH == minHeight) return this
    return cache.of(minW, maxWidth, minH, maxHeight)
}

/**
 * The scope one node is measured in: its scratch space, and the result it hands back.
 *
 * One per node, kept on the node, which is what makes the room it lends free. Nothing here nests —
 * a node's policy runs to the end before the node's parent carries on, and a child measured in the
 * middle of it is using its own scope, not this one.
 */
internal class NodeMeasureScope : MeasureScope {

    /** Copied from the node before each measure, so a policy asking costs a field read. */
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr

    private val result = ReusableResult()

    private var placeables = arrayOfNulls<Placeable>(0)
    private var sizes = FloatArray(0)
    private var positions = FloatArray(0)
    private var placements = FloatArray(0)
    private var offers = emptyArray<ConstraintsCache>()

    override fun layout(width: Float, height: Float, place: PlacementScope.() -> Unit): MeasureResult =
        layout(width, height, Float.NaN, Float.NaN, place)

    override fun layout(
        width: Float,
        height: Float,
        firstBaseline: Float,
        lastBaseline: Float,
        place: PlacementScope.() -> Unit,
    ): MeasureResult {
        result.width = width
        result.height = height
        result.firstBaseline = firstBaseline
        result.lastBaseline = lastBaseline
        result.place = place
        result.count = -1
        return result
    }

    override fun layout(width: Float, height: Float, count: Int): MeasureResult {
        result.width = width
        result.height = height
        result.firstBaseline = Float.NaN
        result.lastBaseline = Float.NaN
        result.place = null
        result.count = count
        result.placeables = placeables
        result.placements = placements
        return result
    }

    override fun placeables(count: Int): Array<Placeable?> {
        if (placeables.size < count) placeables = arrayOfNulls(count)
        return placeables
    }

    override fun sizes(count: Int): FloatArray {
        if (sizes.size < count) sizes = FloatArray(count)
        return sizes
    }

    override fun positions(count: Int): FloatArray {
        if (positions.size < count) positions = FloatArray(count)
        return positions
    }

    override fun placements(count: Int): FloatArray {
        if (placements.size < count * 2) placements = FloatArray(count * 2)
        return placements
    }

    override fun offers(count: Int): Array<ConstraintsCache> {
        if (offers.size < count) offers = Array(count) { ConstraintsCache() }
        return offers
    }

    private var probes: ArrayList<IntrinsicProbe>? = null
    private var probeOffer: ConstraintsCache? = null

    /**
     * Stand-ins for [measurables], one each, asking [kind]. Kept and pointed at new children
     * rather than made again, so a screen that asks the same question every frame makes nothing.
     */
    internal fun probes(measurables: List<IntrinsicMeasurable>, kind: Intrinsic): List<Measurable> {
        val probes = probes ?: ArrayList<IntrinsicProbe>().also { probes = it }
        while (probes.size > measurables.size) probes.removeAt(probes.size - 1)
        for (index in measurables.indices) {
            if (index < probes.size) {
                val probe = probes[index]
                probe.target = measurables[index]
                probe.kind = kind
            } else {
                probes.add(IntrinsicProbe(measurables[index], kind))
            }
        }
        return probes
    }

    /** The room an intrinsic question is asked in: unbounded along it, [across] on the other axis. */
    internal fun probeOffer(kind: Intrinsic, across: Float): Constraints {
        val cache = probeOffer ?: ConstraintsCache().also { probeOffer = it }
        return if (kind.width) {
            cache.of(0f, Float.POSITIVE_INFINITY, 0f, across)
        } else {
            cache.of(0f, across, 0f, Float.POSITIVE_INFINITY)
        }
    }

    /**
     * The answer, filled in again each time rather than made again.
     *
     * It holds whichever of the two ways of placing the layout chose: a block to run, or a count
     * of children and the corners they go at. [count] is -1 when there is a block.
     */
    private class ReusableResult : MeasureResult {
        override var width = 0f
        override var height = 0f
        override var firstBaseline = Float.NaN
        override var lastBaseline = Float.NaN
        var place: (PlacementScope.() -> Unit)? = null
        var count = -1
        var placeables: Array<Placeable?> = EMPTY_PLACEABLES
        var placements: FloatArray = EMPTY_FLOATS

        override fun placeChildren(scope: PlacementScope) {
            val place = place
            if (place != null) {
                scope.place()
                return
            }
            with(scope) {
                for (index in 0 until count) {
                    placeables[index]?.at(placements[index * 2], placements[index * 2 + 1])
                }
            }
        }

        private companion object {
            val EMPTY_PLACEABLES = arrayOfNulls<Placeable>(0)
            val EMPTY_FLOATS = FloatArray(0)
        }
    }
}
