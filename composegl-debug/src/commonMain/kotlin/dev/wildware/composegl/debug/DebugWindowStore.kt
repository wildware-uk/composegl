package dev.wildware.composegl.debug

/**
 * Where debug windows remember where they were: position, size, whether each is folded to its title
 * bar, and which sections inside them are open.
 *
 * ```kotlin
 * DebugWindowHost(state = rememberDebugWindowsState(store = MemoryDebugWindowStore())) { Game() }
 * ```
 *
 * A plain map of strings, read once when a [DebugWindowsState] is made and written whenever a window
 * stops moving, stops being resized, or is folded or unfolded. What the strings mean is the windows'
 * business; a store only keeps them. So a game with a save system of its own writes a store of four
 * lines that puts the map there.
 *
 * [defaultDebugWindowStore] is a file on the desktop and memory everywhere else.
 */
interface DebugWindowStore {

    /** Everything saved last time, or an empty map when nothing was. Never throws: a broken file is a fresh start. */
    fun load(): Map<String, String>

    /** Keeps [values] in place of whatever was kept before. */
    fun save(values: Map<String, String>)
}

/**
 * A store that lasts as long as it does: a run, or a test. Nothing is written anywhere.
 *
 * @param initial what [load] gives before anything has been saved.
 */
class MemoryDebugWindowStore(initial: Map<String, String> = emptyMap()) : DebugWindowStore {

    /** What was saved last, for a test to read. */
    var values: Map<String, String> = initial.toMap()
        private set

    /** How many times [save] has been called. */
    var saves: Int = 0
        private set

    override fun load(): Map<String, String> = values

    override fun save(values: Map<String, String>) {
        this.values = values.toMap()
        saves++
    }
}

/**
 * The store a [DebugWindowHost] uses when it is not given one.
 *
 * On a desktop — the JVM and Linux native both — a file called `composegl-debug-windows.txt` in the
 * working directory, the way imgui keeps `imgui.ini` beside the game. On iOS and in the browser,
 * memory: there is nowhere to write that the game has not chosen, so the windows go back to where the
 * code puts them at the next start unless the game hands in a store of its own.
 */
expect fun defaultDebugWindowStore(): DebugWindowStore

/**
 * The store's map as lines of `key=value`, with `\`, `=` and line breaks escaped so any window title
 * survives. What the file store writes, and common so every platform reads it the same way.
 */
internal object DebugWindowStoreText {

    fun write(values: Map<String, String>): String = buildString {
        values.entries.sortedBy { it.key }.forEach { (key, value) ->
            append(escape(key)).append('=').append(escape(value)).append('\n')
        }
    }

    fun read(text: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        text.split('\n').forEach { line ->
            if (line.isEmpty()) return@forEach
            val split = separator(line)
            if (split < 0) return@forEach
            values[unescape(line.substring(0, split))] = unescape(line.substring(split + 1))
        }
        return values
    }

    /** The first `=` that is not escaped, or -1. */
    private fun separator(line: String): Int {
        var at = 0
        while (at < line.length) {
            when (line[at]) {
                '\\' -> at += 2
                '=' -> return at
                else -> at++
            }
        }
        return -1
    }

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '=' -> append("\\=")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

    private fun unescape(text: String): String = buildString(text.length) {
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (char == '\\' && at + 1 < text.length) {
                when (val next = text[at + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(next)
                }
                at += 2
            } else {
                append(char)
                at++
            }
        }
    }
}
