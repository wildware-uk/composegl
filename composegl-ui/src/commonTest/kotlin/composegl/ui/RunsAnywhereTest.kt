package composegl.ui

import composegl.ui.host.UiHost
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.run
import composegl.ui.layout.Viewport
import composegl.ui.geometry.Size
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One test that runs on every target, rather than only on a JVM.
 *
 * The rest of the suite is JVM-only and will stay that way while that is where the backends are.
 * This one exists to answer a different question: compiling for a target proves no forbidden API
 * was named, but it does not prove the toolkit *works* there. So this composes a tree, runs a
 * frame, measures it, and checks a number — on a JVM and on Linux native, from the same source.
 *
 * If it ever fails on one target and passes on the other, something underneath differs: the
 * dispatcher's lock, a rounding rule, a collection's iteration order.
 */
class RunsAnywhereTest {

    @Test
    fun `a tree composes and measures the same everywhere`() {
        val host = UiHost()
        try {
            host.setContent {
                LeafLayout(Modifier.size(40f), name = "box")
            }

            assertTrue(host.frame(0L), "the first frame builds the tree")
            assertFalse(host.frame(16_666_667L), "nothing changed, so nothing should be redrawn")

            MeasurePass().run(host.root, Viewport.oneToOne(Size(100f, 100f)))
            val box = host.root.children.single()
            assertEquals(40f, box.width)
            assertEquals(40f, box.height)
            assertEquals(1L, host.changedFrames)
        } finally {
            host.dispose()
        }
    }
}
