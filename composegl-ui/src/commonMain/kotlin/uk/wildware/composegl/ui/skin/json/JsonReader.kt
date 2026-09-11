package uk.wildware.composegl.ui.skin.json

import uk.wildware.composegl.ui.skin.SkinFormatException

/**
 * Turns skin-file text into [Json].
 *
 * A hand-written parser, for one reason: this module is allowed the Compose runtime, coroutines and
 * the standard library, and nothing else. That rule is checked by the build, and it is what keeps
 * the toolkit able to run on a phone. A couple of hundred lines of parser is a fair price for it.
 *
 * Two departures from strict JSON, both because a skin file is written by hand:
 *
 * - comments, both the `//` kind that runs to the end of the line and the slash-star kind
 *   that runs across lines;
 * - a trailing comma at the end of an object or a list.
 *
 * Neither changes what a valid JSON file means, so a skin file is still something any other tool
 * can read once the comments are gone.
 */
internal object JsonReader {

    fun read(text: String): Json = Scanner(text).parse()
}

private class Scanner(private val source: String) {

    private var index = 0
    private var line = 1
    private var lineStart = 0

    fun parse(): Json {
        val value = value()
        skipBlanks()
        if (index < source.length) fail("unexpected ${describe(peekOrNull())} after the end of the value")
        return value
    }

    private fun value(): Json {
        skipBlanks()
        if (index >= source.length) fail("the file ended where a value was expected")
        val at = line
        return when (peek()) {
            '{' -> obj()
            '[' -> array()
            '"' -> JsonText(string(), at)
            't', 'f' -> JsonBool(keyword("true", "false") == "true", at)
            'n' -> { keyword("null"); JsonNull(at) }
            else -> number()
        }
    }

    private fun obj(): Json {
        val at = line
        val fields = LinkedHashMap<String, Json>()
        expect('{')
        while (true) {
            skipBlanks()
            when (peekOrNull()) {
                '}' -> { index++; break }   // an empty object, or a trailing comma
                null -> fail("the file ended before this object was closed", at)
                '"' -> Unit
                else -> fail("a name in quotes was expected, found ${describe(peekOrNull())}")
            }
            val nameAt = line
            val name = string()
            expect(':')
            val existing = fields.put(name, value())
            if (existing != null) fail("\"$name\" is named twice in the same object", nameAt)
            skipBlanks()
            when (peekOrNull()) {
                ',' -> index++
                '}' -> { index++; break }
                null -> fail("the file ended before this object was closed", at)
                else -> fail("a comma or a closing brace was expected, found ${describe(peekOrNull())}")
            }
        }
        return JsonObject(fields, at)
    }

    private fun array(): Json {
        val at = line
        val items = mutableListOf<Json>()
        expect('[')
        while (true) {
            skipBlanks()
            when (peekOrNull()) {
                ']' -> { index++; break }   // an empty list, or a trailing comma
                null -> fail("the file ended before this list was closed", at)
                else -> Unit
            }
            items += value()
            skipBlanks()
            when (peekOrNull()) {
                ',' -> index++
                ']' -> { index++; break }
                null -> fail("the file ended before this list was closed", at)
                else -> fail("a comma or a closing bracket was expected, found ${describe(peekOrNull())}")
            }
        }
        return JsonArray(items, at)
    }

    private fun string(): String {
        expect('"')
        val text = StringBuilder()
        while (true) {
            if (index >= source.length) fail("the file ended in the middle of a piece of text")
            when (val character = source[index++]) {
                '"' -> return text.toString()
                '\\' -> text.append(escape())
                '\n' -> fail("a piece of text cannot run across two lines; write \\n instead")
                else -> text.append(character)
            }
        }
    }

    private fun escape(): Char {
        if (index >= source.length) fail("the file ended after a backslash")
        return when (val character = source[index++]) {
            '"', '\\', '/' -> character
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> unicode()
            else -> fail("\\$character does not mean anything")
        }
    }

    private fun unicode(): Char {
        if (index + 4 > source.length) fail("\\u needs four hexadecimal digits")
        val digits = source.substring(index, index + 4)
        index += 4
        return digits.toIntOrNull(16)?.toChar() ?: fail("\\u$digits is not four hexadecimal digits")
    }

    private fun number(): Json {
        val at = line
        val start = index
        if (peek() == '-' || peek() == '+') index++
        while (index < source.length && (source[index].isDigit() || source[index] in ".eE+-")) index++
        val text = source.substring(start, index)
        val value = text.toDoubleOrNull() ?: fail("\"$text\" is not a number", at)
        return JsonNumber(value, at)
    }

    private fun keyword(vararg words: String): String {
        words.forEach { word ->
            if (source.startsWith(word, index)) {
                index += word.length
                return word
            }
        }
        fail("${describe(peekOrNull())} starts something that is not a value")
    }

    /** Whitespace and comments, which to everything above this are the same thing. */
    private fun skipBlanks() {
        while (index < source.length) {
            when {
                source[index] == '\n' -> { index++; line++; lineStart = index }
                source[index].isWhitespace() -> index++
                source.startsWith("//", index) -> while (index < source.length && source[index] != '\n') index++
                source.startsWith("/*", index) -> skipBlockComment()
                else -> return
            }
        }
    }

    private fun skipBlockComment() {
        val at = line
        index += 2
        while (!source.startsWith("*/", index)) {
            if (index >= source.length) fail("the file ended inside a comment", at)
            if (source[index] == '\n') { line++; lineStart = index + 1 }
            index++
        }
        index += 2
    }

    private fun peek(): Char = source[index]

    private fun peekOrNull(): Char? = source.getOrNull(index)

    private fun expect(character: Char) {
        skipBlanks()
        if (peekOrNull() != character) fail("'$character' was expected, found ${describe(peekOrNull())}")
        index++
    }

    private fun describe(character: Char?): String = when (character) {
        null -> "the end of the file"
        '\n' -> "the end of the line"
        else -> "'$character'"
    }

    private fun fail(message: String, at: Int = line): Nothing =
        throw SkinFormatException("line $at, column ${index - lineStart + 1}: $message")
}
