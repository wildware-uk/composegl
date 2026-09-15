package dev.wildware.composegl.korge

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The atlas is bitmaps in memory, so all of it is asked here with no GPU. */
class KorgeAtlasTest {

    @Test
    fun `the white block is on page zero, opaque white, sampled a pixel in from its edge`() {
        val atlas = KorgeAtlas(64)
        val white = atlas.white
        assertEquals(0, white.page)
        val page = atlas.pages[0]
        for (y in white.y - 1..white.y + white.height) {
            for (x in white.x - 1..white.x + white.width) {
                assertEquals(RGBA(255, 255, 255, 255), page.getRgbaRaw(x, y), "white at $x, $y, including the pixel round the sampled part")
            }
        }
    }

    @Test
    fun `regions do not overlap, and there are empty pixels between them`() {
        val atlas = KorgeAtlas(64)
        val regions = List(20) { atlas.pack(7, 5) } + atlas.white
        for (a in regions) for (b in regions) {
            if (a === b || a.page != b.page) continue
            val apart = a.x + a.width < b.x || b.x + b.width < a.x || a.y + a.height < b.y || b.y + b.height < a.y
            assertTrue(apart, "(${a.x}, ${a.y}) and (${b.x}, ${b.y}) touch")
        }
    }

    @Test
    fun `a full page starts another`() {
        val atlas = KorgeAtlas(32)
        repeat(10) { atlas.pack(12, 12) }
        assertTrue(atlas.pageCount > 1, "ten 12 by 12 pictures cannot fit on one 32 page")
    }

    @Test
    fun `a picture bigger than a page is refused, saying so`() {
        val failure = assertThrows<IllegalArgumentException> { KorgeAtlas(32).pack(40, 4) }
        assertTrue("does not fit" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `texture coordinates are the region's edges over the page`() {
        val atlas = KorgeAtlas(100)
        val region = atlas.pack(10, 20)
        assertEquals(region.x / 100f, region.u)
        assertEquals((region.y + 20) / 100f, region.v2)
    }

    @Test
    fun `coverage is written as premultiplied white, and the page says it changed`() {
        val atlas = KorgeAtlas(64)
        val region = atlas.pack(2, 1)
        val page = atlas.pages[region.page]
        val before = page.contentVersion
        val coverage = Bitmap32(2, 1, premultiplied = true).also {
            it.setRgbaRaw(0, 0, RGBA(0, 0, 0, 0))
            it.setRgbaRaw(1, 0, RGBA(100, 100, 100, 100))
        }
        atlas.putCoverage(region, coverage)

        assertTrue(page.premultiplied, "KorGE uploads only premultiplied pages without complaint")
        // Premultiplied white is the coverage in every channel: nothing at all where there is no ink.
        assertEquals(RGBA(0, 0, 0, 0), page.getRgbaRaw(region.x, region.y))
        assertEquals(RGBA(100, 100, 100, 100), page.getRgbaRaw(region.x + 1, region.y))
        assertTrue(page.contentVersion > before, "KorGE uploads a page again only when its version moves")
    }
}
