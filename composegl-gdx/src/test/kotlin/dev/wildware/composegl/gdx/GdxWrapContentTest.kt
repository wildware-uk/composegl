package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.wrapContentWidth
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `wrapContentWidth` in pixels: a composed row drawn by the real renderer, so what the layout tests
 * say about rectangles is checked against what actually came out on the screen.
 */
class GdxWrapContentTest {

    private val host = UiHost()
    private var clock = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val grey = Color(0.5f, 0.5f, 0.5f, 1f)

    /** Settles the composition, then draws the whole tree on the GL thread and reads it back. */
    private fun frame(): Pixmap {
        for (turn in 0 until 8) {
            clock += 16_666_667L
            if (!host.settle(Constraints.atMost(Gl.size.toFloat(), Gl.size.toFloat()), nanos = clock)) break
        }
        return Gl.render {
            val batch = SpriteBatch()
            val canvas = GdxCanvas(batch)
            try {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                DrawPass(canvas).draw(host.root)
                canvas.end()
                Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            } finally {
                canvas.dispose()
                batch.dispose()
            }
        }
    }

    /** The colour at a point with y down from the top; see GdxCanvasTest. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = kotlin.math.abs(expected.r - actual.r) < 0.02f &&
            kotlin.math.abs(expected.g - actual.g) < 0.02f &&
            kotlin.math.abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    @Test
    fun `a wrapped badge is drawn its own width inside a grey slot and moves when told`() {
        var alignment by mutableStateOf(HorizontalAlignment.Centre)
        host.setContent {
            // The row paints its middle third grey, so grey is the slot and red is the node. Without
            // wrapContentWidth the red would cover the grey from one side of the third to the other.
            Row(
                Modifier.width(300f).height(60f).drawBehind { bounds ->
                    rect(Rect.of(bounds.left + 100f, bounds.top, 100f, 24f), Colour.Grey)
                },
            ) {
                Box(Modifier.weight(1f)) {}
                Box(Modifier.weight(1f).wrapContentWidth(alignment).size(24f).background(Colour.Red)) {}
                Box(Modifier.weight(1f)) {}
            }
        }

        val centred = frame()
        try {
            assertColour(Color.RED, centred.at(150, 12), "the badge is in the middle of its third")
            assertColour(Color.RED, centred.at(140, 4), "just inside the badge's left edge")
            assertColour(grey, centred.at(105, 12), "the start of the slot is the slot, not the badge")
            assertColour(grey, centred.at(134, 12), "four pixels left of the badge is still the slot")
            assertColour(grey, centred.at(166, 12), "and four pixels right of it")
            assertColour(Color.BLACK, centred.at(250, 12), "the next third is empty")
        } finally {
            centred.dispose()
        }

        alignment = HorizontalAlignment.End
        val moved = frame()
        try {
            assertColour(Color.RED, moved.at(195, 12), "the badge now sits at the end of its slot")
            assertColour(grey, moved.at(150, 12), "and the middle it left is the slot again")
            assertColour(grey, moved.at(170, 12), "six pixels short of it is still the slot")
        } finally {
            moved.dispose()
        }
    }
}
