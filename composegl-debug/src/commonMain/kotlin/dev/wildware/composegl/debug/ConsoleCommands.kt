package dev.wildware.composegl.debug

import kotlin.reflect.KClass

/**
 * One argument of a console command: what it is called, what it is read as, and what it suggests.
 *
 * Made with [arg], never by hand:
 *
 * ```kotlin
 * command("give", arg<String>("item", suggest = { items.ids }), arg<Int>("count", default = 1)) { id, n -> give(id, n) }
 * ```
 *
 * The type is what turns `"0.2"` into a `Float` before the game's lambda ever runs, and what writes
 * the error when it is not one — so a command is a function of the types it wants, rather than a
 * function of strings that each has to check for itself.
 *
 * @param name what it is called in `help`, in a usage line and in an error.
 * @param typeName the type as a person reads it — "number", "whole number", "text", "true or false".
 * @param default what is used when the player leaves it out. Null means the argument is required.
 */
class ConsoleArg<T : Any> @PublishedApi internal constructor(
    val name: String,
    val typeName: String,
    val default: T?,
    @PublishedApi internal val parse: (String) -> Any?,
    @PublishedApi internal val suggest: () -> List<String>,
) {

    /** Whether the player may leave it out, which is exactly whether it has a [default]. */
    val optional: Boolean get() = default != null

    /** `<item>` when it is required and `[count]` when it is not, the way a usage line reads. */
    val usage: String get() = if (optional) "[$name]" else "<$name>"

    override fun toString(): String = "$usage: $typeName"
}

/**
 * An argument of type [T], for [ConsoleScope.command].
 *
 * Five types, which are the five a command line has any business carrying: [String], [Int], [Long],
 * [Float], [Double] and [Boolean]. Anything else is a game's own object, and the console has no way
 * to turn a word into one — take a [String] and look it up in the command itself, which is what the
 * `item` argument above does.
 *
 * @param default what is used when the player leaves it out. Null makes it required, and an optional
 *   argument may not be followed by a required one.
 * @param suggest what Tab offers for it. Called each time the player asks, so a list that changes
 *   while the game runs — the items that exist now, the levels loaded now — suggests what is there
 *   rather than what was there when the console was built. `true` and `false` are offered for a
 *   [Boolean] without one.
 * @throws IllegalArgumentException if [T] is not one of the types above.
 */
inline fun <reified T : Any> arg(
    name: String,
    default: T? = null,
    noinline suggest: (() -> List<String>)? = null,
): ConsoleArg<T> {
    val type = consoleType(T::class)
    return ConsoleArg(
        name = name,
        typeName = type.typeName,
        default = default,
        parse = type.parse,
        suggest = suggest ?: type.suggest,
    )
}

/**
 * What one of the types a console can read is called, how a word is read as it, and what it suggests.
 *
 * Chosen by the class rather than by a chain of `is` checks on the reified type, because one
 * reified operation a call site — `T::class` — is all this needs, and the compiler inlines that
 * everywhere the toolkit is built.
 */
@PublishedApi
internal class ConsoleType(
    val typeName: String,
    val suggest: () -> List<String> = { emptyList() },
    val parse: (String) -> Any?,
)

/** Which of the types a console can read [type] is, or a failure saying it is none of them. */
@PublishedApi
internal fun consoleType(type: KClass<*>): ConsoleType = when (type) {
    String::class -> ConsoleType("text") { it }
    Int::class -> ConsoleType("a whole number") { it.toIntOrNull() }
    Long::class -> ConsoleType("a whole number") { it.toLongOrNull() }
    // Infinity and NaN parse, and neither is a number anybody meant to type into a console.
    Float::class -> ConsoleType("a number") { it.toFloatOrNull()?.takeIf { value -> value.isFinite() } }
    Double::class -> ConsoleType("a number") { it.toDoubleOrNull()?.takeIf { value -> value.isFinite() } }
    Boolean::class -> ConsoleType("true or false", { listOf("true", "false") }) { booleanWord(it) }
    else -> throw IllegalArgumentException(
        "a console argument is text, a whole number, a number or true/false — ${type.simpleName} is none " +
            "of them. Take a String and look it up in the command.",
    )
}

/** The words a [Boolean] argument answers to. `on` and `off` because a console command reads that way. */
private fun booleanWord(text: String): Boolean? = when (text.lowercase()) {
    "true", "on", "yes", "1" -> true
    "false", "off", "no", "0" -> false
    else -> null
}

/**
 * One command: its name, its arguments and what it does.
 *
 * Built by [ConsoleScope.command] rather than directly, because the arity overloads there are what
 * make the game's lambda take its own types.
 */
