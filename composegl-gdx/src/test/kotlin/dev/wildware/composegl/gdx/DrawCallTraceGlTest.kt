package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
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
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Draw calls blamed on nodes by the LibGDX canvas, on a real GPU.
 *
 * The headless tests prove the draw pass names the right node. These prove the batch really does
 * report each call it makes, for the reason it made it, so that the numbers in the overlay add up
 * to the draw call count beside them — and that the overlay's new lines reach the pixels.
 */
class DrawCallTraceGlTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val passThrough = ShaderEffect(
        ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
    )

    /** Real glyphs, in the family the toolkit's own skin asks for, so the overlay's words are drawn. */
    private fun fonts(): GdxFonts = GdxFonts().also {
        it.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(12, 14, 16))
    }

    /**
     * A red square with a green one inside it that starts adding its light once the button beside
     * it is clicked; the budget overlay in the bottom-right corner.
     *
     * No graph: this test reads the overlay's bottom rows to find the culprit line it just grew by,
     * and the frame-time graph would sit under those lines and be what those rows hold instead.
     */
    private fun screen(backend: GdxBackend, budget: FrameBudget): UiTest =
        uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend, budget = budget) {
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
                FrameBudgetOverlay(
                    budget,
                    Modifier.align(Alignment.BottomEnd).testTag("budget"),
                    graph = false,
                )
            }
        }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        ui.settle()
        // Again, now the overlay has recomposed with what the first frame published.
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    /** Pixels in the rows from [top] to [bottom] of [bounds] that are the overlay's red ink. */
    private fun Pixmap.redInk(bounds: Rect, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) for (x in bounds.left.toInt() until bounds.right.toInt()) {
            val pixel = at(x, y)
            if (pixel.r > 0.6f && pixel.g < 0.6f && pixel.b < 0.5f && pixel.r > pixel.g + 0.25f) count++
        }
        return count
    }

    @Test
    fun `a click that turns on a blend shows the node in the overlay and every call adds up`() = Gl.render {
        val backend = GdxBackend(fonts())
        val budget = FrameBudget(publishEveryMillis = 0L)
        val ui = screen(backend, budget)
        try {
            val before = frame(ui)
            val shortBounds = ui.node("budget").boundsInRoot
            val after: Pixmap
            try {
                assertTrue(budget.reading.culprits.none { it.reason == BatchBreak.Blend }, "${budget.reading.culprits}")
                val pixel = before.at(70, 70)
                assertTrue(pixel.r < 0.1f && pixel.g > 0.9f, "green painted over red before the click: $pixel")

                ui.click("toggle")
                after = frame(ui)

                // The overlay is pinned to the bottom corner, so it grows upwards and its new last line
                // sits in the rows where the draw call count was. Those had no red ink before.
                val tallBounds = ui.node("budget").boundsInRoot
                val grew = (tallBounds.height - shortBounds.height).toInt()
                assertTrue(grew > 0, "$shortBounds grew to $tallBounds")
                val lastLine = tallBounds.bottom.toInt() - grew - 8
                val bottom = tallBounds.bottom.toInt()
                assertEquals(0, before.redInk(tallBounds, lastLine, bottom), "no culprit line before the click")
                val ink = after.redInk(tallBounds, lastLine, bottom)
                assertTrue(ink > 20, "the culprit's line is written in red: $ink pixels")
            } finally {
                before.dispose()
            }

            try {
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

                val pixel = after.at(70, 70)
                assertTrue(pixel.r > 0.9f && pixel.g > 0.9f && pixel.b < 0.1f, "green added onto red is yellow: $pixel")
            } finally {
                after.dispose()
            }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `each flush says why it happened and the reasons add up to the draw calls`() = Gl.render {
        val canvas = GdxCanvas()
        val pixmap = Pixmap(2, 2, Pixmap.Format.RGBA8888).apply {
            setColor(Color.WHITE)
            fill()
        }
        val other = Texture(pixmap)
        val trace = DrawCallTrace()
        val white = Colour.rgb(0xFFFFFF)
        try {
            canvas.traceDrawCalls(trace)
            canvas.begin(viewport)

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

            // A box first, so the queue has something in it for the picture's texture to cut off.
            trace.node = UiNode("slot")
            canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
            trace.node = UiNode("icon")
            canvas.image(GdxTexture(other), Rect.of(20f, 20f, 40f, 40f))

            trace.node = UiNode("card")
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

            val blamed = trace.culprits(withEnd = true).map { "${it.name} ${it.reason} ${it.calls}" }
            assertEquals(canvas.drawCalls, trace.total, "one record per call: $blamed")
            assertTrue("panel Clip 2" in blamed, "$blamed")
            assertTrue("glow Blend 2" in blamed, "$blamed")
            assertTrue("icon Texture 1" in blamed, "$blamed")
            assertTrue(blamed.any { it.startsWith("card Layer") }, "$blamed")
            assertTrue("blur Shader 1" in blamed, "$blamed")
            assertTrue("outside the tree End 1" in blamed, "$blamed")
            assertTrue(canvas.tracesDrawCalls)

            // Switched off, the same frame blames nothing.
            canvas.traceDrawCalls(null)
            trace.clear()
            canvas.begin(viewport)
            canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
            canvas.pushBlend(BlendMode.Additive)
            canvas.rect(Rect.of(0f, 0f, 10f, 10f), white)
            canvas.popBlend()
            canvas.end()
            assertEquals(0, trace.total)
            assertEquals(2, canvas.drawCalls)
        } finally {
            canvas.dispose()
            other.dispose()
            pixmap.dispose()
        }
    }
}
