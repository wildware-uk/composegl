package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.SelectionContainer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Game widgets inside a [SelectionContainer]: a click on their letters is still a click on them.
 *
 * A game that wraps a whole screen in a container, so a seed or a player name can be copied, must
 * not lose its hotbar or its notifications to text selection. Driven through the real pointer
 * router, as composegl-ui's `SelectionContainerUiTest` drives the toolkit's own widgets.
 */
class GameSelectionContainerTest {

    private val opened = mutableListOf<UiTest>()
    private val backend = HeadlessBackend()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f), backend, content = content).also { opened += it }

    @Test
    fun `a hotbar slot inside a container is used when its letters are clicked`() {
        val used = mutableListOf<Int>()
        val ui = open {
            SelectionContainer {
                Hotbar(listOf(HotbarSlot(label = "FIRE"), HotbarSlot(label = "ICE")), onUse = { used += it }, modifier = Modifier.testTag("bar"))
            }
        }

        ui.click(ui.node("bar").children[1].boundsInRoot.centre)

        assertEquals(listOf(1), used)
    }

    @Test
    fun `a notification inside a container is dismissed by a click on its text`() {
        val queue = NotificationQueue(holdMillis = 60_000)
        val ui = open {
            SelectionContainer { Notifications(queue, Modifier.testTag("notices")) }
        }
        queue.show("Quest updated")
        ui.advanceBy(500)

        ui.click(ui.node("notices").children[0].boundsInRoot.centre)
        ui.advanceBy(500)

        assertTrue(queue.shown.isEmpty(), "the click reached the card rather than selecting its text")
    }
}
