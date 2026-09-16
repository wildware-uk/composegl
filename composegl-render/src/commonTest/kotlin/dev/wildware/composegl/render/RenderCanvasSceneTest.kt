package dev.wildware.composegl.render

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shared renderer's half of a scene view, against a device that writes down what it was asked:
 * a picture with depth at the size asked for, bound outside any frame, handed over with the viewport
 * covering it, and drawn back the right way up.
 */
class RenderCanvasSceneTest {

    private val device = RecordingDevice()
    private val canvas = RenderCanvas(device)
    private val design = Viewport.oneToOne(Size(400f, 300f))

    @Test
    fun `a scene is a picture with depth at the size asked for`() {
        assertTrue(canvas.drawsScenes)

        val made = canvas.scene(null, 240, 160) { it.clear(Colour.Black) }

        assertNotNull(made)
        assertEquals(240, made.width)
        assertEquals(160, made.height)
        assertEquals(
            listOf(
                "offscreen(1, 240x160, depth)",
                "begin(target1)",
                "target(target1, 0, 0, 240, 160)",
                "noScissor",
                "target(target1, 0, 0, 240, 160)",
                "noScissor",
                "clear(0.0, 0.0, 0.0, 1.0)",
                "end",
            ),
            device.calls,
        )
    }

    @Test
    fun `the block is told the real size`() {
        var seen = 0 to 0
        canvas.scene(null, 64, 32) { seen = it.width to it.height }
        assertEquals(64 to 32, seen)
    }

    @Test
    fun `the same size is filled again without a new picture`() {
        val first = canvas.scene(null, 100, 50) { }
        device.calls.clear()

        val second = canvas.scene(first, 100, 50) { }

        assertSame(first, second)
        assertTrue(device.named("offscreen").isEmpty())
        assertTrue(device.deleted.isEmpty())
    }

    @Test
    fun `a new size gives the old picture back and makes one at the new size`() {
        val first = assertNotNull(canvas.scene(null, 100, 50) { })
        device.calls.clear()

        val second = assertNotNull(canvas.scene(first, 200, 80) { })

        assertEquals(200 to 80, second.width to second.height)
        assertEquals(1, device.deleted.size)
        assertEquals(listOf("offscreen(3, 200x80, depth)"), device.named("offscreen"))
    }

    @Test
    fun `a size bigger than the device makes is cut down to what it makes`() {
        val small = RenderCanvas(RecordingDevice(maxTextureSize = 128))
        val made = assertNotNull(small.scene(null, 500, 60) { })
        assertEquals(128 to 60, made.width to made.height)
    }

    @Test
    fun `raw hands over the frame with no suspend and the viewport over the picture`() {
        var lent: Any? = null
        canvas.scene(null, 80, 40) { target -> target.raw { lent = it } }

        val frame = assertIs<RenderFrame>(lent)
        assertEquals(Size(80f, 40f), frame.viewport.physical)
        assertEquals(2f / 80f, frame.projection[0])
        assertEquals(2f / 40f, frame.projection[5])
        assertFalse("suspend" in device.calls, "the picture has to stay bound while the game draws")
        assertEquals("end", device.calls.last())
    }

    @Test
    fun `a scene inside a frame is refused`() {
        canvas.begin(design)
        assertFailsWith<IllegalStateException> { canvas.scene(null, 10, 10) { } }
        canvas.end()
    }

    @Test
    fun `nothing to draw into asks the device for nothing`() {
        assertNull(canvas.scene(null, 0, 10) { error("not run") })
        assertTrue(device.calls.isEmpty())
    }

    @Test
    fun `a device that cannot draw offscreen draws no scene`() {
        val flat = RenderCanvas(RecordingDevice(offscreen = false))
        var ran = false
        assertNull(flat.scene(null, 10, 10) { ran = true })
        assertFalse(ran)
    }

    @Test
    fun `the picture is drawn upright and premultiplied where it is put`() {
        val made = assertNotNull(canvas.scene(null, 100, 50) { })
        device.calls.clear()

        canvas.begin(design)
        canvas.image(made, Rect.of(10f, 20f, 100f, 50f))
        canvas.end()

        val draw = device.draws.single()
        assertSame((made as ScenePicture).texture, draw.texture)
        assertEquals(ShapeVertex.PremultipliedPicture, draw.at(0, 28), "the picture's colours are already premultiplied")
        assertEquals(1f, draw.at(1, 16), "the top of the quad reads v = 1, the framebuffer's top row")
    }

    @Test
    fun `closing the picture gives it back`() {
        val made = assertNotNull(canvas.scene(null, 30, 30) { })
        made.close()
        assertTrue(made.closed)
        assertEquals(1, device.deleted.size)
        made.close()
        assertEquals(1, device.deleted.size, "twice is once")
    }
}
