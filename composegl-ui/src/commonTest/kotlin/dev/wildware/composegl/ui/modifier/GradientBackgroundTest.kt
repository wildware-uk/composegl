package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A gradient behind a widget, in composed UI, driven the way a player drives it.
 *
 * Each test composes a real screen with `uiTest`, pushes real input at it — the pointer, the
 * keyboard, the pad, frames on a clock — and asserts what the frame that follows actually drew.
 * What a pixel of that gradient comes out as is the GPU tests' question; which box it was asked
 * for, in which colours, is this file's.
 */
class GradientBackgroundTest {

    private val green = Colour.rgb(0x4CD964)
    private val red = Colour.rgb(0xFF3B30)
    private val health = Brush.horizontal(green, red)

    /** One whole frame through the renderer, into a recording cleared for it. */
    private fun UiTest.frame(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear(Rect.of(0f, 0f, size.width, size.height))
        render()
        return canvas
    }

    private fun UiTest.gradients() = frame().only<DrawCall.GradientRectangle>()

    @Test
    fun `a gradient background is drawn across the node with its corner`() = uiTest {
        Box(Modifier.testTag("bar").size(120f, 40f).background(health, corner = 6f))
    }.use { ui ->
        val canvas = ui.frame()

        val drawn = canvas.only<DrawCall.GradientRectangle>().single()
        assertEquals(ui.node("bar").boundsInRoot, drawn.rect, "exactly the node's rectangle")
        assertEquals(health, drawn.brush)
        assertEquals(6f, drawn.corner)
        assertTrue(canvas.only<DrawCall.Rectangle>().isEmpty(), "and not a flat box as well")
    }

    @Test
    fun `padding before the gradient paints it inside the padding`() = uiTest {
        Box(Modifier.testTag("panel").size(100f, 60f).padding(8f).background(health))
    }.use { ui ->
        val node = ui.node("panel").boundsInRoot
        assertEquals(
            Rect.of(node.left + 8f, node.top + 8f, node.width - 16f, node.height - 16f),
            ui.gradients().single().rect,
        )
    }

    @Test
    fun `a gradient panel inside another draws over it in its own box`() {
        val sky = Brush.vertical(Colour.rgb(0x3A6EA5), Colour.rgb(0x1B2A41))
        uiTest {
            Box(Modifier.testTag("outer").size(200f, 200f).background(sky).padding(20f)) {
                Box(Modifier.testTag("inner").size(60f, 30f).background(health, corner = 4f))
            }
        }.use { ui ->
            val drawn = ui.gradients()

            assertEquals(listOf(sky, health), drawn.map { it.brush }, "the parent first, the child over it")
            assertEquals(ui.node("outer").boundsInRoot, drawn[0].rect)
            assertEquals(ui.node("inner").boundsInRoot, drawn[1].rect)
        }
    }

    @Test
    fun `hovering a panel swaps its gradient and leaving swaps it back`() {
        val cold = Brush.vertical(Colour.rgb(0x1B2A41), Colour.rgb(0x3A6EA5))
        val hot = Brush.radial(Colour.White, Colour.rgb(0x3A6EA5))
        uiTest {
            val state = remember { InteractionState() }
            Box(
                Modifier.testTag("card").size(160f, 90f).interaction(state)
                    .background(if (state.isHovered) hot else cold, corner = 8f),
            )
        }.use { ui ->
            assertEquals(cold, ui.gradients().single().brush, "at rest")

            ui.moveTo("card")
            assertEquals(hot, ui.gradients().single().brush, "under the pointer")

            ui.moveTo(Offset(600f, 600f))
            assertEquals(cold, ui.gradients().single().brush, "after the pointer left")
        }
    }

    @Test
    fun `a key a pad button and a click each drain a health bar that stays red at its tip`() = uiTest {
        var left by remember { mutableStateOf(1f) }
        Column {
            Button("HIT", onClick = { left -= 0.25f }, initialFocus = true, modifier = Modifier.testTag("hit"))
            Box(Modifier.testTag("bar").size(200f * left, 20f).background(health, corner = 10f))
        }
    }.use { ui ->
        val full = ui.gradients().single().rect
        assertEquals(200f, full.width)

        ui.key(Key.Enter)
        assertEquals(150f, ui.gradients().single().rect.width, "the keyboard took a quarter")

        ui.pad(GamepadButton.South)
        assertEquals(100f, ui.gradients().single().rect.width, "the pad took another")

        ui.click("hit")
        val hurt = ui.gradients().single()
        assertEquals(50f, hurt.rect.width, "and the mouse a third")
        assertEquals(full.left, hurt.rect.left, "from the right, not the left")
        assertEquals(red, hurt.brush.colourAt(hurt.rect.right, hurt.rect.centre.y, hurt.rect), "still red at the tip")
        assertEquals(green, hurt.brush.colourAt(hurt.rect.left, hurt.rect.centre.y, hurt.rect), "still green at the root")
    }

