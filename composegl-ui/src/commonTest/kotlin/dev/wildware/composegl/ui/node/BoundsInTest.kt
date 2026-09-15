package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.scale
import kotlin.test.Test
import kotlin.test.assertEquals

/** A node's rectangle in one of its ancestors' coordinates, which is how a popup finds its field. */
class BoundsInTest {

    private fun node(name: String, x: Float, y: Float, width: Float, height: Float) = UiNode(name).also {
        it.x = x
        it.y = y
        it.width = width
        it.height = height
    }

    @Test
    fun `the walk stops at the ancestor and leaves out its own corner`() {
        val root = node("root", 0f, 0f, 500f, 500f)
        val host = node("host", 40f, 30f, 400f, 400f)
        val panel = node("panel", 10f, 20f, 200f, 200f)
        val field = node("field", 5f, 6f, 100f, 20f)
        root.insertAt(0, host)
        host.insertAt(0, panel)
        panel.insertAt(0, field)

        assertEquals(Rect(15f, 26f, 115f, 46f), field.boundsIn(host))
        assertEquals(Rect(55f, 56f, 155f, 76f), field.boundsInRoot)
    }

    @Test
    fun `a scale between the two is folded in as it is drawn`() {
        val host = node("host", 0f, 0f, 400f, 400f)
        val panel = node("panel", 0f, 0f, 200f, 200f).also { it.modifier = Modifier.scale(0.5f) }
        val field = node("field", 100f, 100f, 100f, 20f)
        host.insertAt(0, panel)
        panel.insertAt(0, field)

        // Scaled about the panel's centre, 100 by 100: 100 away becomes 50, so 150.
        assertEquals(Rect(100f, 100f, 150f, 110f), field.boundsIn(host))
    }

    @Test
    fun `a node not under the ancestor gets the root's coordinates`() {
        val root = node("root", 0f, 0f, 500f, 500f)
        val a = node("a", 10f, 10f, 50f, 50f)
        val b = node("b", 100f, 100f, 50f, 50f)
        root.insertAt(0, a)
        root.insertAt(1, b)

        assertEquals(a.boundsInRoot, a.boundsIn(b))
    }
}
