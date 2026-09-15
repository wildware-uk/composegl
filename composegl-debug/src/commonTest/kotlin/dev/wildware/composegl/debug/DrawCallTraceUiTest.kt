package dev.wildware.composegl.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Draw calls blamed on nodes, in a composed screen driven with a real click.
 *
 * The canvas here batches the way the GL ones do, in miniature: one queue, cut by a texture that is
 * not the one it was drawing from, by a blend and by a clip — and only when something is queued.
 * That is enough to prove the half of the feature that lives in the toolkit: the draw pass names
 * the right node at the moment a backend cuts, and the renderer, the budget and the overlay carry
 * that through to the screen. The GL tests prove the backends' half.
 */
class DrawCallTraceUiTest {

    @Test
    fun `a click that turns on a blend puts that node at the top of the overlay`() {
        val budget = FrameBudget(publishEveryMillis = 0L)
        val backend = BatchingBackend()
        screen(budget, backend).use { ui ->
            rendered(ui)
            assertFalse(budget.reading.culprits.any { it.reason == BatchBreak.Blend }, "nothing blends yet")
            assertFalse(ui.text("budget").contains("#glow"))

            ui.click("toggle")
            rendered(ui)

            val top = budget.reading.culprits.first()
            assertTrue(top.node === ui.node("glow"), "blamed on ${top.name}")
            assertEquals(BatchBreak.Blend, top.reason)
            assertEquals(2, top.calls, "once cutting what came before it, once for its own glow")

            val overlay = ui.texts("budget")
            val at = overlay.indexOf("  ${ui.node("glow").name}#glow")
            assertTrue(at >= 0, "the overlay names the node: $overlay")
            assertEquals("blend 2", overlay[at + 1], "and says why and how many: $overlay")

            ui.click("toggle")
            rendered(ui)
            assertFalse(ui.text("budget").contains("#glow"), "gone once the blend is")
        }
    }

    @Test
    fun `every draw call in the frame is blamed on something`() {
        val budget = FrameBudget(publishEveryMillis = 0L)
        val backend = BatchingBackend()
        screen(budget, backend).use { ui ->
            ui.click("toggle")
            rendered(ui)

            val reading = budget.reading
            assertEquals(
                listOf(
                    Triple("glow", BatchBreak.Blend, 2),
                    Triple("panel", BatchBreak.Clip, 2),
                ),
                reading.culprits.map { Triple(it.node?.testTag, it.reason, it.calls) },
            )
            assertEquals(reading.drawCalls - 1, reading.culprits.sumOf { it.calls }, "all but the frame's own last call")
        }
    }

    @Test
    fun `a picture from its own texture is blamed on the node that drew it`() {
        val budget = FrameBudget(publishEveryMillis = 0L)
        screen(budget, BatchingBackend()).use { ui ->
            rendered(ui)
            val texture = budget.reading.culprits.single { it.reason == BatchBreak.Texture }
            assertTrue(texture.node === ui.node("icon"), "blamed on ${texture.name}")
            assertEquals("${ui.node("icon").name}#icon", texture.name)
        }
    }

    @Test
    fun `a clip coming off after its children is the parent's and not the last child's`() {
        val budget = FrameBudget(publishEveryMillis = 0L)
        screen(budget, BatchingBackend()).use { ui ->
            rendered(ui)
            val clips = budget.reading.culprits.filter { it.reason == BatchBreak.Clip }
            assertEquals(listOf("panel" to 2), clips.map { it.node?.testTag to it.calls })
        }
    }

    @Test
    fun `switched off the canvas is told nothing and nothing is blamed`() {
        val budget = FrameBudget(publishEveryMillis = 0L)
        val backend = BatchingBackend()
        screen(budget, backend).use { ui ->
            rendered(ui)
            assertTrue(backend.canvas.tracing === budget.trace)

            budget.isOn = false
            ui.render()
            assertNull(backend.canvas.tracing, "a switched-off budget costs the canvas nothing")
            assertEquals(0, budget.trace.total)
            assertTrue(budget.reading.culprits.isEmpty())
        }
    }

