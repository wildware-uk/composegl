package uk.wildware.composegl.ui.skin.json

/**
 * A parsed JSON value, with the line it came from.
 *
 * The line is the reason this exists at all rather than a `Map<String, Any?>`. A skin file is
 * edited by hand, usually by somebody who is not looking at the code, so every complaint the loader
 * makes has to point at a line in their editor. Carrying the position on the value is the only way
 * to still have it by the time the loader notices that a region name is wrong.
 *
 * Internal, and deliberately small: this is enough JSON to read a skin file, not a JSON library.
 */
internal sealed interface Json {

    /** The line the value starts on, counting from one. */
    val line: Int
}

internal data class JsonText(val value: String, override val line: Int) : Json

internal data class JsonNumber(val value: Double, override val line: Int) : Json

internal data class JsonBool(val value: Boolean, override val line: Int) : Json

internal data class JsonNull(override val line: Int) : Json

internal data class JsonArray(val items: List<Json>, override val line: Int) : Json

internal data class JsonObject(val fields: Map<String, Json>, override val line: Int) : Json {

    operator fun get(key: String): Json? = fields[key]

    val keys: Set<String> get() = fields.keys
}
