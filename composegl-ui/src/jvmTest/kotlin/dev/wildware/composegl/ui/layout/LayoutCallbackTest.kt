package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `onSizeChanged` and `onPlaced` against a hand-built tree and a bare layout pass.
 *
 * The promise is in three parts and each has a test: told after layout, with this frame's
 * rectangles; told when something changed and never otherwise; and told afresh when a node or a
 * handler comes back. The composed, clicked-at version is `LayoutCallbackInteractionTest`.
 */
class LayoutCallbackTest {

    private val tree = UiTree()
    private val sizes = mutableListOf<Size>()
    private val places = mutableListOf<Rect>()

    private val onSize = SizeChangedHandler { sizes += it }
    private val onPlace = PlacedHandler { places += it.boundsInRoot }

    private fun node(name: String, modifier: Modifier, parent: UiNode = tree.root): UiNode =
        UiNode(name).also {
            it.modifier = modifier
            parent.insertAt(parent.children.size, it)
        }

    private fun layOut() = MeasurePass().run(tree.root, Constraints.atMost(500f, 500f))

    @Test
    fun `the first layout counts as a change`() {
        node("watched", Modifier.offset(10f, 20f).size(30f, 40f).onSizeChanged(onSize).onPlaced(onPlace))

        layOut()

        assertEquals(listOf(Size(30f, 40f)), sizes)
        assertEquals(listOf(Rect(10f, 20f, 40f, 60f)), places)
    }

    @Test
    fun `a node of no size is still told its size and place`() {
        node("empty", Modifier.offset(5f, 6f).size(0f).onSizeChanged(onSize).onPlaced(onPlace))

        layOut()
        layOut()

        assertEquals(listOf(Size(0f, 0f)), sizes)
        assertEquals(listOf(Rect(5f, 6f, 5f, 6f)), places)
    }

    @Test
    fun `a still tree is told nothing however many passes run`() {
        node("watched", Modifier.size(30f).onSizeChanged(onSize).onPlaced(onPlace))
        layOut()
        sizes.clear()
        places.clear()

        repeat(50) { layOut() }

        assertEquals(emptyList<Size>(), sizes)
        assertEquals(emptyList<Rect>(), places)
    }

    @Test
    fun `growing is a size change and a place change`() {
        val watched = node("watched", Modifier.size(30f).onSizeChanged(onSize).onPlaced(onPlace))
        layOut()

        watched.modifier = Modifier.size(60f).onSizeChanged(onSize).onPlaced(onPlace)
        layOut()

        assertEquals(listOf(Size(30f, 30f), Size(60f, 60f)), sizes)
        assertEquals(Rect(0f, 0f, 60f, 60f), places.last())
    }

    @Test
    fun `a parent moving it is a place change but not a size change`() {
        val panel = node("panel", Modifier.offset(0f, 0f).size(200f))
        node("watched", Modifier.padding(0f).size(30f).onSizeChanged(onSize).onPlaced(onPlace), panel)
        layOut()

        panel.modifier = Modifier.offset(100f, 50f).size(200f)
        layOut()

        assertEquals(listOf(Size(30f, 30f)), sizes, "only moving is not a size change")
        assertEquals(listOf(Rect(0f, 0f, 30f, 30f), Rect(100f, 50f, 130f, 80f)), places)
    }

    @Test
    fun `a parent scaling it is a place change because it is drawn somewhere else`() {
        val panel = node("panel", Modifier.size(200f))
        node("watched", Modifier.size(50f).onPlaced(onPlace), panel)
        layOut()

        panel.modifier = Modifier.size(200f).scale(2f)
        layOut()

        // Scaled about the panel's centre, (100, 100): the corner at 0 goes to -100, 50 goes to 0.
        assertEquals(Rect(-100f, -100f, 0f, 0f), places.last())
        assertEquals(2, places.size)
    }

    @Test
    fun `the handler sees the whole finished tree and not half a frame`() {
        // The watched node comes first, so a handler run mid-pass would see its sibling unplaced.
        val seen = mutableListOf<Rect>()
        node("first", Modifier.size(10f).onPlaced { seen += tree.root.children[1].layoutBoundsInRoot })
        node("second", Modifier.offset(70f, 80f).size(20f))

        layOut()

        assertEquals(listOf(Rect(70f, 80f, 90f, 100f)), seen)
    }

    @Test
    fun `parents are told before their children`() {
        val order = mutableListOf<String>()
        val panel = node("panel", Modifier.size(100f).onPlaced { order += it.name })
        node("child", Modifier.size(10f).onPlaced { order += it.name }, panel)

        layOut()

        assertEquals(listOf("panel", "child"), order)
    }

    @Test
    fun `a node taken out and put back is told again`() {
        val watched = node("watched", Modifier.size(30f).onSizeChanged(onSize).onPlaced(onPlace))
        layOut()

        tree.root.removeAt(0, 1)
        layOut()
        tree.root.insertAt(0, watched)
        layOut()

        assertEquals(2, sizes.size, "the node came back and its handler never heard: $sizes")
        assertEquals(2, places.size)
    }

    @Test
    fun `a handler added to a node that has not changed still hears where it is`() {
        val watched = node("watched", Modifier.size(30f).onSizeChanged(onSize))
        layOut()

        watched.modifier = Modifier.size(30f)
        layOut()
        watched.modifier = Modifier.size(30f).onSizeChanged(onSize).onPlaced(onPlace)
        layOut()

        assertEquals(listOf(Size(30f, 30f), Size(30f, 30f)), sizes)
        assertEquals(listOf(Rect(0f, 0f, 30f, 30f)), places)
    }

    @Test
    fun `a different handler on a node that has not changed hears where it is`() {
        val watched = node("watched", Modifier.size(30f).onSizeChanged(onSize).onPlaced(onPlace))
        layOut()
        val otherSizes = mutableListOf<Size>()
        val otherPlaces = mutableListOf<Rect>()

        watched.modifier = Modifier.size(30f)
            .onSizeChanged { otherSizes += it }
            .onPlaced { otherPlaces += it.boundsInRoot }
        layOut()
        layOut()

        assertEquals(listOf(Size(30f, 30f)), otherSizes)
        assertEquals(listOf(Rect(0f, 0f, 30f, 30f)), otherPlaces)
        assertEquals(1, sizes.size, "the old handler was told again")
    }

    @Test
    fun `an unrelated change on a watched node tells it nothing`() {
        val watched = node("watched", Modifier.size(30f).onSizeChanged(onSize).onPlaced(onPlace))
        layOut()

        watched.modifier = Modifier.padding(2f).size(30f).onSizeChanged(onSize).onPlaced(onPlace)
        layOut()

        assertEquals(1, sizes.size)
        assertEquals(1, places.size)
    }

    @Test
    fun `a viewport's safe area is part of where it is`() {
        node("watched", Modifier.size(30f).onPlaced(onPlace))

        MeasurePass().run(
            tree.root,
            Viewport(Size(1280f, 720f), Size(1280f, 720f), safeArea = Padding(80f, 40f, 80f, 40f)),
        )

        assertEquals(80f, places.single().left, "reported before the root was put at the safe area: $places")
        assertEquals(40f, places.single().top)
    }
}
