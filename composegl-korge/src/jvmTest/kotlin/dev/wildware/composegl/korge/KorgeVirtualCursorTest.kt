package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.VirtualCursor
import korlibs.event.GameButton
import korlibs.event.GamePadUpdateEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `VirtualCursor` on a real KorGE stage: KorGE pad snapshots push the stick, and the arrow moves across
 * the window, turns gold over a button, snaps to it when the stick is let go, and South clicks it.
 *
 * Nothing here calls the cursor or the routers directly. Every event is a KorGE `GamePadUpdateEvent`
 * dispatched into the stage, the way KorGE's own pad poller sends them, and the answers are read back
 * from the cursor's state and from the window's pixels.
 */
class KorgeVirtualCursorTest {

    private val side = KorgeGl.size

    private val grey = Colour.rgb(0x808080)
    private val blue = Colour.rgb(0x2050C0)

    /** The button: 250 to 350 across, 180 to 220 down. The cursor starts in the middle, left of it. */
    private val button = Offset(300f, 200f)

    @Volatile
    private var clicks = 0

    /** One frame's snapshot of KorGE pad 0: the left stick at [x], [y] (KorGE's +1 is up), [held] down. */
    private fun pad(x: Float = 0f, y: Float = 0f, vararg held: GameButton) = GamePadUpdateEvent().apply {
        gamepadsLength = 1
        gamepads[0].connected = true
        gamepads[0].index = 0
        gamepads[0].rawButtons[GameButton.LX.index] = x
        gamepads[0].rawButtons[GameButton.LY.index] = y
        held.forEach { gamepads[0].rawButtons[it.index] = 1f }
    }

    /** The snapshot sent before every render while the test runs. */
    @Volatile
    private var held: GamePadUpdateEvent? = null

    /**
     * Holds [snapshot] until [check] holds, or fails saying [what].
     *
     * Sent every frame, just before the stage renders, the way a pad poller sends them. Under Xvfb KorGE's
     * own window polls for real pads at the start of each frame and reports none, which reads as our pad
     * leaving; sending ours after that poll and before the render is what a plugged-in pad looks like.
     */
    private fun holdUntil(view: ComposeGlView, what: String, snapshot: GamePadUpdateEvent, frames: Int = 240, check: (ComposeGlView) -> Boolean) {
        held = snapshot
        repeat(frames) {
            if (KorgeGl.render { check(view) }) return
        }
        fail<Unit>("$what, after $frames frames; the cursor is at ${KorgeGl.render { view.cursor.position }}")
    }

    private fun assertNear(expected: Colour, actual: Rgb, because: String) {
        val close = abs(expected.red / 255f - actual.r) < 0.06f &&
            abs(expected.green / 255f - actual.g) < 0.06f &&
            abs(expected.blue / 255f - actual.b) < 0.06f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    @Test
    fun `the stick moves the arrow across a KorGE window, it snaps to a button, and South clicks it`() {
        val backend = KorgeBackend(
            KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(12, 13, 14, 16, 18, 22, 26)) },
        )
        val view = ComposeGlView(backend, Size(side.toFloat(), side.toFloat()))
        view.setContent {
            VirtualCursor(enabled = true) {
                Box(Modifier.size(side.toFloat(), side.toFloat()).background(grey)) {
                    Box(Modifier.offset(250f, 180f).size(100f, 40f).background(blue).clickable { clicks++ })
                }
            }
        }
        var sending: AutoCloseable? = null
        try {
            KorgeGl.render {
                sending = KorgeGl.stage.views.onBeforeRender { held?.let { KorgeGl.stage.views.dispatch(it) } }
                KorgeGl.stage.addChild(view)
            }
            KorgeGl.frames(3)
            assertTrue(KorgeGl.render { view.cursor.enabled }, "VirtualCursor switched the view's pad cursor on")

            // A button the cursor does not use makes the pad the thing in use; the arrow appears in the middle.
            holdUntil(view, "North made the pad the thing in use", pad(0f, 0f, GameButton.BUTTON_NORTH)) { it.source.current == InputSource.Gamepad }
            holdUntil(view, "North let go", pad()) { true }
            KorgeGl.frames(3)
            val start = KorgeGl.render { view.cursor.position }
            assertEquals(Offset(200f, 200f), start, "the cursor starts in the middle of the screen")
            KorgeGl.window().let { pixels ->
                assertNear(Colour.White, pixels.at(start.x.toInt() + 2, start.y.toInt() + 10), "the arrow's body, just below its tip")
                assertNear(Colour.Black, pixels.at(start.x.toInt() - 1, start.y.toInt() + 10), "its dark edge")
                assertNear(grey, pixels.at(start.x.toInt() - 40, start.y.toInt() - 40), "and the map untouched elsewhere")
            }

            // Push right until the arrow is over the button.
            holdUntil(view, "the stick pushed right carried the cursor onto the button", pad(x = 0.8f)) { it.cursor.overTarget }
            val over = KorgeGl.render { view.cursor.position }
            assertTrue(over.x > start.x + 40f, "it moved right, from $start to $over")
            assertTrue(abs(over.y - start.y) < 2f, "and not up or down: $over")

            // Let go: it glides to the middle of the button, and the arrow there is gold.
            holdUntil(view, "the let-go cursor snapped to the button's middle", pad()) {
                abs(it.cursor.position.x - button.x) < 1.5f && abs(it.cursor.position.y - button.y) < 1.5f
            }
            KorgeGl.frames(3)
            val snapped = KorgeGl.render { view.cursor.position }
            KorgeGl.window().let { pixels ->
                assertNear(Colour.rgb(0xFFD54A), pixels.at(snapped.x.toInt() + 2, snapped.y.toInt() + 10), "gold over the button")
                assertNear(grey, pixels.at(start.x.toInt() + 2, start.y.toInt() + 10), "and gone from where it started")
            }

            // South down presses through the cursor; up clicks.
            assertEquals(0, clicks)
            holdUntil(view, "South held down the cursor's button", pad(0f, 0f, GameButton.BUTTON_SOUTH)) { it.cursor.pressed }
            assertEquals(0, clicks, "a press is not a click until it is let go")
            holdUntil(view, "South let go clicked the button", pad()) { !it.cursor.pressed && clicks == 1 }
            assertFalse(KorgeGl.render { view.cursor.pressed })
            assertEquals(InputSource.Gamepad, KorgeGl.render { view.source.current }, "the cursor did not make the mouse the thing in use")
        } finally {
            KorgeGl.render {
                sending?.close()
                // A last empty snapshot, so no pad is left connected for the next test.
                KorgeGl.stage.views.dispatch(GamePadUpdateEvent())
                view.removeFromParent()
            }
            held = null
            view.close()
            backend.close()
        }
    }
}
