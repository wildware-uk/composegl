package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.draw.RectCache
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.ConstraintsCache
import dev.wildware.composegl.ui.layout.Inset
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.NodeMeasureScope
import dev.wildware.composegl.ui.layout.NodePlaceable
import dev.wildware.composegl.ui.layout.OnceMeasurable
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.ResolvedModifier
import dev.wildware.composegl.ui.modifier.resolve

/**
 * One node in the toolkit's own tree.
 *
 * The Compose runtime knows nothing about this class. It knows how to create, insert, move and
 * remove *something*, and [UiApplier] is where that something becomes this. That is the whole
 * seam the toolkit is built on: the same one Compose for Web uses to emit DOM elements.
 *
 * A node holds three things and no more: what it was told (its [modifier]), where layout put it
 * ([x], [y], [width], [height]), and its [children]. Anything else — how it draws, how it
 * measures — arrives later as its own concern, so that this class stays something you can hold in
 * your head.
 */
class UiNode(var name: String = "node") {

    /** Set by [UiApplier] on insertion, cleared on removal. Null for a root, or a detached node. */
    var parent: UiNode? = null
        private set

    /**
     * The tree this node belongs to, so a change here can be reported without walking upwards.
     * Null while the node is detached — a node the runtime has built but not yet inserted.
     */
    internal var tree: UiTree? = null
        private set

    private val mutableChildren = mutableListOf<UiNode>()

    val children: List<UiNode> get() = mutableChildren

    private var cachedDrawOrder: List<UiNode>? = null

    /**
     * [children] in the order they are drawn: by `zIndex`, lowest first, and in source order where
     * two are equal. Hit testing walks it backwards, so the one on top is asked first.
     *
     * The same list as [children] — not a copy — whenever no child has a zIndex, which is nearly
     * every node. Otherwise a sorted copy, kept until a child is added, removed, moved or given a
     * different chain, so a screen standing still sorts nothing.
     *
     * Only drawing and the pointer read this. Layout, focus and the tree walks go by [children],
     * because lifting a card is a statement about the picture, not about where it sits in a row.
     */
    val drawOrder: List<UiNode>
        get() = cachedDrawOrder ?: sortedForDrawing().also { cachedDrawOrder = it }

    private fun sortedForDrawing(): List<UiNode> {
        val children = mutableChildren
        var lifted = false
        for (index in children.indices) {
            if (children[index].resolved.zIndex != 0f) {
                lifted = true
                break
            }
        }
        // `sortedBy` is stable, which is the whole promise: equal values keep source order.
        return if (lifted) children.sortedBy { it.resolved.zIndex } else children
    }

    /**
     * What this node was told about itself.
     *
     * Setting it to an equal chain is free and reports nothing, which is the point of every
     * modifier element being a data class: recomposition hands us a fresh chain every time it runs,
     * and almost all of those chains say exactly what the old one said.
     */
    var modifier: Modifier = Modifier
        set(value) {
            if (field == value) return
            field = value
            cachedResolution = null
            // The parent's pile may have a different order now. Asked again next time rather than
            // worked out here, because a chain changing almost never changes a zIndex.
            parent?.cachedDrawOrder = null
            invalidate()
        }

