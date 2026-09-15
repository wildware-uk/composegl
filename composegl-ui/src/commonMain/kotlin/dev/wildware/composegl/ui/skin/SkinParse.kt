package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.EdgeMode
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.graphics.NineRegions
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.skin.json.Json
import dev.wildware.composegl.ui.skin.json.JsonArray
import dev.wildware.composegl.ui.skin.json.JsonNumber
import dev.wildware.composegl.ui.skin.json.JsonObject
import dev.wildware.composegl.ui.skin.json.JsonReader
import dev.wildware.composegl.ui.skin.json.JsonText
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextStyle

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
        json.allow(
            setOf("patch", "fill", "gradient", "image", "slice", "padding", "corner", "border", "borderWidth", "edges"),
        )
        val kinds = listOf("patch", "fill", "gradient", "image").filter { it in json.keys }
        if (kinds.size != 1) {
            fail(
                if (kinds.isEmpty()) "a background has to say \"patch\", \"fill\", \"gradient\" or \"image\""
                else "a background says ${kinds.joinToString(" and ") { "\"$it\"" }}, and can only be one",
                json,
            )
        }
        return when (kinds.single()) {
            "patch" -> patch(json)
            "fill" -> SkinDrawable.Fill(
                colour = json.getValue("fill").colour(),
                corners = json["corner"]?.corners() ?: Corners.None,
                border = json["border"]?.colour(),
                borderWidth = json["borderWidth"]?.number("\"borderWidth\"") ?: json["border"]?.let { 1f } ?: 0f,
                padding = json["padding"]?.padding() ?: Padding.None,
            )
            "gradient" -> SkinDrawable.Gradient(
                brush = brush(json.getValue("gradient")),
                corners = json["corner"]?.corners() ?: Corners.None,
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

    /**
     * A gradient: which way it runs, as the key, and its two colours, as a list.
     *
     * ```jsonc
     * { "vertical": ["#3A6EA5", "#1B2A41"] }
     * { "horizontal": ["#4CD964", "#FF3B30"] }
     * { "linear": ["#FFFFFF", "#00FFFFFF"], "angle": 45 }
     * { "radial": ["#00000000", "#C0000000"] }
     * ```
     *
     * The direction is a word rather than an angle for the two everybody means, so a file reads as
     * what it draws. `angle` only belongs with `linear`, and saying it anywhere else is refused
     * rather than ignored — a vertical gradient that quietly ignores its angle is the `textColor`
     * mistake again.
     */
    private fun brush(value: Json): Brush {
        val json = value.obj("a \"gradient\"")
        json.allow((GradientKinds + "angle").toSet())
        val kinds = GradientKinds.filter { it in json.keys }
        if (kinds.size != 1) {
            fail(
                if (kinds.isEmpty()) "a gradient has to say ${GradientKinds.joinToString(", ") { "\"$it\"" }}"
                else "a gradient says ${kinds.joinToString(" and ") { "\"$it\"" }}, and can only run one way",
                json,
            )
        }
        val kind = kinds.single()
        val stops = json.getValue(kind)
        val colours = (stops as? JsonArray)?.items
            ?: fail("\"$kind\" is its two colours, written \"[\"#RRGGBB\", \"#RRGGBB\"]\"", stops)
        if (colours.size != 2) {
            fail("a gradient runs between two colours, and this has ${colours.size}", stops)
        }
        val from = colours[0].colour()
        val to = colours[1].colour()
        val angle = json["angle"]
        if (angle != null && kind != "linear") {
            fail("only a \"linear\" gradient has an \"angle\"; a \"$kind\" one already says which way it runs", angle)
        }
        return when (kind) {
            "vertical" -> Brush.vertical(from, to)
            "horizontal" -> Brush.horizontal(from, to)
            "radial" -> Brush.radial(from, to)
            else -> Brush.linear(
                from,
                to,
                angle?.number("\"angle\"") ?: fail("a \"linear\" gradient needs an \"angle\", in degrees clockwise from right", json),
            )
        }
    }

    private fun patch(json: JsonObject): SkinDrawable.Patch {
        val from = json.getValue("patch")
        val edges = json["edges"]?.obj("\"edges\"")
        edges?.allow(setOf("left", "top", "right", "bottom", "centreAcross", "centreDown"))
        fun edge(name: String) = edges?.get(name)?.edgeMode() ?: EdgeMode.Stretch

        // "patch" is a region name, or an object naming the nine pieces one at a time. A patch
        // built out of pieces already knows where its cuts are, so it has no "slice" to write.
        if (from is JsonObject) {
            val pieces = regions(from)
            json["slice"]?.let {
                fail(
                    "a patch cut into nine pieces is its own slice — its pieces say how thick each " +
                        "border is — so it cannot also say \"slice\"",
                    it,
                )
            }
            return SkinDrawable.Patch(
                blaming(from) {
                    NinePatch.of(
                        regions = pieces,
                        padding = json["padding"]?.padding() ?: pieces.slice,
                        leftEdge = edge("left"),
                        topEdge = edge("top"),
                        rightEdge = edge("right"),
                        bottomEdge = edge("bottom"),
                        centreAcross = edge("centreAcross"),
                        centreDown = edge("centreDown"),
                    )
                },
            )
        }

        val slice = json["slice"]?.padding()
            ?: fail("a nine-patch needs a \"slice\": how many pixels of the art are its border", json)
        return SkinDrawable.Patch(
            blaming(json) {
                NinePatch(
                    texture = region(from),
                    slice = slice,
                    padding = json["padding"]?.padding() ?: slice,
                    leftEdge = edge("left"),
                    topEdge = edge("top"),
                    rightEdge = edge("right"),
                    bottomEdge = edge("bottom"),
                    centreAcross = edge("centreAcross"),
                    centreDown = edge("centreDown"),
                )
            },
        )
    }

    /**
     * The nine pieces, each one optional.
     *
     * A piece nobody names means that row or column has no slice, which is how a three-piece
     * scrollbar track is written: a left cap, a middle and a right cap, and no top or bottom at all.
     */
    private fun regions(json: JsonObject): NineRegions {
        json.allow(RegionKeys)
        fun piece(name: String) = json[name]?.let { region(it) }
        return blaming(json) {
            NineRegions(
                topLeft = piece("topLeft"),
                top = piece("top"),
                topRight = piece("topRight"),
                left = piece("left"),
                centre = piece("centre"),
                right = piece("right"),
                bottomLeft = piece("bottomLeft"),
                bottom = piece("bottom"),
                bottomRight = piece("bottomRight"),
            )
        }
    }

    /**
     * Whatever the art itself refuses, said on the line that asked for it.
     *
     * A nine-patch checks its own arithmetic, and those complaints are written for somebody reading
     * Kotlin. Somebody editing a skin file needs the same words with a line number on the front,
     * and has no stack trace to go and find one in.
     */
    private inline fun <T> blaming(at: Json, build: () -> T): T = try {
        build()
    } catch (problem: SkinFormatException) {
        throw problem
    } catch (problem: IllegalArgumentException) {
        fail(problem.message ?: "this nine-patch does not add up", at)
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

    /**
     * A corner radius: one number for all four, four numbers clockwise from the top-left, or an
     * object naming only the corners that are rounded.
     *
     * No two-number form, unlike padding. Two radii could mean top and bottom, or left and right,
     * or the two diagonals, and a file that means one of those and is read as another draws a
     * shape nobody asked for without saying anything.
     */
    private fun Json.corners(): Corners = when (this) {
        // Through [blaming], so that [Corners]' own refusal of a negative radius is said on the
        // line that wrote one.
        is JsonNumber -> blaming(this) { Corners.all(value.toFloat()) }
        is JsonArray -> {
            if (items.size != 4) {
                fail(
                    "a corner is one number, or four written \"[topLeft, topRight, bottomRight, " +
                        "bottomLeft]\", not ${items.size}",
                    this,
                )
            }
            val radii = items.map { it.number("a corner") }
            blaming(this) { Corners(radii[0], radii[1], radii[2], radii[3]) }
        }
        is JsonObject -> {
            allow(CornerKeys)
            fun named(key: String) = this[key]?.number("\"$key\"") ?: 0f
            blaming(this) {
                Corners(named("topLeft"), named("topRight"), named("bottomRight"), named("bottomLeft"))
            }
        }
        else -> fail(
            "a corner is a number, \"[topLeft, topRight, bottomRight, bottomLeft]\" or an object " +
                "such as { \"topLeft\": 8, \"topRight\": 8 }",
            this,
        )
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

        /** The corners a `"corner"` object can name. Any it leaves out are square. */
        val CornerKeys = setOf("topLeft", "topRight", "bottomRight", "bottomLeft")

        /** The ways a gradient can run, in the order a mistake lists them. */
        val GradientKinds = listOf("vertical", "horizontal", "linear", "radial")

        /** The nine pieces a patch can be cut from, when the host cut them itself. */
        val RegionKeys = setOf(
            "topLeft", "top", "topRight",
            "left", "centre", "right",
            "bottomLeft", "bottom", "bottomRight",
        )
    }
}