    @Test
    fun `a still screen with the culprits showing is not redrawn`() {
        val budget = FrameBudget(publishEveryMillis = 60_000L)
        val backend = BatchingBackend()
        screen(budget, backend).use { ui ->
            ui.click("toggle")
            // So the next frame publishes, and the one after shows it.
            budget.reset()
            rendered(ui)
            assertTrue(ui.text("budget").contains("#glow"), "showing the culprit: ${ui.texts("budget")}")
            val shown = budget.reading

            repeat(10) { frame ->
                assertFalse(ui.render(), "frame $frame redrew a still screen")
                assertTrue(backend.canvas.drawCalls > 1, "and it is still cutting the batch, traced")
            }
            assertTrue(budget.reading === shown, "nothing was published behind the overlay's back")
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * A title, a button, a box that glows once the button is clicked, a picture from its own
     * texture, and a clipped panel with a slot in it; the budget overlay in the corner.
     */
    private fun screen(budget: FrameBudget, backend: BatchingBackend): UiTest =
        uiTest(Size(400f, 400f), backend, budget = budget) {
            var glowing by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Column {
                    Text("Inventory")
                    Box(Modifier.size(80f, 30f).background(Colour.rgb(0x3050FF)).clickable { glowing = !glowing }.testTag("toggle"))
                    Box(
                        Modifier.size(40f, 40f)
                            .then(if (glowing) Modifier.blend(BlendMode.Additive) else Modifier)
                            .background(Colour.rgb(0x20FF20))
                            .testTag("glow"),
                    )
                    Box(Modifier.size(40f, 40f).drawBehind { rect -> image(Picture, rect) }.testTag("icon"))
                    Box(Modifier.size(40f, 40f).clip().testTag("panel")) {
                        Box(Modifier.size(20f, 20f).background(Colour.White).testTag("slot"))
                    }
                }
                FrameBudgetOverlay(budget, Modifier.align(Alignment.TopEnd).testTag("budget"))
            }
        }

    /** Two frames: one to publish the reading, one for the overlay to show it. */
    private fun rendered(ui: UiTest) {
        ui.render()
        ui.settle()
        ui.render()
    }

    private class BatchingBackend(
        private val headless: HeadlessBackend = HeadlessBackend(Rect.of(0f, 0f, 400f, 400f)),
    ) : UiBackend by headless {
        override val canvas = BatchingCanvas()
    }

    /** One queue and one texture, cut when the next thing needs something the queue does not share. */
    private class BatchingCanvas(
        private val inner: RecordingCanvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 400f)),
    ) : UiCanvas by inner {

        var tracing: DrawCallTrace? = null
            private set
        private var calls = 0
        private var queued = false

        /** Null is the shared atlas that boxes and text come from. */
        private var texture: TextureHandle? = null

        private fun cut(reason: BatchBreak) {
            if (!queued) return
            calls++
            tracing?.record(reason)
            queued = false
        }

        private fun from(next: TextureHandle?) {
            if (next !== texture) {
                cut(BatchBreak.Texture)
                texture = next
            }
            queued = true
        }

        override fun traceDrawCalls(trace: DrawCallTrace?) {
            tracing = trace
        }

        override val tracesDrawCalls: Boolean get() = true
        override val drawCalls: Int get() = calls

        override fun begin(viewport: Viewport) {
            calls = 0
            queued = false
            texture = null
            inner.begin(viewport)
        }

        override fun end() {
            cut(BatchBreak.End)
            inner.end()
        }

        override fun rect(rect: Rect, colour: Colour, corner: Float) {
            from(null)
            inner.rect(rect, colour, corner)
        }

        override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) {
            from(null)
            inner.text(layout, x, y, colour)
        }

        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
            from(texture)
            inner.image(texture, destination, tint, source)
        }

        override fun supports(mode: BlendMode): Boolean = true

        override fun pushBlend(mode: BlendMode) {
            cut(BatchBreak.Blend)
            inner.pushBlend(mode)
        }

        override fun popBlend() {
            cut(BatchBreak.Blend)
            inner.popBlend()
        }

        override fun pushClip(rect: Rect) {
            cut(BatchBreak.Clip)
            inner.pushClip(rect)
        }

        override fun popClip() {
            cut(BatchBreak.Clip)
            inner.popClip()
        }
    }

    private companion object {
        val Picture = FakeTexture(16, 16)
    }
}