    /**
     * How this node arranges its children.
     *
     * Defaults to stacking them, so a node that nobody gave a policy to still behaves — the root,
     * and anything a test builds by hand.
     */
    var measurePolicy: MeasurePolicy = MeasurePolicy.Stack
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * What this node draws inside itself, under its children — a run of text, a picture, a
     * nine-patch. Null for a node that is only there to arrange other nodes.
     *
     * The rectangle handed over is the content box: the node's bounds with its padding taken off.
     *
     * A widget sets this from a `remember`ed lambda. An inline one is a new object every
     * recomposition and so never compares equal, which would mark the tree changed on every
     * recomposition whether or not anything about the drawing differed.
     */
    var content: (UiCanvas.(Rect) -> Unit)? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Where inside its content box this node's [content] really puts ink, or null when the answer
     * is "all of it".
     *
     * The rectangle handed in is the same content box [content] is given, and the answer is in
     * those same coordinates. Null back means this node drew nothing at all — a widget whose
     * content is empty this frame.
     *
     * Here because for text the two rectangles are not the same and only the widget knows the
     * difference. A text node's box is a *line* box: line height, plus whatever insets turn a line
     * box into a label box. The glyphs inside it occupy a smaller rectangle, and where that
     * rectangle sits depends on the face's ascent and descent. Anything drawing a frame round
     * composed content — a debug overlay, a focus ring that should hug the letters, a containment
     * assertion in a test, a screenshot cropper — needs the second rectangle, and working it out
     * from outside means measuring everything a second time with the same style and keeping the
     * duplicate in step for ever.
     *
     * A node that leaves this null and has [content] is taken to have painted its whole content
     * box, which is true of every widget that is not text. See [paintedInRoot].
     */
    var ink: ((Rect) -> Rect?)? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Whether layout has ever given this node a rectangle.
     *
     * Only [paintedInRoot] reads it, to tell "drew nothing" from "has not been laid out yet" — two
     * answers a caller has to be able to separate and that a zero-sized rectangle at the origin
     * cannot. Ever rather than this frame: a node measured last frame and skipped this one is laid
     * out, and its rectangle is the one it still has.
     */
    internal var everMeasured = false

    // --- what the layout pass uses again every frame ---
    //
    // A pass runs over the whole tree every frame in most games, whether anything changed or not,
    // and the three objects below are the ones it would otherwise make fresh for every node every
    // time. They hold no state that outlives a pass; see MeasurePass for why that is safe.

    internal val measurable = OnceMeasurable(this)

    internal val placeable = NodePlaceable(this)

    internal val inset = Inset()

    internal val scope = NodeMeasureScope()

    /** This node's children, wrapped, refilled by each pass rather than rebuilt. */
    internal val measurables: MutableList<Measurable> = mutableListOf()

    /** The room this node was offered, and the room left inside its padding. */
    internal val outerConstraints = ConstraintsCache()
    internal val contentConstraints = ConstraintsCache()

    /** Where this node sits on screen, and where its contents sit inside it. */
    internal val drawnBounds = RectCache()
    internal val drawnContent = RectCache()

    private var cachedResolution: ResolvedModifier? = null

    /** The chain read into the answers layout and drawing ask, computed once per change. */
    val resolved: ResolvedModifier
        get() = cachedResolution ?: modifier.resolve().also { cachedResolution = it }

    // --- filled in by layout, meaningless before the first pass ---

    var x = 0f
    var y = 0f
    var width = 0f
    var height = 0f

    val size: Size get() = Size(width, height)

    /** Where this node sits inside its parent. */
    val bounds: Rect get() = Rect.of(x, y, width, height)

    /**
     * Whether the last draw pass managed the offscreen picture a [dev.wildware.composegl.ui.modifier.scale]
     * needs.
     *
     * Optimistic: a tree that has never been drawn believes its own modifiers, which is what keeps
     * a measure-then-hit-test test — and the first frame of a real screen — behaving as written.
     * The draw pass clears it when a canvas refuses the picture, and that is what makes the
     * degraded path honest: a widget drawn at its ordinary size is clicked at its ordinary size
     * too, one frame later, rather than being clicked where it was supposed to be.
     *
     * The last canvas to draw this node wins. Drawing one tree with two canvases that disagree
     * about offscreen pictures is not a thing a game does, and a shot test that records a tree
     * before routing a pointer at it is asking for the answer it gets.
     */
    internal var scaleApplied: Boolean = true

    /**
     * This node's own scale as it is actually drawn: what its chain asked for, unless the canvas
     * refused the picture. Internal, because the pointer router works the same rectangles out on
     * its way down the tree and must use the same number.
     */
    internal val drawnScale: Float get() = if (scaleApplied) resolved.scale else 1f

    /**
     * How much bigger or smaller this node is drawn than it was laid out, this node's own scale
     * and every ancestor's together.
     *
     * One for any tree that never calls `scale`, and the number to divide by to turn a distance on
     * screen into a distance in this node's own units — see [toLocal].
     */
    val scaleInRoot: Float
        get() {
            var scale = 1f
            var node: UiNode? = this
            while (node != null) {
                scale *= node.drawnScale
                node = node.parent
            }
            return scale
        }

