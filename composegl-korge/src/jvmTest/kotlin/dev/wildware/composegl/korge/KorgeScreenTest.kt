package dev.wildware.composegl.korge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Text
import korlibs.event.MouseButton
import korlibs.event.MouseEvent
import korlibs.image.bitmap.Bitmap32
import korlibs.image.format.PNG
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Whole screens, composed for real, drawn through KorGE and driven with real pointer input — the proof
 * that the toolkit's frame, its router and this canvas meet, and not only that each works alone.
 */
class KorgeScreenTest {

    private val size = KorgeGl.size.toFloat()

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)

    /**
     * Every test here dispatches an event and then looks at a later frame. On a busy machine the
     * harness once ran the next piece of work in the same frame as the last, so the picture after a
     * click was the one drawn before it.
     */
    @Test
    fun `each piece of work posted to the game runs in a later frame than the one before`() {
        repeat(200) {
            val first = KorgeGl.render { KorgeGl.drawn }
            val second = KorgeGl.render { KorgeGl.drawn }
            KorgeGl.frames(2)
            val third = KorgeGl.render { KorgeGl.drawn }
            assertTrue(second > first, "back-to-back work shared frame $first")
            assertTrue(third - second >= 3, "frames(2) and one more should be three frames, it was ${third - second}")
        }
    }

    /** Every size the default skin asks for, under the name it asks for. */
    private fun backend() = KorgeBackend(
        KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(12, 13, 14, 16, 18, 22, 26)) },
    )

    /** Draws one frame of [ui] through the backend's canvas into an offscreen picture, and reads it back. */
    private fun frame(ui: UiTest, backend: KorgeBackend): Bitmap32 = KorgeGl.picture { ctx ->
        backend.canvas.renderContext = ctx
        try {
            ui.render()
        } finally {
            backend.canvas.renderContext = null
        }
    }

    @Test
    fun `a click sent by the harness turns the square green in the pixels`() {
        val backend = backend()
        val ui = uiTest(Size(size, size), backend) {
            var on by remember { mutableStateOf(false) }
            Box(
                Modifier.offset(100f, 100f).size(100f, 100f)
                    .background(if (on) green else red)
                    .focusable(initial = true)
                    .clickable { on = true }
                    .testTag("switch"),
            )
        }
        try {
            frame(ui, backend).let {
                assertColour(Red, it.at(150, 150), "before the click")
                assertColour(Black, it.at(50, 50), "nothing drawn outside the square")
            }

            ui.click("switch")

            frame(ui, backend).let {
                assertColour(Green, it.at(150, 150), "after the click")
                assertColour(Black, it.at(50, 50), "still nothing outside the square")
            }
        } finally {
            ui.close()
            backend.close()
        }
    }

    @Test
    fun `a real button's label changes in the pixels when it is clicked`() {
        val backend = backend()
        val ui = uiTest(Size(size, size), backend) {
            var clicks by remember { mutableStateOf(0) }
            Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                Text("Clicked $clicks times", Modifier.testTag("count"))
                Button("CLICK ME", onClick = { clicks++ }, modifier = Modifier.testTag("button"))
            }
        }
        try {
            val count = ui.node("count").bounds
            fun label(pixels: Bitmap32): List<Int> {
                val rows = count.top.toInt() until count.bottom.toInt()
                val columns = count.left.toInt() until count.right.toInt()
                return rows.flatMap { y -> columns.map { x -> (pixels.at(x, y).r * 255).toInt() } }
            }
            val before = label(frame(ui, backend))
            assertTrue(before.count { it > 128 } > 20, "the label is drawn in lit pixels before the click")

            ui.click("button")

            val after = label(frame(ui, backend))
            assertTrue(before != after, "the label's pixels changed after the click")
            ui.assertText("count", "Clicked 1 times")
        } finally {
            ui.close()
            backend.close()
        }
    }

    @Test
    fun `a KorGE mouse click on the stage reaches a ComposeGL view and changes the window`() {
        val backend = backend()
        val view = ComposeGlView(backend, Size(size, size))
        view.setContent {
            var on by remember { mutableStateOf(false) }
            Box(Modifier.offset(100f, 100f).size(100f, 100f).background(if (on) green else red).clickable { on = true })
        }
        try {
            KorgeGl.render { KorgeGl.stage.addChild(view) }
            KorgeGl.frames(3)
            val before = KorgeGl.window()
            assertColour(Red, before.at(150, 150), "the view draws into the window where the design said")
            assertColour(Black, before.at(50, 50), "and nothing outside its square")

            // Real KorGE events, dispatched the way the window dispatches them, in window pixels.
            KorgeGl.render {
                KorgeGl.stage.views.dispatch(MouseEvent(type = MouseEvent.Type.MOVE, x = 150, y = 150))
                KorgeGl.stage.views.dispatch(MouseEvent(type = MouseEvent.Type.DOWN, x = 150, y = 150, button = MouseButton.LEFT))
                KorgeGl.stage.views.dispatch(MouseEvent(type = MouseEvent.Type.UP, x = 150, y = 150, button = MouseButton.LEFT))
            }
            KorgeGl.frames(3)

            assertColour(Green, KorgeGl.window().at(150, 150), "the click turned it green")
        } finally {
            KorgeGl.render { view.removeFromParent() }
            view.close()
            backend.close()
        }
    }

    @Test
    fun `a small screen of a panel, a label and a button is drawn through KorGE`() {
        val backend = backend()
        val view = ComposeGlView(backend, Size(size, size))
        view.setContent {
            var clicks by remember { mutableStateOf(1) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
                Panel(Modifier.width(280f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                        Text("HELLO FROM KORGE")
                        Text("Clicked $clicks times")
                        Button("CLICK ME", onClick = { clicks++ })
                    }
                }
            }
        }
        try {
            KorgeGl.render { KorgeGl.stage.addChild(view) }
            KorgeGl.frames(4)
            val shot = KorgeGl.window()

            val lit = (0 until KorgeGl.size).sumOf { y -> (0 until KorgeGl.size).count { x -> shot.at(x, y).let { it.r + it.g + it.b } > 0.3f } }
            assertTrue(lit > 2_000, "a panel should light a good part of the window, lit $lit pixels")
            assertColour(Black, shot.at(5, 5), "the corners of the window are outside the centred panel")

            val bytes = PNG.encode(shot)
            val out = File("build/screenshots/korge-foundation.png").also { it.parentFile.mkdirs() }
            out.writeBytes(bytes)
            System.getenv("COMPOSEGL_KORGE_SHOTS")?.let { dir ->
                File(dir, "korge-foundation.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
            }
        } finally {
            KorgeGl.render { view.removeFromParent() }
            view.close()
            backend.close()
        }
    }
}
