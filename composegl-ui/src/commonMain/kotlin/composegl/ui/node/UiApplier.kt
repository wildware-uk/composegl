package composegl.ui.node

import androidx.compose.runtime.AbstractApplier

/**
 * The bridge between the Compose runtime and our tree.
 *
 * `AbstractApplier` is public, stable Compose API. The runtime hands it inserts, moves and
 * removals and has no opinion about what a node is, which is why a UI toolkit can be built on the
 * runtime alone.
 *
 * Every method here is one line. That is not an accident — if this class ever grows logic, the
 * logic belongs on [UiNode], where it can be tested without a composition.
 */
class UiApplier(root: UiNode) : AbstractApplier<UiNode>(root) {

    /**
     * Nothing. We insert bottom-up instead.
     *
     * The runtime offers both orders and expects exactly one of them to do the work. Bottom-up
     * means a subtree is fully built before it is attached, so it is attached once rather than
     * walked again afterwards.
     */
    override fun insertTopDown(index: Int, instance: UiNode) = Unit

    override fun insertBottomUp(index: Int, instance: UiNode) = current.insertAt(index, instance)

    override fun remove(index: Int, count: Int) = current.removeAt(index, count)

    override fun move(from: Int, to: Int, count: Int) = current.move(from, to, count)

    override fun onClear() = root.removeAt(0, root.children.size)
}
