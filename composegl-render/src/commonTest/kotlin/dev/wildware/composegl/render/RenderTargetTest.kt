package dev.wildware.composegl.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a render target asks the device for, asserted against a device that draws nothing.
 *
 * The depth attachment is the whole of it: a 3D scene drawn into a target with no depth buffer
 * comes out inside-out, and nothing in the game's own renderer can fix that. Whether the pixels
 * really come out in front of each other is a question only a GPU can answer, and the backends'
 * own tests ask it.
 */
class RenderTargetTest {

    @Test
    fun `a target asks for no depth buffer unless one was asked for`() {
        val device = RecordingDevice()
        val target = RenderTarget(device, 64, 32)

        assertFalse(target.depth)
        assertEquals(listOf("offscreen(1, 64x32)"), device.named("offscreen"))
        target.close()
    }

    @Test
    fun `a target with depth asks the device for a depth attachment`() {
        val device = RecordingDevice()
        val target = RenderTarget(device, 64, 32, depth = true)

        assertTrue(target.depth)
        assertEquals(listOf("offscreen(1, 64x32, depth)"), device.named("offscreen"))
        assertTrue(checkNotNull(target.target).depth, "and the picture it got carries one")
        target.close()
    }

    @Test
    fun `a resize asks for the depth attachment again and gives the old picture back`() {
        val device = RecordingDevice()
        val target = RenderTarget(device, 64, 32, depth = true)
        val first = checkNotNull(target.target)

        target.resize(128, 64)

        assertEquals(listOf("offscreen(1, 64x32, depth)", "offscreen(3, 128x64, depth)"), device.named("offscreen"))
        assertEquals(listOf<DeviceResource>(first), device.deleted, "a panel following a window would leak one per resize")
        target.close()
    }

    @Test
    fun `a size bigger than the device allows is clamped rather than failing mid-frame`() {
        val device = RecordingDevice(maxTextureSize = 256)
        val target = RenderTarget(device, 4000, 300, depth = true)

        assertEquals(256, target.width)
        assertEquals(256, target.height)
        assertTrue(target.clamped, "and it says so")
        assertEquals(listOf("offscreen(1, 256x256, depth)"), device.named("offscreen"))
        target.close()
    }

    @Test
    fun `a size the device allows is not clamped`() {
        val device = RecordingDevice(maxTextureSize = 256)
        val target = RenderTarget(device, 256, 128)

        assertEquals(256, target.width)
        assertFalse(target.clamped)
        target.close()
    }

    @Test
    fun `two clamped sizes in a row are one picture`() {
        val device = RecordingDevice(maxTextureSize = 256)
        val target = RenderTarget(device, 4000, 4000)

        target.resize(5000, 6000)

        assertEquals(1, device.named("offscreen").size, "both land on the biggest the device makes")
        assertEquals(emptyList<DeviceResource>(), device.deleted)
        target.close()
    }

    @Test
    fun `closing gives the picture back and can be done twice`() {
        val device = RecordingDevice()
        val target = RenderTarget(device, 8, 8, depth = true)
        val picture = checkNotNull(target.target)

        target.close()
        target.close()

        assertEquals(listOf<DeviceResource>(picture), device.deleted)
        assertEquals(null, target.target)
    }
}
