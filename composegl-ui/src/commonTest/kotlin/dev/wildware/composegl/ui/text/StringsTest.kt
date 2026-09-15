package dev.wildware.composegl.ui.text

import dev.wildware.composegl.ui.layout.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Looking strings up by language, and what happens when a translation is missing. */
class StringsTest {

    private val english = Locale.English
    private val portuguese = Locale("pt")
    private val brazilian = Locale("pt", "BR")
    private val hebrew = Locale("he")

    private val strings = Strings(
        mapOf(
            english to mapOf(
                "play" to "Play",
                "quit" to "Quit",
                "greeting" to "Hello, {name}",
                "score" to "{0} of {1}",
                "lives.one" to "One life",
                "lives.other" to "{0} lives",
                "lives.zero" to "No lives",
            ),
            portuguese to mapOf("play" to "Jogar", "quit" to "Sair"),
            brazilian to mapOf("play" to "Bora"),
            hebrew to mapOf("play" to "שחק", "lives.one" to "חיים אחד", "lives.two" to "זוג חיים", "lives.other" to "{0} חיים"),
        ),
    )

    @Test
    fun `a key comes back in the language asked for`() {
        assertEquals("Play", strings.get(english, "play"))
        assertEquals("Jogar", strings.get(portuguese, "play"))
        assertEquals("שחק", strings.get(hebrew, "play"))
    }

    @Test
    fun `a region falls back to its language and then to the fallback`() {
        assertEquals("Bora", strings.get(brazilian, "play"), "the region's own word wins")
        assertEquals("Sair", strings.get(brazilian, "quit"), "then the language's")
        assertEquals("Hello, Ada", strings.get(brazilian, "greeting", mapOf("name" to "Ada")), "then the fallback's")
    }

    @Test
    fun `a key found nowhere shows as itself`() {
        assertEquals("menu.credits", strings.get(hebrew, "menu.credits"))
        assertEquals("menu.credits", Strings.Empty.get(english, "menu.credits"))
    }

    @Test
    fun `placeholders are filled by position or by name`() {
        assertEquals("3 of 10", strings.get(english, "score", 3, 10))
        assertEquals("Hello, Ada", strings.get(english, "greeting", mapOf("name" to "Ada")))
    }

    @Test
    fun `a placeholder nothing fills is left where it can be seen`() {
        assertEquals("3 of {1}", strings.get(english, "score", 3))
        assertEquals("Hello, {name}", strings.get(english, "greeting"))
    }

    @Test
    fun `doubled braces are a literal brace`() {
        val table = Strings(mapOf(english to mapOf("code" to "{{0}} is {0}")))

        assertEquals("{0} is 7", table.get(english, "code", 7))
    }

    @Test
    fun `plurals pick the form for the count`() {
        assertEquals("No lives", strings.plural(english, "lives", 0))
        assertEquals("One life", strings.plural(english, "lives", 1))
        assertEquals("2 lives", strings.plural(english, "lives", 2), "english has no two form, so other")
        assertEquals("5 lives", strings.plural(english, "lives", 5))
    }

    @Test
    fun `a language with a dual uses it and one without a zero uses other`() {
        assertEquals("זוג חיים", strings.plural(hebrew, "lives", 2))
        assertEquals("0 חיים", strings.plural(hebrew, "lives", 0))
    }

    @Test
    fun `arabic counts take its few and many forms`() {
        val arabic = Locale("ar")
        val table = Strings(
            mapOf(
                english to mapOf("coins.one" to "One coin", "coins.other" to "{0} coins"),
                arabic to mapOf(
                    "coins.zero" to "zero {0}",
                    "coins.one" to "one {0}",
                    "coins.two" to "two {0}",
                    "coins.few" to "few {0}",
                    "coins.many" to "many {0}",
                    "coins.other" to "other {0}",
                ),
            ),
        )

        assertEquals(
            listOf("zero 0", "one 1", "two 2", "few 3", "few 10", "many 11", "many 99", "other 100", "few 103", "many 111"),
            listOf(0, 1, 2, 3, 10, 11, 99, 100, 103, 111).map { table.plural(arabic, "coins", it) },
        )
    }

    @Test
    fun `russian and polish counts go back to one and few past twenty`() {
        val russian = Locale("ru")
        val polish = Locale("pl")
        val forms = mapOf("apples.one" to "one {0}", "apples.few" to "few {0}", "apples.many" to "many {0}", "apples.other" to "other {0}")
        val table = Strings(mapOf(english to mapOf("apples.other" to "{0} apples"), russian to forms, polish to forms))

        assertEquals(
            listOf("one 1", "few 2", "many 5", "many 11", "many 12", "one 21", "few 22", "many 25"),
            listOf(1, 2, 5, 11, 12, 21, 22, 25).map { table.plural(russian, "apples", it) },
        )
        assertEquals("many 21", table.plural(polish, "apples", 21), "polish has one only for one itself")
        assertEquals("few 22", table.plural(polish, "apples", 22))
    }

    @Test
    fun `a missing form falls back to other and the fallback table keeps its own grammar`() {
        val russian = Locale("ru")
        val table = Strings(
            mapOf(
                english to mapOf("apples.one" to "{0} apple", "apples.other" to "{0} apples", "pears.one" to "{0} pear", "pears.other" to "{0} pears"),
                russian to mapOf("apples.one" to "одно {0}", "apples.other" to "другое {0}"),
            ),
        )

        assertEquals("другое 5", table.plural(russian, "apples", 5), "no many form in the table, so other")
        assertEquals("одно 21", table.plural(russian, "apples", 21), "russian grammar makes 21 one")
        assertEquals("21 pears", table.plural(russian, "pears", 21), "shown in english, 21 is english's other")
    }

    @Test
    fun `the keys a translation lacks can be listed`() {
        assertEquals(setOf("quit", "greeting", "score", "lives.zero"), strings.missingFrom(hebrew))
        assertEquals(setOf("greeting", "score", "lives.one", "lives.other", "lives.zero"), strings.missingFrom(brazilian))
        assertTrue(strings.missingFrom(english).isEmpty())
    }

    @Test
    fun `a table can be read from a text file`() {
        val table = Strings.parse(
            """
            # the main menu
            menu.play = Play
            menu.credits=Made by\nsomebody

            path = C:\\games
            """.trimIndent(),
        )

        assertEquals(mapOf("menu.play" to "Play", "menu.credits" to "Made by\nsomebody", "path" to "C:\\games"), table)
    }

    @Test
    fun `a line that is not a key and a value is reported with its number`() {
        val failure = assertFailsWith<IllegalArgumentException> { Strings.parse("a = b\nthis is wrong") }

        assertTrue(failure.message!!.contains("line 2"), failure.message)
    }

    @Test
    fun `a locale tag is read however a platform writes it`() {
        assertEquals(Locale("pt", "BR"), Locale.of("pt_br"))
        assertEquals(Locale("ar"), Locale.of("AR"))
        assertEquals("pt-BR", Locale.of("pt-BR").tag)
    }

    @Test
    fun `the languages written from the right lay out from the right`() {
        assertEquals(LayoutDirection.Rtl, Locale("he").direction)
        assertEquals(LayoutDirection.Rtl, Locale("ar", "EG").direction)
        assertEquals(LayoutDirection.Rtl, Locale("fa").direction)
        assertEquals(LayoutDirection.Ltr, Locale("en").direction)
        assertEquals(LayoutDirection.Ltr, Locale("ja").direction)
    }
}
