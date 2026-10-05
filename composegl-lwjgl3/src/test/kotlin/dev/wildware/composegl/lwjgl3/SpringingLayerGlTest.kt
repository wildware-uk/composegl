package dev.wildware.composegl.lwjgl3

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import dev.wildware.composegl.effects.blur
import dev.wildware.composegl.render.gl.GlBytes
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.backend.InMemoryClipboard
import dev.wildware.composegl.ui.backend.MapTextureSource
import dev.wildware.composegl.ui.backend.RecordingSoftKeyboard
import dev.wildware.composegl.ui.backend.SoftKeyboard
import dev.wildware.composegl.ui.backend.TextureSource
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.rotate
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import kotlin.math.abs
import dev.wildware.composegl.render.gl.Gl as GlBinding

/**
 * A turned card that springs bigger when the game says so, on a real context: its picture changes
 * size every frame of the spring, and the canvas makes a framebuffer every few frames at most, not
 * one a frame. Counted at the `Gl` boundary, as the GPU profile in #242 counted Mega Merge's draft.
 *
 * The game's own state starts the spring rather than a click, because a click in `uiTest` settles
 * the screen and the spring would play out before a single frame of it was drawn.
 */
class SpringingLayerGlTest {

    /**
     * The window's own binding, counting the framebuffers made, the completeness checks that wait on
     * the driver, and the bytes of texture alive.
     */
    private class CountingGl(private val gl: GlBinding) : GlBinding by gl {
        var made = 0
        var checked = 0

        /** Every texture made, width by height, in order. */
        val shapes = ArrayList<Pair<Int, Int>>()
        private var bound = 0
        private val bytes = HashMap<Int, Long>()
        val alive: Long get() = bytes.values.sum()

        override fun createFramebuffer(): Int = gl.createFramebuffer().also { made++ }

        override fun checkFramebufferStatus(target: Int): Int = gl.checkFramebufferStatus(target).also { checked++ }

        override fun bindTexture(target: Int, texture: Int) {
            bound = texture
            gl.bindTexture(target, texture)
        }

        override fun texImage2D(
            target: Int,
            level: Int,
            internalFormat: Int,
            width: Int,
            height: Int,
            format: Int,
            type: Int,
            pixels: GlBytes?,
        ) {
            bytes[bound] = width * height * 4L
            shapes += width to height
            gl.texImage2D(target, level, internalFormat, width, height, format, type, pixels)
        }

        override fun deleteTexture(texture: Int) {
            bytes.remove(texture)
            gl.deleteTexture(texture)
        }
    }

    private class CountingBackend(gl: GlBinding) : UiBackend {
        override val canvas = GlCanvas(gl = gl)
        override val fonts = StbFonts()
        override val clipboard: Clipboard = InMemoryClipboard()
        override val softKeyboard: SoftKeyboard = RecordingSoftKeyboard()
        override val textures: TextureSource = MapTextureSource()
    }

    /**
     * A turned, blurred card at a place between pixels, grown to 1.4 times its size while [big] says
     * so: its size rather than a scale springs, so its turn's picture and both of its blur's grow and
     * shrink with it.
     */
    private fun card(backend: UiBackend, big: MutableState<Boolean>) =
        uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            val swell by animateFloatAsState(if (big.value) 1.4f else 1f)
            Box(
                Modifier.offset(100.3f, 90.6f).size(112f * swell, 112f * swell)
                    .rotate(9f)
                    .blur(8f)
                    .background(Colour.Red),
            ) {
                Box(Modifier.size(56f, 56f).background(Colour.Blue))
                Box(Modifier.offset(56f, 56f).size(56f, 56f).background(Colour.Green))
            }
        }

    private fun frame(ui: UiTest): IntArray {
        Gl.gl.clearColor(0f, 0f, 0f, 1f)
        Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Gl.readPixels(Gl.size, Gl.size)
    }

    @Test
    fun `a card that springs back down into the corners of bigger pictures looks as it does in its own size`() = Gl.render {
        // At rest the card and its blur's bleed are 128 a side, a whole step: a fresh canvas draws
        // every picture of it at exactly its size, as before pictures were shared across sizes.
        val freshGl = CountingGl(Gl.gl)
        val fresh = CountingBackend(freshGl)
        val atRest = card(fresh, mutableStateOf(false))
        // This one starts grown, so the only pictures it has are bigger, and springs back down.
        val sprungGl = CountingGl(Gl.gl)
        val sprung = CountingBackend(sprungGl)
        val big = mutableStateOf(true)
        val springing = card(sprung, big)
        try {
            frame(atRest)
            val expected = frame(atRest)

            frame(springing)
            val before = sprungGl.made
            big.value = false
            var last = IntArray(0)
            repeat(120) { last = frame(springing) }

            assertTrue(128 to 128 in freshGl.shapes, "the fresh canvas's pictures are the card's own size: ${freshGl.shapes}")
            assertTrue(128 to 128 !in sprungGl.shapes, "the springing canvas never made one that size: ${sprungGl.shapes}")
            assertEquals(before, sprungGl.made, "springing down, every picture fits one it already has")
            val worst = expected.indices.maxOf { at ->
                val a = expected[at]
                val b = last[at]
                maxOf(
                    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
                    abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
                    abs((a and 0xFF) - (b and 0xFF)),
                )
            }
            val differ = expected.indices.count { expected[it] != last[it] }
            println("sprung back down: $differ pixels differ, worst $worst")
            assertTrue(worst <= 1, "the card drawn from corners of bigger pictures is the card drawn from its own: worst $worst")
        } finally {
            listOf(atRest, springing).forEach { it.close() }
            listOf(fresh, sprung).forEach {
                it.canvas.close()
                it.fonts.close()
            }
        }
    }

    @Test
    fun `a turned card springing bigger makes a framebuffer every few frames - not every frame`() = Gl.render {
        val gl = CountingGl(Gl.gl)
        val backend = CountingBackend(gl)
        // What a game holds: a seat swells when its card is dealt.
        val dealt = mutableStateOf(false)
        val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            val swell by animateFloatAsState(if (dealt.value) 1.6f else 1f)
            Box(
                Modifier.offset(120f, 100f).size(110f, 150f)
                    .rotate(8f)
                    .scale(swell)
                    .background(Colour.Red)
                    .testTag("card"),
            )
        }
        try {
            fun frame() {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                ui.render()
            }
            frame()
            val before = gl.made

            dealt.value = true
            val counts = arrayListOf(before)
            var most = gl.alive
            repeat(60) {
                frame()
                counts += gl.made
                most = maxOf(most, gl.alive)
            }

            val made = gl.made - before
            val changed = counts.zipWithNext().count { (a, b) -> b != a }
            println(
                "springing card: $made framebuffers in 60 frames, on $changed of them; ${gl.checked} completeness " +
                    "checks in all; at most ${most / 1024} KB of texture alive",
            )
            assertTrue(made in 1..4, "$made framebuffers made while the card sprang, on $changed frames")
        } finally {
            ui.close()
            backend.canvas.close()
            backend.fonts.close()
        }
    }
}
