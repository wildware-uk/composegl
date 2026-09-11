package composegl.ui.node

import composegl.ui.geometry.Rect
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.MeasurePolicy
import composegl.ui.geometry.Size
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.ResolvedModifier
import composegl.ui.modifier.resolve

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
     * Where this node sits in the root's coordinates — the ones a pointer event arrives in.
     *
     * Walked up the parent chain rather than stored, because storing it would mean every node
     * under a moved node had to be revisited, and the only things that ask are hit testing and
     * focus. A child's position already includes its parent's padding, so this is a sum and
     * nothing more.
     */
    val boundsInRoot: Rect
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

    override fun toString(): String = "UiNode($name)"

    /** The shape of this subtree, one node per line. For test failures and for people. */
    fun debugTree(indent: String = ""): String = buildString {
        append(indent).append(name)
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
