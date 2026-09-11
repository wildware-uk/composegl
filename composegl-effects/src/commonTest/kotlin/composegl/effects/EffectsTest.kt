package composegl.effects

import composegl.ui.effect.ShaderEffect
import composegl.ui.effect.Uniform
import composegl.ui.graphics.Colour
import composegl.ui.modifier.EffectElement
import composegl.ui.modifier.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What can be checked without a GPU: the numbers handed to each shader, and whether each shader
 * actually declares what it is being handed.
 *
 * The picture itself is checked by the goldens in `composegl-testing`, which need a screen. These
 * catch the mistakes that produce a shader that compiles and draws nothing — a uniform renamed on
 * one side of the line and not the other — which is the failure that costs an afternoon.
 */
class EffectsTest {

    private fun effectsOf(modifier: Modifier): List<ShaderEffect> =
        modifier.fold(emptyList()) { found, element ->
            if (element is EffectElement) found + element.effect else found
        }

    @Test
    fun `every uniform an effect passes is declared by its own shader`() {
        val everything = listOf(
            blur(6f, Axis.Horizontal),
            blur(6f, Axis.Vertical),
            outline(Colour.White, width = 2f),
            colourGrade(),
            dissolve(progress = 0.5f),
        )
        for (effect in everything) {
            for (name in effect.uniforms.keys) {
                assertTrue(
                    effect.source.fragment.contains(Regex("""uniform\s+\w+\s+$name\s*;""")),
                    "${effect.source.name} is passed $name, which its shader never declares",
                )
            }
        }
    }

    @Test
    fun `every shader has a main`() {
        val everything = listOf(
            blur(1f, Axis.Horizontal),
            outline(Colour.White),
            colourGrade(),
            dissolve(progress = 0f),
        )
        for (effect in everything) {
            assertTrue(
                effect.source.fragment.contains("void main()"),
                "${effect.source.name} has no main",
            )
        }
    }

    @Test
    fun `a blur reaches its own radius past the widget`() {
        assertEquals(8f, blur(8f, Axis.Horizontal).bleed)
    }

    @Test
    fun `the two blur passes differ only in direction`() {
        val across = blur(5f, Axis.Horizontal)
        val down = blur(5f, Axis.Vertical)
        assertEquals(across.source, down.source, "both passes are the same shader, compiled once")
        assertEquals(Uniform.Vector2(1f, 0f), across.uniforms["u_direction"])
        assertEquals(Uniform.Vector2(0f, 1f), down.uniforms["u_direction"])
    }

    @Test
    fun `the blur modifier is both passes`() {
        val effects = effectsOf(Modifier.blur(4f))
        assertEquals(2, effects.size)
        assertEquals(Uniform.Vector2(1f, 0f), effects[0].uniforms["u_direction"])
        assertEquals(Uniform.Vector2(0f, 1f), effects[1].uniforms["u_direction"])
    }

    @Test
    fun `a negative blur is a mistake rather than a picture`() {
        assertFailsWith<IllegalArgumentException> { blur(-1f, Axis.Horizontal) }
    }

    @Test
    fun `an outline reaches its own thickness past the widget`() {
        val effect = outline(Colour.rgb(0xFF0000), width = 3f)
        assertEquals(3f, effect.bleed)
        assertEquals(Uniform.Vector4(1f, 0f, 0f, 1f), effect.uniforms["u_outline"])
        assertEquals(Uniform.Number(3f), effect.uniforms["u_width"])
    }

    @Test
    fun `the outline modifier is one effect`() {
        assertEquals(1, effectsOf(Modifier.outline(Colour.White)).size)
    }

    @Test
    fun `a grade with nothing set changes nothing`() {
        val effect = colourGrade()
        assertEquals(Uniform.Number(1f), effect.uniforms["u_brightness"])
        assertEquals(Uniform.Number(1f), effect.uniforms["u_contrast"])
        assertEquals(Uniform.Number(1f), effect.uniforms["u_saturation"])
        assertEquals(Uniform.of(Colour.White), effect.uniforms["u_tint"])
    }

    @Test
    fun `an effect that reads one pixel needs no margin`() {
        assertEquals(0f, colourGrade(brightness = 0.5f).bleed)
        assertEquals(0f, dissolve(progress = 0.5f).bleed)
    }

    @Test
    fun `a dissolve past either end is held at the end`() {
        assertEquals(Uniform.Number(0f), dissolve(progress = -3f).uniforms["u_progress"])
        assertEquals(Uniform.Number(1f), dissolve(progress = 9f).uniforms["u_progress"])
    }

    @Test
    fun `a dissolve edge is never quite hard because the shader divides by it`() {
        assertEquals(Uniform.Number(0.001f), dissolve(progress = 0.5f, softness = 0f).uniforms["u_softness"])
    }

    @Test
    fun `a dissolve with no pattern size is a mistake rather than a division by zero`() {
        assertFailsWith<IllegalArgumentException> { dissolve(progress = 0.5f, scale = 0f) }
    }

    @Test
    fun `an effect whose numbers have not changed is the same effect`() {
        // What stops a modifier chain from looking new on every recomposition, which would throw
        // away the compiled shader and the pooled picture behind it every frame.
        assertEquals(blur(4f, Axis.Vertical), blur(4f, Axis.Vertical))
        assertEquals(dissolve(progress = 0.25f), dissolve(progress = 0.25f))
    }
}
