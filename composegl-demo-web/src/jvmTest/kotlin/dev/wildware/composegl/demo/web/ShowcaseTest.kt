package dev.wildware.composegl.demo.web

import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the tour does, without drawing it: pages, shortcuts, and the shape it takes on a phone. */
class ShowcaseStateTest {

    @Test
    fun `next and previous go round every section and wrap`() {
        val state = ShowcaseState()
        val seen = mutableListOf(state.section)
        repeat(Section.entries.size) {
            state.next()
            seen += state.section
        }
        assertEquals(Section.entries + Section.Home, seen)
        state.previous()
        assertEquals(Section.Debug, state.section)
    }

    @Test
    fun `page keys and bumpers turn the page and nothing else is taken`() {
        val state = ShowcaseState()
        assertTrue(state.onKey(KeyEvent(Key.PageDown, KeyEventType.Down)))
        assertEquals(Section.Widgets, state.section)
        assertTrue(state.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.RightBumper)))
        assertEquals(Section.Layout, state.section)
        assertTrue(state.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.LeftBumper)))
        assertTrue(state.onKey(KeyEvent(Key.PageUp, KeyEventType.Down)))
        assertEquals(Section.Home, state.section)

        assertFalse(state.onKey(KeyEvent(Key.PageDown, KeyEventType.Up)))
        assertFalse(state.onKey(KeyEvent(Key.Tab, KeyEventType.Down)))
        assertFalse(state.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South)))
    }

    @Test
    fun `an open dialog keeps the page where it is`() {
        val state = ShowcaseState()
        state.dialogOpen = true
        assertFalse(state.onKey(KeyEvent(Key.PageDown, KeyEventType.Down)))
        assertEquals(Section.Home, state.section)
    }

    @Test
    fun `each visit to a new page is counted so it opens at its top`() {
        val state = ShowcaseState()
        state.goTo(Section.Text)
        state.goTo(Section.Text)
        assertEquals(1, state.visits)
    }

    @Test
    fun `a phone gets a narrow layout and a big monitor does not get lines a metre long`() {
        assertEquals(Size(390f * 400f / 390f, 844f * 400f / 390f), designFor(390.0, 844.0))
        assertEquals(Size(1280f, 720f), designFor(1280.0, 720.0))
        assertEquals(1680f, designFor(3360.0, 1890.0).width)

        val state = ShowcaseState()
        state.width = 400f
        assertTrue(state.compact)
        assertEquals(400f - 24f - 12f, cardWidthFor(state))
        state.width = 1280f
        assertFalse(state.compact)
        assertTrue(cardWidthFor(state) in 340f..560f)
    }

    @Test
    fun `frame times are written to one decimal place`() {
        assertEquals("16.7", oneDecimal(16.66f))
        assertEquals("0.0", oneDecimal(0f))
        assertEquals("3.5", oneDecimal(3.49f))
    }
}

/** The whole showcase composed on the headless backend and driven the way a visitor drives it. */
class ShowcaseUiTest {

    private val art = showcaseAtlas(FakeTexture(24, 24), FakeTexture(CoinFrames * CoinSize, CoinSize)) { _, _, _, width, height ->
        FakeTexture(width, height)
    }

    /**
     * A screen tall enough that every card on the longest page is on it, so a test can point at any
     * of them, with motion reduced so the screen goes still between steps.
     */
    private fun showcase(width: Float = 1280f, height: Float = 2600f, links: MutableList<String> = mutableListOf(), test: (UiTest, ShowcaseState) -> Unit) {
        val state = ShowcaseState().also {
            it.width = width
            it.reduceMotion = true
        }
        uiTest(Size(width, height), input = { ShowcaseInput(state, it) }) {
            Showcase(state, ShowcaseSkins(art), openLink = { links += it })
        }.use { ui -> test(ui, state) }
    }

    @Test
    fun `every section opens from its button in the side list`() = showcase { ui, state ->
        Section.entries.reversed().forEach { section ->
            ui.click("nav-${section.tag}")
            ui.advanceBy(400)
            assertEquals(section, state.section)
            ui.assertExists("page-${section.tag}")
        }
    }

    @Test
    fun `page down walks the whole tour and every page composes`() = showcase { ui, state ->
        Section.entries.drop(1).forEach { section ->
            ui.key(Key.PageDown)
            ui.advanceBy(400)
            assertEquals(section, state.section)
            ui.assertExists("page-${section.tag}")
        }
    }

    @Test
    fun `a pad's bumpers turn the page`() = showcase { ui, state ->
        ui.pad(GamepadButton.RightBumper)
        ui.advanceBy(400)
        ui.assertExists("page-widgets")
        ui.pad(GamepadButton.LeftBumper)
        ui.advanceBy(400)
        assertEquals(Section.Home, state.section)
    }

    @Test
    fun `the landing page starts the tour and links out`() {
        val links = mutableListOf<String>()
        showcase(links = links) { ui, state ->
            ui.click("link-github")
            ui.click("link-wiki")
            assertEquals(listOf(RepoUrl, WikiUrl), links)
            ui.click("start")
            ui.advanceBy(400)
            assertEquals(Section.Widgets, state.section)
        }
    }

    @Test
    fun `a button counts its presses and the dialog opens and closes with escape`() = showcase { ui, state ->
        state.goTo(Section.Widgets)
        ui.advanceBy(400)
        ui.click("button-play")
        ui.assertText("presses", "Pressed 1 time")

        ui.click("open-dialog")
        ui.advanceBy(400)
        ui.assertExists("dialog")
        ui.key(Key.Escape)
        ui.advanceBy(600)
        assertFalse(state.dialogOpen)
        ui.assertDoesNotExist("dialog")
    }

    @Test
    fun `typing into the call sign field greets you`() = showcase { ui, state ->
        state.goTo(Section.Widgets)
        ui.advanceBy(400)
        ui.click("callsign")
        ui.type("Nova")
        ui.settle()
        assertTrue(ui.texts("page-widgets").any { it == "Welcome aboard, Nova" })
    }

    @Test
    fun `the settings page switches skin and text size for the whole showcase`() = showcase { ui, state ->
        state.goTo(Section.Settings)
        ui.advanceBy(400)
        ui.click("skin-HighContrast")
        assertEquals(SkinChoice.HighContrast, state.skin)
        ui.click("scale-150")
        ui.advanceBy(400)
        assertEquals(1.5f, state.textScale)
        // Still usable at the new size: the list down the side still turns the page.
        ui.click("nav-game")
        ui.advanceBy(400)
        ui.assertExists("page-game")
    }

    @Test
    fun `the debug page turns an overlay on for everything`() = showcase { ui, state ->
        state.goTo(Section.Debug)
        ui.advanceBy(400)
        ui.click("overlay-layout")
        assertTrue(state.layoutOverlay)
        ui.key(Key.PageUp)
        ui.advanceBy(400)
        ui.assertExists("page-settings")
    }

    @Test
    fun `the game page takes hits and shots`() = showcase { ui, state ->
        state.goTo(Section.Game)
        ui.advanceBy(400)
        ui.click("hit")
        ui.click("ability-0")
        ui.click("range")
        ui.advanceBy(300)
        assertTrue(ui.texts("page-game").any { it == "Shots fired: 1" })
    }

    @Test
    fun `on a phone the sections go along the top and still work`() = showcase(width = 400f, height = 860f) { ui, state ->
        assertTrue(state.compact)
        ui.click("nav-widgets")
        ui.advanceBy(400)
        ui.assertExists("page-widgets")
    }
}
