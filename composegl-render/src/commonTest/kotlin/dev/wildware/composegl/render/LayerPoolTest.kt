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
    fun `a different size is a different layer`() {
        val layers = LayerPool(device)
        val small = layers.acquire(64, 64)
        layers.release(small)

        assertNotSame(small, layers.acquire(64, 32), "a nearly right size is a soft picture")
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
