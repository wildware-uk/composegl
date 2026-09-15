package dev.wildware.composegl.ui.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Finding an animation's frames in an atlas by the names an artist gave them. */
class ArtAtlasFramesTest {

    /** A texture that is only ever equal to itself, so a list of them says which is which. */
    private class Named(val name: String) : TextureHandle {
        override val width = 16
        override val height = 16
        override fun toString() = name
    }

    private fun atlas(vararg names: String) = ArtAtlas.of(names.associateWith { Named(it) })

    private fun List<TextureHandle>.names() = map { (it as Named).name }

    @Test
    fun `frames come back in the order of their numbers`() {
        val atlas = atlas("coin_2", "coin_10", "coin_0", "coin_1", "coin_9")

        assertEquals(listOf("coin_0", "coin_1", "coin_2", "coin_9", "coin_10"), atlas.frames("coin_").names())
    }

    @Test
    fun `leading zeros are only padding`() {
        val atlas = atlas("torch_010", "torch_002", "torch_001")

        assertEquals(listOf("torch_001", "torch_002", "torch_010"), atlas.frames("torch_").names())
    }

    @Test
    fun `names that are not the prefix and a number are left alone`() {
        val atlas = atlas("coin_0", "coin_1", "coin_shadow", "coin_1b", "coin_", "coins_2", "gem_0")

        assertEquals(listOf("coin_0", "coin_1"), atlas.frames("coin_").names())
    }

    @Test
    fun `a strip with a gap still plays every frame it has`() {
        val atlas = atlas("spin1", "spin2", "spin4")

        assertEquals(listOf("spin1", "spin2", "spin4"), atlas.frames("spin").names())
    }

    @Test
    fun `a prefix nothing matches says what the atlas does hold`() {
        val failure = assertFailsWith<IllegalArgumentException> { atlas("coin_0", "gem_0").frames("con_") }

        val message = failure.message.orEmpty()
        assertTrue("con_" in message, message)
        assertTrue("[coin_0, gem_0]" in message, message)
    }

    @Test
    fun `two names for one frame number are refused`() {
        val failure = assertFailsWith<IllegalArgumentException> { atlas("coin_1", "coin_01").frames("coin_") }

        assertTrue("coin_01" in failure.message.orEmpty(), failure.message)
    }
}
