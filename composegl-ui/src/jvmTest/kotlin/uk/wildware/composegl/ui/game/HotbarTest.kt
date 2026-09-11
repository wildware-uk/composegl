package uk.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import uk.wildware.composegl.ui.animation.Clock
import uk.wildware.composegl.ui.backend.MonospaceFontProvider
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.focus.FocusManager
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.input.GamepadButton
import uk.wildware.composegl.ui.input.GamepadEvent
import uk.wildware.composegl.ui.input.GamepadId
import uk.wildware.composegl.ui.input.GamepadNavigator
import uk.wildware.composegl.ui.input.Key
import uk.wildware.composegl.ui.input.KeyEvent
import uk.wildware.composegl.ui.input.KeyEventType
import uk.wildware.composegl.ui.input.KeyNavigator
import uk.wildware.composegl.ui.input.KeyRouter
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerRouter
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.onKeyEvent
import uk.wildware.composegl.ui.skin.Skin
import uk.wildware.composegl.ui.skin.SkinDrawable
import uk.wildware.composegl.ui.widget.Button
import uk.wildware.composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The row of abilities along the bottom of a game.
 *
 * A hotbar is used three ways at once — the number keys, a click, a pad — and the issue asks for
 * all three plus one thing that is about reading rather than pressing: an empty slot has to look
 * different from a slot holding something that cannot be used right now.
 */
class HotbarTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val navigator = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    private var wall = 0L

    private val used = mutableListOf<Int>()
    private val chosen = mutableListOf<Int>()

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear()
        MeasurePass().run(host.root, Constraints.atMost(600f, 200f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        repeat(2) { frame() }
    }

    private fun bar(
        slots: List<HotbarSlot>,
        selected: Int = -1,
        hotkeys: Boolean = true,
    ) = show {
        Hotbar(
            slots = slots,
            modifier = Modifier,
            selected = selected,
            onUse = { used += it },
            onSelect = { chosen += it },
            hotkeys = hotkeys,
            clock = Clock.Ui,
        )
    }

    private fun key(key: Key, repeat: Boolean = false) {
        val down = KeyEvent(key, KeyEventType.Down, repeat = repeat)
        if (!router.onKey(down)) navigator.onKey(down)
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) navigator.onKey(up)
        frame()
    }

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, timeMillis = wall / 1_000_000))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, timeMillis = wall / 1_000_000))
        frame()
    }

    private fun fillOf(style: String) = (Skin.Default.resolve(style).background as SkinDrawable.Fill).colour

    private fun boxes(style: String) =
        canvas.calls.filterIsInstance<DrawCall.Rectangle>().filter { it.colour == fillOf(style) }

    private fun centreOfSlot(index: Int): Offset {
        val slot = 48f
        val spacing = 8f
        return Offset(index * (slot + spacing) + slot / 2f, slot / 2f)
    }

    /** The top of the first slot, whatever state the skin drew it in. */
    private fun slotTop(): Float =
        canvas.calls.filterIsInstance<DrawCall.Rectangle>().first().rect.top

    private fun abilities(count: Int = 4) = List(count) { HotbarSlot(label = "${it + 1}", prompt = "${it + 1}") }

    // --- the three ways of pressing it ---------------------------------------------------------

    @Test
    fun `the number keys press slots`() {
        bar(abilities())

        key(Key.Digit3)

        assertEquals(listOf(2), used, "the third key presses the third slot")
        assertEquals(listOf(2), chosen)
    }

    @Test
    fun `zero is the tenth slot`() {
        bar(abilities(10))

        key(Key.Digit0)

        assertEquals(listOf(9), used)
    }

    @Test
    fun `a held number key is one press`() {
        bar(abilities())

        key(Key.Digit1)
        repeat(5) { key(Key.Digit1, repeat = true) }

        assertEquals(listOf(0), used, "holding a key fired the ability six times")
    }

    @Test
    fun `a click presses the slot under the pointer`() {
        bar(abilities())

        click(centreOfSlot(1))

        assertEquals(listOf(1), used)
    }

    @Test
    fun `a pad walks along the row and presses with south`() {
        bar(abilities())

        // A real pad: the d-pad walks along the row and South presses whatever has focus. Both go
        // through the same focus manager a keyboard's Tab uses, which is the point of having one.
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId(0), GamepadButton.DpadRight))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId(0), GamepadButton.DpadRight))
        frame()
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId(0), GamepadButton.South))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId(0), GamepadButton.South))
        frame()

        assertEquals(listOf(1), used, "South pressed the slot the pad had walked to")
    }

    // --- what can be pressed -------------------------------------------------------------------

    @Test
    fun `an empty slot looks different from a disabled one`() {
        bar(
            listOf(
                HotbarSlot(label = "1"),
                HotbarSlot(),
                HotbarSlot(label = "3", enabled = false),
            ),
        )

        val empties = boxes("hotbar.slot.empty")
        assertEquals(1, empties.size, "only the empty slot is drawn as a hole")
        assertEquals(centreOfSlot(1).x, empties.first().rect.centre.x, 1f, "and it is the middle one")

        val atDisabled = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
            .filter { kotlin.math.abs(it.rect.centre.x - centreOfSlot(2).x) < 1f }
        assertTrue(
            atDisabled.any { it.colour != fillOf("hotbar.slot.empty") },
            "the disabled slot was drawn as a hole in the bar",
        )
        assertNotEquals(
            fillOf("hotbar.slot"),
            fillOf("hotbar.slot.empty"),
            "a hole in the bar and something unusable must not be the same picture",
        )
    }

    @Test
    fun `an empty slot cannot be pressed`() {
        bar(listOf(HotbarSlot(), HotbarSlot(label = "2")))

        key(Key.Digit1)
        click(centreOfSlot(0))

        assertTrue(used.isEmpty(), "an empty slot was used")
    }

    @Test
    fun `a disabled slot cannot be pressed`() {
        bar(listOf(HotbarSlot(label = "1", enabled = false)))

        key(Key.Digit1)

        assertTrue(used.isEmpty())
    }

    @Test
    fun `a slot with no charges left cannot be pressed`() {
        bar(listOf(HotbarSlot(label = "1", charges = 0), HotbarSlot(label = "2", charges = 3)))

        key(Key.Digit1)
        key(Key.Digit2)

        assertEquals(listOf(1), used, "an empty stack fired anyway")
    }

    @Test
    fun `a slot that is cooling down cannot be pressed`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(1_000, Clock.Ui)
            Hotbar(
                slots = listOf(HotbarSlot(label = "1", cooldown = cooldown)),
                onUse = { used += it },
                clock = Clock.Ui,
            )
        }

        key(Key.Digit1)
        assertEquals(listOf(0), used, "the first press goes through")

        cooldown.trigger()
        frame(0)
        frame(0)
        key(Key.Digit1)

        assertEquals(listOf(0), used, "and the second, mid-cooldown, does not")
    }

    @Test
    fun `a key nobody has a slot for is left alone`() {
        bar(abilities(2))

        key(Key.Digit9)

        assertTrue(used.isEmpty(), "a hotbar of two slots answered for the ninth key")
    }

    @Test
    fun `the number keys can be turned off`() {
        bar(abilities(), hotkeys = false)

        key(Key.Digit1)

        assertTrue(used.isEmpty(), "a game that binds its own keys got ours as well")
    }

    // --- what it says --------------------------------------------------------------------------

    @Test
    fun `a hoisted state presses slots from outside the bar`() {
        lateinit var state: HotbarState
        show {
            state = remember { HotbarState() }
            Hotbar(
                slots = abilities(),
                state = state,
                onUse = { used += it },
                clock = Clock.Ui,
            )
        }

        assertTrue(state.use(2), "a game's own binding pressed the third slot")
        assertEquals(listOf(2), used)
        frame()
        frame()

        assertTrue(!state.use(99), "a slot that is not there")
    }

    @Test
    fun `a screen can answer the number keys on the hotbar's behalf`() {
        lateinit var state: HotbarState
        show {
            state = remember { HotbarState() }
            // What a game does: the keys are answered at the top of the screen, where every key
            // already passes, so the bar works while focus is somewhere else entirely.
            Box(Modifier.onKeyEvent(state::onKey)) {
                Button("elsewhere", onClick = {}, initialFocus = true)
            }
            Hotbar(slots = abilities(), state = state, hotkeys = false, onUse = { used += it })
        }

        key(Key.Digit2)

        assertEquals(listOf(1), used, "the key never reached the hotbar")
    }

    @Test
    fun `a slot draws its prompt and its charges`() {
        bar(listOf(HotbarSlot(label = "Q", prompt = "1", charges = 3)))

        val text = canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }

        assertTrue("Q" in text, "the ability")
        assertTrue("1" in text, "the key that presses it")
        assertTrue("3" in text, "how many uses are left")
    }

    @Test
    fun `a slot that does not count charges draws no count`() {
        bar(listOf(HotbarSlot(label = "Q")))

        val text = canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }

        assertEquals(listOf("Q"), text)
    }

    @Test
    fun `the chosen slot is drawn as the chosen one`() {
        bar(abilities(3), selected = 1)

        assertEquals(1, boxes("hotbar.slot.selected").size)
    }

    @Test
    fun `a press dips the slot and it comes back`() {
        bar(abilities())

        val before = slotTop()
        key(Key.Digit1)
        frame(0)
        frame(0)
        val during = slotTop()
        assertTrue(during > before, "a pressed slot should move")

        repeat(10) { frame(20) }
        assertEquals(before, slotTop(), 0.01f, "and come back up")
    }

    @Test
    fun `a bar nobody is touching costs nothing`() {
        bar(abilities())
        repeat(10) { frame(20) }

        repeat(50) {
            wall += 20_000_000L
            assertTrue(!host.frame(wall), "frame $it redrew a hotbar nothing had happened to")
        }
    }

    @Test
    fun `a hotbar with no slots draws nothing and answers no keys`() {
        bar(emptyList())

        key(Key.Digit1)

        assertNull(canvas.calls.filterIsInstance<DrawCall.Rectangle>().firstOrNull())
        assertTrue(used.isEmpty())
    }
}
