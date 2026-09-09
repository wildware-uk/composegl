package spike.rt

import androidx.compose.runtime.AbstractApplier

/** How a node paints itself, once layout has told it where it is. */
fun interface Painter {
    fun paint(renderer: Renderer, node: GlNode)
}

/** Everything a node can be told about itself. Our stand-in for `Modifier`. */
data class GlStyle(
    val width: Float? = null,
    val height: Float? = null,
    val padding: Float = 0f,
    val gap: Float = 0f,
    val background: Long? = null,
    val border: Long? = null,
    val text: String? = null,
    val textSize: Float = 18f,
    val textColour: Long = 0xFFFFFFFF,
    val onClick: (() -> Unit)? = null,
    val direction: Direction = Direction.Column,
    val fillWidth: Boolean = false,
    val alpha: Float = 1f,
    /** Nudge from where layout would have put us. Our stand-in for absolute positioning. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

enum class Direction { Column, Row, Stack }

/**
 * One node in our own tree.
 *
 * The Compose runtime knows nothing about this type — it only knows how to create, insert, move
 * and remove *something*, which is the whole reason this experiment is possible.
 */
class GlNode {
    val children = mutableListOf<GlNode>()

    var style: GlStyle = GlStyle()
        set(value) {
            if (field != value) {
                field = value
                markDirty()
            }
        }

    var painter: Painter? = null

    // Filled in by layout.
    var x = 0f
    var y = 0f
    var width = 0f
    var height = 0f

    fun markDirty() {
        Tree.dirty = true
    }

    fun forEach(action: (GlNode) -> Unit) {
        action(this)
        children.forEach { it.forEach(action) }
    }

    /** Deepest node under the point that wants clicks. */
    fun hitTest(px: Float, py: Float): GlNode? {
        if (px < x || py < y || px > x + width || py > y + height) return null
        for (child in children.asReversed()) {
            child.hitTest(px, py)?.let { return it }
        }
        return if (style.onClick != null) this else null
    }
}

/**
 * Whether anything has changed since the last frame.
 *
 * This is the question the spike exists to answer. In Compose UI, invalidation is tracked for you
 * and `hasInvalidations()` reports it. Without Compose UI, the tracking has to be ours — and it
 * turns out to be four lines: the applier marks structural changes, and the node marks property
 * changes, because the runtime only ever touches the tree through those two paths.
 */
object Tree {
    var dirty = true

    fun consume(): Boolean {
        val was = dirty
        dirty = false
        return was
    }
}

/**
 * The bridge between the Compose runtime and our tree.
 *
 * `AbstractApplier` is public, stable API — the runtime hands it inserts, moves and removals and
 * has no opinion about what a node is. This is the same seam Compose HTML uses to emit DOM and
 * Mosaic uses to emit terminal cells.
 */
class GlNodeApplier(root: GlNode) : AbstractApplier<GlNode>(root) {

    override fun insertTopDown(index: Int, instance: GlNode) {
        // Nothing: inserting bottom-up avoids a second pass over the subtree.
    }

    override fun insertBottomUp(index: Int, instance: GlNode) {
        current.children.add(index, instance)
        Tree.dirty = true
    }

    override fun remove(index: Int, count: Int) {
        current.children.subList(index, index + count).clear()
        Tree.dirty = true
    }

    override fun move(from: Int, to: Int, count: Int) {
        current.children.move(from, to, count)
        Tree.dirty = true
    }

    override fun onClear() {
        root.children.clear()
        Tree.dirty = true
    }
}
