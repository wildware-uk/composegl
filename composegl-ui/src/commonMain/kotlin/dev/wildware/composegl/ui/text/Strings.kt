package dev.wildware.composegl.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection

/**
 * A language, and optionally where it is spoken: `en`, `pt-BR`, `ar`.
 *
 * Deliberately not the platform's locale class, which does not exist on every target this toolkit
 * runs on and knows a great deal a game's menus never ask. What a screen needs from a locale is which
 * strings to show and which way to lay them out, and that is all this is.
 *
 * @param language the two- or three-letter language code, in lower case.
 * @param region the country or region, in upper case, or null for the language anywhere.
 */
data class Locale(val language: String, val region: String? = null) {

    init {
        require(language.isNotBlank()) { "a locale needs a language" }
    }

    /** `pt-BR`, or `pt` with no region. What a string table is filed under. */
    val tag: String get() = if (region == null) language else "$language-$region"

    /**
     * Which way the language reads, and so which way a screen in it is laid out. Right to left for
     * Arabic, Hebrew, Persian, Urdu and the other languages written in those scripts.
     */
    val direction: LayoutDirection
        get() = if (language in RightToLeftLanguages) LayoutDirection.Rtl else LayoutDirection.Ltr

    override fun toString() = tag

    companion object {
        val English = Locale("en")

        /**
         * Reads a tag the way platforms write them — `pt-BR`, `pt_br`, `AR` — into a locale with its
         * case put right. Anything after the region, a script or a variant, is ignored.
         */
        fun of(tag: String): Locale {
            val parts = tag.trim().split('-', '_')
            require(parts[0].isNotBlank()) { "\"$tag\" has no language in it" }
            return Locale(parts[0].lowercase(), parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.uppercase())
        }
    }
}

/** The languages written right to left: Arabic, Hebrew, Persian, Urdu, Pashto, Sindhi, Uyghur, Yiddish, Dhivehi, Sorani. */
private val RightToLeftLanguages = setOf("ar", "he", "iw", "fa", "ur", "ps", "sd", "ug", "yi", "dv", "ckb")

/**
 * Every piece of text a game shows, in every language it ships in.
 *
 * ```kotlin
 * val strings = Strings(
 *     mapOf(
 *         Locale.English to mapOf("play" to "Play", "greeting" to "Hello, {name}"),
 *         Locale("he") to Strings.parse(heFile.readText()),
 *     ),
 * )
 * ProvideLocale(Locale("he"), strings) { Button(stringOf("play"), onClick = ::start) }
 * ```
 *
 * A key is looked up in the most specific table first and the least specific last: `pt-BR`, then
 * `pt`, then the [fallback] locale, then its language. A key found nowhere comes back as the key
 * itself, so a missing translation shows up on the screen as `menu.quit` rather than as a blank
 * button nobody notices; [missingFrom] finds them all before a player does.
 *
 * Placeholders are `{0}`, `{1}` for arguments in order, or `{name}` for the named ones. `{{` and `}}`
 * are a literal brace.
 *
 * @param tables each locale's strings, key to text.
 * @param fallback the language every key is written in, used for any key a translation lacks.
 */
class Strings(tables: Map<Locale, Map<String, String>>, val fallback: Locale = Locale.English) {

    private val tables: Map<String, Map<String, String>> = tables.entries.associate { it.key.tag to it.value.toMap() }

    /** The locales there is a table for. */
    val locales: Set<Locale> = tables.keys

    /** [key] in [locale], with [args] put into its `{0}`, `{1}` placeholders. */
    fun get(locale: Locale, key: String, vararg args: Any?): String = format(lookup(locale, key) ?: key, args, emptyMap())

    /** [key] in [locale], with [named] put into its `{name}` placeholders. */
    fun get(locale: Locale, key: String, named: Map<String, Any?>): String =
        format(lookup(locale, key) ?: key, emptyArray(), named)

