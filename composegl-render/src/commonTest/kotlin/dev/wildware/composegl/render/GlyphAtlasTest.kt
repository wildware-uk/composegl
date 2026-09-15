package dev.wildware.composegl.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GlyphAtlasTest {

    private val device = RecordingDevice()

    @Test
    fun `the white block is opaque white in the first corner of the first page`() {
        val atlas = GlyphAtlas(pageSize = 64)
        val white = atlas.white
        assertSame(atlas.page(0), white.page)
        assertEquals(0 to 0, white.x to white.y)
        val pixels = white.page.pixels
        for (y in 0 until GlyphAtlas.WhiteBlock) for (x in 0 until GlyphAtlas.WhiteBlock) {
            val at = (y * 64 + x) * 4
            assertEquals(listOf<Byte>(-1, -1, -1, -1), (0 until 4).map { pixels[at + it] })
        }
        assertEquals(0.toByte(), pixels[(GlyphAtlas.WhiteBlock * 64 + 0) * 4 + 3], "and nothing below it")
    }

    @Test
    fun `glyphs go along a shelf with a pixel of gap`() {
        val atlas = GlyphAtlas(pageSize = 64)
        val first = atlas.place(4, 4)
        val second = atlas.place(4, 4)

        assertEquals(GlyphAtlas.WhiteBlock + GlyphAtlas.Gap to 0, first.x to first.y, "beside the white block")
        assertEquals(first.x + 4 + GlyphAtlas.Gap to 0, second.x to second.y)
    }

    @Test
    fun `a taller glyph opens a shelf below and a short one still takes the lowest that fits`() {
        val atlas = GlyphAtlas(pageSize = 64)
        val tall = atlas.place(4, 20)
        val short = atlas.place(4, 4)

        assertEquals(0 to GlyphAtlas.WhiteBlock + GlyphAtlas.Gap, tall.x to tall.y)
        assertEquals(0, short.y, "back on the white block's shelf, not wasting the tall one")
    }

    @Test
    fun `a full shelf starts the next one`() {
        val atlas = GlyphAtlas(pageSize = 32)
        atlas.place(20, 8)
        val next = atlas.place(20, 8)
        assertEquals(0 to 9, next.x to next.y)
    }

    @Test
    fun `a page nothing was uploaded from doubles and keeps what it had`() {
        val atlas = GlyphAtlas(pageSize = 16, maxPageSize = 32)
        val spot = atlas.place(10, 10)

        assertEquals(32, atlas.page(0).size)
        assertEquals(1, atlas.pageCount)
        assertEquals(0 to 9, spot.x to spot.y)
        assertEquals((-1).toByte(), atlas.page(0).pixels[3], "the white block came along")
    }

    @Test
    fun `a page that was drawn does not move and a new page is opened instead`() {
        val atlas = GlyphAtlas(pageSize = 16, maxPageSize = 32, maxPages = 2)
        atlas.page(0).texture(device)
        val spot = atlas.place(10, 10)

        assertEquals(16, atlas.page(0).size, "glyphs already on screen keep their texture coordinates")
        assertEquals(2, atlas.pageCount)
        assertSame(atlas.page(1), spot.page)
    }

    @Test
    fun `an atlas with no room left says what to make bigger`() {
        val atlas = GlyphAtlas(pageSize = 16, maxPageSize = 16, maxPages = 1, owner = "TestFonts")
        val thrown = assertFailsWith<IllegalStateException> { atlas.place(12, 12) }
        assertTrue("TestFonts" in thrown.message.orEmpty(), thrown.message)
    }

    @Test
    fun `only the rectangle that changed is uploaded`() {
        val atlas = GlyphAtlas(pageSize = 64)
        val page = atlas.page(0)
        val texture = page.texture(device)
        assertEquals("write(${(texture as RecordingDevice.FakeTexture).id}, 0, 0, 64x64)", device.named("write").single())

        val spot = atlas.place(3, 5)
        page.writeCoverage(spot.x, spot.y, 3, 5, ByteArray(15) { 7 })
        assertSame(texture, page.texture(device))
        assertEquals("write(${texture.id}, ${spot.x}, ${spot.y}, 3x5)", device.named("write").last())

        page.texture(device)
        assertEquals(2, device.named("write").size, "nothing changed, nothing sent")
    }

    @Test
    fun `coverage becomes white with that alpha`() {
        val atlas = GlyphAtlas(pageSize = 64)
        val spot = atlas.place(2, 1)
        atlas.page(0).writeCoverage(spot.x, spot.y, 2, 1, byteArrayOf(0x40, 0x7F))
        val at = (spot.y * 64 + spot.x + 1) * 4
        assertEquals(listOf<Byte>(-1, -1, -1, 0x7F), (0 until 4).map { atlas.page(0).pixels[at + it] })
    }

    @Test
    fun `a device that lost its context gets the whole page again`() {
        val atlas = GlyphAtlas(pageSize = 64)
        val before = atlas.page(0).texture(device)
        atlas.forget(device)
        val after = atlas.page(0).texture(device)

        assertNotSame(before, after)
        assertEquals(2, device.named("write").count { it.endsWith("0, 0, 64x64)") })
        assertEquals(0, device.deleted.size)
    }

    @Test
    fun `closing gives each uploaded page back`() {
        val atlas = GlyphAtlas(pageSize = 64)
        val texture = atlas.page(0).texture(device)
        atlas.close()
        assertEquals(listOf<DeviceResource>(texture), device.deleted)
    }
}
