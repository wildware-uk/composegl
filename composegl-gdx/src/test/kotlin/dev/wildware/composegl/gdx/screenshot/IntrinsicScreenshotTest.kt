package dev.wildware.composegl.gdx.screenshot

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.gdx.GdxCanvas
import dev.wildware.composegl.gdx.GdxFonts
import dev.wildware.composegl.gdx.Gl
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.SceneSize
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * Intrinsic sizing, in pixels, through a real font: a menu whose bars all stop at the longest
 * label, and a divider exactly as tall as the row beside it.
 *
 * A composed screen rather than one of the shared scenes, because the point is what layout decided
 * with the glyph widths only a real font has — and a golden of it, because a bar one label too
 * short or a divider running to the bottom of the picture is obvious in a picture.
 */
class IntrinsicScreenshotTest {

    private val ink = Colour.rgb(0x12161D)
    private val bar = Colour.rgb(0x4CC2FF)
    private val paper = Colour.rgb(0xE6EDF5)
    private val rule = Colour.rgb(0xFF8A3D)
    private val body = TextStyle(family = "body", size = 16f)

    private val viewport = Viewport(
        design = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        physical = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        policy = ScalePolicy.Fit,
    )

    /** What was laid out, read on the GL thread, next to the picture it produced. */
    private class Shot(
        val image: BufferedImage,
        val menuWidth: Float,
        val quitLabelWidth: Float,
        val rowHeight: Float,
        val dividerX: Float,
        val rowBottom: Float,
    )

    private fun shoot(): Shot = Gl.render {
        val fonts = GdxFonts()
        fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16))
        val canvas = GdxCanvas(atlas = fonts.atlas)
        val host = UiHost()
        try {
            host.setContent {
                ProvideFonts(fonts) {
                    Box(Modifier.fillMaxSize().background(ink).padding(16f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(16f)) {
                            Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(6f)) {
                                listOf("PLAY", "OPTIONS", "QUIT").forEach { label ->
                                    Text(label, Modifier.fillMaxWidth().background(bar).padding(6f), textStyle = body, colour = ink)
                                }
                            }
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8f)) {
                                Text("HP", textStyle = body, colour = paper)
                                Box(Modifier.width(3f).fillMaxHeight().background(rule)) {}
                                Text("SHIELD\nHULL", textStyle = body, colour = paper)
                            }
                        }
                    }
                }
            }
            host.frame(0L)
            MeasurePass().run(host.root, Constraints.fixed(SceneSize.toFloat(), SceneSize.toFloat()))

            val stack = host.root.children.single().children.single()
            val menu = stack.children[0]
            val row = stack.children[1]
            val quitLabelWidth = fonts.measure("QUIT", body).size.width + 12f

            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            DrawPass(canvas).draw(host.root)
            canvas.end()

            val image = Pixmap.createFromFrameBuffer(0, 0, SceneSize, SceneSize).let { frame ->
                imageOf(SceneSize, SceneSize) { x, y -> frame.getPixel(x, SceneSize - 1 - y) ushr 8 }
                    .also { frame.dispose() }
            }
            val divider = row.children[1].boundsInRoot
            Shot(image, menu.width, quitLabelWidth, row.height, (divider.left + divider.right) / 2f, row.boundsInRoot.bottom)
        } finally {
            host.dispose()
            canvas.dispose()
            fonts.dispose()
        }
    }

    private fun BufferedImage.rgb(x: Int, y: Int) = getRGB(x, y) and 0xFFFFFF

    private fun assertColour(expected: Colour, actual: Int, what: String) {
        val want = expected.argb and 0xFFFFFF
        val close = listOf(16, 8, 0).all { shift -> kotlin.math.abs((want shr shift and 0xFF) - (actual shr shift and 0xFF)) <= 20 }
        assertTrue(close, "$what: expected #${want.toString(16)}, was #${actual.toString(16)}")
    }

    @Test
    fun `a menu sized to its longest label and a divider sized to its row`() {
        val shot = shoot()

        assertTrue(shot.quitLabelWidth + 8f < shot.menuWidth, "OPTIONS is wider than QUIT in a real font")

        // Row 20 is inside the first bar, which starts 16 down and is a line plus 12 of padding tall.
        val right = (16f + shot.menuWidth).toInt()
        assertColour(bar, shot.image.rgb(right - 2, 20), "the PLAY bar reaches the menu's right edge")
        assertColour(ink, shot.image.rgb(right + 3, 20), "and stops there rather than at the screen")

        // The divider is 3 wide, one gap after "HP", in a row that starts below the menu and is as
        // tall as the two-line cell beside it: coloured just inside the row's bottom, not below it.
        assertTrue(shot.rowHeight > 30f && shot.rowHeight < 60f, "two lines of text, was ${shot.rowHeight}")
        val x = shot.dividerX.toInt()
        val bottom = shot.rowBottom.toInt()
        assertColour(rule, shot.image.rgb(x, bottom - 2), "the divider reaches the bottom of the row")
        assertColour(ink, shot.image.rgb(x, bottom + 3), "and stops there rather than running down the screen")

        Goldens.assertMatches("intrinsic-sizing", shot.image)
    }
}