class ConsoleCommand internal constructor(
    val name: String,
    val help: String,
    val args: List<ConsoleArg<*>>,
    internal val run: (List<Any?>) -> Unit,
) {

    /** `give <item> [count]` — what `help` prints, and what an error quotes back. */
    val usage: String get() = if (args.isEmpty()) name else name + " " + args.joinToString(" ") { it.usage }

    override fun toString(): String = "ConsoleCommand($usage)"
}

/**
 * Where a game's commands are written, inside [rememberDevConsole] or [DevConsoleState.define].
 *
 * ```kotlin
 * command("noclip") { player.collides = !player.collides }
 * command("timescale", arg<Float>("scale")) { clocks.world.scale = it }
 * command("give", arg<String>("item"), arg<Int>("count", default = 1)) { id, n -> give(id, n) }
 * ```
 *
 * Up to three arguments, each with its own type, so the lambda is `(String, Int) -> Unit` rather
 * than something taking a list of strings and picking it apart. A fourth argument is a sign the
 * command wants to be two commands.
 *
 * Naming a command twice replaces the first, which is how a screen can define its own `spawn` over
 * the one the game defined.
 */
class ConsoleScope internal constructor(private val into: MutableList<ConsoleCommand>) {

    /** A command with no arguments: `noclip`, `reload`. */
    fun command(name: String, help: String = "", action: () -> Unit) =
        add(name, help, emptyList()) { action() }

    /** A command with one argument: `timescale 0.2`. */
    @Suppress("UNCHECKED_CAST")
    fun <A : Any> command(name: String, a: ConsoleArg<A>, help: String = "", action: (A) -> Unit) =
        add(name, help, listOf(a)) { action(it[0] as A) }

    /** A command with two: `give sword 10`. */
    @Suppress("UNCHECKED_CAST")
    fun <A : Any, B : Any> command(
        name: String,
        a: ConsoleArg<A>,
        b: ConsoleArg<B>,
        help: String = "",
        action: (A, B) -> Unit,
    ) = add(name, help, listOf(a, b)) { action(it[0] as A, it[1] as B) }

    /** A command with three: `teleport 12 0 -40`. */
    @Suppress("UNCHECKED_CAST")
    fun <A : Any, B : Any, C : Any> command(
        name: String,
        a: ConsoleArg<A>,
        b: ConsoleArg<B>,
        c: ConsoleArg<C>,
        help: String = "",
        action: (A, B, C) -> Unit,
    ) = add(name, help, listOf(a, b, c)) { action(it[0] as A, it[1] as B, it[2] as C) }

    private fun add(name: String, help: String, args: List<ConsoleArg<*>>, run: (List<Any?>) -> Unit) {
        require(name.isNotBlank() && name.none { it.isWhitespace() }) {
            "a command is one word: \"$name\" is not"
        }
        // A required argument after an optional one can never be given: the player would have to
        // leave out the one in front of it. Caught here, where the game is written, not at the prompt.
        val optionalAt = args.indexOfFirst { it.optional }
        require(optionalAt < 0 || args.drop(optionalAt).all { it.optional }) {
            "$name: <${args.drop(optionalAt).first { !it.optional }.name}> comes after an argument with a " +
                "default, so nobody could ever pass it"
        }
        val command = ConsoleCommand(name.lowercase(), help, args, run)
        val existing = into.indexOfFirst { it.name == command.name }
        if (existing >= 0) into[existing] = command else into += command
    }
}

// --- reading a line -----------------------------------------------------------------------------

/**
 * One word of a typed line, and where it sits in it.
 *
 * The offsets are what completion needs: to put a suggestion in, the console has to know which
 * stretch of the line the player is in the middle of typing.
 */
internal class ConsoleToken(val text: String, val start: Int, val end: Int)

/**
 * A line split into words, with `"a quoted run"` counting as one.
 *
 * Quotes are what lets an item called `iron sword` be one argument. A quote that is never closed
 * runs to the end of the line, so the word being typed is still a word before its closing quote
 * has been typed — otherwise Tab would stop working half way through a name.
 */
internal fun tokenise(line: String): List<ConsoleToken> {
    val tokens = mutableListOf<ConsoleToken>()
    var at = 0
    while (at < line.length) {
        while (at < line.length && line[at].isWhitespace()) at++
        if (at >= line.length) break
        val start = at
        val builder = StringBuilder()
        var quoted = false
        while (at < line.length && (quoted || !line[at].isWhitespace())) {
            val character = line[at]
            if (character == '"') quoted = !quoted else builder.append(character)
            at++
        }
        tokens += ConsoleToken(builder.toString(), start, at)
    }
    return tokens
}

/** A word put back into a line: quoted when it has a space in it, so it comes back as one word. */
internal fun quoteIfNeeded(word: String): String =
    if (word.isEmpty() || word.any { it.isWhitespace() } || word.contains('"')) {
        "\"" + word.replace("\"", "") + "\""
    } else {
        word
    }

