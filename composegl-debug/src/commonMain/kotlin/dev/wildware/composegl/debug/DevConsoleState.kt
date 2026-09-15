package dev.wildware.composegl.debug

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextRange

/**
 * How loud a line in the log is, and therefore what colour it is drawn in.
 *
 * The skin names one style per level — `console.line.warn` and so on — so a game recolours them
 * without touching this module. See [DevConsoleState.log].
 */
enum class ConsoleLevel {

    /** Chatter: what a system is doing, off unless somebody is looking for it. */
    Debug,

    /** The ordinary line. What a command prints and what a game reports. */
    Info,

    /** Something is odd but the game carried on. */
    Warn,

    /** Something did not happen: a command that failed, or a line that was not a command. */
    Error,

    /** The line the player typed, echoed back, so the answers underneath have a question above them. */
    Echo,
}

/** One line in the log: what it says, how loud it is, and which line it is. */
class ConsoleLine internal constructor(val number: Int, val text: String, val level: ConsoleLevel) {
    override fun toString(): String = "$level: $text"
}

/**
 * Where the command history is kept between runs of the game.
 *
 * The toolkit has no files: it draws and it takes input, and it runs in a browser where there is no
 * filesystem to have. So a game that wants the history it typed yesterday hands one of these in and
 * writes the lines wherever that platform keeps things:
 *
 * ```kotlin
 * val console = rememberDevConsole(history = FileHistory(Path("build/console-history.txt"))) { … }
 * ```
 *
 * [load] is called once, when the console is built; [save] every time a command is run, with the
 * whole list newest last. Both are called on the thread the interface is composed on, so a store
 * that writes a file should keep it small — which the console does for it, by capping the list.
 */
interface ConsoleHistoryStore {

    /** What was typed before, oldest first. An empty list is a game that has not run yet. */
    fun load(): List<String>

    /** Keep these, oldest first, for the next run. */
    fun save(lines: List<String>)

    companion object {

        /** Keeps the history for as long as the game is running, and no longer. The default. */
        fun inMemory(): ConsoleHistoryStore = InMemoryHistory()
    }
}

/** The default store: the lines live in the object, which lives as long as the game. */
private class InMemoryHistory : ConsoleHistoryStore {
    private var lines: List<String> = emptyList()
    override fun load(): List<String> = lines
    override fun save(lines: List<String>) {
        this.lines = lines
    }
}

/**
 * The console itself: the commands, the log, the history and whether it is down.
 *
 * Made by [rememberDevConsole] and drawn by [DevConsole]. A game holds it to print into it —
 * `console.log("Loaded level 3")` — to run a command from somewhere else, or to open it from its
 * own menu.
 *
 * Everything on it is safe to read from a composable: the log, the filter and whether it is open are
 * snapshot state, so a screen showing "console open" follows it without being told.
 *
 * @param history where the typed lines are kept between runs.
 * @param maxLines how many lines the log holds. The oldest go when it is full, so a game logging
 *   every frame cannot grow it without limit.
 */
