package dev.wildware.composegl.ui.layout

/**
 * Something that has been measured and is waiting to be told where it goes.
 *
 * The split between measuring and placing is what keeps layout to two passes. A parent measures
 * every child to learn how big they are, works out the arrangement knowing all of them, and only
 * then places each one. Without the split, a row could not centre its children without measuring
 * them twice.
 */
abstract class Placeable {

    abstract val width: Float
    abstract val height: Float

    /**
     * How far down from this child's top its first line of text stands, or NaN when there is no
     * text in it to stand on.
     *
     * Known once it has been measured, which is the whole reason it is here rather than worked out
     * from a font: a row lining up "120" beside "HP" by their baselines has to know where each one's
     * baseline landed, and only measuring the child says. Measured from the top of the child's own
     * box, padding included, and not moved by its `offset` — an offset moves a node without
     * changing the space it takes, and so does not change where layout thinks its words are.
     *
     * NaN rather than null so that asking costs nothing: a nullable float is a boxed float, asked
     * of every child of every baseline row every frame.
     */
    open val firstBaseline: Float get() = Float.NaN

    /** The same, for the last line. The same as [firstBaseline] for a single line of text. */
    open val lastBaseline: Float get() = Float.NaN

    /** [firstBaseline] or [lastBaseline], whichever [baseline] names. */
    fun baseline(baseline: Baseline): Float = when (baseline) {
        Baseline.First -> firstBaseline
        Baseline.Last -> lastBaseline
    }

    /** Called by the parent, from inside its placement block. Coordinates are relative to it. */
    abstract fun placeAt(x: Float, y: Float)
}

/**
 * A child a parent may measure.
 *
 * A parent gets these, not nodes: it can ask how big a child would like to be, and nothing else.
 * That is what stops layouts reaching into each other.
 *
 * It can also ask before measuring — see [IntrinsicMeasurable] — which is how a column learns how
 * wide its widest child is before it decides how wide to make all of them.
 */
interface Measurable : IntrinsicMeasurable {

    fun measure(constraints: Constraints): Placeable

    /**
     * What the child said about itself that its parent needs — a weight in a row, an alignment in
     * a box. Null when the child said nothing.
     */
    override val layoutData: LayoutData
}

/**
 * The parts of a child's modifier that only its parent can act on.
 *
 * @property layoutId the name the child was given with `Modifier.layoutId`, so a layout with
 *   named slots can find "the icon" rather than "the first child" — which stops being the icon the
 *   moment something before it is only there sometimes.
 */
data class LayoutData(
    val weight: Float? = null,
    val alignment: Alignment? = null,
    val layoutId: Any? = null,
) {
    companion object {
        val None = LayoutData()
    }
}

/** The name this child was given with `Modifier.layoutId`, or null when it was given none. */
val Measurable.layoutId: Any? get() = layoutData.layoutId

/** The size a layout chose, and how to place its children once it has been given that size. */
interface MeasureResult {
    val width: Float
    val height: Float

    /**
     * Where this layout's own first line of text stands, down from the top of its content, or NaN
     * for "I have none of my own".
     *
     * NaN is the answer almost every layout gives, and it does not mean the node has no baseline:
     * a row, a box or a button with a label in it reports the baseline of the text inside it, which
     * the measure pass works out from the children once they are placed. Only a layout that draws
     * text itself — a label, a text field — has a baseline of its own to report.
     */
    val firstBaseline: Float get() = Float.NaN

    /** The same, for the last line. */
    val lastBaseline: Float get() = Float.NaN

    fun placeChildren(scope: PlacementScope)
}

/** Where placing happens. Exists so that `placeAt` can only be called at the right moment. */
interface PlacementScope {
    fun Placeable.at(x: Float, y: Float) = placeAt(x, y)
}

/**
 * Where measuring happens. Exists so that [layout] can only be called at the right moment.
 *
 * It also lends a layout the scratch space it needs while it works. A layout has to hold on to its
 * children between measuring them and placing them, and the obvious way — a list built on the spot
 * — is a list per node per frame, for a tree that is measured every frame whether it changed or
 * not. So the room is lent instead: it belongs to the node, it is used again next frame, and it is
 * good until this measure returns. Keeping it past that is keeping somebody else's paper.
 *
 * A layout is free to ignore all of it and build its own lists. Nothing checks.
 */
interface MeasureScope {

    /**
     * Which way the node being measured reads.
     *
     * A layout that puts children side by side asks this to know which side is the start: a [Row]
     * in Arabic puts its first child on the right. A layout that only stacks or centres things has
     * no reason to look.
     */
    val layoutDirection: LayoutDirection get() = LayoutDirection.Ltr

    /**
     * The size this layout has chosen, and what to do once it is settled.
     *
     * @param place runs after every layout in the tree has a size, which is why a parent can
     *   centre a child it measured earlier without measuring it again.
     */
    fun layout(width: Float, height: Float, place: PlacementScope.() -> Unit): MeasureResult =
        object : MeasureResult {
            override val width = width
            override val height = height
            override fun placeChildren(scope: PlacementScope) = scope.place()
        }

