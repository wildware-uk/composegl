package dev.wildware.composegl.ui.graphics

/**
 * A game's art, by name.
 *
 * The one thing a skin file needs that the toolkit cannot provide: `"button_pressed"` has to become
 * something drawable, and only a backend knows how. Everything past that point — slicing it into
 * nine, padding the contents, tinting it — is arithmetic the toolkit already does.
 *
 * Names are the artist's, not the toolkit's. Nothing here assumes a separator, a prefix or a case,
 * because an atlas is packed by whatever tool the artist already uses.
 */
interface ArtAtlas {

    /** The region called [name], or null if this atlas has never heard of it. */
    fun region(name: String): TextureHandle?

    /** Every name in the atlas. What a loader lists when it wants to say which one was meant. */
    val names: Set<String>

    companion object {

        /**
         * An atlas that is simply a map.
         *
         * What a backend that has already cut its picture up hands over, and what a test uses. A
         * real packer has a file format and a tool behind it; this is the last half-inch between
         * whatever came out of that and a name a skin file can say.
         */
        fun of(regions: Map<String, TextureHandle>): ArtAtlas = MapAtlas(regions)
    }
}

private class MapAtlas(private val regions: Map<String, TextureHandle>) : ArtAtlas {
    override fun region(name: String): TextureHandle? = regions[name]
    override val names: Set<String> get() = regions.keys
}
