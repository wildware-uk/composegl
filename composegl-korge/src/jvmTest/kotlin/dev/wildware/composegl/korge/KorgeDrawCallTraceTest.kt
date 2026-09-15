package dev.wildware.composegl.korge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.debug.FrameBudgetOverlay
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
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Draw calls blamed on nodes by the KorGE canvas, on a real GPU: the batch reports each call it makes,
 * for the reason it made it, so the numbers in the overlay add up to the draw call count beside them.
 * The LibGDX backend's `DrawCallTraceGlTest`, for KorGE.
 */
class KorgeDrawCallTraceTest {

    private val passThrough = ShaderEffect(
        ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
    )

    private val size = KorgeGl.size.toFloat()
    private val viewport = Viewport(design = Size(size, size), physical = Size(size, size), policy = ScalePolicy.Fit)

    private fun backend() = KorgeBackend(
        KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(12, 13, 14, 16)) },
    )

    private fun screen(backend: KorgeBackend, budget: FrameBudget): UiTest =
        uiTest(Size(size, size), backend, budget = budget) {
            var glowing by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(20f, 20f).size(100f, 100f).background(Colour.rgb(0xFF0000))) {
                    Box(
                        Modifier.offset(25f, 25f).size(50f, 50f)
                            .then(if (glowing) Modifier.blend(BlendMode.Additive) else Modifier)
                            .background(Colour.rgb(0x00FF00))
                            .testTag("glow"),
                    )
                }
                Box(
                    Modifier.offset(140f, 20f).size(60f, 30f).background(Colour.rgb(0x3050FF))
                        .clickable { glowing = !glowing }.testTag("toggle"),
                )
                FrameBudgetOverlay(budget, Modifier.align(Alignment.BottomEnd).testTag("budget"))
            }
        }

    private fun render(ui: UiTest, backend: KorgeBackend): Bitmap32 = KorgeGl.picture { ctx ->
        backend.canvas.renderContext = ctx
        try {
            ui.render()
        } finally {
            backend.canvas.renderContext = null
        }
    }

    /** A frame, then another once the overlay has recomposed with what the first one published. */
    private fun frame(ui: UiTest, backend: KorgeBackend): Bitmap32 {
        render(ui, backend)
        ui.settle()
        return render(ui, backend)
    }

    private fun Bitmap32.redInk(bounds: Rect, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) for (x in bounds.left.toInt() until bounds.right.toInt()) {
            val pixel = at(x, y)
            if (pixel.r > 0.6f && pixel.g < 0.6f && pixel.b < 0.5f && pixel.r > pixel.g + 0.25f) count++
        }
        return count
    }

    @Test
    fun `a click that turns on a blend shows the node in the overlay and every call adds up`() {
        val backend = backend()
        val budget = FrameBudget(publishEveryMillis = 0L)
        val ui = screen(backend, budget)
        try {
            val before = frame(ui, backend)
            val shortBounds = ui.node("budget").boundsInRoot
            assertTrue(budget.reading.culprits.none { it.reason == BatchBreak.Blend }, "${budget.reading.culprits}")
            val green = before.at(70, 70)
            assertTrue(green.r < 0.1f && green.g > 0.9f, "green painted over red before the click: $green")

            ui.click("toggle")
            val after = frame(ui, backend)

            val tallBounds = ui.node("budget").boundsInRoot
            val grew = (tallBounds.height - shortBounds.height).toInt()
            assertTrue(grew > 0, "$shortBounds grew to $tallBounds")
            val lastLine = tallBounds.bottom.toInt() - grew - 8
            val bottom = tallBounds.bottom.toInt()
            assertEquals(0, before.redInk(tallBounds, lastLine, bottom), "no culprit line before the click")
            val ink = after.redInk(tallBounds, lastLine, bottom)
            assertTrue(ink > 20, "the culprit's line is written in red: $ink pixels")

            val reading = budget.reading
            val top = reading.culprits.first()
            assertTrue(top.node === ui.node("glow"), "blamed on ${top.name}: ${reading.culprits}")
            assertEquals(BatchBreak.Blend, top.reason)
            assertEquals(2, top.calls)
            assertEquals(
                reading.drawCalls,
                reading.culprits.sumOf { it.calls } + 1,
                "every call but the frame's last is blamed on something: ${reading.culprits}",
            )

            val yellow = after.at(70, 70)
            assertTrue(yellow.r > 0.9f && yellow.g > 0.9f && yellow.b < 0.1f, "green added onto red is yellow: $yellow")
        } finally {
            ui.close()
            backend.close()
        }
    }

    @Test
    fun `each flush says why it happened and the reasons add up to the draw calls`() {
        val canvas = KorgeCanvas()
        val other = KorgeTexture(Bitmap32(2, 2, premultiplied = false).also { b -> for (y in 0..1) for (x in 0..1) b[x, y] = RGBA(255, 255, 255, 255) })
        val trace = DrawCallTrace()
        val white = Colour.rgb(0xFFFFFF)
        try {
            canvas.traceDrawCalls(trace)
            KorgeGl.picture { ctx ->
                canvas.begin(viewport, ctx)

                trace.node = UiNode("panel")
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                canvas.pushClip(Rect.of(0f, 0f, 200f, 200f))
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                canvas.popClip()

                trace.node = UiNode("glow")
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                canvas.pushBlend(BlendMode.Additive)
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                canvas.popBlend()

                trace.node = UiNode("slot")
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                trace.node = UiNode("icon")
                canvas.image(other, Rect.of(20f, 20f, 40f, 40f))

                trace.node = UiNode("hatch")
                canvas.raw { }

                trace.node = UiNode("card")
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                val picture = canvas.layer(Rect.of(0f, 0f, 50f, 50f)) {
                    canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                }!!
                canvas.drawLayer(picture, Rect.of(0f, 0f, 50f, 50f))

                trace.node = UiNode("blur")
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                val blurred = canvas.layer(Rect.of(0f, 0f, 50f, 50f)) {
                    canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                }!!
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                canvas.drawLayer(blurred, Rect.of(0f, 0f, 50f, 50f), passThrough)

                trace.node = null
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                canvas.end()
            }

            val blamed = trace.culprits(withEnd = true).map { "${it.name} ${it.reason} ${it.calls}" }
            assertEquals(canvas.drawCalls, trace.total, "one record per call: $blamed")
            assertTrue("panel Clip 2" in blamed, "$blamed")
            assertTrue("glow Blend 2" in blamed, "$blamed")
            assertTrue("icon Texture 1" in blamed, "$blamed")
            assertTrue(blamed.any { it.startsWith("card Layer") }, "$blamed")
            assertTrue("blur Shader 1" in blamed, "$blamed")
            assertTrue("hatch Raw 1" in blamed, "$blamed")
            assertTrue("outside the tree End 1" in blamed, "$blamed")
            assertTrue(canvas.tracesDrawCalls)

            // Switched off, the same frame blames nothing.
            canvas.traceDrawCalls(null)
            trace.clear()
            KorgeGl.picture { ctx ->
                canvas.begin(viewport, ctx)
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                canvas.pushBlend(BlendMode.Additive)
                canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
                canvas.popBlend()
                canvas.end()
            }
            assertEquals(0, trace.total)
            assertEquals(2, canvas.drawCalls)
        } finally {
            canvas.close()
        }
    }
}
