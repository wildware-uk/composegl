package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Rect
import org.junit.jupiter.api.Tag
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A canvas's state, used the way a canvas uses it every frame, makes nothing (#252).
 *
 * A canvas resets one state at every `begin`, fills a kept one for every layer, and pushes and pops
 * every stack many times a frame. When the opacity and the tint were stacks of objects, each push
 * boxed a number; when a layer's state was new, each layer made four stacks. Run again by
 * `jvmAllocationTest` with escape analysis off, which counts what a phone counts.
 */
@Tag("allocation")
class CanvasStateAllocationTest {

    private val screen = Rect.of(0f, 0f, 640f, 360f)
    private val card = Rect.of(20f, 20f, 200f, 120f)
    private val inner = Rect.of(30f, 30f, 60f, 40f)

    @Test
    fun `pushing and popping every stack allocates nothing`() {
        val state = CanvasState(screen)
        val cycle = {
            state.pushClip(card)
            state.pushAlpha(0.5f)
            state.pushBlend(BlendMode.Additive)
            state.pushTint(Colour.rgb(0x8080FF))
            state.pushTransform(2f, 3f, 4f)
            state.pushClip(inner)
            state.popClip()
            state.popTransform()
            state.popTint()
            state.popBlend()
            state.popAlpha()
            state.popClip()
        }
        repeat(100) { cycle() }

        assertEquals(0L, leastOf { repeat(1_000) { cycle() } })
    }

    @Test
    fun `a frame's state is reset without allocating`() {
        val state = CanvasState(screen)
        val frame = {
            state.reset(0f, 0f, 640f, 360f)
            state.pushClip(card)
            state.popClip()
        }
        repeat(100) { frame() }

        assertEquals(0L, leastOf { repeat(1_000) { frame() } })
    }

    @Test
    fun `a layer's state is filled into a kept one without allocating`() {
        val outer = CanvasState(screen)
        outer.pushTint(Colour.rgb(0x80FF80))
        outer.pushTransform(1.5f, 10f, 10f)
        val kept = CanvasState(Rect.Zero)
        val layer = {
            val inside = outer.forLayer(card, into = kept)
            inside.pushClip(inner)
            inside.pushAlpha(0.5f)
            inside.popAlpha()
            inside.popClip()
        }
        repeat(100) { layer() }

        assertEquals(0L, leastOf { repeat(1_000) { layer() } })
    }

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** The fewest bytes [block] allocated in five runs: the JVM's own odd allocation lands in one. */
    private fun leastOf(block: () -> Unit): Long {
        var least = Long.MAX_VALUE
        repeat(5) {
            val before = threads.currentThreadAllocatedBytes
            block()
            least = minOf(least, threads.currentThreadAllocatedBytes - before)
        }
        return least
    }
}
