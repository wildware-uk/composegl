package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.text.Locale
import dev.wildware.composegl.ui.text.ProvideLocale
import dev.wildware.composegl.ui.text.Strings
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

/**
 * The heading strip, and the four things the issue asks it to get right: the names slide past and
 * wrap at 360, a pin outside the field of view sticks to the end it left by, the ends fade out, and
 * a heading that changes does not cost the rest of the HUD anything.
 *
 * The strip is a readout rather than a control, so the input tests here are about what it must
 * *not* do: a HUD strip that swallowed a click or took a turn in the focus order would be a bug a
 * player finds in the middle of a fight.
 */
class CompassBarTest {

    private val screen = Rect(0f, 0f, 600f, 400f)
    private val host = UiHost()
    private val canvas = RecordingCanvas(screen)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear(screen)
        MeasurePass().run(host.root, Constraints.atMost(screen.right, screen.bottom))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        repeat(3) { frame() }
    }

    /** A strip 400 wide and 64 tall in the top left, which the numbers below are worked out from. */
    private val bar = Modifier.width(400f).height(64f)

    /** The strip's padding is 10 across, so its inside runs from 10 to 390 with 200 in the middle. */
    private val middle = 200f

    private fun text(which: String) =
        canvas.calls.filterIsInstance<DrawCall.Text>().firstOrNull { it.text == which }

    /** Where a name's middle is, which is what a bearing puts on the strip rather than its left edge. */
    private fun centreOf(which: String): Float {
        val run = checkNotNull(text(which)) { "\"$which\" was not drawn" }
        // The monospace test font is 0.6 of its size across, and the names are 13 point.
        return run.at.x + which.length * 13f * 0.6f / 2f
    }

    private fun fans() = canvas.calls.filterIsInstance<DrawCall.Fan>()

    /** The pin diamonds: four points round a middle, as against a clamp arrow's three. */
    private fun diamonds() = fans().filter { it.points.size == 4 }

    private fun arrows() = fans().filter { it.points.size == 3 }

    private fun xOf(fan: DrawCall.Fan) = fan.points.sumOf { it.x.toDouble() }.toFloat() / fan.points.size

    // --- the names slide past and wrap at 360 ----------------------------------------------------

    @Test
    fun `north is in the middle of the strip when the player is facing it`() {
        show { CompassBar(heading = 0f, modifier = bar, live = false) }

        assertEquals(middle, centreOf("N"), 0.5f, "north should be dead ahead")
    }

    @Test
    fun `the names slide the other way as the player turns`() {
        var heading by mutableStateOf(0f)
        show { CompassBar(heading = heading, modifier = bar, live = false) }

        val ahead = centreOf("N")
        heading = 45f
        frame()

        // Half the field of view across the strip is half its width, so 45 of 180 is a quarter.
        assertEquals(ahead - 380f / 4f, centreOf("N"), 1f, "turning right moves north left")
        assertEquals(middle, centreOf("NE"), 0.5f, "and puts north east dead ahead")
    }

    @Test
    fun `a name wraps round at 360 rather than running off to one side`() {
        show { CompassBar(heading = 350f, modifier = bar, live = false) }

        // Facing 350, north is ten degrees to the right — not 350 degrees round to the left.
        assertEquals(middle + 380f * 10f / 180f, centreOf("N"), 1f, "north wraps the short way round")
    }

    @Test
    fun `the strip shows only the field of view it was given`() {
        show { CompassBar(heading = 0f, fieldOfView = 90f, modifier = bar, live = false) }

        checkNotNull(text("N")) { "north is straight ahead" }
        assertNull(text("E"), "east is 90 away, which is outside a 90 degree window")
        assertNull(text("SE"), "and so is south east")

        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                CompassBar(heading = 0f, fieldOfView = 360f, modifier = bar, live = false)
            }
        }
        repeat(3) { frame() }

        checkNotNull(text("E")) { "the whole circle laid flat has east on it" }
        checkNotNull(text("W")) { "and west" }
    }

    @Test
    fun `a compass can name four points or sixteen`() {
        show { CompassBar(heading = 0f, labels = CompassLabels.Cardinals, modifier = bar, live = false) }
        assertNull(text("NE"), "a four point compass has no north east")

        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                CompassBar(heading = 0f, labels = CompassLabels.EnglishSixteen, modifier = bar, live = false)
            }
        }
        repeat(3) { frame() }
        checkNotNull(text("NNE")) { "a sixteen point compass has north north east" }
    }

    @Test
    fun `there are ticks between the names`() {
        show { CompassBar(heading = 0f, modifier = bar, live = false) }
        val withTicks = ticks()

        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                CompassBar(heading = 0f, tickDegrees = 0f, modifier = bar, live = false)
            }
        }
        repeat(3) { frame() }

        assertTrue(withTicks > ticks(), "a strip that asked for no ticks between the names has none")
        assertTrue(ticks() > 0, "the tick under each name is drawn whatever the ticks between them do")
    }

    /** Every mark on the ruler: the thin rectangles in the tick colour. */
    private fun ticks() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
        .count { it.colour.argb and 0xFFFFFF == 0x6B7686 }

    // --- pins ------------------------------------------------------------------------------------

    @Test
    fun `a pin sits at the bearing it was given`() {
        show {
            CompassBar(heading = 0f, modifier = bar, live = false) { pin(bearing = 45f) }
        }

        assertEquals(middle + 380f / 4f, xOf(diamonds().first()), 1f, "north east is a quarter across")
    }

    @Test
    fun `a pin behind the player sticks to the end it left by`() {
        show {
            CompassBar(heading = 0f, fieldOfView = 90f, modifier = bar, live = false) {
                pin(bearing = 170f)
            }
        }

        val at = xOf(diamonds().first())
        assertTrue(at > 300f, "something behind and to the right is pinned at the right hand end: $at")
        assertTrue(at <= 390f, "and still on the strip")
        assertEquals(1, arrows().size, "with an arrow saying which way to turn")
        assertTrue(arrows().first().points.maxOf { it.x } > at, "pointing further right than the pin")
    }

    @Test
    fun `a pin behind the player on the left sticks to the left hand end`() {
        show {
            CompassBar(heading = 0f, fieldOfView = 90f, modifier = bar, live = false) {
                pin(bearing = 200f)
            }
        }

        val at = xOf(diamonds().first())
        assertTrue(at < 100f, "something behind and to the left is pinned at the left hand end: $at")
        assertTrue(arrows().first().points.minOf { it.x } < at, "with an arrow pointing further left")
    }

    @Test
    fun `a pin that does not want the end is simply not drawn`() {
        show {
            CompassBar(heading = 0f, fieldOfView = 90f, modifier = bar, live = false) {
                pin(bearing = 170f, clamp = false)
            }
        }

        assertTrue(diamonds().isEmpty(), "nothing should be drawn for it")
        assertTrue(arrows().isEmpty())
    }

    @Test
    fun `a pin is never left in the faint part at the end`() {
        show {
            CompassBar(heading = 0f, fieldOfView = 90f, fade = 40f, modifier = bar, live = false) {
                pin(bearing = 170f)
            }
        }

        // The strip's inside ends at 390 and fades over the last 40 pixels of it.
        assertTrue(xOf(diamonds().first()) <= 350f, "a clamped pin stops where the fade starts")
    }

    @Test
    fun `a pin fades with distance only when it asked to`() {
        show {
            CompassBar(heading = 0f, fadeRange = 100f, modifier = bar, live = false) {
                pin(bearing = -20f, distance = 90f, fadeWithDistance = true)
                pin(bearing = 20f, distance = 90f)
            }
        }

        val faded = diamonds().first { xOf(it) < middle }
        val solid = diamonds().first { xOf(it) > middle }
        assertTrue(faded.colour.alpha < solid.colour.alpha, "the far one should be fainter")
        assertEquals(0xFF, solid.colour.alpha, "and the one that said nothing untouched")
    }

    @Test
    fun `a distance is written under a pin and can be turned off`() {
        show {
            CompassBar(heading = 0f, modifier = bar, live = false) { pin(bearing = 0f, distance = 128.4f) }
        }
        checkNotNull(text("128")) { "the default rounds a distance to a whole unit" }

        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                CompassBar(heading = 0f, distanceText = null, modifier = bar, live = false) {
                    pin(bearing = 0f, distance = 128.4f)
                }
            }
        }
        repeat(3) { frame() }
        assertNull(text("128"), "a strip that wants no numbers on it has none")
    }

    @Test
    fun `a game writes a distance in its own units`() {
        show {
            CompassBar(heading = 0f, distanceText = { "${it.roundToInt()}m" }, modifier = bar, live = false) {
                pin(bearing = 0f, distance = 40f)
            }
        }

        checkNotNull(text("40m"))
    }

    @Test
    fun `a pin can wear a style of its own`() {
        show {
            CompassBar(heading = 0f, modifier = bar, live = false) {
                pin(bearing = 0f, style = "label.danger")
            }
        }

        assertEquals(
            Colour(0xFFE5484D.toInt()),
            diamonds().first().colour,
            "a pin with a style of its own should be drawn in it",
        )
    }

    // --- the ends, the middle and the readout ----------------------------------------------------

    @Test
    fun `the ends fade out`() {
        show { CompassBar(heading = 0f, fieldOfView = 200f, fade = 60f, modifier = bar, live = false) }

        val ahead = checkNotNull(text("N"))
        val leaving = checkNotNull(text("W")) { "west is nearly at the left hand end of a 200 degree window" }
        assertEquals(0xFF, ahead.colour.alpha, "a name in the middle is solid")
        assertTrue(leaving.colour.alpha < 0x80, "and one sliding off the end is going: ${leaving.colour}")
        assertTrue(leaving.colour.alpha > 0, "but not gone while it is still on the strip")
    }

    @Test
    fun `the heading readout is optional and takes the middle of the strip`() {
        show { CompassBar(heading = 0f, modifier = bar, live = false) }
        assertNull(text("0"), "a strip that was given no readout writes no number")
        checkNotNull(text("N")) { "and keeps the name in the middle" }

        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                CompassBar(heading = 0f, readout = { "${it.roundToInt()}" }, modifier = bar, live = false)
            }
        }
        repeat(3) { frame() }

        checkNotNull(text("0")) { "the readout should be written" }
        assertNull(text("N"), "and the name it would have been printed across left out")
    }

    @Test
    fun `the readout is the heading wrapped into a circle`() {
        show {
            CompassBar(heading = -90f, readout = { "${it.roundToInt()}" }, modifier = bar, live = false)
        }

        checkNotNull(text("270")) { "facing minus ninety is facing 270, not minus ninety" }
    }

    @Test
    fun `the middle of the strip is marked`() {
        show { CompassBar(heading = 0f, modifier = bar, live = false) }

        val marker = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
            .filter { it.colour.argb and 0xFFFFFF == 0x5B8DEF }
        assertEquals(1, marker.size, "one line down the middle")
        assertEquals(middle, (marker.first().rect.left + marker.first().rect.right) / 2f, 0.5f)
    }

    // --- language and direction ------------------------------------------------------------------

    @Test
    fun `the names are the player's own language`() {
        val strings = Strings(mapOf(Locale("ru") to mapOf("compass.n" to "И", "compass.e" to "В")))
        show {
            ProvideLocale(Locale("ru"), strings) {
                CompassBar(heading = 0f, modifier = bar, live = false)
            }
        }

        checkNotNull(text("И")) { "north should be written the way a Russian player writes it" }
        checkNotNull(text("NE")) { "and a point nobody translated keeps its English letters" }
    }

    @Test
    fun `the strip does not mirror in a right to left language`() {
        show {
            ProvideLayoutDirection(LayoutDirection.Ltr) {
                CompassBar(heading = 0f, fieldOfView = 200f, modifier = bar, live = false)
            }
        }
        val leftToRight = centreOf("E")

        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                ProvideLayoutDirection(LayoutDirection.Rtl) {
                    CompassBar(heading = 0f, fieldOfView = 200f, modifier = bar, live = false)
                }
            }
        }
        repeat(3) { frame() }

        // East is to the right of north wherever the player is from, so the strip must not mirror.
        assertEquals(leftToRight, centreOf("E"), 0.5f, "the strip mirrored, and would now turn backwards")
    }

    // --- what it costs -----------------------------------------------------------------------------

    @Test
    fun `a compass that is not live costs nothing`() {
        show { CompassBar(heading = 0f, modifier = bar, live = false) }

        repeat(20) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it redrew a compass nothing is happening on")
        }
    }

    @Test
    fun `a live compass is redrawn every frame`() {
        show { CompassBar(heading = 0f, modifier = bar, live = true) { pin(bearing = 0f) } }

        repeat(5) {
            wall += 16_000_000L
            assertTrue(host.frame(wall), "frame $it did not redraw a compass the game is moving under")
        }
    }

    /** The heading read inside a composable of its own, which is what the widget's docs ask for. */
    private val turning = mutableFloatStateOf(0f)

    @Composable
    private fun Strip() = CompassBar(heading = turning.value, modifier = bar, live = false)

    @Test
    fun `only the strip is composed again when the heading changes`() {
        var elsewhere = 0
        show {
            Box(Modifier.fillMaxSize()) {
                Strip()
                // Stands in for the rest of a HUD: a player turning round must not touch it.
                elsewhere++
                Button("FIRE", {}, Modifier.align(Alignment.BottomEnd))
            }
        }
        val before = elsewhere
        val north = centreOf("N")

        repeat(4) {
            turning.value += 10f
            frame()
        }

        assertTrue(centreOf("N") < north, "the strip did not move")
        assertEquals(before, elsewhere, "something else on the HUD was composed again for a turn")
    }

    // --- input: a readout must not behave like a control ------------------------------------------

    @Test
    fun `a click on the strip reaches what is underneath it`() {
        var pressed = 0
        show {
            Box(Modifier.fillMaxSize()) {
                Button("FIRE", { pressed++ }, Modifier.size(300f, 80f))
                CompassBar(heading = 0f, modifier = bar, live = false)
            }
        }

        click(Offset(middle, 30f))
        frame()

        assertEquals(1, pressed, "the strip swallowed a click meant for the button under it")
    }

    @Test
    fun `the strip takes no turn in the focus order`() {
        val presses = mutableListOf<String>()
        show {
            Box(Modifier.fillMaxSize()) {
                Button("ONE", { presses += "ONE" }, Modifier.offset(0f, 100f))
                CompassBar(heading = 0f, modifier = bar.offset(0f, 200f), live = false)
                Button("TWO", { presses += "TWO" }, Modifier.offset(0f, 300f))
            }
        }

        key(Key.Tab)
        frame()
        key(Key.Enter)
        frame()
        key(Key.Tab)
        frame()
        // A pad, because a player on a pad is the one who would find a strip that takes focus.
        padPress(GamepadButton.South)
        frame()

        // Two moves, two buttons pressed: a strip that took a turn would have swallowed one of them.
        assertEquals(2, presses.size, "focus stopped on the compass on its way past: $presses")
        assertEquals(setOf("ONE", "TWO"), presses.toSet(), "both buttons should have been reached: $presses")
    }

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, PointerButton.Primary))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, PointerButton.Primary))
    }

    private fun key(key: Key) {
        val down = KeyEvent(key, KeyEventType.Down)
        if (!router.onKey(down)) keys.onKey(down)
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) keys.onKey(up)
    }

    private fun padPress(button: GamepadButton) {
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
    }
}
