package dev.wildware.composegl.game

import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.layout.Box
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * What a screen full of world markers costs on a frame where every one of them has moved.
 *
 * The claim the layer makes is that following a hundred moving things is projection and placement
 * and nothing else — no recomposition, and nothing made and thrown away per frame. This is that
 * claim measured rather than asserted: it reads the bytes this thread allocated across a frame of
 * a hundred markers all chasing something.
 */
class WorldMarkerCostTest {

    private val host = UiHost()

    private var wall = 0L

    /** Where the hundred things are. Moved by the test; never state, so nothing recomposes. */
    private val xs = FloatArray(Markers) { (it % 20) * 60f + 20f }
    private val ys = FloatArray(Markers) { (it / 20) * 60f + 20f }

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame() {
        wall += 16_000_000L
        host.frame(wall)
        MeasurePass().run(host.root, Constraints.atMost(1280f, 720f))
    }

    @Test
    fun `a hundred markers chasing moving things allocate almost nothing a frame`() {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                WorldMarkerLayer(projection = WorldProjection.Screen) {
                    for (at in 0 until Markers) {
                        marker(key = at, position = { it.set(xs[at], ys[at]) }) {
                            Box(Modifier.size(40f, 16f)) { Text("$at") }
                        }
                    }
                }
            }
        }
        // Enough frames to settle: everything measured once, every slot projected once, the
        // ordering sorted from scratch. What is measured below is the steady state after that.
        repeat(6) { frame() }

        val before = allocatedBytes()
        repeat(20) {
            // Every one of them moves, every frame, which is the case this layer exists for.
            for (at in 0 until Markers) {
                xs[at] += 0.5f
                ys[at] += 0.25f
            }
            frame()
        }
        val perFrame = (allocatedBytes() - before) / 20

        // Loose on purpose, as the toolkit's own cost tests are: an order of magnitude is what is
        // being caught. A hundred markers measure at about fifteen bytes each over the same tree
        // laid out without a layer on it, and the last regression this caught was worth four times
        // that — three boxed floats a marker, from holding a marker's alpha in a MutableState.
        assertTrue(
            perFrame < 4_096,
            "a frame of $Markers moving markers allocated $perFrame bytes",
        )
    }

    /** What this thread has allocated so far. HotSpot only, which is what these tests run on. */
    private fun allocatedBytes(): Long {
        val beans = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return beans.getThreadAllocatedBytes(Thread.currentThread().threadId())
    }

    private companion object {
        const val Markers = 100
    }
}
