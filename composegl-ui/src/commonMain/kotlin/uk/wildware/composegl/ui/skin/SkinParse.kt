package uk.wildware.composegl.ui.skin

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.ArtAtlas
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.EdgeMode
import uk.wildware.composegl.ui.graphics.NinePatch
import uk.wildware.composegl.ui.graphics.TextureHandle
import uk.wildware.composegl.ui.layout.Padding
import uk.wildware.composegl.ui.skin.json.Json
import uk.wildware.composegl.ui.skin.json.JsonArray
import uk.wildware.composegl.ui.skin.json.JsonNumber
import uk.wildware.composegl.ui.skin.json.JsonObject
import uk.wildware.composegl.ui.skin.json.JsonReader
import uk.wildware.composegl.ui.skin.json.JsonText
import uk.wildware.composegl.ui.text.FontProvider
import uk.wildware.composegl.ui.text.TextStyle

/**
 * The skin file's meaning, once [JsonReader] has dealt with its shape.
 *
 * Every method here either produces a value or throws, and every throw names the line and, where it
 * can, the nearest name that would have worked. That is the whole job: a skin file is edited by
 * somebody who is not going to read a stack trace.
 */
internal class SkinParse(private val art: ArtAtlas?, private val fonts: FontProvider?) {

    fun skin(text: String): Skin {
        val root = JsonReader.read(text).obj("the skin file")
        root.allow(setOf("defaults", "styles"))

        val defaults = root["defaults"]?.let { ResolvedStyle.Plain.with(state(it, TextStyle.Default)) }
        val base = defaults?.textStyle ?: TextStyle.Default
        val styles = root["styles"]?.obj("\"styles\"")?.fields.orEmpty()
            .mapValues { (name, value) -> style(value, base, name) }

        return Skin(styles = styles, fonts = fonts, art = art, defaults = defaults)
    }

    private fun style(value: Json, defaultText: TextStyle, name: String): Style {
        val json = value.obj("the style \"$name\"")
        json.allow(StateKeys + StyleStates)
        // A style *is* its base, written without a wrapper, plus the states that differ from it.
        val base = fields(json, defaultText)
        val inherited = base.textStyle ?: defaultText
        return Style(
            base = base,
            hovered = json["hovered"]?.let { state(it, inherited) },
            focused = json["focused"]?.let { state(it, inherited) },
            pressed = json["pressed"]?.let { state(it, inherited) },
            disabled = json["disabled"]?.let { state(it, inherited) },
        )
    }

    /**
     * One state's worth of overrides.
     *
     * @param inherited the text style a `"text"` block here changes. A state that sets only a size
     *   keeps the family the style already named, which is what anybody writing the file expects.
     */
    private fun state(value: Json, inherited: TextStyle): StateStyle {
        val json = value.obj("a state")
        json.allow(StateKeys)
        return fields(json, inherited)
    }

    /** The same keys, in an object whose other keys have already been accounted for. */
    private fun fields(json: JsonObject, inherited: TextStyle): StateStyle =
        StateStyle(
            background = json["background"]?.let { drawable(it) },
            textColour = json["textColour"]?.colour(),
            tint = json["tint"]?.colour(),
            padding = json["padding"]?.padding(),
            textStyle = json["text"]?.let { text(it, inherited) },
            contentOffset = json["contentOffset"]?.offset(),
        )

    private fun text(value: Json, inherited: TextStyle): TextStyle {
        val json = value.obj("a \"text\" block")
        json.allow(setOf("font", "size", "lineHeight", "maxLines", "ellipsis"))
        return TextStyle(
            family = json["font"]?.text("\"font\"") ?: inherited.family,
            size = json["size"]?.number("\"size\"") ?: inherited.size,
            lineHeightRatio = json["lineHeight"]?.number("\"lineHeight\"") ?: inherited.lineHeightRatio,
            maxLines = json["maxLines"]?.number("\"maxLines\"")?.toInt() ?: inherited.maxLines,
            ellipsis = json["ellipsis"]?.text("\"ellipsis\"") ?: inherited.ellipsis,
        )
    }

