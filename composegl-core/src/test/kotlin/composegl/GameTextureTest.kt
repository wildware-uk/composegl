package composegl

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The GPU parts of [GameTexture] are proved in `composegl-lwjgl3`'s integration tests, where there
 * is a driver to answer. These are the parts that fail before any GL is involved.
 */
class GameTextureTest {

    @Test
    fun `a raster context cannot adopt a GL texture, and says why`() {
        val context = ComposeGlContext.createRaster()
        val thrown = assertThrows<ComposeGlUnsupportedException> {
            GameTexture.adopt(context, textureId = 1, width = 16, height = 16)
        }
        assertTrue(thrown.message!!.contains("createRaster()"), thrown.message)
        context.dispose()
    }

    @Test
    fun `a zero-sized texture is rejected before it reaches the driver`() {
        val context = ComposeGlContext.createRaster()
        assertThrows<IllegalArgumentException> {
            GameTexture.adopt(context, textureId = 1, width = 0, height = 16)
        }
        context.dispose()
    }
}