    /**
     * [key] for [count] of something, in the form the language uses for that number. The count is
     * `{0}`, and [args] follow it from `{1}`.
     *
     * ```
     * lives.zero = No lives left
     * lives.one = One life left
     * lives.other = {0} lives left
     * ```
     *
     * The forms are `zero`, `one`, `two`, `few`, `many` and `other`, and which a number takes is the
     * language's rule: in Arabic 3 to 10 are `few` and 11 to 99 `many`; in Russian, Ukrainian and
     * Polish 2 to 4 are `few` and 5 to 20 `many`, but 21 is `one` again; Czech and Slovak have `few`
     * for 2 to 4. Every other language is `one` for 1 and `other` for the rest, with `two` for 2
     * where a table has it. A `zero` form in a table is used for nought in any language, since a
     * game often wants "No lives left" there even where the grammar has no such form. A form a table
     * lacks falls back to `other`.
     */
    fun plural(locale: Locale, key: String, count: Int, vararg args: Any?): String {
        // Every form from the one table that has the word at all. Asked form by form down the whole
        // chain, a Hebrew count of nought would find English's "No lives" before Hebrew's own other.
        val prefix = "$key."
        val table = chainOf(locale).firstOrNull { entries -> entries.keys.any { it.startsWith(prefix) } }
            ?: return "${key}.other"
        // The language whose table answered decides the rule, not the one asked for: a Russian player
        // shown the English fallback wants English grammar.
        val language = languageOf(table) ?: locale.language
        val natural = pluralForm(language, count)
        val form = when {
            count == 0 && "$key.zero" in table -> "zero"
            natural == "other" && count == 2 && "$key.two" in table -> "two"
            else -> natural
        }
        val chosen = table["$key.$form"] ?: table["$key.other"] ?: return "$key.$form"
        return format(chosen, arrayOf<Any?>(count, *args), emptyMap())
    }

    /** The language a table from [chainOf] was filed under, found by identity since it is that very map. */
    private fun languageOf(table: Map<String, String>): String? =
        tables.entries.firstOrNull { it.value === table }?.key?.substringBefore('-')

    /**
     * The keys the [fallback] table has that [locale] does not translate, in its own table or its
     * language's. A test asserting this is empty for every shipped locale catches the string added
     * in English and forgotten everywhere else.
     */
    fun missingFrom(locale: Locale): Set<String> {
        val own = tables[locale.tag].orEmpty()
        val language = tables[locale.language].orEmpty()
        val wanted = tables[fallback.tag] ?: tables[fallback.language].orEmpty()
        return wanted.keys.filterTo(LinkedHashSet()) { it !in own && it !in language }
    }

    private fun lookup(locale: Locale, key: String): String? {
        for (table in chainOf(locale)) table[key]?.let { return it }
        return null
    }

    /** The tables [locale] reads from, most specific first. */
    private fun chainOf(locale: Locale): List<Map<String, String>> =
        listOfNotNull(tables[locale.tag], tables[locale.language], tables[fallback.tag], tables[fallback.language])

    companion object {

        /** No strings at all: every key is shown as itself. What a screen has until a game provides some. */
        val Empty = Strings(emptyMap())

        /**
         * A table written as a text file, one string per line:
         *
         * ```
         * # the main menu
         * menu.play = Play
         * menu.greeting = Hello, {name}
         * credits.body = Made by\nsomebody
         * ```
         *
         * Space around the `=` is ignored, `#` starts a comment line, and `\n` in a value is a line
         * break (`\\` a backslash). A line with no `=` is a mistake and is reported with its number,
         * rather than silently dropped and discovered as a key showing on the screen.
         */
        fun parse(source: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            source.lines().forEachIndexed { number, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val equals = line.indexOf('=')
                require(equals > 0) { "line ${number + 1} is not key = value: \"$raw\"" }
                result[line.substring(0, equals).trim()] = unescape(line.substring(equals + 1).trim())
            }
            return result
        }

        private fun unescape(value: String): String {
            if ('\\' !in value) return value
            val out = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character == '\\' && index + 1 < value.length) {
                    when (value[index + 1]) {
                        'n' -> out.append('\n')
                        '\\' -> out.append('\\')
                        else -> out.append(character).append(value[index + 1])
                    }
                    index += 2
                } else {
                    out.append(character)
                    index++
                }
            }
            return out.toString()
        }

        private fun format(pattern: String, args: Array<out Any?>, named: Map<String, Any?>): String {
            if ('{' !in pattern && '}' !in pattern) return pattern
            val out = StringBuilder(pattern.length + 16)
            var index = 0
            while (index < pattern.length) {
                val character = pattern[index]
                when {
                    character == '{' && pattern.startsWith("{{", index) -> {
                        out.append('{')
                        index += 2
                    }
                    character == '}' && pattern.startsWith("}}", index) -> {
                        out.append('}')
                        index += 2
                    }
                    character == '{' -> {
                        val close = pattern.indexOf('}', index)
                        val name = if (close < 0) null else pattern.substring(index + 1, close)
                        val position = name?.toIntOrNull()
                        val value = when {
                            name == null -> NotFound
                            position != null -> if (position in args.indices) args[position] else NotFound
                            name in named -> named[name]
                            else -> NotFound
                        }
                        // A placeholder nothing fills is left as it was written, where it can be seen.
                        if (value === NotFound || close < 0) {
                            out.append(character)
                            index++
                        } else {
                            out.append(value.toString())
                            index = close + 1
                        }
                    }
                    else -> {
                        out.append(character)
                        index++
                    }
                }
            }
            return out.toString()
        }

        private val NotFound = Any()
    }
}

