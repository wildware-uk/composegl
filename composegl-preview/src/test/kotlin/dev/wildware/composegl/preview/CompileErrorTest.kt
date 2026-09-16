package dev.wildware.composegl.preview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The first error a compile printed, out of everything Gradle printed. */
class CompileErrorTest {

    @Test
    fun `the first Kotlin error, with its file and line`() {
        val output = """
            > Task :game:compileKotlin FAILED
            w: file:///home/me/game/src/main/kotlin/Hud.kt:3:5 Parameter 'x' is never used.
            e: file:///home/me/game/src/main/kotlin/Menus.kt:12:9 Unresolved reference 'Buton'.
            e: file:///home/me/game/src/main/kotlin/Menus.kt:20:1 Expecting '}'.

            FAILURE: Build failed with an exception.
        """.trimIndent()

        assertEquals(
            CompileError("/home/me/game/src/main/kotlin/Menus.kt", 12, 9, "Unresolved reference 'Buton'."),
            CompileError.firstIn(output),
        )
    }

    @Test
    fun `a Kotlin error with no file url`() {
        assertEquals(
            CompileError("/game/src/Menus.kt", 4, 2, "Syntax error."),
            CompileError.firstIn("e: /game/src/Menus.kt:4:2 Syntax error."),
        )
    }

    @Test
    fun `a Java error`() {
        assertEquals(
            CompileError("/game/src/main/java/Util.java", 7, null, "cannot find symbol"),
            CompileError.firstIn("/game/src/main/java/Util.java:7: error: cannot find symbol\n  symbol: Foo"),
        )
    }

    @Test
    fun `said as file and line`() {
        assertEquals(
            "/game/Menus.kt:12: Unresolved reference 'Buton'.",
            CompileError("/game/Menus.kt", 12, 9, "Unresolved reference 'Buton'.").toString(),
        )
    }

    @Test
    fun `a build that failed for another reason has no compiler error`() {
        assertNull(CompileError.firstIn("* What went wrong:\nTask 'classes' not found in project ':game'."))
    }
}