    @Test
    fun `an animated bar draws its gradient at every width it passes through`() {
        val target = mutableStateOf(1f)
        uiTest {
            val shown by animateFloatAsState(target.value)
            Box(Modifier.testTag("bar").size(200f * shown, 20f).background(health))
        }.use { ui ->
            target.value = 0.2f

            // Frame by frame, without settling, so the widths in between are seen.
            val widths = mutableListOf<Float>()
            val brushes = mutableSetOf<Brush>()
            repeat(120) {
                val drawn = ui.gradients().single()
                widths += drawn.rect.width
                brushes += drawn.brush
            }

            assertTrue(widths.any { it < 200f && it > 40f }, "it passed through widths in between: $widths")
            assertEquals(40f, widths.last(), 0.5f, "and came to rest at a fifth")
            assertEquals(setOf(health), brushes, "the same gradient every frame")
        }
    }

    @Test
    fun `swapping a gradient for a flat colour leaves no gradient behind`() = uiTest {
        var flat by remember { mutableStateOf(false) }
        Column {
            Button("SWAP", onClick = { flat = !flat }, modifier = Modifier.testTag("swap"))
            Box(Modifier.testTag("panel").size(100f, 40f).then(if (flat) Modifier.background(red) else Modifier.background(health)))
        }
    }.use { ui ->
        assertEquals(1, ui.gradients().size)

        ui.click("swap")
        val canvas = ui.frame()
        assertTrue(canvas.only<DrawCall.GradientRectangle>().isEmpty(), "the gradient is gone")
        assertEquals(red, canvas.only<DrawCall.Rectangle>().single { it.rect == ui.node("panel").boundsInRoot }.colour)

        ui.click("swap")
        assertEquals(health, ui.gradients().single().brush, "and back again")
    }

    @Test
    fun `a gradient rebuilt by recomposition is the same modifier so the node is left alone`() = uiTest {
        var clicks by remember { mutableStateOf(0) }
        Column {
            Button("TAP", onClick = { clicks++ }, modifier = Modifier.testTag("tap"))
            Text("$clicks", modifier = Modifier.testTag("count"))
            // A new brush object every time this runs, which is every click.
            Box(Modifier.testTag("panel").size(100f, 40f).background(Brush.vertical(green, red), corner = 4f))
        }
    }.use { ui ->
        ui.frame()
        val before = ui.node("panel").modifier

        ui.click("tap")
        ui.assertText("count", "1")

        assertSame(before, ui.node("panel").modifier, "an equal chain is not even stored")
        assertFalse(ui.render(), "and a still screen with a gradient on it has nothing to redo")
    }

    @Test
    fun `an alpha on the node fades its gradient with it`() = uiTest {
        Box(Modifier.size(80f, 20f).alpha(0.5f).background(health))
    }.use { ui ->
        assertEquals(0.5f, ui.gradients().single().alpha)
    }

    @Test
    fun `a canvas with no gradients draws the first colour in the right place`() {
        val old = OldBackend()
        val headless = HeadlessBackend()
        val backend = object : UiBackend by headless {
            override val canvas: UiCanvas get() = old
        }
        uiTest(backend = backend) {
            Box(Modifier.testTag("bar").size(120f, 40f).background(health, corner = 6f))
        }.use { ui ->
            ui.render()

            assertFalse(old.drawsGradients, "it says it cannot")
            val drawn = old.inner.only<DrawCall.Rectangle>().single()
            assertEquals(ui.node("bar").boundsInRoot, drawn.rect)
            assertEquals(green, drawn.colour, "the colour the gradient starts from")
            assertEquals(6f, drawn.corner)
        }
    }

    /** A canvas that implements only what [UiCanvas] demanded before gradients existed. */
    private class OldBackend(val inner: RecordingCanvas = RecordingCanvas()) : UiCanvas {
        override fun rect(rect: Rect, colour: Colour, corner: Float) = inner.rect(rect, colour, corner)
        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) =
            inner.border(rect, colour, width, corner)
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) =
            inner.shadow(rect, colour, spread, corner)
        override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) = inner.text(layout, x, y, colour)
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) =
            inner.image(texture, destination, tint, source)
        override fun fan(points: FloatArray, colour: Colour) = inner.fan(points, colour)
        override fun pushClip(rect: Rect) = inner.pushClip(rect)
        override fun popClip() = inner.popClip()
        override fun pushAlpha(alpha: Float) = inner.pushAlpha(alpha)
        override fun popAlpha() = inner.popAlpha()
        override fun raw(block: (Any) -> Unit) = inner.raw(block)
    }
}
