package dev.wildware.composegl.demo.web

import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinFormat

/**
 * The three skins the tour can wear, each with the art laid in.
 *
 * Every one starts from a skin the toolkit ships, so every widget has a style. The styles the tour's
 * own pages invent — the extra bars, the cards — go on all three, and the showcase's own look goes on
 * the first only. Switching is swapping one object: nothing is rebuilt.
 *
 * The styles live in the JSON files in `src/commonMain/skins`, which the build embeds as source, the same way
 * composegl-ui embeds its own skins: a browser tab has no file system to read them from.
 */
class ShowcaseSkins(art: ArtAtlas?) {

    private val extras = SkinFormat.read(TOUR_EXTRAS_JSON)
    private val artOnly = Skin(art = art)

    val showcase: Skin = Skin.Default.overriddenWith(extras).overriddenWith(SkinFormat.read(SHOWCASE_LOOK_JSON)).overriddenWith(artOnly)
    val standard: Skin = Skin.Default.overriddenWith(extras).overriddenWith(artOnly)
    val highContrast: Skin = Skin.HighContrast.overriddenWith(SkinFormat.read(HIGH_CONTRAST_TOUR_EXTRAS_JSON)).overriddenWith(artOnly)

    operator fun get(choice: SkinChoice): Skin = when (choice) {
        SkinChoice.Showcase -> showcase
        SkinChoice.Standard -> standard
        SkinChoice.HighContrast -> highContrast
    }
}
