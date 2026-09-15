package dev.wildware.composegl.korge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.tint
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import korlibs.image.bitmap.Bitmap32
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Per-corner tabs, a gradient bar and a tinted slot on real composed screens, clicked, drawn through
 * KorGE and read back — the join between a modifier written in a screen and the canvas's pixels.
 */
class KorgeCanvasExtrasScreenTest {

    private val size = KorgeGl.size.toFloat()
    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)

    private fun backend() = KorgeBackend(
        KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(12, 13, 14, 16)) },
    )

    private fun frame(ui: UiTest, backend: KorgeBackend): Bitmap32 = KorgeGl.picture { ctx ->
        backend.canvas.renderContext = ctx
        try {
            ui.render()
        } finally {
            backend.canvas.renderContext = null
        }
    }

    private fun withScreen(content: @androidx.compose.runtime.Composable () -> Unit, block: (UiTest, KorgeBackend) -> Unit) {
        val backend = backend()
        val ui = uiTest(Size(size, size), backend) { content() }
        try {
            block(ui, backend)
        } finally {
            ui.close()
            backend.close()
        }
    }

    @Test
    fun `a clicked tab lights up rounded along its top and square along its bottom`() = withScreen({
        var chosen by remember { mutableStateOf(0) }
        Row(Modifier.offset(40f, 40f)) {
            repeat(3) { index ->
                Box(
                    Modifier.size(60f, 40f)
                        .background(if (chosen == index) green else red, Corners.top(16f))
                        .clickable { chosen = index }
                        .testTag("tab$index"),
                )
            }
        }
    }) { ui, backend ->
        assertColour(Red, frame(ui, backend).at(162, 77), "the third tab starts red")

        ui.click("tab2")

        val it = frame(ui, backend)
        assertColour(Green, it.at(190, 60), "the clicked tab is lit")
        assertColour(Black, it.at(162, 42), "its top-left is cut away")
        assertColour(Black, it.at(217, 42), "and its top-right")
        assertColour(Green, it.at(162, 77), "its bottom-left is square")
        assertColour(Green, it.at(217, 77), "and its bottom-right")
        assertColour(Red, it.at(42, 77), "the first tab went back to red, still square below")
        assertColour(Black, it.at(42, 42), "and still cut above")
    }

    @Test
    fun `a clicked gradient tab keeps its top corners cut and its bottom square`() = withScreen({
        var chosen by remember { mutableStateOf(0) }
        Row(Modifier.offset(40f, 40f)) {
            repeat(2) { index ->
                Box(
                    Modifier.size(60f, 40f)
                        .background(if (chosen == index) Brush.vertical(green, green) else Brush.vertical(red, red), Corners.top(16f))
                        .clickable { chosen = index }
                        .testTag("tab$index"),
                )
            }
        }
    }) { ui, backend ->
        ui.click("tab1")

        val it = frame(ui, backend)
        assertColour(Green, it.at(130, 60), "the clicked gradient tab is lit")
        assertColour(Black, it.at(102, 42), "its top-left is cut away")
        assertColour(Black, it.at(157, 42), "and its top-right")
        assertColour(Green, it.at(102, 77), "its bottom-left is square")
        assertColour(Green, it.at(157, 77), "and its bottom-right")
        assertColour(Red, it.at(42, 77), "the first tab is red again and square below")
        assertColour(Black, it.at(42, 42), "and cut above")
    }

    @Test
    fun `clicking damage shrinks a gradient health bar that stays red at its tip`() = withScreen({
        var health by remember { mutableStateOf(1f) }
        Column {
            Box(Modifier.testTag("bar").size(300f * health, 40f).background(Brush.horizontal(green, red)))
            Box(Modifier.testTag("hit").size(100f, 100f).clickable { health -= 0.5f }.background(Colour.White))
        }
    }) { ui, backend ->
        fun near(expected: Rgb, actual: Rgb) =
            abs(expected.r - actual.r) < 0.06f && abs(expected.g - actual.g) < 0.06f && abs(expected.b - actual.b) < 0.06f

        val before = frame(ui, backend)
        assertTrue(near(Red, before.at(296, 20)), "a full bar is red at its right end: ${before.at(296, 20)}")
        assertTrue(near(Green, before.at(3, 20)), "and green at its left: ${before.at(3, 20)}")
        val middle = before.at(200, 20)
        assertTrue(middle.r > 0.2f && middle.g > 0.2f, "and in between in the middle: $middle")

        ui.click("hit")

        val after = frame(ui, backend)
        assertTrue(abs(ui.node("bar").boundsInRoot.width - 150f) < 0.01f, "the click took half")
        assertTrue(near(Red, after.at(146, 20)), "the shorter bar is still red at its tip: ${after.at(146, 20)}")
        assertTrue(near(Green, after.at(3, 20)), "and still green at its root: ${after.at(3, 20)}")
        assertTrue(near(Black, after.at(200, 20)), "and nothing is drawn past it: ${after.at(200, 20)}")
    }

    @Test
    fun `clicking a slot locks it and the lock dims it on screen and nothing beside it`() = withScreen({
        var locked by remember { mutableStateOf(false) }
        Row(Modifier.padding(10f)) {
            LeafLayout(
                Modifier.size(60f, 60f)
                    .tint(if (locked) Colour.Grey else Colour.White)
                    .background(Colour.Orange)
                    .clickable { locked = !locked }
                    .testTag("slot"),
            )
            LeafLayout(Modifier.size(60f, 60f).background(Colour.Orange).testTag("neighbour"))
        }
    }) { ui, backend ->
        val orange = Rgb(1f, 0.5f, 0f)
        val slot = ui.node("slot").boundsInRoot.centre
        val beside = ui.node("neighbour").boundsInRoot.centre
        assertColour(orange, frame(ui, backend).at(slot.x.toInt(), slot.y.toInt()), "unlocked")

        ui.click("slot")

        val after = frame(ui, backend)
        assertColour(Rgb(0.5f, 0.25f, 0f), after.at(slot.x.toInt(), slot.y.toInt()), "locked, the orange is halved")
        assertColour(orange, after.at(beside.x.toInt(), beside.y.toInt()), "the slot next to it is untouched")
    }
}
