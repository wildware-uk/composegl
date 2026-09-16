package dev.wildware.composegl.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shared renderer's half of the scene view's cost rules, against a device that writes down every
 * picture it makes and gives back. The rules themselves — stretch, release, the cap — are decided in
 * `composegl-ui`'s prepass and tested there; this is what the prepass is told and what it saves.
 */
class SceneLifecycleDeviceTest {

    @Test
    fun `the biggest scene a canvas makes is the device's biggest texture`() {
        assertEquals(256, RenderCanvas(RecordingDevice(maxTextureSize = 256)).maxSceneSize)
        assertEquals(4096, RenderCanvas(RecordingDevice()).maxSceneSize)
    }

    @Test
    fun `a device that reports no texture size still allows a pixel`() {
        assertEquals(1, RenderCanvas(RecordingDevice(maxTextureSize = 0)).maxSceneSize)
    }

    @Test
    fun `filling a stretched picture again at its own size makes nothing new`() {
        val device = RecordingDevice()
        val canvas = RenderCanvas(device)
        val first = assertNotNull(canvas.scene(null, 100, 60) { })

        // What the prepass does for a live scene during a drag: the same picture at its old size.
        repeat(8) { assertSame(first, canvas.scene(first, 100, 60) { }) }

        assertEquals(1, device.named("offscreen").size, "eight renders during a drag, one picture")
        assertTrue(device.deleted.isEmpty())
    }

    @Test
    fun `a scene at the maximum is made at the maximum and not cut again`() {
        val device = RecordingDevice(maxTextureSize = 256)
        val canvas = RenderCanvas(device)

        val made = assertNotNull(canvas.scene(null, canvas.maxSceneSize, 128) { })

        assertEquals(256 to 128, made.width to made.height)
        assertEquals(listOf("offscreen(1, 256x128, depth)"), device.named("offscreen"))
    }
}
