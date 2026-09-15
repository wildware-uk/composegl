package dev.wildware.composegl.korge

import dev.wildware.composegl.effects.Axis
import dev.wildware.composegl.effects.blur
import dev.wildware.composegl.effects.outline
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import korlibs.image.format.PNG
import korlibs.math.geom.Angle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * One picture of everything layers do through KorGE — a turned card, a tilted card, a card cut round,
 * a card through the shipped shaders, and a panel drawn into a texture and shown on a turned KorGE view
 * — for a person to look at. Written to `build/screenshots/korge-layers.png`, and to
 * `COMPOSEGL_KORGE_SHOTS` when that is set.
 */
class KorgeLayersShotTest {

    private val width = 760
    private val height = 460

    private val ink = Colour.rgb(0x111820)
    private val panel = Colour.rgb(0x1E2836)
    private val paper = Colour.rgb(0xE6EDF5)
    private val accent = Colour.rgb(0x4CC2FF)
    private val warm = Colour.rgb(0xFFB547)
    private val go = Colour.rgb(0x4ADE80)

    private val fonts = KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(14, 18)) }
    private val small = TextStyle(family = "default", size = 14f)
    private val title = TextStyle(family = "default", size = 18f)

    private fun KorgeCanvas.label(text: String, x: Float, y: Float, style: TextStyle = small, colour: Colour = paper) =
        text(fonts.measure(text, style), x, y, colour)

    private fun KorgeCanvas.card(at: Rect, name: String, colour: Colour) {
        rect(at, panel, corner = 14f)
        border(at, colour, width = 3f, corner = 14f)
        label(name, at.left + 16f, at.top + 16f, title)
        rect(Rect.of(at.left + 16f, at.top + 56f, at.width - 32f, 12f), colour, corner = 6f)
        rect(Rect.of(at.left + 16f, at.top + 80f, (at.width - 32f) * 0.6f, 12f), paper, corner = 6f)
        rect(Rect.of(at.left + 16f, at.top + 104f, (at.width - 32f) * 0.8f, 12f), paper.scaleAlpha(0.5f), corner = 6f)
        rect(Rect.of(at.left + 16f, at.bottom - 44f, 64f, 28f), colour, corner = 8f)
    }

    private fun KorgeCanvas.through(effects: List<ShaderEffect>, bounds: Rect, body: () -> Unit) {
        val effect = effects.lastOrNull() ?: return body()
        val area = bounds.inset(-effect.bleed)
        val picture = checkNotNull(layer(area) { through(effects.dropLast(1), bounds, body) })
        drawLayer(picture, area, effect)
    }

    @Test
    fun `a turned, a tilted, a cut and a shaded card, and a panel in the world`() {
        val backend = KorgeBackend(fonts)
        val canvas = backend.canvas
        val target = KorgeRenderTarget(260, 150)
        val view = KorgeRenderTargetView(target).apply {
            x = 420.0
            y = 262.0
            rotation = Angle.fromDegrees(10.0)
        }
        try {
            val shot = KorgeGl.picture(width, height) { ctx ->
                // The panel for the world, drawn into its own texture first.
                target.draw(canvas, ctx) {
                    canvas.rect(Rect.of(0f, 0f, 260f, 150f), Colour.rgb(0x0B1F14), corner = 12f)
                    canvas.border(Rect.of(0f, 0f, 260f, 150f), go, width = 3f, corner = 12f)
                    canvas.label("TERMINAL 07", 18f, 16f, title, go)
                    canvas.rect(Rect.of(18f, 56f, 200f, 10f), go, corner = 5f)
                    canvas.rect(Rect.of(18f, 76f, 140f, 10f), go.scaleAlpha(0.6f), corner = 5f)
                    val glow = Rect.of(18f, 98f, 110f, 34f)
                    canvas.through(listOf(blur(3f, Axis.Horizontal), blur(3f, Axis.Vertical)), glow) {
                        canvas.rect(glow, go, corner = 8f)
                    }
                    canvas.label("ONLINE", 36f, 106f, small, Colour.rgb(0x0B1F14))
                }

                canvas.begin(Viewport.oneToOne(Size(width.toFloat(), height.toFloat())), ctx)
                canvas.rect(Rect.of(0f, 0f, width.toFloat(), height.toFloat()), ink)

                val slots = listOf(20f, 205f, 390f, 575f).map { Rect.of(it, 50f, 165f, 180f) }
                canvas.label("turned", slots[0].left, 16f)
                canvas.label("tilted in depth", slots[1].left, 16f)
                canvas.label("cut round", slots[2].left, 16f)
                canvas.label("blur + outline shaders", slots[3].left, 16f)

                val turned = checkNotNull(canvas.layer(slots[0]) { canvas.card(slots[0], "Turned", accent) })
                canvas.drawLayer(turned, slots[0], degrees = -12f, pivotX = 0.5f, pivotY = 0.5f)

                val middle = slots[1].centre
                val tilt = Matrix4.translation(middle.x, middle.y) * Matrix4.perspective(260f) *
                    Matrix4.rotationY(42f) * Matrix4.rotationX(-12f) * Matrix4.translation(-middle.x, -middle.y)
                val tilted = checkNotNull(canvas.layer(slots[1]) { canvas.card(slots[1], "Tilted", warm) })
                canvas.drawLayer(tilted, slots[1], tilt)

                val round = Rect.of(slots[2].left, slots[2].top + 8f, 165f, 165f)
                val cut = checkNotNull(canvas.layer(round) { canvas.card(round, "Cut", go) })
                val outline = Shapes.Circle.outline(round.width, round.height)
                for (at in outline.indices) outline[at] += if (at % 2 == 0) round.left else round.top
                canvas.cutLayer(cut, round, outline)

                val shaded = Rect.of(slots[3].left + 8f, slots[3].top + 8f, 150f, 164f)
                canvas.through(listOf(blur(2f, Axis.Horizontal), blur(2f, Axis.Vertical), outline(warm, width = 4f)), shaded) {
                    canvas.card(shaded, "Shader", accent)
                }

                canvas.label("in-world UI: a screen drawn into a", 20f, 300f)
                canvas.label("KorgeRenderTarget, shown on a turned", 20f, 322f)
                canvas.label("KorGE view like any sprite", 20f, 344f)
                canvas.end()

                view.render(ctx)
                ctx.flush()
            }

            val lit = (0 until height).sumOf { y -> (0 until width).count { x -> shot.at(x, y).let { it.r + it.g + it.b } > 0.4f } }
            assertTrue(lit > 20_000, "the picture should be full of cards: lit $lit pixels")

            val bytes = PNG.encode(shot)
            File("build/screenshots/korge-layers.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
            System.getenv("COMPOSEGL_KORGE_SHOTS")?.let { dir ->
                File(dir, "korge-layers.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
            }
        } finally {
            target.close()
            backend.close()
        }
    }
}
