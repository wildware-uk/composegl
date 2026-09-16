package dev.wildware.composegl.preview

/**
 * The compiler's first error: where, and what it said.
 *
 * The first one because it is usually the real one. A missing brace near the top of a file makes
 * every line after it an error too.
 *
 * @param column where on the line, when the compiler says. `javac` does not.
 */
data class CompileError(val file: String, val line: Int, val column: Int?, val message: String) {

    override fun toString() = "$file:$line: $message"

    companion object {

        /** `e: file:///path/Menus.kt:12:9 Unresolved reference 'Buton'.` — Kotlin, with or without the URL. */
        private val Kotlin = Regex("""^e: (?:file://)?(.+?):(\d+):(\d+):? (.*)$""")

        /** `/path/Util.java:7: error: cannot find symbol` */
        private val Java = Regex("""^(.+\.java):(\d+): error: (.*)$""")

        /** The first compiler error in what a build printed, or null when it failed some other way. */
        fun firstIn(output: String): CompileError? = output.lineSequence().map { it.trimEnd() }.firstNotNullOfOrNull { line ->
            Kotlin.find(line)?.destructured?.let { (file, row, column, message) ->
                CompileError(file, row.toInt(), column.toInt(), message)
            } ?: Java.find(line)?.destructured?.let { (file, row, message) ->
                CompileError(file, row.toInt(), null, message)
            }
        }
    }
}
