package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.text.FontProvider

/**
 * A skin, read from and written back to a file.
 *
 * The point of a skin file is that the look of a game stops being a code change. An artist edits a
 * file, saves it, and sees it; nobody recompiles and nobody who is not a programmer has to ask a
 * programmer. Everything in this object exists to make that loop short and its failures obvious.
 *
 * ### The format
 *
 * JSON, with comments and trailing commas allowed because it is written by hand:
 *
 * ```json
 * {
 *   // What every style falls back to. Written once, not in forty places.
 *   "defaults": { "textColour": "#E6E6E6", "text": { "font": "body", "size": 16 } },
 *
 *   "styles": {
 *     "button": {
 *       "background": { "patch": "ui/button", "slice": 6, "padding": [12, 6] },
 *       "textColour": "#CCCCCC",
 *
 *       "hovered":  { "background": { "patch": "ui/button_hover", "slice": 6 } },
 *       "pressed":  { "contentOffset": [0, 1] },
 *       "disabled": { "tint": "#80FFFFFF" },
 *     },
 *
 *     // A name with a dot falls back to the name before it, so this inherits everything above.
 *     "button.danger": { "textColour": "#FF5C5C" }
 *   }
 * }
 * ```
 *
 * A style's own keys are its base: `background`, `textColour`, `tint`, `padding`, `contentOffset`
 * and `text`. The four state keys — `hovered`, `focused`, `pressed`, `disabled` — take the same
 * keys again and change only what they name.
 *
 * Backgrounds are one of four things:
 *
 * - `{ "patch": "region", "slice": 6 }` — art cut into nine. `slice` is required, `padding` defaults
 *   to it, and `edges` can set any of `left`, `top`, `right`, `bottom`, `centreAcross`,
 *   `centreDown` to `"stretch"` or `"tile"`.
 * - `{ "fill": "#203040", "corner": 4 }` — a flat box with no art at all.
 * - `{ "image": "region" }` — one picture stretched across the widget.
 * - `"none"` — nothing, which is how a flat state stops the base's box showing through.
 *
 * Padding is a number, a `[horizontal, vertical]` pair or a `[left, top, right, bottom]` four.
 * Offsets are `[x, y]`. Colours are `"#RRGGBB"` or `"#AARRGGBB"`.
 *
 * ### Failing loudly
 *
 * Reading is strict, and that is deliberate. A region name the atlas has never heard of, a key
 * spelled `textColor`, a colour missing its hash — each of these stops the load, names the line,
 * and suggests the nearest thing that would have worked. The alternative is a widget that quietly
 * draws nothing at four in the morning, which costs hours rather than seconds.
 *
 * Strict at *load*, not at draw. Once a skin has been read it never complains again: a style nobody
 * wrote resolves to something plain, because a half-written skin is the normal state of affairs for
 * weeks and a game that will not start because of one is a game nobody can work on.
 */
object SkinFormat {

    /**
     * Reads a skin from [text].
     *
     * @param art where region names are looked up. A file that names a region without one is an
     *   error, because the alternative is a background that silently is not there.
     * @param fonts carried onto the skin for widgets to measure with. Font *files* are a backend's
     *   business: the file names a family, and the game registers what that family means.
     * @throws SkinFormatException if the text is not valid JSON, or names something that is not
     *   there, or uses a key the format does not have.
     */
    fun read(text: String, art: ArtAtlas? = null, fonts: FontProvider? = null): Skin =
        SkinParse(art, fonts).skin(text)

    /**
     * Writes [skin] back out as a skin file.
     *
     * Round-trips: reading what this writes gives back the same styles. That is what makes a tool
     * that edits a skin possible, and it is the cheapest test there is that the reader and the
     * format agree.
     *
     * Needs the skin's own [Skin.art] to turn textures back into the names they were loaded under.
     * A texture the atlas does not contain cannot be written, and says so.
     */
    fun write(skin: Skin): String = SkinWrite(skin.art).skin(skin)
}

/**
 * A skin file that cannot be read, or cannot mean what it says.
 *
 * One exception for the shape of the text and for the sense of it, because to somebody fixing a
 * skin file the difference is academic: either way, a line is wrong, and the message names it.
 */
class SkinFormatException(message: String) : IllegalArgumentException(message)
