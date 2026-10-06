package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A plain box's middle is drawn as a flat quad through the shader's picture path, and its edge as
 * shape quads through the distance field. Judged here by a real GPU: the picture must be the one
 * the single quad drew — the golden was drawn that way, before the box was split — and no seam may
 * show where the two kinds of quad meet.
 */
class PlainBoxGlTest {

    @BeforeEach
    fun requireGl() = assumeTrue(Gl.available, "no display; these tests need a real GL context")

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val ink = Colour.rgb(0x0B0E13)
    private val panel = Colour.rgb(0x3A6EA5)
    private val card = Colour.rgb(0xE8E2D4)
    private val edge = Colour.rgb(0xC0563B)

    /** A rounded panel on its own, and a card with an outline over it: the plain boxes screens are made of. */
    private val plain = Rect.of(20f, 20f, 360f, 160f)
    private val plainCorners = Corners.all(18f)
    private val outlined = Rect.of(20f, 210f, 360f, 170f)
    private val outlinedCorners = Corners(topLeft = 30f, topRight = 6f, bottomRight = 30f, bottomLeft = 0f)
    private val outline = 3f

    private fun scene(): IntArray = Gl.render {
        val canvas = GlCanvas(null)
        try {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.rect(Rect.of(0f, 0f, Gl.size.toFloat(), Gl.size.toFloat()), ink)
            canvas.rect(plain, panel, plainCorners)
            canvas.rect(outlined, card, outlinedCorners)
            canvas.border(outlined, edge, width = outline, corners = outlinedCorners)
            canvas.end()
            Gl.readPixels(Gl.size, Gl.size)
        } finally {
            canvas.close()
        }
    }

    /**
     * How far the middle of pixel [x], [y] is from [box]'s edge, negative inside: the shader's own
     * sum, with the radius of the corner whose quarter the pixel is in. Toolkit coordinates, y down.
     */
    private fun distance(box: Rect, corners: Corners, x: Int, y: Int): Float {
        val acrossX = x + 0.5f - (box.left + box.width / 2f)
        val acrossY = y + 0.5f - (box.top + box.height / 2f)
        val radius = when {
            acrossY < 0f -> if (acrossX < 0f) corners.topLeft else corners.topRight
            else -> if (acrossX < 0f) corners.bottomLeft else corners.bottomRight
        }.coerceIn(0f, min(box.width, box.height) / 2f)
        val qx = abs(acrossX) - box.width / 2f + radius
        val qy = abs(acrossY) - box.height / 2f + radius
        val outX = max(qx, 0f)
        val outY = max(qy, 0f)
        return min(max(qx, qy), 0f) + sqrt(outX * outX + outY * outY) - radius
    }

    @Test
    fun `a plain rounded box with and without a border matches its golden`() {
        val frame = scene()

        Goldens.assertMatches("plain-box", imageOf(Gl.size, Gl.size) { x, y -> frame[y * Gl.size + x] })
    }

    /**
     * What pixel [x], [y] must be to the last bit, or null where it is softened and any rasteriser
     * may round it its own way: one pixel either side of every edge. Past the soft edge a box is
     * its fill, the outline its colour, and outside every box by a pixel what is underneath.
     */
    private fun exactly(x: Int, y: Int): Colour? {
        val fromPanel = distance(plain, plainCorners, x, y)
        val fromCard = distance(outlined, outlinedCorners, x, y)
        val fromScreen = distance(Rect.of(0f, 0f, Gl.size.toFloat(), Gl.size.toFloat()), Corners.all(0f), x, y)
        return when {
            fromPanel <= -1f -> panel
            fromPanel < 1f -> null
            // The outline lies inside the card's edge: solid from one pixel in to one pixel short
            // of its inner edge, then softened into the card for a pixel either side of that.
            fromCard <= -1f - outline -> card
            fromCard < 1f - outline -> null
            fromCard <= -1f -> edge
            fromCard < 1f -> null
            fromScreen <= -1f -> ink
            else -> null
        }
    }

    @Test
    fun `every pixel clear of a soft edge is the colour it was before the box was split`() {
        val frame = scene()
        val wrong = ArrayList<String>()
        val checked = HashMap<Colour, Int>()
        for (y in 0 until Gl.size) {
            for (x in 0 until Gl.size) {
                val expected = exactly(x, y) ?: continue
                checked[expected] = (checked[expected] ?: 0) + 1
                val actual = frame[y * Gl.size + x]
                val want = expected.red shl 16 or (expected.green shl 8) or expected.blue
                if (actual != want) wrong += "($x, $y) is ${actual.toString(16)}, not ${want.toString(16)}"
            }
        }

        assertTrue((checked[panel] ?: 0) > 50_000 && (checked[card] ?: 0) > 50_000, "both middles were looked at: $checked")
        assertTrue((checked[edge] ?: 0) > 500, "and the outline: $checked")
        assertTrue((checked[ink] ?: 0) > 10_000, "and what is round them, corners included: $checked")
        assertEquals(emptyList<String>(), wrong.take(20), "${wrong.size} pixels are not what they were")
    }
}
