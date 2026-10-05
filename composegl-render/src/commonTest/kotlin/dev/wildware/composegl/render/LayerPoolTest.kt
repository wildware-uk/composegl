package dev.wildware.composegl.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** What `GlLayersTest` held the LWJGL backend's own pool to, now held against the one shared pool. */
class LayerPoolTest {

    private val device = RecordingDevice()

    @Test
    fun `a released layer of the same size comes back`() {
        val layers = LayerPool(device)
        val first = layers.acquire(64, 64)
        layers.release(first)

        assertSame(first, layers.acquire(64, 64), "the same picture should have been reused")
        assertEquals(1, layers.size)
        assertEquals(1, device.named("offscreen").size, "one picture made, not two")
    }

    @Test
    fun `a layer that is still in use is never handed out twice`() {
        val layers = LayerPool(device)
        val outer = layers.acquire(64, 64)
        val inner = layers.acquire(64, 64)

        assertNotSame(outer, inner, "a layer inside a layer would draw into its own parent")
        assertEquals(2, layers.size)
    }

    @Test
    fun `a smaller layer is drawn in the corner of a free picture a little bigger`() {
        val layers = LayerPool(device)
        val big = layers.acquire(100, 100)
        layers.release(big)
        layers.trim()

        assertSame(big, layers.acquire(90, 70), "a picture that shrank keeps the one it had")
        assertEquals(1, device.named("offscreen").size)
    }

    @Test
    fun `a picture handed out this frame is shared only by a layer the same size`() {
        val layers = LayerPool(device)
        val first = layers.acquire(100, 100)
        layers.release(first)

        // The first layer may not have been put down yet: a different layer must not draw over it.
        val other = layers.acquire(90, 80)
        assertNotSame(first, other)
        layers.release(other)
        assertSame(first, layers.acquire(100, 100), "the same size again shares it, as it always has")
        layers.release(first)

        layers.trim()
        assertSame(first, layers.acquire(90, 80), "a frame later it is anybody's that fits")
    }

    @Test
    fun `a new picture is rounded up to a step each way`() {
        val layers = LayerPool(device)
        val picture = layers.acquire(434, 594)

        assertEquals(448 to 640, picture.width to picture.height)
        assertEquals(64 to 64, layers.acquire(64, 64).let { it.width to it.height }, "a whole step is already round")
    }

    @Test
    fun `a free picture much bigger is not handed to a small layer`() {
        val layers = LayerPool(device)
        val big = layers.acquire(512, 512)
        layers.release(big)
        layers.trim()

        val small = layers.acquire(64, 64)
        assertNotSame(big, small, "clearing all of a big picture for a small one costs more than a new one")
        assertEquals(64 to 64, small.width to small.height)
    }

    @Test
    fun `the smallest free picture that fits is the one handed out`() {
        val layers = LayerPool(device)
        val larger = layers.acquire(150, 150)
        val smaller = layers.acquire(100, 100)
        layers.release(larger)
        layers.release(smaller)
        layers.trim()

        assertSame(smaller, layers.acquire(110, 110))
        assertSame(larger, layers.acquire(110, 110), "the next one fits too, and the smaller is busy")
    }

    @Test
    fun `a free picture too small either way is not used`() {
        val layers = LayerPool(device)
        layers.release(layers.acquire(128, 64))
        layers.trim()

        val tall = layers.acquire(64, 100)
        assertEquals(64 to 128, tall.width to tall.height)
        assertEquals(2, device.named("offscreen").size)
    }

    @Test
    fun `a layer that grows a pixel a frame makes a picture a step - not a picture a frame`() {
        val layers = LayerPool(device)
        repeat(30) { frame ->
            layers.release(layers.acquire(90 + frame, 50 + frame))
            layers.trim()
        }

        assertEquals(listOf("128x64", "128x128"), device.named("offscreen").map { it.substringAfter(", ").removeSuffix(")") })
    }

    @Test
    fun `a picture is never rounded past the biggest the device makes`() {
        val small = RecordingDevice(maxTextureSize = 100)
        val picture = LayerPool(small).acquire(90, 30)

        assertEquals(100 to 64, picture.width to picture.height)
    }

    @Test
    fun `a layer nobody wants for a while goes back to the device`() {
        val layers = LayerPool(device, spare = 2)
        val picture = layers.acquire(64, 64)
        layers.release(picture)

        layers.trim()
        assertEquals(1, layers.size, "one quiet frame is not enough to throw it away")
        layers.trim()
        layers.trim()
        assertEquals(0, layers.size, "three quiet frames are")
        assertEquals(listOf<DeviceResource>(picture), device.deleted)
    }

    @Test
    fun `a layer in use is kept however long the frame takes`() {
        val layers = LayerPool(device, spare = 0)
        val held = layers.acquire(64, 64)

        repeat(3) { layers.trim() }

        assertEquals(1, layers.size, "it is still being drawn into")
        layers.release(held)
    }

    @Test
    fun `being wanted again starts the quiet count over`() {
        val layers = LayerPool(device, spare = 2)
        layers.release(layers.acquire(64, 64))
        layers.trim()
        layers.trim()
        layers.release(layers.acquire(64, 64))
        layers.trim()
        layers.trim()

        assertEquals(1, layers.size)
    }

    @Test
    fun `a lost context forgets every picture without deleting any`() {
        val layers = LayerPool(device)
        layers.release(layers.acquire(64, 64))
        layers.forget()

        assertEquals(0, layers.size)
        assertEquals(0, device.deleted.size, "those names belong to a context that is gone")
    }

    @Test
    fun `closing gives every picture back`() {
        val layers = LayerPool(device)
        layers.acquire(64, 64)
        layers.acquire(32, 32)
        layers.close()

        assertEquals(0, layers.size)
        assertEquals(2, device.deleted.size)
    }
}
