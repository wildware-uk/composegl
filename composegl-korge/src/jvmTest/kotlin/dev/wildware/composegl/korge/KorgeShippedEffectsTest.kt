package dev.wildware.composegl.korge

import dev.wildware.composegl.effects.Axis
import dev.wildware.composegl.effects.blur
import dev.wildware.composegl.effects.colourGrade
import dev.wildware.composegl.effects.dissolve
import dev.wildware.composegl.effects.outline
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.image.bitmap.Bitmap32
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The effects `composegl-effects` ships — a blur, an outline, a colour grade and a dissolve — compiled
 * by KorGE's context and drawn through the KorGE canvas. They are written against the public shader
 * API and nothing else, so this is the proof that the API means the same GLSL here as on LibGDX.
 */
class KorgeShippedEffectsTest {

    private val size = KorgeGl.size.toFloat()
    private val viewport = Viewport(design = Size(size, size), physical = Size(size, size), policy = ScalePolicy.Fit)

    private val red = Colour.rgb(0xFF0000)
    private val box = Rect.of(100f, 100f, 100f, 100f)

    /** A red box through [effects], innermost first, the way the draw pass chains a modifier's effects. */
    private fun through(vararg effects: ShaderEffect): Bitmap32 {
        val canvas = KorgeCanvas()
        try {
            return KorgeGl.picture { ctx ->
                canvas.begin(viewport, ctx)
                canvas.chain(effects.toList())
                canvas.end()
            }
        } finally {
            canvas.close()
        }
    }

    private fun KorgeCanvas.chain(effects: List<ShaderEffect>) {
        val effect = effects.lastOrNull() ?: return rect(box, red)
        val area = box.inset(-effect.bleed)
        val picture = checkNotNull(layer(area) { chain(effects.dropLast(1)) }) { "this driver gave us no layer" }
        drawLayer(picture, area, effect)
    }

    @Test
    fun `a blur softens the box's edge past where it was`() {
        val frame = through(blur(8f, Axis.Horizontal), blur(8f, Axis.Vertical))

        assertColour(Red, frame.at(150, 150), "the middle is still red")
        val edge = frame.at(97, 150).r
        assertTrue(edge > 0.05f && edge < 0.6f, "just outside the left edge is a little red: $edge")
        val inside = frame.at(103, 150).r
        assertTrue(inside > 0.4f && inside < 0.95f, "just inside it is not quite: $inside")
        assertColour(Black, frame.at(70, 150), "and well away from it is still black")
    }

    @Test
    fun `an outline rings the box in its colour`() {
        val frame = through(outline(Colour.White, width = 4f))

        assertColour(Red, frame.at(150, 150), "the box itself")
        assertColour(White, frame.at(98, 150), "a white ring just outside it")
        assertColour(Black, frame.at(85, 150), "and nothing further out")
    }

    @Test
    fun `a colour grade with no saturation turns red to grey`() {
        val frame = through(colourGrade(saturation = 0f))

        val pixel = frame.at(150, 150)
        assertTrue(pixel.r > 0.1f, "still lit: $pixel")
        assertTrue(abs(pixel.r - pixel.g) < 0.03f && abs(pixel.g - pixel.b) < 0.03f, "grey: $pixel")
        assertColour(Black, frame.at(90, 150), "outside the box")
    }

    @Test
    fun `a dissolve takes pixels away as it goes`() {
        fun lit(frame: Bitmap32): Float {
            var count = 0
            for (y in 100 until 200) for (x in 100 until 200) if (frame.at(x, y).r > 0.5f) count++
            return count / 10_000f
        }

        val whole = lit(through(dissolve(progress = 0f, scale = 12f)))
        val half = lit(through(dissolve(progress = 0.5f, scale = 12f)))
        val gone = lit(through(dissolve(progress = 1f, scale = 12f)))

        assertTrue(whole > 0.95f, "nothing gone at the start: $whole")
        assertTrue(half > 0.1f && half < 0.9f, "some gone half way: $half")
        assertTrue(gone < 0.05f, "all gone at the end: $gone")
    }
}
