package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A mirror in the pixels, through LibGDX: the picture really is read from the other side, and it
 * lands in the rectangle it was asked for.
 *
 * The art is a two-colour strip — red on its left, blue on its right — because a flat colour
 * cannot tell you it came out the wrong way round.
 */
class GdxMirrorTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    private fun draw(content: GdxCanvas.() -> Unit): Pixmap = Gl.render {
        val canvas = GdxCanvas()
        try {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.content()
            canvas.end()
            Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
        } finally {
            canvas.dispose()
        }
    }

    /** The colour at a point with y counted down from the top, like the toolkit's. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = kotlin.math.abs(expected.r - actual.r) < 0.02f &&
            kotlin.math.abs(expected.g - actual.g) < 0.02f &&
            kotlin.math.abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because expected about $expected, got $actual")
    }

    /** Red on the left half of [bounds], blue on the right, then [put] down however the test says. */
    private fun GdxCanvas.strip(bounds: Rect, put: GdxCanvas.(picture: dev.wildware.composegl.ui.graphics.TextureHandle) -> Unit) {
        val picture = layer(bounds) {
            rect(Rect(bounds.left, bounds.top, bounds.centre.x, bounds.bottom), red)
            rect(Rect(bounds.centre.x, bounds.top, bounds.right, bounds.bottom), blue)
        }
        put(checkNotNull(picture) { "this driver gave us no layer" })
    }

    @Test
    fun `a mirrored layer swaps its left and right`() {
        val bounds = Rect.of(40f, 40f, 120f, 80f)
        val pixels = draw { strip(bounds) { drawLayer(it, bounds, mirrorX = true, mirrorY = false) } }

        assertColour(Color.BLUE, pixels.at(60, 80), "the picture's right half is drawn on the left:")
        assertColour(Color.RED, pixels.at(140, 80), "and its left half on the right:")
        assertColour(Color.BLACK, pixels.at(30, 80), "nothing spills past the rectangle's left:")
        assertColour(Color.BLACK, pixels.at(170, 80), "or its right:")
    }

    @Test
    fun `a layer mirrored neither way is the plain composite`() {
        val bounds = Rect.of(40f, 40f, 120f, 80f)
        val pixels = draw { strip(bounds) { drawLayer(it, bounds, mirrorX = false, mirrorY = false) } }

        assertColour(Color.RED, pixels.at(60, 80), "left is left:")
        assertColour(Color.BLUE, pixels.at(140, 80), "right is right:")
    }

    @Test
    fun `a vertically mirrored layer swaps its top and bottom`() {
        val bounds = Rect.of(40f, 40f, 80f, 120f)
        val pixels = draw {
            val picture = layer(bounds) {
                rect(Rect(40f, 40f, 120f, 100f), red)
                rect(Rect(40f, 100f, 120f, 160f), blue)
            }
            drawLayer(checkNotNull(picture), bounds, mirrorX = false, mirrorY = true)
        }

        assertColour(Color.BLUE, pixels.at(80, 60), "the picture's bottom half is drawn on top:")
        assertColour(Color.RED, pixels.at(80, 140), "and its top half underneath:")
    }

    @Test
    fun `a mirrored node in a tree draws its art facing the other way`() {
        // The whole path: a modifier, the draw pass, and this canvas's composite. A sprite laid
        // out with its red face on the left is drawn with it on the right.
        val screen = TestTree()
        val sprite = screen.box("sprite", 20f, 20f, 160f, 60f, Modifier.mirror())
        screen.box("face", 0f, 0f, 40f, 60f, Modifier.background(red), parent = sprite)
        screen.box("tail", 40f, 0f, 120f, 60f, Modifier.background(blue), parent = sprite)

        val pixels = draw { DrawPass(this).draw(screen.root) }

        assertColour(Color.RED, pixels.at(160, 50), "the face, laid out on the left, is on the right:")
        assertColour(Color.BLUE, pixels.at(40, 50), "and the tail on the left:")
        assertColour(Color.RED, pixels.at(screen.root.firstOrNull { it.name == "face" }!!.boundsInRoot.centre.x.toInt(), 50),
            "where the face says it is drawn is where its pixels are:")
    }

    @Test
    fun `a mirrored and scaled node flips and shrinks in one picture`() {
        val screen = TestTree()
        val sprite = screen.box("sprite", 0f, 0f, 200f, 100f, Modifier.mirror().scale(0.5f))
        screen.box("face", 0f, 0f, 100f, 100f, Modifier.background(red), parent = sprite)
        screen.box("tail", 100f, 0f, 100f, 100f, Modifier.background(blue), parent = sprite)

        val pixels = draw { DrawPass(this).draw(screen.root) }

        // Drawn 50..150 across, 25..75 down: the tail in the left half, the face in the right.
        assertColour(Color.BLUE, pixels.at(75, 50), "the tail:")
        assertColour(Color.RED, pixels.at(125, 50), "the face:")
        assertColour(Color.BLACK, pixels.at(40, 50), "and nothing outside the shrunk rectangle:")
    }
}
