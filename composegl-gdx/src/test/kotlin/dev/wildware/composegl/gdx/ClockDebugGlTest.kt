package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.animation.ClockDebugKeys
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The debug pause on a real GPU: a frozen animation draws the same pixels frame after frame, and a
 * step moves what is on the screen.
 */
class ClockDebugGlTest {

    private val green = Colour.rgb(0x00FF00)

    /**
     * A 40px green square that slides 200px right over a linear second once it is clicked, with the
     * debug keys on it.
     */
    private fun slider(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        val clocks = LocalClocks.current
        val keys = remember(clocks) { ClockDebugKeys(clocks) }
        var go by remember { mutableStateOf(false) }
        val x by animateFloatAsState(if (go) 200f else 0f, Tween(1_000, easing = Easings.Linear))
        Box(
            Modifier.offset(100f + x, 100f).size(40f, 40f)
                .background(green)
                .focusable(initial = true)
                .onKeyEvent(keys)
                .clickable { go = true }
                .testTag("slider"),
        )
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun Pixmap.isGreen(x: Int, y: Int) = at(x, y).let { abs(it.g - 1f) < 0.02f && it.r < 0.02f }

    /** The left edge of the green square along row 120, or -1 when none is drawn. */
    private fun Pixmap.squareLeft(): Int = (0 until Gl.size).firstOrNull { isGreen(it, 120) } ?: -1

    @Test
    fun `a paused slide draws the same pixels however long it is left and a step moves them`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = slider(backend)
        try {
            ui.key(Key.F5)
            ui.click("slider")
            ui.advanceBy(2_000)

            val frozen = frame(ui).use { it.squareLeft() }
            // Within a pixel: where GL puts an edge that lands exactly on a pixel boundary is its own business.
            assertTrue(abs(frozen - 100) <= 1, "two seconds on a frozen clock left the square where it started: $frozen")

            ui.advanceBy(500)
            val stillFrozen = frame(ui).use { it.squareLeft() }
            assertTrue(stillFrozen == frozen, "still frozen, still drawn at $frozen: $stillFrozen")

            repeat(15) { ui.key(Key.F6) }
            // Fifteen frames of a linear second across 200px is 50px.
            val stepped = frame(ui).use { it.squareLeft() }
            assertTrue(abs(stepped - frozen - 50) <= 1, "fifteen steps drew the square 50px on from $frozen: $stepped")

            ui.key(Key.F5)
            ui.settle()
            val landed = frame(ui).use { it.squareLeft() }
            assertTrue(landed - frozen == 200, "let go, it lands 200px on from $frozen: $landed")
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    private inline fun <T> Pixmap.use(block: (Pixmap) -> T): T = try {
        block(this)
    } finally {
        dispose()
    }
}