    /**
     * The same, for a layout that draws text itself and so knows where its lines stand.
     *
     * Both baselines are measured down from the top of this layout's content, the same coordinates
     * its children are placed in. A layout that only arranges other nodes has no reason to call
     * this: its baseline is taken from the text inside it without being asked.
     *
     * ```kotlin
     * return layout(width, height, firstBaseline = block.firstBaseline, lastBaseline = last) {}
     * ```
     */
    fun layout(
        width: Float,
        height: Float,
        firstBaseline: Float,
        lastBaseline: Float,
        place: PlacementScope.() -> Unit,
    ): MeasureResult =
        object : MeasureResult {
            override val width = width
            override val height = height
            override val firstBaseline = firstBaseline
            override val lastBaseline = lastBaseline
            override fun placeChildren(scope: PlacementScope) = scope.place()
        }

    /** Room for [count] children, from index zero. May be longer than asked for. */
    fun placeables(count: Int): Array<Placeable?> = arrayOfNulls(count)

    /** Room for [count] numbers: how big each child turned out. */
    fun sizes(count: Int): FloatArray = FloatArray(count)

    /** Room for [count] numbers: where each child starts. A different array from [sizes]. */
    fun positions(count: Int): FloatArray = FloatArray(count)

    /**
     * Room for [count] corners: x, y, x, y…, the top-left each child is going to be placed at.
     *
     * Filled in while measuring and handed to the [layout] below, for layouts that already know
     * where everything goes by the time they know how big they are — which is most of them.
     */
    fun placements(count: Int): FloatArray = FloatArray(count * 2)

    /**
     * Room for [count] remembered offers, one per child: what each was last measured with.
     *
     * A layout that works out a child's [Constraints] rather than passing on its own — a line
     * handing out what is left, a bar sizing its pieces — would otherwise make one per child per
     * frame. One cache each, kept on the node, means a screen standing still makes none.
     */
    fun offers(count: Int): Array<ConstraintsCache> = Array(count) { ConstraintsCache() }

    /**
     * The same as the [layout] above, but placing the first [count] of [placeables] at the corners
     * in [placements] instead of running a block.
     *
     * Worth the second way of saying it because of what the block costs. A block that mentions
     * anything around it — the children, their number, the size just chosen — is a small object
     * made fresh every time the layout runs, and a layout runs every frame for every node on the
     * screen whether anything moved or not. Nothing here mentions anything: the answer, the
     * children and their corners all belong to the node already, so a screen standing still makes
     * nothing at all.
     *
     * Use it when the corners are known at the end of measuring. Use the block form when they are
     * not, or when the placing is unusual enough that arrays would obscure it.
     */
    fun layout(width: Float, height: Float, count: Int): MeasureResult {
        val placeables = placeables(count)
        val placements = placements(count)
        return layout(width, height) {
            for (index in 0 until count) {
                placeables[index]?.at(placements[index * 2], placements[index * 2 + 1])
            }
        }
    }
}

/**
 * How one kind of layout arranges its children. A row, a column, a box, or anything a game writes.
 *
 * There is no private hook here that only the built-in layouts can reach. If `Row` needs something
 * this interface cannot express, the model is wrong and the fix is to change the model.
 */
fun interface MeasurePolicy {

    fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult

    /**
     * How narrow this layout can be, [height] tall, given what its children say about themselves.
     *
     * The four intrinsic questions have an answer already: [measure] is run over stand-ins that
     * report each child's own intrinsic size, so a layout that arranges its children gets
     * intrinsics that agree with the arrangement without writing any. Override them when the
     * answer is cheaper to work out directly, or when measuring cannot tell — a leaf of text knows
     * its longest word, and measuring it at a width does not.
     *
     * An answer must not measure anything. Ask [IntrinsicMeasurable]s instead.
     *
     * A policy whose [measure] writes anything down — a scroll position clamped to the window, a
     * list of lines to draw — must override all four. The default runs [measure] with made-up room,
     * and whatever it writes down there is wrong by the time the real measure comes.
     */
    fun MeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float): Float =
        probe(this, measurables, Intrinsic.MinWidth, height)

    /** How wide this layout would be, [height] tall, if room were no object. */
    fun MeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Float): Float =
        probe(this, measurables, Intrinsic.MaxWidth, height)

    /** How short this layout can be, [width] wide. */
    fun MeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float): Float =
        probe(this, measurables, Intrinsic.MinHeight, width)

    /** How tall this layout would be, [width] wide, if room were no object. */
    fun MeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Float): Float =
        probe(this, measurables, Intrinsic.MaxHeight, width)

    companion object {

        /**
         * Children laid on top of each other at the origin; the node is as big as the largest of
         * them. This is also what a node gets when nobody gave it a policy, so a tree of bare
         * `Layout` calls behaves sensibly instead of collapsing to nothing.
         */
        val Stack = MeasurePolicy { measurables, constraints ->
            val count = measurables.size
            val placeables = placeables(count)
            val offered = if (count == 0) constraints else constraints.loosen(offers(1)[0])

            var widest = 0f
            var tallest = 0f
            for (index in 0 until count) {
                val placeable = measurables[index].measure(offered)
                placeables[index] = placeable
                if (placeable.width > widest) widest = placeable.width
                if (placeable.height > tallest) tallest = placeable.height
            }

            val placements = placements(count)
            for (index in 0 until count) {
                placements[index * 2] = 0f
                placements[index * 2 + 1] = 0f
            }
            layout(constraints.constrainWidth(widest), constraints.constrainHeight(tallest), count)
        }

        /** Nothing inside: the node takes the smallest size it is allowed. */
        val Empty = MeasurePolicy { _, constraints ->
            layout(constraints.minWidth, constraints.minHeight) {}
        }
    }
}

