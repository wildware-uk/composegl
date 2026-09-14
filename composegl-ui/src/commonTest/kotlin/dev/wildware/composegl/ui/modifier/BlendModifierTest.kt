package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The blend mode a node draws under, and how far it reaches.
 *
 * A glow meant to add light that instead paints over what is behind it makes the same picture as a
 * glow that is simply the wrong colour, so the only way to tell the two apart is to ask what mode
 * each call went out under. [RecordingCanvas.blendOf] answers that, and every test here is that one
 * question asked of one call.
 */
class BlendModifierTest {

    private val viewport = Viewport.oneToOne(Size(640f, 360f))

    private fun draw(content: @Composable () -> Unit): RecordingCanvas {
        val canvas = HeadlessBackend().canvas
        val host = UiHost()
        host.setContent(content)
        UiRenderer(host, canvas).render(viewport, nanos = 0L)
        return canvas
    }

    @Test
    fun `a modifier with no blend on it paints`() {
        assertEquals(BlendMode.SourceOver, Modifier.resolve().blend)
    }

    @Test
    fun `the blend asked for is the blend resolved`() {
        assertEquals(BlendMode.Additive, Modifier.blend(BlendMode.Additive).resolve().blend)
    }

    @Test
    fun `the last blend named wins`() {
        val modifier = Modifier.blend(BlendMode.Additive).blend(BlendMode.SourceOver)

        assertEquals(BlendMode.SourceOver, modifier.resolve().blend)
    }

    @Test
    fun `a node's own background is drawn under its blend`() {
        val canvas = draw {
            LeafLayout(Modifier.size(40f, 20f).blend(BlendMode.Additive).background(Colour.White))
        }

        val rectangle = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(BlendMode.Additive, canvas.blendOf(rectangle))
    }

    @Test
    fun `the blend does not reach the node drawn next to it`() {
        val canvas = draw {
            Box {
                LeafLayout(Modifier.size(40f, 20f).blend(BlendMode.Additive).background(Colour.White))
                LeafLayout(Modifier.size(10f, 10f).background(Colour.White))
            }
        }

        val (lit, plain) = canvas.only<DrawCall.Rectangle>().sortedByDescending { it.rect.width }

        assertEquals(BlendMode.Additive, canvas.blendOf(lit))
        assertEquals(BlendMode.SourceOver, canvas.blendOf(plain), "a sibling is not lit by it")
    }

    @Test
    fun `a child draws under the blend its parent asked for`() {
        val canvas = draw {
            Box(Modifier.size(40f, 20f).blend(BlendMode.Additive)) {
                LeafLayout(Modifier.size(10f, 10f).background(Colour.White))
            }
        }

        val rectangle = canvas.only<DrawCall.Rectangle>().single()
        assertEquals(BlendMode.Additive, canvas.blendOf(rectangle), "the child is inside the push")
    }
}