@Stable
class DevConsoleState internal constructor(
    val history: ConsoleHistoryStore = ConsoleHistoryStore.inMemory(),
    val maxLines: Int = ConsoleDefaultLines,
) {

    private val defined = mutableStateListOf<ConsoleCommand>()

    private val log = mutableStateListOf<ConsoleLine>()

    private val typed = mutableListOf<String>().also { it += history.load().takeLast(MaxHistory) }

    private var counted = 0

    /** Whether the console is down. Setting it is the same as [open] and [close]. */
    var isOpen: Boolean by mutableStateOf(false)

    /**
     * Only lines with this in them are shown, ignoring case. Empty shows everything.
     *
     * The filter box at the top of the console writes here, and so can a game: setting it to the
     * name of a subsystem is the fastest way to read one system's chatter out of a busy log.
     */
    var filter: String by mutableStateOf("")

    /** Every line the console has, oldest first, whatever the filter says. */
    val lines: List<ConsoleLine> get() = log

    /** The lines the filter lets through, which is what the console draws. */
    val visibleLines: List<ConsoleLine>
        get() = if (filter.isBlank()) log else log.filter { it.text.contains(filter, ignoreCase = true) }

    /** Every command there is, including the built-in `help` and `clear`, in the order defined. */
    val commands: List<ConsoleCommand> get() = defined

    /** What has been typed, oldest first. A game may read it; the console adds to the end of it. */
    val typedHistory: List<String> get() = typed

    // --- what the prompt is doing ---------------------------------------------------------------

    /** What is in the prompt now. The field writes it; completion and history rewrite it. */
    internal var input: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    /** What Tab would offer for the word being typed, or empty when there is nothing to offer. */
    internal var suggestions: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * Which suggestion is picked out, or -1 for none.
     *
     * None until Tab is pressed: until then the list is only showing what is there, and the arrows
     * belong to the history. Once one is picked out the arrows move along the list instead, and
     * Escape puts them back.
     */
    internal var highlighted: Int by mutableStateOf(-1)
        private set

    /** Goes up by one whenever a line is added or the log is cleared, so the view can follow it. */
    internal var revision: Int by mutableStateOf(0)
        private set

    /** Where Up and Down are in the history: -1 is the line being typed now. */
    private var historyAt = -1

    /** What was being typed before Up went into the history, so Down can bring it back. */
    private var draft = ""

    /** The list Tab is walking, and where the word it is filling in starts. Null between walks. */
    private var cycling: ConsoleCompletion? = null

    /** Where the word Tab last filled in ends, so the next choice replaces it rather than the text. */
    private var filledTo = 0

    init {
        builtIns()
    }

    // --- being open -----------------------------------------------------------------------------

    fun open() {
        isOpen = true
    }

    /** Closes it. What is half-typed at the prompt is still there when it comes back down. */
    fun close() {
        isOpen = false
        highlighted = -1
        suggestions = emptyList()
    }

    fun toggle() {
        isOpen = !isOpen
    }

    // --- the log --------------------------------------------------------------------------------

    /**
     * Prints a line.
     *
     * ```kotlin
     * console.log("Loaded level 3")
     * console.log("No such item: $id", ConsoleLevel.Error)
     * ```
     */
    fun log(text: String, level: ConsoleLevel = ConsoleLevel.Info) {
        // A printed block arrives as a block: one call per line keeps the levels and the filter
        // working on each of them, which a single line with newlines in it would not.
        text.split('\n').forEach { line ->
            log += ConsoleLine(counted++, line, level)
        }
        while (log.size > maxLines) log.removeAt(0)
        revision++
    }

    /** [log] at [ConsoleLevel.Warn]. */
    fun warn(text: String) = log(text, ConsoleLevel.Warn)

    /** [log] at [ConsoleLevel.Error]. */
    fun error(text: String) = log(text, ConsoleLevel.Error)

    /** Empties the log. What the built-in `clear` command does. */
    fun clear() {
        log.clear()
        revision++
    }

    // --- the commands ---------------------------------------------------------------------------

    /**
     * Adds commands, or replaces ones of the same name.
     *
     * The same block [rememberDevConsole] takes, for a screen that brings its own commands with it
     * and a game that only knows some of them once a level is loaded.
     */
    fun define(builder: ConsoleScope.() -> Unit) {
        ConsoleScope(defined).builder()
    }

    /**
     * Runs a line as though it had been typed, and prints whatever it has to say.
     *
     * The line is echoed first, so the answers have their question above them, and it goes into the
     * history. Nothing thrown by a command escapes: a command that fails says so in the log, because
     * a console that closes the game when a command is wrong is a console nobody dares use.
     */
    fun run(line: String) {
        if (line.isBlank()) return
        log("> ${line.trim()}", ConsoleLevel.Echo)
        recordTyped(line.trim())
        when (val parsed = parseLine(line, defined)) {
            is ConsoleParse.Blank -> Unit
            is ConsoleParse.Failed -> error(parsed.message)
            is ConsoleParse.Ready -> {
                try {
                    parsed.command.run(parsed.values)
                } catch (failure: Throwable) {
                    error("${parsed.command.name} failed: ${failure.message ?: failure.toString()}")
                }
            }
        }
    }

    /** Keeps the line, newest last, unless it is the one that was typed last anyway. */
    private fun recordTyped(line: String) {
        if (typed.lastOrNull() != line) {
            typed += line
            while (typed.size > MaxHistory) typed.removeAt(0)
            history.save(typed.toList())
        }
        historyAt = -1
        draft = ""
    }

    /** `help` and `clear`, defined the same way a game defines its own so they behave the same. */
    private fun builtIns() {
        define {
            command(
                "help",
                arg<String>("command", default = "", suggest = { defined.map { it.name } }),
                help = "What there is, or what one command takes",
            ) { name -> printHelp(name) }
            command("clear", help = "Empties the log") { clear() }
        }
    }

    private fun printHelp(name: String) {
        if (name.isBlank()) {
            log("Commands (help <command> for one of them):")
            defined.sortedBy { it.name }.forEach { command ->
                log("  ${command.usage}${if (command.help.isBlank()) "" else " — ${command.help}"}")
            }
            return
        }
        val command = defined.firstOrNull { it.name == name.lowercase() }
        if (command == null) {
            error("no command called \"$name\". Type help for the list.")
            return
        }
        log("  ${command.usage}${if (command.help.isBlank()) "" else " — ${command.help}"}")
        command.args.forEach { argument -> log("    ${argument.usage} is ${argument.typeName}") }
    }

    // --- the prompt -----------------------------------------------------------------------------

    /** The field said the text changed. Suggestions follow the caret; the highlight starts again. */
    internal fun onInput(value: TextFieldValue) {
        val changed = value.text != input.text
        input = value
        if (changed) historyAt = -1
        // Anything the player does at the prompt by hand ends the walk through the list: the word
        // Tab was filling in is not the word being typed any more.
        cycling = null
        refreshSuggestions()
    }

    /** Puts [text] in the prompt with the caret at its end. What history and completion both need. */
    private fun setInput(text: String, caret: Int = text.length) {
        input = TextFieldValue(text, TextRange(caret.coerceIn(0, text.length)))
        cycling = null
        refreshSuggestions()
    }

    private fun refreshSuggestions() {
        // An empty prompt is not asking for the whole list of commands: that is what `help` is for,
        // and a list covering the log before a letter has been typed is only in the way. Tab still
        // opens it, which is how a player who does want it asks.
        if (input.text.isBlank()) {
            suggestions = emptyList()
            highlighted = -1
            return
        }
        val completion = completionAt(input.text, input.selection.end, defined)
        val options = completion.options
        // A single option already typed in full is not a suggestion, it is the answer, and a list
        // saying one word the player has just finished typing is only in the way.
        suggestions = if (options.size == 1 && options[0].equals(wordAt(completion), ignoreCase = true)) {
            emptyList()
        } else {
            options
        }
        highlighted = if (highlighted in suggestions.indices) highlighted else -1
    }

    private fun wordAt(completion: ConsoleCompletion): String =
        input.text.substring(
            completion.start.coerceIn(0, input.text.length),
            completion.end.coerceIn(0, input.text.length),
        )

    /** Enter: run what is typed, empty the prompt, and come back to the bottom of the history. */
    internal fun submit() {
        val line = input.text
        setInput("")
        highlighted = -1
        suggestions = emptyList()
        run(line)
    }

    /**
     * Tab, and Shift+Tab.
     *
     * The first press fills in as far as every choice agrees — the way a shell does — and shows the
     * list. Pressing it again walks the list, putting each choice in the prompt as it goes, so the
     * player reads the result rather than a menu.
     *
     * The list and the word it is filling in are held between presses ([cycling]). Working it out
     * again each time would not walk a list at all: once `giveall` is in the prompt, the only word
     * starting with `giveall` is `giveall`, and Tab would stop on whichever one it reached first.
     */
    internal fun completeNext(backwards: Boolean = false) {
        val active = cycling ?: completionAt(input.text, input.selection.end, defined).also {
            if (it.isEmpty) return
            cycling = it
            filledTo = it.end
            val word = wordAt(it)
            val shared = commonPrefix(it.options)
            if (it.options.size > 1 && shared.length > word.length) {
                // Everything they agree on, and stop: this is the press that turns `tim` into
                // `timescale` without choosing between `give` and `giveall`.
                fill(it.start, shared)
                suggestions = it.options
                highlighted = -1
                return
            }
        }
        val options = active.options
        val next = when {
            highlighted !in options.indices -> if (backwards) options.lastIndex else 0
            else -> (highlighted + (if (backwards) -1 else 1) + options.size) % options.size
        }
        fill(active.start, options[next])
        suggestions = options
        highlighted = next
    }

    /** The pointer's way of choosing: a click on a line of the list takes that one. */
    internal fun choose(index: Int) {
        val active = cycling ?: completionAt(input.text, input.selection.end, defined).also {
            if (it.isEmpty) return
            cycling = it
            filledTo = it.end
        }
        if (index !in active.options.indices) return
        fill(active.start, active.options[index])
        suggestions = active.options
        highlighted = index
    }

    /**
     * Puts [choice] in place of whatever the last one filled in, and leaves the caret after it.
     *
     * Not `refreshSuggestions`: the list stays as it was, so the next Tab walks the same list rather
     * than the one word now in the prompt.
     */
    private fun fill(start: Int, choice: String) {
        val text = input.text
        val from = start.coerceIn(0, text.length)
        val to = filledTo.coerceIn(from, text.length)
        val word = quoteIfNeeded(choice)
        val replaced = text.substring(0, from) + word + text.substring(to)
        filledTo = from + word.length
        input = TextFieldValue(replaced, TextRange(filledTo))
    }

    /** Escape on an open list only puts the list away; the console closes on the next one. */
    internal fun dismissSuggestions(): Boolean {
        if (highlighted < 0 && suggestions.isEmpty()) return false
        highlighted = -1
        suggestions = emptyList()
        cycling = null
        return true
    }

    /** Up: one line back through what was typed, or one choice up the list while one is picked out. */
    internal fun previous() {
        if (highlighted >= 0 && suggestions.isNotEmpty()) {
            completeNext(backwards = true)
            return
        }
        if (typed.isEmpty()) return
        if (historyAt < 0) {
            draft = input.text
            historyAt = typed.size
        }
        if (historyAt <= 0) return
        historyAt--
        setInput(typed[historyAt])
    }

    /** Down: back towards the line being typed, or one choice down the list. */
    internal fun next() {
        if (highlighted >= 0 && suggestions.isNotEmpty()) {
            completeNext()
            return
        }
        if (historyAt < 0) return
        historyAt++
        if (historyAt >= typed.size) {
            historyAt = -1
            setInput(draft)
        } else {
            setInput(typed[historyAt])
        }
    }

    private companion object {
        /** How many typed lines are kept. More than anybody presses Up for, few enough to write out. */
        const val MaxHistory = 100
    }
}