    /**
     * Where this node is **drawn** in the root's coordinates — the ones a pointer event arrives in.
     *
     * Walked up the parent chain rather than stored, because storing it would mean every node
     * under a moved node had to be revisited, and the only things that ask are hit testing and
     * focus.
     *
     * Note that this and [bounds] are no longer the same rectangle written in two coordinate
     * systems. [bounds] is where layout put the node, before any scaling; this is where it ends up
     * on screen after it. For a tree with no `scale` in it — which is nearly every tree — they
     * still agree: the multiplies are skipped entirely and what is left is the upward sum this
     * always was. The edges are accumulated rather than added at the end, so a very deep tree can
     * come out a last-bit different from the old `Rect.of(left, top, width, height)`.
     *
     * A scale is folded in here, and not only into the drawing, on purpose: everything that asks
     * where a node is asks this. Hit testing, the click test, focus scoring and a game doing its
     * own arithmetic all get drawn pixels without knowing scale exists. Fold it into drawing alone
     * and a scaled screen looks perfect and takes its clicks in the wrong place.
     *
     * See [layoutBoundsInRoot] for the rectangle before any scaling.
     */
    val boundsInRoot: Rect get() = inRoot(0f, 0f, width, height)

    /**
     * A rectangle written in this node's own coordinates, in the root's.
     *
     * The walk [boundsInRoot] is: carried up a level at a time, scaled about this level's anchor,
     * then moved into the parent's box, then scaled about the parent's anchor, and so on. Four
     * floats, one Rect at the end — the same allocation count as the plain sum was, and the
     * multiplies only happen where something actually scales.
     *
     * This is `Rect.scaledAbout` written out rather than called: calling it would make a Rect per
     * level of the tree, and hit testing and focus ask this of a lot of nodes. `DrawPassTest`'s
     * `where a scaled node is drawn is where it says it is` is what keeps the copies honest.
     */
    private fun inRoot(startLeft: Float, startTop: Float, startRight: Float, startBottom: Float): Rect {
        var left = startLeft
        var top = startTop
        var right = startRight
        var bottom = startBottom
        var node: UiNode? = this
        while (node != null) {
            val factor = node.drawnScale
            if (factor != 1f) {
                // Alignment with a child of no width is the anchor itself: 0, half, or all of
                // the node's width.
                val anchorX = node.resolved.scaleOrigin.xIn(node.width, 0f)
                val anchorY = node.resolved.scaleOrigin.yIn(node.height, 0f)
                left = anchorX + (left - anchorX) * factor
                right = anchorX + (right - anchorX) * factor
                top = anchorY + (top - anchorY) * factor
                bottom = anchorY + (bottom - anchorY) * factor
            }
            left += node.x
            right += node.x
            top += node.y
            bottom += node.y
            node = node.parent
        }
        return Rect(left, top, right, bottom)
    }

    /**
     * What this node and everything under it actually **painted**, in the root's coordinates, or
     * null if it painted nothing.
     *
     * Not the same question as [boundsInRoot], and the difference is the point. That one is where
     * the nodes *are*; this one is where the ink is. For most nodes they are the same rectangle.
     * For text they are not — a text node's box is a line box and the glyphs sit in a smaller
     * rectangle inside it — and for a bare `Box` used only to arrange other things the honest
     * answer is nothing at all rather than its own rectangle.
     *
     * That last part is what separates this from unioning [boundsInRoot] over a subtree, which
     * over-reports by roughly the leading plus the descent. Small enough to look like a rounding
     * bug, big enough to fail a strict containment check.
     *
     * What counts as painting:
     *
     * - anything the chain put behind or in front — a background, a border, a shadow, a nine-patch,
     *   a `drawBehind` — contributes the rectangle it was painted into, insets and all;
     * - a node with [content] contributes what [ink] says, or its whole content box when [ink] says
     *   nothing, which is right for every widget that is not text;
     * - a node with neither contributes nothing, and is not a reason for its parent to report one.
     *
     * A scale folds in here exactly as it does for [boundsInRoot], so this is drawn pixels rather
     * than laid-out ones.
     *
     * Null for a node no layout pass has reached yet, which is a different answer from "drew
     * nothing" and has to be told apart from it.
     */
    val paintedInRoot: Rect?
        get() {
            if (!everMeasured) return null
            var painted = ownPaint()
            val children = this.children
            for (index in children.indices) {
                val under = children[index].paintedInRoot ?: continue
                painted = painted?.union(under) ?: under
            }
            return painted
        }

