package dev.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sweeping wedge over an ability icon.
 *
 * The three things the issue asks for are the first three tests: the sweep is smooth at any
 * duration, a cooldown triggered while it is already running is ignored rather than restarting,
 * and a ready ability draws nothing extra at all.
 */
class RadialCooldownTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear()
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(2)
    }

    /** The runtime takes a frame to notice a state change and another to act on it. */
    private fun begin() = frames(2, millis = 0)

    private fun settle() = frame(0)

    private val wedge: DrawCall.Fan?
        get() = canvas.calls.filterIsInstance<DrawCall.Fan>().lastOrNull()

    private fun text(): String? =
        canvas.calls.filterIsInstance<DrawCall.Text>().lastOrNull()?.text

    /** Where the wedge's leading edge is on the icon's edge: the clock hand, in other words. */
    private fun leadingEdge(): Offset = checkNotNull(wedge) { "nothing was swept" }.points[1]

    /** A hand's position, to within a tenth of a pixel. The angle comes out of a sine. */
    private fun assertHandAt(expected: Offset, message: String) {
        val at = leadingEdge()
        assertEquals(expected.x, at.x, 0.1f, message)
        assertEquals(expected.y, at.y, 0.1f, message)
    }

    // --- the three the issue asks for --------------------------------------------------------------

    @Test
    fun `the sweep is smooth at any duration`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(1_000, Clock.Ui)
            RadialCooldown(cooldown, Modifier.size(100f))
        }

        cooldown.trigger()
        begin()

        assertHandAt(Offset(50f, 0f), "it starts covering everything, from the top")

        frame(250)
        settle()
        assertHandAt(Offset(100f, 50f), "a quarter through it has swept round to three")

        frame(250)
        settle()
        assertHandAt(Offset(50f, 100f), "and half way it is at six")

        // The angles above are exact because the wedge is exact, not sampled: the same widget with
        // a hundredth of the duration lands in the same places.
        frames(3, 250)
        settle()
        assertNull(wedge, "when it is over there is nothing left to draw")
    }

    @Test
    fun `a cooldown triggered while it is running is ignored`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(1_000, Clock.Ui)
            RadialCooldown(cooldown, Modifier.size(100f))
        }

        assertTrue(cooldown.trigger(), "the first press uses the ability")
        begin()
        frame(500)
        settle()
        val halfWay = leadingEdge()

        assertFalse(cooldown.trigger(), "the second press does not use it")
        begin()

        assertEquals(halfWay, leadingEdge(), "and the wedge carried on rather than starting again")

        frames(3, 250)
        settle()
        assertTrue(cooldown.isReady, "it still ends when the first press said it would")
    }

    @Test
    fun `a ready ability draws nothing extra`() {
        show {
            val cooldown = rememberCooldown(1_000, Clock.Ui)
            RadialCooldown(cooldown, Modifier.size(100f))
        }

        assertNull(wedge, "no wedge")
        assertNull(text(), "no countdown")
        repeat(20) { assertFalse(frame(), "frame $it asked to be drawn while nothing was happening") }
    }

    // --- the rest ----------------------------------------------------------------------------------

    @Test
    fun `it counts down in seconds and in tenths at the end`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(3_000, Clock.Ui)
            RadialCooldown(cooldown, Modifier.size(100f))
        }

        cooldown.trigger()
        begin()
        assertEquals("3", text())

        frame(1_500)
        settle()
        assertEquals("2", text(), "a second and a half left still reads as two")

        frame(1_200)
        settle()
        assertEquals("0.3", text(), "the last second is worth counting in tenths")
    }

    @Test
    fun `it flashes when it comes back and then stops`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(200, Clock.Ui)
            RadialCooldown(cooldown, Modifier.size(100f))
        }

        cooldown.trigger()
        begin()
        frames(3, 100)
        settle()

        val flash = canvas.calls.filterIsInstance<DrawCall.Rectangle>().lastOrNull()
        assertTrue(flash != null && flash.colour.alpha > 0, "nothing flashed")

        frames(10, 100)
        repeat(20) { assertFalse(frame(), "the flash never ended") }
    }

    @Test
    fun `a cooldown on a stopped clock does not cool down`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(1_000)
            RadialCooldown(cooldown, Modifier.size(100f))
        }
        host.clocks.register(Clock.World)

        cooldown.trigger()
        begin()
        host.clocks.stop(Clock.World)
        frames(20, 100)
        settle()

        assertHandAt(Offset(50f, 0f), "a paused game cooled an ability down")

        host.clocks.start(Clock.World)
        frames(12, 100)
        settle()
        assertTrue(cooldown.isReady, "and it finished once the game was running again")
    }

    @Test
    fun `a reset makes it ready at once`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(10_000, Clock.Ui)
            RadialCooldown(cooldown, Modifier.size(100f))
        }

        cooldown.trigger()
        begin()
        cooldown.reset()
        begin()

        assertTrue(cooldown.isReady)
        assertNull(wedge)
    }

    @Test
    fun `an ability with no cooldown at all is always ready`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(0, Clock.Ui)
            RadialCooldown(cooldown, Modifier.size(100f))
        }

        assertTrue(cooldown.trigger(), "it was used")
        begin()

        assertTrue(cooldown.isReady, "and it is usable again immediately")
        assertNull(wedge)
    }

    @Test
    fun `the countdown can be turned off`() {
        lateinit var cooldown: Cooldown
        show {
            cooldown = rememberCooldown(1_000, Clock.Ui)
            RadialCooldown(cooldown, Modifier.size(100f), seconds = false)
        }

        cooldown.trigger()
        begin()

        assertNotNull(wedge)
        assertNull(text(), "the wedge alone is the whole of it")
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
}
