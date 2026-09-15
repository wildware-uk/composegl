package dev.wildware.composegl.gdx.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.gdx.GdxBackend
import dev.wildware.composegl.gdx.GdxFonts
import dev.wildware.composegl.gdx.Gl
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.preview.PreviewFunction
import dev.wildware.composegl.ui.preview.Previews
import dev.wildware.composegl.ui.preview.uiTest
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.math.abs

/** A light that goes from red to green when it is clicked, on a dark ground. */
@Preview(width = 200, height = 120, name = "gdx-light", background = 0xFF202020)
@Composable
fun LightPreview() {
    var on by remember { mutableStateOf(false) }
    Box(
        Modifier.offset(20f, 20f).size(40f, 40f)
            .background(if (on) Colour.rgb(0x00FF00) else Colour.rgb(0xFF0000))
            .clickable { on = true }
            .testTag("light"),
    )
}

/** Widgets and text through the default skin, for the golden. */
@Preview(width = 220, height = 110, name = "gdx-menu", background = 0xFF101418)
@Composable
fun MenuPreview() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
        Column {
            Text("PAUSED")
            Button("RESUME", onClick = {})
        }
    }
}

/**
 * The same `@Preview` functions the renderer photographs, drawn by the LibGDX backend.
 *
 * The raw OpenGL backend is what `renderPreviews` uses to write files; these are the proof that a
 * preview is an ordinary screen any backend draws — found the same way, laid out at its own size,
 * and still clickable, with the click showing up in the pixels.
 */
class PreviewGlTest {

    private fun preview(name: String): PreviewFunction =
        Previews.of(Class.forName("dev.wildware.composegl.gdx.preview.PreviewGlTestKt")).single { it.name == name }

    private fun backend(): GdxBackend {
        val fonts = GdxFonts()
        fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(14, 16, 18))
        return GdxBackend(fonts)
    }

    /** One frame of [ui] into the window, and the preview's corner of it read back. */
    private fun frame(ui: UiTest): BufferedImage {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        val width = ui.size.width.toInt()
        val height = ui.size.height.toInt()
        val pixmap = Pixmap.createFromFrameBuffer(0, 0, width, height)
        try {
            // OpenGL hands back the bottom row first.
            return imageOf(width, height) { x, y -> pixmap.getPixel(x, height - 1 - y) ushr 8 }
        } finally {
            pixmap.dispose()
        }
    }

    private fun assertRgb(expected: Int, image: BufferedImage, x: Int, y: Int, because: String) {
        val actual = image.getRGB(x, y) and 0xFFFFFF
        val off = (0..2).maxOf { abs((expected shr (it * 8) and 0xFF) - (actual shr (it * 8) and 0xFF)) }
        assertTrue(off <= 6, "$because at ($x, $y): expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}")
    }

    @Test
    fun `a preview clicked on the gdx backend changes on the screen`() = Gl.render {
        val backend = backend()
        val ui = uiTest(preview("gdx-light"), backend)
        try {
            val before = frame(ui)
            assertEquals(200 to 120, before.width to before.height)
            assertRgb(0xFF0000, before, 40, 40, "the light before the click")
            assertRgb(0x202020, before, 150, 100, "the preview's own background")

            ui.click("light")

            assertRgb(0x00FF00, frame(ui), 40, 40, "the light after the click")
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `a preview with widgets and text matches its golden`() = Gl.render {
        val backend = backend()
        val ui = uiTest(preview("gdx-menu"), backend)
        try {
            Goldens.assertMatches("preview-menu", frame(ui))
        } finally {
            ui.close()
            backend.dispose()
        }
    }
}