    /** What this node itself put on the canvas, ignoring its children, in the root's coordinates. */
    private fun ownPaint(): Rect? {
        val resolved = this.resolved
        // A paint op is painted into the node's rectangle less its own inset, which is what makes
        // `padding(8f).background(blue)` and `background(blue).padding(8f)` different pictures.
        var painted: Rect? = null
        val behind = resolved.behind
        for (index in behind.indices) painted = painted.grownBy(opRect(behind[index].inset))
        val inFront = resolved.inFront
        for (index in inFront.indices) painted = painted.grownBy(opRect(inFront[index].inset))

        if (content != null) {
            val padding = resolved.padding
            val box = Rect(padding.left, padding.top, width - padding.right, height - padding.bottom)
            // Null from `ink` is a widget saying it drew nothing this frame, which is not the same
            // as having no ink function at all — that means "wherever you put me, I filled it".
            val declared = ink
            val drawn = if (declared == null) box else declared(box)
            if (drawn != null) painted = painted.grownBy(inRoot(drawn.left, drawn.top, drawn.right, drawn.bottom))
        }
        return painted
    }

    /** Where one paint op landed, in the root's coordinates: this node's box less the op's inset. */
    private fun opRect(inset: Padding) =
        inRoot(inset.left, inset.top, width - inset.right, height - inset.bottom)

    private fun Rect?.grownBy(other: Rect) = this?.union(other) ?: other

    /**
     * Where layout put this node in the root's coordinates, before any scaling.
     *
     * What [boundsInRoot] meant before scaling existed, kept under its own name for the callers
     * that want the slot rather than the pixels — a layout assertion, a measurement, anything
     * comparing against [width] and [height].
     */
    val layoutBoundsInRoot: Rect
        get() {
            var left = 0f
            var top = 0f
            var node: UiNode? = this
            while (node != null) {
                left += node.x
                top += node.y
                node = node.parent
            }
            return Rect.of(left, top, width, height)
        }

    /**
     * A point in the root's coordinates, in this node's own.
     *
     * What a pointer handler is handed, and what a widget doing its own arithmetic wants: a node
     * drawn at half size is still 100 wide to itself, so a click at its drawn centre is (50, 50)
     * and a drag of 100 pixels across it is 200 of its own units. Getting this wrong is a slider
     * that moves at the wrong speed rather than anything you can see in a screenshot.
     *
     * A node scaled to nothing is drawn nowhere, so every point in it is the same point.
     */
    fun toLocal(point: Offset): Offset {
        val corner = boundsInRoot.topLeft
        val scale = scaleInRoot
        if (scale == 1f) return Offset(point.x - corner.x, point.y - corner.y)
        if (scale <= 0f) return Offset.Zero
        return Offset((point.x - corner.x) / scale, (point.y - corner.y) / scale)
    }

    /** Tells the tree that this frame is not the same as the last one. */
    fun invalidate() {
        tree?.invalidate()
    }

    // --- structure. Only the applier calls these; the tests call them directly too, because a
    // tree operation that needs a whole Compose runtime to test is a tree operation nobody tests.

    internal fun insertAt(index: Int, child: UiNode) {
        require(child.parent == null) { "$child is already in a tree, under ${child.parent}" }
        mutableChildren.add(index, child)
        child.parent = this
        child.attachTo(tree)
        cachedDrawOrder = null
        invalidate()
    }

    internal fun removeAt(index: Int, count: Int) {
        if (count == 0) return
        val removed = mutableChildren.subList(index, index + count)
        removed.forEach {
            it.parent = null
            it.attachTo(null)
        }
        removed.clear()
        cachedDrawOrder = null
        invalidate()
    }

    /**
     * Moves a run of [count] children so that they start at [to].
     *
     * [to] is an index into the list *as it is now*, before anything has moved — that is what the
     * runtime means by it, and getting it wrong shuffles children in ways that only show up as a
     * list rendering in the wrong order after a sort.
     */
    internal fun move(from: Int, to: Int, count: Int) {
        if (from == to || count == 0) return
        val destination = if (from > to) to else to - count
        val run = mutableChildren.subList(from, from + count)
        val moved = run.toList()
        run.clear()
        mutableChildren.addAll(destination, moved)
        cachedDrawOrder = null
        invalidate()
    }

