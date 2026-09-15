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

/**
 * The frames of an animation, found by name: every region called [prefix] followed by a number.
 *
 * `coin_0`, `coin_1` … `coin_11` for a prefix of `"coin_"`, in the order of the numbers rather
 * than of the names, so `coin_10` comes after `coin_9` and not after `coin_1`. Leading zeros are
 * allowed and mean nothing: `coin_007` is frame seven. A name with anything but digits after the
 * prefix — `coin_shadow`, `coin_1b` — is not a frame and is left alone.
 *
 * The numbers are an order, not positions: a strip numbered from one, or with a gap where the
 * artist deleted a frame, still plays every frame there is, one after another.
 *
 * Fails, listing what the atlas does hold, when nothing matches — an animation with no frames draws
 * nothing, and nothing looks exactly like a prefix with a typo in it. Two names for the same number
 * (`coin_1` and `coin_01`) fail too, because which one was meant is a question only the artist
 * can answer.
 */
fun ArtAtlas.frames(prefix: String): List<TextureHandle> {
    val numbered = names.mapNotNull { name ->
        if (!name.startsWith(prefix)) return@mapNotNull null
        val rest = name.substring(prefix.length)
        if (rest.isEmpty() || !rest.all { it in '0'..'9' }) return@mapNotNull null
        // Past what a Long holds is not a frame number anybody wrote on purpose.
        val number = rest.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: return@mapNotNull null
        number to name
    }
    if (numbered.isEmpty()) {
        throw IllegalArgumentException(
            "no frames called \"$prefix\" followed by a number in the atlas; it holds " +
                names.sorted().joinToString(prefix = "[", postfix = "]"),
        )
    }
    numbered.groupBy({ it.first }, { it.second }).values.firstOrNull { it.size > 1 }?.let { same ->
        throw IllegalArgumentException("${same.sorted()} are the same frame of \"$prefix\"; keep one of them")
    }
    return numbered.sortedBy { it.first }.map { (_, name) -> checkNotNull(region(name)) { "\"$name\" went missing" } }
}

private class MapAtlas(private val regions: Map<String, TextureHandle>) : ArtAtlas {
    override fun region(name: String): TextureHandle? = regions[name]
    override val names: Set<String> get() = regions.keys
}
