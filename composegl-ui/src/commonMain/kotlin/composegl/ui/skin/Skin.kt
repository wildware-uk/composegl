package composegl.ui.skin

import composegl.ui.graphics.ArtAtlas
import composegl.ui.text.FontProvider

/**
 * Everything about how a game's interface looks, in one object.
 *
 * Appearance lives in data. A widget asks for the style called `button` and draws what it is
 * handed; it never contains a colour, a corner radius or a texture name. Swapping this object
 * swaps the look of the whole interface, which is what makes a skin file — and, in development, a
 * skin file being saved while the game runs — worth having at all.
 *
 * Three parts:
 *
 * - **Styles**, by name. `button`, `button.danger`, `panel`, `label`. The dot is not special to the
 *   toolkit but is the convention a skin file should follow, because [style] falls back along it:
 *   asking for `button.danger` in a skin that has never heard of it gets `button`, which is what
 *   lets a game use a variant name safely before an artist has drawn one.
 * - **Fonts**, so a style naming a family has somewhere for that name to mean something.
 * - **Art**, so a widget can ask for a named region — an icon, a portrait frame — without knowing
 *   what a texture is.
 *
 * @param defaults what a style leaves unsaid. A skin sets the game's body font and text colour
 *   here once, rather than in forty styles. Null means [ResolvedStyle.Plain], and is not the same
 *   as passing Plain: a skin laid over another keeps the one underneath unless it names its own.
 */
class Skin(
    val styles: Map<String, Style> = emptyMap(),
    val fonts: FontProvider? = null,
    val art: ArtAtlas? = null,
    val defaults: ResolvedStyle? = null,
) {

    /**
     * The style called [name], or the nearest thing to it.
     *
     * Never null and never an exception. A widget asking for a style it cannot get should look
     * plain, not vanish and not crash: a missing style is a skin that is not finished, which is a
     * normal state of affairs for weeks at a time, and a game that will not start because of one
     * is a game nobody can work on.
     *
     * Loud failure belongs at load time instead, where the name and the line number are known —
     * see the skin loader.
     */
    fun style(name: String): Style {
        styles[name]?.let { return it }
        // `button.danger` falls back to `button`, and `button` to nothing. One dot at a time, so a
        // three-part name degrades a step at a time rather than all the way at once.
        var shorter = name
        while (true) {
            val dot = shorter.lastIndexOf('.')
            if (dot <= 0) return Empty
            shorter = shorter.substring(0, dot)
            styles[shorter]?.let { return it }
        }
    }

    /** Whether [name] resolves to a style of its own rather than to a fallback. */
    fun has(name: String): Boolean = name in styles

    /**
     * The style to draw a widget with, in one call.
     *
     * What a widget actually uses: name, states, done. Every value is filled in, from the style,
     * then the skin's own defaults.
     */
    fun resolve(name: String, states: Set<WidgetState> = emptySet()): ResolvedStyle =
        style(name).resolve(states, defaults ?: ResolvedStyle.Plain)

    /**
     * This skin with [overrides] laid over it, style by style.
     *
     * How a screen says "the buttons in this dialogue are red" without copying the skin or
     * reaching into it: the result shares everything it does not change.
     */
    fun overriddenWith(overrides: Skin): Skin {
        if (overrides.styles.isEmpty() && overrides.fonts == null &&
            overrides.art == null && overrides.defaults == null
        ) {
            return this
        }
        val merged = styles.toMutableMap()
        overrides.styles.forEach { (name, style) ->
            merged[name] = merged[name]?.mergedWith(style) ?: style
        }
        return Skin(
            styles = merged,
            fonts = overrides.fonts ?: fonts,
            art = overrides.art ?: art,
            defaults = overrides.defaults ?: defaults,
        )
    }

    companion object {

        /** A style that says nothing, so a widget with no skin at all still draws its text. */
        val Empty = Style()

        /** No styles, no art, no fonts. What a widget falls back to when nobody has said anything. */
        val Nothing = Skin()

        /**
         * The skin a game gets when it has not written one.
         *
         * Neutral and dark, made of flat rounded boxes, and needing no atlas and no artist. A game
         * that registers nothing still has an interface somebody can use, and a game that is
         * writing its own can replace it a style at a time with [overriddenWith].
         *
         * It is an ordinary skin file — `src/commonMain/skins/default.json`, read by the same
         * loader a game's own file goes through. Nothing in the toolkit's code knows what colour a
         * button is, which is the whole point: if the default needed a special case, the skin
         * system would not be finished.
         */
        val Default: Skin by lazy { SkinFormat.read(DEFAULT_SKIN_JSON) }
    }
}
