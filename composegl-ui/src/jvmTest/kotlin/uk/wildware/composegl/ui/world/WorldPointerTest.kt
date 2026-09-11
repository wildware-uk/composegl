package uk.wildware.composegl.ui.world

import androidx.compose.runtime.Composable
import uk.wildware.composegl.ui.backend.MonospaceFontProvider
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.focus.FocusManager
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.input.InteractionState
import uk.wildware.composegl.ui.input.PointerRouter
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.interaction
import uk.wildware.composegl.ui.modifier.size
import uk.wildware.composegl.ui.widget.Button
import uk.wildware.composegl.ui.widget.ProvideFonts
import uk.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A player pointing at an interface that is in the world.
 *
 * The issue's one: a press, a drag and a release on a panel behave as they do on the HUD — so
 * every test here is written the way the same test would be written for a mouse, and the only
 * difference is that the coordinates come from a raycast.
 *
 * The one it warns about has a test of its own: a release belongs to the panel that got the press,
 * never to "the toolkit did something with the press".
 */
class WorldPointerTest {

    private val panel = WorldPanel(200f, 120f)
    private val canvas = RecordingCanvas(Rect(0f, 0f, 200f, 120f))
    private val focus = FocusManager(panel.root)
    private val router = PointerRouter(panel.root, focus)
    private val pointer = WorldPointer(router)

    private var wall = 0L
    private var clicks = 0
    private val touch = InteractionState()

    @AfterEach
    fun tearDown() = panel.close()

    private fun frame() {
        wall += 16_000_000L
        panel.needsRedraw(wall)
        canvas.clear(Rect(0f, 0f, 200f, 120f))
        MeasurePass().run(panel.root, Constraints.fixed(200f, 120f))
        focus.refresh()
        DrawPass(canvas).draw(panel.root)
        canvas.assertBalanced()
    }

    private fun show(content: @Composable () -> Unit) {
        panel.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        repeat(2) { frame() }
    }

    /** A button in the top left of the panel, roughly forty by twenty. */
    private fun button() = show {
        Box(Modifier.size(200f, 120f)) {
            Button(onClick = { clicks++ }, interaction = touch) { Text("GO") }
        }
    }

    // --- the issue's one -----------------------------------------------------------------------

    @Test
    fun `a press a drag and a release on a panel click a button`() {
        button()

        pointer.aim(20f, 12f)
        pointer.press()
        frame()
        assertTrue(touch.isPressed, "the button should be held")

        // A hand that wobbles, which is every hand.
        pointer.aim(22f, 14f)
        pointer.aim(19f, 11f)
        pointer.release()
        frame()

        assertEquals(1, clicks)
        assertFalse(touch.isPressed)
    }

    @Test
    fun `a release belongs to the panel that got the press and not to the toolkit`() {
        button()

        // On the background: nothing under it, so nothing does anything with it.
        pointer.aim(180f, 100f)
        assertFalse(pointer.press(), "nothing was under that, and the press says so")
        assertTrue(pointer.hasPress, "but the panel still has the press")

        pointer.release()
        assertFalse(pointer.hasPress, "the release has to end the gesture whatever the press said")

        // And the panel is not dead: this is the bug the issue names.
        pointer.aim(20f, 12f)
        pointer.press()
        pointer.release()
        frame()

        assertEquals(1, clicks, "the panel stopped working after a press nobody wanted")
    }

    @Test
    fun `a press survives the panel moving under the ray`() {
        button()

        // A panel on the side of a moving vehicle. The ray is still, the quad slides past it, and
        // the game works out where on the panel the ray now lands: this is the whole of what
        // "moving panel" means to the toolkit, and it is a drag.
        var quad = 0f
        val ray = 20f
        fun look() = pointer.aim(ray - quad, 12f)

        look()
        pointer.press()
        frame()
        assertTrue(touch.isPressed)

        // It slides out from under the ray...
        quad = 120f
        look()
        frame()
        assertFalse(touch.isPressed, "the button moved out from under the finger")

        // ...and back again, and letting go is still a click, exactly as on the HUD.
        quad = 0f
        look()
        pointer.release()
        frame()

        assertEquals(1, clicks)
    }

    // --- the rest of it ------------------------------------------------------------------------

    @Test
    fun `dragging off the panel and letting go does not click`() {
        button()

        pointer.aim(20f, 12f)
        pointer.press()
        frame()

        // Off the edge of the button, still on the panel's plane.
        pointer.aim(150f, 110f)
        frame()
        assertFalse(touch.isPressed, "a finger dragged off a button is not pressing it")

        pointer.release()
        frame()
        assertEquals(0, clicks, "letting go somewhere else is how a press is taken back")
    }

    @Test
    fun `dragging off and back again still clicks`() {
        button()

        pointer.aim(20f, 12f)
        pointer.press()
        pointer.aim(150f, 110f)
        frame()
        pointer.aim(20f, 12f)
        frame()
        assertTrue(touch.isPressed, "coming back is pressing it again")

        pointer.release()
        frame()
        assertEquals(1, clicks)
    }

    @Test
    fun `looking away holds the press and ends the hover`() {
        button()

        pointer.aim(20f, 12f)
        frame()
        assertTrue(touch.isHovered)

        pointer.press()
        pointer.away()
        frame()

        assertTrue(pointer.hasPress, "looking away does not abandon a gesture")

        // Coming back and letting go on the button is still a click.
        pointer.aim(20f, 12f)
        pointer.release()
        frame()
        assertEquals(1, clicks)
    }

    @Test
    fun `the ray leaving takes the hover away`() {
        button()

        pointer.aim(20f, 12f)
        frame()
        assertTrue(touch.isHovered)

        pointer.away()
        frame()

        assertFalse(touch.isHovered, "a player who looked away is not hovering anything")
    }

    @Test
    fun `pressing while looking at the wall presses nothing`() {
        button()

        assertFalse(pointer.press(), "there is nothing to press")
        assertFalse(pointer.hasPress)

        pointer.release()
        frame()
        assertEquals(0, clicks)
    }

    @Test
    fun `cancelling ends the gesture without a click`() {
        button()

        pointer.aim(20f, 12f)
        pointer.press()
        frame()

        pointer.cancel()
        frame()

        assertFalse(pointer.hasPress)
        assertFalse(touch.isPressed, "a cancelled gesture has to let go of the button")
        assertEquals(0, clicks, "and must not fire a click")
    }
}
