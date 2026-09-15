package dev.wildware.composegl.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A screen in the player's language, switched by the player, with real clicks. */
class LocaleUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f), content = content).also { opened += it }

    private val hebrew = Locale("he")

    private val strings = Strings(
        mapOf(
            Locale.English to mapOf(
                "play" to "Play",
                "language" to "Hebrew",
                "lives.zero" to "No lives",
                "lives.one" to "One life",
                "lives.other" to "{0} lives",
            ),
            hebrew to mapOf("play" to "שחק", "language" to "English"),
        ),
    )

    @Test
    fun `a screen shows the strings of the language it was given`() {
        val ui = open {
            ProvideLocale(hebrew, strings) { Text(stringOf("play"), Modifier.testTag("play")) }
        }

        assertEquals("קחש", ui.text("play"), "the Hebrew, drawn right to left")
    }

    @Test
    fun `picking a language with a click changes the words and mirrors the screen`() {
        var locale by mutableStateOf(Locale.English)
        val ui = open {
            ProvideLocale(locale, strings) {
                Row(Modifier.width(400f)) {
                    Button(
                        stringOf("language"),
                        onClick = { locale = if (locale == Locale.English) hebrew else Locale.English },
                        modifier = Modifier.testTag("language"),
                    )
                    Text(stringOf("play"), Modifier.testTag("play"))
                }
            }
        }
        assertEquals("Play", ui.text("play"))
        assertEquals(0f, ui.node("language").boundsInRoot.left, "the first thing in the row starts on the left")

        ui.click("language")

        assertEquals("קחש", ui.text("play"))
        assertEquals("English", ui.text("language"))
        assertEquals(400f, ui.node("language").boundsInRoot.right, "and now on the right")
        assertEquals(ui.node("language").boundsInRoot.left, ui.node("play").boundsInRoot.right, "with the label to its left")

        ui.click("language")

        assertEquals("Play", ui.text("play"))
        assertEquals(0f, ui.node("language").boundsInRoot.left, "back again")
    }

    @Test
    fun `a count is shown in the plural form for it as it changes`() {
        var lives by mutableStateOf(0)
        val ui = open {
            ProvideLocale(Locale.English, strings) {
                Column {
                    Text(pluralOf("lives", lives), Modifier.testTag("lives"))
                    Button("+", onClick = { lives++ }, modifier = Modifier.testTag("more"))
                }
            }
        }

        assertEquals("No lives", ui.text("lives"))
        ui.click("more")
        assertEquals("One life", ui.text("lives"))
        ui.click("more")
        assertEquals("2 lives", ui.text("lives"))
    }

    @Test
    fun `a missing translation shows the fallback's words rather than nothing`() {
        val ui = open {
            ProvideLocale(hebrew, strings) { Text(pluralOf("lives", 3), Modifier.testTag("lives")) }
        }

        assertEquals("3 lives", ui.text("lives"))
    }

    @Test
    fun `a still screen in hebrew draws nothing new`() {
        val ui = open {
            ProvideLocale(hebrew, strings) {
                Row { Button(stringOf("language"), onClick = {}); Text(stringOf("play")) }
            }
        }

        ui.render()

        assertFalse(ui.render())
    }
}
