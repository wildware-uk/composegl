package dev.wildware.composegl.korge

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.text.scaledTextSizes
import dev.wildware.composegl.ui.widget.ProvideTextScale
import dev.wildware.composegl.ui.widget.Text
import korlibs.image.bitmap.Bitmap32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The player's text size setting, drawn through KorGE and judged by the pixels, as the LibGDX
 * backend's `TextScaleGlTest` judges it.
 *
 * The toolkit's own tests prove the boxes grow. These prove the letters are rasterised at the bigger
 * size rather than stretched: text at 16 under a scale of two is pixel for pixel what a style asking
 * for 32 draws, which is only true if both came from glyphs made at 32.
 */
class KorgeTextScaleTest {

    private val size = KorgeGl.size.toFloat()
    private val white = Colour.rgb(0xFFFFFF)

    private fun style(size: Float) = TextStyle(family = "test", size = size)

    /** Every size the tests here draw at: 16 at each scale they use. */
    private fun fonts() = KorgeFonts().also {
        it.registerTrueType("test", TestFonts.dejaVu(), scaledTextSizes(listOf(16), listOf(1f, 1.25f, 1.5f, 2f)))
    }

    private fun open(backend: KorgeBackend, content: @Composable () -> Unit): UiTest = uiTest(Size(size, size), backend, content = content)

    private fun <T> UiTest.using(block: (UiTest) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun frame(ui: UiTest, backend: KorgeBackend): Bitmap32 = KorgeGl.picture { ctx ->
        backend.canvas.renderContext = ctx
        try {
            ui.render()
        } finally {
            backend.canvas.renderContext = null
        }
    }

    private fun same(a: Bitmap32, b: Bitmap32) = (0 until a.height).all { y -> (0 until a.width).all { x -> a[x, y] == b[x, y] } }

    /** Rows holding anything brighter than half grey: how tall the ink is. */
    private fun inkHeight(image: Bitmap32): Int = (0 until image.height).count { y -> (0 until image.width).any { x -> image[x, y].r > 128 } }

    private fun <T> withBackend(block: (KorgeBackend) -> T): T {
        val backend = KorgeBackend(fonts())
        try {
            return block(backend)
        } finally {
            backend.close()
        }
    }

    @Test
    fun `text under a scale of two is drawn from the glyphs made at twice the size`() = withBackend { backend ->
        val scaled = open(backend) {
            ProvideTextScale(2f) { Text("Hg", Modifier.offset(10f, 10f), textStyle = style(16f), colour = white) }
        }.using { frame(it, backend) }
        val direct = open(backend) {
            Text("Hg", Modifier.offset(10f, 10f), textStyle = style(32f), colour = white)
        }.using { frame(it, backend) }
        val plain = open(backend) {
            Text("Hg", Modifier.offset(10f, 10f), textStyle = style(16f), colour = white)
        }.using { frame(it, backend) }

        assertTrue(inkHeight(plain) > 0, "the plain label should have drawn something")
        assertTrue(same(scaled, direct), "a scale of two should draw exactly what a 32 style draws")
        assertFalse(same(scaled, plain), "and not what the 16 style draws")
        val ratio = inkHeight(scaled).toFloat() / inkHeight(plain)
        assertTrue(ratio in 1.8f..2.2f, "the letters should be about twice as tall, were $ratio times")
    }

    @Test
    fun `a scaled label is laid out exactly like one styled at the scaled size`() = withBackend { backend ->
        val scaled = open(backend) {
            ProvideTextScale(1.25f) { Text("Hull integrity", Modifier.testTag("t"), textStyle = style(16f)) }
        }.using { it.node("t").let { node -> node.width to node.height } }
        val direct = open(backend) {
            Text("Hull integrity", Modifier.testTag("t"), textStyle = style(20f))
        }.using { it.node("t").let { node -> node.width to node.height } }

        assertEquals(direct, scaled)
    }

    @Test
    fun `a scale whose size was never registered says which sizes were`() {
        val backend = KorgeBackend(KorgeFonts().also { it.registerTrueType("test", TestFonts.dejaVu(), listOf(16)) })
        try {
            val failure = assertThrows<IllegalStateException> {
                open(backend) { ProvideTextScale(1.5f) { Text("Hull", textStyle = style(16f)) } }.close()
            }
            assertTrue("test at 24" in failure.message.orEmpty(), failure.message)
            assertTrue("[16]" in failure.message.orEmpty(), failure.message)
        } finally {
            backend.close()
        }
    }
}
