package dev.wildware.composegl.ui.testing

import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree

/**
 * A tree of rectangles, at the coordinates you asked for, for the tests that are about rectangles.
 *
 * Most of what this toolkit does is geometry: which button is to the right of which, where a
 * pointer landed, what got drawn where. None of those questions need a composition, and building
 * one to ask them costs three steps in a fixed order — compose leaf layouts carrying offset and
 * size modifiers, run frames until the composition settles, then run a measure pass. Leave the
 * last step out and the contents are right while every rectangle is still zero, so the test
 * quietly asserts about a screen where nothing is anywhere.
 *
 * So here a box goes where you put it, immediately:
 *
 * ```
 * val screen = TestTree()
 * screen.row("cut", "copy", "paste", modifier = Modifier.focusable())
 *
 * val focus = FocusManager(screen.root)
 * focus.focusOn(screen["cut"])
 * focus.moveFocus(FocusDirection.Right)
 *
 * assertEquals("copy", focus.focused?.name)
 * ```
 *
 * It ships in the main source set, next to `RecordingCanvas` and `HeadlessBackend`, for the same
 * reasons those do: a game testing its own screens wants it, it is multiplatform without being
 * built twice, and from in here it can put a node into the tree directly rather than through a
 * whole Compose applier.
 *
 * Each box also writes down the `offset` and `size` that would produce the rectangle it was given,
 * so [layOut] — a real measure pass over the tree — leaves every rectangle exactly where this put
 * it. That is why the fixture's own numbers win over a `size` in the modifier you pass: the
 * promise here is the coordinates you asked for, and a node whose size is layout's decision is a
 * node for a layout test rather than for this.
 */
class TestTree {

    /** The tree, for the things that want the tree — `hasChanges`, mostly. */
    val tree = UiTree()

    /** What a focus manager, a pointer router or a draw pass is built over. */
    val root: UiNode get() = tree.root

    /**
     * A rectangle called [name], inside [parent], at [x], [y] and that big.
     *
     * [x] and [y] are inside the parent, the same as [UiNode.x] and [UiNode.y] — a box at 10 in a
     * panel at 100 is at 110 on the screen.
     *
     * The size defaults to something rather than to nothing on purpose. A node with no area is not
     * a focus candidate and cannot be hit by a pointer, so a box that defaulted to zero would be
     * invisible to the very things most tests using this go on to assert.
     */
    fun box(
        name: String,
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 40f,
        height: Float = 20f,
        modifier: Modifier = Modifier,
        parent: UiNode = root,
    ): UiNode {
        check(root.firstOrNull { it.name == name } == null) {
            "there is already a node called $name in this tree, and $name is how tests find it:\n" +
                root.debugTree()
        }
        return UiNode(name).also {
            it.modifier = modifier.offset(x, y).size(width, height)
            parent.insertAt(parent.children.size, it)
            it.x = x
            it.y = y
            it.width = width
            it.height = height
        }
    }

    /**
     * Boxes side by side, left to right, [gap] apart.
     *
     * The row a menu is, and the shape half the focus tests want. A column is [column].
     */
    fun row(
        vararg names: String,
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 40f,
        height: Float = 20f,
        gap: Float = 10f,
        modifier: Modifier = Modifier,
        parent: UiNode = root,
    ): List<UiNode> = names.mapIndexed { index, name ->
        box(name, x + index * (width + gap), y, width, height, modifier, parent)
    }

    /** Boxes one under another, top to bottom, [gap] apart. */
    fun column(
        vararg names: String,
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 40f,
        height: Float = 20f,
        gap: Float = 10f,
        modifier: Modifier = Modifier,
        parent: UiNode = root,
    ): List<UiNode> = names.mapIndexed { index, name ->
        box(name, x, y + index * (height + gap), width, height, modifier, parent)
    }

    /** The node called [name]. Fails with the tree printed out, because a typo here is silent. */
    operator fun get(name: String): UiNode = checkNotNull(
        root.firstOrNull { it !== root && it.name == name },
    ) { "no node called $name in this tree:\n${root.debugTree()}" }

    /** Takes [node] out of the tree, the way a recomposition that removed it would. */
    fun remove(node: UiNode) {
        val parent = checkNotNull(node.parent) { "$node is not in this tree" }
        parent.removeAt(parent.children.indexOf(node), 1)
    }

    /** The same, for the usual case where the test knows the name and not the node. */
    fun remove(name: String) = remove(get(name))

    /** Empties the tree, for a test that builds a second screen after asserting about the first. */
    fun clear() = root.removeAt(0, root.children.size)

    /**
     * Runs a real measure pass over the tree, for a test that wants one.
     *
     * Nothing here needs this — a box is already where it was put — but a test that pulls in
     * something which measures for itself does, and then every rectangle has to survive it. It
     * does: each box declares the `offset` and `size` that produce the numbers it was given.
     *
     * The constraints default to [Constraints.Unbounded] and deliberately not to the root's own
     * size. A fresh root is 0 by 0, `atMost(0f, 0f)` clamps every `size` in the tree down to
     * nothing, and the fixture would hand back the all-zero tree it exists to prevent.
     */
    fun layOut(constraints: Constraints = Constraints.Unbounded) {
        MeasurePass().run(root, constraints)
        assertPlaced()
    }

    /**
     * Fails when every rectangle in the tree is still zero.
     *
     * Whole-tree, not per node: a box with no size modifier under a stacking parent honestly
     * measures to nothing, so "this one node has no area" is not news. "Nothing in the whole tree
     * has any area" is — nothing can be drawn, hit or focused — and it is what a tree squeezed
     * into constraints of nothing looks like.
     */
    fun assertPlaced() {
        var anything = false
        var placed = false
        root.forEach {
            if (it === root) return@forEach
            anything = true
            if (it.width != 0f || it.height != 0f) placed = true
        }
        check(!anything || placed) {
            "every rectangle in this tree is still zero, so nothing can be drawn, hit or " +
                "focused:\n" + root.debugTree()
        }
    }
}
