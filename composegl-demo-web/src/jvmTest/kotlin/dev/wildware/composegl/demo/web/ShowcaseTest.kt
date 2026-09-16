package dev.wildware.composegl.demo.web

import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.node.UiNode
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
        assertEquals(Section.Tools, state.section)
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
    private fun showcase(
        width: Float = 1280f,
        height: Float = 2600f,
        links: MutableList<String> = mutableListOf(),
        direction: LayoutDirection = LayoutDirection.Ltr,
        test: (UiTest, ShowcaseState) -> Unit,
    ) {
        val state = ShowcaseState().also {
            it.width = width
            it.reduceMotion = true
        }
        uiTest(Size(width, height), input = { ShowcaseInput(state, it) }) {
            ProvideLayoutDirection(direction) {
                Showcase(state, ShowcaseSkins(art), openLink = { links += it })
            }
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

    // --- the pages from the 0.6.0 sweep ------------------------------------------------------------

    @Test
    fun `every page from the sweep composes, lays out inside the page and draws, both ways round`() {
        SweepPages.forEach { section ->
            Directions.forEach { direction ->
                showcase(direction = direction) { ui, state ->
                    state.goTo(section)
                    ui.advanceBy(400)
                    ui.assertExists("page-${section.tag}")

                    val area = ui.node("page-area").boundsInRoot
                    val page = ui.node("page-${section.tag}")
                    var cards = 0
                    page.forEach { inside ->
                        val box = inside.boundsInRoot
                        if (box.isEmpty) return@forEach
                        if (inside.name == "card" || inside.testTag != null) cards++
                        assertTrue(
                            box.left >= area.left - 1f && box.right <= area.right + 1f,
                            "${inside.testTag ?: inside.name} on ${section.tag}/$direction runs outside the page, " +
                                "$box in $area:\n" + ui.dump(),
                        )
                    }
                    assertTrue(cards >= 5, "${section.tag}/$direction has only $cards things on it")
                    // A whole frame drawn: a widget that throws while painting is not caught by layout.
                    ui.render()
                }
            }
        }
    }

    @Test
    fun `every page from the sweep takes a mouse, a keyboard and a pad without throwing, both ways round`() {
        SweepPages.forEach { section ->
            Directions.forEach { direction ->
                showcase(direction = direction) { ui, state ->
                    state.goTo(section)
                    ui.advanceBy(400)

                    // The mouse: a hover, a click and a right-click on everything tagged on the page.
                    // By node rather than by tag: a widget's own parts can share a tag, as two bags' squares do.
                    val tagged = mutableListOf<UiNode>()
                    ui.node("page-${section.tag}").forEach { node -> if (node.testTag != null && node.testTag != "page-${section.tag}") tagged += node }
                    tagged.forEach { node ->
                        if (state.section != section || node.parent == null) return@forEach
                        val box = node.boundsInRoot
                        if (box.isEmpty || box.top < 0f || box.bottom > ui.size.height) return@forEach
                        ui.moveTo(box.centre)
                        ui.click(box.centre)
                        ui.key(Key.Escape)
                    }
                    state.goTo(section)
                    ui.advanceBy(400)
                    ui.click(ui.node("page-${section.tag}").boundsInRoot.centre, PointerButton.Secondary)
                    ui.key(Key.Escape)
                    ui.scroll("page-area", Offset(0f, 200f))
                    ui.scroll("page-area", Offset(0f, -200f))

                    // The keyboard: walk focus onto the page and press what it lands on.
                    repeat(30) { ui.key(Key.Tab) }
                    ui.key(Key.Enter)
                    ui.key(Key.Left)
                    ui.key(Key.Right)
                    ui.key(Key.Escape)

                    // The pad.
                    ui.stick(0.9f, 0.4f)
                    ui.stick(0f, 0f)
                    listOf(GamepadButton.DpadDown, GamepadButton.DpadRight, GamepadButton.South, GamepadButton.East).forEach { ui.pad(it) }

                    ui.render()
                }
            }
        }
    }

    @Test
    fun `the tools page's menus, context menu and folding sections do what they say`() = showcase { ui, state ->
        state.goTo(Section.Tools)
        ui.advanceBy(400)

        ui.click("context-box", PointerButton.Secondary)
        ui.click(ui.menuRow("Empty it").boundsInRoot.centre)
        ui.settle()
        assertEquals(listOf("Ammo: 0"), ui.texts("ammo"))

        ui.click(ui.titled("menubar.title", "Weapon").boundsInRoot.centre)
        ui.click(ui.menuRow("LANCE").boundsInRoot.centre)
        ui.settle()
        assertEquals("LANCE", state.weapon)

        assertFalse(ui.texts("fold-audio").any { "Music" in it })
        ui.click(ui.node("fold-audio").boundsInRoot.let { Offset(it.centre.x, it.top + 12f) })
        ui.advanceBy(600)
        assertTrue(ui.texts("fold-audio").any { "Music" in it }, "the audio section opened:\n" + ui.dump())
    }

    @Test
    fun `the graphics section's rows stand one under another and do not overlap, both ways round`() {
        Directions.forEach { direction ->
            showcase(direction = direction) { ui, state ->
                state.goTo(Section.Tools)
                ui.advanceBy(600)

                val fold = ui.node("fold-graphics")
                val body = mutableListOf<UiNode>().also { found -> fold.forEach { if (it.name == "collapsingheader.body") found += it } }.single()
                // The body holds one container, styled "collapsingheader.body", and the rows are what is in it.
                val rows = body.children.single().children
                assertTrue(rows.size >= 2, "$direction: the graphics section has ${rows.size} rows:\n" + ui.dump())

                val bloom = rows.single { "Bloom" in ui.wordsOf(it) }.boundsInRoot
                val shadow = rows.single { "Shadow quality" in ui.wordsOf(it) }.boundsInRoot
                assertFalse(bloom.overlaps(shadow), "$direction: Bloom $bloom is drawn over Shadow quality $shadow:\n" + ui.dump())
                rows.forEachIndexed { i, a ->
                    rows.drop(i + 1).forEach { b ->
                        assertFalse(a.boundsInRoot.overlaps(b.boundsInRoot), "$direction: rows overlap, ${a.boundsInRoot} and ${b.boundsInRoot}:\n" + ui.dump())
                    }
                }
            }
        }
    }

    @Test
    fun `the hud page marks hits, turns the view, picks from the wheel and plays subtitles`() = showcase { ui, state ->
        state.goTo(Section.Hud)
        ui.advanceBy(400)

        ui.click("mark-hit")
        ui.click("crosshair")
        ui.advanceBy(600)
        assertEquals(listOf("Hits landed: 1 · shots taken: 1"), ui.texts("hits"))

        val before = ui.texts("compass")
        val world = ui.node("world").boundsInRoot
        ui.press(world.centre)
        ui.moveTo(Offset(world.centre.x + 60f, world.centre.y))
        ui.moveTo(Offset(world.centre.x + 120f, world.centre.y))
        ui.release()
        ui.settle()
        assertTrue(before != ui.texts("compass"), "dragging the view turned the compass: $before")

        ui.click("open-wheel")
        ui.advanceBy(400)
        val wheel = ui.node("wheel-area").boundsInRoot
        // Four slices from the top, clockwise: the third is straight down.
        ui.click(Offset(wheel.centre.x, wheel.centre.y + 80f))
        ui.advanceBy(400)
        assertEquals("LANCE", state.weapon)

        ui.click("play-scene")
        ui.advanceBy(300)
        assertTrue(ui.texts("subtitle-stage").any { "Hold the door" in it }, "the first line is up:\n" + ui.texts("subtitle-stage"))
    }

    @Test
    fun `the dialogue box moves on when clicked and offers its answers`() = showcase { ui, state ->
        state.goTo(Section.Hud)
        ui.advanceBy(400)
        ui.advanceBy(3_000)
        ui.click("dialogue")
        ui.advanceBy(3_000)
        assertTrue(ui.texts("dialogue").any { "Here it is." in it }, "the question's answers are up:\n" + ui.texts("dialogue"))
    }

    @Test
    fun `the gear page moves a pile between bags, shows a card and finishes a quest`() = showcase { ui, state ->
        state.goTo(Section.Gear)
        ui.advanceBy(400)

        // The bow is in the pack's top-left two squares; the chest's bottom-left square is empty.
        assertTrue(ui.texts("pack").contains("BOW"))
        val pack = ui.node("pack").boundsInRoot
        val chest = ui.node("chest").boundsInRoot
        val pitch = pack.width / 4f
        ui.press(Offset(pack.left + pitch * 0.5f, pack.top + pitch * 0.5f))
        ui.moveTo(Offset(pack.left + pitch, pack.top + pitch))
        ui.moveTo(Offset(chest.left + pitch * 0.5f, chest.top + pitch * 2.5f))
        ui.release()
        ui.settle()
        assertFalse(ui.texts("pack").contains("BOW"), "the bow left the pack:\n" + ui.dump())
        assertTrue(ui.texts("chest").contains("BOW"), "and arrived in the chest")

        ui.moveTo("loot-0")
        ui.advanceBy(200)
        assertTrue(ui.texts("loot").any { "GLASSWING" in it }, "hovering a drop shows its card")

        repeat(3) { ui.click("shard") }
        ui.advanceBy(1_000)
        assertTrue(ui.texts("objectives").any { "THE NORTH ROAD" in it }, "the next quest arrived:\n" + ui.texts("objectives"))
    }

    @Test
    fun `the chat box opens from its button and says what was typed`() = showcase { ui, state ->
        state.goTo(Section.Gear)
        ui.advanceBy(400)
        ui.click("open-chat")
        ui.advanceBy(300)
        ui.type("on my way")
        ui.key(Key.Enter)
        ui.advanceBy(300)
        assertTrue(ui.texts("chat").any { "on my way" in it }, "the line is in the chat:\n" + ui.texts("chat"))
    }

    @Test
    fun `the debug page opens, docks and floats windows and runs console commands`() = showcase { ui, state ->
        state.goTo(Section.Debug)
        ui.advanceBy(400)

        ui.click("open-tweaks")
        ui.advanceBy(300)
        assertTrue(state.tuningOpen)
        ui.click("dock-tweaks")
        ui.advanceBy(300)
        assertEquals(listOf("Docked now: 1"), ui.texts("docked"))
        ui.click("undock")
        ui.advanceBy(300)
        assertEquals(listOf("Docked now: 0"), ui.texts("docked"))

        ui.click("run-heat")
        assertEquals(0.95f, state.heat)
        ui.click("fire")
        assertEquals(1f, state.heat)

        ui.click("open-console")
        ui.advanceBy(400)
        ui.type("page hud")
        ui.key(Key.Enter)
        ui.advanceBy(400)
        assertEquals(Section.Hud, state.section)
    }

    private companion object {
        val SweepPages = listOf(Section.Tools, Section.Hud, Section.Gear, Section.Debug)
        val Directions = listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)
    }
}

/** The words [node] and everything inside it draw, joined: a mnemonic's underlined letter is its own run. */
private fun UiTest.wordsOf(node: UiNode): String {
    val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
    val bounds = node.layoutBoundsInRoot
    DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
    return canvas.texts().joinToString("")
}

private fun UiTest.named(name: String): List<UiNode> {
    val found = mutableListOf<UiNode>()
    root.forEach { if (it.name == name) found += it }
    return found
}

/** A row of whichever menu is open, found by its words as a person finds it. Menus take no tags. */
private fun UiTest.menuRow(label: String): UiNode =
    named("menu.item").lastOrNull { wordsOf(it).startsWith(label) } ?: throw AssertionError("no open menu row \"$label\":\n" + dump())

private fun UiTest.titled(name: String, label: String): UiNode =
    named(name).firstOrNull { wordsOf(it) == label } ?: throw AssertionError("no $name \"$label\":\n" + dump())