/**
 * Which plural form [language] uses for [count], from the Unicode plural rules for whole numbers.
 *
 * Only the languages whose rules differ from "one for 1, other for the rest" are written out; every
 * language not named here gets that.
 */
internal fun pluralForm(language: String, count: Int): String {
    val n = if (count < 0) -count else count
    val lastDigit = n % 10
    val lastTwo = n % 100
    return when (language) {
        "ar" -> when {
            n == 0 -> "zero"
            n == 1 -> "one"
            n == 2 -> "two"
            lastTwo in 3..10 -> "few"
            lastTwo in 11..99 -> "many"
            else -> "other"
        }
        "ru", "uk", "be" -> when {
            lastDigit == 1 && lastTwo != 11 -> "one"
            lastDigit in 2..4 && lastTwo !in 12..14 -> "few"
            else -> "many"
        }
        "pl" -> when {
            n == 1 -> "one"
            lastDigit in 2..4 && lastTwo !in 12..14 -> "few"
            else -> "many"
        }
        "cs", "sk" -> when (n) {
            1 -> "one"
            in 2..4 -> "few"
            else -> "other"
        }
        "he", "iw" -> when (n) {
            1 -> "one"
            2 -> "two"
            else -> "other"
        }
        "ja", "zh", "ko", "th", "vi", "id", "ms" -> "other"
        else -> if (n == 1) "one" else "other"
    }
}

/** The player's language. English until a game provides one with [ProvideLocale]. */
val LocalLocale: ProvidableCompositionLocal<Locale> = staticCompositionLocalOf { Locale.English }

/** The game's strings. [Strings.Empty] until a game provides some with [ProvideLocale]. */
val LocalStrings: ProvidableCompositionLocal<Strings> = staticCompositionLocalOf { Strings.Empty }

/**
 * [content] in [locale]: its strings looked up in [strings], and laid out in the direction the
 * language reads — so providing Hebrew is all it takes to mirror a screen.
 *
 * Changing [locale] — a player picking a language on the options screen — recomposes what is inside,
 * so every [stringOf] reads the new language and every layout mirrors on the next frame.
 */
@Composable
fun ProvideLocale(locale: Locale, strings: Strings = LocalStrings.current, content: @Composable () -> Unit) =
    CompositionLocalProvider(
        LocalLocale provides locale,
        LocalStrings provides strings,
        LocalLayoutDirection provides locale.direction,
        content = content,
    )

/** [key] in the player's language, with [args] in its `{0}`, `{1}` placeholders. See [Strings]. */
@Composable
fun stringOf(key: String, vararg args: Any?): String = LocalStrings.current.get(LocalLocale.current, key, *args)

/** [key] for [count] of something, in the player's language. See [Strings.plural]. */
@Composable
fun pluralOf(key: String, count: Int, vararg args: Any?): String =
    LocalStrings.current.plural(LocalLocale.current, key, count, *args)
