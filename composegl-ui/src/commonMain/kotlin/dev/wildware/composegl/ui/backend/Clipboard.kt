package dev.wildware.composegl.ui.backend

/**
 * The system clipboard.
 *
 * One line on every platform a game actually ships to. The previous version of this project needed
 * a reflective bridge into AWT for the same job, which worked on a desktop JVM and nowhere else.
 */
interface Clipboard {

    /** What is on the clipboard, or null when it holds nothing this toolkit can use. */
    fun read(): String?

    fun write(text: String)

    companion object {

        /**
         * A clipboard that keeps nothing and offers nothing.
         *
         * What a text field gets when a game has not wired one up: copy quietly goes nowhere and
         * paste finds nothing, rather than the field throwing on a key the player is allowed to
         * press.
         */
        val None: Clipboard = object : Clipboard {
            override fun read(): String? = null
            override fun write(text: String) = Unit
        }
    }
}

/** A clipboard that lives in memory. For tests, and for platforms that have no system one. */
class InMemoryClipboard(private var contents: String? = null) : Clipboard {
    override fun read(): String? = contents
    override fun write(text: String) { contents = text }
}
