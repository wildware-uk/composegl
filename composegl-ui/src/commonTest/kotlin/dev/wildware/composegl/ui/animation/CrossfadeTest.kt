package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One screen fading into the next, driven the way a player drives it.
 *
 * Each test composes real pages, draws them into a recording canvas through the frame a game uses and
 * moves time on a frame at a time. What is asserted is what a player sees: which pages are in the tree,
 * how opaque each was drawn, what text each shows and where focus is.
 */
class CrossfadeTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val renderer = UiRenderer(host, canvas).also { it.focus = focus }
    private val viewport = Viewport.oneToOne(Size(400f, 300f))
    private val pointer = PointerRouter(host.root, focus)

    private var wall = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(3)
    }

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        canvas.clear()
        return renderer.render(viewport, wall)
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun play(millis: Int) = frames((millis + 15) / 16)

    private fun click(tag: String) {
        val at = host.root.find(tag).boundsInRoot.centre
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
    }

    /** How opaque the fill of [colour] was drawn last frame, or null when it was not drawn. */
    private fun alphaOf(colour: Colour): Float? =
        canvas.only<DrawCall.Rectangle>().firstOrNull { it.colour == colour }?.alpha

    /** Each run of text drawn last frame, with how opaque it was. */
    private fun textsDrawn(): Map<String, Float> = canvas.only<DrawCall.Text>().associate { it.text to it.alpha }

    // --- the fade -----------------------------------------------------------------------------------

    @Test
    fun `clicking through to another page fades one out as the other fades in`() {
        var page by mutableStateOf(Page.Main)
        show {
            Crossfade(page, spec = Tween(300, easing = Easings.Linear)) { shown ->
                when (shown) {
                    Page.Main -> Column(Modifier.testTag("main").background(MainColour)) {
                        Button("OPTIONS", onClick = { page = Page.Options }, modifier = Modifier.testTag("to-options"))
                    }
                    else -> Box(Modifier.size(200f, 100f).testTag("options").background(OptionsColour))
                }
            }
        }
        assertEquals(1f, alphaOf(MainColour), "the first page is simply there")
        assertNull(host.root.findOrNull("options"))

        click("to-options")
        frame()
        assertNotNull(host.root.findOrNull("main"), "the old page is still there the frame after the click")
        assertNotNull(host.root.findOrNull("options"), "and the new one has arrived on the same frame")
        assertNull(alphaOf(OptionsColour), "starting from nothing rather than cutting in, so nothing of it is drawn yet")
        assertEquals(1f, alphaOf(MainColour), "while the old page has not started to go")

        frames(4)
        val arriving = assertNotNull(alphaOf(OptionsColour), "a few frames on, the new page is drawn")
        assertTrue(arriving < 0.3f, "but faintly, at $arriving")

        play(150)
        val leaving = assertNotNull(alphaOf(MainColour))
        val coming = assertNotNull(alphaOf(OptionsColour))
        assertTrue(leaving in 0.25f..0.75f, "half way, the old page is half faded out, at $leaving")
        assertTrue(coming in 0.25f..0.75f, "and the new page half faded in, at $coming")

        play(250)
        assertNull(host.root.findOrNull("main"), "once faded out, the old page leaves the tree")
        assertNull(alphaOf(MainColour), "and is not drawn")
        assertEquals(1f, alphaOf(OptionsColour), "the new page is fully there")
    }

    @Test
    fun `a settled crossfade costs no frames before or after a change`() {
        var page by mutableStateOf(Page.Main)
        show {
            Crossfade(page) { shown ->
                Box(Modifier.size(40f).background(if (shown == Page.Main) MainColour else OptionsColour))
            }
        }
        repeat(60) { assertFalse(frame(), "frame $it redrew a settled page") }

        page = Page.Options
        assertTrue(frame(), "a change is drawn")
        play(400)
        repeat(60) { assertFalse(frame(), "frame $it redrew a page that had finished fading in") }
        assertEquals(listOf(OptionsColour), canvas.only<DrawCall.Rectangle>().map { it.colour })
    }

    @Test
    fun `the screen around a settled crossfade recomposing does not redraw it`() {
        var page by mutableStateOf(Page.Main)
        var tick by mutableStateOf(0)
        show {
            // Read here, so every tick recomposes this scope and calls Crossfade again with a new modifier.
            tick
            Crossfade(page, Modifier.size(40f), spec = Tween(200)) { shown ->
                Box(Modifier.size(40f).background(if (shown == Page.Main) MainColour else OptionsColour))
            }
        }
        repeat(20) {
            tick++
            assertFalse(frame(), "tick $it redrew a settled crossfade")
        }
        page = Page.Options
        play(400)
        repeat(20) {
            tick++
            assertFalse(frame(), "tick $it redrew a crossfade settled after a change")
        }
    }

    @Test
    fun `going back part way turns round and the page keeps what it remembered`() {
        var page by mutableStateOf(Page.Main)
        val seen = mutableListOf<Float>()
        show {
            Crossfade(page, spec = Tween(300, easing = Easings.Linear)) { shown ->
                when (shown) {
                    Page.Main -> Column(Modifier.testTag("main").background(MainColour)) {
                        var count by remember { mutableStateOf(0) }
                        Button("COUNT $count", onClick = { count++ }, modifier = Modifier.testTag("count"))
                    }
                    else -> Box(Modifier.size(40f).testTag("options").background(OptionsColour))
                }
            }
        }
        repeat(3) {
            click("count")
            frame()
        }
        assertEquals(1f, textsDrawn()["COUNT 3"])

        page = Page.Options
        repeat(10) {
            frame()
            seen += assertNotNull(alphaOf(MainColour))
        }
        val turningPoint = seen.last()
        assertTrue(turningPoint in 0.2f..0.8f, "part way out, at $turningPoint")

        page = Page.Main
        repeat(30) {
            frame()
            assertNotNull(host.root.findOrNull("main"), "the page left the tree on frame $it after going back")
            seen += assertNotNull(alphaOf(MainColour))
        }
        assertEquals(1f, seen.last(), "it came all the way back")
        assertTrue(seen.min() >= turningPoint - 0.1f, "it turned round rather than finishing its exit: ${seen.min()}")
        assertEquals(1f, textsDrawn()["COUNT 3"], "with its count intact")
        assertNull(host.root.findOrNull("options"), "and the page it was going to has faded back out and gone")

        // Leaving for good forgets it, so coming back later starts it fresh.
        page = Page.Options
        play(400)
        assertNull(host.root.findOrNull("main"))
        page = Page.Main
        play(400)
        assertEquals(1f, textsDrawn()["COUNT 0"], "a page that had gone comes back new")
    }

    @Test
    fun `three pages flicked through quickly each fade from where they were and only the last stays`() {
        var page by mutableStateOf(Page.Main)
        show {
            Crossfade(page, spec = Tween(300, easing = Easings.Linear)) { shown ->
                val colour = when (shown) {
                    Page.Main -> MainColour
                    Page.Options -> OptionsColour
                    Page.Credits -> CreditsColour
                }
                Box(Modifier.size(40f).testTag(shown.name).background(colour))
            }
        }

        page = Page.Options
        frames(8)
        page = Page.Credits
        frame()
        assertEquals(3, Page.entries.count { host.root.findOrNull(it.name) != null }, "all three are on screen")
        val optionsWhenLeft = assertNotNull(alphaOf(OptionsColour))
        assertTrue(optionsWhenLeft in 0.1f..0.6f, "options was part way in, at $optionsWhenLeft")

        repeat(6) {
            frame()
            val options = assertNotNull(alphaOf(OptionsColour))
            assertTrue(options <= optionsWhenLeft + 0.01f, "options turned round and faded out from $optionsWhenLeft, not up to $options")
        }

        play(400)
        assertNull(host.root.findOrNull(Page.Main.name))
        assertNull(host.root.findOrNull(Page.Options.name))
        assertEquals(1f, alphaOf(CreditsColour))
        assertEquals(1, canvas.only<DrawCall.Rectangle>().size, "one page left on screen")
    }

    @Test
    fun `a portrait keyed on its expression fades only when the expression changes`() {
        var portrait by mutableStateOf(Portrait("SMILE", health = 10))
        show {
            Crossfade(portrait, contentKey = { it.expression }, spec = Tween(300, easing = Easings.Linear)) {
                Text("${it.expression} ${it.health}")
            }
        }
        assertEquals(mapOf("SMILE 10" to 1f), textsDrawn())

        portrait = portrait.copy(health = 7)
        frame()
        assertEquals(mapOf("SMILE 7" to 1f), textsDrawn(), "same expression: redrawn at once with no fade")
        assertFalse(host.clocks.isAnimating)

        portrait = portrait.copy(expression = "FROWN")
        play(150)
        val drawn = textsDrawn()
        assertEquals(setOf("SMILE 7", "FROWN 7"), drawn.keys, "the old face shows what it showed, the new one what it shows")
        assertTrue(drawn.getValue("SMILE 7") in 0.25f..0.75f)
        assertTrue(drawn.getValue("FROWN 7") in 0.25f..0.75f)

        play(250)
        assertEquals(mapOf("FROWN 7" to 1f), textsDrawn())
    }

    @Test
    fun `a smaller page is centred over the bigger one and the box shrinks once the bigger one has gone`() {
        var page by mutableStateOf(Page.Main)
        show {
            Crossfade(page, Modifier.testTag("fade"), contentAlignment = Alignment.Centre, spec = Tween(200)) { shown ->
                when (shown) {
                    Page.Main -> Box(Modifier.size(200f, 100f).testTag("big").background(MainColour))
                    else -> Box(Modifier.size(50f).testTag("small").background(OptionsColour))
                }
            }
        }
        assertEquals(Size(200f, 100f), host.root.find("fade").boundsInRoot.size)

        page = Page.Options
        play(100)
        val big = host.root.find("big").boundsInRoot
        val small = host.root.find("small").boundsInRoot
        assertEquals(big.centre, small.centre, "centred over each other while they overlap")
        assertEquals(Size(200f, 100f), host.root.find("fade").boundsInRoot.size, "as big as the biggest page")

        play(300)
        assertEquals(Size(50f, 50f), host.root.find("fade").boundsInRoot.size, "and just the page left once it is gone")
    }

    @Test
    fun `a fade on the world clock holds still while the game is paused`() {
        host.clocks.register(Clock.World)
        var page by mutableStateOf(Page.Main)
        show {
            Crossfade(page, spec = Tween(400, easing = Easings.Linear), clock = Clock.World) { shown ->
                Box(Modifier.size(40f).background(if (shown == Page.Main) MainColour else OptionsColour))
            }
        }

        page = Page.Options
        play(200)
        host.clocks.stop(Clock.World)
        frames(2)
        val frozen = assertNotNull(alphaOf(MainColour))
        assertTrue(frozen in 0.2f..0.8f, "under way before the pause, at $frozen")

        play(2000)
        assertEquals(frozen, alphaOf(MainColour), "the world is paused, so the fade is")

        host.clocks.start(Clock.World)
        play(500)
        assertNull(alphaOf(MainColour), "and it finishes once the game carries on")
        assertEquals(1f, alphaOf(OptionsColour))
    }

    @Test
    fun `a page keyed the same is redrawn with the newer state even when its content captures something`() {
        var portrait by mutableStateOf(Portrait("SMILE", health = 10))
        val label = "HP"
        show {
            Crossfade(portrait, contentKey = { it.expression }) { Text("$label ${it.health}") }
        }
        repeat(3) {
            portrait = portrait.copy(health = portrait.health - 1)
            frame()
            assertEquals(mapOf("HP ${portrait.health}" to 1f), textsDrawn(), "health ${portrait.health} drawn at once")
        }
    }

    @Test
    fun `going back on the very frame the old page finishes leaving still shows it`() {
        // Try every frame around the end of the exit, so one of them lands on the frame it finishes.
        for (after in 14..22) {
            var page by mutableStateOf(Page.Main)
            val host = UiHost()
            val canvas = RecordingCanvas()
            val renderer = UiRenderer(host, canvas)
            var wall = 0L
            fun frame() {
                wall += 16_000_000L
                canvas.clear()
                renderer.render(viewport, wall)
            }
            try {
                host.setContent {
                    Crossfade(page, spec = Tween(300, easing = Easings.Linear)) { shown ->
                        Box(Modifier.size(40f).testTag(shown.name).background(if (shown == Page.Main) MainColour else OptionsColour))
                    }
                }
                repeat(3) { frame() }
                page = Page.Options
                repeat(after) { frame() }
                page = Page.Main
                repeat(40) { frame() }
                assertNotNull(host.root.findOrNull(Page.Main.name), "going back $after frames in, the main page is there")
                assertEquals(1f, canvas.only<DrawCall.Rectangle>().firstOrNull { it.colour == MainColour }?.alpha, "and drawn, $after frames in")
                assertNull(host.root.findOrNull(Page.Options.name), "$after frames in")
            } finally {
                host.dispose()
            }
        }
    }

    @Test
    fun `clicking a button on the page that is fading out still reaches it`() {
        var page by mutableStateOf(Page.Main)
        var clicks = 0
        show {
            Crossfade(page, spec = Tween(400, easing = Easings.Linear)) { shown ->
                when (shown) {
                    Page.Main -> Button("PLAY", onClick = { clicks++ }, modifier = Modifier.testTag("play"))
                    else -> Box(Modifier.size(10f).testTag("options"))
                }
            }
        }
        page = Page.Options
        play(100)
        click("play")
        frame()
        assertEquals(1, clicks, "the leaving page was clicked")
        play(500)
        assertNull(host.root.findOrNull("play"))
    }

    @Test
    fun `a crossfade taken away mid-fade leaves nothing behind and asks for no more frames`() {
        var page by mutableStateOf(Page.Main)
        var there by mutableStateOf(true)
        show {
            if (there) {
                Crossfade(page, spec = Tween(400)) { shown -> Box(Modifier.size(40f).testTag(shown.name).background(MainColour)) }
            }
        }
        page = Page.Options
        play(100)
        assertTrue(host.clocks.isAnimating)
        there = false
        frame()
        assertNull(host.root.findOrNull(Page.Main.name))
        assertNull(host.root.findOrNull(Page.Options.name))
        frame()
        assertFalse(host.clocks.isAnimating, "the fade stopped with it")
        repeat(30) { assertFalse(frame(), "frame $it redrew after the crossfade had gone") }

        there = true
        play(100)
        assertEquals(1f, alphaOf(MainColour), "put back, it starts settled on the current page")
        assertNotNull(host.root.findOrNull(Page.Options.name))
    }

    @Test
    fun `an empty page and a zero sized page fade in and out without trouble`() {
        var page by mutableStateOf(Page.Main)
        show {
            Crossfade(page, Modifier.testTag("fade"), spec = Tween(200)) { shown ->
                when (shown) {
                    Page.Main -> {}
                    Page.Options -> Box(Modifier.size(0f).testTag("nothing"))
                    Page.Credits -> Box(Modifier.size(30f).testTag("credits").background(CreditsColour))
                }
            }
        }
        assertEquals(Size(0f, 0f), host.root.find("fade").boundsInRoot.size)

        page = Page.Options
        play(300)
        assertNotNull(host.root.findOrNull("nothing"))
        page = Page.Credits
        play(300)
        assertNull(host.root.findOrNull("nothing"))
        assertEquals(1f, alphaOf(CreditsColour))
        page = Page.Main
        play(300)
        assertNull(host.root.findOrNull("credits"))
        assertEquals(Size(0f, 0f), host.root.find("fade").boundsInRoot.size)
        repeat(10) { assertFalse(frame(), "frame $it redrew an empty settled page") }
    }

    @Test
    fun `a crossfade inside a page that is fading out goes with it`() {
        var page by mutableStateOf(Page.Main)
        var tab by mutableStateOf(Page.Options)
        show {
            Crossfade(page, spec = Tween(300)) { outer ->
                if (outer == Page.Main) {
                    Crossfade(tab, spec = Tween(300)) { inner ->
                        Box(Modifier.size(40f).testTag("tab-${inner.name}").background(if (inner == Page.Options) OptionsColour else CreditsColour))
                    }
                } else {
                    Box(Modifier.size(40f).testTag("outer-credits").background(MainColour))
                }
            }
        }
        tab = Page.Credits
        page = Page.Credits
        play(150)
        assertNotNull(host.root.findOrNull("tab-Options"), "the inner fade plays inside the leaving page")
        assertNotNull(host.root.findOrNull("tab-Credits"))
        play(400)
        assertNull(host.root.findOrNull("tab-Options"))
        assertNull(host.root.findOrNull("tab-Credits"))
        assertEquals(1f, alphaOf(MainColour))
        assertFalse(host.clocks.isAnimating)
    }

    // --- every way in -------------------------------------------------------------------------------

    @Test
    fun `a pad and the keyboard move between menu pages and focus follows into each page`() {
        var page by mutableStateOf(Page.Main)
        uiTest(Size(400f, 300f), onBack = { page = Page.Main }) {
            Crossfade(page, spec = Tween(300), clock = Clock.World) { shown ->
                when (shown) {
                    Page.Main -> Column(Modifier.testTag("main")) {
                        Button("PLAY", onClick = {}, initialFocus = true, modifier = Modifier.testTag("play"))
                        Button("OPTIONS", onClick = { page = Page.Options }, modifier = Modifier.testTag("to-options"))
                    }
                    else -> Column(Modifier.testTag("options")) {
                        Button("VOLUME", onClick = {}, initialFocus = true, modifier = Modifier.testTag("volume"))
                    }
                }
            }
        }.use { ui ->
            ui.assertFocused("play")
            ui.pad(GamepadButton.DpadDown)
            ui.assertFocused("to-options")

            // Every action waits out whatever is playing, so pause the world to catch the fade.
            ui.host.clocks.register(Clock.World)
            ui.host.clocks.stop(Clock.World)
            ui.pad(GamepadButton.South)
            assertEquals(Page.Options, page, "the pad pressed options")
            ui.assertText("main", "PLAY\nOPTIONS")
            ui.assertExists("options")
            ui.assertText("options", "")
            ui.assertFocused("to-options")

            ui.host.clocks.start(Clock.World)
            ui.advanceBy(400)
            ui.assertDoesNotExist("main")
            ui.assertFocused("volume")

            // The pad's back button fades home, and focus lands where the main page asks for it.
            ui.pad(GamepadButton.East)
            ui.advanceBy(400)
            ui.assertDoesNotExist("options")
            ui.assertFocused("play")

            // And the keyboard goes the same way.
            ui.key(Key.Down)
            ui.key(Key.Enter)
            ui.advanceBy(400)
            ui.assertText("options", "VOLUME")
            ui.assertFocused("volume")
            ui.key(Key.Escape)
            ui.advanceBy(400)
            ui.assertText("main", "PLAY\nOPTIONS")
        }
    }

    @Test
    fun `pages that have faded all the way out are let go however many there have been`() {
        var hp by mutableStateOf(100)
        val pages = CrossfadePages(100, 100)
        show {
            Crossfade(hp, Modifier, Tween(100), Alignment.TopStart, Clock.Ui, { it }, pages) { Text("HP $it") }
        }
        repeat(20) {
            hp--
            frames(3)
        }
        assertTrue(pages.all.size > 2, "quick changes overlap, so several pages are fading at once")
        play(300)
        assertEquals(listOf<Any?>(80), pages.all.map { it.key }, "only the page on screen is kept")
        assertEquals(mapOf("HP 80" to 1f), textsDrawn())
    }

    @Test
    fun `a page already on screen is handed the newer state and a forgotten page is forgotten once`() {
        val pages = CrossfadePages(Portrait("SMILE", 10), "SMILE")
        pages.show("SMILE", Portrait("SMILE", 3))
        assertEquals(1, pages.all.size)
        assertEquals(3, pages.all.single().state.health)
        assertTrue(pages.all.single().initiallyVisible)

        pages.show("FROWN", Portrait("FROWN", 3))
        val first = pages.all.first()
        assertFalse(pages.all.last().initiallyVisible, "a page added later fades in")

        pages.forget(first)
        pages.forget(first)
        assertEquals(1, pages.forgotten)
        assertEquals(listOf<Any?>("FROWN"), pages.all.map { it.key })
    }

    private enum class Page { Main, Options, Credits }

    private data class Portrait(val expression: String, val health: Int)

    private companion object {
        val MainColour = Colour.rgb(0x3366CC)
        val OptionsColour = Colour.rgb(0xCC3333)
        val CreditsColour = Colour.rgb(0x33CC66)
    }
}