    private fun attachTo(tree: UiTree?) {
        if (this.tree === tree) return
        this.tree = tree
        mutableChildren.forEach { it.attachTo(tree) }
    }

    internal fun becomeRootOf(tree: UiTree) {
        attachTo(tree)
    }

    // --- walking ---

    /** This node and everything under it, parents before children. */
    fun forEach(action: (UiNode) -> Unit) {
        action(this)
        mutableChildren.forEach { it.forEach(action) }
    }

    fun firstOrNull(predicate: (UiNode) -> Boolean): UiNode? {
        if (predicate(this)) return this
        mutableChildren.forEach { child -> child.firstOrNull(predicate)?.let { return it } }
        return null
    }

    // --- finding a node from a test ---

    /**
     * The name a test gave this node with [dev.wildware.composegl.ui.modifier.testTag], or null.
     *
     * Read off the chain rather than stored, so it is whatever the last recomposition said.
     */
    val testTag: String? get() = resolved.testTag

    /**
     * Every node under this one — this one included — whose [testTag] is [tag], parents before
     * children and in the order they were written. Empty when there are none.
     */
    fun findAll(tag: String): List<UiNode> {
        val found = mutableListOf<UiNode>()
        forEach { if (it.testTag == tag) found += it }
        return found
    }

    /**
     * The one node under this one tagged [tag], or null when there is none.
     *
     * Fails when there are two, for the same reason [find] does.
     */
    fun findOrNull(tag: String): UiNode? {
        val found = findAll(tag)
        check(found.size <= 1) {
            "$tag is on ${found.size} nodes under $name, so there is no one node to hand back. " +
                "Use findAll, or look inside the part of the screen you mean first:\n" + debugTree()
        }
        return found.firstOrNull()
    }

    /**
     * The one node under this one tagged [tag].
     *
     * Fails with the subtree printed, tags and all, because a misspelt tag is otherwise a test
     * that asserts about nothing. Two nodes with the tag fail too: picking the first would make
     * the test depend on the order of a screen it was trying not to depend on. Ask on a tagged
     * parent to narrow it — `root.find("inventory").find("slot")`.
     */
    fun find(tag: String): UiNode = checkNotNull(findOrNull(tag)) {
        "no node under $name is tagged $tag:\n" + debugTree()
    }

    override fun toString(): String = "UiNode($name)"

    /**
     * The shape of this subtree, one node per line. For test failures and for people.
     *
     * A node with a test tag shows it after its name as `#tag`.
     */
    fun debugTree(indent: String = ""): String = buildString {
        append(indent).append(name)
        testTag?.let { append(" #").append(it) }
        if (width != 0f || height != 0f || x != 0f || y != 0f) {
            append(" [").append(x).append(',').append(y)
            append(' ').append(width).append('x').append(height).append(']')
        }
        mutableChildren.forEach { append('\n').append(it.debugTree("$indent  ")) }
    }

    /** Child names in order, which is what most structural tests actually want to assert. */
    fun childNames(): List<String> = mutableChildren.map { it.name }
}

/**
 * A tree of nodes, and the one bit of bookkeeping that belongs to the tree rather than any node:
 * whether anything has changed since the last frame.
 *
 * This is the question the whole design turns on. Compose UI tracks invalidation for you; without
 * it, the tracking has to be ours, and it turns out to be a boolean — because the runtime only
 * ever reaches the tree through two doors. The applier marks structural change; the node marks
 * modifier change. There is no third door.
 */
class UiTree(val root: UiNode = UiNode("root")) {

    private var changed = true

    init {
        root.becomeRootOf(this)
    }

    fun invalidate() {
        changed = true
    }

    /** True when the tree needs laying out and drawing again. Clears the flag. */
    fun consumeChanges(): Boolean {
        val was = changed
        changed = false
        return was
    }

    /** Whether a change is pending, without clearing it. For tests and for assertions. */
    val hasChanges: Boolean get() = changed

    override fun toString(): String = root.debugTree()
}