/** What a typed line turned out to be: something to run, something to complain about, or nothing. */
internal sealed interface ConsoleParse {

    /** Ready to go: the command, and each argument already read as its own type. */
    class Ready(val command: ConsoleCommand, val values: List<Any?>) : ConsoleParse

    /** What to print instead, in the player's words rather than an exception's. */
    class Failed(val message: String) : ConsoleParse

    /** An empty line. Pressing Enter on nothing does nothing. */
    data object Blank : ConsoleParse
}

/**
 * A typed line against the commands there are.
 *
 * Every failure is a sentence a person can act on: which command, which argument, what was typed and
 * what it should have been. "Expected Float" is not that sentence.
 */
internal fun parseLine(line: String, commands: List<ConsoleCommand>): ConsoleParse {
    val tokens = tokenise(line)
    if (tokens.isEmpty()) return ConsoleParse.Blank

    val name = tokens[0].text.lowercase()
    val command = commands.firstOrNull { it.name == name }
        ?: return ConsoleParse.Failed(unknownCommand(name, commands))

    val given = tokens.drop(1)
    if (given.size > command.args.size) {
        val extra = if (command.args.isEmpty()) "takes no arguments" else "takes ${command.args.size}"
        return ConsoleParse.Failed("$name $extra, and was given ${given.size}. Usage: ${command.usage}")
    }

    val values = mutableListOf<Any?>()
    command.args.forEachIndexed { index, argument ->
        val word = given.getOrNull(index)?.text
        if (word == null) {
            val value = argument.default
                ?: return ConsoleParse.Failed("$name needs ${argument.usage}. Usage: ${command.usage}")
            values += value
        } else {
            val value = argument.parse(word)
                ?: return ConsoleParse.Failed("$name: \"$word\" is not ${argument.typeName} for ${argument.usage}")
            values += value
        }
    }
    return ConsoleParse.Ready(command, values)
}

/** "unknown command" is half an answer; the other half is the one the player probably meant. */
private fun unknownCommand(name: String, commands: List<ConsoleCommand>): String {
    val near = commands.map { it.name }
        .filter { it.startsWith(name) || name.startsWith(it) || editDistance(it, name) <= NearEnough }
        .minByOrNull { editDistance(it, name) }
    val guess = near?.let { " Did you mean \"$it\"?" } ?: ""
    return "unknown command \"$name\".$guess Type help for the list."
}

/** How wrong a word may be and still be worth suggesting. Two letters: a typo, not another word. */
private const val NearEnough = 2

/**
 * How many single-letter edits turn one word into the other.
 *
 * The plain two-row Levenshtein. Command names are short and this runs once, when a word was not
 * recognised, so there is nothing here worth making cleverer.
 */
internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitute = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitute)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

// --- completing a line --------------------------------------------------------------------------

/**
 * What Tab could put where the caret is: the stretch of the line it would replace, and the choices.
 *
 * [start] and [end] are the word the caret is in, which is an empty stretch at the caret when it is
 * in the space after one — that is how `give ` offers the items rather than completing `give` again.
 */
internal class ConsoleCompletion(val start: Int, val end: Int, val options: List<String>) {
    val isEmpty: Boolean get() = options.isEmpty()
}

/** What the word being typed could be, given the commands there are. */
internal fun completionAt(line: String, caret: Int, commands: List<ConsoleCommand>): ConsoleCompletion {
    val at = caret.coerceIn(0, line.length)
    val tokens = tokenise(line)
    // The word the caret is inside, or is on the end of. Typing carries on from the end of a word,
    // so `giv|` is completing `giv`, while `give |` is starting the word after it.
    val index = tokens.indexOfFirst { at in it.start..it.end }
    val token = tokens.getOrNull(index)
    val prefix = token?.text?.take(at - token.start) ?: ""
    val start = token?.start ?: at
    val end = token?.end ?: at
    // Which word this is: the command when it is the first, otherwise the argument in that place.
    val position = if (index >= 0) index else tokens.count { it.end < at }

    val choices = if (position == 0) {
        commands.map { it.name }
    } else {
        val command = commands.firstOrNull { it.name == tokens.getOrNull(0)?.text?.lowercase() }
        command?.args?.getOrNull(position - 1)?.suggest?.invoke().orEmpty()
    }

    val matching = choices.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
    return ConsoleCompletion(start, end, matching)
}

/** The longest start every option shares, which is how far one Tab can fill a word in unasked. */
internal fun commonPrefix(options: List<String>): String {
    if (options.isEmpty()) return ""
    var prefix = options.first()
    options.forEach { option ->
        var length = 0
        while (length < prefix.length && length < option.length &&
            prefix[length].lowercaseChar() == option[length].lowercaseChar()
        ) {
            length++
        }
        prefix = prefix.take(length)
    }
    return prefix
}
