package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The picture widget, drawn for real: what reaches the canvas, and how big the node ends up. */
class ImageWidgetTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()

    /** Twice as wide as it is tall, so a square box has to make a decision about it. */
    private val wide = FakeTexture(200, 100)

    @AfterEach
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent(content)
    }

    private fun frame(width: Float = 400f, height: Float = 400f) {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(width, height))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private var clock = 0L

    private fun drawn() = canvas.calls.filterIsInstance<DrawCall.Image>().single()

    private fun image() = find(host.root, "image") ?: error("no image node was composed")

    private fun find(node: UiNode, name: String): UiNode? =
        if (node.name == name) node else node.children.firstNotNullOfOrNull { find(it, name) }

    @Test
    fun `a picture asks for its own size in texture pixels`() {
        show { Image(wide) }

        frame()

        assertEquals(200f, image().width)
        assertEquals(100f, image().height)
    }

    @Test
    fun `and takes a smaller size when that is all there is`() {
        show { Image(wide) }

        frame(width = 80f, height = 80f)

        assertEquals(80f, image().width, "it cannot escape the room it was given")
        assertEquals(80f, image().height)
    }

    @Test
    fun `contain keeps the aspect inside a square box`() {
        show { Image(wide, Modifier.size(120f)) }

        frame()

        val call = drawn()
        assertEquals(120f, call.destination.width)
        assertEquals(60f, call.destination.height, 0.001f, "half the width, as the picture is")
        assertEquals(30f, call.destination.top, 0.001f, "centred in the box")
        assertNull(call.source, "the whole picture is used")
    }

    @Test
    fun `cover fills the box and says which part of the picture it used`() {
        show { Image(wide, Modifier.size(120f), fit = ImageFit.Cover) }

        frame()

        val call = drawn()
        assertEquals(120f, call.destination.width)
        assertEquals(120f, call.destination.height, "no gaps")
        val source = call.source ?: error("cover must crop")
        assertEquals(100f, source.width, 0.001f, "a square of the picture")
        assertEquals(100f, source.height, 0.001f)
    }

    @Test
    fun `alignment decides where a contained picture sits`() {
        show { Image(wide, Modifier.size(120f), alignment = Alignment.BottomEnd) }

        frame()

        assertEquals(120f, drawn().destination.bottom, 0.001f, "hard against the bottom")
        assertEquals(120f, drawn().destination.right, 0.001f)
    }

    @Test
    fun `the tint reaches the canvas`() {
        show { Image(wide, tint = Colour.rgb(0xFF0000)) }

        frame()

        assertEquals(Colour.rgb(0xFF0000), drawn().tint)
    }

    @Test
    fun `a named picture comes out of the skin's atlas`() {
        val skin = Skin(art = ArtAtlas.of(mapOf("icons/heart" to wide)))
        show { ProvideSkin(skin) { Image("icons/heart") } }

        frame()

        assertSame(wide, drawn().texture)
    }

    @Test
    fun `a name the atlas has never heard of stops there and says so`() {
        val skin = Skin(art = ArtAtlas.of(mapOf("icons/heart" to wide)))

        val failure = assertThrows<IllegalStateException> {
            show { ProvideSkin(skin) { Image("icons/hart") } }
            host.frame(0L)
        }

        assertTrue(failure.message.orEmpty().contains("icons/hart"), "it names it: ${failure.message}")
    }

    @Test
    fun `the same picture twice does not redraw the frame`() {
        show { Image(wide) }
        frame()

        assertEquals(false, host.frame(clock), "nothing changed, so nothing is invalidated")
    }
}