    private fun drawable(value: Json): SkinDrawable {
        if (value is JsonText) {
            if (value.value == "none") return SkinDrawable.Blank
            fail("a background is \"none\" or an object, not \"${value.value}\"", value)
        }
        val json = value.obj("a background")
        json.allow(setOf("patch", "fill", "image", "slice", "padding", "corner", "border", "borderWidth", "edges"))
        val kinds = listOf("patch", "fill", "image").filter { it in json.keys }
        if (kinds.size != 1) {
            fail(
                if (kinds.isEmpty()) "a background has to say \"patch\", \"fill\" or \"image\""
                else "a background says ${kinds.joinToString(" and ") { "\"$it\"" }}, and can only be one",
                json,
            )
        }
        return when (kinds.single()) {
            "patch" -> patch(json)
            "fill" -> SkinDrawable.Fill(
                colour = json.getValue("fill").colour(),
                corner = json["corner"]?.number("\"corner\"") ?: 0f,
                border = json["border"]?.colour(),
                borderWidth = json["borderWidth"]?.number("\"borderWidth\"") ?: json["border"]?.let { 1f } ?: 0f,
                padding = json["padding"]?.padding() ?: Padding.None,
            )
            else -> SkinDrawable.Image(
                texture = region(json.getValue("image")),
                padding = json["padding"]?.padding() ?: Padding.None,
            )
        }
    }

    private fun patch(json: JsonObject): SkinDrawable.Patch {
        val slice = json["slice"]?.padding()
            ?: fail("a nine-patch needs a \"slice\": how many pixels of the art are its border", json)
        val edges = json["edges"]?.obj("\"edges\"")
        edges?.allow(setOf("left", "top", "right", "bottom", "centreAcross", "centreDown"))
        fun edge(name: String) = edges?.get(name)?.edgeMode() ?: EdgeMode.Stretch
        return SkinDrawable.Patch(
            NinePatch(
                texture = region(json.getValue("patch")),
                slice = slice,
                padding = json["padding"]?.padding() ?: slice,
                leftEdge = edge("left"),
                topEdge = edge("top"),
                rightEdge = edge("right"),
                bottomEdge = edge("bottom"),
                centreAcross = edge("centreAcross"),
                centreDown = edge("centreDown"),
            ),
        )
    }

    /**
     * A region name turned into something drawable, or a stop.
     *
     * The loud failure the whole file format is built around. A misspelled region that loaded
     * anyway would draw nothing, and nothing looks exactly like a widget that has not been written
     * yet — so it gets found days later, by somebody else.
     */
    private fun region(value: Json): TextureHandle {
        val name = value.text("a region name")
        val atlas = art ?: fail("the file names the region \"$name\", but no atlas was loaded", value)
        return atlas.region(name) ?: fail(
            "no region called \"$name\" in the atlas${suggest(name, atlas.names)}",
            value,
        )
    }

    private fun Json.edgeMode(): EdgeMode = when (val name = text("an edge")) {
        "stretch" -> EdgeMode.Stretch
        "tile" -> EdgeMode.Tile
        else -> fail("an edge is \"stretch\" or \"tile\", not \"$name\"", this)
    }

    private fun Json.colour(): Colour {
        val text = text("a colour")
        val digits = text.removePrefix("#")
        if (!text.startsWith("#") || (digits.length != 6 && digits.length != 8)) {
            fail("a colour is \"#RRGGBB\" or \"#AARRGGBB\", not \"$text\"", this)
        }
        val value = digits.toLongOrNull(16)
            ?: fail("\"$text\" has something in it that is not a hexadecimal digit", this)
        return if (digits.length == 6) Colour.rgb(value) else Colour.argb(value)
    }

    private fun Json.padding(): Padding = when (this) {
        is JsonNumber -> Padding.all(value.toFloat())
        is JsonArray -> when (items.size) {
            2 -> Padding.symmetric(items[0].number("a padding"), items[1].number("a padding"))
            4 -> Padding(
                items[0].number("a padding"), items[1].number("a padding"),
                items[2].number("a padding"), items[3].number("a padding"),
            )
            else -> fail("a padding is a number, two numbers or four, not ${items.size}", this)
        }
        else -> fail("a padding is a number, \"[horizontal, vertical]\" or \"[left, top, right, bottom]\"", this)
    }

    private fun Json.offset(): Offset {
        val items = (this as? JsonArray)?.items ?: fail("an offset is written \"[x, y]\"", this)
        if (items.size != 2) fail("an offset is written \"[x, y]\", and this has ${items.size}", this)
        return Offset(items[0].number("an offset"), items[1].number("an offset"))
    }

    private fun Json.obj(what: String): JsonObject =
        this as? JsonObject ?: fail("$what has to be an object, written \"{ … }\"", this)

    private fun Json.text(what: String): String =
        (this as? JsonText)?.value ?: fail("$what has to be a piece of text, in quotes", this)

    private fun Json.number(what: String): Float =
        (this as? JsonNumber)?.value?.toFloat() ?: fail("$what has to be a number", this)

    private fun JsonObject.getValue(key: String): Json = fields.getValue(key)

    /**
     * Stops on any key the format does not have.
     *
     * `textColor` for `textColour` is the example that matters: a lenient reader would take the
     * file, ignore the line, and leave somebody staring at a colour that will not change.
     */
    private fun JsonObject.allow(keys: Set<String>) {
        val unknown = this.keys.firstOrNull { it !in keys } ?: return
        fail("\"$unknown\" is not something a skin file says${suggest(unknown, keys)}", this)
    }

    /** The nearest names that would have worked, if any are near enough to be worth guessing at. */
    private fun suggest(name: String, candidates: Collection<String>): String {
        val near = candidates.map { it to distance(name.lowercase(), it.lowercase()) }
            .filter { (_, distance) -> distance <= (name.length / 3).coerceAtLeast(2) }
            .sortedBy { (_, distance) -> distance }
            .take(3)
            .map { (candidate, _) -> "\"$candidate\"" }
        return if (near.isEmpty()) "" else ". Did you mean ${near.joinToString(" or ")}?"
    }

    private fun distance(from: String, to: String): Int {
        var previous = IntArray(to.length + 1) { it }
        for (i in 1..from.length) {
            val current = IntArray(to.length + 1)
            current[0] = i
            for (j in 1..to.length) {
                val substitute = previous[j - 1] + if (from[i - 1] == to[j - 1]) 0 else 1
                current[j] = minOf(substitute, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[to.length]
    }

    private fun fail(message: String, at: Json): Nothing =
        throw SkinFormatException("line ${at.line}: $message")

    private companion object {

        /** Everything a state can say. A style's own keys are these plus the four state names. */
        val StateKeys = setOf("background", "textColour", "tint", "padding", "text", "contentOffset")

        val StyleStates = setOf("hovered", "focused", "pressed", "disabled")
    }
}
