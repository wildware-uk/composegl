package dev.wildware.composegl.webgl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.debug.FrameBudgetOverlay
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Draw calls blamed on nodes by the WebGL canvas, in a real browser: the desktop backends' tests
 * asked again of a batch that shares none of their code, so the overlay means the same thing here.
 */
class WebGlDrawCallTraceTest {

    private val box = Rect.of(0f, 0f, 10f, 10f)
    private val white = Colour.rgb(0xFFFFFF)
    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)

    private val passThrough = ShaderEffect(
        ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
    )

    private var frameNanos = 0L

    private fun frames(ui: BrowserUi, count: Int = 4) = repeat(count) {
        frameNanos += 16_666_667L
        ui.frame(frameNanos)
    }

    @Test
    fun `a click that turns on a blend names the node and every call adds up`() = runTest(timeout = 2.minutes) {
        val element = pageCanvas(320, 240)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        // The renderer's budget does not exist until the page does, so it reaches the overlay as state.
        var shown by mutableStateOf<FrameBudget?>(null)
        val ui = BrowserUi(backend, Size(320f, 240f)) {
            var glowing by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(20f, 20f).size(100f, 100f).background(red)) {
                    Box(
                        Modifier.offset(25f, 25f).size(50f, 50f)
                            .then(if (glowing) Modifier.blend(BlendMode.Additive) else Modifier)
                            .background(green)
                            .testTag("glow"),
                    )
                }
                Box(Modifier.offset(200f, 20f).size(60f, 30f).background(Colour.rgb(0x3050FF)).clickable { glowing = !glowing })
                shown?.let { FrameBudgetOverlay(it, Modifier.align(Alignment.BottomEnd)) }
            }
        }
        try {
            val budget = ui.renderer.budget
            shown = budget
            frames(ui)
            assertTrue(budget.reading.culprits.none { it.reason == BatchBreak.Blend }, "${budget.reading.culprits}")
            val before = readFrame(backend.gl, 320, 240)[70 * 320 + 70]
            assertTrue(red(before) < 30 && green(before) > 230, "green over red before the click")

            click(element, 230.0, 35.0)
            // Published on the next frame rather than a quarter of a second from now.
            budget.reset()
            frames(ui)

            val reading = budget.reading
            val top = reading.culprits.first()
            assertEquals("glow", top.node?.testTag, "${reading.culprits}")
            assertEquals(BatchBreak.Blend, top.reason)
            assertEquals(2, top.calls, "once going in, once for its own glow")
            assertEquals(reading.drawCalls, reading.culprits.sumOf { it.calls } + 1, "all but the last: ${reading.culprits}")

            val after = readFrame(backend.gl, 320, 240)[70 * 320 + 70]
            assertTrue(red(after) > 230 && green(after) > 230 && blue(after) < 30, "green added onto red is yellow")
        } finally {
            ui.close()
            backend.close()
            element.remove()
        }
    }

    @Test
    fun `each flush says why it happened and the reasons add up to the draw calls`() = runTest(timeout = 2.minutes) {
        val element = pageCanvas(256, 256)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        val canvas = backend.canvas
        val gl = backend.gl
        val other = WebGlTexture.rgba(gl, 1, 1, byteArrayOf(-1, -1, -1, -1), smooth = false)
        val viewport = Viewport(Size(256f, 256f), Size(256f, 256f), ScalePolicy.Fit)
        val trace = DrawCallTrace()
        try {
            gl.clearColor(0f, 0f, 0f, 1f)
            gl.clear(GL.COLOR_BUFFER_BIT)
            canvas.traceDrawCalls(trace)
            canvas.begin(viewport)

            trace.node = UiNode("panel")
            canvas.rect(box, white)
            canvas.pushClip(Rect.of(0f, 0f, 200f, 200f))
            canvas.rect(box, white)
            canvas.popClip()

            // Red, then green added onto it: a traced frame is still drawn properly.
            trace.node = UiNode("glow")
            canvas.rect(Rect.of(100f, 100f, 150f, 150f), red)
            canvas.pushBlend(BlendMode.Additive)
            canvas.rect(Rect.of(100f, 100f, 150f, 150f), green)
            canvas.popBlend()

            // A box first, so the queue has something in it for the picture's texture to cut off.
            trace.node = UiNode("slot")
            canvas.rect(box, white)
            trace.node = UiNode("icon")
            canvas.image(other, Rect.of(20f, 20f, 40f, 40f))

            trace.node = UiNode("card")
            val picture = canvas.layer(Rect.of(0f, 0f, 50f, 50f)) { canvas.rect(box, white) }!!
            canvas.drawLayer(picture, Rect.of(0f, 0f, 50f, 50f))

            trace.node = UiNode("blur")
            canvas.rect(box, white)
            val blurred = canvas.layer(Rect.of(0f, 0f, 50f, 50f)) { canvas.rect(box, white) }!!
            canvas.rect(box, white)
            canvas.drawLayer(blurred, Rect.of(0f, 0f, 50f, 50f), passThrough)

            trace.node = UiNode("sprites")
            canvas.rect(box, white)
            canvas.raw { }

            trace.node = null
            canvas.rect(box, white)
            canvas.end()

            val blamed = trace.culprits(withEnd = true).map { "${it.name} ${it.reason} ${it.calls}" }
            assertEquals(canvas.drawCalls, trace.total, "one record per call: $blamed")
            assertTrue("panel Clip 2" in blamed, "$blamed")
            assertTrue("glow Blend 2" in blamed, "$blamed")
            assertTrue("icon Texture 1" in blamed, "$blamed")
            assertTrue(blamed.any { it.startsWith("card Layer") }, "$blamed")
            assertTrue("blur Shader 1" in blamed, "$blamed")
            assertTrue("sprites Raw 1" in blamed, "$blamed")
            assertTrue("outside the tree End 1" in blamed, "$blamed")
            assertTrue(canvas.tracesDrawCalls)

            val pixel = readFrame(gl, 256, 256)[125 * 256 + 125]
            assertTrue(red(pixel) > 230 && green(pixel) > 230, "red plus green")

            // Switched off, the same kind of frame blames nothing.
            canvas.traceDrawCalls(null)
            trace.clear()
            canvas.begin(viewport)
            canvas.rect(box, white)
            canvas.pushBlend(BlendMode.Additive)
            canvas.rect(box, white)
            canvas.popBlend()
            canvas.end()
            assertEquals(0, trace.total)
            assertEquals(2, canvas.drawCalls)
        } finally {
            other.close()
            backend.close()
            element.remove()
        }
    }
}
